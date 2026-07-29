#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

BASE_URL="${MCONNECT_BASE_URL:-https://api-mfpl.theairix.com/}"
APP_URL="${MCONNECT_APP_URL:-https://mg.theairix.com/}"
AGP_VERSION="${AGP_VERSION:-9.0.1}"
DESKTOP_DIR="${DESKTOP_DIR:-$HOME/Desktop}"
KEY_INFO="${KEY_INFO:-$DESKTOP_DIR/mconnect-play-upload-key-info.txt}"
VERSION_STATE_FILE="${VERSION_STATE_FILE:-$ROOT_DIR/.mconnect-version-code}"
EXPECTED_UPLOAD_SHA1="${EXPECTED_UPLOAD_SHA1:-39:98:60:F2:34:E9:F1:37:4E:8E:D4:27:85:69:71:69:A6:FB:7B:AF}"
BUILD_TASKS="${BUILD_TASKS:-:app:assembleDebug :app:assembleRelease :app:bundleRelease}"
USE_CLAUDE="${USE_CLAUDE:-1}"
PULL_MAIN="${PULL_MAIN:-1}"

die() {
  echo "ERROR: $*" >&2
  exit 1
}

ensure_file() {
  [[ -f "$1" ]] || die "Missing required file: $1"
}

read_key_info() {
  if [[ ! -f "$KEY_INFO" ]]; then
    local discovered
    discovered="$(find "$HOME" -maxdepth 4 -name 'mconnect-play-upload-key-info.txt' 2>/dev/null | sort | tail -1 || true)"
    if [[ -n "$discovered" ]]; then
      KEY_INFO="$discovered"
      echo "Using discovered key info: $KEY_INFO"
    fi
  fi

  ensure_file "$KEY_INFO"
  KEYSTORE="$(awk -F': ' '/^Keystore:/ {print $2}' "$KEY_INFO")"
  ALIAS="$(awk -F': ' '/^Alias:/ {print $2}' "$KEY_INFO")"
  STOREPASS="$(awk -F': ' '/^Store password:/ {print $2}' "$KEY_INFO")"
  KEYPASS="$(awk -F': ' '/^Key password:/ {print $2}' "$KEY_INFO")"

  [[ -n "${KEYSTORE:-}" ]] || die "Keystore path missing in $KEY_INFO"
  [[ -n "${ALIAS:-}" ]] || die "Alias missing in $KEY_INFO"
  [[ -n "${STOREPASS:-}" ]] || die "Store password missing in $KEY_INFO"
  [[ -n "${KEYPASS:-}" ]] || die "Key password missing in $KEY_INFO"
  if [[ ! -f "$KEYSTORE" ]]; then
    local key_dir key_name discovered_keystore
    key_dir="$(dirname "$KEY_INFO")"
    key_name="$(basename "$KEYSTORE")"
    discovered_keystore="$key_dir/$key_name"
    if [[ -f "$discovered_keystore" ]]; then
      KEYSTORE="$discovered_keystore"
      echo "Using discovered keystore: $KEYSTORE"
    fi
  fi
  ensure_file "$KEYSTORE"

  local actual_sha1
  actual_sha1="$(keytool -list -v -keystore "$KEYSTORE" -storepass "$STOREPASS" -alias "$ALIAS" 2>/dev/null | awk -F'SHA1: ' '/SHA1:/ {print $2; exit}')"
  if [[ -n "$EXPECTED_UPLOAD_SHA1" && "$actual_sha1" != "$EXPECTED_UPLOAD_SHA1" ]]; then
    die "Wrong upload key. Expected SHA1 $EXPECTED_UPLOAD_SHA1 but $KEYSTORE alias $ALIAS is $actual_sha1"
  fi
}

find_apksigner() {
  APKSIGNER="$(find "$HOME/Library/Android/sdk" -name apksigner -type f 2>/dev/null | sort | tail -1)"
  [[ -n "${APKSIGNER:-}" ]] || die "Could not find Android SDK apksigner"
}

stash_tracked_changes_if_needed() {
  if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
    git stash push -m "loop-sh-pre-pull-$(date +%Y%m%d-%H%M%S)"
    STASHED_FOR_PULL=1
  else
    STASHED_FOR_PULL=0
  fi
}

pull_main() {
  [[ "$PULL_MAIN" == "1" ]] || return 0
  stash_tracked_changes_if_needed
  git pull origin main
  if [[ "${STASHED_FOR_PULL:-0}" == "1" ]]; then
    echo "Local tracked changes were stashed before pull. Leaving stash in place to avoid merge conflicts."
  fi
}

current_version_code() {
  awk -F'= *' '/^[[:space:]]*versionCode[[:space:]]*=/ {gsub(/[^0-9]/, "", $2); print $2; exit}' app/build.gradle.kts
}

highest_desktop_version_code() {
  find "$DESKTOP_DIR" -maxdepth 1 -type f -name 'Mconnect-*-v*' 2>/dev/null \
    | sed -E 's/.*-v([0-9]+)-.*/\1/' \
    | awk '/^[0-9]+$/ { if ($1 > max) max = $1 } END { print max + 0 }'
}

state_version_code() {
  if [[ -f "$VERSION_STATE_FILE" ]]; then
    awk '/^[0-9]+$/ { print $1; exit }' "$VERSION_STATE_FILE"
  else
    echo 0
  fi
}

