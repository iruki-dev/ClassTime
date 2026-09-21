#!/usr/bin/env bash
# Back up (and restore) ClassTime's on-device data over adb.
#
#   ./scripts/phone-backup.sh connect [HOST:PORT]  # pair/connect wireless debugging
#   ./scripts/phone-backup.sh check                # what's installed, and its signature
#   ./scripts/phone-backup.sh backup               # pull db + prefs + the installed APK
#   ./scripts/phone-backup.sh restore <dir>        # push data back after reinstalling
#
# Only the Room database (classtime.db) and preferences live in app-private
# storage and are lost on uninstall. Recordings go to the shared MediaStore
# Music/ folder and survive uninstall untouched.
#
# `backup` and `restore` rely on `run-as`, which the OS allows only for a
# debuggable (debug) build. `check` tells you whether that applies.
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/env.sh"

PKG="dev.iruki.classtime"
BACKUP_ROOT="$REPO_ROOT/backups"

SDK="$(find_android_home)" || die "Android SDK not found. Run ./scripts/setup-build-env.sh."
ADB="$SDK/platform-tools/adb"
[ -x "$ADB" ] || die "adb not found at $ADB"
APKSIGNER="$SDK/build-tools/$ANDROID_BUILD_TOOLS/apksigner"

require_device() {
  local n
  n="$("$ADB" devices | awk 'NR>1 && $2=="device"' | wc -l)"
  if [ "$n" -eq 0 ]; then
    err "No device connected."
    err "This container has no USB access, so use wireless debugging:"
    err "  1. Phone: Settings > Developer options > Wireless debugging > ON"
    err "  2. Tap 'Pair device with pairing code' and note IP:PORT + the code"
    err "  3. ./scripts/phone-backup.sh connect <IP>:<PAIR_PORT>"
    exit 1
  fi
  [ "$n" -eq 1 ] || die "More than one device connected; disconnect the others."
}

app_installed() { "$ADB" shell pm list packages 2>/dev/null | tr -d '\r' | grep -qx "package:$PKG"; }

cmd_connect() {
  local target="${1:-}"
  if [ -z "$target" ]; then
    err "Usage: $0 connect <IP>:<PAIRING_PORT>"
    err "Get both from: Settings > Developer options > Wireless debugging >"
    err "  'Pair device with pairing code'."
    exit 1
  fi
  log "Pairing with $target (you'll be asked for the 6-digit code) ..."
  "$ADB" pair "$target"
  echo
  log "Now connect. The CONNECT port is the one on the Wireless debugging main"
  log "screen - it differs from the pairing port."
  read -r -p "  IP:PORT to connect > " conn
  "$ADB" connect "$conn"
  "$ADB" devices -l
}

cmd_check() {
  require_device
  log "Device:"
  "$ADB" shell getprop ro.product.manufacturer 2>/dev/null | tr -d '\r' | sed 's/^/    vendor:  /'
  "$ADB" shell getprop ro.product.model 2>/dev/null | tr -d '\r' | sed 's/^/    model:   /'
  "$ADB" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' | sed 's/^/    android: /'

  if ! app_installed; then
    warn "$PKG is NOT installed on this device."
    return 0
  fi

  log "Installed app:"
  "$ADB" shell dumpsys package "$PKG" 2>/dev/null | tr -d '\r' \
    | sed -nE 's/^ *(versionCode=[0-9]+).*/    \1/p;s/^ *(versionName=.*)/    \1/p' | head -2

  # Pull the installed APK so its certificate can be compared with our keystores.
  local apk_path tmp
  apk_path="$("$ADB" shell pm path "$PKG" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | head -1)"
  if [ -n "$apk_path" ] && [ -x "$APKSIGNER" ]; then
    tmp="$(mktemp -d)"
    if "$ADB" pull "$apk_path" "$tmp/installed.apk" >/dev/null 2>&1; then
      log "Signature of the APK currently on the phone:"
      "$APKSIGNER" verify --print-certs "$tmp/installed.apk" 2>/dev/null \
        | sed -nE 's/^Signer #1 certificate SHA-256 digest: (.*)$/    installed: \1/p'
      compare_known_keys "$(
        "$APKSIGNER" verify --print-certs "$tmp/installed.apk" 2>/dev/null \
          | sed -nE 's/^Signer #1 certificate SHA-256 digest: (.*)$/\1/p' | head -1)"
    fi
    rm -rf "$tmp"
  fi

  if "$ADB" shell run-as "$PKG" true 2>/dev/null; then
    ok "run-as works - this is a debuggable build, so backup/restore will work."
  else
    warn "run-as refused: the installed build is not debuggable."
    warn "Data cannot be pulled without root. See the notes at the end of DEPLOY.md."
  fi
}

