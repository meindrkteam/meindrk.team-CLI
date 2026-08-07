# CLI CRUD für Termine (CalendarEvent) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Vier neue CLI-Befehle (`termin list|get|create|update|delete`) plus einen Hilfsbefehl (`kalender list`) ergänzen, damit das meinDRK-CLI Termine (`CalendarEvent`) nicht nur lesen, sondern auch anlegen, ändern und löschen kann.

**Architektur:** `RestClient.java` bekommt `post`/`put`/`delete`, die dieselbe HTTP/Cookie/Fehlerbehandlung wie `get()` nutzen (`requireOk`, `requireErfolg`). `CLI.java` bekommt neue `run*`-Methoden nach dem Muster von `runPerson`/`runGruppe`, neue Validatoren (`pruefeDatum`, `pruefeZeit`, `pruefeEnum`, `pruefeBool`) und erweitert `WERT_FLAGS` sowie `runManifest`.

**Tech Stack:** Java 21 (`javac --release 21`), Jackson (`lib/jackson/*.jar`), keine externen Test-Frameworks — Tests sind ausführbare Bash-Skripte (`test/*.test.sh`) mit eingebettetem Python-Mock-HTTP-Server, siehe bestehende `test/such-filter.test.sh` als Referenzmuster.

## Global Constraints

- Spec: `docs/specs/2026-08-07-cli-termin-crud-design.md` — verbindlich für Feldnamen, Pflichtfelder, Endpunkte.
- `WERT_FLAGS` (`CLI.java`) muss jedes neue wertnehmende Flag enthalten — sonst kann ein Flag-Wert als globaler Schalter (`--insecure`) missbraucht werden (siehe `test/sicherheit.test.sh`, Fall C2).
- IDs, die in URL-Pfade oder Filter eingehen, laufen immer durch `pruefeId()` — nie unvalidiert in `client.get/post/put/delete`.
- `--json`-Modus liefert bei jedem Fehler einen Envelope `{"ok":false,"error":"..."}` auf stderr, Exit-Code 1 — nie einen Java-Stacktrace.
- Kein neuer Server-Code im Hauptrepo (`src/de/kreisalarm/...`) — alle Endpunkte existieren bereits generisch.
- Commit-Message-Stil dieses Repos: `feat(cli): ...` / `fix(cli): ...` / `test(cli): ...` (siehe `git log --oneline`).

## Lokale Testumgebung (dieser Windows-Rechner)

Die Tests `test/*.test.sh` sind für Linux/macOS-Shells geschrieben (`javac` über `:`-Classpath, `python3`-Kommando). Auf diesem Windows-Rechner (Git Bash, kein WSL installiert) vor jedem Testlauf:

```bash
export PATH="/d/Program Files/Java/jdk21.0.2_13/bin:$PATH"   # javac --release 21 sonst nicht gefunden
```

`python3` ist auf diesem Rechner nur ein nicht funktionierender Microsoft-Store-Alias; echtes Python 3.13 ist über den `py`-Launcher erreichbar. Vor dem ersten Testlauf einmalig einen Shim anlegen (liegt aus `$PATH` außerhalb des Repos, keine Repo-Änderung):

```bash
printf '#!/usr/bin/env bash\nexec py "$@"\n' > /c/Users/joern/bin/python3
chmod +x /c/Users/joern/bin/python3
```

(`/c/Users/joern/bin` steht bereits vorn in `$PATH`.) Ohne diesen Shim schlagen alle `test/*.test.sh` mit "Python was not found" fehl — das ist ein Umgebungsproblem dieses Rechners, kein Fehler der Skripte.

**Classpath-Trenner/Pfade:** Natives `java.exe`/`javac.exe` unter Windows versteht weder `:` als Classpath-Trenner noch rohe `/tmp/...`-Pfade. Dafür existiert bereits `test/lib/portable.sh` (Commit `build(test): test/*.test.sh nativ unter Windows (Git Bash) lauffaehig machen`) mit `CP_SEP` (`;` unter Windows/Git Bash, sonst `:`) und `winpath()` (löst einen POSIX-Pfad über `pwd -W` in einen von `java`/`javac` nutzbaren Pfad auf; auf Linux/macOS ein No-op). **Jedes neue Testskript in diesem Plan muss das nutzen** — direkt nach `cd "$(dirname "$0")/.."` einfügen:

```bash
source test/lib/portable.sh
```

und die Classpath-Variable so bauen (statt `CP="$CLASSES:lib/jackson/*"`):

```bash
CP="$(winpath "$CLASSES")${CP_SEP}$(winpath "$PWD")/lib/jackson/*"
```

Alle sechs Testskript-Vorlagen weiter unten in diesem Plan sind bereits in dieser portablen Form geschrieben — beim Anlegen exakt so übernehmen, nicht auf das alte `"$CLASSES:lib/jackson/*"`-Muster zurückfallen.

---

### Task 1: RestClient — post/put/delete

**Files:**
- Modify: `src/de/kreisalarm/cli/RestClient.java`
- Test: `test/restclient-schreiben.test.sh` (neu)

**Interfaces:**
- Produces: `RestClient.post(String path, ObjectNode body): JsonNode`, `RestClient.put(String path, ObjectNode body): JsonNode`, `RestClient.delete(String path): void` — alle werfen `Exception` mit Klartext-Message bei HTTP-Fehler (`requireOk`) oder `success:false`-Envelope (`requireErfolg`), analog zu `get()`.

- [ ] **Step 1: Test schreiben — RestClient.post/put/delete senden korrekte HTTP-Requests und prüfen den Erfolg zentral**

Erstelle `test/restclient-schreiben.test.sh`:

```bash
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
    java -cp "${CP}${CP_SEP}$(winpath "$WORK/j")" SchreibProbe "$1" "$2"
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
```

- [ ] **Step 2: Test ausführen, sicherstellen dass er an fehlenden Methoden scheitert**

