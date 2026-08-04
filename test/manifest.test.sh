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

for c in d["commands"]:
    assert c["modus"] == "lesen", c            # CLI ist ausschliesslich lesend
    assert c["beschreibung"].strip(), c
    for pname, p in c["params"].items():
        assert p["typ"] in ("string", "integer"), (c["name"], pname, p)
        assert isinstance(p["pflicht"], bool), (c["name"], pname, p)

# person_get braucht zwingend eine id, person_list nicht
pg = next(c for c in d["commands"] if c["name"] == "person_get")
assert pg["params"]["id"]["pflicht"] is True, pg
pl = next(c for c in d["commands"] if c["name"] == "person_list")
assert all(p["pflicht"] is False for p in pl["params"].values()), pl
assert pl["params"]["limit"]["typ"] == "integer", pl

print("OK manifest:", d["cli_version"], sorted(namen))
' || { echo "FAIL: Manifest unerwartet"; echo "war: ${out:0:400}"; exit 1; }
