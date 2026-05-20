import jdk.jshell.*;
import java.io.*;
import java.util.*;

/**
 * Bridge between JavaScript and JShell running on CheerpJ. JS calls
 * {@link #init()} once, then {@link #eval(String)} per user input.
 *
 * CheerpJ-specific behaviors we work around (see {@code /probe} for live state):
 * <ul>
 *   <li>Stock {@code LocalExecutionControl} uses {@code Method.invoke} for the
 *       synthesized {@code do_it$} call. CheerpJ's WASM reflection drops the
 *       thrown {@code InvocationTargetException} — so {@code SnippetEvent
 *       .value()} and {@code .exception()} both come back null and user-code
 *       failures vanish. We swap in {@link jdk.jshell.execution.MhExecutionControl}
 *       which invokes via {@link java.lang.invoke.MethodHandle#invokeWithArguments};
 *       that path propagates the exception correctly and JShell wires up the
 *       SnippetEvent normally.</li>
 *   <li>{@code shell.close() + new JShell.builder().build()} is hardcoded to
 *       ~15 cycles per page; after that, javac's {@code Names.Table} corrupts.
 *       Soft reset (drop snippets, keep JShell alive) bypasses this; hard
 *       resets are budgeted at 14, see {@link #reset}.</li>
 *   <li>Stdout is routed to a {@code #console} DOM element, not the Java
 *       {@code PrintStream}. {@link SwitchOutputStream} captures when
 *       {@code capturing=true}; JS reads both sources.</li>
 *   <li>Two remaining CheerpJ WASM-opcode bugs we can't easily fix at the
 *       bridge level (documented as canary tests in {@code /test-errors}):
 *       {@code IREM} with zero divisor returns 0 instead of trapping; {@code
 *       NEWARRAY} with negative size throws {@code ArithmeticException} instead
 *       of {@code NegativeArraySizeException}. JVM-thrown exceptions also tend
 *       to come through with a null message.</li>
 * </ul>
 */
public class JShellBridge {

    /** Real jshell's feedback prefix for system messages (errors, exceptions, created/replaced/modified). */
    private static final String JSHELL_PREFIX = "|  ";

    /** Default auto-imports — same set as real jshell's DEFAULT startup. Survives soft reset. */
    private static final java.util.Set<String> DEFAULT_IMPORT_NAMES = java.util.Set.of(
        "java.io.*",
        "java.math.*",
        "java.net.*",
        "java.nio.file.*",
        "java.util.*",
        "java.util.concurrent.*",
        "java.util.function.*",
        "java.util.prefs.*",
        "java.util.regex.*",
        "java.util.stream.*"
    );

    private static JShell shell;
    private static SourceCodeAnalysis sca;

    // Output capture state — shared with SwitchOutputStream
    static PrintStream realOut;
    static boolean capturing = false;
    static ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();

    /**
     * Toggleable output stream: when {@code capturing}, writes go to
     * {@link #outputBuffer} (UTF-8 byte-safe); otherwise forwarded to
     * {@code realOut}. Named class — anonymous would generate a separate
     * {@code JShellBridge$1.class} that CheerpJ would need to load explicitly.
     */
    static class SwitchOutputStream extends OutputStream {
        public void write(int b) {
            if (capturing) outputBuffer.write(b);
            else if (realOut != null) realOut.write(b);
        }
        public void write(byte[] b, int off, int len) {
            if (capturing) {
                outputBuffer.write(b, off, len);
            } else if (realOut != null) realOut.write(b, off, len);
        }
        public void flush() { if (realOut != null) realOut.flush(); }
    }

    private static final PrintStream switchStream = new PrintStream(new SwitchOutputStream());

    /** Append UTF-8 bytes to {@link #outputBuffer}. */
    private static void bufferWrite(String s) {
        try { outputBuffer.write(s.getBytes("UTF-8")); } catch (Exception e) { /* ignore */ }
    }

    /** One-time JShell initialization. JS calls this once before any {@link #eval}. */
    public static String init() {
        try {
            realOut = System.out;
            System.setOut(switchStream);
            System.setErr(switchStream);
            shell = JShell.builder()
                // Custom ExecutionControl: invokes do_it$ via MethodHandle so
                // user exceptions propagate to SnippetEvent.exception() (CheerpJ
                // drops them through Method.invoke). See MhExecutionControl.
                .executionEngine(new jdk.jshell.execution.MhExecutionControl.Provider(),
                    java.util.Map.of())
                .out(switchStream)         // JShell execution engine output
                .err(switchStream)         // JShell execution engine errors
                .build();
            sca = shell.sourceCodeAnalysis();
            initFormatter();
            return "OK";
        } catch (Throwable t) {
            if (realOut != null) System.setOut(realOut);
            return "ERROR: " + t.toString();
        }
    }

    /** Install the bridge's default auto-imports (same set as real jshell's
     *  DEFAULT startup). The earlier {@code __safe}/{@code __threw}/{@code __trigger}
     *  helpers are gone — MhExecutionControl surfaces user exceptions via
     *  {@code SnippetEvent.exception()} natively. */
    private static void initFormatter() {
        for (String pkg : DEFAULT_IMPORT_NAMES) {
            shell.eval("import " + pkg + ";");
        }
    }

