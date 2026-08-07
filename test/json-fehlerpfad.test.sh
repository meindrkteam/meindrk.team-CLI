#!/usr/bin/env bash
# Prueft: mit --json endet JEDER Fehler als JSON-Envelope, nie als Stacktrace.
set -uo pipefail
cd "$(dirname "$0")/.."
source test/lib/portable.sh

CLASSES=/tmp/cliclasses-test
rm -rf "$CLASSES" && mkdir -p "$CLASSES"
javac --release 21 -cp "lib/jackson/*" -d "$CLASSES" src/de/kreisalarm/cli/*.java || {
  echo "FAIL: kompiliert nicht"; exit 1; }
CP="$(winpath "$CLASSES")${CP_SEP}$(winpath "$PWD")/lib/jackson/*"

FAKEHOME=/tmp/cli-fakehome
rm -rf "$FAKEHOME" && mkdir -p "$FAKEHOME"

run () {  # $1 = MEINDRK_URL, Rest = Argumente
  local url="$1"; shift
  HOME="$FAKEHOME" MEINDRK_URL="$url" MEINDRK_SESSION=DUMMY MEINDRK_KVID=1 \
    java -cp "$CP" de.kreisalarm.cli.CLI "$@"
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

# 4) Server antwortet mit HTTP 500 und langem HTML-Body -> darf nicht 1:1
#    im JSON landen (weder Laenge noch Markup duerfen durchschlagen).
cat > /tmp/cli-mockserver.py <<'PY'
import http.server, sys

class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        body = ("<html><head><title>Internal Server Error</title></head><body>"
                + "Serverfehler " * 100 + "</body></html>").encode("utf-8")
        self.send_response(500)
        self.send_header("Content-Type", "text/html")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass

if __name__ == "__main__":
    port = int(sys.argv[1])
    http.server.HTTPServer(("127.0.0.1", port), Handler).serve_forever()
PY

MOCKPORT=59998
python3 /tmp/cli-mockserver.py "$MOCKPORT" &
MOCKPID=$!
trap 'kill "$MOCKPID" 2>/dev/null' EXIT

for i in $(seq 1 30); do
  curl -s -o /dev/null "http://127.0.0.1:$MOCKPORT/" && break
  sleep 0.1
done

err="$(run "http://127.0.0.1:$MOCKPORT" --json person list --q Test 2>&1 >/dev/null)"
kill "$MOCKPID" 2>/dev/null
wait "$MOCKPID" 2>/dev/null
echo "$err" | python3 -c '
import sys, json
d = json.loads(sys.stdin.read().strip())
assert d["ok"] is False, d
err = d["error"]
assert len(err) < 300, "error zu lang: %d Zeichen" % len(err)
assert "<html" not in err.lower(), "HTML im error-Feld: " + err[:100]
print("  ok: Server-500 mit HTML-Body -> gekuerzt, kein HTML:", err[:70])
' || { echo "  FAIL: Server-Fehler mit HTML-Body nicht sauber gekuerzt"; echo "  war: ${err:0:200}"; fail=1; }

[ "$fail" = "0" ] && echo "OK json-fehlerpfad" || exit 1
