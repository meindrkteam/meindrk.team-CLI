package de.kreisalarm.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.Console;
import java.util.UUID;

/**
 * meinDRK CLI – spricht den Jetty-REST-Server via HTTP an.
 *
 * Einrichtung:
 *   cli setup
 *   cli login
 *
 * Befehle:
 *   cli setup
 *   cli login [--password <pw>]
 *   cli projekt list
 *   cli person list [--kvid <id>] [--q <suchtext>] [--limit <n>]
 *   cli person get <id>
 *   cli gruppe list [--kvid <id>] [--q <suchtext>]
 *   cli benutzer list [--kvid <id>]
 *   cli help
 */
public class CLI {

    private static final ObjectMapper MAPPER = new ObjectMapper ();

    /** Muss beim Release mit dem Git-Tag uebereinstimmen. */
    private static final String CLI_VERSION = "0.1.7";

    public static void main (String[] args) throws Exception {
        boolean json = hasFlag (args, "--json");
        try {
            run (args, json);
        } catch (Exception e) {
            exitWithError (beschreibe (e), json);
        }
    }

    /** Uebersetzt technische Ausnahmen in eine Meldung fuer Menschen und Agenten.
     *  Nie Stacktrace, nie Klassennamen — der Aufrufer parst das als JSON. */
    private static String beschreibe (Exception e) {
        if (e instanceof java.net.ConnectException)
            return "Server nicht erreichbar.";
        if (e instanceof java.net.UnknownHostException)
            return "Server-Adresse unbekannt: " + e.getMessage ();
        // Kein Hinweis auf --insecure: diese Meldung geht bei Dienst-Aufrufen an ein
        // Sprachmodell, das den Vorschlag beim naechsten Versuch befolgen wuerde.
        if (e instanceof javax.net.ssl.SSLException)
            return "TLS-Fehler: " + e.getMessage ();
        if (e instanceof java.net.http.HttpTimeoutException)
            return "Zeitueberschreitung beim Server.";
        String m = e.getMessage ();
        return (m == null || m.isBlank ()) ? "Unerwarteter Fehler." : m;
    }

