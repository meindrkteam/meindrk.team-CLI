package de.kreisalarm.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        JsonNode result = client.getList ("Projekt", 1000, null, null);
        printResult (result.path ("root"), new String[]{"id", "name", "organisation", "prefix"}, json);
    }

    // -------------------------------------------------------------------------
    // person
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // gruppe
    // -------------------------------------------------------------------------

    private static void runGruppe (RestClient client, String[] args, boolean json) throws Exception {
        String kvid  = arg (args, "--kvid", null);
        String query = arg (args, "--q",    null);
        JsonNode result = client.getList ("Gruppe", 1000, query, kvid);
        printResult (result.path ("root"), new String[]{"id", "projektID", "name"}, json);
    }

    // -------------------------------------------------------------------------
    // benutzer
    // -------------------------------------------------------------------------

    private static void runBenutzer (RestClient client, String[] args, boolean json) throws Exception {
        String kvid = arg (args, "--kvid", null);
        JsonNode result = client.getList ("Benutzer", 1000, null, kvid);
        printResult (result.path ("root"),
            new String[]{"id", "projektID", "login", "vorname", "nachname", "email", "deaktiviert"}, json);
    }

    // -------------------------------------------------------------------------
    // Hilfsmethoden
    // -------------------------------------------------------------------------

    private static String arg (String[] args, String flag, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals (args[i])) return args[i + 1];
        }
        return defaultValue;
    }

    private static boolean hasFlag (String[] args, String flag) {
        for (String arg : args)
            if (flag.equals (arg)) return true;
        return false;
    }

    private static String sub (String[] args) {
        String s = positional (args, 1);
        return s != null ? s : "list";
    }

    private static void requireSub (String[] args, String fallback) {
        // nothing to enforce – defaults to fallback via sub()
    }

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
        System.out.println ("  help                                   Diese Hilfe");
        System.out.println ();
        System.out.println ("Globale Optionen:");
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
