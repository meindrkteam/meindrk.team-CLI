# CLI Agentenoptimierung — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `--json`-Flag für maschinenlesbares Envelope-Output und Umgebungsvariablen als Alternative zur Config-Datei ergänzen.

**Architecture:** `Config.java` erhält Env-Var-Fallback in allen Gettern. `CLI.java` parst `--json` global, leitet es durch alle Befehle und gibt Erfolge als `{"ok":true,"data":...,"count":N}` bzw. Fehler als `{"ok":false,"error":"..."}` aus. Normale Tabellenausgabe bleibt unverändert.

**Tech Stack:** Java 21, Jackson 2.18 (`ObjectMapper`, `ObjectNode`), GraalVM native-image

---

## Dateistruktur

| Datei | Änderung |
|-------|----------|
| `src/de/kreisalarm/cli/Config.java` | Env-Var-Fallback in Gettern, neuer `getPassword()` |
| `src/de/kreisalarm/cli/CLI.java` | `--json`-Flag, `positional()`, `exitWithError()`, `printResult()`, alle `run*`-Methoden angepasst |
| `src/de/kreisalarm/cli/TablePrinter.java` | Keine Änderung |
| `src/de/kreisalarm/cli/RestClient.java` | Keine Änderung |

---

## Task 1: Config.java — Env-Var-Fallback

**Files:**
- Modify: `src/de/kreisalarm/cli/Config.java`

- [ ] **Schritt 1: Env-Var-Hilfsmethode und aktualisierte Getter einsetzen**

  Ersetze die fünf Getter-Zeilen (37–41) und füge `getPassword()` hinzu:

  ```java
  // Alt (Zeilen 37-41):
  public String getUrl ()     { return get ("url"); }
  public String getLogin ()   { return get ("login"); }
  public String getSession ()  { return get ("session"); }
  public String getKvid ()    { return get ("kvid"); }
  public String getUuid ()    { return get ("uuid"); }

  // Neu:
  private static String env (String key) { return System.getenv (key); }

  public String getUrl ()      { String e = env ("MEINDRK_URL");      return e != null ? e : get ("url"); }
  public String getLogin ()    { String e = env ("MEINDRK_LOGIN");    return e != null ? e : get ("login"); }
  public String getSession ()  { String e = env ("MEINDRK_SESSION");  return e != null ? e : get ("session"); }
  public String getKvid ()     { String e = env ("MEINDRK_KVID");     return e != null ? e : get ("kvid"); }
  public String getUuid ()     { return get ("uuid"); }
  public String getPassword () { return env ("MEINDRK_PASSWORD"); }
  ```

- [ ] **Schritt 2: Kompilieren**

  ```
  CD CLI
  graalvm-win\bin\javac.exe --release 21 -cp lib\jackson\* -d tmp\verify src\de\kreisalarm\cli\Config.java
  ```

  Erwartung: kein Fehler, `tmp\verify\de\kreisalarm\cli\Config.class` erzeugt.

- [ ] **Schritt 3: Commit**

  ```
  git add src/de/kreisalarm/cli/Config.java
  git commit -m "feat: Env-Var-Fallback in Config (MEINDRK_URL/LOGIN/KVID/SESSION/PASSWORD)"
  ```

---

## Task 2: CLI.java — Hilfsmethoden und `--json`-Flag

**Files:**
- Modify: `src/de/kreisalarm/cli/CLI.java`

Dieser Task fügt vier neue private Methoden hinzu und passt `main()` an.

- [ ] **Schritt 1: Import und MAPPER ergänzen**

  Nach `import java.util.UUID;` einfügen:

  ```java
  import com.fasterxml.jackson.databind.ObjectMapper;
  import com.fasterxml.jackson.databind.node.ObjectNode;
  ```

  Direkt nach `public class CLI {` einfügen:

  ```java
  private static final ObjectMapper MAPPER = new ObjectMapper ();
  ```