set_version_code_and_agp() {
  local current desktop_high state_high high next
  current="$(current_version_code)"
  [[ -n "$current" ]] || current=0
  desktop_high="$(highest_desktop_version_code)"
  state_high="$(state_version_code)"
  high="$current"
  (( desktop_high > high )) && high="$desktop_high"
  (( state_high > high )) && high="$state_high"

  if [[ -n "${VERSION_CODE:-}" ]]; then
    next="$VERSION_CODE"
  else
    next=$((high + 1))
  fi

  perl -0pi -e "s/versionCode = \\d+/versionCode = $next/" app/build.gradle.kts
  perl -0pi -e "s/^agp = \"[^\"]+\"/agp = \"$AGP_VERSION\"/m" gradle/libs.versions.toml
  VERSION_CODE="$next"
  printf '%s\n' "$VERSION_CODE" > "$VERSION_STATE_FILE"
  echo "Using versionCode=$VERSION_CODE"
  echo "Version sources: gradle=$current desktop=$desktop_high state=$state_high"
  echo "Using AGP=$AGP_VERSION"
}

run_claude_note() {
  [[ "$USE_CLAUDE" == "1" ]] || return 0
  if ! command -v claude >/dev/null 2>&1; then
    echo "Claude CLI not found; skipping Claude note."
    return 0
  fi

  local out="$DESKTOP_DIR/Mconnect-loop-v${VERSION_CODE}-claude-note-$(date +%Y%m%d-%H%M%S).txt"
  echo "Running Claude CLI quick build note..."
  claude -p "In this Android repo, after git pull and before release build, briefly list any obvious build/release risks from app/build.gradle.kts and gradle/libs.versions.toml. Do not edit files." >"$out" 2>/tmp/mconnect_loop_claude.err || {
    echo "Claude CLI note failed; continuing build. See /tmp/mconnect_loop_claude.err"
    rm -f "$out"
    return 0
  }
  echo "Claude note: $out"
}

build_all() {
  echo "Building with BASE_URL=$BASE_URL"
  echo "Building with APP_URL=$APP_URL"
  MCONNECT_BASE_URL="$BASE_URL" MCONNECT_APP_URL="$APP_URL" ./gradlew $BUILD_TASKS
}

copy_sign_verify() {
  read_key_info
  find_apksigner

  local stamp debug_apk release_apk release_aab
  stamp="$(date +%Y%m%d-%H%M%S)"
  debug_apk="$DESKTOP_DIR/Mconnect-api-mfpl-v${VERSION_CODE}-dev-debug-$stamp.apk"
  release_apk="$DESKTOP_DIR/Mconnect-api-mfpl-v${VERSION_CODE}-prod-release-signed-$stamp.apk"
  release_aab="$DESKTOP_DIR/Mconnect-api-mfpl-v${VERSION_CODE}-prod-playstore-release-signed-$stamp.aab"

  cp app/build/outputs/apk/debug/app-debug.apk "$debug_apk"
  cp app/build/outputs/apk/release/app-release-unsigned.apk "$release_apk"
  cp app/build/outputs/bundle/release/app-release.aab "$release_aab"

  "$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$ALIAS" \
    --ks-pass "pass:$STOREPASS" \
    --key-pass "pass:$KEYPASS" \
    "$release_apk"

  jarsigner \
    -sigalg SHA256withRSA \
    -digestalg SHA-256 \
    -keystore "$KEYSTORE" \
    -storepass "$STOREPASS" \
    -keypass "$KEYPASS" \
    "$release_aab" \
    "$ALIAS" >/tmp/mconnect_loop_aab_sign.log 2>&1

  "$APKSIGNER" verify --verbose "$debug_apk" >/tmp/mconnect_loop_debug_verify.log 2>&1
  "$APKSIGNER" verify --verbose "$release_apk" >/tmp/mconnect_loop_release_verify.log 2>&1
  jarsigner -verify -verbose -certs "$release_aab" >/tmp/mconnect_loop_aab_verify.log 2>&1

  cat > /tmp/mconnect_loop_latest_artifacts.env <<EOF
DEV_DEBUG_APK=$debug_apk
PROD_RELEASE_APK=$release_apk
PROD_AAB=$release_aab
EOF

  echo
  echo "Artifacts:"
  ls -lh "$debug_apk" "$release_apk" "$release_aab"
  echo
  echo "Debug APK signature:"
  grep -E 'Verified using|Number of signers' /tmp/mconnect_loop_debug_verify.log
  echo
  echo "Release APK signature:"
  grep -E 'Verified using|Number of signers' /tmp/mconnect_loop_release_verify.log
  echo
  echo "AAB signature:"
  grep -E 'jar verified|Signed by' /tmp/mconnect_loop_aab_verify.log | tail -5
  echo
  echo "SHA256:"
  shasum -a 256 "$debug_apk" "$release_apk" "$release_aab"
}

final_check() {
  echo
  echo "BuildConfig check:"
  rg -n "BASE_URL|APP_URL" \
    app/build/generated/source/buildConfig/debug/com/manjugroups/m_connect/BuildConfig.java \
    app/build/generated/source/buildConfig/release/com/manjugroups/m_connect/BuildConfig.java
  echo
  echo "Version check:"
  rg -n "versionCode|versionName|agp" app/build.gradle.kts gradle/libs.versions.toml app/build/outputs/apk/debug/output-metadata.json app/build/outputs/apk/release/output-metadata.json
}

pull_main
set_version_code_and_agp
run_claude_note
build_all
copy_sign_verify
final_check
