package de.kreisalarm.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;

public class RestClient {
    private static final ObjectMapper MAPPER = new ObjectMapper ();

    private final Config config;
    private final HttpClient http;
    //: Einmal je Prozess geholt, siehe currentUser(). null = nicht ermittelbar.
    private JsonNode aktuellerBenutzer = null;
    private boolean benutzerGeholt = false;

    public RestClient (Config config) throws Exception {
        this(config, false);
    }

    public RestClient (Config config, boolean insecure) throws Exception {
        this.config = config;
        HttpClient.Builder builder = HttpClient.newBuilder ()
            .cookieHandler (new CookieManager ())
            .followRedirects (HttpClient.Redirect.NORMAL);
        if (insecure) {
            // X509ExtendedTrustManager verhindert, dass Java einen AbstractTrustManagerWrapper
            // darum legt, der Hostname-Prüfung trotzdem erzwingen würde.
            SSLContext ctx = SSLContext.getInstance ("TLS");
            ctx.init (null, new TrustManager[]{ new X509ExtendedTrustManager () {
                public void checkClientTrusted (X509Certificate[] c, String a) {}
                public void checkServerTrusted (X509Certificate[] c, String a) {}
                public void checkClientTrusted (X509Certificate[] c, String a, Socket s) {}
                public void checkServerTrusted (X509Certificate[] c, String a, Socket s) {}
                public void checkClientTrusted (X509Certificate[] c, String a, SSLEngine e) {}
                public void checkServerTrusted (X509Certificate[] c, String a, SSLEngine e) {}
                public X509Certificate[] getAcceptedIssuers () { return new X509Certificate[0]; }
            }}, null);
            builder.sslContext (ctx);
        }
        this.http = builder.build ();
    }

    public JsonNode login (String password, String token, String uuid) throws Exception {
        StringBuilder params = new StringBuilder ()
            .append ("login=").append (enc (config.getLogin ()))
            .append ("&password=").append (enc (password))
            .append ("&kreisverbandID=").append (enc (config.getKvid ()));
        if (uuid != null)
            params.append ("&uuid=").append (enc (uuid));
        if (token != null && !token.isBlank ())
            params.append ("&token=").append (enc (token));

        HttpRequest req = HttpRequest.newBuilder ()
            .uri (URI.create (config.getUrl () + "/backend/rest/app/login?" + params))
            .POST (HttpRequest.BodyPublishers.noBody ())
            .build ();

        HttpResponse<String> resp = http.send (req, HttpResponse.BodyHandlers.ofString ());

        resp.headers ().allValues ("set-cookie").forEach (header -> {
            for (String part : header.split (";")) {
                part = part.trim ();
                if (part.startsWith ("JSESSIONID=")) {
                    try {
                        config.setSession (part.substring ("JSESSIONID=".length ()));
                        config.save ();
                    } catch (Exception e) {
                        System.err.println ("Warnung: Session konnte nicht gespeichert werden: " + e.getMessage ());
                    }
                    break;
                }
            }
        });

        return MAPPER.readTree (resp.body ());
    }

    public JsonNode get (String path) throws Exception {
        return get (path, Map.of ());
    }

    public JsonNode get (String path, Map<String, String> params) throws Exception {
        HttpRequest req = HttpRequest.newBuilder ()
            .uri (URI.create (buildUrl (path, params)))
            .header ("Cookie", "JSESSIONID=" + config.getSession ())
            .GET ()
            .build ();

        HttpResponse<String> resp = http.send (req, HttpResponse.BodyHandlers.ofString ());
        requireOk (resp);
        JsonNode body = MAPPER.readTree (resp.body ());
        requireErfolg (body);
        return body;
    }

    /**
     * Der Benutzer der laufenden Sitzung, oder null, wenn er sich nicht
     * ermitteln laesst.
     *
     * <p>Gebraucht, um Objekte ins Verhaeltnis zum Aufrufer zu setzen — etwa ob
     * ein Kalender ihm selbst, seinem Kreisverband oder einem fremden gehoert.
     * Ohne das waere die Angabe nicht berechenbar: die CLI kennt sonst nur ihr
     * Sitzungs-Cookie, nicht die Identitaet dahinter.
     *
     * <p>Das Ergebnis wird gemerkt — auch ein Fehlschlag. Ein zweiter Versuch
     * je Aufruf wuerde nur denselben Fehler noch einmal kosten. Ein Fehlschlag
     * wirft NICHT: eine Auflistung soll nicht daran scheitern, dass eine
     * ergaenzende Angabe fehlt; sie faellt dann auf "unbekannt" zurueck.
     */
    public JsonNode currentUser () {
        if (benutzerGeholt)
            return aktuellerBenutzer;
        benutzerGeholt = true;
        try {
            JsonNode n = get ("/backend/rest/current-user");
            // Nicht angemeldet liefert {} — ein Objekt ohne id ist kein Benutzer.
            aktuellerBenutzer = (n != null && n.isObject () && n.hasNonNull ("id")) ? n : null;
        } catch (Exception e) {
            aktuellerBenutzer = null;
        }
        return aktuellerBenutzer;
    }

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

