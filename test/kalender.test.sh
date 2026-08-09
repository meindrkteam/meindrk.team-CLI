#!/usr/bin/env bash
# Prueft: kalender list zeigt id/projektID/name und traegt je Kalender ein,
# WIE der angemeldete Benutzer zu ihm steht (besitz) und OB er hineinschreiben
# darf (schreiben). Beides ist unabhaengig: ein fremder Kalender kann
# beschreibbar sein, ein Kalender des eigenen Kreisverbands nur lesbar.
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

# calendarAccesses stammen aus der Extended-Sicht (Calendar.getCalendarAccesses).
KALENDER = [
    {"id": 1, "projektID": 42, "benutzerID": 7,  "personID": -1, "name": "Mein Kalender",
     "calendarAccesses": []},                                        # Besitzer (benutzerID)
    {"id": 2, "projektID": 42, "benutzerID": -1, "personID": 70, "name": "Meine Person",
     "calendarAccesses": []},                                        # Besitzer (personID)
    {"id": 3, "projektID": 42, "benutzerID": 99, "personID": -1, "name": "NurLesen",
     "calendarAccesses": [{"benutzerID": 7, "personID": -1, "gruppeID": -1, "writeAccess": False}]},
    {"id": 4, "projektID": 88, "benutzerID": 99, "personID": -1, "name": "Nachbarverband",
     "calendarAccesses": [{"benutzerID": -1, "personID": 70, "gruppeID": -1, "writeAccess": True}]},
    {"id": 5, "projektID": 42, "benutzerID": 99, "personID": -1, "name": "UeberGruppe",
     "calendarAccesses": [{"benutzerID": -1, "personID": -1, "gruppeID": 500, "writeAccess": True}]},
    {"id": 6, "projektID": 42, "benutzerID": 99, "personID": -1, "name": "FremdeGruppe",
     "calendarAccesses": [{"benutzerID": -1, "personID": -1, "gruppeID": 600, "writeAccess": True}]},
    {"id": 7, "projektID": 42, "benutzerID": 99, "personID": -1, "name": "GruppeNurLesend",
     "calendarAccesses": [{"benutzerID": -1, "personID": -1, "gruppeID": 700, "writeAccess": False}]},
]

# Mitglieder je Gruppe. 500 enthaelt mich (Person 70), 600 nicht.
GRUPPEN = {"500": [{"id": 70}, {"id": 71}], "600": [{"id": 71}], "700": [{"id": 70}]}

class H (BaseHTTPRequestHandler):
    def do_GET (self):
        with open (mitschrift, "a") as f:
            f.write (self.path + "\n")
        if self.path.startswith ("/backend/rest/current-user"):
            with open (benutzerdatei) as f:
                roh = f.read ().strip ().encode ()
        elif "/PersonIds" in self.path:
            gid = self.path.split ("/Gruppe/")[1].split ("/")[0]
            roh = json.dumps (GRUPPEN.get (gid, [])).encode ()
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

MSYS_NO_PATHCONV=1 python3 - "$(grep -v -e current-user -e PersonIds "$MITSCHRIFT" | head -1)" "$out" <<'PY' || fail=1
import sys, json, urllib.parse
pfad, out = sys.argv[1], sys.argv[2]
assert "/backend/rest/store/Calendar/view/Extended" in pfad, pfad
qs = urllib.parse.parse_qs (urllib.parse.urlparse (pfad).query)
eigenschaften = {e["property"]: e for e in json.loads (qs["filter"][0])}
assert eigenschaften["projektID"]["value"] == "42", eigenschaften
assert eigenschaften["projektID"].get ("exact") is True, eigenschaften
d = json.loads (out)
assert d["ok"] is True and d["count"] == 7, d
print ("  ok: kalender list filtert ueber projektID")
PY

# ── besitz: wem der Kalender gehoert ────────────────────────────────────────
#    "eigen" schlaegt "eigener_kv": ein eigener Kalender liegt fast immer auch
#    im eigenen Kreisverband, und die genauere Aussage ist die nuetzlichere.
MSYS_NO_PATHCONV=1 python3 - "$out" <<'PY' || fail=1
import sys, json
nach_id = {k["id"]: k for k in json.loads (sys.argv[1])["data"]}
erwartet = {1: "eigen", 2: "eigen", 3: "eigener_kv", 4: "fremder_kv",
            5: "eigener_kv", 6: "eigener_kv", 7: "eigener_kv"}
for kid, soll in erwartet.items ():
    ist = nach_id[kid].get ("besitz")
    assert ist == soll, f"Kalender {kid}: besitz erwartet {soll}, bekam {ist}"
print ("  ok: besitz eigen / eigener_kv / fremder_kv")
PY

