package de.kreisalarm.cli;

import com.fasterxml.jackson.databind.JsonNode;

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

    public static void main (String[] args) throws Exception {
        if (args.length == 0) {
            printHelp ();
            return;
        }

        String cmd = args[0];

        if ("help".equals (cmd)) {
            printHelp ();
            return;
        }

        Config config = new Config ();

        if ("setup".equals (cmd)) {
            runSetup (config);
            return;
        }

        if (config.getUrl () == null) {
            System.err.println ("Nicht konfiguriert. Bitte zuerst 'cli setup' ausführen.");
            System.exit (1);
        }

        boolean insecure = hasFlag (args, "--insecure");
        RestClient client = new RestClient (config, insecure);

        switch (cmd) {
            case "login":
                runLogin (client, config, args);
                break;
            case "projekt":
                runProjekt (client, args);
                break;
            case "person":
                runPerson (client, args);
                break;
            case "gruppe":
                runGruppe (client, args);
                break;
            case "benutzer":
                runBenutzer (client, args);
                break;
            default:
                System.err.println ("Unbekannter Befehl: " + cmd);
                printHelp ();
                System.exit (1);
        }
    }

    // -------------------------------------------------------------------------
    // setup
    // -------------------------------------------------------------------------

    private static void runSetup (Config config) throws Exception {
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

    private static void runLogin (RestClient client, Config config, String[] args) throws Exception {
        String password = arg (args, "--password", null);
        String token    = arg (args, "--token", null);

        if (password == null) {
            Console console = System.console ();
            if (console == null) {
                System.err.println ("Kein Terminal – bitte --password <pw> angeben.");
                System.exit (1);
            }
            password = new String (console.readPassword ("Passwort für %s: ", config.getLogin ()));
        }

        // UUID identifiziert dieses Gerät dauerhaft; nach einmalig erfolgter 2FA wird es vom Server
        // als vertrauenswürdig gespeichert und 2FA bei späteren Logins übersprungen.
        if (config.getUuid () == null) {
            config.setUuid (UUID.randomUUID ().toString ());
            config.save ();
        }

        JsonNode result = client.login (password, token, config.getUuid ());
        String reason = result.path ("reason").asText ("");

        if (!result.path ("success").asBoolean (false)) {
            if ("missing google-authentification-token".equals (reason)
                    || "wrong google authentification code".equals (reason)) {
                if (token != null) {
                    System.err.println ("Falscher Google-Authenticator-Code.");
                    System.exit (1);
                }
                Console console = System.console ();
                if (console == null) {
                    System.err.println ("Google Authenticator erforderlich – bitte --token <6-stelliger-code> angeben.");
                    System.exit (1);
                }
                token = console.readLine ("Google Authenticator Code: ").trim ();
                result = client.login (password, token, config.getUuid ());
                reason = result.path ("reason").asText ("");

            } else if ("missing email-token".equals (reason)
                    || "wrong email code".equals (reason)) {
                if (token != null) {
                    System.err.println ("Falscher E-Mail-Code.");
                    System.exit (1);
                }
                Console console = System.console ();
                if (console == null) {
                    System.err.println ("E-Mail-2FA erforderlich – bitte --token <code> angeben.");
                    System.exit (1);
                }
                System.out.println ("Ein Code wurde per E-Mail gesendet.");
                token = console.readLine ("E-Mail Code: ").trim ();
                result = client.login (password, token, config.getUuid ());
                reason = result.path ("reason").asText ("");
            }
        }

        if (result.path ("success").asBoolean (false)) {
            JsonNode user = result.path ("user");
            System.out.println ("Eingeloggt als " + user.path ("vorname").asText () + " " + user.path ("nachname").asText ()
                + " (Projekt " + user.path ("projektID").asText () + ")");
        } else {
            System.err.println ("Login fehlgeschlagen: " + reason);
            System.exit (1);
        }
    }

    // -------------------------------------------------------------------------
    // projekt
    // -------------------------------------------------------------------------

    private static void runProjekt (RestClient client, String[] args) throws Exception {
        requireSub (args, "list");
        JsonNode result = client.getList ("Projekt", 1000, null, null);
        TablePrinter.print (result.path ("root"), new String[]{"id", "name", "organisation", "prefix"});
    }

    // -------------------------------------------------------------------------
    // person
    // -------------------------------------------------------------------------

    private static void runPerson (RestClient client, String[] args) throws Exception {
        String sub = sub (args);
        switch (sub) {
            case "list":
                String kvid  = arg (args, "--kvid",  null);
                String query = arg (args, "--q",     null);
                int limit    = Integer.parseInt (arg (args, "--limit", "100"));
                JsonNode list = client.getList ("Person", limit, query, kvid);
                TablePrinter.print (list.path ("root"),
                    new String[]{"id", "projektID", "nachname", "vorname", "geburtsdatum", "status", "aktiv"});
                break;
            case "get":
                if (args.length < 3) { System.err.println ("Person-ID fehlt."); System.exit (1); }
                JsonNode person = client.get ("/backend/rest/Person/" + args[2]);
                TablePrinter.printObject (person);
                break;
            default:
                System.err.println ("Unbekannter Subbefehl: " + sub);
                System.exit (1);
        }
    }

    // -------------------------------------------------------------------------
    // gruppe
    // -------------------------------------------------------------------------

    private static void runGruppe (RestClient client, String[] args) throws Exception {
        requireSub (args, "list");
        String kvid  = arg (args, "--kvid", null);
        String query = arg (args, "--q",    null);
        JsonNode result = client.getList ("Gruppe", 1000, query, kvid);
        TablePrinter.print (result.path ("root"), new String[]{"id", "projektID", "name"});
    }

    // -------------------------------------------------------------------------
    // benutzer
    // -------------------------------------------------------------------------

    private static void runBenutzer (RestClient client, String[] args) throws Exception {
        requireSub (args, "list");
        String kvid = arg (args, "--kvid", null);
        JsonNode result = client.getList ("Benutzer", 1000, null, kvid);
        TablePrinter.print (result.path ("root"),
            new String[]{"id", "projektID", "login", "vorname", "nachname", "email", "deaktiviert"});
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
        return args.length >= 2 ? args[1] : "list";
    }

    private static void requireSub (String[] args, String fallback) {
        // nothing to enforce – defaults to fallback via sub()
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
        System.out.println ();
        System.out.println ("Beispiel:");
        System.out.println ("  cli setup");
        System.out.println ("  cli login");
        System.out.println ("  cli person list --q Müller --limit 20");
        System.out.println ("  cli person get 12345");
    }
}
