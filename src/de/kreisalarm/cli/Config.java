package de.kreisalarm.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class Config {
    private static final String FILE = System.getProperty ("user.home") + "/.meindrk-cli.properties";
    private final Properties props = new Properties ();

    public Config () throws IOException {
        Path path = Path.of (FILE);
        if (Files.exists (path)) {
            try (InputStream in = Files.newInputStream (path)) {
                props.load (in);
            }
        }
    }

    public String get (String key) { return props.getProperty (key); }
    public String get (String key, String def) { return props.getProperty (key, def); }

    public void set (String key, String value) {
        if (value == null) props.remove (key);
        else props.setProperty (key, value);
    }

    public void save () throws IOException {
        try (OutputStream out = Files.newOutputStream (Path.of (FILE))) {
            props.store (out, "meinDRK CLI");
        }
    }

    public String getUrl () { return get ("url"); }
    public String getLogin () { return get ("login"); }
    public String getSession () { return get ("session"); }
    public String getKvid () { return get ("kvid"); }
    public String getUuid () { return get ("uuid"); }

    public void setUrl (String url) { set ("url", url); }
    public void setLogin (String login) { set ("login", login); }
    public void setSession (String session) { set ("session", session); }
    public void setKvid (String kvid) { set ("kvid", kvid); }
    public void setUuid (String uuid) { set ("uuid", uuid); }
}
