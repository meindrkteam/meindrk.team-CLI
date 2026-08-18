# meinDRK.team CLI — Arbeitsanweisung

Java-21-Quellen (`src/de/kreisalarm/cli/`) → Fat-JAR (Jackson eingepackt) → GraalVM
`native-image` je Plattform. Gebaut wird in der CI (`.github/workflows/release.yml`,
Tag `v*`), lokal mit `build.bat` (Windows), `build-linux.sh`, `build-macos.sh`.

Version steht in `src/de/kreisalarm/cli/CLI.java` (`CLI_VERSION`) und muss zum Tag passen.

---

## Release: die Windows-.exe wird IMMER signiert

**Regel:** Jede neue CLI-Version bekommt eine Authenticode-Signatur auf beiden
Windows-Binaries. Ohne Signatur zeigt Windows „Unbekannter Herausgeber“ und
SmartScreen sammelt keine Reputation.

Erzwungen wird das über die Release-Mechanik, nicht über guten Willen:
Die CI erstellt jedes Release als **Draft** (`draft: true`). Sichtbar wird es
erst durch `tools/release-sign.sh` — und das signiert vorher.

```bash
# 1. Version anheben (CLI_VERSION) + committen
# 2. Tag pushen -> CI baut alle Plattformen, Release entsteht als DRAFT
git tag v0.1.15 && git push origin v0.1.15

# 3. SimplySign Desktop starten und einloggen (Token-ID + OTP aus der Handy-App)
# 4. Signieren + freigeben (am Mac):
bash tools/release-sign.sh v0.1.15
```

`release-sign.sh` lädt die Windows-Assets aus dem Draft, signiert sie über
`tools/sign-windows.sh`, prüft die Kette so wie Windows es tut, lädt sie mit
`--clobber` zurück und schaltet erst dann `--draft=false`. Scheitert ein Schritt,
bleibt das Release Draft — es kann also keine unsignierte .exe beim Nutzer landen.

Signiert werden **beide** Artefakte: `meindrk-cli-windows-x64.exe` und
`meindrk-cli-windows-x64-upx.exe`. Linux/macOS sind nicht Authenticode-signierbar
und bleiben bewusst außen vor.

### Schlüssel und Zertifikate

- **Zertifikat:** Certum Open Source Code Signing. Der private Schlüssel liegt
  `never extractable` in Certums **SimplySign-Cloud-HSM** — nie auf Platte.
  Zugriff per PKCS#11; SimplySign Desktop muss **laufen und eingeloggt** sein,
  sonst ist der Token unsichtbar und das Skript bricht mit klarer Meldung ab.
- **Öffentliche Zerts liegen zentral in `~/.certum/`** (nicht im Repo):
  `leaf.pem`, `inter.pem` (von `repository.certum.pl/ccsca2021.cer`),
  `chain.pem` = leaf+intermediate, `root.pem` (CTNCA2).
  Derselbe Ordner bedient auch das `updater`-Projekt → Zert-Erneuerung einmal
  jährlich an genau einer Stelle.
- **Toolchain (Homebrew):** `osslsigncode`, `libp11` (PKCS#11-Engine
  `engines-3/pkcs11.dylib`), `opensc` (`pkcs11-tool`), plus die SimplySign-Desktop-App
  (liefert `/usr/local/lib/libSimplySignPKCS.dylib`).
- **Cert-ID wird zur Laufzeit ermittelt** (`pkcs11-tool -O`), eine Zert-Erneuerung
  mit neuer Key-ID braucht daher keine Skript-Änderung.
- **Timestamp:** RFC3161 über `http://time.certum.pl` → Signatur überlebt den
  Ablauf des Zertifikats.
- **Überschreibbar:** `SIGN_DIR`, `SIGN_ENGINE`, `SIGN_MODULE`, `SIGN_CHAIN`,
  `SIGN_AC`, `SIGN_TS`, `SIGN_HASH`, `SIGN_FORCE`.

### Gotchas (teuer erkauft im updater-Projekt)

- **`-pkcs11cert` sticht `-certs`:** osslsigncode nimmt das Signaturzert dann vom
  Token und ignoriert das Chain-File komplett → nur das Leaf im Signaturblock →
  Windows kann die Kette nicht bauen → UAC sagt trotz gültiger Signatur
  „Unbekannter Herausgeber“. Fix: zusätzlich **`-ac inter.pem`**. `sign-windows.sh`
  zählt nach dem Signieren die Zerts im Block und bricht bei `<2` ab.
- **Reihenfolge UPX → Signatur:** UPX schreibt die .exe neu und würde eine
  vorhandene Signatur strippen. Weil wir die fertigen CI-Artefakte signieren,
  stimmt die Reihenfolge automatisch — beim lokalen `build.bat` gilt: erst UPX,
  dann signieren.
- **`osslsigncode verify` ohne `-CAfile` liefert auf macOS exit≠0**
  („unable to get local issuer“), weil der lokale Trust-Store die Certum-Roots
  nicht kennt — das ist **kein** Signaturfehler. Die Skripte greppen deshalb den
  Output statt auf den Exit-Code zu bauen.
- **Echter Ketten-Test** (das, was Windows macht):
  `osslsigncode verify -CAfile ~/.certum/root.pem -TSA-CAfile ~/.certum/root.pem <exe>`
  → muss `Signature verification: ok` sagen. Nur den **Root** vorgeben, nicht das
  Intermediate — sonst testet man genau die Lücke weg, die Windows sieht.
  `release-sign.sh` macht das vor dem Veröffentlichen.
- **`PKCS7_dataFinal failed` / `Failed to sign spcIndirectDataContent`:**
  SimplySign-Cloud-Session abgelaufen. `pkcs11-tool -O` listet die Objekte
  weiterhin (Token „sichtbar“), nur die Private-Key-Operation scheitert — die
  Token-Preflight fängt das also NICHT. Abhilfe: in SimplySign Desktop neu
  einloggen (Token-ID + OTP).
- **Idempotent:** bereits signierte .exe wird übersprungen (`SIGN_FORCE=1`
  überschreibt).
