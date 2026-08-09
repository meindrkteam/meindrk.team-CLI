#!/usr/bin/env bash
# Prueft: kalender list zeigt id/projektID/name, dazu besitz (wem gehoert der
# Kalender) und schreiben (darf ich hineinschreiben). Beides ist unabhaengig:
# ein Kalender eines fremden Kreisverbands kann beschreibbar sein, einer des
# eigenen nur lesbar.
#
# Massgeblich fuer "schreiben" ist CalendarService.accessibleCalenderWhereClause
# im Hauptrepo. Dort gibt es zwei sich ausschliessende Zweige: fuer eine
# PERSON-Sitzung zaehlen personID und Gruppen, fuer eine BENUTZER-Sitzung
# ausschliesslich CalendarAccess.benutzerID (plus Suchtemplates). Die CLI
# arbeitet immer mit einer Benutzer-Sitzung.
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
python3 - "$PORT" "$MITSCHRIFT" "$BENUTZER" <<'SERVER' &
import sys, json
from http.server import BaseHTTPRequestHandler, HTTPServer

port, mitschrift, benutzerdatei = int (sys.argv[1]), sys.argv[2], sys.argv[3]

def zugriff (benutzerID=-1, personID=-1, gruppeID=-1, template=-1, schreiben=False):
    return {"benutzerID": benutzerID, "personID": personID, "gruppeID": gruppeID,
            "tagSearchTemplateID": template, "writeAccess": schreiben}