- [ ] **Schritt 2: `positional()` Hilfsmethode hinzufügen**

  Ersetzt den direkten `args[0]`/`args[1]`/`args[2]`-Zugriff. Am Ende der Klasse (vor der schließenden `}`) einfügen:

  ```java
  private static String positional (String[] args, int n) {
      int count = 0;
      for (String arg : args) {
          if (!arg.startsWith ("--")) {
              if (count == n) return arg;
              count++;
          }
      }
      return null;
  }
  ```

- [ ] **Schritt 3: `exitWithError()` Hilfsmethode hinzufügen**

  Am Ende der Klasse einfügen:

  ```java
  private static void exitWithError (String msg, boolean json) {
      if (json) {
          System.err.println ("{\"ok\":false,\"error\":" + jsonStr (msg) + "}");
      } else {
          System.err.println (msg);
      }
      System.exit (1);
  }

  private static String jsonStr (String s) {
      if (s == null) s = "";
      return "\"" + s.replace ("\\", "\\\\").replace ("\"", "\\\"").replace ("\n", "\\n") + "\"";
  }
  ```

- [ ] **Schritt 4: `main()` anpassen**

  Bestehende `main()`-Methode vollständig ersetzen:

  ```java
  public static void main (String[] args) throws Exception {
      boolean json = hasFlag (args, "--json");

      String cmd = positional (args, 0);
      if (cmd == null) {
          printHelp ();
          return;
      }

      if ("help".equals (cmd)) {
          printHelp ();
          return;
      }

      Config config = new Config ();

      if ("setup".equals (cmd)) {
          runSetup (config, json);
          return;
      }

      if (config.getUrl () == null) {
          exitWithError ("Nicht konfiguriert. Bitte zuerst 'cli setup' ausführen oder MEINDRK_URL setzen.", json);
          return;
      }

      boolean insecure = hasFlag (args, "--insecure");
      RestClient client = new RestClient (config, insecure);

      switch (cmd) {
          case "login":
              runLogin (client, config, args, json);
              break;
          case "projekt":
              runProjekt (client, args, json);
              break;
          case "person":
              runPerson (client, args, json);
              break;
          case "gruppe":
              runGruppe (client, args, json);
              break;
          case "benutzer":
              runBenutzer (client, args, json);
              break;
          default:
              exitWithError ("Unbekannter Befehl: " + cmd, json);
      }
  }
  ```

- [ ] **Schritt 5: `sub()` anpassen**

  Bestehende `sub()`-Methode ersetzen:

  ```java
  private static String sub (String[] args) {
      String s = positional (args, 1);
      return s != null ? s : "list";
  }
  ```

- [ ] **Schritt 6: Kompilieren**

  ```
  graalvm-win\bin\javac.exe --release 21 -cp lib\jackson\* -d tmp\verify src\de\kreisalarm\cli\*.java
  ```

  Erwartung: kein Fehler.

- [ ] **Schritt 7: Commit**

  ```
  git add src/de/kreisalarm/cli/CLI.java
  git commit -m "feat: --json Flag geparst, positional()/exitWithError() Helfer"
  ```

---

## Task 3: CLI.java — `printResult()` Methode

**Files:**
- Modify: `src/de/kreisalarm/cli/CLI.java`

- [ ] **Schritt 1: `printResult()` am Ende der Klasse einfügen**

  ```java
  private static void printResult (JsonNode data, String[] columns, boolean json) {
      if (json) {
          ObjectNode envelope = MAPPER.createObjectNode ();
          envelope.put ("ok", true);
          envelope.set ("data", data);
          envelope.put ("count", data.isArray () ? data.size () : 1);
          System.out.println (envelope.toString ());
      } else {
          if (columns != null) {
              TablePrinter.print (data, columns);
          } else {
              TablePrinter.printObject (data);
          }
      }
  }
  ```

  `columns == null` signalisiert Single-Object-Ausgabe (→ `TablePrinter.printObject`).

