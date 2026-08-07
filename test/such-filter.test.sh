#!/usr/bin/env bash
# Prueft die Suche (--q) gegen einen Fake-Server:
#   1. Der Store kennt keinen Parameter "query" – gesucht wird ueber "filter".
#   2. Ein Envelope mit success=false darf NIE als ok=true durchgehen.
#   3. Fehlendes "root" ergibt count=0, nicht count=1 mit data=null.
set -uo pipefail
cd "$(dirname "$0")/.."
source test/lib/portable.sh

CLASSES=/tmp/cliclasses-such
rm -rf "$CLASSES" && mkdir -p "$CLASSES"
javac --release 21 -cp "lib/jackson/*" -d "$CLASSES" src/de/kreisalarm/cli/*.java || {
  echo "FAIL: kompiliert nicht"; exit 1; }
CP="$(winpath "$CLASSES")${CP_SEP}$(winpath "$PWD")/lib/jackson/*"

FAKEHOME=/tmp/cli-fakehome-such
rm -rf "$FAKEHOME" && mkdir -p "$FAKEHOME"

MITSCHRIFT=/tmp/cli-fake-requests.txt
: > "$MITSCHRIFT"

# ── Fake-Server: schreibt jede Anfrage mit und antwortet je nach Pfad ─────────
PORT=58731
python3 - "$PORT" "$MITSCHRIFT" <<'PY' &
import sys, json
from http.server import BaseHTTPRequestHandler, HTTPServer

port, mitschrift = int(sys.argv[1]), sys.argv[2]

class H(BaseHTTPRequestHandler):
    def do_GET(self):
        with open(mitschrift, "a") as f:
            f.write(self.path + "\n")
        if "Kaputt" in self.path:              # Store lehnt ab
            body = {"success": False, "error": 1223664}
        elif "Leer" in self.path:              # kein root im Envelope
            body = {"success": True}
        else:
            body = {"success": True, "total": 1,
                    "root": [{"id": 1, "nachname": "Müller", "vorname": "Anna"}]}
        roh = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(roh)))
        self.end_headers()
        self.wfile.write(roh)
    def log_message(self, *a):
        pass

HTTPServer(("127.0.0.1", port), H).serve_forever()
PY
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null' EXIT
for _ in $(seq 1 40); do
  curl -sf "http://127.0.0.1:$PORT/ping" >/dev/null 2>&1 && break
  sleep 0.2
done

run () {  # Argumente der CLI
  HOME="$FAKEHOME" MEINDRK_URL="http://127.0.0.1:$PORT" \
    MEINDRK_SESSION=DUMMY MEINDRK_KVID=1 \
    java -cp "$CP" de.kreisalarm.cli.CLI "$@"
}

fail=0

# ── 1) Suche geht ueber filter, nicht ueber query ────────────────────────────
: > "$MITSCHRIFT"
out="$(run --json person list --q "Müller" 2>&1)"
pfad="$(cat "$MITSCHRIFT")"
python3 - "$pfad" "$out" <<'PY' || fail=1
import sys, json, urllib.parse
pfad, out = sys.argv[1], sys.argv[2]
qs = urllib.parse.parse_qs(urllib.parse.urlparse(pfad).query)
assert "query" not in qs, f"Parameter query wird vom Store abgelehnt: {pfad}"
assert "filter" in qs, f"keine filter-Angabe: {pfad}"
f = json.loads(qs["filter"][0])
nach = [e for e in f if e.get("property") == "nachname"]
assert nach and nach[0]["value"] == "Müller", f
d = json.loads(out)
assert d["ok"] is True and d["count"] == 1, d
PY

# ── 2) Suche plus Kreisverband: beide Filter, ein Aufruf ─────────────────────
: > "$MITSCHRIFT"
run --json person list --q "Müller" --kvid 42 >/dev/null 2>&1
python3 - "$(cat "$MITSCHRIFT")" <<'PY' || fail=1
import sys, json, urllib.parse
qs = urllib.parse.parse_qs(urllib.parse.urlparse(sys.argv[1]).query)
f = json.loads(qs["filter"][0])
eigenschaften = {e["property"]: e for e in f}
assert "nachname" in eigenschaften and "projektID" in eigenschaften, f
assert eigenschaften["projektID"]["value"] == "42", f
assert eigenschaften["projektID"].get("exact") is True, f
PY

# ── 3) Gruppen suchen ueber name, nicht ueber nachname ───────────────────────
: > "$MITSCHRIFT"
run --json gruppe list --q "Bereitschaft" >/dev/null 2>&1
python3 - "$(cat "$MITSCHRIFT")" <<'PY' || fail=1
import sys, json, urllib.parse
qs = urllib.parse.parse_qs(urllib.parse.urlparse(sys.argv[1]).query)
f = json.loads(qs["filter"][0])
assert any(e.get("property") == "name" for e in f), f
PY

# ── 4) success=false darf nicht als Erfolg durchgehen ────────────────────────
out="$(run --json person list --q "Kaputt" 2>&1 >/dev/null)"; code=$?
python3 - "$out" "$code" <<'PY' || fail=1
import sys, json
out, code = sys.argv[1], sys.argv[2]
d = json.loads(out.strip())          # muss JSON auf stderr sein
assert d["ok"] is False, d
assert "1223664" in d["error"] or "abgelehnt" in d["error"].lower(), d
assert "Exception" not in d["error"], d
assert code == "1", code
PY

# ── 5) Envelope ohne root -> count 0, nicht count 1 mit data null ────────────
out="$(run --json person list --q "Leer" 2>&1)"
python3 - "$out" <<'PY' || fail=1
import sys, json
d = json.loads(sys.argv[1])
assert d["ok"] is True, d
assert d["count"] == 0, f"leeres Ergebnis darf nicht count=1 melden: {d}"
assert d["data"] in (None, []), d
PY

[ "$fail" -eq 0 ] && echo "OK such-filter" || echo "FAIL such-filter"
exit "$fail"
