#!/usr/bin/env bash
# Prueft die Annahmen, unter denen ein Dienst dieses CLI als Unterprozess aufruft:
#   C1  Aufruf aus einem Nicht-Shell-Elternprozess liefert Daten, nicht die GUI
#   C2  --insecure aus einem Modell-Wert heraus schaltet TLS NICHT ab
#   C3  Person-ID kann den URL-Pfad nicht verlassen
#   I4  Kreisverband-ID kann den JSON-Filter nicht erweitern
#   I5  --json liefert auch bei help / fehlendem Befehl einen Envelope
set -uo pipefail
cd "$(dirname "$0")/.."

CLASSES=/tmp/cliclasses-sicherheit
rm -rf "$CLASSES" && mkdir -p "$CLASSES"
javac --release 21 -cp "lib/jackson/*" -d "$CLASSES" src/de/kreisalarm/cli/*.java || {
  echo "FAIL: kompiliert nicht"; exit 1; }

FAKEHOME=/tmp/cli-sicherheit-home
rm -rf "$FAKEHOME" && mkdir -p "$FAKEHOME"

WORK=/tmp/cli-sicherheit
rm -rf "$WORK" && mkdir -p "$WORK"
LOG="$WORK/requests.log"
: > "$LOG"

HTTP_PORT=59900
HTTPS_PORT=59901
CP="$CLASSES:lib/jackson/*"

run () {  # $1 = URL, Rest = Argumente
  local url="$1"; shift
  HOME="$FAKEHOME" MEINDRK_URL="$url" MEINDRK_SESSION=DUMMY MEINDRK_KVID=1 \
    java -cp "$CP" de.kreisalarm.cli.CLI "$@"
}

fail=0
melde () { echo "  FAIL: $1"; fail=1; }

# ---------------------------------------------------------------------------
# Mock-Server: protokolliert JEDE eingehende Anfrage mit Pfad und Query.
# ---------------------------------------------------------------------------
cat > "$WORK/mock.py" <<'PY'
import http.server, ssl, sys

LOG = sys.argv[2]

class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        with open(LOG, "a", encoding="utf-8") as f:
            f.write(self.path + "\n")
        body = b'{"success":true,"root":[]}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass

if __name__ == "__main__":
    port = int(sys.argv[1])
    srv = http.server.HTTPServer(("127.0.0.1", port), Handler)
    if len(sys.argv) > 3:                       # argv[3] = PEM mit Key+Cert
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(sys.argv[3])
        srv.socket = ctx.wrap_socket(srv.socket, server_side=True)
    srv.serve_forever()
PY

openssl req -x509 -newkey rsa:2048 -nodes -days 2 \
  -subj "/CN=127.0.0.1" -addext "subjectAltName=IP:127.0.0.1" \
  -keyout "$WORK/key.pem" -out "$WORK/cert.pem" >/dev/null 2>&1 || {
  echo "FAIL: openssl fehlt – Selbstsigniertes Zertifikat nicht erzeugbar"; exit 1; }
cat "$WORK/key.pem" "$WORK/cert.pem" > "$WORK/server.pem"

python3 "$WORK/mock.py" "$HTTP_PORT"  "$LOG" &
HTTP_PID=$!
python3 "$WORK/mock.py" "$HTTPS_PORT" "$LOG" "$WORK/server.pem" &
HTTPS_PID=$!
trap 'kill "$HTTP_PID" "$HTTPS_PID" 2>/dev/null' EXIT

for i in $(seq 1 40); do
  curl -s -o /dev/null      "http://127.0.0.1:$HTTP_PORT/x"  && break; sleep 0.1
done
for i in $(seq 1 40); do
  curl -sk -o /dev/null "https://127.0.0.1:$HTTPS_PORT/x" && break; sleep 0.1
done
: > "$LOG"   # Bereitschaftspings verwerfen

# ---------------------------------------------------------------------------
# C1 – Aufruf aus einem Nicht-Shell-Elternprozess (Python, wie der Dienst).
#      Aus bash heraus ist dieser Fehler prinzipiell unsichtbar.
# ---------------------------------------------------------------------------
cat > "$WORK/elternprozess.py" <<'PY'
"""Startet das CLI so, wie der FastAPI-Dienst es tut: als Unterprozess eines
Python-Prozesses. Der Elternprozess heisst dann 'python3' und nicht 'bash'."""
import json, os, subprocess, sys

cp, url, home = sys.argv[1], sys.argv[2], sys.argv[3]
env = dict(os.environ, HOME=home, MEINDRK_URL=url, MEINDRK_SESSION="DUMMY", MEINDRK_KVID="1")
argv = ["java", "-cp", cp, "de.kreisalarm.cli.CLI"] + sys.argv[4:]
try:
    p = subprocess.run(argv, capture_output=True, text=True, timeout=25, env=env)
except subprocess.TimeoutExpired:
    print("TIMEOUT: CLI kehrte nicht zurueck (GUI-Server blockiert?)"); sys.exit(3)
if not p.stdout.strip():
    print("LEER: exit=%d, stdout leer, stderr=%r" % (p.returncode, p.stderr[:200])); sys.exit(4)
try:
    d = json.loads(p.stdout)
except ValueError:
    print("KEIN JSON: %r" % p.stdout[:200]); sys.exit(5)
print("OK %s" % json.dumps(d)[:80])
PY

for befehl in "--json person list --q Test" "--json manifest"; do
  out="$(python3 "$WORK/elternprozess.py" "$CP" "http://127.0.0.1:$HTTP_PORT" "$FAKEHOME" $befehl 2>&1)"
  case "$out" in
    OK\ *) echo "  ok: '$befehl' aus Python-Elternprozess -> ${out#OK }" ;;
    *)     melde "'$befehl' aus Python-Elternprozess: $out" ;;
  esac
done

# ---------------------------------------------------------------------------
# C2 – --insecure als WERT eines Flags darf TLS nicht abschalten.
# ---------------------------------------------------------------------------
err="$(run "https://127.0.0.1:$HTTPS_PORT" --json person list --q --insecure 2>&1 >/dev/null)"
echo "$err" | python3 -c '
import sys, json
d = json.loads(sys.stdin.read().strip())
assert d["ok"] is False, d
assert "TLS" in d["error"] or "SSL" in d["error"].upper(), d
assert "--insecure" not in d["error"], "Fehlertext schlaegt --insecure vor: " + d["error"]
print("  ok: --q --insecure schaltet TLS nicht ab ->", d["error"][:60])
' || { melde "--insecure als Flag-Wert hat die TLS-Pruefung abgeschaltet"; echo "  war: ${err:0:200}"; }

# Gegenprobe: an der vorgesehenen Stelle (vor dem Befehl) wirkt --insecure weiter.
out="$(run "https://127.0.0.1:$HTTPS_PORT" --insecure --json person list --q Test 2>/dev/null)"
echo "$out" | python3 -c '
import sys, json
d = json.loads(sys.stdin.read().strip())
assert d["ok"] is True, d
print("  ok: --insecure vor dem Befehl wirkt weiterhin")
' || melde "--insecure vor dem Befehl wirkt nicht mehr (Regression)"

# ---------------------------------------------------------------------------
# C3 – Person-ID darf den URL-Pfad nicht verlassen.
# ---------------------------------------------------------------------------
: > "$LOG"
err="$(run "http://127.0.0.1:$HTTP_PORT" --json person get '../store/Benutzer/view/Extended?limit=9999&x=' 2>&1 >/dev/null)"
echo "$err" | python3 -c '
import sys, json
d = json.loads(sys.stdin.read().strip())
assert d["ok"] is False, d
assert "Zahl" in d["error"], d
print("  ok: Pfad-Ausbruch in person get abgewiesen ->", d["error"])
' || { melde "person get akzeptiert eine ID mit Pfadanteilen"; echo "  war: ${err:0:200}"; }
if [ -s "$LOG" ]; then
  melde "trotz ungueltiger Person-ID ging eine Anfrage an den Server: $(head -1 "$LOG")"
else
  echo "  ok: keine Anfrage an den Server abgesetzt"
fi

# ---------------------------------------------------------------------------
# I4 – Kreisverband-ID darf den JSON-Filter nicht erweitern.
#      (a) CLI weist sie ab, (b) RestClient baut den Filter escaped mit Jackson.
# ---------------------------------------------------------------------------
: > "$LOG"
err="$(run "http://127.0.0.1:$HTTP_PORT" --json person list --kvid '1","exact":false,"passwort":"x' 2>&1 >/dev/null)"
echo "$err" | python3 -c '
import sys, json
d = json.loads(sys.stdin.read().strip())
assert d["ok"] is False, d
assert "Zahl" in d["error"], d
print("  ok: Filter-Injektion ueber --kvid abgewiesen ->", d["error"])
' || { melde "--kvid akzeptiert JSON-Sonderzeichen"; echo "  war: ${err:0:200}"; }
[ -s "$LOG" ] && melde "trotz ungueltiger kvid ging eine Anfrage raus: $(head -1 "$LOG")" \
              || echo "  ok: keine Anfrage an den Server abgesetzt"

# (b) direkt gegen RestClient, damit die Jackson-Kodierung selbst geprueft wird
mkdir -p "$WORK/j"
cat > "$WORK/j/FilterProbe.java" <<'JAVA'
import de.kreisalarm.cli.Config;
import de.kreisalarm.cli.RestClient;

public class FilterProbe {
    public static void main (String[] a) throws Exception {
        new RestClient (new Config ()).getList ("Person", 5, null, a[0]);
    }
}
JAVA
javac -cp "$CP" -d "$WORK/j" "$WORK/j/FilterProbe.java" 2>/dev/null || {
  melde "FilterProbe kompiliert nicht"; }
: > "$LOG"
HOME="$FAKEHOME" MEINDRK_URL="http://127.0.0.1:$HTTP_PORT" MEINDRK_SESSION=DUMMY \
  java -cp "$CP:$WORK/j" FilterProbe '1","exact":false,"passwort":"x' >/dev/null 2>&1
python3 - "$LOG" <<'PY' || melde "RestClient klebt den Filter zusammen statt ihn zu kodieren"
import json, sys, urllib.parse
zeilen = [z.strip() for z in open(sys.argv[1], encoding="utf-8") if z.strip()]
assert zeilen, "keine Anfrage protokolliert"
q = urllib.parse.parse_qs(urllib.parse.urlparse(zeilen[0]).query)
f = json.loads(q["filter"][0])
assert len(f) == 1, f
assert set(f[0]) == {"property", "value", "exact"}, "fremde Felder im Filter: %s" % f[0]
assert f[0]["exact"] is True, f[0]
assert f[0]["value"] == '1","exact":false,"passwort":"x', f[0]
print("  ok: RestClient kodiert den Filter, Sonderzeichen bleiben ein Wert")
PY

# ---------------------------------------------------------------------------
# I5 – JSON-Modus liefert immer einen Envelope.
# ---------------------------------------------------------------------------
out="$(run "http://127.0.0.1:$HTTP_PORT" --json help 2>/dev/null)"; code=$?
echo "$out" | python3 -c '
import sys, json
d = json.loads(sys.stdin.read().strip())
assert d["ok"] is True, d
print("  ok: --json help ->", json.dumps(d["data"], ensure_ascii=False)[:70])
' || { melde "--json help liefert keinen Envelope"; echo "  war: ${out:0:200}"; }
[ "$code" = "0" ] || melde "--json help endet mit Exit $code statt 0"

err="$(run "http://127.0.0.1:$HTTP_PORT" --json 2>&1 >/dev/null)"; code=$?
echo "$err" | python3 -c '
import sys, json
d = json.loads(sys.stdin.read().strip())
assert d["ok"] is False, d
print("  ok: --json ohne Befehl ->", d["error"][:70])
' || { melde "--json ohne Befehl liefert keinen Envelope"; echo "  war: ${err:0:200}"; }
[ "$code" = "1" ] || melde "--json ohne Befehl endet mit Exit $code statt 1"

# Ohne --json bleibt help menschenlesbar
out="$(run "http://127.0.0.1:$HTTP_PORT" help 2>/dev/null)"
case "$out" in
  '{"ok"'*) melde "help ohne --json gibt JSON aus" ;;
  *meinDRK*) echo "  ok: help ohne --json bleibt Klartext" ;;
  *) melde "help ohne --json gibt nichts Sinnvolles aus" ;;
esac

[ "$fail" = "0" ] && echo "OK sicherheit" || exit 1
