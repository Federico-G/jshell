# JShell Web

A fully functional JShell (Java's built-in REPL) running entirely in the browser

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

Supports: variables, methods, classes, enums, records, interfaces, generics, lambdas, imports, arrays, collections, streams, and more.

## How it works

- **[CheerpJ](https://cheerpj.com)**: Full JVM running in WebAssembly (loaded from CDN)
- **jdk.compiler_17.jar**: Java 17 compiler (javac), extracted from Temurin JDK 17
- **jdk.jshell.jar**: JShell engine + dependencies, extracted from Temurin JDK 17
- **JShellBridge.java**: Our bridge between JavaScript and the JShell API

Total download: ~5.6MB of JARs + CheerpJ runtime from CDN.

## Commands

| Command | Description |
|---|---|
| `/help` | Show available commands |
| `/clear` | Clear the console |
| `/reset` | Reset JShell session |
| `/test` | Run regression test suite (80+ tests) |
| `/diag <code>` | Show JShell internals for given code |

## Keyboard shortcuts

- **Enter**: Run code
- **Shift+Enter**: New line (multiline input)
- **Up/Down**: Command history

## Building from source

See [BUILD.md](BUILD.md) for instructions on rebuilding the JARs from a JDK 17 distribution.

## Known limitations

All related to Exceptions
- **`1 % 0`**: No ArithmeticException (CheerpJ WASM limitation)
- **`new int[-1]`**: No NegativeArraySizeException (CheerpJ WASM limitation)
- **Some exception messages**: Show `null` instead of detail

## Credits

- **[CheerpJ](https://cheerpj.com)** by Leaning Technologies. JVM in the browser
- **[SnapCode](https://github.com/reportmill/SnapCode)** by Jeff Martin. Inspired the jdk.compiler extraction approach
- **[Eclipse Temurin](https://adoptium.net/)** JDK 17 source for compiler and JShell JARs

## License

CheerpJ Community License (free for non-commercial use from CDN).
JDK components are under the GPL v2 with Classpath Exception.
