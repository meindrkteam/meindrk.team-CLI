#!/usr/bin/env bash
# Prueft: manifest --json beschreibt genau die Befehle, die es wirklich gibt.
set -uo pipefail
cd "$(dirname "$0")/.."

CLASSES=/tmp/cliclasses-test
rm -rf "$CLASSES" && mkdir -p "$CLASSES"
javac --release 21 -cp "lib/jackson/*" -d "$CLASSES" src/de/kreisalarm/cli/*.java || {
  echo "FAIL: kompiliert nicht"; exit 1; }

FAKEHOME=/tmp/cli-fakehome
rm -rf "$FAKEHOME" && mkdir -p "$FAKEHOME"

out="$(HOME="$FAKEHOME" java -cp "$CLASSES:lib/jackson/*" de.kreisalarm.cli.CLI manifest --json)"

echo "$out" | python3 -c '
import sys, json, re
d = json.load(sys.stdin)

assert re.fullmatch(r"\d+\.\d+\.\d+", d["cli_version"]), d.get("cli_version")

namen = {c["name"] for c in d["commands"]}
erwartet = {"person_list", "person_get", "gruppe_list", "benutzer_list", "projekt_list"}
assert namen == erwartet, f"erwartet {erwartet}, bekam {namen}"

# Wahrheit ist CLI.java, nicht der Brief: runPerson (list/get), runGruppe,
# runBenutzer, runProjekt und ihre arg(args, "--...", ...)/positional(...)-
# Aufrufe legen fest, welche Parameter es mit welchem typ, welcher pflicht
# und welcher uebergabe wirklich gibt. Ein Dict statt einer blossen Namens-
# menge, damit JEDER der fuenf Befehle gleich streng geprueft wird -- keine
# Sonderfall-Bloecke mehr, die einzelne Befehle laxer pruefen als andere.
erwartete_params = {
    "person_list": {
        # runPerson, case "list": arg(args,"--q",...), arg(args,"--kvid",...),
        # Integer.parseInt(arg(args,"--limit","100"))
        "q":     ("string",  False, "flag"),
        "kvid":  ("string",  False, "flag"),
        "limit": ("integer", False, "flag"),
    },
    "person_get": {
        # runPerson, case "get": positional(args, 2), exitWithError wenn null
        "id": ("string", True, "positional"),
    },
    "gruppe_list": {
        # runGruppe: arg(args,"--kvid",...), arg(args,"--q",...)
        "q":    ("string", False, "flag"),
        "kvid": ("string", False, "flag"),
    },
    "benutzer_list": {
        # runBenutzer: arg(args,"--kvid",...)
        "kvid": ("string", False, "flag"),
    },
    "projekt_list": {},  # runProjekt liest keine Args, alles fest verdrahtet
}

by_name = {c["name"]: c for c in d["commands"]}

for name, erwartete in erwartete_params.items():
    c = by_name[name]
    gefunden = set(c["params"].keys())
    erwartete_namen = set(erwartete.keys())
    assert gefunden == erwartete_namen, f"{name}: erwartet {erwartete_namen}, bekam {gefunden}"

    for pname, (typ, pflicht, uebergabe) in erwartete.items():
        p = c["params"][pname]
        gefunden_typ, gefunden_pflicht, gefunden_uebergabe = p["typ"], p["pflicht"], p["uebergabe"]
        assert gefunden_typ == typ, \
            f"{name}.{pname}.typ: erwartet {typ!r}, bekam {gefunden_typ!r}"
        assert gefunden_pflicht == pflicht, \
            f"{name}.{pname}.pflicht: erwartet {pflicht!r}, bekam {gefunden_pflicht!r}"
        assert gefunden_uebergabe == uebergabe, \
            f"{name}.{pname}.uebergabe: erwartet {uebergabe!r}, bekam {gefunden_uebergabe!r}"

for c in d["commands"]:
    assert c["modus"] == "lesen", c            # CLI ist ausschliesslich lesend
    assert c["beschreibung"].strip(), c
    for pname, p in c["params"].items():
        assert p["typ"] in ("string", "integer"), (c["name"], pname, p)
        assert isinstance(p["pflicht"], bool), (c["name"], pname, p)
        assert p["uebergabe"] in ("positional", "flag"), (c["name"], pname, p)

# default muss zum typ passen: bei integer eine Zahl, kein String --
# sonst bricht ein Consumer, der typ zum Casten/strikten Typisieren nutzt.
pl = by_name["person_list"]
assert pl["params"]["limit"]["default"] == 100, pl["params"]["limit"]
assert isinstance(pl["params"]["limit"]["default"], int) and not isinstance(pl["params"]["limit"]["default"], bool), \
    "limit.default muss eine Zahl sein, ist " + str(type(pl["params"]["limit"]["default"]))

print("OK manifest:", d["cli_version"], sorted(namen))
' || { echo "FAIL: Manifest unerwartet"; echo "war: ${out:0:400}"; exit 1; }
