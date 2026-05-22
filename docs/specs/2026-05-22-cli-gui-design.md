# Design: CLI GUI-Modus (Browser-basiert)

**Datum:** 2026-05-22  
**Ziel:** Wenn die CLI-Binary per Doppelklick gestartet wird (Windows Explorer, macOS Finder, Linux Dateimanager), öffnet sich statt des Terminal-Fensters eine Browser-GUI.

---

## Hintergrund

Beim Doppelklick auf die native Binary erscheint das DOS-Fenster nur kurz. Für nicht-technische Benutzer ist die CLI so nicht nutzbar. Eine Browser-GUI löst das plattformübergreifend ohne native GUI-Frameworks (Swing/AWT sind mit GraalVM native-image problematisch).

---

## Feature 1: Erkennung (GUI vs. Terminal)

### Erkennungslogik

`ProcessHandle.current().parent()` liefert den Elternprozess. Enthält sein Name einen bekannten Shell-/Terminal-Bezeichner → Terminal-Modus, sonst → GUI-Modus.

**Terminal-Prozesse (→ kein GUI):**
`bash`, `zsh`, `sh`, `fish`, `dash`, `cmd`, `powershell`, `pwsh`, `wt`

**Alles andere** (explorer.exe, nautilus, thunar, dolphin, nemo, caja, Finder via launchd, …) → GUI-Modus.

### Override-Flags

| Flag | Wirkung |
|------|---------|
| `--gui` | Erzwingt GUI-Modus auch aus Terminal |
| `--no-gui` | Erzwingt Terminal-Modus auch bei Doppelklick |

### Implementierung

Pure Java, kein nativer Code:
```java
public static boolean isGuiLaunch(String[] args) {
    if (hasFlag(args, "--gui"))    return true;
    if (hasFlag(args, "--no-gui")) return false;
    return ProcessHandle.current()
        .parent()
        .flatMap(ProcessHandle.Info::command)
        .map(cmd -> {
            String lower = cmd.toLowerCase();
            for (String shell : SHELL_NAMES)
                if (lower.contains(shell)) return false;
            return true;
        })
        .orElse(false);
}

private static final String[] SHELL_NAMES =
    {"bash","zsh","sh","fish","dash","cmd","powershell","pwsh","wt"};
```

---

## Feature 2: Eingebetteter HTTP-Server

### Architektur

- `com.sun.net.httpserver.HttpServer` (keine neue Dependency, in JDK enthalten)
- Zufälliger freier Port auf `127.0.0.1` (localhost-only, kein Netzwerkzugriff von außen)
- Komplettes HTML/CSS/JS als Java-Textkonstante in `GuiServer.java` eingebettet (kein Ressourcen-Dateisystem-Zugriff nötig — GraalVM-kompatibel)
- Server-Lifecycle: Heartbeat via `GET /api/ping` alle 5 s; nach 30 s ohne Ping beendet sich der Server

### Browser öffnen

Ohne `Desktop.browse()` (funktioniert nicht zuverlässig in GraalVM native-image):

```java
String os = System.getProperty("os.name").toLowerCase();
ProcessBuilder pb;
if (os.contains("win"))       pb = new ProcessBuilder("cmd", "/c", "start", url);
else if (os.contains("mac"))  pb = new ProcessBuilder("open", url);
else                          pb = new ProcessBuilder("xdg-open", url);
pb.start();
```

### HTTP-Endpunkte

| Methode | Pfad | Funktion |
|---------|------|----------|
| `GET` | `/` | Liefert eingebettetes HTML |
| `GET` | `/api/ping` | Heartbeat (Server-Lifecycle) |
| `GET` | `/api/status` | Session-Status, Config (URL, Login, KVID) |
| `POST` | `/api/login` | Login mit `{password, token?}` |
| `POST` | `/api/setup` | Config speichern `{url, login, kvid}` |
| `POST` | `/api/command` | Befehl ausführen `{cmd, args}` → JSON-Envelope |
| `POST` | `/api/logout` | Session löschen |

`/api/command` delegiert an die vorhandene Befehlslogik in `CLI.java` (Methoden `runPerson`, `runGruppe`, etc.), die bereits JSON-Envelopes liefern.

---

## Feature 3: Login-Flow

### Startverhalten

Beim GUI-Start prüft `GET /api/status` sofort, ob eine gültige Session existiert:
- **Ja** → direkt zur Hauptoberfläche (kein Login nötig)
- **Nein** → Maske 1

