package de.kreisalarm.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class RestClient {
    private static final ObjectMapper MAPPER = new ObjectMapper ();

    private final Config config;
    private final HttpClient http;

    public RestClient (Config config) {
        this.config = config;
        this.http = HttpClient.newBuilder ()
            .cookieHandler (new CookieManager ())
            .followRedirects (HttpClient.Redirect.NORMAL)
            .build ();
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
        return MAPPER.readTree (resp.body ());
    }

    public JsonNode getList (String className, int limit, String query, String kvid) throws Exception {
        Map<String, String> params = new LinkedHashMap<> ();
        params.put ("start", "0");
        params.put ("limit", String.valueOf (limit));
        if (query != null && !query.isBlank ())
            params.put ("query", query);
        if (kvid != null && !kvid.isBlank ())
            params.put ("filter", "[{\"property\":\"projektID\",\"value\":\"" + kvid + "\",\"exact\":true}]");
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

    private void requireOk (HttpResponse<String> resp) throws Exception {
        if (resp.statusCode () == 401 || resp.statusCode () == 403)
            throw new Exception ("Nicht authentifiziert – bitte mit 'cli login' einloggen.");
        if (resp.statusCode () >= 400)
            throw new Exception ("Server-Fehler " + resp.statusCode () + ": " + resp.body ());
    }

    private static String enc (String s) {
        return URLEncoder.encode (s, StandardCharsets.UTF_8);
    }
}