    /**
     * Evaluate user input. Multi-statement input is split via
     * {@link SourceCodeAnalysis}; each snippet is dispatched in two passes:
     * <ol>
     *   <li>{@link #tryEvalAsExpression} — wraps the snippet as
     *       {@code var $N = (expr);} so the value is captured as a real
     *       top-level var the user can reference. Returns false when the
     *       input isn't a wrappable expression (declarations, control flow,
     *       imports, throws, void method calls).</li>
     *   <li>{@link #evalOneSnippet} — handles everything tier 1 rejected.
     *       Runtime exceptions surface via {@code SnippetEvent.exception()}
     *       (powered by {@link jdk.jshell.execution.MhExecutionControl}).</li>
     * </ol>
     * Errors are prefixed with {@code @@ERR@@} per line so the JS layer can
     * style them distinctly.
     */
    public static String eval(String cellCode) {
        if (shell == null) return "ERROR: JShell not initialized";
        noteEvalCall(); // bump evals-since-last-hard counter for hybrid reset
        try {
            outputBuffer.reset();
            capturing = true;
            StringBuilder errors = new StringBuilder();

            // For Shift+Enter multi-line input, infer per-line semicolons so
            // analyzeCompletion can split the snippets correctly. Lines inside
            // braces (class/method bodies) are left untouched.
            String processed = preprocessMultiline(cellCode);

            String remaining = processed;
            while (remaining != null && !remaining.trim().isEmpty()) {
                SourceCodeAnalysis.CompletionInfo ci = sca.analyzeCompletion(remaining);
                SourceCodeAnalysis.Completeness comp = ci.completeness();

                if (comp == SourceCodeAnalysis.Completeness.EMPTY) break;

                if (comp == SourceCodeAnalysis.Completeness.COMPLETE
                    || comp == SourceCodeAnalysis.Completeness.COMPLETE_WITH_SEMI
                    || comp == SourceCodeAnalysis.Completeness.CONSIDERED_INCOMPLETE) {

                    String src = ci.source();
                    String srcTrimmed = src.trim().replaceAll(";$", "").trim();

                    // Try as an expression first (captures value as $N); if that
                    // path rejects (declarations, statements, control flow,
                    // imports, throws), fall through to evalOneSnippet, which
                    // also handles runtime exceptions via SnippetEvent.exception()
                    // — surfaced by MhExecutionControl's MethodHandle invoke.
                    if (!tryEvalAsExpression(srcTrimmed, errors)) {
                        evalOneSnippet(src, errors);
                    }

                    remaining = ci.remaining();
                } else {
                    // DEFINITELY_INCOMPLETE / UNKNOWN — report and stop
                    errors.append("Incomplete input: ").append(remaining.trim()).append('\n');
                    break;
                }
            }

            capturing = false;
            switchStream.flush();
            String output = outputBuffer.toString("UTF-8");
            if (errors.length() > 0) {
                // Prefix each error line with @@ERR@@ so JS can color them separately
                StringBuilder combined = new StringBuilder();
                for (String line : errors.toString().trim().split("\n")) {
                    combined.append("@@ERR@@").append(line).append("\n");
                }
                if (!output.isEmpty()) combined.append(output);
                return combined.toString().trim();
            }
            return output;
        } catch (Throwable t) {
            capturing = false;
            return "ERROR: " + t.toString();
        }
    }

    /**
     * Buffer multi-line input until each segment is a complete snippet, then
     * inject a trailing semicolon for COMPLETE_WITH_SEMI segments. Class and
     * method bodies are left alone.
     */
    private static String preprocessMultiline(String input) {
        if (!input.contains("\n")) return input;

        String[] lines = input.split("\n");
        StringBuilder result = new StringBuilder();
        StringBuilder buffer = new StringBuilder();

        for (String line : lines) {
            if (buffer.length() > 0) buffer.append("\n");
            buffer.append(line);

            SourceCodeAnalysis.CompletionInfo ci = sca.analyzeCompletion(buffer.toString());
            SourceCodeAnalysis.Completeness comp = ci.completeness();

            if (comp == SourceCodeAnalysis.Completeness.COMPLETE) {
                // Already complete. Only add ; if NOT a block declaration (class/method/etc)
                // Array inits like new String[]{"x","y"} end with } but need ; to separate
                String buf = buffer.toString().trim();
                boolean isBlockDecl = buf.matches("(?s)^(class|interface|enum|record|abstract|void|public|private|protected|static|default|synchronized)\\b.*")
                    || buf.matches("(?s)^[a-zA-Z_$][\\w$]*\\s*(<.*>)?\\s+[a-zA-Z_$][\\w$]*\\s*\\(.*"); // method signature
                if (buf.endsWith(";") || isBlockDecl) {
                    result.append(buffer).append("\n");
                } else {
                    result.append(buffer).append(";\n");
                }
                buffer.setLength(0);
            } else if (comp == SourceCodeAnalysis.Completeness.COMPLETE_WITH_SEMI) {
                // Would be complete with ; — add it
                result.append(buffer).append(";\n");
                buffer.setLength(0);
            }
            // CONSIDERED_INCOMPLETE, DEFINITELY_INCOMPLETE, etc. — keep buffering
        }

        // Remaining buffer (last expression/statement)
        if (buffer.length() > 0) {
            result.append(buffer);
        }

        return result.toString();
    }

    private static int scratchCounter = 0;

