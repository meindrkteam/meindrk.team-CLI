# Design: CLI Agentenoptimierung

**Datum:** 2026-05-22  
**Ziel:** CLI für KI-Agenten optimieren — einheitliches JSON-Envelope-Format und Umgebungsvariablen als Alternative zur Config-Datei.

## Hintergrund

Das CLI gibt aktuell ASCII-Tabellen aus (`TablePrinter`) und erfordert eine interaktiv angelegte Config-Datei. Für KI-Agenten ist beides unpraktisch: Tabellen sind nicht zuverlässig parsebar, und ein Terminal für `setup` ist oft nicht vorhanden.

## Feature 1: `--json` Flag

Globaler Flag (wie `--insecure`), vor dem Befehl angegeben.

### Erfolg (stdout)

Listenbefehle und `person get`:
```json
{ "ok": true, "data": [...], "count": 3 }
{ "ok": true, "data": {...}, "count": 1 }
```

`login`:
```json
{ "ok": true, "data": { "vorname": "...", "nachname": "...", "projektID": "..." } }
```

### Fehler (stderr)

```json
{ "ok": false, "error": "Nicht authentifiziert – bitte mit 'cli login' einloggen." }
```

### `setup` in JSON-Modus

Da `setup` zwingend ein interaktives Terminal benötigt, gibt es im JSON-Modus sofort einen Fehler zurück:
```json
{ "ok": false, "error": "setup erfordert ein Terminal. Verwende stattdessen: MEINDRK_URL, MEINDRK_LOGIN, MEINDRK_KVID" }
```

### Normalausgabe

Ohne `--json` bleibt alles unverändert — `TablePrinter` wie bisher.

## Feature 2: Umgebungsvariablen

`Config.java` prüft bei jedem Getter erst die Env-Var, dann die Properties-Datei.

| Env-Var | Properties-Schlüssel | Beschreibung |
|---------|---------------------|-------------|
| `MEINDRK_URL` | `url` | Server-URL |
| `MEINDRK_LOGIN` | `login` | Benutzername |
| `MEINDRK_KVID` | `kvid` | Standard-Kreisverband-ID |
| `MEINDRK_SESSION` | `session` | Session-Token |
| `MEINDRK_PASSWORD` | — | Passwort (nur für Login, nie gespeichert) |

**Priorität:** Env-Var > Config-Datei

`MEINDRK_PASSWORD` wird nur in `runLogin()` ausgewertet. Priorität dort: `--password` Flag > `MEINDRK_PASSWORD` > interaktiver Prompt.

Wenn alle benötigten Werte via Env-Vars gesetzt sind, ist `setup` überflüssig — kein Dateisystem-Zugriff nötig.

## Architektur

Vier betroffene Dateien, keine neuen:

### `Config.java`

- Alle Getter (`getUrl()`, `getLogin()`, `getKvid()`, `getSession()`) mit Env-Var-Fallback erweitern
- Neuer Getter `getPassword()` → liest `MEINDRK_PASSWORD`, wird nie in Properties geschrieben
- Reihenfolge: `System.getenv(VAR)` → `properties.getProperty(key)` → `null`

### `CLI.java`

- `--json` aus Args parsen → `boolean json` an alle `run*`-Methoden weitergeben
- Neue private Methode `printResult(JsonNode data, boolean json)`:
  - `json=false`: übergibt an `TablePrinter.print()` / `TablePrinter.printObject()` (wie bisher)
  - `json=true`: baut `{"ok": true, "data": ..., "count": N}` via `ObjectMapper` und schreibt auf stdout
- Zentrale Fehlerbehandlung in `main()`: bei `json=true` alle Exceptions fangen und `{"ok": false, "error": "..."}` auf stderr schreiben, dann `System.exit(1)`
- `runSetup()`: bei `json=true` sofort JSON-Fehler ausgeben
- `runLogin()`: Passwort-Priorität erweitern: `--password` Flag > `config.getPassword()` > interaktiver Prompt
- Jackson `ObjectMapper` (bereits vorhanden in `RestClient`) für Envelope-Serialisierung nutzen

### `TablePrinter.java`

Keine Änderungen.

### `RestClient.java`

Keine Änderungen.

## Beispiel-Workflow für KI-Agent

```bash
export MEINDRK_URL=https://server.kreisalarm.de
export MEINDRK_LOGIN=admin
export MEINDRK_PASSWORD=geheim
export MEINDRK_KVID=42

# Login (Session wird in ~/.meindrk-cli.properties gespeichert)
cli login --json
# → {"ok": true, "data": {"vorname": "Max", "nachname": "Muster", "projektID": "42"}}

# Daten abfragen
cli --json person list --q Müller
# → {"ok": true, "data": [{"id": 1, "nachname": "Müller", ...}], "count": 1}

# Fehlerfall
cli --json person list
# stderr: {"ok": false, "error": "Nicht authentifiziert"}
# exit code: 1
```
