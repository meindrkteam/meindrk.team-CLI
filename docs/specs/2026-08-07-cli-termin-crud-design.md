# CLI CRUD für Termine (CalendarEvent) — Design

Datum: 2026-08-07

## Ausgangslage

Das meinDRK-CLI (dieses Repo, `meindrkteam/meindrk.team-CLI`) kann bislang nur lesen:
`setup`, `login`, `projekt list`, `person list|get`, `gruppe list`, `benutzer list`,
`manifest --json`. `RestClient.java` implementiert nur GET-Requests. Für echtes CRUD
fehlen POST/PUT/DELETE.

Der Jetty-Server (Hauptrepo `src/de/kreisalarm/server/services/`) bietet CRUD bereits
generisch, reflection-basiert, ohne dass pro Entity eigener Service-Code existiert:

- `POST /backend/rest/{Klasse}` — create (`ZZRestService.createObject`)
- `PUT /backend/rest/{Klasse}/{id}` — update (`ZZStoreService.updateObject2`)
- `DELETE /backend/rest/{Klasse}/{id}` — delete (`ZZRestService.remove`)
- `GET /backend/rest/store/{Klasse}/view/{view}` — Liste mit Filter/Query/Paging (bereits von CLI genutzt)
- `GET /backend/rest/{Klasse}/{id}` — Einzelobjekt (bereits von CLI genutzt)

Alle drei Schreiboperationen schreiben serverseitig automatisch Einträge in
`objectchange` (Änderungshistorie) und laufen durch die generische Rechte-/
Mandantenprüfung (`AbstractService.checkRights`). Für dieses Feature ist **kein
neuer Server-Code** nötig — nur neue CLI-Befehle gegen vorhandene Endpunkte.

## Begriffsklärung "Termin"

Der Begriff ist im Hauptprojekt mehrdeutig, drei Kandidaten wurden geprüft:

1. **`PersonTermin`** — personenbezogenes Feature, laut Admin-UI-Text
   ("Funktionalität wird demnächst eingestellt") auslaufend, Anlegen im UI bereits
   deaktiviert. Nicht gewählt.
2. **`veranstaltung`/`veranstaltungzeitraum`** — Tabellen laut `.claude/database.md`
   vorhanden, aber keine Java-Entity-Klasse im Hauptrepo-Code. Totes/historisches
   Schema. Nicht gewählt.
3. **`CalendarEvent`** (Tabelle `calendarevent`) — aktiv genutztes Kalender-/
   Termin-Objekt, das, was der Admin-UI-Tab "Termine" fachlich meint. **Gewählt.**

`CalendarEvent` hat keine `projektID` direkt, sondern hängt über `calendarID` an
`Calendar` (welches `projektID` trägt). Mandanten-Scoping für `termin list` läuft
daher über `--calendar <id>`, nicht über `--kvid`.

## Bestehende Architektur-Konventionen dieses CLI (verbindlich für die Umsetzung)

- **`WERT_FLAGS`** (`CLI.java:289`) — Whitelist aller Flags, die einen Wert
  nachziehen. Existiert, damit ein Flag-Wert, der bei Dienst-Aufrufen aus einem
  Sprachmodell stammt, nicht versehentlich als globaler Schalter (`--insecure`)
  oder Positional-Argument gelesen wird. **Jedes neue wertnehmende Flag muss hier
  ergänzt werden.**
- **`requireErfolg()`** (`RestClient.java:162`) — zentrale Prüfung auf
  `success:false` im Response-Body, auch bei HTTP 200 (der Server lehnt
  Rechte-/Mandantenkonflikte mit HTTP 200 ab). Bereits in `get()` eingebaut;
  `post()`/`put()`/`delete()` müssen dieselbe Prüfung nutzen.
- **`pruefeId()`/`pruefeZahl()`** (`CLI.java:329,338`) — IDs/Zahlen aus
  Nutzereingabe werden auf reine Ziffern begrenzt, bevor sie in URL-Pfad oder
  Filter landen (Schutz gegen von einem Sprachmodell erzeugte Werte).
- **`--json`-Envelope** (`printResult()`, `exitWithError()`) — einheitliches
  Ausgabeformat für Agenten/Skripte, inkl. Exit-Code 1 bei Fehlern.