### Maske 1 — Login & Konfiguration

Einzige Maske für Ersteinrichtung und Folgestarts. Aufbau:

1. **Logo** — SVG als Data-URI eingebettet (keine externen Abhängigkeiten)
2. **Server-URL** — eingeklappt, zeigt `meindrk.team`; Klick auf "ändern ▾" klappt Eingabefeld auf. Standard: `https://meindrk.team`
3. **Kreisverband-Dropdown** — wird beim Laden der Seite automatisch aus `<server-url>/js/kreisverbaende.js` befüllt (öffentlicher Endpunkt, kein Login nötig). Format: `window.kreisverbaende = [{id, name, organisation, active}, ...]`. Gruppiert nach `organisation`. Wenn URL geändert wird: Dropdown lädt neu.
4. **Benutzername** — Textfeld, vorausgefüllt aus Config falls vorhanden
5. **Passwort** — Passwortfeld
6. **"Anmelden"-Button**

Beim ersten Start (keine Config): alle Felder leer (außer URL-Default).  
Bei Folgestarts: URL, Benutzername und KV vorausgefüllt aus Config, nur Passwort muss eingegeben werden.

### Maske 2 — 2FA (nur wenn nötig)

Erscheint nur wenn der Server nach dem Login-Versuch einen 2FA-Code verlangt (`reason = "missing google-authentification-token"` oder `"missing email-token"`).

- Gelbe Info-Box mit Hinweis (Google Authenticator oder E-Mail)
- Großes 6-stelliges Code-Eingabefeld (Monospace, zentriert)
- "Bestätigen"-Button, "← Zurück"-Link

---

## Feature 4: Hauptoberfläche

### Topbar

Rote Leiste (`#cc0000`) mit Logo (weiß), Benutzername, aktivem KV-Badge, "Abmelden"-Button.

### Toolbar (Befehlsleiste)

| Element | Inhalt |
|---------|--------|
| Befehl-Dropdown | `person list`, `person get`, `gruppe list`, `benutzer list`, `projekt list` |
| Kreisverband | Dropdown aus Login-KV-Liste, Standard = konfigurierter KV |
| Suche `--q` | Textfeld (nur sichtbar bei `person list`, `gruppe list`) |
| Limit | Zahlfeld (nur sichtbar bei `person list`), Standard 100 |
| Person-ID | Textfeld (nur sichtbar bei `person get`) |
| "Ausführen"-Button | Rot, sendet `POST /api/command` |

Toolbar-Parameter werden je nach gewähltem Befehl dynamisch ein-/ausgeblendet.

### Ergebnisbereich

HTML-Tabelle mit den Spalten der jeweiligen Antwort. Spalten entsprechen exakt den bestehenden `TablePrinter`-Definitionen:

| Befehl | Spalten |
|--------|---------|
| `person list` | id, projektID, nachname, vorname, geburtsdatum, status, aktiv |
| `person get` | alle Felder als Key-Value-Liste |
| `gruppe list` | id, projektID, name |
| `benutzer list` | id, projektID, login, vorname, nachname, email, deaktiviert |
| `projekt list` | id, name, organisation, prefix |

Klick auf Zeile bei `person list` → führt `person get <id>` aus.  
Oberhalb der Tabelle: Ergebniszeile `N Ergebnisse · <ausgeführter Befehl>`.

---

## Betroffene Dateien

| Datei | Änderung |
|-------|---------|
| `src/de/kreisalarm/cli/GuiDetector.java` | **Neu** — Elternprozess-Erkennung |
| `src/de/kreisalarm/cli/GuiServer.java` | **Neu** — HTTP-Server + eingebettetes HTML/CSS/JS |
| `src/de/kreisalarm/cli/CLI.java` | `main()` erweitern: GUI-Check + Server-Start |
| `build.bat` | `javac`-Aufruf um zwei neue Dateien ergänzen |
| `build-linux.sh` | wie build.bat |
| `build-macos.sh` | wie build.bat |

`Config.java`, `RestClient.java`, `TablePrinter.java` — keine Änderungen.

---

## GraalVM native-image Kompatibilität

- `ProcessHandle` — unterstützt ✓
- `com.sun.net.httpserver` — unterstützt ✓  
- HTML als Java-String-Konstante — kein Ressourcen-Zugriff nötig ✓
- `ProcessBuilder` für Browser-Start — unterstützt ✓
- Kein AWT/Swing — keine zusätzlichen native-image-Flags nötig ✓
