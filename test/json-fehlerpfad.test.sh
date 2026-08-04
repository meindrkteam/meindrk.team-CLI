#!/usr/bin/env bash
# Prueft: mit --json endet JEDER Fehler als JSON-Envelope, nie als Stacktrace.
set -uo pipefail
cd "$(dirname "$0")/.."

CLASSES=/tmp/cliclasses-test
rm -rf "$CLASSES" && mkdir -p "$CLASSES"
javac --release 21 -cp "lib/jackson/*" -d "$CLASSES" src/de/kreisalarm/cli/*.java || {
  echo "FAIL: kompiliert nicht"; exit 1; }

FAKEHOME=/tmp/cli-fakehome
rm -rf "$FAKEHOME" && mkdir -p "$FAKEHOME"

run () {  # $1 = MEINDRK_URL, Rest = Argumente
  local url="$1"; shift
  HOME="$FAKEHOME" MEINDRK_URL="$url" MEINDRK_SESSION=DUMMY MEINDRK_KVID=1 \
    java -cp "$CLASSES:lib/jackson/*" de.kreisalarm.cli.CLI "$@"
}

fail=0

# 1) Toter Port -> ConnectException. Muss JSON auf stderr sein, Exit 1.
err="$(run "http://127.0.0.1:59999" --json person list --q Test 2>&1 >/dev/null)"
code=$?
echo "$err" | python3 -c '
import sys, json
raw = sys.stdin.read().strip()
d = json.loads(raw)                      # wirft, wenn Stacktrace statt JSON
assert d["ok"] is False, d
assert d["error"] and "Exception" not in d["error"], d
print("  ok: ConnectException ->", d["error"][:70])
' || { echo "  FAIL: kein JSON-Envelope bei ConnectException"; echo "  war: ${err:0:200}"; fail=1; }
[ "$code" = "1" ] || { echo "  FAIL: Exit-Code $code statt 1"; fail=1; }

# 2) Unbekannter Befehl -> ebenfalls JSON (Regression: ging schon vorher)
err="$(run "http://127.0.0.1:59999" --json quatsch 2>&1 >/dev/null)"
echo "$err" | python3 -c '
import sys, json
d = json.loads(sys.stdin.read().strip())
assert d["ok"] is False, d
print("  ok: unbekannter Befehl ->", d["error"][:70])
' || { echo "  FAIL: unbekannter Befehl liefert kein JSON"; fail=1; }

# 3) OHNE --json bleibt die Ausgabe menschenlesbar (kein JSON erzwungen)
err="$(run "http://127.0.0.1:59999" person list --q Test 2>&1 >/dev/null)"
case "$err" in
  '{"ok"'*) echo "  FAIL: ohne --json wurde JSON ausgegeben"; fail=1 ;;
  *)        echo "  ok: ohne --json bleibt Klartext" ;;
esac

[ "$fail" = "0" ] && echo "OK json-fehlerpfad" || exit 1