```bash
export PATH="/d/Program Files/Java/jdk21.0.2_13/bin:$PATH"
bash test/restclient-schreiben.test.sh
```

Erwartet: `FAIL: SchreibProbe kompiliert nicht` (`post`/`put`/`delete` existieren noch nicht in `RestClient`).

- [ ] **Step 3: `post`/`put`/`delete` in `RestClient.java` implementieren**

Nach der bestehenden `get(String path, Map<String,String> params)`-Methode (vor `getList`) einfügen:

```java
    public JsonNode post (String path, ObjectNode body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder ()
            .uri (URI.create (config.getUrl () + path))
            .header ("Cookie", "JSESSIONID=" + config.getSession ())
            .header ("Content-Type", "application/json")
            .POST (HttpRequest.BodyPublishers.ofString (body.toString (), StandardCharsets.UTF_8))
            .build ();
        HttpResponse<String> resp = http.send (req, HttpResponse.BodyHandlers.ofString ());
        requireOk (resp);
        JsonNode result = MAPPER.readTree (resp.body ());
        requireErfolg (result);
        return result;
    }

    public JsonNode put (String path, ObjectNode body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder ()
            .uri (URI.create (config.getUrl () + path))
            .header ("Cookie", "JSESSIONID=" + config.getSession ())
            .header ("Content-Type", "application/json")
            .PUT (HttpRequest.BodyPublishers.ofString (body.toString (), StandardCharsets.UTF_8))
            .build ();
        HttpResponse<String> resp = http.send (req, HttpResponse.BodyHandlers.ofString ());
        requireOk (resp);
        JsonNode result = MAPPER.readTree (resp.body ());
        requireErfolg (result);
        return result;
    }

    public void delete (String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder ()
            .uri (URI.create (config.getUrl () + path))
            .header ("Cookie", "JSESSIONID=" + config.getSession ())
            .DELETE ()
            .build ();
        HttpResponse<String> resp = http.send (req, HttpResponse.BodyHandlers.ofString ());
        requireOk (resp);
        requireErfolg (MAPPER.readTree (resp.body ()));
    }
```

(`ObjectNode` und `StandardCharsets` sind in `RestClient.java` bereits importiert — keine neuen Imports nötig.)

- [ ] **Step 4: Test erneut ausführen, muss durchlaufen**

```bash
bash test/restclient-schreiben.test.sh
```

Erwartet: `OK restclient-schreiben`.

- [ ] **Step 5: Commit**

```bash
git add src/de/kreisalarm/cli/RestClient.java test/restclient-schreiben.test.sh
git commit -m "feat(cli): RestClient.post/put/delete fuer schreibende Endpunkte"
```

---

### Task 2: `kalender list`

**Files:**
- Modify: `src/de/kreisalarm/cli/CLI.java`
- Test: `test/kalender.test.sh` (neu)

**Interfaces:**
- Consumes: `RestClient.getList(String className, int limit, String query, String queryProperty, String kvid): JsonNode` (bestehende Signatur, unverändert).
- Produces: Befehl `kalender list [--kvid <id>]`, `manifest`-Eintrag `kalender_list` (modus `lesen`).

- [ ] **Step 1: Test schreiben**

Erstelle `test/kalender.test.sh`:

```bash
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
python3 - "$(cat "$MITSCHRIFT")" "$out" <<'PY' || fail=1
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
```

- [ ] **Step 2: Ausführen, muss an fehlendem Befehl scheitern**

```bash
bash test/kalender.test.sh
```

Erwartet: `Unbekannter Befehl: kalender` (Exit 1) statt `OK kalender`.

- [ ] **Step 3: `kalender list` in `CLI.java` implementieren**

Im `switch (cmd)` in `run()` (nach `case "gruppe":`) ergänzen:

```java
            case "kalender":
                runKalender (client, args, json);
                break;
```

Neue Methode nach `runGruppe` einfügen (gleiches Muster wie `runGruppe`/`runBenutzer` — kein Sub-Befehl, da bislang nur `list` existiert):

```java
    // -------------------------------------------------------------------------
    // kalender
    // -------------------------------------------------------------------------

    private static void runKalender (RestClient client, String[] args, boolean json) throws Exception {
        String kvid = pruefeId (arg (args, "--kvid", null), "Kreisverband-ID");
        JsonNode result = client.getList ("Calendar", 1000, null, null, kvid);
        printResult (result.path ("root"), new String[]{"id", "projektID", "name"}, json);
    }
```

In `runManifest()` nach dem `benutzerList`-Block ergänzen:

```java
        ObjectNode kalenderList = befehl (cmds, "kalender_list",
            "Kalender eines Kreisverbands auflisten (liefert die calendarID fuer termin_create).");
        param (kalenderList, "kvid", "string", false, null, "flag", "Kreisverband-ID");
```

In `printHelp()` nach der `benutzer list`-Zeile ergänzen:

```java
        System.out.println ("  kalender list [--kvid <id>]            Kalender auflisten (liefert calendarID)");
```

- [ ] **Step 4: Test erneut ausführen**

```bash
bash test/kalender.test.sh
```

Erwartet: `OK kalender`.

- [ ] **Step 5: `manifest`-Test anpassen (kalender_list ist jetzt Teil des Katalogs)**

In `test/manifest.test.sh`:
- `erwartet` (Zeile 23) um `"kalender_list"` ergänzen.
- `erwartete_params` (Zeile 32) um Eintrag ergänzen:

```python
    "kalender_list": {
        "kvid": ("string", False, "flag"),
    },
```

```bash
bash test/manifest.test.sh
```

Erwartet: `OK manifest: ...` mit `kalender_list` in der Namensliste.

- [ ] **Step 6: Commit**

```bash
git add src/de/kreisalarm/cli/CLI.java test/kalender.test.sh test/manifest.test.sh
git commit -m "feat(cli): kalender list -- Kalender-ID fuer termin_create nachschlagen"
```

---

### Task 3: `termin list` + `termin get`

