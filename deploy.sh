#!/usr/bin/env bash
# Build ClassTime and upload it to Firebase App Distribution.
#
#   ./deploy.sh                    # bump versionCode, build release, upload
#   ./deploy.sh --variant debug    # same, but the debug build type
#   ./deploy.sh --no-bump          # reuse the current versionCode
#   ./deploy.sh --skip-upload      # build and sign only
#   ./deploy.sh --notes "fixed X"  # custom release notes
#
# Requires (once):
#   ./scripts/setup-build-env.sh   # JDK + Android SDK
#   ./scripts/create-keystore.sh   # the fixed signing key
#   firebase login --no-localhost  # or GOOGLE_APPLICATION_CREDENTIALS for CI
#   export FIREBASE_APP_ID=1:...:android:...   (or set it in gradle.properties)
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/scripts/env.sh"
cd "$REPO_ROOT"

VARIANT="release"
DO_BUMP=1
DO_UPLOAD=1
NOTES=""

while [ $# -gt 0 ]; do
  case "$1" in
    -v|--variant)  VARIANT="${2:-}"; shift 2 ;;
    --no-bump)     DO_BUMP=0; shift ;;
    --skip-upload) DO_UPLOAD=0; shift ;;
    --notes)       NOTES="${2:-}"; shift 2 ;;
    -h|--help)     awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next } NR>1 { exit }' "$0"; exit 0 ;;
    *) die "Unknown option: $1  (try --help)" ;;
  esac
done

case "$VARIANT" in
  release) VARIANT_CAP="Release" ;;
  debug)   VARIANT_CAP="Debug" ;;
  *) die "--variant must be 'release' or 'debug' (got '$VARIANT')" ;;
esac

[ -n "$NOTES" ] && export FIREBASE_RELEASE_NOTES="$NOTES"

# --- Environment --------------------------------------------------------------

log "Resolving build environment ..."
prepare_build_env
ok "JDK $(jdk_major "$JAVA_HOME") at $JAVA_HOME"
ok "Android SDK at $ANDROID_HOME"

# Normalise the credentials path so Gradle (different cwd) still finds it.
if [ -n "${GOOGLE_APPLICATION_CREDENTIALS:-}" ]; then
  case "$GOOGLE_APPLICATION_CREDENTIALS" in
    "~/"*) GOOGLE_APPLICATION_CREDENTIALS="$HOME/${GOOGLE_APPLICATION_CREDENTIALS#\~/}" ;;
    /*)    : ;;
    *)     GOOGLE_APPLICATION_CREDENTIALS="$REPO_ROOT/$GOOGLE_APPLICATION_CREDENTIALS" ;;
  esac
  export GOOGLE_APPLICATION_CREDENTIALS
  if [ ! -f "$GOOGLE_APPLICATION_CREDENTIALS" ]; then
    die "GOOGLE_APPLICATION_CREDENTIALS points at a file that does not exist:
      $GOOGLE_APPLICATION_CREDENTIALS"
  fi
  if ! grep -q '"type"[[:space:]]*:[[:space:]]*"service_account"' "$GOOGLE_APPLICATION_CREDENTIALS" 2>/dev/null; then
    warn "$GOOGLE_APPLICATION_CREDENTIALS does not look like a service account JSON."
  fi
fi

# --- Preflight ----------------------------------------------------------------

if [ "$DO_UPLOAD" -eq 1 ]; then
  log "Checking deployment configuration ..."
  if ! ./gradlew --quiet --console=plain deployConfigCheck; then
    echo
    err "Deployment is not configured yet - see the list above."
    err "Signing key:  ./scripts/create-keystore.sh"
    err "Sign in:      firebase login --no-localhost      (simplest; no service account)"
    err "  or in CI:   export GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json"
    err "App ID:       firebase apps:list ANDROID         then export FIREBASE_APP_ID=1:...:android:..."
    err "Testers:      export FIREBASE_TESTERS=you@example.com"
    exit 1
  fi
fi

# --- Version bump -------------------------------------------------------------

if [ "$DO_BUMP" -eq 1 ]; then
  log "Bumping versionCode ..."
  # Must be its own Gradle invocation: the build below reads version.properties
  # at configuration time, so the bump has to be finished before it starts.
  ./gradlew --console=plain --quiet bumpVersionCode
fi
VERSION_CODE="$(sed -nE 's/^versionCode=(.*)$/\1/p' version.properties || true)"
VERSION_CODE="${VERSION_CODE%%$'\n'*}"
VERSION_NAME="$(sed -nE 's/^versionName=(.*)$/\1/p' version.properties || true)"
VERSION_NAME="${VERSION_NAME%%$'\n'*}"
ok "Building version $VERSION_NAME (versionCode $VERSION_CODE)"

# --- Build (+ upload) ---------------------------------------------------------

GRADLE_TASKS=("assemble$VARIANT_CAP")
[ "$DO_UPLOAD" -eq 1 ] && GRADLE_TASKS+=("appDistributionUpload$VARIANT_CAP")

log "Running: ./gradlew ${GRADLE_TASKS[*]}"
./gradlew --console=plain "${GRADLE_TASKS[@]}"

# --- Report -------------------------------------------------------------------

APK="$(find "app/build/outputs/apk/$VARIANT" -maxdepth 1 -name '*.apk' -print -quit 2>/dev/null || true)"
echo
if [ -n "$APK" ]; then
  ok "APK: $APK ($(du -h "$APK" | cut -f1))"
  APKSIGNER="$ANDROID_HOME/build-tools/$ANDROID_BUILD_TOOLS/apksigner"
  if [ -x "$APKSIGNER" ]; then
    # The signer certificate digest must stay identical across builds, otherwise
    # the phone refuses to install the update over the existing app.
    "$APKSIGNER" verify --print-certs "$APK" 2>/dev/null \
      | sed -nE 's/^Signer #1 certificate SHA-256 digest: (.*)$/    signer SHA-256: \1/p' || true
  fi
fi

if [ "$DO_UPLOAD" -eq 1 ]; then
  ok "Uploaded version $VERSION_NAME ($VERSION_CODE) to Firebase App Distribution."
  echo "    Testers get a notification; the build also shows up in the"
  echo "    Firebase App Tester app on the phone within a minute or so."
else
  ok "Build complete (upload skipped)."
fi
