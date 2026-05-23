#!/usr/bin/env bash
# =============================================================================
#  meinDRK CLI – Linux x64 Build
#  Auf einem Linux-System (oder in einem GraalVM-Docker-Container) ausführen.
#  Ergebnis: build/meindrk-cli-linux-x64
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC_DIR="$SCRIPT_DIR/src"
LIB_DIR="$SCRIPT_DIR/lib/jackson"
BUILD_DIR="$SCRIPT_DIR/build"
TMP_DIR="$SCRIPT_DIR/tmp"

mkdir -p "$TMP_DIR/classes" "$TMP_DIR/fat" "$BUILD_DIR"

echo "[1/5] Kompiliere Java-Quellen..."
javac --release 21 \
    -cp "$LIB_DIR/*" \
    -d "$TMP_DIR/classes" \
    "$SRC_DIR/de/kreisalarm/cli/Config.java" \
    "$SRC_DIR/de/kreisalarm/cli/RestClient.java" \
    "$SRC_DIR/de/kreisalarm/cli/TablePrinter.java" \
    "$SRC_DIR/de/kreisalarm/cli/GuiDetector.java" \
    "$SRC_DIR/de/kreisalarm/cli/GuiServer.java" \
    "$SRC_DIR/de/kreisalarm/cli/CLI.java"

echo "[2/5] Baue Fat-JAR..."
(cd "$TMP_DIR/fat" && for f in "$LIB_DIR"/jackson-{core,annotations,databind}-*.jar; do jar xf "$f"; done)
cp -r "$TMP_DIR/classes/." "$TMP_DIR/fat/"
printf 'Main-Class: de.kreisalarm.cli.CLI\n\n' > "$TMP_DIR/MANIFEST.MF"
jar cfm "$TMP_DIR/meindrk-cli.jar" "$TMP_DIR/MANIFEST.MF" -C "$TMP_DIR/fat" .

echo "[3/5] Baue Linux x64 Binary..."
native-image \
    -jar "$TMP_DIR/meindrk-cli.jar" \
    --no-fallback \
    --enable-url-protocols=https \
    -O1 \
    --strict-image-heap \
    --initialize-at-build-time=com.fasterxml.jackson.annotation,com.fasterxml.jackson.core,com.fasterxml.jackson.databind \
    -o "$BUILD_DIR/meindrk-cli-linux-x64"

echo "[4/5] UPX-Komprimierung (optional)..."
if command -v upx &>/dev/null; then
    upx --best "$BUILD_DIR/meindrk-cli-linux-x64" && echo "    OK" || echo "    WARNUNG: UPX fehlgeschlagen - Binary bleibt unkomprimiert."
else
    echo "    UPX nicht gefunden - uebersprungen. Install: apt install upx-ucl"
fi

echo "[5/5] Fertig: $BUILD_DIR/meindrk-cli-linux-x64"
