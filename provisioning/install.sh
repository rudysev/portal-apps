#!/usr/bin/env bash
#
# portal-apps one-click installer for the Meta Portal (macOS / Linux).
#
# Installs BOTH Jarvis (portal-assistant) and the "hey jarvis" wake listener (portal-wake) in one go,
# by delegating to each app's own installer — so you always get the current release of each. Finds (or
# downloads) Android's adb once, waits for a connected Portal, then runs the two installers in order
# (Jarvis first — it asks for your free Gemini key — then wake). No Android SDK, no build tools, no
# Node — just this script and a USB-C cable.
#
# Usage:
#   ./install.sh             install both apps on the connected Portal (downloads the latest releases)
#   ./install.sh --uninstall remove both apps (and restore Meta's "Hey Alexa" detector)
#   ./install.sh --key [KEY]  (re)enter the Gemini API key on an already-installed Jarvis
#   ./install.sh --status    show whether each app is installed (and whether the key is set)

set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"   # the portal-apps superproject root

ASSISTANT_DIR="$ROOT/portal-assistant/provisioning"
WAKE_DIR="$ROOT/portal-wake/provisioning"

# Source repos + branch used to bootstrap the per-app installers when this is run from a plain
# (non-git) download — e.g. a ZIP, where the submodule folders arrive empty. Only the small
# provisioning scripts are fetched; each app still downloads its own APK from its GitHub release.
ASSISTANT_REPO="rudysev/portal-assistant"
WAKE_REPO="rudysev/portal-wake"
BOOTSTRAP_REF="main"

# ----- pretty output ---------------------------------------------------------
if [ -t 1 ]; then B=$'\033[1m'; G=$'\033[32m'; Y=$'\033[33m'; R=$'\033[31m'; D=$'\033[2m'; N=$'\033[0m'; else B=; G=; Y=; R=; D=; N=; fi
step() { printf "%s==>%s %s\n" "$B" "$N" "$1"; }
ok()   { printf "  %s✓%s %s\n" "$G" "$N" "$1"; }
warn() { printf "  %s!%s %s\n" "$Y" "$N" "$1"; }
die()  { printf "%sERROR:%s %s\n" "$R" "$N" "$1" >&2; exit 1; }

# ----- ensure the per-app installers are present -----------------------------
# This script delegates to each app's installer (which lives in its submodule). A plain `git clone`
# without --recursive — or a downloaded ZIP — leaves those empty. We try, in order: use what's already
# here, `git submodule update` (when this is a git checkout), then a direct download from GitHub (so a
# ZIP + double-click works with no git at all).
# Fetch one file to a temp path and validate it: non-empty AND contains an expected marker. The marker
# guards against a 200-but-wrong body (captive-portal HTML, an empty/truncated transfer) — curl -fsSL
# only fails on HTTP >=400 / a dropped connection, not on a "successful" wrong payload.
fetch_verify() { # fetch_verify <url> <tmp-dest> <marker>
  curl -fsSL "$1" -o "$2" || return 1
  [ -s "$2" ] || return 1
  grep -q "$3" "$2" || return 1
}

# Download just the small provisioning scripts each child installer needs (it fetches its own APK from
# its release). install.sh is run via `bash` and config.env/keyprov.sh are sourced, so no +x is needed.
# All-or-nothing: stage every file to a temp dir and validate it; only move them into place once ALL
# succeed, so a partial/failed download never leaves a corrupt tree that a later run mistakes for good.
bootstrap_from_github() {
  step "Downloading the app installers from GitHub (no git checkout detected)"
  local a="https://raw.githubusercontent.com/$ASSISTANT_REPO/$BOOTSTRAP_REF/provisioning"
  local w="https://raw.githubusercontent.com/$WAKE_REPO/$BOOTSTRAP_REF/provisioning"
  local tmp; tmp="$(mktemp -d)" || return 1
  if fetch_verify "$a/install.sh" "$tmp/a_install.sh" "resolve_adb"  &&
     fetch_verify "$a/config.env" "$tmp/a_config.env" "RELEASE_REPO" &&
     fetch_verify "$a/keyprov.sh" "$tmp/a_keyprov.sh" "provision_key" &&
     fetch_verify "$w/install.sh" "$tmp/w_install.sh" "resolve_adb"  &&
     fetch_verify "$w/config.env" "$tmp/w_config.env" "RELEASE_REPO" &&
     mkdir -p "$ASSISTANT_DIR" "$WAKE_DIR"; then
    mv "$tmp/a_install.sh" "$ASSISTANT_DIR/install.sh" && mv "$tmp/a_config.env" "$ASSISTANT_DIR/config.env" &&
    mv "$tmp/a_keyprov.sh" "$ASSISTANT_DIR/keyprov.sh" && mv "$tmp/w_install.sh" "$WAKE_DIR/install.sh" &&
    mv "$tmp/w_config.env" "$WAKE_DIR/config.env" || { rm -rf "$tmp"; return 1; }
    rm -rf "$tmp"; ok "App installers downloaded"; return 0
  fi
  rm -rf "$tmp"; return 1
}