# calendarAccesses stammen aus der Extended-Sicht (Calendar.getCalendarAccesses).
KALENDER = [
    {"id": 1, "projektID": 42, "benutzerID": 7,  "personID": -1, "name": "MeinKalender",
     "calendarAccesses": []},
    {"id": 2, "projektID": 42, "benutzerID": -1, "personID": 70, "name": "MeinePerson",
     "calendarAccesses": []},
    {"id": 3, "projektID": 42, "benutzerID": 99, "personID": -1, "name": "NurLesen",
     "calendarAccesses": [zugriff (benutzerID=7, schreiben=False)]},
    {"id": 4, "projektID": 88, "benutzerID": 99, "personID": -1, "name": "Nachbarverband",
     "calendarAccesses": [zugriff (benutzerID=7, schreiben=True)]},
    {"id": 5, "projektID": 42, "benutzerID": 99, "personID": -1, "name": "NurGruppe",
     "calendarAccesses": [zugriff (gruppeID=500, schreiben=True)]},
    {"id": 6, "projektID": 42, "benutzerID": 99, "personID": -1, "name": "FremderBenutzer",
     "calendarAccesses": [zugriff (benutzerID=99, schreiben=True)]},
    {"id": 7, "projektID": 42, "benutzerID": 99, "personID": -1, "name": "UeberTemplate",
     "calendarAccesses": [zugriff (template=12, schreiben=True)]},
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
SERVER
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

MSYS_NO_PATHCONV=1 python3 - "$(grep -v current-user "$MITSCHRIFT" | head -1)" "$out" <<'PRUEF' || fail=1
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
PRUEF

# ── besitz: wem der Kalender gehoert ────────────────────────────────────────
MSYS_NO_PATHCONV=1 python3 - "$out" <<'PRUEF' || fail=1
import sys, json
nach_id = {k["id"]: k for k in json.loads (sys.argv[1])["data"]}
erwartet = {1: "eigen", 2: "eigen", 3: "eigener_kv", 4: "fremder_kv",
            5: "eigener_kv", 6: "eigener_kv", 7: "eigener_kv"}
for kid, soll in erwartet.items ():
    ist = nach_id[kid].get ("besitz")
    assert ist == soll, f"Kalender {kid}: besitz erwartet {soll}, bekam {ist}"
print ("  ok: besitz eigen / eigener_kv / fremder_kv")
PRUEF

# ── schreiben: allein ueber CalendarAccess.benutzerID ───────────────────────
#    Kalender 4 ist der Kern: fremder Kreisverband, aber beschreibbar. Besitz
#    und Schreibrecht fallen auseinander.
MSYS_NO_PATHCONV=1 python3 - "$out" <<'PRUEF' || fail=1
import sys, json
nach_id = {k["id"]: k for k in json.loads (sys.argv[1])["data"]}
erwartet = {
    1: True,   # Besitzer (Calendar.benutzerID)
    2: False,  # Besitz ueber personID zaehlt im Benutzer-Zweig nicht
    3: False,  # Freigabe an mich, aber writeAccess=false
    4: True,   # Freigabe an mich mit writeAccess=true -- fremder KV!
    5: False,  # nur Gruppen-Freigabe -> im Benutzer-Zweig ohne Wirkung
    6: False,  # Freigabe an einen anderen Benutzer
}
for kid, soll in erwartet.items ():
    ist = nach_id[kid].get ("schreiben")
    assert ist == soll, f"Kalender {kid}: schreiben erwartet {soll}, bekam {ist}"

# Suchtemplate: ob es auf mich zutrifft, steht in einer Server-Cache-Tabelle,
# die von aussen nicht abfragbar ist. Dann lieber KEINE Angabe als ein
# falsches "du darfst nicht".
assert "schreiben" not in nach_id[7], nach_id[7]
print ("  ok: schreiben ueber benutzerID; Suchtemplate bleibt offen")
PRUEF

# ── Keine Gruppen-Abfragen: der Benutzer-Zweig braucht sie nicht ───────────
MSYS_NO_PATHCONV=1 python3 - "$MITSCHRIFT" <<'PRUEF' || fail=1
import sys
assert "/PersonIds" not in open (sys.argv[1]).read (), \
    "Gruppen duerfen fuer eine Benutzer-Sitzung nicht abgefragt werden"
print ("  ok: keine ueberfluessigen Gruppen-Abfragen")
PRUEF

# ── current-user wird genau EINMAL geholt, nicht je Zeile ───────────────────
anzahl="$(grep -c current-user "$MITSCHRIFT")"
if [ "$anzahl" = "1" ]; then
  echo "  ok: current-user einmal geholt"
else
  echo "  FAIL: current-user $anzahl-mal geholt, erwartet 1"; fail=1
fi

# ── Ohne ermittelbaren Benutzer wird nichts behauptet ──────────────────────
echo '{}' > "$BENUTZER"
out="$(run --json kalender list --kvid 42 2>&1)"
MSYS_NO_PATHCONV=1 python3 - "$out" <<'PRUEF' || fail=1
import sys, json
daten = json.loads (sys.argv[1])["data"]
assert {k.get ("besitz") for k in daten} == {"unbekannt"}, daten
assert all ("schreiben" not in k for k in daten), daten
print ("  ok: ohne Benutzer -> unbekannt, kein geratenes Schreibrecht")
PRUEF

# ── Die Liste bleibt brauchbar, auch wenn current-user ganz ausfaellt ───────
echo 'kein json' > "$BENUTZER"
out="$(run --json kalender list --kvid 42 2>&1)"
MSYS_NO_PATHCONV=1 python3 - "$out" <<'PRUEF' || fail=1
import sys, json
d = json.loads (sys.argv[1])
assert d["ok"] is True and d["count"] == 7, d
assert {k.get ("besitz") for k in d["data"]} == {"unbekannt"}, d["data"]
print ("  ok: kaputtes current-user kippt die Auflistung nicht")
PRUEF

# ── Ohne --kvid kein Filter: alle zugaenglichen Kalender ───────────────────
#    Der Server begrenzt ueber die Sitzung. Genau deshalb darf der Aufrufer
#    nie nach einem Kreisverband gefragt werden.
echo '{"id":7,"personID":70,"projektID":42,"login":"testuser"}' > "$BENUTZER"
: > "$MITSCHRIFT"
out="$(run --json kalender list 2>&1)"
MSYS_NO_PATHCONV=1 python3 - "$(grep -v current-user "$MITSCHRIFT" | head -1)" <<'PRUEF' || fail=1
import sys, urllib.parse
qs = urllib.parse.parse_qs (urllib.parse.urlparse (sys.argv[1]).query)
assert "filter" not in qs or qs["filter"] == ["[]"], qs.get ("filter")
print ("  ok: ohne --kvid wird nicht gefiltert")
PRUEF

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
