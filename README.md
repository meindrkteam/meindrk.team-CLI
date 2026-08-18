# meinDRK CLI

> **⚠ Beta-Software** — Dieses Tool befindet sich noch in der Beta-Phase. Funktionen, Befehle und Konfigurationsformat können sich ohne Vorankündigung ändern. Für produktiven Einsatz empfehlen wir, auf stabile Releases zu warten.

Inoffizielle Kommandozeilen-Schnittstelle für meinDRK / KreisAlarm. Spricht den Jetty-REST-Server direkt an und ermöglicht die schnelle Abfrage von Personen, Gruppen, Benutzer- und Projektdaten aus dem Terminal.

---

## Download

Vorkompilierte Binaries ohne Installationsvoraussetzungen (kein Java erforderlich):

| Plattform | Download |
|-----------|----------|
| **Windows x64** | [meindrk-cli-windows-x64.exe](https://github.com/meindrkteam/meindrk.team-CLI/releases/latest/download/meindrk-cli-windows-x64.exe) |
| **Linux x64** | [meindrk-cli-linux-x64](https://github.com/meindrkteam/meindrk.team-CLI/releases/latest/download/meindrk-cli-linux-x64) |
| **Linux arm64** | [meindrk-cli-linux-arm64](https://github.com/meindrkteam/meindrk.team-CLI/releases/latest/download/meindrk-cli-linux-arm64) |
| **macOS Apple Silicon (arm64)** | [meindrk-cli-macos-arm64](https://github.com/meindrkteam/meindrk.team-CLI/releases/latest/download/meindrk-cli-macos-arm64) |

> Alle Releases: [github.com/meindrkteam/meindrk.team-CLI/releases](https://github.com/meindrkteam/meindrk.team-CLI/releases)

Zu jeder Linux-/Windows-Datei gibt es zusätzlich eine Variante mit dem Suffix
`-upx`: rund ein Drittel der Größe, dafür **rund 90 ms längere Startzeit je
Aufruf**, weil UPX das Image bei jedem Start entpackt (gemessen 90,9 ms gegen
2,0 ms). Für den interaktiven Gebrauch ist das egal, für Dienste, die die CLI je
Abfrage als Unterprozess starten, nicht — dort die Variante **ohne** Suffix
nehmen.

**Windows-Binaries sind signiert.** Beide `.exe` tragen eine
Authenticode-Signatur mit RFC3161-Zeitstempel; als Herausgeber steht das
Certum-Open-Source-Zertifikat **„Open Source Developer, Heid Jörn“** darin.
Windows zeigt also diesen Namen statt „Unbekannter Herausgeber“. Selbst prüfen:

```powershell
Get-AuthenticodeSignature .\meindrk-cli-windows-x64.exe | Format-List Status, SignerCertificate
```
`Status` muss `Valid` sein. Wer eine unsignierte oder abweichend signierte
Datei erhält, hat sie nicht von der offiziellen Release-Seite.

**Linux/macOS nach dem Download ausführbar machen:**
```bash
chmod +x meindrk-cli-linux-x64
```

---

## Einrichtung

### 1. Konfiguration

Beim ersten Start die Verbindungsdaten einrichten:

```
meindrk-cli setup
```

Das Tool fragt interaktiv nach:
- **Server-URL** — z. B. `https://meinserver.kreisalarm.de`
- **Login** — Benutzername des Admin-Accounts
- **Kreisverband-ID** — ID des Standard-Kreisverbands (kann bei jedem Befehl überschrieben werden)

Die Einstellungen werden in `~/.meindrk-cli.properties` gespeichert.

### 2. Anmelden

```
meindrk-cli login
```

Das Passwort wird sicher interaktiv abgefragt. Bei aktivierter Zwei-Faktor-Authentifizierung (Google Authenticator oder E-Mail) erscheint automatisch eine weitere Abfrage.

Nach erfolgreichem Login wird die Session gespeichert und bei allen weiteren Befehlen automatisch verwendet.

---

## Befehle

### `setup` — Konfiguration einrichten

```
meindrk-cli setup
```

Erstellt oder aktualisiert `~/.meindrk-cli.properties` interaktiv. Bestehende Werte werden als Vorgabe angezeigt und können mit Enter übernommen werden.

---

### `login` — Anmelden

```
meindrk-cli login [--password <passwort>] [--token <2fa-code>]
```

| Option | Beschreibung |
|--------|-------------|
| `--password <passwort>` | Passwort direkt übergeben (z. B. für Skripte). Ohne diese Option wird interaktiv abgefragt. |
| `--token <code>` | 2FA-Code direkt übergeben (6-stelliger Google-Authenticator-Code oder E-Mail-Code). Ohne diese Option wird bei aktivierter 2FA interaktiv abgefragt. |

---

### `projekt list` — Kreisverbände auflisten

```
meindrk-cli projekt list
```

Listet alle Kreisverbände (Projekte/Mandanten) auf, auf die der angemeldete Benutzer Zugriff hat. Ausgabe: `id`, `name`, `organisation`, `prefix`.

---

### `person list` — Personen suchen

```
meindrk-cli person list [--kvid <id>] [--q <suchtext>] [--limit <anzahl>]
```

| Option | Beschreibung |
|--------|-------------|
| `--kvid <id>` | Ergebnisse auf einen bestimmten Kreisverband (Projekt-ID) einschränken. Ohne Angabe werden alle zugänglichen Kreisverbände durchsucht. |
| `--q <suchtext>` | Freitextsuche (Name, Vorname o. ä.). |
| `--limit <anzahl>` | Maximale Anzahl Ergebnisse (Standard: `100`). |

Ausgabe: `id`, `projektID`, `nachname`, `vorname`, `geburtsdatum`, `status`, `aktiv`.

---

### `person get` — Person-Details anzeigen

```
meindrk-cli person get <id>
```

Gibt alle verfügbaren Felder einer einzelnen Person aus. `<id>` ist die numerische Datenbank-ID aus `person list`.

---

### `gruppe list` — Gruppen auflisten

```
meindrk-cli gruppe list [--kvid <id>] [--q <suchtext>]
```

| Option | Beschreibung |
|--------|-------------|
| `--kvid <id>` | Ergebnisse auf einen Kreisverband einschränken. |
| `--q <suchtext>` | Freitextsuche im Gruppennamen. |

Ausgabe: `id`, `projektID`, `name`.

---

### `benutzer list` — Admin-Benutzer auflisten

```
meindrk-cli benutzer list [--kvid <id>]
```

| Option | Beschreibung |
|--------|-------------|
| `--kvid <id>` | Ergebnisse auf einen Kreisverband einschränken. |

Ausgabe: `id`, `projektID`, `login`, `vorname`, `nachname`, `email`, `deaktiviert`.

---

### `kalender list` — Kalender auflisten

```
meindrk-cli kalender list [--kvid <id>]
```

| Option | Beschreibung |
|--------|-------------|
| `--kvid <id>` | Ergebnisse auf einen Kreisverband einschränken. |

Ausgabe: `id`, `projektID`, `name`, `besitz`. Die `id` ist die `calendarID` für `termin create`.

`besitz` sagt, wie der angemeldete Benutzer zu diesem Kalender steht — feste Bezeichner, keine Anzeigetexte:

| Wert | Bedeutung |
|------|-----------|
| `eigen` | Der Kalender gehört dem Benutzer selbst. Schlägt `eigener_kv`: die genauere Aussage ist die nützlichere. |
| `eigener_kv` | Gehört dem Kreisverband des Benutzers. |
| `fremder_kv` | Gehört einem anderen Kreisverband — der Benutzer sieht ihn, weil er eingeladen wurde. |
| `unbekannt` | Der angemeldete Benutzer ließ sich nicht ermitteln. **Nicht geraten**: eine bestimmt klingende, falsche Zuordnung wäre schlechter als gar keine. |

Ermittelt über `GET /backend/rest/current-user`, einmal je Prozess. Fällt der Aufruf aus, bleibt die Auflistung nutzbar und alle Einträge stehen auf `unbekannt`.

Ob der Benutzer auf einen Kalender **schreiben** darf, steht hier noch nicht: Der Zugriff kann über eine Gruppe vermittelt sein, und die Gruppenmitgliedschaften kennt die CLI nicht. Das muss der Server mitliefern.

---

### `termin list` / `get` / `create` / `update` / `delete` — Termine (CalendarEvent)

```
meindrk-cli termin list [--calendar <id>] [--q <text>] [--limit <n>]
meindrk-cli termin get <id>
meindrk-cli termin create --calendar <id> --name <text> [--start <JJJJMMTT>] …
meindrk-cli termin update <id> [--name <text>] …
meindrk-cli termin delete <id> [--yes]
```

Die `--calendar`-ID stammt aus `kalender list`. Vollständige Parameterliste: `meindrk-cli --json manifest`.

`termin delete` verlangt im JSON-Modus **`--yes`** und bricht sonst mit einem Fehler ab — ein Aufruf aus einem Dienst hat kein Terminal und darf ein Löschen weder durch eine fehlende Rückfrage blockieren noch stillschweigend bestätigen. Im Textmodus wird interaktiv nachgefragt.

---

### `help` — Hilfe anzeigen

```
meindrk-cli help
```

---

## Globale Optionen

Diese Optionen können bei jedem Befehl angegeben werden:

| Option | Beschreibung |
|--------|-------------|
| `--insecure` | TLS-Zertifikat des Servers nicht prüfen. Nur für lokale Entwicklungs- oder Testserver mit selbstsigniertem Zertifikat verwenden — niemals in der Produktion. **Muss vor dem Befehl stehen** (`cli --insecure person list`). |

`--insecure` wird bewusst nur im Kopf des Aufrufs gelesen, also vor dem ersten
Befehlswort. Dahinter stehen die Werte von `--q`, `--kvid` und `--limit`, die bei
einem Aufruf durch einen Dienst aus einem Sprachmodell stammen; ein Wert wie
`--q --insecure` darf die Zertifikatsprüfung nicht abschalten können.

---

## Beispiele

```bash
# Einrichtung und Login
meindrk-cli setup
meindrk-cli login

# Alle Kreisverbände anzeigen
meindrk-cli projekt list

# Personen nach Name suchen (max. 20 Ergebnisse)
meindrk-cli person list --q Müller --limit 20

# Personen eines bestimmten Kreisverbands anzeigen
meindrk-cli person list --kvid 42

# Details einer Person anzeigen
meindrk-cli person get 12345

# Alle Gruppen eines Kreisverbands
meindrk-cli gruppe list --kvid 42

# Admin-Benutzer auflisten
meindrk-cli benutzer list

# Lokalen Entwicklungsserver ansprechen (selbstsigniertes Zertifikat)
meindrk-cli --insecure person list
```

---

## Konfigurationsdatei

Die Datei `~/.meindrk-cli.properties` enthält:

| Schlüssel | Beschreibung |
|-----------|-------------|
| `url` | Server-URL (z. B. `https://meinserver.kreisalarm.de`) |
| `login` | Benutzername |
| `kvid` | Standard-Kreisverband-ID |
| `session` | Gespeicherte Session-ID (wird automatisch verwaltet) |
| `uuid` | Geräte-UUID für 2FA-Vertrauensstatus (wird automatisch generiert) |

---

## Agenten & Skripte

Für nicht-interaktive Umgebungen (KI-Agenten, CI/CD) können alle Verbindungsdaten
als Umgebungsvariablen gesetzt werden — eine `~/.meindrk-cli.properties` ist dann
nicht erforderlich.

| Env-Var | Entspricht | Beschreibung |
|---------|-----------|-------------|
| `MEINDRK_URL` | `url` | Server-URL |
| `MEINDRK_LOGIN` | `login` | Benutzername |
| `MEINDRK_KVID` | `kvid` | Standard-Kreisverband-ID |
| `MEINDRK_SESSION` | `session` | Gespeicherte Session-ID |
| `MEINDRK_PASSWORD` | — | Passwort (nur für `login`, nie gespeichert) |

**Priorität:** Env-Var > Konfigurationsdatei > interaktiver Prompt

### JSON-Ausgabe (`--json`)

Der globale Flag `--json` schaltet alle Ausgaben auf ein maschinenlesbares
Envelope-Format um:

Erfolg (stdout):

    { "ok": true,  "data": [...], "count": 3 }
    { "ok": false, "error": "Fehlermeldung"  }

Fehler (stderr). Exit-Code ist bei Fehlern immer `1`.

Beispiel-Workflow für Agenten:

    export MEINDRK_URL=https://server.kreisalarm.de
    export MEINDRK_LOGIN=admin
    export MEINDRK_PASSWORD=geheim

    cli login --json
    # stdout: {"ok":true,"data":{"vorname":"Max","nachname":"Muster","projektID":"42"},"count":1}

    cli --json person list --q Mueller
    # stdout: {"ok":true,"data":[...],"count":2}

    cli --json person list   # ohne gueltige Session
    # stderr: {"ok":false,"error":"Nicht authentifiziert – bitte mit 'cli login' einloggen."}
    # exit: 1
