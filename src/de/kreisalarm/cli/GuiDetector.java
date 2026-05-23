package de.kreisalarm.cli;

import java.util.Optional;

public class GuiDetector {

    private static final String[] SHELL_NAMES = {
        "bash", "zsh", "sh", "fish", "dash", "cmd", "powershell", "pwsh", "wt"
    };

    public static boolean isGuiLaunch (String[] args) {
        for (String arg : args) {
            if ("--gui".equals (arg))    return true;
            if ("--no-gui".equals (arg)) return false;
        }
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
