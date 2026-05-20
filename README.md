# JShell Web

A fully functional JShell (Java's built-in REPL) running entirely in the browser.

**[Live Demo](https://federico-g.github.io/jshell/)**

## What it does

Type Java code, press Enter, see results. Variables, methods, and classes persist between evaluations, exactly like the real `jshell` command.

```
jshell> int x = 10
x ==> 10
jshell> x * 2
$2 ==> 20
jshell> String greet(String name) { return "Hello, " + name + "!"; }
|  created method greet(String)
jshell> greet("World")
$4 ==> "Hello, World!"
```

Output format matches real `jshell` line-for-line, including `$N` scratch variables, `|  created/replaced/modified` messages, and `|  Exception java.lang.X: msg` for thrown errors.

## Features

- **All standard Java syntax**: variables, methods, classes, records, enums, interfaces, abstract classes, generics, lambdas, method references, pattern matching, streams.
- **Real `$N` scratch variables**: `1` then `$1` returns 1, just like real `jshell`. Reusable in later expressions and method calls.
- **Auto-imports** for `java.io.*`, `java.math.*`, `java.net.*`, `java.nio.file.*`, `java.util.*`, `java.util.concurrent.*`, `java.util.function.*`, `java.util.prefs.*`, `java.util.regex.*`, `java.util.stream.*` — same set as real `jshell`'s default startup.
- **Multiline input** via Shift+Enter; auto-semicolon insertion for incomplete statements.
- **Forward references**: methods can reference vars/methods defined later, with the same `cannot be invoked until X is declared` warning.

## Commands

| Command | Description |
|---|---|
| `/help`  | Show available commands |
| `/clear` | Clear the console |
| `/reset` | Reset session (clear all user state) |
| `/dev`   | Enable developer mode (test suites + diagnostics) |

In **developer mode**, additional commands are available:

| Command | Description |
|---|---|
| `/test` | Run all 5 test suites (218 tests) |
| `/test-language` | Java language features |
| `/test-format`   | Exact value display per type |
| `/test-scratch`  | `$N` scratch-variable semantics |
| `/test-errors`   | Compile errors, runtime exceptions, single-execution |
| `/test-advanced` | Multiline + integration programs |
| `/probe`         | Diagnostic snapshot (varValue, event status, reset budget) |
| `/diag <code>`   | Per-snippet inspector — what JShell sees for any input |

## Keyboard shortcuts

- **Enter** — run code
- **Shift+Enter** — new line (multiline input)
- **Up/Down** — command history

## How it works

- **[CheerpJ](https://cheerpj.com)**: Full JVM running in WebAssembly (loaded from CDN).
- **`jdk.compiler_17.jar`**: Java 17 compiler (`javac`), extracted from Temurin JDK 17.
- **`jdk.jshell.jar`**: JShell engine + dependencies, with `TaskFactory` patched to use `ServiceLoader` (CheerpJ's `ToolProvider.getSystemJavaCompiler()` returns null otherwise).
- **`MhExecutionControl`**: a custom `LocalExecutionControl` subclass (patched into the `jdk.jshell.execution` package) that invokes the snippet's `do_it$` via `MethodHandle.invokeWithArguments` instead of `Method.invoke`. CheerpJ silently drops exceptions through `Method.invoke`; the MethodHandle path propagates them correctly, so `SnippetEvent.value()` and `.exception()` work natively.
- **`JShellBridge.java`**: bridge between JavaScript and the JShell API. Handles `$N` referenceability via top-level `var` wrapping, prints exceptions + jshell-style `at (#N:L)` stack frames, and runs a hybrid soft/hard reset that bypasses CheerpJ's 15-cycle close+build limit.

Total download: ~5.6 MB of JARs + CheerpJ runtime from CDN.

## Building from source

See [BUILD.md](BUILD.md) for instructions on rebuilding the JARs from a JDK 17 distribution. To rebuild the bridge:

```bash
bash src/build.sh
```

To run a local dev server:

```bash
serve.bat        # Windows
python serve.py  # any platform with Python 3
```

The dev server adds HTTP `Range` support (required by CheerpJ to load JARs) and `no-cache` headers (so edits land without hard-reload).

## Known limitations

These are CheerpJ-specific deviations from real Java semantics. None affect normal user code; they're documented for transparency.

- **`1 % 0`** silently returns `0` — CheerpJ's WASM doesn't trap mod-by-zero. Real Java throws `ArithmeticException`. *(Tested as a "canary" in `/test-errors`.)*
- **`new int[-1]`** throws `ArithmeticException` instead of `NegativeArraySizeException`. *(Also a canary.)*
- **JVM-thrown exception messages are often `null`** — e.g. real `ArithmeticException` from `1/0` carries `: / by zero`; CheerpJ's WASM raises the exception without setting the message field. User-thrown exceptions (`throw new RuntimeException("boom")`) keep their message intact.
- **`shell.close() + new JShell.builder().build()`** is hardcoded to ~15 cycles per page in CheerpJ; after that, javac's `Names.Table` corrupts and any compile fails. We work around it by soft-resetting (drop snippets, keep JShell alive) and only doing a hard reset every 20 evals — budget capped at 14, with a warning before the cliff.
- **`cheerpjRunLibrary`** can only be called once per page. The only true "deep reset" is reloading the page.

## Credits

- **[CheerpJ](https://cheerpj.com)** by Leaning Technologies — JVM in the browser.
- **[SnapCode](https://github.com/reportmill/SnapCode)** by Jeff Martin — inspired the `jdk.compiler` extraction approach.
- **[Eclipse Temurin](https://adoptium.net/)** JDK 17 — source for `jdk.compiler_17.jar` and `jdk.jshell.jar`.

## License

CheerpJ Community License (free for non-commercial use from the CDN).
JDK components are under the GPL v2 with Classpath Exception.
