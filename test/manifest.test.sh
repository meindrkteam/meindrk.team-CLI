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

# Vollstaendige, benannte Parametermenge je Befehl -- exakte Mengengleichheit,
# nicht nur Teilmenge. Sonst bliebe ein Manifest mit vertauschten oder
# falschen Parameternamen gruen.
erwartete_params = {
    "person_list":   {"q", "kvid", "limit"},
    "person_get":    {"id"},
    "gruppe_list":   {"q", "kvid"},
    "benutzer_list": {"kvid"},
    "projekt_list":  set(),
}

by_name = {c["name"]: c for c in d["commands"]}

for name, erwartete in erwartete_params.items():
    c = by_name[name]
    gefunden = set(c["params"].keys())
    assert gefunden == erwartete, f"{name}: erwartet {erwartete}, bekam {gefunden}"

for c in d["commands"]:
    assert c["modus"] == "lesen", c            # CLI ist ausschliesslich lesend
    assert c["beschreibung"].strip(), c
    for pname, p in c["params"].items():
        assert p["typ"] in ("string", "integer"), (c["name"], pname, p)
        assert isinstance(p["pflicht"], bool), (c["name"], pname, p)
        assert p["uebergabe"] in ("positional", "flag"), (c["name"], pname, p)

# person_get.id wird ueber positional(args, 2) gelesen, nicht ueber ein
# --id-Flag -- das Manifest muss das ehrlich als "positional" ausweisen,
# sonst baut ein generischer Aufrufer --id=<wert> und bekommt entweder
# "Person-ID fehlt." oder (schlimmer) still die falsche Person, weil
# positional() z.B. den Wert von --kvid als Position 2 mitzaehlt.
pg = next(c for c in d["commands"] if c["name"] == "person_get")
assert pg["params"]["id"]["pflicht"] is True, pg
assert pg["params"]["id"]["uebergabe"] == "positional", pg

pl = next(c for c in d["commands"] if c["name"] == "person_list")
assert all(p["pflicht"] is False for p in pl["params"].values()), pl
assert all(p["uebergabe"] == "flag" for p in pl["params"].values()), pl
assert pl["params"]["limit"]["typ"] == "integer", pl
# default muss zum typ passen: bei integer eine Zahl, kein String --
# sonst bricht ein Consumer, der typ zum Casten/strikten Typisieren nutzt.
assert pl["params"]["limit"]["default"] == 100, pl["params"]["limit"]
assert isinstance(pl["params"]["limit"]["default"], int) and not isinstance(pl["params"]["limit"]["default"], bool), \
    "limit.default muss eine Zahl sein, ist " + str(type(pl["params"]["limit"]["default"]))

gl = next(c for c in d["commands"] if c["name"] == "gruppe_list")
assert all(p["uebergabe"] == "flag" for p in gl["params"].values()), gl

bl = next(c for c in d["commands"] if c["name"] == "benutzer_list")
assert all(p["uebergabe"] == "flag" for p in bl["params"].values()), bl

print("OK manifest:", d["cli_version"], sorted(namen))
' || { echo "FAIL: Manifest unerwartet"; echo "war: ${out:0:400}"; exit 1; }