**Files:**
- Modify: `src/de/kreisalarm/cli/CLI.java`
- Modify: `src/de/kreisalarm/cli/RestClient.java`
- Test: `test/termin-lesen.test.sh` (neu)

**Interfaces:**
- Produces: `RestClient.getList(String className, int limit, String query, String queryProperty, String filterProperty, String filterValue): JsonNode` — **ersetzt** die bisherige 5-Parameter-Signatur (letzter Parameter `kvid` wird zu `filterProperty`+`filterValue`).
- Produces: Befehle `termin list [--calendar <id>] [--q <text>] [--limit <n>]`, `termin get <id>`; `manifest`-Einträge `termin_list`, `termin_get` (modus `lesen`).

- [ ] **Step 1: Test schreiben**

Erstelle `test/termin-lesen.test.sh`:

```bash
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
python3 - "$(cat "$MITSCHRIFT")" "$out" <<'PY' || fail=1
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
python3 - "$(cat "$MITSCHRIFT")" "$out" <<'PY' || fail=1
import sys, json
pfad, out = sys.argv[1], sys.argv[2]
assert pfad.strip () == "/backend/rest/CalendarEvent/9", pfad
d = json.loads (out)
assert d["ok"] is True and d["data"]["name"] == "Uebung", d
print ("  ok: termin get liefert Einzelobjekt")
PY

[ "$fail" = "0" ] && echo "OK termin-lesen" || exit 1
```

- [ ] **Step 2: Ausführen, muss an fehlendem Befehl scheitern**

```bash
bash test/termin-lesen.test.sh
```

Erwartet: `Unbekannter Befehl: termin`.

- [ ] **Step 3: `RestClient.getList` verallgemeinern (`kvid`-Parameter → `filterProperty`/`filterValue`)**

In `RestClient.java` die bestehende `getList`-Methode ersetzen:

```java
    public JsonNode getList (String className, int limit, String query, String queryProperty,
                             String filterProperty, String filterValue) throws Exception {
        Map<String, String> params = new LinkedHashMap<> ();
        params.put ("start", "0");
        params.put ("limit", String.valueOf (limit));

        ArrayNode filter = MAPPER.createArrayNode ();
        if (query != null && !query.isBlank () && queryProperty != null) {
            ObjectNode f = filter.addObject ();
            f.put ("property", queryProperty);
            f.put ("value", query);
        }
        if (filterValue != null && !filterValue.isBlank () && filterProperty != null) {
            ObjectNode f = filter.addObject ();
            f.put ("property", filterProperty);
            f.put ("value", filterValue);
            f.put ("exact", true);
        }
        if (!filter.isEmpty ())
            params.put ("filter", filter.toString ());

        return get ("/backend/rest/store/" + className + "/view/Extended", params);
    }
```

- [ ] **Step 4: Alle bestehenden Aufrufer von `getList` anpassen**

In `CLI.java`:

```java
// runProjekt
JsonNode result = client.getList ("Projekt", 1000, null, null, null, null);

// runPerson, case "list"
JsonNode list = client.getList ("Person", limit, query, "nachname", "projektID", kvid);

// runGruppe
JsonNode result = client.getList ("Gruppe", 1000, query, "name", "projektID", kvid);

// runBenutzer
JsonNode result = client.getList ("Benutzer", 1000, null, null, "projektID", kvid);

// runKalender (aus Task 2)
JsonNode result = client.getList ("Calendar", 1000, null, null, "projektID", kvid);
```

Zusätzlich `FilterProbe.java` in `test/sicherheit.test.sh` (Prüfung I4b, direkter `RestClient.getList`-Aufruf) auf die neue Signatur anpassen:

```java
public class FilterProbe {
    public static void main (String[] a) throws Exception {
        new RestClient (new Config ()).getList ("Person", 5, null, null, "projektID", a[0]);
    }
}
```

```bash
bash test/sicherheit.test.sh
```

Erwartet weiterhin: `OK sicherheit`.

- [ ] **Step 5: `termin list` + `termin get` implementieren**

Im `switch (cmd)` in `run()` ergänzen:

```java
            case "termin":
                runTermin (client, args, json);
                break;
```

Neue Methoden nach `runKalender` einfügen:

```java
    // -------------------------------------------------------------------------
    // termin
    // -------------------------------------------------------------------------

    private static void runTermin (RestClient client, String[] args, boolean json) throws Exception {
        String sub = sub (args);
        switch (sub) {
            case "list":
                runTerminList (client, args, json);
                break;
            case "get":
                runTerminGet (client, args, json);
                break;
            default:
                exitWithError ("Unbekannter Subbefehl: " + sub, json);
        }
    }

    private static void runTerminList (RestClient client, String[] args, boolean json) throws Exception {
        String calendarId = pruefeId (arg (args, "--calendar", null), "calendar");
        String query      = arg (args, "--q", null);
        int limit         = pruefeZahl (arg (args, "--limit", "100"), "limit");
        JsonNode result = client.getList ("CalendarEvent", limit, query, "name", "calendarID", calendarId);
        printResult (result.path ("root"),
            new String[]{"id", "calendarID", "name", "startDate", "startTime", "endDate", "endTime", "type"}, json);
    }

    private static void runTerminGet (RestClient client, String[] args, boolean json) throws Exception {
        String id = positional (args, 2);
        if (id == null) { exitWithError ("Termin-ID fehlt.", json); return; }
        JsonNode termin = client.get ("/backend/rest/CalendarEvent/" + pruefeId (id, "Termin-ID"));
        printResult (termin, null, json);
    }
```

`WERT_FLAGS` um `--calendar` erweitern:

```java
    static final java.util.Set<String> WERT_FLAGS =
        java.util.Set.of ("--password", "--token", "--q", "--kvid", "--limit", "--calendar");
```

In `runManifest()` ergänzen:

