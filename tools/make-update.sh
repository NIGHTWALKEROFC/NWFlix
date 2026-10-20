#!/usr/bin/env bash
# usage: make-update.sh <apk folder> <version> "<notes>" <download base url> [mandatory]
# e.g.   make-update.sh apks 1.1 "Bug fixes" https://github.com/you/NWFlix-Website/releases/download/v1.1/
set -euo pipefail
DIR="${1:?apk folder}"; NAME="${2:?version, e.g. 1.1}"; NOTES="${3:-}"; BASE="${4:?download base url}"; MANDATORY="${5:-}"
KEY="${UPDATE_PRIVATE_KEY:-update_private.pem}"
[ -f "$KEY" ] || { echo "$KEY not found"; exit 1; }
[[ "$BASE" == https://* ]] || { echo "base url must start with https://"; exit 1; }
[[ "$BASE" == */ ]] || BASE="$BASE/"

# same formula as app/build.gradle.kts
IFS=. read -r a b c <<< "$NAME"
a=${a//[!0-9]/}; b=${b//[!0-9]/}; c=${c//[!0-9]/}
CODE=$(( 10#${a:-0} * 1000000 + 10#${b:-0} * 1000 + 10#${c:-0} ))

esc() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }
sha() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
size() { wc -c < "$1" | tr -d ' '; }

entries=""
for abi in arm64-v8a armeabi-v7a x86_64 universal; do
  f="$DIR/app-$abi-release.apk"
  [ -f "$f" ] || { echo "skipping $abi (no file)"; continue; }
  entries+="${entries:+,}\"$abi\":{\"url\":\"${BASE}app-$abi-release.apk\",\"sha256\":\"$(sha "$f")\",\"size\":$(size "$f")}"
done
[ -n "$entries" ] || { echo "no apk files in $DIR"; exit 1; }

mand=false; [ "$MANDATORY" = "mandatory" ] && mand=true
printf '{"versionCode":%s,"versionName":"%s","notes":"%s","mandatory":%s,"apks":{%s}}' \
  "$CODE" "$(esc "$NAME")" "$(esc "$NOTES")" "$mand" "$entries" > update.json
openssl dgst -sha256 -sign "$KEY" update.json | base64 | tr -d '\n' > update.json.sig
echo "update.json and update.json.sig created (version $NAME, code $CODE, mandatory: $mand)"