    /**
     * Detect source that clearly isn't an expression: imports, statement
     * keywords, typed variable declarations, method/class declarations.
     * Used to short-circuit {@link #tryEvalAsExpression} — wrapping these
     * as {@code var $N = (src);} would just generate a javac error and
     * waste a compile cycle before falling through to {@link #evalOneSnippet}.
     */
    private static boolean looksLikeDeclaration(String expr) {
        String s = expr.trim().replaceAll(";$", "").trim();
        if (s.isEmpty()) return false;

        // Leading keyword = not an expression
        if (s.matches("(?s)^(import|class|interface|enum|record|abstract|public|private|"
            + "protected|static|final|default|synchronized|void|for|if|while|do|switch|try|"
            + "throw|return|assert|continue|break|yield)\\b.*"))
            return true;

        // Typed variable declaration: `Type[<generics>][[]] name[[]] = ...`
        //   int x = 5, var y = ..., Function<Integer,Integer> f = ...
        //   Excludes plain `x = 10` (single identifier before =).
        if (s.matches("(?s)^[a-zA-Z_$][\\w$.]*(\\s*<[^<>]*(<[^<>]*>[^<>]*)*>)?"
            + "(\\s*\\[\\s*\\])*\\s+[a-zA-Z_$][\\w$]*(\\s*\\[\\s*\\])*\\s*=(?!=).*"))
            return true;

        // Method declaration: `Type[<generics>] name(...)` — excludes `new Foo(...)`.
        if (s.matches("(?s)^(?!new\\b)[a-zA-Z_$][\\w$.]*(\\s*<[^<>]*(<[^<>]*>[^<>]*)*>)?"
            + "\\s+[a-zA-Z_$][\\w$]*\\s*\\(.*"))
            return true;

        return false;
    }

    /**
     * Wrap a scratch expression as {@code var $N = (expr);} so the value
     * persists as {@code $N} for later use. Returns true if handled; false
     * to fall back to {@link #evalOneSnippet} for anything that isn't a
     * value-producing fresh expression — declarations, statements, void
     * method calls, bare-identifier reads, assignments. JShell handles
     * those last two natively as ExpressionSnippets (VAR_VALUE_SUBKIND /
     * ASSIGNMENT_SUBKIND) and they get the same {@code name ==> value}
     * display via {@link #evalOneSnippet}.
     */
    private static boolean tryEvalAsExpression(String expr, StringBuilder errors) {
        scratchCounter++; // Advance for every snippet — matches real jshell's $N counter.

        // Short-circuit anything that isn't an expression — wrapping a
        // declaration as `var $N = (decl);` would just generate a javac error.
        // Return false so caller falls through to evalOneSnippet.
        if (looksLikeDeclaration(expr)) return false;

        boolean isBareIdentifier = expr.matches("[a-zA-Z_$][a-zA-Z0-9_$]*")
            && !expr.equals("true") && !expr.equals("false") && !expr.equals("null");
        boolean isAssignment = expr.matches("[a-zA-Z_$][a-zA-Z0-9_$]*\\s*[+\\-*/%&|^]?=(?!=).*");

        // Bare reads (`x`) — let JShell handle natively as an
        // ExpressionSnippet (VAR_VALUE_SUBKIND); they then appear in /list
        // like real jshell.
        if (isBareIdentifier) return false;

        // Assignments stay wrapped (despite the /list-visibility cost) for a
        // boring reason: under our JShell library version, plain `x = 10`
        // becomes an ASSIGNMENT_SUBKIND ExpressionSnippet (clean to display)
        // but compound `x += 1` becomes a TEMP_VAR_EXPRESSION_SUBKIND
        // VarSnippet auto-named $<id>. Recovering the LHS for display in the
        // compound case would mean source-pattern-matching the snippet — a
        // hardcoded heuristic on top of a heuristic. Wrapping uniformly via
        // __sN lets the LHS fall out of one regex up front. Trade-off:
        // assignments don't get their own /list entries (they're hidden
        // under the __sN prefix).
        String displayName;
        String varName;
        if (isAssignment) {
            displayName = expr.replaceAll("\\s*[+\\-*/%&|^]?=.*", "");
            varName = "__s" + scratchCounter;
        } else {
            displayName = "$" + scratchCounter;
            varName = "$" + scratchCounter;
        }

        // Top-level `var $N = (expr);` so $N persists across snippets.
        // Any runtime exception in the initializer is captured by
        // MhExecutionControl and surfaced via SnippetEvent.exception().
        // REJECTED means the body wasn't a valid expression — caller falls
        // back to evalOneSnippet.
        String declaration = "var " + varName + " = (" + expr + ");";
        List<SnippetEvent> events;
        try {
            events = shell.eval(declaration);
        } catch (Throwable t) {
            errors.append("Exception: ").append(t.getClass().getSimpleName())
                .append(": ").append(t.getMessage()).append('\n');
            return true;
        }
        if (events.stream().noneMatch(e -> e.status() == Snippet.Status.VALID)) return false;

        // Read the formatted result straight from the SnippetEvent. With
        // MhExecutionControl, e.value() carries do_it$'s return — already
        // formatted by JShell's valueString (same routine that backs
        // shell.varValue). If the initializer threw, real jshell leaves $N
        // declared with its type default but doesn't emit the "$N ==> ..."
        // line — only the exception.
        for (SnippetEvent ev : events) {
            if (ev.snippet() == null || ev.causeSnippet() != null) continue;
            if (ev.exception() != null) {
                printException(ev.exception());
                return true;
            }
            if (ev.snippet() instanceof VarSnippet) {
                bufferWrite(displayName + " ==> " + ev.value() + "\n");
                return true;
            }
        }
        return true;
    }