```java
        ObjectNode terminList = befehl (cmds, "termin_list",
            "Termine (Kalendereintraege) eines Kalenders auflisten oder durchsuchen.");
        param (terminList, "calendar", "string", false, null, "flag", "Kalender-ID (aus kalender_list)");
        param (terminList, "q",        "string", false, null, "flag", "Suchtext im Titel (Teiltreffer)");
        param (terminList, "limit",    "integer", false, "100", "flag", "Maximale Trefferzahl");

        ObjectNode terminGet = befehl (cmds, "termin_get",
            "Alle Details zu genau einem Termin anhand seiner ID.");
        param (terminGet, "id", "string", true, null, "positional", "Termin-ID, z. B. aus termin_list");
```

In `printHelp()` ergänzen:

```java
        System.out.println ("  termin  list [--calendar <id>] [--q <text>] [--limit <n>]");
        System.out.println ("                                         Termine auflisten");
        System.out.println ("  termin  get <id>                       Termin-Details anzeigen");
```

- [ ] **Step 6: Test erneut ausführen**

```bash
bash test/termin-lesen.test.sh
```

Erwartet: `OK termin-lesen`.

- [ ] **Step 7: Regressionstest — `such-filter.test.sh` muss weiterhin bestehen (Signaturänderung von `getList`)**

```bash
bash test/such-filter.test.sh
```

Erwartet: `OK such-filter` (keine Verhaltensänderung für Person/Gruppe, nur die interne Signatur wurde erweitert).

- [ ] **Step 8: `manifest`-Test anpassen**

In `test/manifest.test.sh`: `erwartet` um `"termin_list"` und `"termin_get"` ergänzen, `erwartete_params` um:

```python
    "termin_list": {
        "calendar": ("string", False, "flag"),
        "q":        ("string", False, "flag"),
        "limit":    ("integer", False, "flag"),
    },
    "termin_get": {
        "id": ("string", True, "positional"),
    },
```

```bash
bash test/manifest.test.sh
```

Erwartet: `OK manifest: ...`.

- [ ] **Step 9: Commit**

```bash
git add src/de/kreisalarm/cli/CLI.java src/de/kreisalarm/cli/RestClient.java \
        test/termin-lesen.test.sh test/manifest.test.sh
git commit -m "feat(cli): termin list/get -- CalendarEvent lesen, getList um filterProperty verallgemeinert"
```

---

### Task 4: Validatoren + `termin create`

**Files:**
- Modify: `src/de/kreisalarm/cli/CLI.java`
- Test: `test/termin-schreiben.test.sh` (neu)
- Modify: `test/sicherheit.test.sh` (eine zusätzliche Gegenprobe)

**Interfaces:**
- Consumes: `RestClient.post(String, ObjectNode)` (Task 1).
- Produces: `CLI.pruefeDatum(String,String):String`, `CLI.pruefeZeit(String,String):String`, `CLI.pruefeEnum(String,String,String...):String`, `CLI.pruefeBool(String,String):boolean`, `CLI.terminBody(String[]):ObjectNode` — werden in Task 5 (`termin update`) wiederverwendet. `befehl(ArrayNode,String,String,String)` (mit neuem `modus`-Parameter) — Signatur, die Task 5/6 ebenfalls nutzen.
- Produces: Befehl `termin create --calendar <id> --name <text> --start <yyyyMMdd> --end <yyyyMMdd> [...]`.

- [ ] **Step 1: Test schreiben**

Erstelle `test/termin-schreiben.test.sh`:

```bash
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
```

- [ ] **Step 2: Ausführen, muss an fehlendem Subbefehl scheitern**

```bash
bash test/termin-schreiben.test.sh
```

Erwartet: `Unbekannter Subbefehl: create`.

- [ ] **Step 3: Validatoren in `CLI.java` ergänzen**

Nach `pruefeZahl` einfügen:

```java
    private static String pruefeDatum (String wert, String bezeichnung) {
        if (wert == null) return null;
        if (!wert.matches ("[0-9]{8}"))
            throw new IllegalArgumentException (bezeichnung + " muss das Format yyyyMMdd haben.");
        return wert;
    }

    private static String pruefeZeit (String wert, String bezeichnung) {
        if (wert == null) return null;
        if (!wert.matches ("[0-9]{4}"))
            throw new IllegalArgumentException (bezeichnung + " muss das Format hhmm haben.");
        return wert;
    }

    private static String pruefeEnum (String wert, String bezeichnung, String... erlaubt) {
        if (wert == null) return null;
        for (String e : erlaubt) if (e.equals (wert)) return wert;
        throw new IllegalArgumentException (bezeichnung + " muss einer von " + String.join ("|", erlaubt) + " sein.");
    }

    private static boolean pruefeBool (String wert, String bezeichnung) {
        if ("true".equals (wert)) return true;
        if ("false".equals (wert)) return false;
        throw new IllegalArgumentException (bezeichnung + " muss true oder false sein.");
    }
```

- [ ] **Step 4: `WERT_FLAGS` um alle neuen Termin-Flags erweitern**

```java
    static final java.util.Set<String> WERT_FLAGS =
        java.util.Set.of ("--password", "--token", "--q", "--kvid", "--limit", "--calendar",
            "--name", "--start", "--end", "--startTime", "--endTime", "--description", "--type",
            "--ort", "--tags", "--feedback", "--allowFreeRegistration", "--gpsNearbyRequired",
            "--countAsService");
```

- [ ] **Step 5: `terminBody` + `runTerminCreate` implementieren**

In `CLI.java`, `case "create":` im `runTermin`-`switch` ergänzen:

```java
            case "create":
                runTerminCreate (client, args, json);
                break;
```

Neue Methoden nach `runTerminGet` einfügen:

