#!/usr/bin/env bash
# Prueft RestClient.post/put/delete direkt (ohne CLI-Kommandos):
#   1. Methode, Pfad, Content-Type und JSON-Body kommen unveraendert beim Server an.
#   2. success:false im Envelope wird als Exception erkannt (requireErfolg), auch
#      wenn der HTTP-Status 200 ist.
#   3. HTTP >=400 wird als Exception erkannt (requireOk), auch fuer post/put/delete.
set -uo pipefail
cd "$(dirname "$0")/.."
source test/lib/portable.sh

CLASSES=/tmp/cliclasses-schreiben
rm -rf "$CLASSES" && mkdir -p "$CLASSES"
javac --release 21 -cp "lib/jackson/*" -d "$CLASSES" src/de/kreisalarm/cli/*.java || {
  echo "FAIL: kompiliert nicht"; exit 1; }

WORK=/tmp/cli-schreiben
rm -rf "$WORK" && mkdir -p "$WORK/j"
LOG="$WORK/requests.log"
: > "$LOG"
CP="$(winpath "$CLASSES")${CP_SEP}$(winpath "$PWD")/lib/jackson/*"

cat > "$WORK/j/SchreibProbe.java" <<'JAVA'
import de.kreisalarm.cli.Config;
import de.kreisalarm.cli.RestClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class SchreibProbe {
    public static void main (String[] a) throws Exception {
        String modus = a[0];
        String path  = a[1];
        RestClient client = new RestClient (new Config ());
        ObjectMapper mapper = new ObjectMapper ();
        ObjectNode body = mapper.createObjectNode ();
        body.put ("name", "Testtermin");
        body.put ("calendarID", 7);
        switch (modus) {
            case "post":   System.out.println (client.post (path, body)); break;
            case "put":    System.out.println (client.put (path, body)); break;
            case "delete": client.delete (path); System.out.println ("{\"deleted\":true}"); break;
            default: throw new IllegalArgumentException (modus);
        }
    }
}
JAVA
javac -cp "$CP" -d "$WORK/j" "$WORK/j/SchreibProbe.java" || { echo "FAIL: SchreibProbe kompiliert nicht"; exit 1; }

fail=0
melde () { echo "  FAIL: $1"; fail=1; }

# ---------------------------------------------------------------------------
# Mock-Server: protokolliert Methode+Pfad+Body, antwortet je nach Pfad.
# ---------------------------------------------------------------------------
cat > "$WORK/mock.py" <<'PY'
import http.server, sys, json

LOG = sys.argv[2]

class Handler (http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _handle (self, methode):
        laenge = int (self.headers.get ("Content-Length", 0))
        rohbody = self.rfile.read (laenge).decode ("utf-8") if laenge else ""
        with open (LOG, "a", encoding="utf-8") as f:
            f.write (json.dumps ({"methode": methode, "pfad": self.path,
                                   "contentType": self.headers.get ("Content-Type", ""),
                                   "body": rohbody}) + "\n")
        if "Kaputt" in self.path:
            antwort = {"success": False, "error": "kein Recht"}
            status = 200
        elif "Fehler500" in self.path:
            self.send_response (500)
            self.send_header ("Content-Length", "0")
            self.end_headers ()
            return
        elif methode == "PUT":
            antwort = {"root": [{"id": 5, "name": "Testtermin geaendert"}], "total": 1}
            status = 200
        elif methode == "DELETE":
            antwort = {"success": True}
            status = 200
        else:  # POST
            antwort = {"id": 42, "name": "Testtermin", "calendarID": 7}
            status = 200
        roh = json.dumps (antwort).encode ()
        self.send_response (status)
        self.send_header ("Content-Type", "application/json")
        self.send_header ("Content-Length", str (len (roh)))
        self.end_headers ()
        self.wfile.write (roh)

    def do_POST (self):   self._handle ("POST")
    def do_PUT (self):    self._handle ("PUT")
    def do_DELETE (self): self._handle ("DELETE")
    def do_GET (self):    self._handle ("GET")
    def log_message (self, *a): pass

if __name__ == "__main__":
    http.server.HTTPServer (("127.0.0.1", int (sys.argv[1])), Handler).serve_forever ()
PY

PORT=58911
python3 "$WORK/mock.py" "$PORT" "$LOG" &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null' EXIT
for i in $(seq 1 40); do
  curl -s -o /dev/null "http://127.0.0.1:$PORT/ping" && break; sleep 0.1
done

FAKEHOME=/tmp/cli-schreiben-home
rm -rf "$FAKEHOME" && mkdir -p "$FAKEHOME"
lauf () {  # modus pfad
  HOME="$FAKEHOME" MEINDRK_URL="http://127.0.0.1:$PORT" MEINDRK_SESSION=DUMMY \
    MSYS_NO_PATHCONV=1 java -cp "${CP}${CP_SEP}$(winpath "$WORK/j")" SchreibProbe "$1" "$2"
}

# ── 1) POST: Methode, Content-Type, Body kommen korrekt an ──────────────────
: > "$LOG"
lauf post /backend/rest/CalendarEvent >/dev/null 2>&1
python3 - "$(cat "$LOG")" <<'PY' || { melde "POST-Anfrage falsch protokolliert"; }
import sys, json
d = json.loads (sys.argv[1])
assert d["methode"] == "POST", d
assert d["pfad"] == "/backend/rest/CalendarEvent", d
assert "application/json" in d["contentType"], d
body = json.loads (d["body"])
assert body == {"name": "Testtermin", "calendarID": 7}, body
print ("  ok: POST sendet Methode/Content-Type/Body korrekt")
PY

# ── 2) PUT: Methode + Pfad korrekt, Antwort (root[0]) kommt durch ───────────
: > "$LOG"
out="$(lauf put /backend/rest/CalendarEvent/5 2>&1)"
python3 - "$(cat "$LOG")" "$out" <<'PY' || { melde "PUT-Anfrage falsch"; }
import sys, json
d = json.loads (sys.argv[1])
assert d["methode"] == "PUT" and d["pfad"] == "/backend/rest/CalendarEvent/5", d
out = json.loads (sys.argv[2])
assert out["root"][0]["name"] == "Testtermin geaendert", out
print ("  ok: PUT sendet Methode/Pfad korrekt, Antwort kommt durch")
PY

# ── 3) DELETE: Methode + Pfad korrekt, kein Body noetig ─────────────────────
: > "$LOG"
lauf delete /backend/rest/CalendarEvent/5 >/dev/null 2>&1
python3 - "$(cat "$LOG")" <<'PY' || { melde "DELETE-Anfrage falsch"; }
import sys, json
d = json.loads (sys.argv[1])
assert d["methode"] == "DELETE" and d["pfad"] == "/backend/rest/CalendarEvent/5", d
print ("  ok: DELETE sendet Methode/Pfad korrekt")
PY

# ── 4) success:false wird bei allen drei Methoden als Fehler erkannt ────────
for modus in post put delete; do
  err="$(lauf "$modus" /backend/rest/Kaputt 2>&1 >/dev/null)"
  case "$err" in
    *"kein Recht"*) echo "  ok: $modus meldet success:false als Fehler" ;;
    *) melde "$modus schluckt success:false -> war: ${err:0:150}" ;;
  esac
done

# ── 5) HTTP 500 wird bei allen drei Methoden als Fehler erkannt ─────────────
for modus in post put delete; do
  err="$(lauf "$modus" /backend/rest/Fehler500 2>&1 >/dev/null)"
  case "$err" in
    *"500"*) echo "  ok: $modus meldet HTTP 500 als Fehler" ;;
    *) melde "$modus schluckt HTTP 500 -> war: ${err:0:150}" ;;
  esac
done

[ "$fail" = "0" ] && echo "OK restclient-schreiben" || exit 1
