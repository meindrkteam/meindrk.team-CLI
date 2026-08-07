# Gemeinsame Helfer, damit test/*.test.sh sowohl unter Linux/macOS/CI als auch
# nativ unter Windows (Git Bash, kein WSL) laufen. Natives java.exe verlangt
# den Windows-Pfadtrenner ';' und real aufloesbare Windows-Pfade statt /tmp/...;
# MSYS wandelt Argumente, die wie ein POSIX-Pfad aussehen (z. B. "/CN=...' bei
# openssl -subj), sonst unerwuenscht in einen Windows-Pfad um.
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) CP_SEP=";" ;;
    *)                    CP_SEP=":" ;;
esac

# Wandelt einen POSIX-Pfad in einen von java.exe/javac.exe aufloesbaren Pfad um.
# Unter Linux/macOS unveraendert, unter Git Bash ueber 'pwd -W'.
winpath () {
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*) ( cd "$1" 2>/dev/null && pwd -W ) || printf '%s' "$1" ;;
        *) printf '%s' "$1" ;;
    esac
}

# Schuetzt einen openssl -subj-Wert ("/CN=...") davor, von MSYS wie ein
# POSIX-Pfad uebersetzt zu werden: ein zusaetzlicher fuehrender Slash laesst
# MSYS den Wert unangetastet (kollabiert zu einem Slash beim eigentlichen
# Aufruf); unter Linux/macOS/CI bleibt der Wert unveraendert.
msys_subj () {
    case "$(uname -s)" in
        MINGW*|MSYS*|CYGWIN*) printf '/%s' "$1" ;;
        *) printf '%s' "$1" ;;
    esac
}