    /** Render a captured user exception in jshell format. Shared by the
     *  tryEvalAsExpression and evalOneSnippet paths.
     *
     *  Format matches real jshell:
     *  <pre>
     *    |  Exception java.lang.ArithmeticException: / by zero
     *    |        at divide (#1:1)
     *    |        at (#2:1)
     *  </pre>
     *
     *  JShell translates the raw StackTraceElement[] to use {@code #<snippet-id>}
     *  as the className and the user-visible method name (with {@code do_it$}
     *  → top-level rendering as just the {@code at (#N:L)} form). We filter to
     *  snippet-mapped frames only; JVM/library frames don't appear in jshell. */
    private static void printException(jdk.jshell.JShellException je) {
        // Make sure the user-id map covers everything snippets currently knows
        // about — stack frames may reference snippets just created by this eval.
        syncUserIds();

        String exClass = je.getClass().getName();
        if (je instanceof jdk.jshell.EvalException ev) {
            exClass = ev.getExceptionClassName();
        }
        String msg = je.getLocalizedMessage();
        bufferWrite(JSHELL_PREFIX + "Exception " + exClass
            + (msg != null ? ": " + msg : "") + "\n");

        for (StackTraceElement frame : je.getStackTrace()) {
            // JShell stamps the snippet ID as "#N" — empirically it lands in
            // fileName under CheerpJ (real JDK puts it in className). Accept
            // either; skip frames where it's in neither (JVM/library frames
            // that jshell hides too).
            String className = frame.getClassName();
            String fileName = frame.getFileName();
            String rawId =
                (className != null && className.startsWith("#")) ? className :
                (fileName != null && fileName.startsWith("#")) ? fileName :
                null;
            if (rawId == null) continue;
            String method = frame.getMethodName();
            String methodPart = (method != null && !method.isEmpty() && !method.equals("do_it$"))
                ? method + " " : "";
            bufferWrite(JSHELL_PREFIX + "      at " + methodPart
                + "(" + toUserSnippetId(rawId) + ":" + frame.getLineNumber() + ")\n");
        }
    }

    /** Translate JShell's internal snippet id ("#26") into the user-facing
     *  one shown by /list ("#3"). Falls back to the raw id when the map
     *  doesn't know it (which would be an infrastructure snippet — those
     *  filter out of /list anyway). */
    private static String toUserSnippetId(String rawHashId) {
        String internal = rawHashId.substring(1);
        Integer user = userIdByRealId.get(internal);
        return user != null ? "#" + user : rawHashId;
    }

    /**
     * Evaluate one declaration/statement/control-flow snippet (anything
     * {@link #tryEvalAsExpression} couldn't handle as an expression). Renders
     * jshell-style feedback for created/replaced/modified entities, REJECTED
     * compile errors with caret pointer, and per-var values via
     * {@link JShell#varValue}.
     */
    private static void evalOneSnippet(String source, StringBuilder errors) {
        // Phase 1: eval and collect events
        List<SnippetEvent> events;
        try {
            events = shell.eval(source);
        } catch (Throwable t) {
            errors.append("Exception: ").append(t.toString()).append('\n');
            return;
        }

        // Phase 2: iterate events. Values come straight from e.value() —
        // MhExecutionControl makes that the do_it$ return, already formatted
        // by JShell's valueString (the same routine that backs shell.varValue).
        for (SnippetEvent e : events) {
            Snippet s = e.snippet();
            if (s == null || e.causeSnippet() != null) continue;  // Skip secondary (cascade) events

            // User-code runtime exception, captured by MhExecutionControl
            // (via MethodHandle.invokeWithArguments) and surfaced here as
            // event.exception(). With stock LocalExecutionControl this is
            // always null under CheerpJ.
            boolean threwInInit = e.exception() != null;
            if (threwInInit) printException(e.exception());

            Snippet.Status status = e.status();

            if (status == Snippet.Status.REJECTED) {
                // Compilation error — format like real JShell:
                //   |  Error:
                //   |  cannot find symbol
                //   |    symbol:   variable Pepe
                //   |  public /*a*/ Pepe; /**a**/
                //   |               ^--^
                // Each diagnostic gets its own block (jshell shows multiple
                // diagnostics for malformed input — `;` expected, illegal
                // start of expression, etc.).
                String src = s.source();
                // Drop trailing newline so it doesn't break our multi-line
                // formatting; we re-add explicit \n's per output line.
                while (src.endsWith("\n") || src.endsWith("\r")) src = src.substring(0, src.length() - 1);
                for (Diag d : shell.diagnostics(s).toList()) {
                    errors.append(JSHELL_PREFIX).append("Error:\n");
                    // javac's getMessage may be multi-line. Prefix every line
                    // with "|  "; javac's own 2-space sub-info indent then
                    // renders as 4 effective spaces, matching jshell. Skip
                    // "location:" sub-lines — verified that real jshell omits
                    // them universally in REPL context (hard errors at top
                    // level, and errors inside class bodies become forward-ref
                    // warnings before this code path runs).
                    for (String line : d.getMessage(Locale.ENGLISH).split("\n")) {
                        if (line.trim().startsWith("location:")) continue;
                        errors.append(JSHELL_PREFIX).append(line).append('\n');
                    }
                    errors.append(JSHELL_PREFIX).append(src).append('\n');
                    long start = d.getStartPosition(), end = d.getEndPosition();
                    if (start >= 0 && end >= start) {
                        errors.append(JSHELL_PREFIX);
                        for (long i = 0; i < start; i++) errors.append(' ');
                        errors.append('^');
                        long width = end - start;
                        if (width > 1) {
                            for (long i = 0; i < width - 2; i++) errors.append('-');
                            errors.append('^');
                        }
                        errors.append('\n');
                    }
                }

            } else if (status == Snippet.Status.VALID
                    || status == Snippet.Status.RECOVERABLE_DEFINED      // Forward references
                    || status == Snippet.Status.RECOVERABLE_NOT_DEFINED) {

                if (s instanceof VarSnippet vs) {
                    // Variable declaration. When the initializer threw, real
                    // jshell skips the "name ==> value" line (var still exists
                    // at its type default; the exception line stands alone).
                    if (!threwInInit) {
                        bufferWrite(vs.name() + " ==> " + e.value() + "\n");
                    }

                } else if (s instanceof ExpressionSnippet es) {
                    // VAR_VALUE_SUBKIND — bare ident read (`x`). Assignments
                    // are wrapped in tryEvalAsExpression and so don't reach
                    // here as ExpressionSnippets.
                    if (!threwInInit) {
                        String shown = es.source().trim().replaceAll(";$", "").trim();
                        bufferWrite(shown + " ==> " + e.value() + "\n");
                    }

                } else if (s instanceof MethodSnippet ms) {
                    boolean isNew = e.previousStatus() == Snippet.Status.NONEXISTENT;
                    // Methods say "modified" when redeclared (real JShell).
                    String msg = JSHELL_PREFIX + (isNew ? "created" : "modified")
                        + " method " + ms.name() + "(" + ms.parameterTypes() + ")";
                    // Forward-reference warning — match real JShell's grammar:
                    //   1 dep:   "until variable X is declared"
                    //   2 deps:  "until variable X, and variable Y are declared"
                    //   3+ deps: "until X, Y, and Z are declared"  (Oxford comma)
                    if (status == Snippet.Status.RECOVERABLE_DEFINED
                        || status == Snippet.Status.RECOVERABLE_NOT_DEFINED) {
                        List<String> deps = shell.unresolvedDependencies(ms).toList();
                        if (!deps.isEmpty()) {
                            String list;
                            if (deps.size() == 1) {
                                list = deps.get(0);
                            } else if (deps.size() == 2) {
                                list = deps.get(0) + ", and " + deps.get(1);
                            } else {
                                list = String.join(", ", deps.subList(0, deps.size() - 1))
                                     + ", and " + deps.get(deps.size() - 1);
                            }
                            String verb = deps.size() == 1 ? "is" : "are";
                            msg += ", however, it cannot be invoked until " + list + " " + verb + " declared";
                        }
                    }
                    bufferWrite(msg + "\n");

                } else if (s instanceof TypeDeclSnippet ts) {
                    boolean isNew = e.previousStatus() == Snippet.Status.NONEXISTENT;
                    String kind = s.subKind() == Snippet.SubKind.CLASS_SUBKIND ? "class"
                        : s.subKind() == Snippet.SubKind.INTERFACE_SUBKIND ? "interface"
                        : s.subKind() == Snippet.SubKind.ENUM_SUBKIND ? "enum"
                        : s.subKind() == Snippet.SubKind.RECORD_SUBKIND ? "record" : "type";
                    // Type declarations say "replaced" when redeclared (real JShell).
                    bufferWrite(JSHELL_PREFIX + (isNew ? "created" : "replaced")
                        + " " + kind + " " + ts.name() + "\n");
                }
                // ImportSnippet, StatementSnippet — no auto-display needed
            }
        }

    }

