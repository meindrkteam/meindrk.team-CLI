package de.kreisalarm.cli;

import java.util.Optional;

public class GuiDetector {

    private static final String[] SHELL_NAMES = {
        "bash", "zsh", "sh", "fish", "dash", "cmd", "powershell", "pwsh", "wt"
    };

    public static boolean isGuiLaunch (String[] args) {
        // Nur im Kopf des Aufrufs lesen, nie in den Werten dahinter: die stammen
        // bei Dienst-Aufrufen aus einem Sprachmodell, und ein Suchtext "--gui"
        // wuerde sonst den blockierenden GUI-Server starten.
        if (CLI.hatGlobalenSchalter (args, "--gui"))    return true;
        if (CLI.hatGlobalenSchalter (args, "--no-gui")) return false;

        // Wer Argumente uebergibt, will einen Befehl ausfuehren – nie die GUI.
        // Die Elternprozess-Heuristik unten erkennt nur den Doppelklick im Finder
        // bzw. Explorer; sie darf sonst nichts entscheiden. Ohne diese Schranke
        // startete jeder Aufruf aus einem Nicht-Shell-Elternprozess (etwa ein
        // Python-Dienst via subprocess) den blockierenden GUI-Server, und der
        // Aufrufer sah eine leere Ausgabe mit Exit-Code 0 – also einen stillen
        // Fehlschlag, der wie Erfolg aussieht.
        if (args.length > 0) return false;

        Optional<ProcessHandle> parent = ProcessHandle.current ().parent ();
        if (parent.isEmpty ()) return false;
        Optional<String> cmd = parent.get ().info ().command ();
        if (cmd.isEmpty ()) return false;
        String lower = cmd.get ().toLowerCase ();
        for (String shell : SHELL_NAMES)
            if (lower.contains (shell)) return false;
        return true;
    }
}