```java
    private static ObjectNode terminBody (String[] args) throws Exception {
        ObjectNode body = MAPPER.createObjectNode ();
        String calendar = pruefeId (arg (args, "--calendar", null), "calendar");
        if (calendar != null) body.put ("calendarID", Long.parseLong (calendar));
        String name = arg (args, "--name", null);
        if (name != null) body.put ("name", name);
        String start = pruefeDatum (arg (args, "--start", null), "start");
        if (start != null) body.put ("startDate", start);
        String end = pruefeDatum (arg (args, "--end", null), "end");
        if (end != null) body.put ("endDate", end);
        String startTime = pruefeZeit (arg (args, "--startTime", null), "startTime");
        if (startTime != null) body.put ("startTime", startTime);
        String endTime = pruefeZeit (arg (args, "--endTime", null), "endTime");
        if (endTime != null) body.put ("endTime", endTime);
        String description = arg (args, "--description", null);
        if (description != null) body.put ("description", description);
        String type = arg (args, "--type", null);
        if (type != null) body.put ("type", type);
        String ort = pruefeId (arg (args, "--ort", null), "ort");
        if (ort != null) body.put ("dpVeranstaltungOrtID", Long.parseLong (ort));
        String tags = arg (args, "--tags", null);
        if (tags != null) body.put ("tags", tags);
        String feedback = pruefeEnum (arg (args, "--feedback", null), "feedback", "NONE", "ALL", "INVITED");
        if (feedback != null) body.put ("feedbackPolicy", feedback);
        String allowFree = arg (args, "--allowFreeRegistration", null);
        if (allowFree != null) body.put ("allowFreeRegistration", pruefeBool (allowFree, "allowFreeRegistration"));
        String gpsNearby = arg (args, "--gpsNearbyRequired", null);
        if (gpsNearby != null) body.put ("gpsNearbyRequired", pruefeBool (gpsNearby, "gpsNearbyRequired"));
        String countAsService = arg (args, "--countAsService", null);
        if (countAsService != null) body.put ("countAsService", pruefeBool (countAsService, "countAsService"));
        return body;
    }

    private static void runTerminCreate (RestClient client, String[] args, boolean json) throws Exception {
        if (arg (args, "--calendar", null) == null) { exitWithError ("--calendar erforderlich.", json); return; }
        if (arg (args, "--name", null) == null)     { exitWithError ("--name erforderlich.", json); return; }
        if (arg (args, "--start", null) == null)    { exitWithError ("--start erforderlich.", json); return; }
        if (arg (args, "--end", null) == null)      { exitWithError ("--end erforderlich.", json); return; }
        ObjectNode body = terminBody (args);
        JsonNode result = client.post ("/backend/rest/CalendarEvent", body);
        printResult (result, null, json);
    }
```

(`ObjectNode` ist in `CLI.java` bereits importiert — wird schon von `runManifest()`/`befehl()`/`param()` genutzt.)

- [ ] **Step 6: `befehl()` um `modus`-Parameter erweitern, alle Aufrufer anpassen**

```java
    private static ObjectNode befehl (ArrayNode cmds, String name, String beschreibung, String modus) {
        ObjectNode c = cmds.addObject ();
        c.put ("name", name);
        c.put ("beschreibung", beschreibung);
        c.put ("modus", modus);
        c.putObject ("params");
        return c;
    }
```

Alle bestehenden Aufrufe in `runManifest()` um `"lesen"` ergänzen: `personList`, `personGet`, `gruppeList`, `benutzerList`, `projektList` (`befehl (cmds, "projekt_list", "...")` → `befehl (cmds, "projekt_list", "...", "lesen")`), sowie die in Task 2/3 ergänzten `kalenderList`, `terminList`, `terminGet` — alle mit `"lesen"`.

Neuer Eintrag für `termin_create`:

```java
        ObjectNode terminCreate = befehl (cmds, "termin_create",
            "Neuen Termin (Kalendereintrag) anlegen.", "schreiben");
        param (terminCreate, "calendar",               "string", true,  null,    "flag", "Kalender-ID (aus kalender_list)");
        param (terminCreate, "name",                   "string", true,  null,    "flag", "Titel des Termins");
        param (terminCreate, "start",                  "string", true,  null,    "flag", "Startdatum, Format yyyyMMdd");
        param (terminCreate, "end",                     "string", true,  null,    "flag", "Enddatum, Format yyyyMMdd");
        param (terminCreate, "startTime",               "string", false, null,    "flag", "Startzeit, Format hhmm");
        param (terminCreate, "endTime",                 "string", false, null,    "flag", "Endzeit, Format hhmm");
        param (terminCreate, "description",             "string", false, null,    "flag", "Beschreibungstext");
        param (terminCreate, "type",                    "string", false, null,    "flag", "Freitext-Kategorie");
        param (terminCreate, "ort",                     "string", false, null,    "flag", "DpVeranstaltungOrt-ID");
        param (terminCreate, "tags",                    "string", false, null,    "flag", "Kommagetrennte Tags");
        param (terminCreate, "feedback",                "string", false, "INVITED", "flag", "NONE|ALL|INVITED");
        param (terminCreate, "allowFreeRegistration",   "string", false, "false", "flag", "true|false");
        param (terminCreate, "gpsNearbyRequired",       "string", false, "false", "flag", "true|false");
        param (terminCreate, "countAsService",          "string", false, "false", "flag", "true|false");
```

In `printHelp()` ergänzen:

```java
        System.out.println ("  termin  create --calendar <id> --name <text> --start <yyyyMMdd> --end <yyyyMMdd>");
        System.out.println ("                 [--startTime hhmm] [--endTime hhmm] [--description <text>]");
        System.out.println ("                 [--type <text>] [--ort <id>] [--tags <text>]");
        System.out.println ("                 [--feedback NONE|ALL|INVITED] [--allowFreeRegistration true|false]");
        System.out.println ("                 [--gpsNearbyRequired true|false] [--countAsService true|false]");
        System.out.println ("                                         Neuen Termin anlegen");
```

- [ ] **Step 7: Test erneut ausführen**

```bash
bash test/termin-schreiben.test.sh
```

Erwartet: `OK termin-schreiben`.

- [ ] **Step 8: `manifest`-Test umbauen — blanke `modus=="lesen"`-Annahme durch Pro-Befehl-Prüfung ersetzen**

