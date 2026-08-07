#!/usr/bin/env bash
# Prueft: termin list filtert ueber calendarID (nicht projektID) und durchsucht
# den Namen; termin get liefert ein Einzelobjekt.
set -uo pipefail
cd "$(dirname "$0")/.."
source test/lib/portable.sh

CLASSES=/tmp/cliclasses-termin-lesen
rm -rf "$CLASSES" && mkdir -p "$CLASSES"
javac --release 21 -cp "lib/jackson/*" -d "$CLASSES" src/de/kreisalarm/cli/*.java || {
  echo "FAIL: kompiliert nicht"; exit 1; }
CP="$(winpath "$CLASSES")${CP_SEP}$(winpath "$PWD")/lib/jackson/*"

FAKEHOME=/tmp/cli-termin-lesen-home
rm -rf "$FAKEHOME" && mkdir -p "$FAKEHOME"
MITSCHRIFT=/tmp/cli-termin-lesen-requests.log
: > "$MITSCHRIFT"

PORT=58833
python3 - "$PORT" "$MITSCHRIFT" <<'PY' &
import sys, json
from http.server import BaseHTTPRequestHandler, HTTPServer

port, mitschrift = int (sys.argv[1]), sys.argv[2]

class H (BaseHTTPRequestHandler):
    def do_GET (self):
        with open (mitschrift, "a") as f:
            f.write (self.path + "\n")
        if self.path.startswith ("/backend/rest/CalendarEvent/"):
            body = {"id": 9, "calendarID": 7, "name": "Uebung", "startDate": "20260901"}
        else:
            body = {"success": True, "total": 1,
                    "root": [{"id": 9, "calendarID": 7, "name": "Uebung",
                              "startDate": "20260901", "endDate": "20260901"}]}
        roh = json.dumps (body).encode ()
        self.send_response (200)
        self.send_header ("Content-Type", "application/json")
        self.send_header ("Content-Length", str (len (roh)))
        self.end_headers ()
        self.wfile.write (roh)
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

# ── 1) termin list filtert ueber calendarID, sucht ueber name ───────────────
: > "$MITSCHRIFT"
out="$(run --json termin list --calendar 7 --q Uebung 2>&1)"
MSYS_NO_PATHCONV=1 python3 - "$(cat "$MITSCHRIFT")" "$out" <<'PY' || fail=1
import sys, json, urllib.parse
pfad, out = sys.argv[1], sys.argv[2]
assert "/backend/rest/store/CalendarEvent/view/Extended" in pfad, pfad
qs = urllib.parse.parse_qs (urllib.parse.urlparse (pfad).query)
f = json.loads (qs["filter"][0])
eigenschaften = {e["property"]: e for e in f}
assert "calendarID" in eigenschaften and eigenschaften["calendarID"]["value"] == "7", f
assert eigenschaften["calendarID"].get ("exact") is True, f
assert "name" in eigenschaften and eigenschaften["name"]["value"] == "Uebung", f
d = json.loads (out)
assert d["ok"] is True and d["count"] == 1, d
print ("  ok: termin list filtert calendarID, sucht ueber name")
PY

# ── 2) --calendar als Wert-Flag steht in WERT_FLAGS (nicht als globaler Schalter lesbar) ──
: > "$MITSCHRIFT"
err="$(run --json termin list --calendar abc 2>&1 >/dev/null)"
echo "$err" | python3 -c '
import sys, json
d = json.loads (sys.stdin.read ().strip ())
assert d["ok"] is False, d
assert "Zahl" in d["error"] or "calendar" in d["error"].lower (), d
print ("  ok: ungueltige --calendar-ID wird abgewiesen:", d["error"][:60])
' || { echo "  FAIL: --calendar abc wird nicht abgewiesen"; fail=1; }

# ── 3) termin get liefert Einzelobjekt ───────────────────────────────────────
: > "$MITSCHRIFT"
out="$(run --json termin get 9 2>&1)"
MSYS_NO_PATHCONV=1 python3 - "$(cat "$MITSCHRIFT")" "$out" <<'PY' || fail=1
import sys, json
pfad, out = sys.argv[1], sys.argv[2]
assert pfad.strip () == "/backend/rest/CalendarEvent/9", pfad
d = json.loads (out)
assert d["ok"] is True and d["data"]["name"] == "Uebung", d
print ("  ok: termin get liefert Einzelobjekt")
PY

[ "$fail" = "0" ] && echo "OK termin-lesen" || exit 1
