#!/usr/bin/env bash
# Builds a signed release and writes the update descriptor next to it, so the app's
# self-update can find it.
#
#   scripts/publish-update.sh                      # build the version in build.gradle.kts
#   scripts/publish-update.sh 1.2.1 3              # build an explicit versionName / versionCode
#   BASE_URL=https://example.com/dsh scripts/publish-update.sh
#
# Output lands in release/: the APK plus update.json. Serve that directory over HTTP and point
# the app's "更新地址" at <BASE_URL>/update.json.
set -euo pipefail

cd "$(dirname "$0")/.."
NAME="${1:-}"
CODE="${2:-}"
BASE_URL="${BASE_URL:-.}"

ARGS=()
[ -n "$NAME" ] && ARGS+=("-PdshVersionName=$NAME")
[ -n "$CODE" ] && ARGS+=("-PdshVersionCode=$CODE")

# macOS ships bash 3.2, where "${ARGS[@]}" on an empty array trips `set -u`.
if [ ${#ARGS[@]} -gt 0 ]; then
  ./gradlew --quiet :app:collectRelease "${ARGS[@]}"
else
  ./gradlew --quiet :app:collectRelease
fi

# Resolve what was actually built rather than assuming.
APK="$(ls -t release/*.apk | head -1)"
[ -f "$APK" ] || { echo "no apk produced" >&2; exit 1; }

BT="$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/* | sort -V | tail -1)"
BADGING="$("$BT/aapt2" dump badging "$APK")"
VNAME="$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGING" | head -1)"
VCODE="$(sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" <<<"$BADGING" | head -1)"
# aapt2 has used both spellings across versions.
MINSDK="$(sed -n "s/^\(min\)\{0,1\}[sS]dkVersion:'\([^']*\)'.*/\2/p" <<<"$BADGING" | head -1)"
[ -n "$MINSDK" ] || MINSDK=26
SHA="$(shasum -a 256 "$APK" | awk '{print $1}')"
SIZE="$(wc -c < "$APK" | tr -d ' ')"
NOTES="${NOTES:-}"

python3 - "$APK" "$VNAME" "$VCODE" "$MINSDK" "$SHA" "$SIZE" "$BASE_URL" "$NOTES" <<'PY' > release/update.json
import json, os, sys
apk, vname, vcode, minsdk, sha, size, base, notes = sys.argv[1:9]
print(json.dumps({
    "versionCode": int(vcode),
    "versionName": vname,
    "apkUrl": base.rstrip("/") + "/" + os.path.basename(apk),
    "sha256": sha,
    "sizeBytes": int(size),
    "minSdk": int(minsdk or 0),
    "notes": notes,
}, ensure_ascii=False, indent=2))
PY

echo "built    $APK"
echo "version  $VNAME ($VCODE), minSdk $MINSDK, $SIZE bytes"
echo "sha256   $SHA"
echo "manifest release/update.json  (apkUrl base: $BASE_URL)"
