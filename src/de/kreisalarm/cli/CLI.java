package de.kreisalarm.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.Console;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
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
    private static final String CLI_VERSION = "0.1.9";

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
            case "termin":
                runTermin (client, args, json);
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
        JsonNode result = client.getList ("Projekt", 1000, null, null, null, null);
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
                JsonNode list = client.getList ("Person", limit, query, "nachname", "projektID", kvid);
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
        JsonNode result = client.getList ("Gruppe", 1000, query, "name", "projektID", kvid);
        printResult (result.path ("root"), new String[]{"id", "projektID", "name"}, json);
    }

    // -------------------------------------------------------------------------
    // benutzer
    // -------------------------------------------------------------------------

    private static void runBenutzer (RestClient client, String[] args, boolean json) throws Exception {
        String kvid = pruefeId (arg (args, "--kvid", null), "Kreisverband-ID");
        JsonNode result = client.getList ("Benutzer", 1000, null, null, "projektID", kvid);
        printResult (result.path ("root"),
            new String[]{"id", "projektID", "login", "vorname", "nachname", "email", "deaktiviert"}, json);
    }

    // -------------------------------------------------------------------------
    // kalender
    // -------------------------------------------------------------------------

    private static void runKalender (RestClient client, String[] args, boolean json) throws Exception {
        String kvid = pruefeId (arg (args, "--kvid", null), "Kreisverband-ID");
        JsonNode result = client.getList ("Calendar", 1000, null, null, "projektID", kvid);
        JsonNode root = result.path ("root");
        JsonNode ich = client.currentUser ();
        besitzErgaenzen (root, ich);
        schreibrechtErgaenzen (client, root, ich);
        printResult (root, new String[]{"id", "projektID", "name", "besitz", "schreiben"}, json);
    }

    /**
     * Traegt je Kalender ein, ob der angemeldete Benutzer hineinschreiben darf.
     *
     * <p>Unabhaengig vom Besitz: Ein Kalender eines fremden Kreisverbands kann
     * beschreibbar sein, einer des eigenen nur lesbar. Wer einen Termin anlegen
     * will, braucht diese Angabe — sonst erfaehrt er es erst, wenn der Server
     * das Anlegen ablehnt.
     *
     * <p>Ohne ermittelbaren Benutzer bleibt das Feld WEG statt auf false zu
     * stehen. Ein falsches "du darfst nicht" ist schlimmer als keine Angabe:
     * es entwertet beim ersten Gegenbeweis jede weitere Warnung.
     */
    private static void schreibrechtErgaenzen (RestClient client, JsonNode root, JsonNode ich) {
        if (root == null || !root.isArray () || ich == null)
            return;
        long meineID     = ich.path ("id").asLong (-1);
        long meinePerson = ich.path ("personID").asLong (-1);

        // Erst sammeln, welche Gruppen ueberhaupt Schreibrecht gewaehren, dann
        // je Gruppe EINMAL fragen. Eine Gruppe, die nur Leserecht gibt, zu
        // befragen waere ein Aufruf ohne Erkenntnis.
        Set<Long> zuPruefen = new LinkedHashSet<> ();
        for (JsonNode k : root)
            for (JsonNode a : k.path ("calendarAccesses"))
                if (a.path ("writeAccess").asBoolean (false)) {
                    long g = a.path ("gruppeID").asLong (-1);
                    if (g > 0)
                        zuPruefen.add (g);
                    }

        Set<Long> meineGruppen = new HashSet<> ();
        for (Long g : zuPruefen) {
            JsonNode personen = client.gruppePersonIds (g);
            if (personen == null || !personen.isArray ())
                continue;                       // Ausfall -> Gruppe zaehlt nicht
            for (JsonNode p : personen)
                if (meinePerson > 0 && p.path ("id").asLong (-1) == meinePerson) {
                    meineGruppen.add (g);
                    break;
                    }
            }

        for (JsonNode k : root)
            if (k instanceof ObjectNode o)
                o.put ("schreiben", darfSchreiben (o, meineID, meinePerson, meineGruppen));
        }

    /** Besitzer duerfen immer; sonst zaehlt eine Freigabe mit writeAccess. */
    private static boolean darfSchreiben (JsonNode kalender, long meineID, long meinePerson,
                                          Set<Long> meineGruppen) {
        if (meineID > 0 && kalender.path ("benutzerID").asLong (-1) == meineID)
            return true;
        if (meinePerson > 0 && kalender.path ("personID").asLong (-1) == meinePerson)
            return true;
        for (JsonNode a : kalender.path ("calendarAccesses")) {
            if (!a.path ("writeAccess").asBoolean (false))
                continue;
            if (meineID > 0 && a.path ("benutzerID").asLong (-1) == meineID)
                return true;
            if (meinePerson > 0 && a.path ("personID").asLong (-1) == meinePerson)
                return true;
            long g = a.path ("gruppeID").asLong (-1);
            if (g > 0 && meineGruppen.contains (g))
                return true;
            }
        return false;
        }

    /**
     * Traegt je Kalender ein, in welchem Verhaeltnis der angemeldete Benutzer
     * zu ihm steht. Ohne diese Angabe ist eine Kalender-ID fuer einen Menschen
     * wertlos: man kann zu fremden Kalendern eingeladen sein, "eigener
     * Kreisverband" und "mir gehoerend" sind zweierlei.
     */
    private static void besitzErgaenzen (JsonNode root, JsonNode ich) {
        if (root == null || !root.isArray ())
            return;
        for (JsonNode k : root)
            if (k instanceof ObjectNode o)
                o.put ("besitz", besitzBestimmen (o, ich));
        }

    /**
     * eigen | eigener_kv | fremder_kv | unbekannt.
     *
     * <p>Feste Bezeichner, keine Anzeigetexte: der Wert wird maschinell
     * ausgewertet, formuliert wird beim Aufrufer.
     *
     * <p><b>unbekannt</b> statt zu raten. Laesst sich der Sitzungsbenutzer
     * nicht ermitteln oder fehlt eine der Projekt-IDs, waere jede Aussage
     * geraten — und "fremder_kv" auf Verdacht ist die schlechteste Antwort:
     * sie klingt bestimmt und kann falsch sein.
     */
    private static String besitzBestimmen (JsonNode kalender, JsonNode ich) {
        if (ich == null)
            return "unbekannt";
        // -1 ist der Standardwert dieser Felder und darf nie als Treffer zaehlen.
        long meineID     = ich.path ("id").asLong (-1);
        long meinePerson = ich.path ("personID").asLong (-1);
        long meinProjekt = ich.path ("projektID").asLong (-1);
        long besitzerB   = kalender.path ("benutzerID").asLong (-1);
        long besitzerP   = kalender.path ("personID").asLong (-1);
        long projekt     = kalender.path ("projektID").asLong (-1);

        if (meineID > 0 && besitzerB == meineID)
            return "eigen";
        if (meinePerson > 0 && besitzerP == meinePerson)
            return "eigen";
        if (meinProjekt <= 0 || projekt <= 0)
            return "unbekannt";
        return projekt == meinProjekt ? "eigener_kv" : "fremder_kv";
        }

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
            case "create":
                runTerminCreate (client, args, json);
                break;
            case "update":
                runTerminUpdate (client, args, json);
                break;
            case "delete":
                runTerminDelete (client, args, json);
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

    private static void runTerminUpdate (RestClient client, String[] args, boolean json) throws Exception {
        String id = positional (args, 2);
        if (id == null) { exitWithError ("Termin-ID fehlt.", json); return; }
        String terminId = pruefeId (id, "Termin-ID");
        ObjectNode body = terminBody (args);
        if (body.isEmpty ()) { exitWithError ("Keine zu aendernden Felder angegeben.", json); return; }
        JsonNode result = client.put ("/backend/rest/CalendarEvent/" + terminId, body);
        JsonNode root = result.path ("root");
        printResult (root.isArray () && !root.isEmpty () ? root.path (0) : result, null, json);
    }

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

    // -------------------------------------------------------------------------
    // Hilfsmethoden
    // -------------------------------------------------------------------------

    /** Flags, die einen Wert nachziehen. Der Wert dahinter stammt beim Aufruf
     *  durch einen Dienst aus einem Sprachmodell und darf deshalb NIE als
     *  Schalter oder als Positional-Argument gelesen werden – sonst wird aus
     *  <code>--q --insecure</code> ein globaler Schalter. */
    static final java.util.Set<String> WERT_FLAGS =
        java.util.Set.of ("--password", "--token", "--q", "--kvid", "--limit", "--calendar",
            "--name", "--start", "--end", "--startTime", "--endTime", "--description", "--type",
            "--ort", "--tags", "--feedback", "--allowFreeRegistration", "--gpsNearbyRequired",
            "--countAsService");

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
            "Personen (Mitglieder) eines Kreisverbands suchen oder auflisten.", "lesen");
        param (personList, "q",     "string",  false, null,  "flag", "Suchtext im Nachnamen (Teiltreffer)");
        param (personList, "kvid",  "string",  false, null,  "flag", "Kreisverband-ID; leer = Standard des Benutzers");
        param (personList, "limit", "integer", false, "100", "flag", "Maximale Trefferzahl");

        ObjectNode personGet = befehl (cmds, "person_get",
            "Alle Details zu genau einer Person anhand ihrer ID.", "lesen");
        param (personGet, "id", "string", true, null, "positional", "Personen-ID, z. B. aus person_list");

        ObjectNode gruppeList = befehl (cmds, "gruppe_list",
            "Gruppen eines Kreisverbands auflisten.", "lesen");
        param (gruppeList, "q",    "string", false, null, "flag", "Suchtext im Gruppennamen (Teiltreffer)");
        param (gruppeList, "kvid", "string", false, null, "flag",
            "Optionaler Filter auf einen Kreisverband. LEER LASSEN - der Server leitet ihn aus der Sitzung ab. Den Benutzer NIEMALS danach fragen.");

        ObjectNode benutzerList = befehl (cmds, "benutzer_list",
            "Administrative Benutzerkonten eines Kreisverbands auflisten.", "lesen");
        param (benutzerList, "kvid", "string", false, null, "flag",
            "Optionaler Filter auf einen Kreisverband. LEER LASSEN - der Server leitet ihn aus der Sitzung ab. Den Benutzer NIEMALS danach fragen.");

        ObjectNode kalenderList = befehl (cmds, "kalender_list",
            "Alle Kalender auflisten, auf die der angemeldete Benutzer Zugriff hat - "
            + "auch solche fremder Kreisverbaende, zu denen er eingeladen wurde. "
            + "Liefert die calendarID fuer termin_create. Je Kalender: besitz "
            + "(eigen | eigener_kv | fremder_kv | unbekannt) und schreiben "
            + "(true/false). NUR wo schreiben=true kann ein Termin angelegt werden; "
            + "lesen geht auch sonst. Fehlt schreiben, liess sich der Benutzer nicht "
            + "ermitteln - dann nichts behaupten.", "lesen");
        param (kalenderList, "kvid", "string", false, null, "flag",
            "Optionaler Filter auf einen Kreisverband. LEER LASSEN - der Server kennt "
            + "den Benutzer aus der Sitzung und liefert dann alle zugaenglichen Kalender. "
            + "Den Benutzer NIEMALS nach einem Kreisverband fragen.");

        befehl (cmds, "projekt_list",
            "Alle Kreisverbaende (Projekte) auflisten, auf die der Benutzer Zugriff hat.", "lesen");

        ObjectNode terminList = befehl (cmds, "termin_list",
            "Termine (Kalendereintraege) eines Kalenders auflisten oder durchsuchen.", "lesen");
        param (terminList, "calendar", "string", false, null, "flag", "Kalender-ID (aus kalender_list); leer = alle Kalender ungefiltert");
        param (terminList, "q",        "string", false, null, "flag", "Suchtext im Titel (Teiltreffer)");
        param (terminList, "limit",    "integer", false, "100", "flag", "Maximale Trefferzahl");

        ObjectNode terminGet = befehl (cmds, "termin_get",
            "Alle Details zu genau einem Termin anhand seiner ID.", "lesen");
        param (terminGet, "id", "string", true, null, "positional", "Termin-ID, z. B. aus termin_list");

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
        param (terminCreate, "feedback",                "string", false, null, "flag", "NONE|ALL|INVITED");
        param (terminCreate, "allowFreeRegistration",   "string", false, null, "flag", "true|false");
        param (terminCreate, "gpsNearbyRequired",       "string", false, null, "flag", "true|false");
        param (terminCreate, "countAsService",          "string", false, null, "flag", "true|false");

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

        ObjectNode terminDelete = befehl (cmds, "termin_delete",
            "Termin unwiderruflich loeschen.", "schreiben");
        param (terminDelete, "id", "string", true, null, "positional", "Termin-ID, z. B. aus termin_list");
        paramSchalter (terminDelete, "yes", true, "Bestaetigung ohne Nachfrage; im JSON-Modus erforderlich");

        System.out.println (root.toString ());
    }

    private static ObjectNode befehl (ArrayNode cmds, String name, String beschreibung, String modus) {
        ObjectNode c = cmds.addObject ();
        c.put ("name", name);
        c.put ("beschreibung", beschreibung);
        c.put ("modus", modus);
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

    /** Schalter-Parameter ohne Wert, z. B. --yes. typ ist immer "boolean". */
    private static void paramSchalter (ObjectNode cmd, String name, boolean pflicht, String beschreibung) {
        ObjectNode p = ((ObjectNode) cmd.get ("params")).putObject (name);
        p.put ("typ", "boolean");
        p.put ("pflicht", pflicht);
        p.put ("uebergabe", "schalter");
        p.put ("beschreibung", beschreibung);
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
        System.out.println ("  kalender list [--kvid <id>]            Kalender auflisten (calendarID, besitz, schreiben)");
        System.out.println ("  termin  list [--calendar <id>] [--q <text>] [--limit <n>]");
        System.out.println ("                                         Termine auflisten");
        System.out.println ("  termin  get <id>                       Termin-Details anzeigen");
        System.out.println ("  termin  create --calendar <id> --name <text> --start <yyyyMMdd> --end <yyyyMMdd>");
        System.out.println ("                 [--startTime hhmm] [--endTime hhmm] [--description <text>]");
        System.out.println ("                 [--type <text>] [--ort <id>] [--tags <text>]");
        System.out.println ("                 [--feedback NONE|ALL|INVITED] [--allowFreeRegistration true|false]");
        System.out.println ("                 [--gpsNearbyRequired true|false] [--countAsService true|false]");
        System.out.println ("                                         Neuen Termin anlegen");
        System.out.println ("  termin  update <id> [gleiche Flags wie create, alle optional]");
        System.out.println ("                                         Termin aendern (nur gesetzte Felder)");
        System.out.println ("  termin  delete <id> [--yes]            Termin loeschen (--yes noetig ohne Terminal/im --json-Modus)");
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