- [ ] **Schritt 2: Kompilieren**

  ```
  graalvm-win\bin\javac.exe --release 21 -cp lib\jackson\* -d tmp\verify src\de\kreisalarm\cli\*.java
  ```

  Erwartung: kein Fehler.

- [ ] **Schritt 3: Commit**

  ```
  git add src/de/kreisalarm/cli/CLI.java
  git commit -m "feat: printResult() mit JSON-Envelope-Support"
  ```

---

## Task 4: CLI.java — Listenbefehle auf `printResult()` umstellen

**Files:**
- Modify: `src/de/kreisalarm/cli/CLI.java`

- [ ] **Schritt 1: `runProjekt()` anpassen**

  Signatur und Body ersetzen:

  ```java
  private static void runProjekt (RestClient client, String[] args, boolean json) throws Exception {
      JsonNode result = client.getList ("Projekt", 1000, null, null);
      printResult (result.path ("root"), new String[]{"id", "name", "organisation", "prefix"}, json);
  }
  ```

- [ ] **Schritt 2: `runGruppe()` anpassen**

  ```java
  private static void runGruppe (RestClient client, String[] args, boolean json) throws Exception {
      String kvid  = arg (args, "--kvid", null);
      String query = arg (args, "--q",    null);
      JsonNode result = client.getList ("Gruppe", 1000, query, kvid);
      printResult (result.path ("root"), new String[]{"id", "projektID", "name"}, json);
  }
  ```

- [ ] **Schritt 3: `runBenutzer()` anpassen**

  ```java
  private static void runBenutzer (RestClient client, String[] args, boolean json) throws Exception {
      String kvid = arg (args, "--kvid", null);
      JsonNode result = client.getList ("Benutzer", 1000, null, kvid);
      printResult (result.path ("root"),
          new String[]{"id", "projektID", "login", "vorname", "nachname", "email", "deaktiviert"}, json);
  }
  ```

- [ ] **Schritt 4: `runPerson()` anpassen**

  ```java
  private static void runPerson (RestClient client, String[] args, boolean json) throws Exception {
      String sub = sub (args);
      switch (sub) {
          case "list":
              String kvid  = arg (args, "--kvid",  null);
              String query = arg (args, "--q",     null);
              int limit    = Integer.parseInt (arg (args, "--limit", "100"));
              JsonNode list = client.getList ("Person", limit, query, kvid);
              printResult (list.path ("root"),
                  new String[]{"id", "projektID", "nachname", "vorname", "geburtsdatum", "status", "aktiv"}, json);
              break;
          case "get":
              String id = positional (args, 2);
              if (id == null) { exitWithError ("Person-ID fehlt.", json); return; }
              JsonNode person = client.get ("/backend/rest/Person/" + id);
              printResult (person, null, json);
              break;
          default:
              exitWithError ("Unbekannter Subbefehl: " + sub, json);
      }
  }
  ```

- [ ] **Schritt 5: Kompilieren**

  ```
  graalvm-win\bin\javac.exe --release 21 -cp lib\jackson\* -d tmp\verify src\de\kreisalarm\cli\*.java
  ```

  Erwartung: kein Fehler.

- [ ] **Schritt 6: Commit**

  ```
  git add src/de/kreisalarm/cli/CLI.java
  git commit -m "feat: Listenbefehle nutzen printResult() fuer JSON-Support"
  ```

---

## Task 5: CLI.java — `login`, `setup` und `printHelp` abschließen

**Files:**
- Modify: `src/de/kreisalarm/cli/CLI.java`

