import jdk.jshell.*;
import java.io.*;
import java.util.*;

/**
 * Bridge between JavaScript and JShell running on CheerpJ. JS calls
 * {@link #init()} once, then {@link #eval(String)} per user input.
 *
 * CheerpJ-specific behaviors we work around (see {@code /probe} for live state):
 * <ul>
 *   <li>{@code SnippetEvent.value()} and {@code .exception()} always return
 *       null — runtime results don't cross the WASM boundary. We read values
 *       via {@link JShell#varValue} and catch exceptions inside compiled
 *       bytecode through the {@code __safe(Supplier)} helper.</li>
 *   <li>{@code shell.varValue} returns the type default (0/null/false)
 *       immediately after declaration — the synthetic class's {@code <clinit>}
 *       runs lazily. Any subsequent {@code shell.eval} flushes it, so we
 *       invoke a no-op {@code __trigger()} method (see {@link #triggerClinit}).</li>
 *   <li>{@code LocalExecutionControl} silently swallows runtime exceptions
 *       (e.g. {@code 1/0} returns 0). The {@code __safe} lambda's try/catch
 *       runs inside compiled bytecode and catches them properly.</li>
 *   <li>{@code shell.close() + new JShell.builder().build()} is hardcoded to
 *       ~15 cycles per page; after that, javac's {@code Names.Table} corrupts.
 *       Soft reset (drop snippets, keep JShell alive) bypasses this; hard
 *       resets are budgeted at 14, see {@link #reset}.</li>
 *   <li>Stdout is routed to a {@code #console} DOM element, not the Java
 *       {@code PrintStream}. {@link SwitchOutputStream} captures when
 *       {@code capturing=true}; JS reads both sources.</li>
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
                .executionEngine("local")  // Run snippets in-process (not via JDI)
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

    /**
     * Install the bridge's three helper snippets plus default auto-imports:
     * <ul>
     *   <li>{@code __threw} — flag set by {@code __safe} when its supplier throws.</li>
     *   <li>{@code __safe(Supplier&lt;T&gt;)} — runs the supplier inside compiled
     *       bytecode try/catch and prints exceptions in jshell format.</li>
     *   <li>{@code __trigger()} — void no-op called via {@link #triggerClinit}
     *       to flush pending {@code <clinit>}s. Must be a void method call —
     *       any value-producing statement (e.g. {@code x++;}) gets auto-named
     *       as {@code $N} by jshell, polluting the user's scratch namespace.</li>
     * </ul>
     */
    private static void initFormatter() {
        shell.eval("boolean __threw = false;");
        // Exception output: "|  Exception java.lang.X: msg" (or no ": msg" when null) — matches jshell.
        shell.eval(
            "<T> T __safe(java.util.function.Supplier<T> s) { "
            + "__threw = false; "
            + "try { return s.get(); } catch (Throwable __e) { "
            + "__threw = true; "
            + "System.out.println(\"" + JSHELL_PREFIX + "Exception \" + __e.getClass().getName() "
            + "+ (__e.getMessage() == null ? \"\" : \": \" + __e.getMessage())); "
            + "return null; } }"
        );
        shell.eval("void __trigger() {}");
        for (String pkg : DEFAULT_IMPORT_NAMES) {
            shell.eval("import " + pkg + ";");
        }
    }

    /**
     * Flush pending {@code <clinit>}s. Without this, {@link JShell#varValue}
     * on a freshly-declared variable returns the type default (0/null/false)
     * because CheerpJ runs reflection before the synthetic class's static
     * initializer fires.
     */
    private static void triggerClinit() {
        try { shell.eval("__trigger();"); } catch (Throwable ignore) {}
    }

    /** Look up the currently-VALID VarSnippet by name, or null if not found. */
    private static VarSnippet findVar(String name) {
        // Filter to VALID only — a redeclared var leaves an OVERWRITTEN snippet
        // behind; findFirst() could otherwise return the stale one.
        return shell.variables()
            .filter(vs -> vs.name().equals(name))
            .filter(vs -> shell.status(vs) == Snippet.Status.VALID)
            .findFirst().orElse(null);
    }

    /** Read a top-level var's formatted value via shell.varValue. */
    private static String readVarValue(String name) {
        VarSnippet vs = findVar(name);
        if (vs == null) return "(no VALID var named " + name + ")";
        try { return shell.varValue(vs); }
        catch (Throwable t) { return "(read error: " + t.getMessage() + ")"; }
    }

    /**
     * Evaluate user input. Multi-statement input is split via
     * {@link SourceCodeAnalysis}; each snippet is dispatched in three tiers:
     * <ol>
     *   <li>{@code throw} statements — wrapped in compiled-bytecode try/catch
     *       so the exception is reported (CheerpJ otherwise swallows it).</li>
     *   <li>Expressions — wrapped as {@code var $N = __safe(() -&gt; (expr))},
     *       which both captures the value as a real top-level var (so the user
     *       can reference {@code $N} later) and catches exceptions inside the
     *       supplier's compiled bytecode.</li>
     *   <li>Anything else — declarations, statements, control flow — handled
     *       directly by {@link #evalOneSnippet}.</li>
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

                    // Tier 1: throw statements — wrap in try/catch. Output
                    // format matches real JShell (see JSHELL_PREFIX).
                    if (srcTrimmed.startsWith("throw ")) {
                        shell.eval("try { " + src + " } catch (Throwable __e) { "
                            + "System.out.println(\"" + JSHELL_PREFIX + "Exception \" + __e.getClass().getName() "
                            + "+ (__e.getMessage() == null ? \"\" : \": \" + __e.getMessage())); }");
                    }
                    // Tier 2: try as expression with try/catch value capture
                    else if (!tryEvalAsExpression(srcTrimmed, errors)) {
                        // Tier 3: not an expression — declarations, statements, control flow
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
     *
     * These must not be wrapped as `var $N = __safe(() -> (src))` because
     * placing a non-expression inside a lambda body lets javac's
     * error-recovery path reach ConstFold, which can throw a javac
     * InternalError ("Exception during analyze - ArithmeticException")
     * and corrupt JShell's compiler state — breaking every subsequent
     * snippet that uses generics or lambdas.
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
     * Try to evaluate {@code expr} as an expression. Returns true on success;
     * false if jshell rejects it (caller falls back to {@link #evalOneSnippet}).
     *
     * <p>The display name pattern follows real jshell:
     * <pre>
     *   "x"       → "x ==> value"    (bare identifier)
     *   "x = 10"  → "x ==> value"    (assignment)
     *   "x + 5"   → "$N ==> value"   (scratch — N is the auto-counter)
     * </pre>
     */
    private static boolean tryEvalAsExpression(String expr, StringBuilder errors) {
        scratchCounter++; // Always advance, matching real jshell's $N counter (even for bare reads).

        // Skip the __safe wrap for anything that isn't an expression. Wrapping
        // a declaration inside a lambda body triggers bad javac error recovery
        // (see looksLikeDeclaration). Return false so caller falls through to
        // evalOneSnippet, which handles declarations natively.
        if (looksLikeDeclaration(expr)) return false;

        String displayName;
        String varName;
        boolean isBareIdentifier = expr.matches("[a-zA-Z_$][a-zA-Z0-9_$]*")
            && !expr.equals("true") && !expr.equals("false") && !expr.equals("null");
        boolean isAssignment = expr.matches("[a-zA-Z_$][a-zA-Z0-9_$]*\\s*[+\\-*/%&|^]?=(?!=).*");
        if (isBareIdentifier) {
            displayName = expr;       // "x ==> value"
            varName = "__s" + scratchCounter;
        } else if (isAssignment) {
            displayName = expr.replaceAll("\\s*[+\\-*/%&|^]?=.*", "");  // "x ==> value"
            varName = "__s" + scratchCounter;
        } else {
            // Scratch expression: name the real var "$N" so the user can reference
            // it in later snippets ($ is a legal Java identifier char).
            displayName = "$" + scratchCounter;  // "$N ==> value"
            varName = "$" + scratchCounter;
        }

        // Top-level `var $N = __safe(...)` so $N persists across snippets and
        // the lambda body's try/catch lives in compiled bytecode (CheerpJ
        // otherwise swallows runtime exceptions). REJECTED means the body
        // wasn't a valid expression — caller falls back to evalOneSnippet.
        String declaration = "var " + varName + " = __safe(() -> (" + expr + "));";
        List<SnippetEvent> events;
        try {
            events = shell.eval(declaration);
        } catch (Throwable t) {
            // javac throws InternalError here for compile-time-constant arithmetic
            // exceptions inside generic-method lambdas (e.g. `1/0`, `1%0`). Reporting
            // it without re-running raw avoids silently creating $N=0 in CheerpJ.
            errors.append("Exception: ").append(t.getClass().getSimpleName())
                .append(": ").append(t.getMessage()).append('\n');
            return true;
        }
        if (events.stream().noneMatch(e -> e.status() == Snippet.Status.VALID)) return false;

        triggerClinit();
        if (!"true".equals(readVarValue("__threw"))) {
            bufferWrite(displayName + " ==> " + readVarValue(varName) + "\n");
        }
        return true;
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

        // Phase 2: iterate events, collect what needs displaying
        List<VarSnippet> varsToShow = new ArrayList<>();
        List<ExpressionSnippet> exprsToShow = new ArrayList<>();

        for (SnippetEvent e : events) {
            Snippet s = e.snippet();
            if (s == null || e.causeSnippet() != null) continue;  // Skip secondary (cascade) events

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
                    // Variable declaration or temp var from expression
                    varsToShow.add(vs);

                } else if (s instanceof ExpressionSnippet es) {
                    // Bare variable reference (e.g., "x", "list") — VAR_VALUE_SUBKIND
                    exprsToShow.add(es);

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

        // Phase 3: emit "name ==> value" lines via shell.varValue.
        // One triggerClinit() call covers every var declared in this eval
        // (they share the same pending-init batch).

        if (!varsToShow.isEmpty() || !exprsToShow.isEmpty()) {
            triggerClinit();
        }

        for (VarSnippet vs : varsToShow) {
            try {
                bufferWrite(vs.name() + " ==> " + shell.varValue(vs) + "\n");
            } catch (Throwable t) {
                errors.append("Error reading ").append(vs.name()).append(": ").append(t).append('\n');
            }
        }

        // ExpressionSnippet: bare expression that produced a value but wasn't
        // assigned. Declare a throwaway VarSnippet and read that.
        for (ExpressionSnippet es : exprsToShow) {
            String expr = es.source().trim().replaceAll(";$", "");
            try {
                shell.eval("var __tmp_expr = (" + expr + ");");
                triggerClinit();
                VarSnippet tmp = findVar("__tmp_expr");
                if (tmp != null) {
                    bufferWrite(expr + " ==> " + shell.varValue(tmp) + "\n");
                }
            } catch (Throwable t) {
                errors.append("Error displaying ").append(expr).append(": ").append(t).append('\n');
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
    // /probe — consolidated diagnostic.
    //
    // Verifies the major CheerpJ-specific behaviors we depend on:
    //   - shell.varValue() returns the real formatted value across types
    //   - SnippetEvent.value() and .exception() are still null (broken)
    //   - hybrid reset budget state (how many hard resets are left)
    // ============================================================
    public static String probe() {
        if (shell == null) return "ERROR: JShell not initialized";
        StringBuilder sb = new StringBuilder();
        sb.append("=== JShell-on-CheerpJ probe ===\n");
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
            for (String d : decls) shell.eval(d + ";");
            triggerClinit();
            for (VarSnippet vs : shell.variables().toList()) {
                if (!vs.name().startsWith("__px_")) continue;
                String val;
                try { val = shell.varValue(vs); }
                catch (Throwable t) { val = "ERR " + t.getClass().getSimpleName(); }
                sb.append(String.format("    %-32s -> %s%n", vs.typeName() + " " + vs.name(), val));
            }
            // Drop the probe vars to keep the session clean
            for (VarSnippet vs : shell.variables().toList()) {
                if (vs.name().startsWith("__px_")) {
                    try { shell.drop(vs); } catch (Throwable ignore) {}
                }
            }

            // --- SnippetEvent value/exception ---
            sb.append("\n[3] SnippetEvent.value() / .exception() (expected: both null in CheerpJ)\n");
            for (SnippetEvent e : shell.eval("40 + 2;")) {
                if (e.snippet() == null || e.causeSnippet() != null) continue;
                sb.append("    eval('40 + 2').value():       ")
                  .append(e.value() == null ? "null" : "'" + e.value() + "'").append('\n');
            }
            for (SnippetEvent e : shell.eval("throw new RuntimeException(\"x\");")) {
                if (e.snippet() == null || e.causeSnippet() != null) continue;
                sb.append("    eval('throw...').exception():  ")
                  .append(e.exception() == null ? "null" : e.exception().getClass().getName())
                  .append('\n');
            }
        } catch (Throwable t) {
            sb.append("\nPROBE FAILED: ").append(t).append('\n');
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
            if (shell == null) {
                // First-time init path
                System.setOut(switchStream);
                System.setErr(switchStream);
                shell = JShell.builder()
                    .executionEngine("local")
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
                    .executionEngine("local")
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

            // Otherwise — soft reset (drop active user snippets only).
            for (VarSnippet vs : shell.variables().toList()) {
                if (!"__threw".equals(vs.name())) {
                    try { shell.drop(vs); } catch (Throwable ignore) {}
                }
            }
            for (MethodSnippet ms : shell.methods().toList()) {
                String n = ms.name();
                if (!"__safe".equals(n) && !"__trigger".equals(n)) {
                    try { shell.drop(ms); } catch (Throwable ignore) {}
                }
            }
            for (TypeDeclSnippet ts : shell.types().toList()) {
                try { shell.drop(ts); } catch (Throwable ignore) {}
            }
            for (ImportSnippet is : shell.imports().toList()) {
                // Keep our default auto-imports across resets — they're
                // infrastructure, not user state.
                if (DEFAULT_IMPORT_NAMES.contains(is.name())) continue;
                try { shell.drop(is); } catch (Throwable ignore) {}
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