ensure_submodules() {
  # Test for presence with -f, not -x: the children are invoked via `bash "$f"` (which ignores the
  # exec bit), so a checkout that lost +x (FAT/exFAT, a tarball, core.fileMode=false) still runs fine.
  # Reuse present scripts only in a git checkout (git owns their freshness, and we must not overwrite a
  # tracked submodule tree). A plain ZIP re-run falls through and re-fetches below, so a fix on main
  # reaches a kept-folder re-run instead of being shadowed by the first run's stale copy.
  if [ -f "$ASSISTANT_DIR/install.sh" ] && [ -f "$WAKE_DIR/install.sh" ] && git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then return; fi
  if git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    # A git checkout: the apps are submodules — pull them. Do NOT fall back to the network bootstrap
    # here: that would write unpinned main-branch content into the tracked submodule trees and dirty
    # the repo. A git user should fix their clone instead.
    step "Fetching the app submodules (portal-assistant + portal-wake)"
    git -C "$ROOT" submodule update --init --recursive \
      || die "git submodule update failed. Fix your network/credentials and re-run, or re-clone with: git clone --recursive https://github.com/rudysev/portal-apps.git"
  else
    # Plain download (ZIP), no git: download the installers directly.
    command -v curl >/dev/null 2>&1 || die "Can't download the app installers (curl not found). Get the full repo with: git clone --recursive https://github.com/rudysev/portal-apps.git"
    bootstrap_from_github || die "Couldn't download the app installers. Check your internet connection and re-run, or get the full repo with: git clone --recursive https://github.com/rudysev/portal-apps.git"
  fi
  [ -f "$ASSISTANT_DIR/install.sh" ] || die "portal-assistant installer missing after download."
  [ -f "$WAKE_DIR/install.sh" ]      || die "portal-wake installer missing after download."
}

# ----- resolve adb (bundled -> PATH -> download) -----------------------------
# Resolved once here and shared with the child installers (we prepend its dir to PATH and export
# ANDROID_SERIAL), so platform-tools is downloaded at most once and the device is picked once.
resolve_adb() {
  if [ -x "$SCRIPT_DIR/platform-tools/adb" ]; then ADB="$SCRIPT_DIR/platform-tools/adb"; return; fi
  if command -v adb >/dev/null 2>&1; then ADB="$(command -v adb)"; return; fi
  step "Android platform-tools (adb) not found — downloading the official package from Google"
  local os zip url
  case "$(uname -s)" in
    Darwin) os=darwin ;;
    Linux)  os=linux ;;
    *) die "Unsupported OS for auto-download. Install Android platform-tools and re-run." ;;
  esac
  url="https://dl.google.com/android/repository/platform-tools-latest-${os}.zip"
  zip="$SCRIPT_DIR/platform-tools.zip"
  curl -fL "$url" -o "$zip" || die "Download failed. Check your internet connection."
  unzip -oq "$zip" -d "$SCRIPT_DIR" || die "Could not unzip platform-tools."
  rm -f "$zip"
  [ -x "$SCRIPT_DIR/platform-tools/adb" ] || die "adb missing after download."
  ADB="$SCRIPT_DIR/platform-tools/adb"
  ok "platform-tools installed locally"
}
a() { "$ADB" "$@"; }

# Put the resolved adb first on PATH so the delegated child scripts reuse it instead of
# downloading their own copy.
share_adb() { PATH="$(dirname "$ADB"):$PATH"; export PATH; }

# ----- wait for an authorized device -----------------------------------------
wait_for_device() {
  step "Looking for your Portal"
  a start-server >/dev/null 2>&1
  local printed_plug=0 printed_auth=0
  while true; do
    local raw devs n state line
    raw="$(a devices)"   # query adb once per poll, then parse it for both the serial list and the state line
    devs="$(printf "%s\n" "$raw" | awk 'NR>1 && $2=="device"{print $1}')"
    n="$(printf "%s" "$devs" | grep -c . || true)"
    if [ "$n" -gt 1 ] && [ -z "${ANDROID_SERIAL:-}" ]; then
      die "More than one device is connected. Unplug the others (or set ANDROID_SERIAL=<serial>) and re-run."
    elif [ "$n" = 1 ]; then
      ANDROID_SERIAL="$devs"; export ANDROID_SERIAL; state="device"
    else
      line="$(printf "%s\n" "$raw" | awk 'NR>1 && NF{print; exit}')"
      state="$(printf "%s" "$line" | awk '{print $2}')"
    fi
    case "$state" in
      device)
        local model; model="$(a shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
        ok "Connected: ${model:-device}"
        return ;;
      unauthorized)
        if [ "$printed_auth" = 0 ]; then
          printf "  %sOn the Portal screen, tap %sAllow%s (check \"Always allow from this computer\").%s\n" "$Y" "$B" "$N$Y" "$N"
          printed_auth=1
        fi ;;
      *)
        if [ "$printed_plug" = 0 ]; then
          printf "  %sPlug the Portal into this computer with a USB-C cable.%s\n" "$Y" "$N"
          printf "  %sOn the Portal: Settings > Debug > ADB Enabled.%s\n" "$D" "$N"
          printed_plug=1
        fi ;;
    esac
    sleep 2
  done
}

