#!/bin/bash
# Build JShellBridge + MhExecutionControl and copy to precompiled/
#
# MhExecutionControl lives in package jdk.jshell.execution to subclass
# LocalExecutionControl and access its protected invoke(Method). javac
# refuses to add a class to an existing system module on the classpath, so
# we use --patch-module jdk.jshell=<patch-dir> to inject it. The patch dir
# is src/patch/, which must contain ONLY classes belonging to that module
# (hence why JShellBridge.java is compiled in a separate pass).
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$SCRIPT_DIR/.."
SRC="$SCRIPT_DIR"
BUILD="$SCRIPT_DIR/build"
OUT="$ROOT/precompiled"
CP="$ROOT/jdk.jshell.jar;$ROOT/jdk.compiler_17.jar"

rm -rf "$BUILD" && mkdir -p "$BUILD"

echo "Compiling MhExecutionControl (--patch-module jdk.jshell)..."
# Must compile first so JShellBridge can reference it on classpath. Using
# -source/-target 17 instead of --release 17 because the latter's ct.sym
# module graph is incomplete for patching (jdk.internal.opt/le/ed missing).
javac -source 17 -target 17 -Xlint:-options \
    --patch-module "jdk.jshell=$SRC/patch" \
    -d "$BUILD" \
    "$SRC/patch/jdk/jshell/execution/MhExecutionControl.java" || exit 1

echo "Compiling JShellBridge..."
# Use --patch-module so JShellBridge can reference our patched class in
# jdk.jshell.execution.MhExecutionControl (otherwise --release 17 won't see
# it on regular classpath, since the package belongs to a system module).
javac -source 17 -target 17 -Xlint:-options \
    --patch-module "jdk.jshell=$BUILD" \
    -cp "$CP" \
    -d "$BUILD" \
    "$SRC/JShellBridge.java" || exit 1

echo "Packaging into bridge.jar..."
# CheerpJ's cheerpOSAddStringFile doesn't support nested directories under
# /str/, so we can't ship MhExecutionControl as a loose class file (its package
# is jdk/jshell/execution/). Package everything as a JAR and put it on the
# CheerpJ classpath instead — that supports package layout natively.
mkdir -p "$OUT"
rm -f "$OUT"/*.class "$OUT"/bridge.jar
rm -rf "$OUT"/jdk
( cd "$BUILD" && jar cf "$OUT/bridge.jar" . )

echo "Done. JAR contents:"
jar tf "$OUT/bridge.jar"