    /**
     *  Sucht in einem Store. <b>Der Store kennt keinen Parameter {@code query}</b> –
     *  er beantwortet ihn mit {@code success:false}. Gesucht wird ueber {@code filter}.
     *
     *  @param queryProperty Eigenschaft, in der {@code query} gesucht wird
     *                       (Person: nachname, Gruppe: name). Null = keine Suche.
     *  @param filterProperty Eigenschaft, ueber die exakt gefiltert wird
     *                       (meist projektID; CalendarEvent hat kein projektID
     *                       und filtert stattdessen ueber calendarID). Null = kein Filter.
     */
    public JsonNode getList (String className, int limit, String query, String queryProperty,
                             String filterProperty, String filterValue) throws Exception {
        Map<String, String> params = new LinkedHashMap<> ();
        params.put ("start", "0");
        params.put ("limit", String.valueOf (limit));

        // Mit Jackson bauen statt zusammenkleben: query und filterValue stammen bei
        // Dienst-Aufrufen aus einem Sprachmodell und koennten sonst eigene
        // Filter-Eigenschaften in die Abfrage schmuggeln.
        ArrayNode filter = MAPPER.createArrayNode ();
        if (query != null && !query.isBlank () && queryProperty != null) {
            ObjectNode f = filter.addObject ();
            f.put ("property", queryProperty);
            f.put ("value", query);          // ohne exact -> Teiltreffer
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

    private String buildUrl (String path, Map<String, String> params) throws Exception {
        StringBuilder sb = new StringBuilder (config.getUrl ()).append (path);
        if (!params.isEmpty ()) {
            sb.append ("?");
            boolean first = true;
            for (Map.Entry<String, String> e : params.entrySet ()) {
                if (!first) sb.append ("&");
                sb.append (enc (e.getKey ())).append ("=").append (enc (e.getValue ()));
                first = false;
            }
        }
        return sb.toString ();
    }

    /** Der Server antwortet auch auf abgelehnte Abfragen mit HTTP 200 und meldet den
     *  Fehler erst im Envelope ({@code success:false}). Ohne diese Pruefung wuerde
     *  eine fehlgeschlagene Abfrage als leeres Ergebnis durchgehen – ein aufrufendes
     *  Sprachmodell antwortet darauf ueberzeugt "nichts gefunden". */
    private static void requireErfolg (JsonNode body) throws Exception {
        if (body == null || !body.isObject () || !body.has ("success")) return;
        if (body.path ("success").asBoolean (true)) return;
        JsonNode fehler = body.get ("error");
        String text = (fehler == null || fehler.isNull ()) ? "" : fehler.asText ();
        throw new Exception ("Der Server hat die Abfrage abgelehnt"
            + (text.isBlank () ? "." : " (Fehler " + text + ")."));
    }

    private void requireOk (HttpResponse<String> resp) throws Exception {
        if (resp.statusCode () == 401 || resp.statusCode () == 403)
            throw new Exception ("Nicht authentifiziert – bitte mit 'cli login' einloggen.");
        if (resp.statusCode () >= 400)
            throw new Exception ("Server-Fehler " + resp.statusCode () + kurzerBody (resp.body ()));
    }

    /** Haengt einen knappen, normalisierten Auszug des Response-Bodys an – nie den
     *  rohen Body. HTML-Fehlerseiten (Reverse-Proxies, Server-Stacktraces als HTML)
     *  werden komplett verworfen statt gekuerzt, da ihr Anfang fast immer "<html"
     *  bzw. "<!doctype" enthaelt und ein Kuerzen allein das nicht zuverlaessig
     *  entfernen wuerde. Reiner Text wird auf 200 Zeichen gekuerzt. */
    private static String kurzerBody (String body) {
        if (body == null) return "";
        String s = body.strip ();
        if (s.isEmpty ()) return "";
        String lower = s.toLowerCase ();
        if (lower.contains ("<html") || lower.contains ("<!doctype"))
            return "";
        s = s.replaceAll ("\\s+", " ");
        if (s.length () > 200) s = s.substring (0, 200) + "…";
        return ": " + s;
    }

    private static String enc (String s) {
        return URLEncoder.encode (s, StandardCharsets.UTF_8);
    }
}