- [ ] **Schritt 1: `runSetup()` anpassen**

  Signatur ändern und JSON-Frühabbruch ergänzen:

  ```java
  private static void runSetup (Config config, boolean json) throws Exception {
      if (json) {
          exitWithError ("setup erfordert ein interaktives Terminal. Verwende stattdessen Umgebungsvariablen: MEINDRK_URL, MEINDRK_LOGIN, MEINDRK_KVID", json);
          return;
      }
      Console console = System.console ();
      if (console == null) {
          System.err.println ("Kein Terminal – bitte URL, Login und KVID direkt in ~/.meindrk-cli.properties eintragen.");
          System.exit (1);
      }

      String url   = prompt (console, "Server-URL",        config.getUrl ());
      String login = prompt (console, "Login",             config.getLogin ());
      String kvid  = prompt (console, "Kreisverband-ID",   config.getKvid ());

      if (url   != null) config.setUrl (url);
      if (login != null) config.setLogin (login);
      if (kvid  != null) config.setKvid (kvid);
      config.save ();

      System.out.println ("Gespeichert in ~/.meindrk-cli.properties");
      System.out.println ("Jetzt mit 'cli login' einloggen.");
  }
  ```

- [ ] **Schritt 2: `runLogin()` anpassen**

  Signatur ändern und `MEINDRK_PASSWORD`-Fallback ergänzen. Vollständige neue Methode:

  ```java
  private static void runLogin (RestClient client, Config config, String[] args, boolean json) throws Exception {
      String password = arg (args, "--password", null);
      String token    = arg (args, "--token", null);

      if (password == null) password = config.getPassword ();

      if (password == null) {
          Console console = System.console ();
          if (console == null) {
              exitWithError ("Kein Terminal – bitte --password <pw> oder MEINDRK_PASSWORD setzen.", json);
              return;
          }
          password = new String (console.readPassword ("Passwort für %s: ", config.getLogin ()));
      }

      if (config.getUuid () == null) {
          config.setUuid (UUID.randomUUID ().toString ());
          config.save ();
      }

      JsonNode result = client.login (password, token, config.getUuid ());
      String reason = result.path ("reason").asText ("");

      if (!result.path ("success").asBoolean (false)) {
          if ("missing google-authentification-token".equals (reason)
                  || "wrong google authentification code".equals (reason)) {
              if (token != null) { exitWithError ("Falscher Google-Authenticator-Code.", json); return; }
              Console console = System.console ();
              if (console == null) {
                  exitWithError ("Google Authenticator erforderlich – bitte --token <6-stelliger-code> angeben.", json);
                  return;
              }
              token = console.readLine ("Google Authenticator Code: ").trim ();
              result = client.login (password, token, config.getUuid ());
              reason = result.path ("reason").asText ("");

          } else if ("missing email-token".equals (reason)
                  || "wrong email code".equals (reason)) {
              if (token != null) { exitWithError ("Falscher E-Mail-Code.", json); return; }
              Console console = System.console ();
              if (console == null) {
                  exitWithError ("E-Mail-2FA erforderlich – bitte --token <code> angeben.", json);
                  return;
              }
              System.out.println ("Ein Code wurde per E-Mail gesendet.");
              token = console.readLine ("E-Mail Code: ").trim ();
              result = client.login (password, token, config.getUuid ());
              reason = result.path ("reason").asText ("");
          }
      }

      if (result.path ("success").asBoolean (false)) {
          JsonNode user = result.path ("user");
          if (json) {
              printResult (user, null, true);
          } else {
              System.out.println ("Eingeloggt als " + user.path ("vorname").asText ()
                  + " " + user.path ("nachname").asText ()
                  + " (Projekt " + user.path ("projektID").asText () + ")");
          }
      } else {
          exitWithError ("Login fehlgeschlagen: " + reason, json);
      }
  }
  ```

- [ ] **Schritt 3: `printHelp()` — globale Optionen ergänzen**

  In `printHelp()` nach der `--insecure`-Zeile einfügen:

  ```java
  System.out.println ("  --json       Ausgabe als JSON-Envelope {\"ok\":true,\"data\":...} fuer Agenten/Skripte");
  ```

  Außerdem den Beispielblock um einen JSON-Aufruf erweitern:

  ```java
  System.out.println ("  cli --json person list --q Müller");
  ```