- **`manifest --json`** (`runManifest()`) — Selbstbeschreibung aller Befehle für
  aufrufende Dienste, aktuell hart auf `"modus":"lesen"` codiert.
- **`beschreibe()`** (`CLI.java:46`) — übersetzt technische Exceptions in
  Klartext, nie Stacktrace/Klassenname (landet ggf. im JSON-Envelope für ein
  Sprachmodell).

## Architektur-Erweiterung

### `RestClient.java`

Neue Methoden, gleiches Muster wie `get()` (Cookie-Header, `requireOk()` für
HTTP-Fehler, `requireErfolg()` für `success:false`-Envelopes):

```java
public JsonNode post   (String path, ObjectNode body) throws Exception
public JsonNode put    (String path, ObjectNode body) throws Exception
public JsonNode delete (String path) throws Exception
```

JSON-Body via Jackson `ObjectNode` (nicht String-Konkatenation — gleiche
Begründung wie beim bestehenden `getList()`-Filteraufbau: Werte können aus einem
Sprachmodell stammen und dürfen keine zusätzlichen JSON-Felder einschmuggeln).
`Content-Type: application/json` Header ergänzen.

### `CLI.java`

Neue Cases `"kalender"` und `"termin"` im `switch` in `run()`.

`WERT_FLAGS` erweitern um: `--calendar`, `--name`, `--start`, `--end`,
`--startTime`, `--endTime`, `--description`, `--type`, `--ort`, `--tags`,
`--feedback`, `--allowFreeRegistration`, `--gpsNearbyRequired`,
`--countAsService`.

Neue Validierungs-Helfer, analog zu `pruefeId`/`pruefeZahl` (werfen
`IllegalArgumentException`, die `main()` bereits zentral über `beschreibe()`
abfängt — kein neues try/catch nötig):

```java
private static String pruefeDatum (String wert, String bezeichnung) // ^[0-9]{8}$
private static String pruefeZeit  (String wert, String bezeichnung) // ^[0-9]{4}$
private static String pruefeEnum  (String wert, String bezeichnung, String... erlaubt)
private static String pruefeBool  (String wert, String bezeichnung) // "true"|"false"
```

`runManifest()`: `befehl()`-Helper bekommt Parameter `modus` (bisher hart
`"lesen"`) — neue Einträge `kalender_list` (lesen), `termin_list`/`termin_get`
(lesen), `termin_create`/`termin_update`/`termin_delete` (schreiben).

## Befehle im Detail

```
cli kalender list [--kvid <id>]
    Spalten: id, projektID, name

cli termin list [--calendar <id>] [--q <text>] [--limit <n>]
    filter=[{"property":"calendarID","value":"<id>","exact":true}]
    Spalten: id, calendarID, name, startDate, startTime, endDate, endTime, type

cli termin get <id>
    GET /backend/rest/CalendarEvent/<id>  -> printResult (Key/Value)

cli termin create --calendar <id> --name <text> --start <yyyyMMdd> --end <yyyyMMdd>
                   [--startTime hhmm] [--endTime hhmm] [--description <text>]
                   [--type <text>] [--ort <dpVeranstaltungOrtID>] [--tags <text>]
                   [--feedback NONE|ALL|INVITED] [--allowFreeRegistration true|false]
                   [--gpsNearbyRequired true|false] [--countAsService true|false]
    Pflicht: --calendar, --name, --start, --end.
    POST /backend/rest/CalendarEvent

cli termin update <id> [gleiche Flags, alle optional]
    Nur gesetzte Flags werden in den JSON-Body aufgenommen (Server merged aufs
    Altobjekt statt es zu ersetzen).
    PUT /backend/rest/CalendarEvent/<id>

cli termin delete <id> [--yes]
    Text-Modus ohne --yes: interaktive Bestaetigung ueber Console (wie runSetup).
    JSON-Modus (--json) ohne --yes: Fehler ("--yes erforderlich"), kein Prompt —
    ein Agenten-Aufruf hat kein Terminal und darf ein Loeschen nicht "versehentlich"
    durch fehlendes Prompt blockieren oder gar automatisch bestaetigen.
    DELETE /backend/rest/CalendarEvent/<id>
```

