#!/usr/bin/env bash
# Shared helpers for the ClassTime build/deploy scripts. Sourced, not executed.
#
# The devcontainer ships a JDK that is too new for this project's Gradle/AGP
# (Gradle 8.9 + AGP 8.5.2 support JDK 17-21 and fail outright on JDK 22+), so
# everything here funnels the build through a JDK in that range.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

JDK_MIN="${JDK_MIN:-17}"
JDK_MAX="${JDK_MAX:-21}"
JDK_WANTED="${JDK_WANTED:-17}"
JDK_INSTALL_DIR="${JDK_INSTALL_DIR:-$HOME/.local/jdks}"
ANDROID_HOME_DEFAULT="${ANDROID_HOME_DEFAULT:-$HOME/android-sdk}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-34}"
ANDROID_BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-34.0.0}"

if [ -t 1 ]; then
  C_BLUE=$'\033[1;34m'; C_YELLOW=$'\033[1;33m'; C_RED=$'\033[1;31m'
  C_GREEN=$'\033[1;32m'; C_OFF=$'\033[0m'
else
  C_BLUE=''; C_YELLOW=''; C_RED=''; C_GREEN=''; C_OFF=''
fi

log()  { printf '%s==>%s %s\n' "$C_BLUE" "$C_OFF" "$*"; }
ok()   { printf '%s  ok%s %s\n' "$C_GREEN" "$C_OFF" "$*"; }
warn() { printf '%s [!]%s %s\n' "$C_YELLOW" "$C_OFF" "$*" >&2; }
err()  { printf '%s [x]%s %s\n' "$C_RED" "$C_OFF" "$*" >&2; }
die()  { err "$@"; exit 1; }

# Major version of the JDK at $1, or empty.
jdk_major() {
  local home="$1" out line
  [ -n "$home" ] && [ -x "$home/bin/java" ] || return 1
  # No `| head -1` here: the early pipe close can SIGPIPE java and trip pipefail.
  out="$("$home/bin/java" -version 2>&1)" || return 1
  line="${out%%$'\n'*}"
  printf '%s' "$line" | sed -nE 's/.*version "([0-9]+).*/\1/p'
}

jdk_supported() {
  local major
  major="$(jdk_major "$1" 2>/dev/null)" || return 1
  [ -n "$major" ] || return 1
  [ "$major" -ge "$JDK_MIN" ] 2>/dev/null && [ "$major" -le "$JDK_MAX" ] 2>/dev/null
}

# Prints the path of a JDK in the supported range, or returns 1.
find_supported_jdk() {
  local candidate
  for candidate in "$JDK_INSTALL_DIR"/* "${JAVA_HOME:-}" /usr/lib/jvm/*; do
    [ -n "$candidate" ] && [ -d "$candidate" ] || continue
    if jdk_supported "$candidate"; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  return 1
}

# Prints the Android SDK path, or returns 1.
find_android_home() {
  local candidate sdk_dir
  sdk_dir="$(sed -nE 's/^[[:space:]]*sdk\.dir=(.*)$/\1/p' "$REPO_ROOT/local.properties" 2>/dev/null || true)"
  sdk_dir="${sdk_dir%%$'\n'*}"
  for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$sdk_dir" \
                   "$ANDROID_HOME_DEFAULT" "$HOME/Android/Sdk"; do
    [ -n "$candidate" ] && [ -d "$candidate" ] || continue
    printf '%s' "$candidate"
    return 0
  done
  return 1
}

android_sdk_complete() {
  local sdk="$1"
  [ -x "$sdk/cmdline-tools/latest/bin/sdkmanager" ] &&
    [ -d "$sdk/platforms/$ANDROID_PLATFORM" ] &&
    [ -d "$sdk/build-tools/$ANDROID_BUILD_TOOLS" ] &&
    [ -x "$sdk/platform-tools/adb" ]
}

# Exports JAVA_HOME / ANDROID_HOME / PATH for a Gradle build, or exits with
# instructions. Used by deploy.sh.
prepare_build_env() {
  local jdk sdk
  if ! jdk="$(find_supported_jdk)"; then
    die "No JDK $JDK_MIN-$JDK_MAX found (current JAVA_HOME is $(jdk_major "${JAVA_HOME:-}" 2>/dev/null || echo unknown)).
      Run ./scripts/setup-build-env.sh to install one."
  fi
  if ! sdk="$(find_android_home)"; then
    die "Android SDK not found. Run ./scripts/setup-build-env.sh to install it."
  fi
  if ! android_sdk_complete "$sdk"; then
    die "Android SDK at $sdk is missing $ANDROID_PLATFORM / build-tools $ANDROID_BUILD_TOOLS / platform-tools.
      Run ./scripts/setup-build-env.sh."
  fi

  export JAVA_HOME="$jdk"
  export ANDROID_HOME="$sdk"
  export ANDROID_SDK_ROOT="$sdk"
  export PATH="$JAVA_HOME/bin:$sdk/platform-tools:$sdk/cmdline-tools/latest/bin:$PATH"
}
