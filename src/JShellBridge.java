import jdk.jshell.*;
import java.io.*;
import java.util.*;

/**
 * JShellBridge — Bridge between JavaScript and JShell running in CheerpJ.
 *
 * Designed for CheerpJ's library mode (cheerpjRunLibrary): the JShell instance
 * stays alive across calls, providing persistent REPL state. JS calls init() once,
 * then eval(code) for each user input.
 *
 * CheerpJ-specific workarounds:
 *   - SnippetEvent.value() always returns null — not usable
 *   - shell.varValue() returns type defaults (0, null, false) — not usable
 *   - Printing via shell.eval("println($N)") works for reading values, but re-evaluates
 *     the expression for temp vars — causes double side effects for ++/--
 *   - Exceptions from LocalExecutionControl are silently swallowed — expressions like
 *     1/0 return 0 instead of throwing. Workaround: wrap expressions in try/catch
 *     before eval so exception handling is in the compiled bytecode.
 *   - CheerpJ routes stdout to a #console DOM element, not to Java PrintStream objects.
 *     The SwitchOutputStream captures output to a buffer when 'capturing' is true,
 *     but actual JShell snippet output goes to the DOM. JS reads both sources.
 */
public class JShellBridge {

    private static JShell shell;
    private static SourceCodeAnalysis sca;

    // Output capture state — shared with SwitchOutputStream
    static PrintStream realOut;
    static boolean capturing = false;
    static ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();

    /**
     * Switchable output stream. When capturing=true, writes to outputBuffer.
     * Otherwise forwards to the original System.out (realOut).
     *
     * Uses ByteArrayOutputStream (not StringBuilder) so multi-byte UTF-8
     * characters (tildes, accents, etc.) are preserved correctly.
     *
     * Named static class (not anonymous) to avoid generating JShellBridge$1.class —
     * each extra .class file must be separately loaded via cheerpOSAddStringFile.
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

    /** Write a string to outputBuffer as UTF-8 bytes */
    private static void bufferWrite(String s) {
        try { outputBuffer.write(s.getBytes("UTF-8")); } catch (Exception e) { /* ignore */ }
    }

    /**
     * Initialize JShell with local execution engine.
     * Must be called once before eval(). Sets up stdout/stderr capture
     * and passes switchStream to JShell's builder so the execution engine
     * routes snippet output through our stream.
     */
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

    /** Define __fmt helper in JShell — formats values like real JShell output */
    private static void initFormatter() {
        // Recursive formatter matching real JShell:
        //   int[3] { 1, 2, 3 }
        //   int[3][] { int[2] { 1, 2 }, int[2] { 3, 4 }, ... }
        //   String[3] { "Alice", "Bob", "Charlie" }
        shell.eval(
            "String __fmt(Object v) { "
            + "if (v == null) return \"null\"; "
            + "if (v instanceof String) return \"\\\"\" + v + \"\\\"\"; "
            + "if (v instanceof Character) return \"'\" + v + \"'\"; "
            + "if (v.getClass().isArray()) { "
            + "  int len = java.lang.reflect.Array.getLength(v); "
            + "  String type = v.getClass().getSimpleName().replaceFirst(\"\\\\[\\\\]\", \"[\" + len + \"]\"); "
            + "  var sb = new StringBuilder(type + \" { \"); "
            + "  for (int i = 0; i < len; i++) { "
            + "    if (i > 0) sb.append(\", \"); "
            + "    sb.append(__fmt(java.lang.reflect.Array.get(v, i))); "
            + "  } "
            + "  return sb.append(\" }\").toString(); "
            + "} "
            + "return String.valueOf(v); }"
        );
    }

