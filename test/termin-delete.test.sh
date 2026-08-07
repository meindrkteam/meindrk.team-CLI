#!/usr/bin/env bash
# Prueft termin delete: --yes noetig im JSON-Modus, DELETE geht an die richtige
# ID, success:false (z. B. RESTRICT-Abhaengigkeit) wird als Fehler gemeldet.
set -uo pipefail
cd "$(dirname "$0")/.."
source test/lib/portable.sh

CLASSES=/tmp/cliclasses-termin-delete
rm -rf "$CLASSES" && mkdir -p "$CLASSES"
javac --release 21 -cp "lib/jackson/*" -d "$CLASSES" src/de/kreisalarm/cli/*.java || {
  echo "FAIL: kompiliert nicht"; exit 1; }
CP="$(winpath "$CLASSES")${CP_SEP}$(winpath "$PWD")/lib/jackson/*"

FAKEHOME=/tmp/cli-termin-delete-home
rm -rf "$FAKEHOME" && mkdir -p "$FAKEHOME"
MITSCHRIFT=/tmp/cli-termin-delete-requests.log
: > "$MITSCHRIFT"

PORT=58866
python3 - "$PORT" "$MITSCHRIFT" <<'PY' &
import sys, json
from http.server import BaseHTTPRequestHandler, HTTPServer

port, mitschrift = int (sys.argv[1]), sys.argv[2]

class H (BaseHTTPRequestHandler):
    def do_DELETE (self):
        with open (mitschrift, "a") as f:
            f.write (self.path + "\n")
        erfolg = "13" not in self.path   # ID 13 simuliert eine RESTRICT-Sperre
        antwort = json.dumps ({"success": erfolg}).encode ()
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

# ── 1) JSON-Modus ohne --yes -> Fehler, kein Request ─────────────────────────
: > "$MITSCHRIFT"
err="$(run --json termin delete 9 2>&1 >/dev/null)"
echo "$err" | python3 -c '
import sys, json
d = json.loads (sys.stdin.read ().strip ())
assert d["ok"] is False and "yes" in d["error"].lower (), d
print ("  ok: --json ohne --yes verlangt Bestaetigung")
' || { echo "  FAIL: --json ohne --yes loescht trotzdem"; fail=1; }
[ -s "$MITSCHRIFT" ] && { echo "  FAIL: trotz fehlendem --yes ging ein DELETE raus"; fail=1; }

# ── 1b) --yes als Wert eines Wert-Flags darf nicht als Bestaetigung zaehlen ──
: > "$MITSCHRIFT"
err="$(run --json termin delete 9 --description --yes 2>&1 >/dev/null)"
echo "$err" | python3 -c '
import sys, json
d = json.loads (sys.stdin.read ().strip ())
assert d["ok"] is False and "yes" in d["error"].lower (), d
print ("  ok: --description --yes wird nicht als Bestaetigung durchgeschmuggelt")
' || { echo "  FAIL: --yes als Wert von --description loescht trotzdem"; fail=1; }
[ -s "$MITSCHRIFT" ] && { echo "  FAIL: --description --yes hat trotzdem ein DELETE ausgeloest"; fail=1; }

# ── 2) JSON-Modus mit --yes -> DELETE an die richtige ID ─────────────────────
: > "$MITSCHRIFT"
out="$(run --json termin delete 9 --yes 2>&1)"
MSYS_NO_PATHCONV=1 python3 - "$(cat "$MITSCHRIFT")" "$out" <<'PY' || fail=1
import sys, json
pfad, out = sys.argv[1], sys.argv[2]
assert pfad.strip () == "/backend/rest/CalendarEvent/9", pfad
d = json.loads (out)
assert d["ok"] is True, d
print ("  ok: --yes loescht die richtige ID")
PY

# ── 3) success:false (RESTRICT-Abhaengigkeit) wird als Fehler gemeldet ──────
err="$(run --json termin delete 13 --yes 2>&1 >/dev/null)"
echo "$err" | python3 -c '
import sys, json
d = json.loads (sys.stdin.read ().strip ())
assert d["ok"] is False, d
print ("  ok: success:false beim Loeschen wird als Fehler gemeldet")
' || { echo "  FAIL: success:false beim Loeschen wird verschluckt"; fail=1; }

[ "$fail" = "0" ] && echo "OK termin-delete" || exit 1