    /**
     * Diagnostic tool — returns detailed info about what JShell produces for given input.
     * Accessible via /diag command in the console. Shows: snippet class, kind, subKind,
     * status, value(), varValue(), and all session variables.
     */
    public static String diagnose(String code) {
        if (shell == null) return "ERROR: JShell not initialized";
        try {
            // Add ; if missing (like normal eval path via COMPLETE_WITH_SEMI)
            String src = code.trim();
            if (!src.endsWith(";") && !src.endsWith("}")) src = src + ";";
            StringBuilder sb = new StringBuilder();
            List<SnippetEvent> events = shell.eval(src);

            sb.append("Input: ").append(code).append('\n');
            sb.append("Events: ").append(events.size()).append('\n');

            for (int i = 0; i < events.size(); i++) {
                SnippetEvent e = events.get(i);
                Snippet s = e.snippet();
                sb.append("\n--- Event ").append(i).append(" ---\n");
                sb.append("  status: ").append(e.status()).append('\n');
                sb.append("  value(): '").append(e.value()).append("'\n");
                sb.append("  causeSnippet: ").append(e.causeSnippet()).append('\n');

                if (s != null) {
                    sb.append("  snippet.class: ").append(s.getClass().getSimpleName()).append('\n');
                    sb.append("  snippet.kind: ").append(s.kind()).append('\n');
                    sb.append("  snippet.subKind: ").append(s.subKind()).append('\n');
                    sb.append("  snippet.source: '").append(s.source()).append("'\n");

                    if (s instanceof VarSnippet vs) {
                        sb.append("  varSnippet.name: ").append(vs.name()).append('\n');
                        sb.append("  varSnippet.typeName: ").append(vs.typeName()).append('\n');
                        try {
                            String vv = shell.varValue(vs);
                            sb.append("  shell.varValue(): '").append(vv).append("'\n");
                        } catch (Throwable t) {
                            sb.append("  shell.varValue() ERROR: ").append(t).append('\n');
                        }
                    }
                }
            }

            sb.append("\n--- Session variables ---\n");
            for (VarSnippet vs : shell.variables().toList()) {
                String val = "?";
                try { val = shell.varValue(vs); } catch (Throwable t) { val = "ERR:" + t; }
                sb.append("  ").append(vs.typeName()).append(' ').append(vs.name())
                    .append(" = '").append(val).append("'\n");
            }

            return sb.toString();
        } catch (Throwable t) {
            return "ERROR: " + t.toString();
        }
    }

    // ============================================================
    // Snippet listing commands — /list, /vars, /methods, /types, /imports, /drop.
    //
    // All use shell.snippets()/variables()/methods()/types()/imports() and
    // shell.varValue() — the same paths our eval feedback uses, which are
    // known to work under CheerpJ. Output format matches real jshell's
    // --feedback normal mode.
    //
    // Bridge infrastructure (snippets whose name starts with "__") and our
    // DEFAULT_IMPORT_NAMES are filtered out so the user sees only their own
    // state, except in /imports which mirrors real jshell by including the
    // startup imports.
    // ============================================================

