#!/usr/bin/env bash
#
# release-sign.sh — sign the Windows binaries of a release and publish it.
#
# The release workflow deliberately creates every release as a DRAFT: the
# signing key lives in Certum's SimplySign cloud HSM and needs an interactive
# OTP login, which GitHub Actions cannot do. So the last release step happens
# here, on a Mac with SimplySign Desktop logged in:
#
#   download the CI-built .exe -> Authenticode-sign -> verify the chain the way
#   Windows does -> re-upload -> flip the draft switch
#
# Until this ran, the release is invisible to users. That is the point: no
# unsigned meinDRK-CLI .exe can ever reach a user.
#
# Usage:  tools/release-sign.sh <tag>             e.g. tools/release-sign.sh v0.1.15
#         tools/release-sign.sh <tag> --keep      keep the downloaded files
#         tools/release-sign.sh <tag> --dry-run   sign + verify locally, but
#                                                 neither upload nor publish
#
# Environment:
#   SIGN_DIR   shared cert directory (default ~/.certum) — needs root.pem for
#              the chain test, plus what sign-windows.sh needs.
#   SIGN_FORCE re-sign even if the asset already carries a signature.
#
set -euo pipefail

TAG=""
KEEP=""
DRYRUN=""
for arg in "$@"; do
  case "$arg" in
    --keep)    KEEP=1 ;;
    --dry-run) DRYRUN=1 ;;
    -*)        echo "release-sign: unknown option: $arg" >&2; exit 1 ;;
    *)         [ -z "$TAG" ] && TAG="$arg" || { echo "release-sign: more than one tag given" >&2; exit 1; } ;;
  esac
done

SIGN_DIR="${SIGN_DIR:-$HOME/.certum}"
ROOT="${SIGN_ROOT:-$SIGN_DIR/root.pem}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Both Windows artifacts are offered for download, so both must be signed.
ASSETS=(
  meindrk-cli-windows-x64.exe
  meindrk-cli-windows-x64-upx.exe
)

die() { echo "release-sign: $*" >&2; exit 1; }

[ -n "$TAG" ] || die "no tag given (usage: release-sign.sh <tag> [--keep] [--dry-run])"
command -v gh >/dev/null 2>&1 || die "gh not found (brew install gh)"
[ -f "$ROOT" ] || die "root cert not found: $ROOT (needed for the Windows-style chain test)"
[ -x "$SCRIPT_DIR/sign-windows.sh" ] || [ -f "$SCRIPT_DIR/sign-windows.sh" ] \
  || die "sign-windows.sh not found next to this script"

# --- release state ------------------------------------------------------------
state="$(gh release view "$TAG" --json isDraft,isPrerelease -q '.isDraft' 2>/dev/null)" \
  || die "release $TAG not found (has the tag been pushed and the workflow finished?)"

if [ "$state" = "false" ]; then
  echo "release-sign: WARNING — $TAG is already published; its unsigned assets were downloadable."
  echo "release-sign: continuing, the assets will be replaced by signed ones."
fi

work="$(mktemp -d "${TMPDIR:-/tmp}/release-sign-$TAG.XXXXXX")"
cleanup() { if [ -n "$KEEP" ]; then echo "release-sign: kept $work"; else rm -rf "$work"; fi; }
trap cleanup EXIT

# --- download -----------------------------------------------------------------
found=()
for a in "${ASSETS[@]}"; do
  if gh release download "$TAG" --pattern "$a" --dir "$work" >/dev/null 2>&1; then
    found+=("$work/$a")
    echo "release-sign: downloaded $a"
  else
    echo "release-sign: asset not in release, skipped: $a"
  fi
done

[ "${#found[@]}" -ge 1 ] || die "no Windows assets found in release $TAG — nothing to sign."
# The plain .exe is the one the README links; a release without it is broken.
printf '%s\n' "${found[@]}" | grep -q 'meindrk-cli-windows-x64\.exe$' \
  || die "meindrk-cli-windows-x64.exe missing from release $TAG — refusing to publish."

# --- sign ---------------------------------------------------------------------
bash "$SCRIPT_DIR/sign-windows.sh" "${found[@]}"

# --- verify the chain the way Windows does ------------------------------------
# Only the ROOT is supplied: handing over the intermediate as well would verify
# away exactly the gap Windows would hit.
for exe in "${found[@]}"; do
  vout="$(osslsigncode verify -CAfile "$ROOT" -TSA-CAfile "$ROOT" "$exe" 2>&1 || true)"
  printf '%s' "$vout" | grep -q 'Signature verification: ok' \
    || { printf '%s\n' "$vout" >&2; die "chain verification failed for $(basename "$exe") — NOT publishing."; }
  echo "release-sign: chain ok  $(basename "$exe")"
done

# --- upload + publish ---------------------------------------------------------
if [ -n "$DRYRUN" ]; then
  echo "release-sign: --dry-run — signed and verified, nothing uploaded, $TAG stays as it is."
  exit 0
fi

gh release upload "$TAG" "${found[@]}" --clobber
echo "release-sign: uploaded signed assets to $TAG"

gh release edit "$TAG" --draft=false >/dev/null
echo "release-sign: published $TAG"