    private static void run (String[] args, boolean json) throws Exception {
        if (GuiDetector.isGuiLaunch (args)) {
            GuiServer.start (new Config ());
            return;
        }

        String cmd = positional (args, 0);
        if (cmd == null) {
            if (json) { exitWithError ("Kein Befehl angegeben. Befehlskatalog: --json manifest", true); return; }
            printHelp ();
            return;
        }

        if ("help".equals (cmd)) {
            if (json) { printHinweis ("Befehlskatalog maschinenlesbar abrufen mit: --json manifest"); return; }
            printHelp ();
            return;
        }

        Config config = new Config ();

        if ("setup".equals (cmd)) {
            runSetup (config, json);
            return;
        }

        if ("manifest".equals (cmd)) {
            runManifest (json);
            return;
        }

        if (config.getUrl () == null) {
            exitWithError ("Nicht konfiguriert. Bitte zuerst 'cli setup' ausführen oder MEINDRK_URL setzen.", json);
            return;
        }

        boolean insecure = hatGlobalenSchalter (args, "--insecure");
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
            case "kalender":
                runKalender (client, args, json);
                break;
            default:
                exitWithError ("Unbekannter Befehl: " + cmd, json);
        }
    }

    // -------------------------------------------------------------------------
    // setup
    // -------------------------------------------------------------------------

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

    private static String prompt (Console console, String label, String current) {
        String hint = current != null ? " [" + current + "]" : "";
        String input = console.readLine ("%s%s: ", label, hint).trim ();
        return input.isEmpty () ? null : input;
    }

    // -------------------------------------------------------------------------
    // login
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // projekt
    // -------------------------------------------------------------------------

    private static void runProjekt (RestClient client, String[] args, boolean json) throws Exception {
        JsonNode result = client.getList ("Projekt", 1000, null, null, null);
        printResult (result.path ("root"), new String[]{"id", "name", "organisation", "prefix"}, json);
    }

    // -------------------------------------------------------------------------
    // person
    // -------------------------------------------------------------------------

    private static void runPerson (RestClient client, String[] args, boolean json) throws Exception {
        String sub = sub (args);
        switch (sub) {
            case "list":
                String kvid  = pruefeId (arg (args, "--kvid", null), "Kreisverband-ID");
                String query = arg (args, "--q",     null);
                int limit    = pruefeZahl (arg (args, "--limit", "100"), "limit");
                JsonNode list = client.getList ("Person", limit, query, "nachname", kvid);
                printResult (list.path ("root"),
                    new String[]{"id", "projektID", "nachname", "vorname", "geburtsdatum", "status", "aktiv"}, json);
                break;
            case "get":
                String id = positional (args, 2);
                if (id == null) { exitWithError ("Person-ID fehlt.", json); return; }
                JsonNode person = client.get ("/backend/rest/Person/" + pruefeId (id, "Person-ID"));
                printResult (person, null, json);
                break;
            default:
                exitWithError ("Unbekannter Subbefehl: " + sub, json);
        }
    }

    // -------------------------------------------------------------------------
    // gruppe
    // -------------------------------------------------------------------------

    private static void runGruppe (RestClient client, String[] args, boolean json) throws Exception {
        String kvid  = pruefeId (arg (args, "--kvid", null), "Kreisverband-ID");
        String query = arg (args, "--q",    null);
        JsonNode result = client.getList ("Gruppe", 1000, query, "name", kvid);
        printResult (result.path ("root"), new String[]{"id", "projektID", "name"}, json);
    }

    // -------------------------------------------------------------------------
    // benutzer
    // -------------------------------------------------------------------------

    private static void runBenutzer (RestClient client, String[] args, boolean json) throws Exception {
        String kvid = pruefeId (arg (args, "--kvid", null), "Kreisverband-ID");
        JsonNode result = client.getList ("Benutzer", 1000, null, null, kvid);
        printResult (result.path ("root"),
            new String[]{"id", "projektID", "login", "vorname", "nachname", "email", "deaktiviert"}, json);
    }

    // -------------------------------------------------------------------------
    // kalender
    // -------------------------------------------------------------------------

    private static void runKalender (RestClient client, String[] args, boolean json) throws Exception {
        String kvid = pruefeId (arg (args, "--kvid", null), "Kreisverband-ID");
        JsonNode result = client.getList ("Calendar", 1000, null, null, kvid);
        printResult (result.path ("root"), new String[]{"id", "projektID", "name"}, json);
    }

    // -------------------------------------------------------------------------
    // Hilfsmethoden
    // -------------------------------------------------------------------------

    /** Flags, die einen Wert nachziehen. Der Wert dahinter stammt beim Aufruf
     *  durch einen Dienst aus einem Sprachmodell und darf deshalb NIE als
     *  Schalter oder als Positional-Argument gelesen werden – sonst wird aus
     *  <code>--q --insecure</code> ein globaler Schalter. */
    static final java.util.Set<String> WERT_FLAGS =
        java.util.Set.of ("--password", "--token", "--q", "--kvid", "--limit");

    /** true, wenn args[i] der Wert eines vorangehenden Wert-Flags ist. */
    static boolean istKonsumierterWert (String[] args, int i) {
        return i > 0 && WERT_FLAGS.contains (args[i - 1]);
    }

    private static String arg (String[] args, String flag, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (istKonsumierterWert (args, i)) continue;
            if (flag.equals (args[i])) return args[i + 1];
        }
        return defaultValue;
    }

    /** Schalter ohne Wert. Konsumierte Werte zaehlen nie mit. */
    private static boolean hasFlag (String[] args, String flag) {
        for (int i = 0; i < args.length; i++) {
            if (istKonsumierterWert (args, i)) continue;
            if (flag.equals (args[i])) return true;
        }
        return false;
    }

    /** Sicherheitsrelevanter globaler Schalter: gilt nur im Kopf des Aufrufs,
     *  also vor dem ersten Positional-Argument (dem Befehl). Damit liegt er
     *  ausserhalb des Bereichs, in dem die Werte des Aufrufers stehen. */
    static boolean hatGlobalenSchalter (String[] args, String flag) {
        for (int i = 0; i < args.length; i++) {
            if (istKonsumierterWert (args, i)) continue;
            if (!args[i].startsWith ("--")) return false;   // erstes Positional – Ende des Kopfes
            if (flag.equals (args[i])) return true;
        }
        return false;
    }

    /** IDs stammen bei Dienst-Aufrufen aus einem Sprachmodell und landen in URL-Pfaden
     *  und Filtern. Nur Ziffern zulassen – damit koennen sie den Pfad nicht verlassen.
     *  null bleibt null (Parameter nicht gesetzt). */
    private static String pruefeId (String wert, String bezeichnung) {
        if (wert == null || wert.isBlank ()) return null;
        if (!wert.matches ("[0-9]{1,18}"))
            throw new IllegalArgumentException (bezeichnung + " muss eine Zahl sein.");
        return wert;
    }

    /** Wie pruefeId, nur mit Rueckgabe als Zahl. Verhindert, dass die rohe
     *  NumberFormatException ("For input string: ...") an den Aufrufer geht. */
    private static int pruefeZahl (String wert, String bezeichnung) {
        String geprueft = pruefeId (wert, bezeichnung);
        if (geprueft == null) throw new IllegalArgumentException (bezeichnung + " muss eine Zahl sein.");
        long n = Long.parseLong (geprueft);          // pruefeId begrenzt auf 18 Ziffern
        return (int) Math.min (n, 10000);            // deckelt auch absurde Modell-Werte
    }

    private static String sub (String[] args) {
        String s = positional (args, 1);
        return s != null ? s : "list";
    }

    private static String positional (String[] args, int n) {
        int count = 0;
        for (int i = 0; i < args.length; i++) {
            if (istKonsumierterWert (args, i)) continue;
            if (args[i].startsWith ("--")) continue;
            if (count == n) return args[i];
            count++;
        }
        return null;
    }

    private static void exitWithError (String msg, boolean json) {
        if (json) {
            System.err.println ("{\"ok\":false,\"error\":" + jsonStr (msg) + "}");
        } else {
            System.err.println (msg);
        }
        System.exit (1);
    }

    /** Erfolgs-Envelope ohne Nutzdaten – fuer Faelle, in denen es im JSON-Modus
     *  nichts abzurufen, aber auch nichts zu melden gibt (z. B. help). */
    private static void printHinweis (String hinweis) {
        ObjectNode envelope = MAPPER.createObjectNode ();
        envelope.put ("ok", true);
        envelope.putObject ("data").put ("hinweis", hinweis);
        envelope.put ("count", 1);
        System.out.println (envelope.toString ());
    }

    private static String jsonStr (String s) {
        if (s == null) s = "";
        return "\"" + s.replace ("\\", "\\\\").replace ("\"", "\\\"").replace ("\n", "\\n") + "\"";
    }

    private static void printResult (JsonNode data, String[] columns, boolean json) {
        if (json) {
            // Fehlt der Datenknoten, ist das ein leeres Ergebnis – nicht eines mit
            // einem Treffer. count=1 bei data=null hat einen Aufrufer schon einmal
            // glauben lassen, die Abfrage sei erfolgreich gewesen.
            boolean leer = data == null || data.isMissingNode () || data.isNull ();
            ObjectNode envelope = MAPPER.createObjectNode ();
            envelope.put ("ok", true);
            if (leer) envelope.putNull ("data"); else envelope.set ("data", data);
            envelope.put ("count", leer ? 0 : (data.isArray () ? data.size () : 1));
            System.out.println (envelope.toString ());
        } else {
            if (columns != null) {
                TablePrinter.print (data, columns);
            } else {
                TablePrinter.printObject (data);
            }
        }
    }

    // -------------------------------------------------------------------------
    // manifest — Selbstbeschreibung fuer aufrufende Dienste
    // -------------------------------------------------------------------------

    private static void runManifest (boolean json) {
        if (!json) {
            exitWithError ("manifest gibt es nur mit --json.", false);
            return;
        }

        ObjectNode root = MAPPER.createObjectNode ();
        root.put ("cli_version", CLI_VERSION);
        ArrayNode cmds = root.putArray ("commands");

        ObjectNode personList = befehl (cmds, "person_list",
            "Personen (Mitglieder) eines Kreisverbands suchen oder auflisten.");
        param (personList, "q",     "string",  false, null,  "flag", "Suchtext im Nachnamen (Teiltreffer)");
        param (personList, "kvid",  "string",  false, null,  "flag", "Kreisverband-ID; leer = Standard des Benutzers");
        param (personList, "limit", "integer", false, "100", "flag", "Maximale Trefferzahl");

        ObjectNode personGet = befehl (cmds, "person_get",
            "Alle Details zu genau einer Person anhand ihrer ID.");
        param (personGet, "id", "string", true, null, "positional", "Personen-ID, z. B. aus person_list");

        ObjectNode gruppeList = befehl (cmds, "gruppe_list",
            "Gruppen eines Kreisverbands auflisten.");
        param (gruppeList, "q",    "string", false, null, "flag", "Suchtext im Gruppennamen (Teiltreffer)");
        param (gruppeList, "kvid", "string", false, null, "flag", "Kreisverband-ID");

        ObjectNode benutzerList = befehl (cmds, "benutzer_list",
            "Administrative Benutzerkonten eines Kreisverbands auflisten.");
        param (benutzerList, "kvid", "string", false, null, "flag", "Kreisverband-ID");

        ObjectNode kalenderList = befehl (cmds, "kalender_list",
            "Kalender eines Kreisverbands auflisten (liefert die calendarID fuer termin_create).");
        param (kalenderList, "kvid", "string", false, null, "flag", "Kreisverband-ID");

        befehl (cmds, "projekt_list",
            "Alle Kreisverbaende (Projekte) auflisten, auf die der Benutzer Zugriff hat.");

        System.out.println (root.toString ());
    }

    private static ObjectNode befehl (ArrayNode cmds, String name, String beschreibung) {
        ObjectNode c = cmds.addObject ();
        c.put ("name", name);
        c.put ("beschreibung", beschreibung);
        c.put ("modus", "lesen");
        c.putObject ("params");
        return c;
    }

    /** @param uebergabe "positional" (ueber Position im Aufruf, wie person_get.id)
     *                    oder "flag" (ueber --name <wert>). */
    private static void param (ObjectNode cmd, String name, String typ, boolean pflicht,
                               String standard, String uebergabe, String beschreibung) {
        ObjectNode p = ((ObjectNode) cmd.get ("params")).putObject (name);
        p.put ("typ", typ);
        p.put ("pflicht", pflicht);
        p.put ("uebergabe", uebergabe);
        p.put ("beschreibung", beschreibung);
        if (standard != null) {
            if ("integer".equals (typ)) {
                p.put ("default", Integer.parseInt (standard));
            } else {
                p.put ("default", standard);
            }
        }
    }

    private static void printHelp () {
        System.out.println ("meinDRK CLI");
        System.out.println ();
        System.out.println ("Befehle:");
        System.out.println ("  setup                                  Konfiguration einrichten (~/.meindrk-cli.properties)");
        System.out.println ("  login [--password <pw>] [--token <code>]  Einloggen (2FA wird interaktiv abgefragt)");
        System.out.println ("  projekt list                           Alle Kreisverbände auflisten");
        System.out.println ("  person list [--kvid <id>] [--q <text>] [--limit <n>]");
        System.out.println ("                                         Personen auflisten");
        System.out.println ("  person get <id>                        Person-Details anzeigen");
        System.out.println ("  gruppe  list [--kvid <id>] [--q <text>]  Gruppen auflisten");
        System.out.println ("  benutzer list [--kvid <id>]            Admin-Benutzer auflisten");
        System.out.println ("  kalender list [--kvid <id>]            Kalender auflisten (liefert calendarID)");
        System.out.println ("  manifest --json                        Befehlskatalog als JSON (fuer aufrufende Dienste)");
        System.out.println ("  help                                   Diese Hilfe");
        System.out.println ();
        System.out.println ("Globale Optionen (müssen VOR dem Befehl stehen):");
        System.out.println ("  --insecure   TLS-Zertifikat nicht prüfen (für lokale Entwicklungsserver)");
        System.out.println ("  --json       Ausgabe als JSON-Envelope {\"ok\":true,\"data\":...} fuer Agenten/Skripte");
        System.out.println ();
        System.out.println ("Beispiel:");
        System.out.println ("  cli setup");
        System.out.println ("  cli login");
        System.out.println ("  cli person list --q Müller --limit 20");
        System.out.println ("  cli person get 12345");
        System.out.println ("  cli --json person list --q Müller");
    }
}