    /**
     * Evaluate user input. Handles multi-statement input by splitting with
     * SourceCodeAnalysis. Each snippet goes through a 3-tier eval strategy:
     *
     *   1. throw statements → wrapped in try/catch (CheerpJ swallows thrown exceptions)
     *   2. expressions → wrapped in try { var __r = (expr); println(__r); } catch { ... }
     *      This both captures the value AND catches exceptions CheerpJ would swallow.
     *      If JShell rejects it (not an expression), falls through to tier 3.
     *   3. declarations/statements → normal evalOneSnippet (methods, classes, control flow, etc.)
     *
     * Returns: output string, or "@@ERR@@..." for errors.
     */
    public static String eval(String cellCode) {
        if (shell == null) return "ERROR: JShell not initialized";
        try {
            outputBuffer.reset();
            capturing = true;
            StringBuilder errors = new StringBuilder();

            // Pre-process: for multiline input, help analyzeCompletion by adding
            // semicolons to lines that look like standalone statements without them.
            // This handles "int x = 5\nx = 10\nx" typed with Shift+Enter.
            // Lines inside blocks (braces) are left alone.
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

                    // Tier 1: throw statements — wrap in try/catch
                    if (srcTrimmed.startsWith("throw ")) {
                        shell.eval("try { " + src + " } catch (Throwable __e) { "
                            + "System.out.println(\"Exception \" + __e.getClass().getSimpleName() "
                            + "+ \": \" + __e.getMessage()); }");
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
     * Attempt to eval source as an expression wrapped in try/catch.
     * Generates: try { var __r = (expr); System.out.println(__r); }
     *            catch (Throwable __e) { System.out.println("Exception ..."); }
     *
     * This serves two purposes:
     *   1. Captures expression values via a named var (__r) — works in CheerpJ
     *      (unlike $N temp vars which have broken varValue())
     *   2. Catches runtime exceptions that CheerpJ's LocalExecutionControl swallows
     *      (e.g., ArithmeticException from 1/0, NumberFormatException from parseInt)
     *
     * Returns true if JShell accepted it (i.e., source was a valid expression).
     * Returns false if REJECTED (source is a statement/declaration — caller should
     * use evalOneSnippet instead).
     */
    /**
     * Pre-process multiline input: use SourceCodeAnalysis to determine where each
     * snippet ends and add semicolons where needed. Handles multiline method/class
     * bodies by buffering until complete.
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

    private static boolean tryEvalAsExpression(String expr, StringBuilder errors) {
        // Determine display name:
        //   "x"       → "x ==> value"    (bare identifier)
        //   "x = 10"  → "x ==> value"    (assignment to named var)
        //   "x += 5"  → "x ==> value"    (compound assignment)
        //   "x + 5"   → "$N ==> value"   (scratch var)
        scratchCounter++; // Always increment — matches real JShell's $N counter
        String displayName;
        String varName = "__s" + scratchCounter;
        boolean isBareIdentifier = expr.matches("[a-zA-Z_$][a-zA-Z0-9_$]*")
            && !expr.equals("true") && !expr.equals("false") && !expr.equals("null");
        boolean isAssignment = expr.matches("[a-zA-Z_$][a-zA-Z0-9_$]*\\s*[+\\-*/%&|^]?=(?!=).*");
        if (isBareIdentifier) {
            displayName = expr;       // "x ==> value"
        } else if (isAssignment) {
            displayName = expr.replaceAll("\\s*[+\\-*/%&|^]?=.*", "");  // "x ==> value"
        } else {
            displayName = "$" + scratchCounter;  // "$N ==> value"
        }
        String wrapped = "try { var " + varName + " = (" + expr + "); "
            + "System.out.println(\"" + displayName + " ==> \" + __fmt(" + varName + ")); }"
            + " catch (Throwable __e) { System.out.println(\"Exception \" "
            + "+ __e.getClass().getSimpleName() + \": \" + __e.getMessage()); }";
        List<SnippetEvent> events = shell.eval(wrapped);
        // VALID here means "JShell accepted this as a compilable expression" — the try/catch
        // itself always succeeds (VALID) whether the inner expression succeeds or throws.
        // REJECTED means it's not an expression (declaration, method, class) — caller falls
        // through to evalOneSnippet.
        //
        // Known tradeoff: bare assignment expressions like "x = 10" are captured here and
        // display just "10" instead of "x ==> 10". This is acceptable since the value IS
        // shown, and the alternative (evalOneSnippet) would miss exception catching.
        return events.stream().anyMatch(e -> e.status() == Snippet.Status.VALID);
    }

    /**
     * Eval a snippet normally (no try/catch wrapping). Used for declarations,
     * statements, and control flow that can't be wrapped as expressions.
     *
     * Two-phase processing:
     *   Phase 1: shell.eval(source) — compile and execute, collect events
     *   Phase 2: iterate events, collect vars/exprs that need value display
     *   Phase 3: print values via separate shell.eval("println(...)") calls
     *            (done AFTER event iteration to avoid re-entrant eval issues)
     *
     * Handles snippet statuses: VALID, RECOVERABLE_DEFINED (forward refs),
     * RECOVERABLE_NOT_DEFINED, and REJECTED (compilation errors).
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
                // Compilation error — collect diagnostics
                errors.append("Error: ").append(s.source()).append('\n');
                for (Diag d : shell.diagnostics(s).toList())
                        errors.append("  ").append(d.getMessage(Locale.ENGLISH)).append('\n');

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
                    String msg = "|  " + (isNew ? "created" : "modified")
                        + " method " + ms.name() + "(" + ms.parameterTypes() + ")";
                    // Forward reference warning
                    if (status == Snippet.Status.RECOVERABLE_DEFINED
                        || status == Snippet.Status.RECOVERABLE_NOT_DEFINED) {
                        List<String> deps = shell.unresolvedDependencies(ms).toList();
                        if (!deps.isEmpty()) {
                            msg += ", however, it cannot be invoked until "
                                + String.join(", and ", deps) + " are declared";
                        }
                    }
                    bufferWrite(msg + "\n");

                } else if (s instanceof TypeDeclSnippet ts) {
                    boolean isNew = e.previousStatus() == Snippet.Status.NONEXISTENT;
                    String kind = s.subKind() == Snippet.SubKind.CLASS_SUBKIND ? "class"
                        : s.subKind() == Snippet.SubKind.INTERFACE_SUBKIND ? "interface"
                        : s.subKind() == Snippet.SubKind.ENUM_SUBKIND ? "enum"
                        : s.subKind() == Snippet.SubKind.RECORD_SUBKIND ? "record" : "type";
                    bufferWrite("|  " + (isNew ? "created" : "modified")
                        + " " + kind + " " + ts.name() + "\n");
                }
                // ImportSnippet, StatementSnippet — no auto-display needed
            }
        }

        // Phase 3: print values AFTER event iteration
        //
        // CheerpJ limitations force this approach:
        //   - varValue() returns type defaults (0, null, false) — unusable
        //   - SnippetEvent.value() always returns null — unusable
        //   - shell.eval("println(varName)") works: it runs in JShell's execution context
        //     where the actual values are correct
        //
        // For temp vars ($N): println($N) reads the temp var by name — safe for most
        // expressions. Note: this path is only reached for expressions that couldn't be
        // wrapped by tryEvalAsExpression (rare, since most expressions go through tier 2).
        //
        // For named vars: println("name ==> " + name) — always safe (just reads the var).
        // For ExpressionSnippet (bare var refs like "x"): println(x) — safe, just reads.

        for (VarSnippet vs : varsToShow) {
            shell.eval("System.out.println(\"" + vs.name() + " ==> \" + __fmt(" + vs.name() + "));");
        }

        for (ExpressionSnippet es : exprsToShow) {
            String expr = es.source().trim().replaceAll(";$", "");
            shell.eval("System.out.println(\"" + expr + " ==> \" + __fmt(" + expr + "));");
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

    /**
     * Reset JShell — destroys current session and creates a fresh one.
     * All variables, methods, classes, and imports are cleared.
     */
    public static String reset() {
        try {
            scratchCounter = 0;
            if (shell != null) shell.close();
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
        } catch (Throwable t) {
            return "ERROR: " + t.toString();
        }
    }
}
