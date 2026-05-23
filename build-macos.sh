#!/usr/bin/env bash
# =============================================================================
#  meinDRK CLI – macOS Universal Binary (arm64 + x86_64)
#  Auf einem Mac mit GraalVM JDK 21 ausführen.
#  Installation: brew install --cask graalvm-jdk
#  Ergebnis: build/meindrk-cli-macos-universal
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="$SCRIPT_DIR/src"
LIB_DIR="$SCRIPT_DIR/lib/jackson"
BUILD_DIR="$SCRIPT_DIR/build"
TMP_DIR="$SCRIPT_DIR/tmp"

mkdir -p "$TMP_DIR/classes" "$TMP_DIR/fat" "$BUILD_DIR"

# GraalVM suchen (Homebrew-Default oder GRAALVM_HOME)
if [[ -n "${GRAALVM_HOME:-}" && -x "$GRAALVM_HOME/bin/native-image" ]]; then
    NATIVE_IMAGE="$GRAALVM_HOME/bin/native-image"
    JAVAC="$GRAALVM_HOME/bin/javac"
    JAR_CMD="$GRAALVM_HOME/bin/jar"
elif command -v native-image &>/dev/null; then
    NATIVE_IMAGE="native-image"
    JAVAC="javac"
    JAR_CMD="jar"
else
    echo "FEHLER: native-image nicht gefunden."
    echo "Installieren mit: brew install --cask graalvm-jdk"
    echo "Dann: export GRAALVM_HOME=\$(brew --prefix graalvm-jdk)/libexec"
    exit 1
fi

echo "[1/5] Kompiliere Java-Quellen..."
"$JAVAC" --release 21 \
    -cp "$LIB_DIR/*" \
    -d "$TMP_DIR/classes" \
    "$SRC_DIR/de/kreisalarm/cli/Config.java" \
    "$SRC_DIR/de/kreisalarm/cli/RestClient.java" \
    "$SRC_DIR/de/kreisalarm/cli/TablePrinter.java" \
    "$SRC_DIR/de/kreisalarm/cli/GuiDetector.java" \
    "$SRC_DIR/de/kreisalarm/cli/GuiServer.java" \
    "$SRC_DIR/de/kreisalarm/cli/CLI.java"

echo "[2/5] Baue Fat-JAR..."
(cd "$TMP_DIR/fat" && for f in "$LIB_DIR"/jackson-{core,annotations,databind}-*.jar; do "$JAR_CMD" xf "$f"; done)
cp -r "$TMP_DIR/classes/." "$TMP_DIR/fat/"
printf 'Main-Class: de.kreisalarm.cli.CLI\n\n' > "$TMP_DIR/MANIFEST.MF"
"$JAR_CMD" cfm "$TMP_DIR/meindrk-cli.jar" "$TMP_DIR/MANIFEST.MF" -C "$TMP_DIR/fat" .

echo "[3/5] Baue macOS arm64 Binary..."
"$NATIVE_IMAGE" \
    -jar "$TMP_DIR/meindrk-cli.jar" \
    --no-fallback \
    --enable-url-protocols=https \
    -O1 \
    --strict-image-heap \
    --initialize-at-build-time=com.fasterxml.jackson.annotation,com.fasterxml.jackson.core,com.fasterxml.jackson.databind \
    -o "$BUILD_DIR/meindrk-cli-macos-arm64"

echo "[4/5] Baue macOS x86_64 Binary..."
# Erzwingt x86_64-Build auf Apple Silicon via Rosetta
arch -x86_64 "$NATIVE_IMAGE" \
    -jar "$TMP_DIR/meindrk-cli.jar" \
    --no-fallback \
    --enable-url-protocols=https \
    -O1 \
    --strict-image-heap \
    --initialize-at-build-time=com.fasterxml.jackson.annotation,com.fasterxml.jackson.core,com.fasterxml.jackson.databind \
    -o "$BUILD_DIR/meindrk-cli-macos-x64"

echo "[5/5] Erstelle Universal Binary..."
lipo -create -output "$BUILD_DIR/meindrk-cli-macos-universal" \
    "$BUILD_DIR/meindrk-cli-macos-arm64" \
    "$BUILD_DIR/meindrk-cli-macos-x64"

rm -f "$BUILD_DIR/meindrk-cli-macos-arm64" "$BUILD_DIR/meindrk-cli-macos-x64"

echo "Fertig: $BUILD_DIR/meindrk-cli-macos-universal"
file "$BUILD_DIR/meindrk-cli-macos-universal"
