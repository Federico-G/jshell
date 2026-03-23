#!/bin/bash
# Build JShellBridge and copy to public/precompiled/
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$SCRIPT_DIR/src"
BUILD="$SCRIPT_DIR/build"
OUT="$SCRIPT_DIR/../public/precompiled"
CP="$SCRIPT_DIR/../public/jdk.jshell.jar;$SCRIPT_DIR/../public/jdk.compiler_17.jar"

rm -rf "$BUILD" && mkdir -p "$BUILD" "$OUT"

echo "Compiling JShellBridge..."
javac --release 17 -cp "$CP" -d "$BUILD" "$SRC/JShellBridge.java" || exit 1

echo "Copying to public/precompiled/..."
cp "$BUILD"/*.class "$OUT/"
echo "Done. Classes:"
ls "$OUT"/JShellBridge*.class
