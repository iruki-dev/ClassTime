#!/usr/bin/env bash
# Installs everything needed to build this project in a fresh container:
# a supported JDK, the Android SDK command-line tools, the platform and
# build-tools this project compiles against, and the SDK licences.
#
# Safe to re-run: anything already present is left alone.
#
#   ./scripts/setup-build-env.sh [--no-global-java-home]
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

CMDLINE_TOOLS_VERSION="13114758"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
ADOPTIUM_URL="https://api.adoptium.net/v3/binary/latest/${JDK_WANTED}/ga/linux/x64/jdk/hotspot/normal/eclipse"

WRITE_GLOBAL_JAVA_HOME=1
for arg in "$@"; do
  case "$arg" in
    --no-global-java-home) WRITE_GLOBAL_JAVA_HOME=0 ;;
    -h|--help) awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next } NR>1 { exit }' "$0"; exit 0 ;;
    *) die "Unknown option: $arg" ;;
  esac
done

need() { command -v "$1" >/dev/null 2>&1 || die "Required tool '$1' is not installed."; }
need curl
need unzip

# --- 1. JDK -------------------------------------------------------------------

log "Checking for a JDK between $JDK_MIN and $JDK_MAX ..."
if JDK_PATH="$(find_supported_jdk)"; then
  ok "Using JDK $(jdk_major "$JDK_PATH") at $JDK_PATH"
else
  current="$(jdk_major "${JAVA_HOME:-}" 2>/dev/null || true)"
  warn "No supported JDK found (current: ${current:-none}). Gradle 8.9 + AGP 8.5.2 need JDK $JDK_MIN-$JDK_MAX."
  log "Downloading Temurin $JDK_WANTED from Adoptium ..."
  mkdir -p "$JDK_INSTALL_DIR"
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL --retry 3 -o "$tmp/jdk.tar.gz" "$ADOPTIUM_URL"
  tar -xzf "$tmp/jdk.tar.gz" -C "$tmp"
  extracted="$(find "$tmp" -maxdepth 1 -mindepth 1 -type d -name 'jdk-*' | head -1)"
  [ -n "$extracted" ] || die "Could not find the extracted JDK directory."
  JDK_PATH="$JDK_INSTALL_DIR/temurin-$JDK_WANTED"
  rm -rf "$JDK_PATH"
  mv "$extracted" "$JDK_PATH"
  rm -rf "$tmp"
  trap - EXIT
  jdk_supported "$JDK_PATH" || die "Installed JDK at $JDK_PATH is not usable."
  ok "Installed JDK $(jdk_major "$JDK_PATH") at $JDK_PATH"
fi
export JAVA_HOME="$JDK_PATH"
export PATH="$JAVA_HOME/bin:$PATH"

# --- 2. Android SDK -----------------------------------------------------------

if ! SDK="$(find_android_home)"; then
  SDK="$ANDROID_HOME_DEFAULT"
  log "No Android SDK found; will install into $SDK"
  mkdir -p "$SDK"
fi
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  log "Installing Android SDK command-line tools into $SDK ..."
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fsSL --retry 3 -o "$tmp/cmdline-tools.zip" "$CMDLINE_TOOLS_URL"
  unzip -q "$tmp/cmdline-tools.zip" -d "$tmp"
  mkdir -p "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -rf "$tmp"
  trap - EXIT
  ok "Command-line tools installed."
else
  ok "Android SDK command-line tools already present."
fi

SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$PATH"

# A bounded stream of "y" rather than `yes`: `yes` is still running when
# sdkmanager exits, and the resulting SIGPIPE trips `set -o pipefail`.
accept_prompts() { printf 'y\n%.0s' $(seq 1 200); }

log "Accepting Android SDK licences ..."
accept_prompts | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null
ok "Licences accepted."

log "Installing SDK packages (platform-tools, platforms;$ANDROID_PLATFORM, build-tools;$ANDROID_BUILD_TOOLS) ..."
accept_prompts | "$SDKMANAGER" --sdk_root="$SDK" \
  "platform-tools" "platforms;$ANDROID_PLATFORM" "build-tools;$ANDROID_BUILD_TOOLS" >/dev/null
