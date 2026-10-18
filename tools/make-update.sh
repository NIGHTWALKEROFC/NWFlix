#!/usr/bin/env bash
# Creates the SIGNED update files for NWMovie's in-app updater. Run it on your own computer (Linux, Mac, or Windows with Git Bash / WSL).
#
# ONE-TIME SETUP (keep update_private.pem secret and safe; never upload it anywhere):
#   openssl genrsa -out update_private.pem 3072
#   openssl rsa -in update_private.pem -pubout -outform DER | base64 -w0      # (Mac: ... | base64)  -> value for UPDATE_PUBKEY in Codemagic
#
# EACH RELEASE:
#   1) Download the APKs from the Codemagic build (app-arm64-v8a-release.apk, app-armeabi-v7a-release.apk, app-x86_64-release.apk, app-universal-release.apk) into one folder.
#   2) Upload them to your website.
#   3) Run:  ./make-update.sh <folder-with-apks> <versionCode> <versionName> "<release notes>" <https://your-site.com/path/>  [mandatory]
#      versionCode = the Codemagic build number of that build (shown as "build" in Settings > App updates).
#   4) Upload the two created files  update.json  and  update.json.sig  to the address you put in UPDATE_URL.
set -euo pipefail
DIR="${1:?folder with apks}"; CODE="${2:?versionCode}"; NAME="${3:?versionName}"; NOTES="${4:-}"; BASE="${5:?https://your-site.com/path/}"; MANDATORY="${6:-}"
KEY="${UPDATE_PRIVATE_KEY:-update_private.pem}"
[ -f "$KEY" ] || { echo "Private key $KEY not found (see the setup lines at the top of this file)"; exit 1; }
[[ "$BASE" == https://* ]] || { echo "The website address must start with https://"; exit 1; }
[[ "$BASE" == */ ]] || BASE="$BASE/"
esc() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
sha() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
size() { wc -c < "$1" | tr -d ' '; }
entries=""
for abi in arm64-v8a armeabi-v7a x86_64 universal; do
  f="$DIR/app-$abi-release.apk"
  [ -f "$f" ] || { echo "skip $abi (no $f)"; continue; }
  entries+="${entries:+,}\"$abi\":{\"url\":\"${BASE}app-$abi-release.apk\",\"sha256\":\"$(sha "$f")\",\"size\":$(size "$f")}"
done
[ -n "$entries" ] || { echo "No APK files found in $DIR"; exit 1; }
mand=false; [ "$MANDATORY" = "mandatory" ] && mand=true
printf '{"versionCode":%s,"versionName":"%s","notes":"%s","mandatory":%s,"apks":{%s}}' "$CODE" "$(esc "$NAME")" "$(esc "$NOTES")" "$mand" "$entries" > update.json
openssl dgst -sha256 -sign "$KEY" update.json | base64 | tr -d '\n' > update.json.sig
echo "Created update.json and update.json.sig. Upload both to your website (the UPDATE_URL address)."
