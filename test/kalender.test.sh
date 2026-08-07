#!/usr/bin/env bash
# Prueft: kalender list filtert ueber projektID und zeigt id/projektID/name.
set -uo pipefail
cd "$(dirname "$0")/.."
source test/lib/portable.sh

CLASSES=/tmp/cliclasses-kalender
rm -rf "$CLASSES" && mkdir -p "$CLASSES"
javac --release 21 -cp "lib/jackson/*" -d "$CLASSES" src/de/kreisalarm/cli/*.java || {
  echo "FAIL: kompiliert nicht"; exit 1; }
CP="$(winpath "$CLASSES")${CP_SEP}$(winpath "$PWD")/lib/jackson/*"

FAKEHOME=/tmp/cli-kalender-home
rm -rf "$FAKEHOME" && mkdir -p "$FAKEHOME"
MITSCHRIFT=/tmp/cli-kalender-requests.log
: > "$MITSCHRIFT"

PORT=58822
python3 - "$PORT" "$MITSCHRIFT" <<'PY' &
import sys, json
from http.server import BaseHTTPRequestHandler, HTTPServer

port, mitschrift = int (sys.argv[1]), sys.argv[2]

class H (BaseHTTPRequestHandler):
    def do_GET (self):
        with open (mitschrift, "a") as f:
            f.write (self.path + "\n")
        body = {"success": True, "total": 1,
                "root": [{"id": 3, "projektID": 42, "name": "Kalender Kreisverband"}]}
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

: > "$MITSCHRIFT"
out="$(run --json kalender list --kvid 42 2>&1)"
MSYS_NO_PATHCONV=1 python3 - "$(cat "$MITSCHRIFT")" "$out" <<'PY' || fail=1
import sys, json, urllib.parse
pfad, out = sys.argv[1], sys.argv[2]
assert "/backend/rest/store/Calendar/view/Extended" in pfad, pfad
qs = urllib.parse.parse_qs (urllib.parse.urlparse (pfad).query)
f = json.loads (qs["filter"][0])
eigenschaften = {e["property"]: e for e in f}
assert eigenschaften["projektID"]["value"] == "42", f
assert eigenschaften["projektID"].get ("exact") is True, f
d = json.loads (out)
assert d["ok"] is True and d["count"] == 1, d
assert d["data"][0]["name"] == "Kalender Kreisverband", d
print ("  ok: kalender list filtert ueber projektID")
PY

out="$(run kalender list --kvid 42 2>&1)"
case "$out" in
  *"Kalender Kreisverband"*) echo "  ok: Textausgabe zeigt Kalendername" ;;
  *) echo "  FAIL: Textausgabe zeigt Kalendername nicht"; fail=1 ;;
esac

[ "$fail" = "0" ] && echo "OK kalender" || exit 1
