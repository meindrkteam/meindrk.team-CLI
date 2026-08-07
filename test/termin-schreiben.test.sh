#!/usr/bin/env bash
# Prueft termin create: Pflichtfelder, Validierung von Datum/Zeit/Enum/Bool,
# korrekter POST-Body.
set -uo pipefail
cd "$(dirname "$0")/.."
source test/lib/portable.sh

CLASSES=/tmp/cliclasses-termin-schreiben
rm -rf "$CLASSES" && mkdir -p "$CLASSES"
javac --release 21 -cp "lib/jackson/*" -d "$CLASSES" src/de/kreisalarm/cli/*.java || {
  echo "FAIL: kompiliert nicht"; exit 1; }
CP="$(winpath "$CLASSES")${CP_SEP}$(winpath "$PWD")/lib/jackson/*"

FAKEHOME=/tmp/cli-termin-schreiben-home
rm -rf "$FAKEHOME" && mkdir -p "$FAKEHOME"
MITSCHRIFT=/tmp/cli-termin-schreiben-requests.log
: > "$MITSCHRIFT"

PORT=58844
python3 - "$PORT" "$MITSCHRIFT" <<'PY' &
import sys, json
from http.server import BaseHTTPRequestHandler, HTTPServer

port, mitschrift = int (sys.argv[1]), sys.argv[2]

class H (BaseHTTPRequestHandler):
    def do_POST (self):
        laenge = int (self.headers.get ("Content-Length", 0))
        body = self.rfile.read (laenge).decode ("utf-8")
        with open (mitschrift, "a") as f:
            f.write (json.dumps ({"pfad": self.path, "body": body}) + "\n")
        antwort = json.dumps ({"id": 99, **json.loads (body)}).encode ()
        self.send_response (200)
        self.send_header ("Content-Type", "application/json")
        self.send_header ("Content-Length", str (len (antwort)))
        self.end_headers ()
        self.wfile.write (antwort)
    def do_GET (self):
        self.send_response (200); self.send_header ("Content-Length", "0"); self.end_headers ()
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
melde () { echo "  FAIL: $1"; fail=1; }

# ── 1) Pflichtfelder fehlen -> klare Fehlermeldung, kein Request ────────────
for fehlend in "--name X --start 20260901 --end 20260901" \
               "--calendar 7 --start 20260901 --end 20260901" \
               "--calendar 7 --name X --end 20260901" \
               "--calendar 7 --name X --start 20260901"; do
  : > "$MITSCHRIFT"
  err="$(run --json termin create $fehlend 2>&1 >/dev/null)"
  echo "$err" | python3 -c '
import sys, json
d = json.loads (sys.stdin.read ().strip ())
assert d["ok"] is False, d
' || { melde "fehlendes Pflichtfeld bei '$fehlend' nicht abgewiesen: $err"; }
  [ -s "$MITSCHRIFT" ] && melde "trotz fehlendem Pflichtfeld ging ein Request raus ($fehlend)"
done

# ── 2) Ungueltiges Datumsformat ──────────────────────────────────────────────
err="$(run --json termin create --calendar 7 --name X --start 2026-09-01 --end 20260901 2>&1 >/dev/null)"
echo "$err" | python3 -c '
import sys, json
d = json.loads (sys.stdin.read ().strip ())
assert d["ok"] is False and "yyyyMMdd" in d["error"], d
print ("  ok: ungueltiges --start wird abgewiesen")
' || melde "ungueltiges Datumsformat nicht abgewiesen: $err"

# ── 3) Ungueltiges --feedback ────────────────────────────────────────────────
err="$(run --json termin create --calendar 7 --name X --start 20260901 --end 20260901 --feedback FOO 2>&1 >/dev/null)"
echo "$err" | python3 -c '
import sys, json
d = json.loads (sys.stdin.read ().strip ())
assert d["ok"] is False, d
print ("  ok: ungueltiges --feedback wird abgewiesen")
' || melde "ungueltiges --feedback nicht abgewiesen: $err"

# ── 4) Ungueltiges --allowFreeRegistration ───────────────────────────────────
err="$(run --json termin create --calendar 7 --name X --start 20260901 --end 20260901 --allowFreeRegistration vielleicht 2>&1 >/dev/null)"
echo "$err" | python3 -c '
import sys, json
d = json.loads (sys.stdin.read ().strip ())
assert d["ok"] is False, d
print ("  ok: ungueltiges --allowFreeRegistration wird abgewiesen")
' || melde "ungueltiges --allowFreeRegistration nicht abgewiesen: $err"

# ── 5) Gueltiger Aufruf: korrekter POST-Body, alle Felder korrekt benannt ───
: > "$MITSCHRIFT"
out="$(run --json termin create --calendar 7 --name Uebung --start 20260901 --end 20260901 \
  --startTime 0900 --endTime 1100 --description "Text" --type Uebung --ort 3 --tags "sani,uebung" \
  --feedback ALL --allowFreeRegistration true --gpsNearbyRequired false --countAsService true 2>&1)"
python3 - "$(cat "$MITSCHRIFT")" "$out" <<'PY' || fail=1
import sys, json
d = json.loads (sys.argv[1])
assert d["pfad"] == "/backend/rest/CalendarEvent", d
body = json.loads (d["body"])
erwartet = {
    "calendarID": 7, "name": "Uebung", "startDate": "20260901", "endDate": "20260901",
    "startTime": "0900", "endTime": "1100", "description": "Text", "type": "Uebung",
    "dpVeranstaltungOrtID": 3, "tags": "sani,uebung", "feedbackPolicy": "ALL",
    "allowFreeRegistration": True, "gpsNearbyRequired": False, "countAsService": True,
}
assert body == erwartet, body
out = json.loads (sys.argv[2])
assert out["ok"] is True and out["data"]["id"] == 99, out
print ("  ok: termin create sendet vollstaendigen, korrekt benannten Body")
PY

[ "$fail" = "0" ] && echo "OK termin-schreiben" || exit 1