# ----- top-level actions -----------------------------------------------------
do_install() {
  printf "%sportal-apps installer%s\n" "$B" "$N"
  printf "%sInstalls Jarvis (the assistant) + the \"hey jarvis\" wake listener on your Portal.%s\n\n" "$D" "$N"
  ensure_submodules
  resolve_adb
  share_adb
  wait_for_device

  step "Installing Jarvis (portal-assistant)"
  printf "\n"
  bash "$ASSISTANT_DIR/install.sh" || die "Jarvis install failed."

  # portal-wake adds nothing on gen2 (Android 10+): the OS silences its background mic, so it can't detect
  # — and holding the mic there would starve Jarvis's in-app "hey jarvis" detector. So install it only on
  # gen1 (Android 9); on gen2 Jarvis handles wake in-app while on screen. Detection failure falls through to
  # installing it (conservative: preserves gen1 behavior, and portal-wake self-guards inert on A10 anyway).
  local sdk; sdk="$(a shell getprop ro.build.version.sdk 2>/dev/null | tr -dc '0-9')"
  if [ "${sdk:-0}" -ge 29 ]; then
    printf "\n"
    step "Skipping the wake listener (portal-wake)"
    ok "This Portal (Android 10+, gen2): Jarvis detects \"hey jarvis\" in-app while on screen — portal-wake isn't needed here."
    printf "  %sIf portal-wake is already installed, remove it with: adb uninstall com.portal.wake%s\n" "$D" "$N"
  else
    printf "\n"
    step "Installing the wake listener (portal-wake)"
    printf "\n"
    bash "$WAKE_DIR/install.sh" || die "portal-wake install failed."
  fi

  if [ "${sdk:-0}" -ge 29 ]; then
    printf "\n%s✓ Done. Open Jarvis and say \"hey jarvis\" while it's on screen.%s\n" "$G$B" "$N"
  else
    printf "\n%s✓ Done. Say \"hey jarvis\" near the Portal.%s\n" "$G$B" "$N"
  fi
  printf "%sTo remove them: re-run with --uninstall (or double-click Uninstall-PortalApps).%s\n" "$D" "$N"
}

do_uninstall() {
  printf "%sportal-apps uninstaller%s\n" "$B" "$N"
  printf "%sRemoves both apps and restores Meta's \"Hey Alexa\" to its original state.%s\n\n" "$D" "$N"
  # Self-contained on purpose: uninstall must work with no network and no child scripts present (a user
  # may just want the apps gone). So remove the packages directly and restore "Hey Alexa" inline rather
  # than delegating to the per-app installers.
  resolve_adb
  wait_for_device
  for pkg in com.portal.assistant com.portal.wake; do
    step "Removing $pkg"
    a shell am force-stop "$pkg" >/dev/null 2>&1
    a uninstall "$pkg" >/dev/null 2>&1 && ok "Uninstalled $pkg" || warn "$pkg was not installed."
  done
  # Re-enable Meta's "Hey Alexa" detector if portal-wake disabled it. Detect the disabled state with -d
  # (a disable-user package can be hidden from the default `pm list packages` on some Android builds).
  if a shell pm list packages -d com.millennium 2>/dev/null | tr -d '\r' | grep -q "package:com.millennium"; then
    step "Re-enabling Meta's \"Hey Alexa\" detector"
    a shell pm enable com.millennium >/dev/null 2>&1 && ok "Re-enabled com.millennium" || warn "Couldn't re-enable com.millennium — re-enable it from the Portal's app settings."
  fi
  printf "\n%s✓ Done. Both apps removed; Meta's \"Hey Alexa\" restored to its original state.%s\n" "$G$B" "$N"
}

do_status() {
  ensure_submodules
  resolve_adb
  share_adb
  wait_for_device
  printf "\n%sJarvis (portal-assistant)%s\n" "$B" "$N"
  bash "$ASSISTANT_DIR/install.sh" --status
  printf "\n%sWake listener (portal-wake)%s\n" "$B" "$N"
  bash "$WAKE_DIR/install.sh" --status
}

do_key() {
  ensure_submodules
  resolve_adb
  share_adb
  # Delegate to Jarvis's own --key flow (it waits for the device + drives the key prompt itself).
  bash "$ASSISTANT_DIR/install.sh" --key "$@"
}

case "${1:-}" in
  --uninstall|-u)           do_uninstall ;;
  --status|-s)              do_status ;;
  --key|-k)                 shift; do_key "$@" ;;
  --help|-h)                sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//' ;;
  "")                       do_install ;;
  *)                        die "Unknown option: $1 (use --uninstall, --key, --status, or no argument)" ;;
esac