    private static boolean isHiddenInfra(Snippet s) {
        if (s instanceof VarSnippet vs) return vs.name().startsWith("__");
        if (s instanceof MethodSnippet ms) return ms.name().startsWith("__");
        // Catch any statement snippets whose source is purely a call into
        // bridge infrastructure (e.g. leftover __trigger() from older runs).
        if (s instanceof StatementSnippet) {
            String src = s.source().trim();
            return src.startsWith("__");
        }
        return false;
    }

    /** True when a snippet should appear in user-facing /list output. */
    private static boolean isUserVisible(Snippet s) {
        if (isHiddenInfra(s)) return false;
        if (s instanceof ImportSnippet is && DEFAULT_IMPORT_NAMES.contains(is.name())) return false;
        return true;
    }

    // ============================================================
    // User-facing snippet IDs.
    //
    // jshell's real Snippet.id() starts at 1 and increments per eval —
    // including the bridge's default-import setup — so a user's first
    // input lands at ~11. We shadow real IDs with our own monotonic
    // counter that only advances for user-visible snippets, giving
    // 1, 2, 3 in /list output.
    //
    // IDs are assigned lazily and once-only when a snippet is first seen
    // active by syncUserIds(). Dropping or overwriting a snippet does not
    // reuse its user ID, matching real jshell. A reset() clears the map
    // and the counter restarts at 1.
    // ============================================================

    private static final Map<String, Integer> userIdByRealId = new LinkedHashMap<>();
    private static int nextUserId = 1;

    private static void syncUserIds() {
        shell.snippets()
            .filter(s -> shell.status(s).isActive())
            .filter(JShellBridge::isUserVisible)
            .forEach(s -> userIdByRealId.computeIfAbsent(s.id(), k -> nextUserId++));
    }

    private static void clearUserIdMap() {
        userIdByRealId.clear();
        nextUserId = 1;
    }

    /** /list — user-facing id + source, like {@code "   1 : int x = 2"}. */
    public static String listAll() {
        if (shell == null) return "ERROR: JShell not initialized";
        syncUserIds();
        StringBuilder sb = new StringBuilder();
        shell.snippets()
            .filter(s -> shell.status(s).isActive())
            .filter(JShellBridge::isUserVisible)
            .forEach(s -> {
                Integer uid = userIdByRealId.get(s.id());
                String src = unwrapScratchSource(s.source());
                while (src.endsWith("\n") || src.endsWith("\r")) src = src.substring(0, src.length() - 1);
                sb.append(String.format("%4d : %s%n", uid != null ? uid : 0, src));
            });
        return sb.toString();
    }

    /** Pattern: `var <scratch-name> = (EXPR);` where scratch-name is one of
     *  our bridge-internal names ($N or __sN). Lets /list show the user's
     *  original expression instead of our wrap. Group 1 = the expression. */
    private static final java.util.regex.Pattern SCRATCH_WRAP =
        java.util.regex.Pattern.compile("^var (?:\\$\\d+|__s\\d+) = \\((.*)\\);\\s*$",
            java.util.regex.Pattern.DOTALL);

    private static String unwrapScratchSource(String src) {
        if (src == null) return src;
        java.util.regex.Matcher m = SCRATCH_WRAP.matcher(src);
        return m.matches() ? m.group(1) : src;
    }

    /** /vars — {@code "|    <type> <name> = <value>"} per active var. */
    public static String listVars() {
        if (shell == null) return "ERROR: JShell not initialized";
        StringBuilder sb = new StringBuilder();
        shell.variables()
            .filter(vs -> shell.status(vs).isActive())
            .filter(vs -> !vs.name().startsWith("__"))
            .forEach(vs -> {
                String val;
                try { val = shell.varValue(vs); }
                catch (Throwable t) { val = "?"; }
                sb.append("|    ").append(vs.typeName()).append(' ').append(vs.name())
                  .append(" = ").append(val).append('\n');
            });
        return sb.toString();
    }

    /** /methods — {@code "|    <returnType> <name>(<paramTypes>)"} per active method. */
    public static String listMethods() {
        if (shell == null) return "ERROR: JShell not initialized";
        StringBuilder sb = new StringBuilder();
        shell.methods()
            .filter(ms -> shell.status(ms).isActive())
            .filter(ms -> !ms.name().startsWith("__"))
            .forEach(ms -> {
                // MethodSnippet.signature() format is `(paramTypes)returnType`
                // (e.g. `()void`, `(int,int)int`, `(String[])String`). Pull
                // the return type from after the closing paren.
                String sig = ms.signature();
                int close = sig.lastIndexOf(')');
                String returnType = close >= 0 ? sig.substring(close + 1) : sig;
                sb.append("|    ").append(returnType)
                  .append(' ').append(ms.name())
                  .append('(').append(ms.parameterTypes()).append(")\n");
            });
        return sb.toString();
    }

    /** /types — {@code "|    class Foo"}, {@code "|    interface Bar"}, etc. */
    public static String listTypes() {
        if (shell == null) return "ERROR: JShell not initialized";
        StringBuilder sb = new StringBuilder();
        shell.types()
            .filter(ts -> shell.status(ts).isActive())
            .forEach(ts -> {
                String kind = ts.subKind() == Snippet.SubKind.CLASS_SUBKIND ? "class"
                    : ts.subKind() == Snippet.SubKind.INTERFACE_SUBKIND ? "interface"
                    : ts.subKind() == Snippet.SubKind.ENUM_SUBKIND ? "enum"
                    : ts.subKind() == Snippet.SubKind.RECORD_SUBKIND ? "record" : "type";
                sb.append("|    ").append(kind).append(' ').append(ts.name()).append('\n');
            });
        return sb.toString();
    }

