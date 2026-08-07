#!/usr/bin/env bash
# Prueft termin update: nur gesetzte Flags landen im Body, Antwort (root[0])
# wird korrekt ausgepackt.
set -uo pipefail
cd "$(dirname "$0")/.."
source test/lib/portable.sh

CLASSES=/tmp/cliclasses-termin-update
rm -rf "$CLASSES" && mkdir -p "$CLASSES"
javac --release 21 -cp "lib/jackson/*" -d "$CLASSES" src/de/kreisalarm/cli/*.java || {
  echo "FAIL: kompiliert nicht"; exit 1; }
CP="$(winpath "$CLASSES")${CP_SEP}$(winpath "$PWD")/lib/jackson/*"

FAKEHOME=/tmp/cli-termin-update-home
rm -rf "$FAKEHOME" && mkdir -p "$FAKEHOME"
MITSCHRIFT=/tmp/cli-termin-update-requests.log
: > "$MITSCHRIFT"

PORT=58855
python3 - "$PORT" "$MITSCHRIFT" <<'PY' &
import sys, json
from http.server import BaseHTTPRequestHandler, HTTPServer

port, mitschrift = int (sys.argv[1]), sys.argv[2]

class H (BaseHTTPRequestHandler):
    def do_PUT (self):
        laenge = int (self.headers.get ("Content-Length", 0))
        body = self.rfile.read (laenge).decode ("utf-8")
        with open (mitschrift, "a") as f:
            f.write (json.dumps ({"pfad": self.path, "body": body}) + "\n")
        geaendert = json.loads (body)
        if self.path.endswith ("/77"):
            # simuliert einen Server, der die Antwort NICHT in "root" einpackt
            geaendert["id"] = 77
            antwort = json.dumps (geaendert).encode ()
        else:
            geaendert["id"] = 9
            antwort = json.dumps ({"root": [geaendert], "total": 1}).encode ()
        self.send_response (200)
        self.send_header ("Content-Type", "application/json")
        self.send_header ("Content-Length", str (len (antwort)))
        self.end_headers ()
        self.wfile.write (antwort)
    def log_message (self, *a): pass

HTTPServer (("127.0.0.1", port), H).serve_forever ()
PY
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null' EXIT
for i in $(seq 1 40); do curl -sf "http://127.0.0.1:$PORT/ping" >/dev/null 2>&1 && break; sleep 0.1; done

run () {
  HOME="$FAKEHOME" MEINDRK_URL="http://127.0.0.1:$PORT" MEINDRK_SESSION=DUMMY \
    java -cp "$CP" de.kreisalarm.cli.CLI "$@"
}

fail=0

# ── 1) Termin-ID fehlt ───────────────────────────────────────────────────────
err="$(run --json termin update 2>&1 >/dev/null)"
echo "$err" | python3 -c '
import sys, json
d = json.loads (sys.stdin.read ().strip ())
assert d["ok"] is False, d
' || { echo "  FAIL: fehlende Termin-ID nicht abgewiesen"; fail=1; }

# ── 2) Nur gesetzte Flags landen im Body ─────────────────────────────────────
: > "$MITSCHRIFT"
out="$(run --json termin update 9 --name "Neuer Name" 2>&1)"
python3 - "$(cat "$MITSCHRIFT")" "$out" <<'PY' || fail=1
import sys, json
d = json.loads (sys.argv[1])
assert d["pfad"] == "/backend/rest/CalendarEvent/9", d
body = json.loads (d["body"])
assert body == {"name": "Neuer Name"}, body
out = json.loads (sys.argv[2])
assert out["ok"] is True and out["data"]["name"] == "Neuer Name" and out["data"]["id"] == 9, out
print ("  ok: termin update sendet nur gesetzte Felder, packt root[0] aus")
PY

# ── 3) Antwort ohne root-Wrapper -> Rohobjekt statt data:null ────────────────
: > "$MITSCHRIFT"
out="$(run --json termin update 77 --name "Ohne Wrapper" 2>&1)"
python3 - "$(cat "$MITSCHRIFT")" "$out" <<'PY' || fail=1
import sys, json
d = json.loads (sys.argv[1])
assert d["pfad"] == "/backend/rest/CalendarEvent/77", d
out = json.loads (sys.argv[2])
assert out["ok"] is True, out
assert out["data"] is not None, out
assert out["data"]["name"] == "Ohne Wrapper" and out["data"]["id"] == 77, out
print ("  ok: Antwort ohne root-Wrapper faellt auf das Rohobjekt zurueck")
PY

# ── 4) Keine Feld-Flags -> Fehler, kein PUT ──────────────────────────────────
: > "$MITSCHRIFT"
err="$(run --json termin update 9 2>&1 >/dev/null)"
echo "$err" | python3 -c '
import sys, json
d = json.loads (sys.stdin.read ().strip ())
assert d["ok"] is False, d
' || { echo "  FAIL: termin update ohne Feld-Flags wurde nicht abgewiesen"; fail=1; }
if [ -s "$MITSCHRIFT" ]; then
  echo "  FAIL: termin update ohne Feld-Flags hat trotzdem ein PUT ausgeloest"; fail=1
else
  echo "  ok: termin update ohne Feld-Flags sendet kein PUT"
fi

[ "$fail" = "0" ] && echo "OK termin-update" || exit 1
