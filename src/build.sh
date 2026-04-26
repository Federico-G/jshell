#!/bin/bash
# Build JShellBridge and copy to precompiled/
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$SCRIPT_DIR/.."
SRC="$SCRIPT_DIR"
BUILD="$SCRIPT_DIR/build"
OUT="$ROOT/precompiled"
CP="$ROOT/jdk.jshell.jar;$ROOT/jdk.compiler_17.jar"

rm -rf "$BUILD" && mkdir -p "$BUILD" "$OUT"

echo "Compiling JShellBridge..."
javac --release 17 -cp "$CP" -d "$BUILD" "$SRC/JShellBridge.java" || exit 1

echo "Copying to public/precompiled/..."
cp "$BUILD"/*.class "$OUT/"
echo "Done. Classes:"
ls "$OUT"/JShellBridge*.class