    /** /imports — including our DEFAULT_IMPORT_NAMES, matching real jshell which lists its startup imports. */
    public static String listImports() {
        if (shell == null) return "ERROR: JShell not initialized";
        StringBuilder sb = new StringBuilder();
        shell.imports()
            .filter(is -> shell.status(is).isActive())
            .forEach(is -> {
                String src = is.source().trim();
                while (src.endsWith(";")) src = src.substring(0, src.length() - 1).trim();
                sb.append("|    ").append(src).append('\n');
            });
        return sb.toString();
    }

    /**
     * /drop &lt;id-or-name&gt; — drop a single matching snippet. Numeric arg
     * is interpreted as a user-facing id (the one shown by /list), then
     * falls back to variable/method/type name.
     */
    public static String dropSnippet(String arg) {
        if (shell == null) return "ERROR: JShell not initialized";
        if (arg == null || arg.trim().isEmpty()) {
            // Match real jshell's two-line message verbatim.
            return JSHELL_PREFIX + "In the /drop argument, please specify an import, variable, method, or class to drop.\n"
                + "Specify by ID or name. Use /list to see IDs. Use /reset to reset all state.\n";
        }
        String key = arg.trim();
        syncUserIds();

        Snippet target = null;
        try {
            int uid = Integer.parseInt(key);
            String realId = userIdByRealId.entrySet().stream()
                .filter(e -> e.getValue() == uid)
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
            if (realId != null) {
                target = shell.snippets()
                    .filter(s -> shell.status(s).isActive())
                    .filter(s -> s.id().equals(realId))
                    .findFirst().orElse(null);
            }
        } catch (NumberFormatException ignore) { /* not numeric — try name */ }

        if (target == null) {
            target = shell.snippets()
                .filter(s -> shell.status(s).isActive())
                .filter(s -> !isHiddenInfra(s))
                .filter(s -> {
                    if (s instanceof VarSnippet vs) return vs.name().equals(key);
                    if (s instanceof MethodSnippet ms) return ms.name().equals(key);
                    if (s instanceof TypeDeclSnippet ts) return ts.name().equals(key);
                    return false;
                })
                .findFirst().orElse(null);
        }

        if (target == null) {
            // Real jshell prints a second hint line.
            return JSHELL_PREFIX + "No such snippet: " + key + "\n"
                + JSHELL_PREFIX + "See /types, /methods, /vars, or /list\n";
        }

        try { shell.drop(target); }
        catch (Throwable t) { return JSHELL_PREFIX + "Error dropping " + key + ": " + t.getMessage() + "\n"; }

        // Real jshell drop feedback: "|  dropped variable x" / "|  dropped method add(int,int)" / "|  dropped class Foo"
        if (target instanceof VarSnippet vs) {
            return JSHELL_PREFIX + "dropped variable " + vs.name() + "\n";
        }
        if (target instanceof MethodSnippet ms) {
            return JSHELL_PREFIX + "dropped method " + ms.name() + "(" + ms.parameterTypes() + ")\n";
        }
        if (target instanceof TypeDeclSnippet ts) {
            String kind = ts.subKind() == Snippet.SubKind.CLASS_SUBKIND ? "class"
                : ts.subKind() == Snippet.SubKind.INTERFACE_SUBKIND ? "interface"
                : ts.subKind() == Snippet.SubKind.ENUM_SUBKIND ? "enum"
                : ts.subKind() == Snippet.SubKind.RECORD_SUBKIND ? "record" : "type";
            return JSHELL_PREFIX + "dropped " + kind + " " + ts.name() + "\n";
        }
        return JSHELL_PREFIX + "dropped " + key + "\n";
    }

    // ============================================================
    // /probe — consolidated diagnostic.
    //
    // Verifies the bridge's core invariants on the running CheerpJ build:
    //   - shell.varValue() formats every primitive/reference type correctly
    //   - SnippetEvent.value() and .exception() reach us (MhExecutionControl)
    //   - hybrid reset budget state (how many hard resets are left)
    //
    // The probe cleans up after itself — all snippets it evaluates are
    // dropped before returning so /list and /vars stay user-only.
    // ============================================================
    public static String probe() {
        if (shell == null) return "ERROR: JShell not initialized";
        StringBuilder sb = new StringBuilder();
        sb.append("=== JShell-on-CheerpJ probe ===\n");
        List<Snippet> probeSnippets = new ArrayList<>();
        try {
            // --- Hybrid reset budget ---
            sb.append("\n[1] Hybrid reset budget\n");
            sb.append("    hard resets used: ").append(hardResetCount).append("/").append(MAX_HARD_RESETS).append('\n');
            sb.append("    evals since last hard: ").append(evalsSinceLastHard)
              .append(" (auto-upgrade on next reset() at ").append(EVALS_BEFORE_HARD).append(")\n");

            // --- shell.varValue() readback by type ---
            sb.append("\n[2] shell.varValue() readback (must match real JShell display)\n");
            String[] decls = {
                "byte    __px_byte = 127",
                "int     __px_int  = 42",
                "long    __px_long = 12345678901L",
                "double  __px_dbl  = 3.14",
                "boolean __px_bool = true",
                "char    __px_char = 'X'",
                "String  __px_str  = \"hello\"",
                "Object  __px_null = null",
                "int[]   __px_arr  = {1, 2, 3}",
                "java.util.List<Integer> __px_list = java.util.List.of(1,2,3)",
                "Class<?> __px_cls = String.class"
            };
            for (String d : decls) {
                for (SnippetEvent e : shell.eval(d + ";")) {
                    if (e.snippet() != null && e.causeSnippet() == null) probeSnippets.add(e.snippet());
                }
            }
            for (VarSnippet vs : shell.variables().toList()) {
                if (!vs.name().startsWith("__px_")) continue;
                String val;
                try { val = shell.varValue(vs); }
                catch (Throwable t) { val = "ERR " + t.getClass().getSimpleName(); }
                sb.append(String.format("    %-32s -> %s%n", vs.typeName() + " " + vs.name(), val));
            }

            // --- SnippetEvent value/exception (MhExecutionControl) ---
            sb.append("\n[3] SnippetEvent (MhExecutionControl effect)\n");
            for (SnippetEvent e : shell.eval("40 + 2;")) {
                if (e.snippet() == null || e.causeSnippet() != null) continue;
                probeSnippets.add(e.snippet());
                sb.append("    eval('40 + 2').value():       ")
                  .append(e.value() == null ? "null  ✗ MhExecutionControl NOT delivering value"
                                            : "'" + e.value() + "'  ✓ value() reaches us").append('\n');
            }
            for (SnippetEvent e : shell.eval("throw new RuntimeException(\"x-boom\");")) {
                if (e.snippet() == null || e.causeSnippet() != null) continue;
                probeSnippets.add(e.snippet());
                String exc;
                if (e.exception() == null) {
                    exc = "null  ✗ MhExecutionControl NOT delivering exception";
                } else {
                    String cls = e.exception().getClass().getSimpleName();
                    if (e.exception() instanceof jdk.jshell.EvalException ev) {
                        cls += " (cause: " + ev.getExceptionClassName() + ")";
                    }
                    exc = cls + "  ✓ exception() reaches us";
                }
                sb.append("    eval('throw...').exception(): ").append(exc).append('\n');
            }
        } catch (Throwable t) {
            sb.append("\nPROBE FAILED: ").append(t).append('\n');
        } finally {
            // Always drop probe-created snippets so /list stays user-only.
            for (Snippet s : probeSnippets) {
                try { shell.drop(s); } catch (Throwable ignore) {}
            }
        }
        return sb.toString();
    }