In `test/manifest.test.sh`:

`erwartet` um `"termin_create"` ergänzen. `erwartete_params` um den `termin_create`-Eintrag ergänzen (Namen/Typen/Pflicht/Übergabe exakt wie oben in `param()` definiert — 14 Parameter, siehe Step 6).

Die komplette bisherige Schleife am Dateiende

```python
for c in d["commands"]:
    assert c["modus"] == "lesen", c            # CLI ist ausschliesslich lesend
    assert c["beschreibung"].strip(), c
    for pname, p in c["params"].items():
        assert p["typ"] in ("string", "integer"), (c["name"], pname, p)
        assert isinstance(p["pflicht"], bool), (c["name"], pname, p)
        assert p["uebergabe"] in ("positional", "flag"), (c["name"], pname, p)
```

ersetzen durch:

```python
erwartete_modi = {
    "person_list": "lesen", "person_get": "lesen", "gruppe_list": "lesen",
    "benutzer_list": "lesen", "projekt_list": "lesen", "kalender_list": "lesen",
    "termin_list": "lesen", "termin_get": "lesen", "termin_create": "schreiben",
}
for c in d["commands"]:
    assert c["modus"] == erwartete_modi[c["name"]], c
    assert c["beschreibung"].strip(), c
    for pname, p in c["params"].items():
        assert p["typ"] in ("string", "integer"), (c["name"], pname, p)
        assert isinstance(p["pflicht"], bool), (c["name"], pname, p)
        assert p["uebergabe"] in ("positional", "flag"), (c["name"], pname, p)
```

(Nur die `modus`-Zeile ändert sich hier von der harten `"lesen"`-Annahme zu einem Nachschlagen in `erwartete_modi`; `typ`/`uebergabe` bleiben vorerst auf `string`/`integer`/`positional`/`flag` beschränkt — `termin_create` benutzt nur diese Typen. Diese Schleife wird in Task 6 um `"boolean"`/`"schalter"` erweitert, wenn `termin_delete` mit `--yes` dazukommt.)

```bash
bash test/manifest.test.sh
```

Erwartet: `OK manifest: ...` mit `termin_create` in der Liste.

- [ ] **Step 9: Sicherheits-Gegenprobe ergänzen — `--name` als Wert-Flag darf `--insecure` nicht aktivieren**

In `test/sicherheit.test.sh`, direkt nach dem bestehenden C2-Block (`--q --insecure`, vor der "Gegenprobe: an der vorgesehenen Stelle..."-Zeile) einfügen:

```bash
# C2b - dasselbe Muster fuer ein neues Wert-Flag aus der Termin-Erweiterung.
err="$(run "https://127.0.0.1:$HTTPS_PORT" --json termin list --calendar --insecure 2>&1 >/dev/null)"
echo "$err" | python3 -c '
import sys, json
d = json.loads (sys.stdin.read ().strip ())
assert d["ok"] is False, d
assert "TLS" in d["error"] or "SSL" in d["error"].upper (), d
assert "--insecure" not in d["error"], "Fehlertext schlaegt --insecure vor: " + d["error"]
print ("  ok: --calendar --insecure schaltet TLS nicht ab ->", d["error"][:60])
' || { melde "--insecure als Wert von --calendar hat die TLS-Pruefung abgeschaltet"; echo "  war: ${err:0:200}"; }
```

```bash
bash test/sicherheit.test.sh
```

Erwartet: `OK sicherheit`.

- [ ] **Step 10: Commit**

```bash
git add src/de/kreisalarm/cli/CLI.java test/termin-schreiben.test.sh \
        test/manifest.test.sh test/sicherheit.test.sh
git commit -m "feat(cli): termin create -- CalendarEvent anlegen, Validatoren fuer Datum/Zeit/Enum/Bool"
```

---

### Task 5: `termin update`

**Files:**
- Modify: `src/de/kreisalarm/cli/CLI.java`
- Test: `test/termin-update.test.sh` (neu)

**Interfaces:**
- Consumes: `RestClient.put(String, ObjectNode)` (Task 1), `terminBody(String[])` (Task 4).
- Produces: Befehl `termin update <id> [alle Flags aus termin create, optional]`; `manifest`-Eintrag `termin_update` (modus `schreiben`).

- [ ] **Step 1: Test schreiben**

Erstelle `test/termin-update.test.sh`:

```bash
#!/usr/bin/env bash
# Prueft termin update: nur gesetzte Flags landen im Body, Antwort (root[0])
# wird korrekt ausgepackt.
set -uo pipefail
cd "$(dirname "$0")/.."
source test/lib/portable.sh

CLASSES=/tmp/cliclasses-termin-update
rm -rf "$CLASSES" && mkdir -p "$CLASSES"
javac --release 21 -cp "lib/jackson/*" -d "$CLASSES" src/de/kreisalarm/cli/*.java || {
  echo "FAIL: kompiliert nicht"; exit 1; }
CP="$(winpath "$CLASSES")${CP_SEP}$(winpath "$PWD")/lib/jackson/*"

FAKEHOME=/tmp/cli-termin-update-home
rm -rf "$FAKEHOME" && mkdir -p "$FAKEHOME"
MITSCHRIFT=/tmp/cli-termin-update-requests.log
: > "$MITSCHRIFT"

PORT=58855
python3 - "$PORT" "$MITSCHRIFT" <<'PY' &
import sys, json
from http.server import BaseHTTPRequestHandler, HTTPServer

port, mitschrift = int (sys.argv[1]), sys.argv[2]

class H (BaseHTTPRequestHandler):
    def do_PUT (self):
        laenge = int (self.headers.get ("Content-Length", 0))
        body = self.rfile.read (laenge).decode ("utf-8")
        with open (mitschrift, "a") as f:
            f.write (json.dumps ({"pfad": self.path, "body": body}) + "\n")
        geaendert = json.loads (body)
        geaendert["id"] = 9
        antwort = json.dumps ({"root": [geaendert], "total": 1}).encode ()
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

# ── 1) Termin-ID fehlt ───────────────────────────────────────────────────────
err="$(run --json termin update 2>&1 >/dev/null)"
echo "$err" | python3 -c '
import sys, json
d = json.loads (sys.stdin.read ().strip ())
assert d["ok"] is False, d
' || { echo "  FAIL: fehlende Termin-ID nicht abgewiesen"; fail=1; }

# ── 2) Nur gesetzte Flags landen im Body ─────────────────────────────────────
: > "$MITSCHRIFT"
out="$(run --json termin update 9 --name "Neuer Name" 2>&1)"
python3 - "$(cat "$MITSCHRIFT")" "$out" <<'PY' || fail=1
import sys, json
d = json.loads (sys.argv[1])
assert d["pfad"] == "/backend/rest/CalendarEvent/9", d
body = json.loads (d["body"])
assert body == {"name": "Neuer Name"}, body
out = json.loads (sys.argv[2])
assert out["ok"] is True and out["data"]["name"] == "Neuer Name" and out["data"]["id"] == 9, out
print ("  ok: termin update sendet nur gesetzte Felder, packt root[0] aus")
PY

[ "$fail" = "0" ] && echo "OK termin-update" || exit 1
```