- [ ] **Schritt 4: Kompilieren (alle Quelldateien)**

  ```
  graalvm-win\bin\javac.exe --release 21 -cp lib\jackson\* -d tmp\verify src\de\kreisalarm\cli\Config.java src\de\kreisalarm\cli\RestClient.java src\de\kreisalarm\cli\TablePrinter.java src\de\kreisalarm\cli\CLI.java
  ```

  Erwartung: kein Fehler, keine Warnings auf unbenutzte Importe.

- [ ] **Schritt 5: Schnelltest mit JAR**

  Fat-JAR aus `tmp/verify` bauen und testen:

  ```
  mkdir tmp\verify-fat
  cd tmp\verify-fat
  ..\..\graalvm-win\bin\jar.exe xf ..\..\lib\jackson\jackson-core-2.18.3.jar
  ..\..\graalvm-win\bin\jar.exe xf ..\..\lib\jackson\jackson-annotations-2.18.3.jar
  ..\..\graalvm-win\bin\jar.exe xf ..\..\lib\jackson\jackson-databind-2.18.3.jar
  cd ..\..
  xcopy /s /q tmp\verify\* tmp\verify-fat\ >nul
  echo Main-Class: de.kreisalarm.cli.CLI> tmp\verify-MANIFEST.MF
  echo.>> tmp\verify-MANIFEST.MF
  graalvm-win\bin\jar.exe cfm tmp\verify.jar tmp\verify-MANIFEST.MF -C tmp\verify-fat .
  graalvm-win\bin\java.exe -jar tmp\verify.jar help
  graalvm-win\bin\java.exe -jar tmp\verify.jar --json help
  ```

  Erwartung:
  - `help` → normaler Hilfetext inkl. `--json`-Zeile
  - `--json help` → `{"ok":false,"error":"Unbekannter Befehl: help"}` auf stderr (da `help` kein Datenabruf ist)

  Achtung: `help` ist ein Sonderfall — er gibt keinen JSON-Fehler, sondern einfach den Hilfetext. Prüfen ob `--json help` den Hilfetext ausgibt (Normalverhalten, da `help` vor dem JSON-Fehlerblock behandelt wird).

- [ ] **Schritt 6: Commit**

  ```
  git add src/de/kreisalarm/cli/CLI.java
  git commit -m "feat: --json in login/setup/printHelp, MEINDRK_PASSWORD Fallback"
  ```

---

## Task 6: Release — Tag v0.1.2 pushen

**Files:** keine Code-Änderungen

- [ ] **Schritt 1: README — Env-Var-Abschnitt ergänzen**

  In `README.md` nach dem Abschnitt `## Konfigurationsdatei` einen neuen Abschnitt einfügen:

  ```markdown
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

  ```json
  { "ok": true,  "data": [...], "count": 3 }
  { "ok": false, "error": "Fehlermeldung"  }
  ```

  Fehler werden auf stderr ausgegeben. Exit-Code ist bei Fehlern immer `1`.

  ```bash
  export MEINDRK_URL=https://server.kreisalarm.de
  export MEINDRK_LOGIN=admin
  export MEINDRK_PASSWORD=geheim

  cli login --json
  # stdout: {"ok":true,"data":{"vorname":"Max","nachname":"Muster","projektID":"42"},"count":1}

  cli --json person list --q Müller
  # stdout: {"ok":true,"data":[...],"count":2}

  cli --json person list   # ohne Session
  # stderr: {"ok":false,"error":"Nicht authentifiziert – bitte mit 'cli login' einloggen."}
  # exit: 1
  ```
  ```

- [ ] **Schritt 2: Commit und Tag**

  ```
  git add README.md
  git commit -m "docs: Env-Vars und --json im README dokumentiert"
  git tag v0.1.2
  git push && git push --tags
  ```

  Erwartung: GitHub Actions Workflow `Build Native Binaries` wird durch den Tag `v0.1.2` ausgelöst und erzeugt einen Release mit den drei Binary-Assets. Die Download-Links im README werden damit aktiv.