# ── schreiben: Besitz, direkte Freigabe und Gruppenweg ──────────────────────
#    Das ist die eigentliche Frage: nicht "wem gehoert der Kalender", sondern
#    "wo darf ich hineinschreiben". Kalender 4 zeigt, dass beides auseinander
#    faellt — fremder Verband, aber beschreibbar.
MSYS_NO_PATHCONV=1 python3 - "$out" <<'PY' || fail=1
import sys, json
nach_id = {k["id"]: k for k in json.loads (sys.argv[1])["data"]}
erwartet = {
    1: True,   # Besitzer ueber benutzerID
    2: True,   # Besitzer ueber personID
    3: False,  # direkte Freigabe, aber writeAccess=false
    4: True,   # direkte Freigabe an meine Person, writeAccess=true
    5: True,   # ueber Gruppe 500, in der ich bin
    6: False,  # ueber Gruppe 600, in der ich NICHT bin
    7: False,  # Gruppe 700 gewaehrt nur Leserecht
}
for kid, soll in erwartet.items ():
    ist = nach_id[kid].get ("schreiben")
    assert ist == soll, f"Kalender {kid}: schreiben erwartet {soll}, bekam {ist}"
print ("  ok: schreiben ueber Besitz, Freigabe und Gruppe")
PY

# ── Nur schreibende Gruppen werden befragt, jede genau einmal ───────────────
#    Gruppe 700 gewaehrt nur Leserecht — sie zu befragen waere ein Aufruf ohne
#    Erkenntnis. Und eine Gruppe zweimal zu fragen ebenfalls.
MSYS_NO_PATHCONV=1 python3 - "$MITSCHRIFT" <<'PY' || fail=1
import sys, re, collections
zaehler = collections.Counter (
    m.group (1) for z in open (sys.argv[1])
    for m in [re.search (r"/Gruppe/(\d+)/PersonIds", z)] if m)
assert set (zaehler) == {"500", "600"}, f"befragt: {dict (zaehler)} (700 gewaehrt nur Leserecht)"
assert all (n == 1 for n in zaehler.values ()), dict (zaehler)
print ("  ok: nur schreibende Gruppen befragt, je einmal")
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
#    und moeglicherweise falsch. Beim Schreibrecht gilt dasselbe — es fehlt
#    dann ganz, statt ein falsches "false" zu behaupten.
echo '{}' > "$BENUTZER"
out="$(run --json kalender list --kvid 42 2>&1)"
MSYS_NO_PATHCONV=1 python3 - "$out" <<'PY' || fail=1
import sys, json
daten = json.loads (sys.argv[1])["data"]
assert {k.get ("besitz") for k in daten} == {"unbekannt"}, daten
assert all (k.get ("schreiben") is None for k in daten), daten
print ("  ok: ohne Benutzer -> unbekannt, kein geratenes Schreibrecht")
PY

# ── Die Liste bleibt brauchbar, auch wenn current-user ganz ausfaellt ───────
echo 'kein json' > "$BENUTZER"
out="$(run --json kalender list --kvid 42 2>&1)"
MSYS_NO_PATHCONV=1 python3 - "$out" <<'PY' || fail=1
import sys, json
d = json.loads (sys.argv[1])
assert d["ok"] is True and d["count"] == 7, d
assert {k.get ("besitz") for k in d["data"]} == {"unbekannt"}, d["data"]
print ("  ok: kaputtes current-user kippt die Auflistung nicht")
PY

# ── Ohne --kvid kein Filter: alle zugaenglichen Kalender ───────────────────
#    Der Server begrenzt ueber die Sitzung. Genau deshalb darf der Aufrufer
#    nie nach einem Kreisverband gefragt werden.
echo '{"id":7,"personID":70,"projektID":42,"login":"testuser"}' > "$BENUTZER"
: > "$MITSCHRIFT"
out="$(run --json kalender list 2>&1)"
MSYS_NO_PATHCONV=1 python3 - "$(grep -v -e current-user -e PersonIds "$MITSCHRIFT" | head -1)" <<'PY' || fail=1
import sys, urllib.parse
qs = urllib.parse.parse_qs (urllib.parse.urlparse (sys.argv[1]).query)
assert "filter" not in qs or qs["filter"] == ["[]"], qs.get ("filter")
print ("  ok: ohne --kvid wird nicht gefiltert")
PY

out="$(run kalender list --kvid 42 2>&1)"
case "$out" in
  *NurLesen*) echo "  ok: Textausgabe zeigt Kalendername" ;;
  *) echo "  FAIL: Textausgabe zeigt Kalendername nicht"; echo "$out" | head -3; fail=1 ;;
esac
case "$out" in
  *eigener_kv*) echo "  ok: Textausgabe zeigt besitz" ;;
  *) echo "  FAIL: Textausgabe zeigt besitz nicht"; fail=1 ;;
esac

[ "$fail" = "0" ] && echo "OK kalender" || exit 1