- [ ] **Step 2: Ausführen, muss an fehlendem Subbefehl scheitern**

```bash
bash test/termin-update.test.sh
```

Erwartet: `Unbekannter Subbefehl: update`.

- [ ] **Step 3: `runTerminUpdate` implementieren**

`case "update":` im `runTermin`-`switch` ergänzen:

```java
            case "update":
                runTerminUpdate (client, args, json);
                break;
```

Neue Methode nach `runTerminCreate`:

```java
    private static void runTerminUpdate (RestClient client, String[] args, boolean json) throws Exception {
        String id = positional (args, 2);
        if (id == null) { exitWithError ("Termin-ID fehlt.", json); return; }
        String terminId = pruefeId (id, "Termin-ID");
        ObjectNode body = terminBody (args);
        JsonNode result = client.put ("/backend/rest/CalendarEvent/" + terminId, body);
        printResult (result.path ("root").path (0), null, json);
    }
```

In `runManifest()` ergänzen:

```java
        ObjectNode terminUpdate = befehl (cmds, "termin_update",
            "Vorhandenen Termin aendern. Nur angegebene Felder werden geaendert.", "schreiben");
        param (terminUpdate, "id",                      "string", true,  null,    "positional", "Termin-ID, z. B. aus termin_list");
        param (terminUpdate, "calendar",                "string", false, null,    "flag", "Kalender-ID (aus kalender_list)");
        param (terminUpdate, "name",                    "string", false, null,    "flag", "Titel des Termins");
        param (terminUpdate, "start",                   "string", false, null,    "flag", "Startdatum, Format yyyyMMdd");
        param (terminUpdate, "end",                      "string", false, null,    "flag", "Enddatum, Format yyyyMMdd");
        param (terminUpdate, "startTime",                "string", false, null,    "flag", "Startzeit, Format hhmm");
        param (terminUpdate, "endTime",                  "string", false, null,    "flag", "Endzeit, Format hhmm");
        param (terminUpdate, "description",              "string", false, null,    "flag", "Beschreibungstext");
        param (terminUpdate, "type",                     "string", false, null,    "flag", "Freitext-Kategorie");
        param (terminUpdate, "ort",                      "string", false, null,    "flag", "DpVeranstaltungOrt-ID");
        param (terminUpdate, "tags",                     "string", false, null,    "flag", "Kommagetrennte Tags");
        param (terminUpdate, "feedback",                 "string", false, null,    "flag", "NONE|ALL|INVITED");
        param (terminUpdate, "allowFreeRegistration",    "string", false, null,    "flag", "true|false");
        param (terminUpdate, "gpsNearbyRequired",        "string", false, null,    "flag", "true|false");
        param (terminUpdate, "countAsService",           "string", false, null,    "flag", "true|false");
```

In `printHelp()` ergänzen:

```java
        System.out.println ("  termin  update <id> [gleiche Flags wie create, alle optional]");
        System.out.println ("                                         Termin aendern (nur gesetzte Felder)");
```

- [ ] **Step 4: Test erneut ausführen**

```bash
bash test/termin-update.test.sh
```

Erwartet: `OK termin-update`.

- [ ] **Step 5: `manifest`-Test anpassen**

`erwartet` um `"termin_update"`, `erwartete_params` um den `termin_update`-Eintrag (siehe Step 3), `erwartete_modi` um `"termin_update": "schreiben"` ergänzen.

```bash
bash test/manifest.test.sh
```

Erwartet: `OK manifest: ...`.

- [ ] **Step 6: Commit**

```bash
git add src/de/kreisalarm/cli/CLI.java test/termin-update.test.sh test/manifest.test.sh
git commit -m "feat(cli): termin update -- CalendarEvent aendern, nur gesetzte Felder senden"
```

---

### Task 6: `termin delete`

**Files:**
- Modify: `src/de/kreisalarm/cli/CLI.java`
- Test: `test/termin-delete.test.sh` (neu)

**Interfaces:**
- Consumes: `RestClient.delete(String)` (Task 1).
- Produces: Befehl `termin delete <id> [--yes]`; `manifest`-Eintrag `termin_delete` (modus `schreiben`, `yes` als `typ:"boolean"`/`uebergabe:"schalter"` — neue Werte, `manifest.test.sh`-Schema wird dafür erweitert).

- [ ] **Step 1: Test schreiben**

Erstelle `test/termin-delete.test.sh`:

```bash
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

# ── 2) JSON-Modus mit --yes -> DELETE an die richtige ID ─────────────────────
: > "$MITSCHRIFT"
out="$(run --json termin delete 9 --yes 2>&1)"
python3 - "$(cat "$MITSCHRIFT")" "$out" <<'PY' || fail=1
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
```