ok "SDK packages installed."

# --- 3. Firebase CLI ----------------------------------------------------------
# Optional but by far the easiest way to authenticate: the App Distribution
# Gradle plugin reads the token `firebase login` stores, so no service account
# or downloadable JSON key is needed.

if command -v firebase >/dev/null 2>&1; then
  ok "Firebase CLI already installed ($(firebase --version 2>/dev/null | head -1))."
elif command -v npm >/dev/null 2>&1; then
  log "Installing the Firebase CLI ..."
  if npm install -g firebase-tools >/dev/null 2>&1; then
    ok "Firebase CLI installed."
  elif command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null &&
       sudo npm install -g firebase-tools >/dev/null 2>&1; then
    ok "Firebase CLI installed (via sudo)."
  else
    warn "Could not install the Firebase CLI automatically."
    warn "Install it yourself with: sudo npm install -g firebase-tools"
  fi
else
  warn "npm not found - skipping Firebase CLI install."
fi

# --- 4. local.properties ------------------------------------------------------

LOCAL_PROPS="$REPO_ROOT/local.properties"
if [ ! -f "$LOCAL_PROPS" ] || ! grep -q '^[[:space:]]*sdk\.dir=' "$LOCAL_PROPS"; then
  printf 'sdk.dir=%s\n' "$SDK" >> "$LOCAL_PROPS"
  ok "Recorded sdk.dir in local.properties (untracked)."
else
  ok "local.properties already points at an SDK."
fi

# --- 5. Make plain ./gradlew work too -----------------------------------------
# deploy.sh always sets JAVA_HOME itself, but a bare `./gradlew` from a normal
# shell would still pick up the container's unsupported JDK. A user-level Gradle
# property fixes that without putting a machine path into the repo.

if [ "$WRITE_GLOBAL_JAVA_HOME" -eq 1 ]; then
  GRADLE_USER_PROPS="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
  mkdir -p "$(dirname "$GRADLE_USER_PROPS")"
  touch "$GRADLE_USER_PROPS"
  if grep -q '^# >>> classtime setup-build-env' "$GRADLE_USER_PROPS"; then
    python3 - "$GRADLE_USER_PROPS" "$JDK_PATH" <<'PY'
import re, sys
path, jdk = sys.argv[1], sys.argv[2]
text = open(path, encoding='utf-8').read()
block = f"# >>> classtime setup-build-env\norg.gradle.java.home={jdk}\n# <<< classtime setup-build-env\n"
text = re.sub(r"# >>> classtime setup-build-env.*?# <<< classtime setup-build-env\n",
              block, text, flags=re.S)
open(path, 'w', encoding='utf-8').write(text)
PY
  else
    {
      printf '\n# >>> classtime setup-build-env\n'
      printf 'org.gradle.java.home=%s\n' "$JDK_PATH"
      printf '# <<< classtime setup-build-env\n'
    } >> "$GRADLE_USER_PROPS"
  fi
  ok "Pointed Gradle at $JDK_PATH via $GRADLE_USER_PROPS"
  warn "That file is outside the repo and affects every Gradle build for this user."
  warn "Re-run with --no-global-java-home to skip it (deploy.sh works either way)."
fi

# --- Summary ------------------------------------------------------------------

echo
log "Build environment ready:"
echo "    JAVA_HOME    $JAVA_HOME  (JDK $(jdk_major "$JAVA_HOME"))"
echo "    ANDROID_HOME $ANDROID_HOME"
echo "    platform     $ANDROID_PLATFORM"
echo "    build-tools  $ANDROID_BUILD_TOOLS"
echo
echo "Next: ./scripts/create-keystore.sh   (once, to create the fixed signing key)"
echo "Then: firebase login --no-localhost  (once, to authenticate)"
echo "Then: ./deploy.sh"