Client-seitige Validierung vor dem Request (verhindert unklare Serverfehler und
haelt sich an bestehendes `pruefeId`/`pruefeZahl`-Muster):
- `--start`/`--end`: `pruefeDatum` (8 Ziffern)
- `--startTime`/`--endTime`: `pruefeZeit` (4 Ziffern)
- `--feedback`: `pruefeEnum` gegen `NONE|ALL|INVITED`
- `--allowFreeRegistration`/`--gpsNearbyRequired`/`--countAsService`: `pruefeBool`
- `--calendar`/`--ort`: `pruefeId` (wie bestehende IDs)

Nicht abgedeckte CalendarEvent-Felder (bewusst weggelassen, YAGNI): `recurrence`,
`recurrenceID`, `meta`, `iconCls`, `abgeschlossenAm`, `abgeschlossenDurch` — selten
genutzt bzw. eher system-/UI-verwaltet. Bei Bedarf später als weitere Flags
ergänzbar.

## Fehlerbehandlung

- HTTP-Fehler und `success:false`-Envelopes laufen zentral über
  `RestClient.requireOk()`/`requireErfolg()` — für `post`/`put`/`delete` wie für
  `get()`. Die `run*`-Methoden in `CLI.java` müssen das Ergebnis nicht zusätzlich
  auf `success` prüfen.
- Alle geworfenen Exceptions (inkl. neuer `IllegalArgumentException` aus den
  `pruefe*`-Validatoren) laufen durch das bestehende `main()`-try/catch und
  `beschreibe()` — Text- oder JSON-Fehlerausgabe je nach `--json`, Exit-Code 1.
  Kein neuer Fehlerpfad nötig.
- `termin delete` ohne `--yes`: siehe Befehls-Detail oben (JSON-Modus striktes
  Erfordernis, Text-Modus interaktive Bestätigung).

## Testing

Kein automatisiertes Test-Framework im `src`-Baum dieses Repos erkennbar
(`test/`-Verzeichnis existiert laut `git status`, Inhalt nicht geprüft — bei der
Umsetzung prüfen, ob dort Unit-Tests für `CLI`/`RestClient` liegen und diesem
Muster folgen). Manueller Testplan gegen laufenden Jetty-Server
(`starter/run.bat` im Hauptrepo):

1. `cli kalender list --kvid <bekannte-projekt-id>` → `calendarID` notieren
2. `cli termin create --calendar <id> --name "Testtermin" --start 20260901 --end 20260901`
   → Response prüfen, neue `id` notieren
3. `cli termin get <id>` → Felder korrekt?
4. `cli termin update <id> --name "Testtermin geändert"` → erneut `get` → Name geändert?
5. `cli termin list --calendar <id>` → Termin erscheint in Liste
6. `cli termin delete <id> --yes` → `cli termin get <id>` → Fehler "nicht gefunden"
7. Negativfälle: `termin create` ohne `--name`, falsches Datumsformat, `--feedback FOO`,
   `--allowFreeRegistration maybe` → jeweils klare Fehlermeldung vor dem HTTP-Request
8. `--json`-Modus: `cli --json termin create ...` → Envelope-Format prüfen;
   `cli --json termin delete <id>` ohne `--yes` → Fehler-Envelope, kein Prompt
9. Rechte-/Mandanten-Fehlfall (falls Testbenutzer nicht Admin ist): Server liefert
   `success:false` → wird über `requireErfolg()` als Exception sichtbar, nicht
   stillschweigend ignoriert
10. `cli --json manifest` → neue Einträge `kalender_list`, `termin_*` mit korrektem
    `modus` (`lesen`/`schreiben`) vorhanden

## Offene Punkte für die Implementierungsplanung

- Exakte Signaturänderung von `befehl()` in `runManifest()` (zusätzlicher
  `modus`-Parameter, bestehende Aufrufe anpassen)
- `printHelp()` um `kalender`/`termin`-Befehle ergänzen
- `CLI_VERSION` beim Release hochzählen (Projektkonvention laut Kommentar in
  `CLI.java:32`)
- Prüfen, ob `test/`-Verzeichnis bereits Tests enthält, die als Vorlage für neue
  Tests zu `pruefeDatum`/`pruefeZeit`/`WERT_FLAGS`-Erweiterung dienen können
