#!/usr/bin/env bash
# Builds a signed release the app's self-update can find.
#
# Two ways to publish:
#
#   scripts/publish-update.sh --github 1.3.1
#       Build, then create a GitHub release with the APK attached. This is what the shipped
#       default update URL reads, so nothing else needs configuring. Needs `gh` logged in.
#
#   scripts/publish-update.sh 1.3.1
#   BASE_URL=https://example.com/dsh scripts/publish-update.sh 1.3.1
#       Build and write release/update.json beside the APK. Serve that directory yourself and
#       point 更新地址 at <BASE_URL>/update.json. This path also records a sha256, which the app
#       verifies before installing.
#
# versionCode is derived as major*10000 + minor*100 + patch, matching what CI does, unless you
# pass one explicitly as the second argument.
set -euo pipefail

cd "$(dirname "$0")/.."

GITHUB=0
if [ "${1:-}" = "--github" ]; then
  GITHUB=1
  shift
fi

NAME="${1:-}"
CODE="${2:-}"
BASE_URL="${BASE_URL:-.}"

# Keep local and CI versionCodes in step so the same version never disagrees with itself.
if [ -n "$NAME" ] && [ -z "$CODE" ]; then
  CODE="$(awk -F. '{printf "%d", $1*10000 + $2*100 + $3}' <<<"$NAME")"
fi

if [ "$GITHUB" = "1" ]; then
  command -v gh >/dev/null || { echo "gh is not installed; see README" >&2; exit 1; }
  gh auth status >/dev/null 2>&1 || { echo "gh is not logged in: run 'gh auth login'" >&2; exit 1; }
  [ -n "$NAME" ] || { echo "--github needs a version, e.g. --github 1.3.1" >&2; exit 1; }
fi

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

if [ "$GITHUB" = "1" ]; then
  TAG="v$VNAME"
  {
    echo "DSH Mobile $TAG"
    echo
    [ -n "$NOTES" ] && { echo "$NOTES"; echo; }
    echo "安装包会被应用内的「检查更新」自动发现。"
    echo
    echo "SHA-256："
    echo '```'
    echo "$SHA"
    echo '```'
  } > release/notes-$VNAME.md

  gh release create "$TAG" "$APK" \
    --title "$TAG" --notes-file "release/notes-$VNAME.md" --latest
  echo "released $TAG — the app will offer it on the next check"
else
  echo "manifest release/update.json  (apkUrl base: $BASE_URL)"
fi