    // Hybrid reset budget: every 50 evals since the last hard reset, the next
    // reset() call is upgraded from soft to hard (close + new JShell) so that
    // accumulated compiled classes are actually unloaded. CheerpJ's cliff is
    // 15 close()+build() cycles per page; init() consumed 1 already, leaving
    // 14 budget. After 14 hard resets, all further resets are soft and the JS
    // side is told to ask the user to reload the page.
    private static int evalsSinceLastHard = 0;
    private static int hardResetCount = 0;
    private static final int EVALS_BEFORE_HARD = 20;
    private static final int MAX_HARD_RESETS = 14;

    /** Called from public eval() so reset() can decide soft-vs-hard. */
    static void noteEvalCall() { evalsSinceLastHard++; }

    /** Compact "N/MAX" budget snapshot for JS-side messaging. */
    public static String getHardResetState() {
        return hardResetCount + "/" + MAX_HARD_RESETS;
    }

    public static String reset() {
        try {
            scratchCounter = 0;
            clearUserIdMap();
            if (shell == null) {
                // First-time init path
                System.setOut(switchStream);
                System.setErr(switchStream);
                shell = JShell.builder()
                    .executionEngine(new jdk.jshell.execution.MhExecutionControl.Provider(),
                        java.util.Map.of())
                    .out(switchStream)
                    .err(switchStream)
                    .build();
                sca = shell.sourceCodeAnalysis();
                initFormatter();
                return "OK";
            }

            boolean wantsHard = evalsSinceLastHard >= EVALS_BEFORE_HARD;
            boolean canHard = hardResetCount < MAX_HARD_RESETS;

            if (wantsHard && canHard) {
                // Upgrade to a HARD reset to unload accumulated classes.
                JShell oldShell = shell;
                shell = null;
                sca = null;
                try { oldShell.close(); } catch (Throwable ignore) {}
                System.gc();
                Thread.yield();
                System.setOut(switchStream);
                System.setErr(switchStream);
                shell = JShell.builder()
                    .executionEngine(new jdk.jshell.execution.MhExecutionControl.Provider(),
                        java.util.Map.of())
                    .out(switchStream)
                    .err(switchStream)
                    .build();
                sca = shell.sourceCodeAnalysis();
                initFormatter();
                hardResetCount++;
                evalsSinceLastHard = 0;
                if (hardResetCount >= MAX_HARD_RESETS) {
                    return "@@RELOAD@@:OK (hard reset " + hardResetCount + "/" + MAX_HARD_RESETS
                         + " — last one available; please reload the page soon)";
                }
                return "OK (hard reset " + hardResetCount + "/" + MAX_HARD_RESETS + ")";
            }

            // Otherwise — soft reset: drop every active snippet (vars,
            // methods, types, expression/statement snippets, non-default
            // imports). shell.snippets() covers all snippet kinds; iterating
            // it once is cleaner than per-type loops and catches the
            // ExpressionSnippets / StatementSnippets that bare reads,
            // assignments, and control-flow statements now produce.
            for (Snippet s : shell.snippets().toList()) {
                if (s instanceof ImportSnippet is
                    && DEFAULT_IMPORT_NAMES.contains(is.name())) continue;
                try { shell.drop(s); } catch (Throwable ignore) {}
            }

            // If user wanted hard but budget is exhausted, signal the JS side.
            if (wantsHard && !canHard) {
                return "@@RELOAD@@:OK (soft only — hard-reset budget exhausted at "
                     + hardResetCount + "/" + MAX_HARD_RESETS
                     + ". Eval time will grow until you reload the page.)";
            }
            return "OK";
        } catch (Throwable t) {
            return "ERROR: " + t.toString();
        }
    }
}
