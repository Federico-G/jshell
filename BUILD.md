# Building CheerpJ JShell Dependencies

This documents how to build the two JAR dependencies from scratch.
Both are extracted from [Eclipse Temurin JDK 17](https://adoptium.net/).

## Prerequisites

- Java 17+ installed (for `jmod` and `jar` commands)
- Temurin JDK 17 zip (downloaded automatically by the scripts below)

## Download JDK 17

```bash
# Download Temurin JDK 17 for Windows x64 (~180MB)
curl -L -o /tmp/jdk17.zip \
  "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk"

# For Linux x64:
# curl -L -o /tmp/jdk17.tar.gz \
#   "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk"
```

## Build jdk.compiler_17.jar (Java 17 javac — 3.3MB)

This is the Java compiler, needed by JShell to compile snippets.

```bash
TEMP="/tmp/compiler17"
rm -rf "$TEMP" && mkdir -p "$TEMP/classes"

# Extract jdk.compiler module from JDK zip
unzip -o /tmp/jdk17.zip "*/jmods/jdk.compiler.jmod" -d "$TEMP"
JMOD=$(find "$TEMP" -name "jdk.compiler.jmod")

# Extract classes from jmod
jmod extract --dir "$TEMP/extract" "$JMOD"
cp -r "$TEMP/extract/classes/"* "$TEMP/classes/"

# Build JAR
cd "$TEMP/classes" && jar cf "$TEMP/jdk.compiler_17.jar" .

# Deploy
cp "$TEMP/jdk.compiler_17.jar" public/jdk.compiler_17.jar
```

Verify: `javap -verbose /tmp/compiler17/classes/com/sun/tools/javac/Main.class | grep "major version"`
→ should be `61` (Java 17)

## Build jdk.jshell.jar (JShell + dependencies — 2.3MB)

This includes JShell and all its dependencies (except jdk.compiler which is separate).

### Step 1: Extract modules

```bash
TEMP="/tmp/jshell17"
rm -rf "$TEMP" && mkdir -p "$TEMP/classes"

# Modules needed by JShell (excluding jdk.compiler)
MODULES="jdk.jshell java.compiler jdk.jdi jdk.internal.opt jdk.internal.le jdk.internal.ed java.prefs java.logging"

for mod in $MODULES; do
  unzip -o /tmp/jdk17.zip "*/jmods/$mod.jmod" -d "$TEMP"
  JMOD=$(find "$TEMP" -name "$mod.jmod")
  mkdir -p "$TEMP/extract"
  jmod extract --dir "$TEMP/extract" "$JMOD"
  cp -r "$TEMP/extract/classes/"* "$TEMP/classes/"
  rm -rf "$TEMP/extract"
done
```

### Step 2: Add service registrations

JShell uses `ServiceLoader` to find execution control providers.
CheerpJ doesn't expose these from its module system, so we register them manually:

```bash
mkdir -p "$TEMP/classes/META-INF/services"

cat > "$TEMP/classes/META-INF/services/jdk.jshell.spi.ExecutionControlProvider" << 'EOF'
jdk.jshell.execution.LocalExecutionControlProvider
jdk.jshell.execution.FailOverExecutionControlProvider
EOF
```

### Step 3: Patch TaskFactory bytecode

CheerpJ's `ToolProvider.getSystemJavaCompiler()` returns `null` even though the compiler
is on the classpath. JShell's `TaskFactory` calls this and fails. We patch it to use
`ServiceLoader` instead.

Create the replacement class:

```java
// File: CompilerFixer.java (package jdk.jshell)
package jdk.jshell;
import javax.tools.JavaCompiler;
import java.util.ServiceLoader;

public class CompilerFixer {
    public static JavaCompiler getJavaCompilerBridge() {
        for (JavaCompiler c : ServiceLoader.load(JavaCompiler.class)) return c;
        try {
            return (JavaCompiler) Class.forName("com.sun.tools.javac.api.JavacTool")
                .getConstructor().newInstance();
        } catch (Exception e) { return null; }
    }
}
```

Compile it (needs `--release 8` to bypass module restrictions):

```bash
javac --release 8 -cp "$TEMP/classes" -d "$TEMP/patch" CompilerFixer.java
cp "$TEMP/patch/jdk/jshell/CompilerFixer.class" "$TEMP/classes/jdk/jshell/"
```

Patch `TaskFactory.class` — replace the `ToolProvider.getSystemJavaCompiler()` call
with `CompilerFixer.getJavaCompilerBridge()`. Both class and method names must be
exactly the same length for binary replacement:

- `javax/tools/ToolProvider` (24 chars) → `jdk/jshell/CompilerFixer` (24 chars)
- `getSystemJavaCompiler` (21 chars) → `getJavaCompilerBridge` (21 chars)

```python
# patch_taskfactory.py
path = "TEMP/classes/jdk/jshell/TaskFactory.class"  # adjust path
data = open(path, "rb").read()
data = data.replace(b"javax/tools/ToolProvider", b"jdk/jshell/CompilerFixer")
data = data.replace(b"getSystemJavaCompiler", b"getJavaCompilerBridge")
open(path, "wb").write(data)
```

### Step 4: Add javax.tools.JavaCompiler service registration

```bash
cat > "$TEMP/classes/META-INF/services/javax.tools.JavaCompiler" << 'EOF'
com.sun.tools.javac.api.JavacTool
EOF
```

### Step 5: Build JAR

```bash
cd "$TEMP/classes" && jar cf "$TEMP/jdk.jshell.jar" .
cp "$TEMP/jdk.jshell.jar" public/jdk.jshell.jar
```

Verify: `javap -verbose /tmp/jshell17/classes/jdk/jshell/JShell.class | grep "major version"`
→ should be `61` (Java 17)

## Build the bridge

Our bridge code (JShellBridge + MhExecutionControl) is compiled and packaged
into `precompiled/bridge.jar`:

```bash
bash src/build.sh
```

This does two `javac` passes:

1. `MhExecutionControl` lives in package `jdk.jshell.execution` so it can
   subclass `LocalExecutionControl` and override its `protected invoke(Method)`.
   javac refuses to add classes to existing system modules from the classpath,
   so the compile uses `--patch-module jdk.jshell=src/patch`.
2. `JShellBridge` (default package) is compiled with the patched class on
   classpath via the same `--patch-module` mechanism.

Output goes to `precompiled/bridge.jar`, which is added to the CheerpJ classpath
at runtime. JARs are used instead of loose `.class` files because CheerpJ's
`cheerpOSAddStringFile` doesn't accept paths with subdirectories under `/str/`.

## File inventory

| File | Size | Source |
|---|---|---|
| `jdk.compiler_17.jar` | 3.3MB | Extracted from Temurin JDK 17 `jdk.compiler` module |
| `jdk.jshell.jar` | 2.3MB | Extracted from Temurin JDK 17 (8 modules + patches) |
| `precompiled/bridge.jar` | ~16KB | Our bridge + MhExecutionControl, compiled by `src/build.sh` |

## CheerpJ runtime

CheerpJ is loaded from the official CDN (free Community License for non-commercial use):

```html
<script src="https://cjrtnc.leaningtech.com/4.2/loader.js"></script>
```

This loads `cj3.js` + `cj3.wasm` and lazily fetches Java standard library classes on demand.
Self-hosting requires a Commercial License from [Leaning Technologies](https://cheerpj.com).