- [ ] **Step 2: Ausführen, muss an fehlendem Subbefehl scheitern**

```bash
bash test/termin-delete.test.sh
```

Erwartet: `Unbekannter Subbefehl: delete`.

- [ ] **Step 3: `runTerminDelete` implementieren**

`case "delete":` im `runTermin`-`switch` ergänzen:

```java
            case "delete":
                runTerminDelete (client, args, json);
                break;
```

Neue Methode nach `runTerminUpdate`:

```java
    private static void runTerminDelete (RestClient client, String[] args, boolean json) throws Exception {
        String id = positional (args, 2);
        if (id == null) { exitWithError ("Termin-ID fehlt.", json); return; }
        String terminId = pruefeId (id, "Termin-ID");
        boolean yes = hasFlag (args, "--yes");
        if (!yes) {
            if (json) { exitWithError ("--yes erforderlich zum Loeschen im JSON-Modus.", json); return; }
            Console console = System.console ();
            if (console == null) { exitWithError ("Kein Terminal - bitte --yes angeben.", json); return; }
            String antwort = console.readLine ("Termin %s wirklich loeschen? [j/N] ", terminId).trim ();
            if (!"j".equalsIgnoreCase (antwort)) { System.out.println ("Abgebrochen."); return; }
        }
        client.delete ("/backend/rest/CalendarEvent/" + terminId);
        if (json) printHinweis ("Termin " + terminId + " geloescht.");
        else System.out.println ("Termin " + terminId + " geloescht.");
    }
```

`hasFlag` ist aktuell `private` mit Sichtbarkeit für globale Schalter (`--json`) — Methode ist bereits vorhanden und generisch (`args`, `flag`), kein Import/Signaturwechsel nötig.

In `runManifest()` ergänzen:

```java
        ObjectNode terminDelete = befehl (cmds, "termin_delete",
            "Termin unwiderruflich loeschen.", "schreiben");
        param (terminDelete, "id", "string", true, null, "positional", "Termin-ID, z. B. aus termin_list");
        paramSchalter (terminDelete, "yes", true, "Bestaetigung ohne Nachfrage; im JSON-Modus erforderlich");
```

Neuen Helfer `paramSchalter` neben `param()` einfügen (Schalter haben keinen Wert/Typ/Default, nur `pflicht`+`uebergabe:"schalter"`):

```java
    /** Schalter-Parameter ohne Wert, z. B. --yes. typ ist immer "boolean". */
    private static void paramSchalter (ObjectNode cmd, String name, boolean pflicht, String beschreibung) {
        ObjectNode p = ((ObjectNode) cmd.get ("params")).putObject (name);
        p.put ("typ", "boolean");
        p.put ("pflicht", pflicht);
        p.put ("uebergabe", "schalter");
        p.put ("beschreibung", beschreibung);
    }
```

In `printHelp()` ergänzen:

```java
        System.out.println ("  termin  delete <id> [--yes]            Termin loeschen (--yes noetig ohne Terminal/im --json-Modus)");
```

- [ ] **Step 4: Test erneut ausführen**

```bash
bash test/termin-delete.test.sh
```

Erwartet: `OK termin-delete`.

- [ ] **Step 5: `manifest`-Test erweitern (neues Schema: `typ:"boolean"`, `uebergabe:"schalter"`)**

In `test/manifest.test.sh`:

`erwartet` um `"termin_delete"` ergänzen. `erwartete_params` um:

```python
    "termin_delete": {
        "id":  ("string", True, "positional"),
        "yes": ("boolean", True, "schalter"),
    },
```

`erwartete_modi` um `"termin_delete": "schreiben"` ergänzen.

In der Schleife am Dateiende (aus Task 4, nutzt bereits `erwartete_modi`) die beiden `assert`-Zeilen für `typ` und `uebergabe` erweitern:

```python
        assert p["typ"] in ("string", "integer", "boolean"), (c["name"], pname, p)
        assert isinstance (p["pflicht"], bool), (c["name"], pname, p)
        assert p["uebergabe"] in ("positional", "flag", "schalter"), (c["name"], pname, p)
```

(Nur `"boolean"` bei `typ` und `"schalter"` bei `uebergabe` neu ergänzen — `assert c["modus"] == erwartete_modi[c["name"]], c` bleibt unverändert.)

```bash
bash test/manifest.test.sh
```

Erwartet: `OK manifest: ...` mit `termin_delete` in der Liste.

- [ ] **Step 6: Vollständigen Regressionslauf aller Tests durchführen**

```bash
export PATH="/d/Program Files/Java/jdk21.0.2_13/bin:$PATH"
for t in test/*.test.sh; do
  echo "=== $t ==="
  bash "$t" || echo "!!! $t FEHLGESCHLAGEN !!!"
done
```

Erwartet: alle Skripte melden `OK ...`. (`json-fehlerpfad.test.sh` prüft ausschließlich bestehendes Verhalten und sollte unverändert bestehen.)

- [ ] **Step 7: Commit**

```bash
git add src/de/kreisalarm/cli/CLI.java test/termin-delete.test.sh test/manifest.test.sh
git commit -m "feat(cli): termin delete -- CalendarEvent loeschen, --yes im JSON-Modus erzwungen"
```

---

## Nicht Teil dieses Plans (bewusst ausgeklammert, siehe Spec)

- Felder `recurrence`, `recurrenceID`, `meta`, `iconCls`, `abgeschlossenAm`, `abgeschlossenDurch` von `CalendarEvent` — nicht als Flags abgebildet.
- `CLI_VERSION`-Erhöhung (`CLI.java:33`) — laut Kommentar erst beim Release, nicht Teil der Implementierung.
- README.md-Aktualisierung mit den neuen Befehlen — sollte vor einem Release nachgezogen werden, ist aber kein Test-/Verhaltens-Artefakt und daher hier nicht als eigener Task geführt.
