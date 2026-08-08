#!/usr/bin/env bash
# Prueft: kalender list filtert ueber projektID, zeigt id/projektID/name und
# traegt je Kalender ein, wie der angemeldete Benutzer zu ihm steht (besitz).
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
BENUTZER=/tmp/cli-kalender-user.json
: > "$MITSCHRIFT"

# Der angemeldete Benutzer: Benutzer 7, Person 70, Kreisverband 42.
echo '{"id":7,"personID":70,"projektID":42,"login":"testuser"}' > "$BENUTZER"

PORT=58822
python3 - "$PORT" "$MITSCHRIFT" "$BENUTZER" <<'PY' &
import sys, json
from http.server import BaseHTTPRequestHandler, HTTPServer

port, mitschrift, benutzerdatei = int (sys.argv[1]), sys.argv[2], sys.argv[3]

# Vier Kalender, einer je moeglichem Verhaeltnis zum Benutzer oben.
KALENDER = [
    {"id": 1, "projektID": 42, "benutzerID": 7,  "personID": -1, "name": "Mein Kalender"},
    {"id": 2, "projektID": 42, "benutzerID": -1, "personID": 70, "name": "Meine Person"},
    {"id": 3, "projektID": 42, "benutzerID": 99, "personID": -1, "name": "Kalender Kreisverband"},
    {"id": 4, "projektID": 88, "benutzerID": 99, "personID": -1, "name": "Nachbarverband"},
]

class H (BaseHTTPRequestHandler):
    def do_GET (self):
        with open (mitschrift, "a") as f:
            f.write (self.path + "\n")
        if self.path.startswith ("/backend/rest/current-user"):
            with open (benutzerdatei) as f:
                roh = f.read ().strip ().encode ()
        else:
            roh = json.dumps ({"success": True, "total": len (KALENDER),
                               "root": KALENDER}).encode ()
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
MSYS_NO_PATHCONV=1 python3 - "$(grep -v current-user "$MITSCHRIFT" | head -1)" "$out" <<'PY' || fail=1
import sys, json, urllib.parse
pfad, out = sys.argv[1], sys.argv[2]
assert "/backend/rest/store/Calendar/view/Extended" in pfad, pfad
qs = urllib.parse.parse_qs (urllib.parse.urlparse (pfad).query)
f = json.loads (qs["filter"][0])
eigenschaften = {e["property"]: e for e in f}
assert eigenschaften["projektID"]["value"] == "42", f
assert eigenschaften["projektID"].get ("exact") is True, f
d = json.loads (out)
assert d["ok"] is True and d["count"] == 4, d
print ("  ok: kalender list filtert ueber projektID")
PY

# ── besitz: die vier Verhaeltnisse ──────────────────────────────────────────
#    "eigen" schlaegt "eigener_kv": ein eigener Kalender liegt fast immer auch
#    im eigenen Kreisverband, und die genauere Aussage ist die nuetzlichere.
MSYS_NO_PATHCONV=1 python3 - "$out" <<'PY' || fail=1
import sys, json
d = json.loads (sys.argv[1])
nach_id = {k["id"]: k for k in d["data"]}
erwartet = {1: "eigen", 2: "eigen", 3: "eigener_kv", 4: "fremder_kv"}
for kid, soll in erwartet.items ():
    ist = nach_id[kid].get ("besitz")
    assert ist == soll, f"Kalender {kid}: erwartet {soll}, bekam {ist}"
print ("  ok: besitz eigen / eigener_kv / fremder_kv")
PY

# ── current-user wird genau EINMAL geholt, nicht je Zeile ───────────────────
anzahl="$(grep -c current-user "$MITSCHRIFT")"
if [ "$anzahl" = "1" ]; then
  echo "  ok: current-user einmal geholt"
else
  echo "  FAIL: current-user $anzahl-mal geholt, erwartet 1"; fail=1
fi

# ── Ohne ermittelbaren Benutzer wird nicht geraten ──────────────────────────
#    "fremder_kv" auf Verdacht waere die schlechteste Antwort: bestimmt im Ton
#    und moeglicherweise falsch.
echo '{}' > "$BENUTZER"
out="$(run --json kalender list --kvid 42 2>&1)"
MSYS_NO_PATHCONV=1 python3 - "$out" <<'PY' || fail=1
import sys, json
d = json.loads (sys.argv[1])
werte = {k.get ("besitz") for k in d["data"]}
assert werte == {"unbekannt"}, werte
print ("  ok: ohne Benutzer -> unbekannt, keine Behauptung")
PY

# ── Die Liste bleibt brauchbar, auch wenn current-user ganz ausfaellt ───────
echo 'kein json' > "$BENUTZER"
out="$(run --json kalender list --kvid 42 2>&1)"
MSYS_NO_PATHCONV=1 python3 - "$out" <<'PY' || fail=1
import sys, json
d = json.loads (sys.argv[1])
assert d["ok"] is True and d["count"] == 4, d
assert {k.get ("besitz") for k in d["data"]} == {"unbekannt"}, d["data"]
print ("  ok: kaputtes current-user kippt die Auflistung nicht")
PY

echo '{"id":7,"personID":70,"projektID":42,"login":"testuser"}' > "$BENUTZER"
out="$(run kalender list --kvid 42 2>&1)"
case "$out" in
  *"Kalender Kreisverband"*) echo "  ok: Textausgabe zeigt Kalendername" ;;
  *) echo "  FAIL: Textausgabe zeigt Kalendername nicht"; fail=1 ;;
esac
case "$out" in
  *"eigener_kv"*) echo "  ok: Textausgabe zeigt besitz" ;;
  *) echo "  FAIL: Textausgabe zeigt besitz nicht"; fail=1 ;;
esac

[ "$fail" = "0" ] && echo "OK kalender" || exit 1