# Compare an installed-APK digest against the keystores we know about.
compare_known_keys() {
  local installed="$1" ks_digest debug_digest
  [ -n "$installed" ] || return 0
  local keytool="${JAVA_HOME:-}/bin/keytool"
  [ -x "$keytool" ] || keytool="$(find_supported_jdk 2>/dev/null)/bin/keytool"
  [ -x "$keytool" ] || return 0

  debug_digest="$("$keytool" -list -v -keystore "$HOME/.android/debug.keystore" \
      -storepass android -alias androiddebugkey 2>/dev/null \
    | sed -nE 's/^[[:space:]]*SHA256:[[:space:]]*(.*)$/\1/p' | head -1 \
    | tr -d ':' | tr 'A-Z' 'a-z')"
  if [ -n "$debug_digest" ] && [ "$debug_digest" = "$installed" ]; then
    ok "MATCHES the old debug keystore (~/.android/debug.keystore)."
    ok "Sign with that key and the update installs in place - no uninstall, no data loss."
    return 0
  fi

  local store pass alias
  store="$(sed -nE 's/^storeFile=(.*)/\1/p' "$REPO_ROOT/keystore.properties" 2>/dev/null | head -1)"
  pass="$(sed -nE 's/^storePassword=(.*)/\1/p' "$REPO_ROOT/keystore.properties" 2>/dev/null | head -1)"
  alias="$(sed -nE 's/^keyAlias=(.*)/\1/p' "$REPO_ROOT/keystore.properties" 2>/dev/null | head -1)"
  if [ -n "$store" ]; then
    case "$store" in /*) : ;; *) store="$REPO_ROOT/$store" ;; esac
    ks_digest="$("$keytool" -list -v -keystore "$store" -storepass "$pass" -alias "$alias" 2>/dev/null \
      | sed -nE 's/^[[:space:]]*SHA256:[[:space:]]*(.*)$/\1/p' | head -1 | tr -d ':' | tr 'A-Z' 'a-z')"
    if [ -n "$ks_digest" ] && [ "$ks_digest" = "$installed" ]; then
      ok "MATCHES the current keystore.properties key - updates already install in place."
      return 0
    fi
  fi
  warn "Does not match the old debug key or the configured key."
  warn "Installing over it would be refused, so back the data up first."
}

cmd_backup() {
  require_device
  app_installed || die "$PKG is not installed on this device."
  "$ADB" shell run-as "$PKG" true 2>/dev/null \
    || die "run-as refused - the installed build is not debuggable, data cannot be pulled."

  local dest="$BACKUP_ROOT/$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$dest"

  log "Stopping the app so the database is quiesced ..."
  "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1 || true

  log "Pulling app-private data ..."
  # -wal/-shm are included: Room runs in WAL mode and recent writes may live there.
  "$ADB" exec-out run-as "$PKG" sh -c \
    "cd /data/data/$PKG && tar cf - databases shared_prefs files 2>/dev/null" > "$dest/appdata.tar"
  [ -s "$dest/appdata.tar" ] || die "Backup came out empty - nothing was written."

  local apk_path
  apk_path="$("$ADB" shell pm path "$PKG" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | head -1)"
  [ -n "$apk_path" ] && "$ADB" pull "$apk_path" "$dest/installed.apk" >/dev/null 2>&1 || true

  ok "Backup written to $dest"
  tar tf "$dest/appdata.tar" 2>/dev/null | sed 's/^/    /' | head -20
  echo
  log "Contents:"
  du -h "$dest"/* 2>/dev/null | sed 's/^/    /'
  echo
  warn "Recordings are NOT in here - they live in the phone's shared Music/ folder"
  warn "and survive uninstall on their own."
}

cmd_restore() {
  local dir="${1:-}"
  [ -n "$dir" ] || die "Usage: $0 restore <backup-dir>"
  [ -f "$dir/appdata.tar" ] || die "No appdata.tar in $dir"
  require_device
  app_installed || die "$PKG is not installed. Install the new build first, then restore."
  "$ADB" shell run-as "$PKG" true 2>/dev/null \
    || die "run-as refused - install a DEBUG build to restore into, then update to release."

  log "Stopping the app ..."
  "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1 || true

  log "Pushing backup to the device ..."
  "$ADB" push "$dir/appdata.tar" /data/local/tmp/classtime-restore.tar >/dev/null

  # Piped through run-as: /data/local/tmp is not readable by the app uid itself.
  log "Restoring into /data/data/$PKG ..."
  "$ADB" shell "cat /data/local/tmp/classtime-restore.tar | run-as $PKG tar xf - -C /data/data/$PKG"
  "$ADB" shell rm -f /data/local/tmp/classtime-restore.tar >/dev/null 2>&1 || true

  ok "Restored. Launch the app and check your timetable."
}

case "${1:-}" in
  connect) shift; cmd_connect "${1:-}" ;;
  check)   cmd_check ;;
  backup)  cmd_backup ;;
  restore) shift; cmd_restore "${1:-}" ;;
  -h|--help|"") awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next } NR>1 { exit }' "$0" ;;
  *) die "Unknown command: $1  (try --help)" ;;
esac
