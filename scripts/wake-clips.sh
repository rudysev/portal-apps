#!/usr/bin/env bash
# Pull, list, play and label the wake clips portal-wake records on a debug build (WakeClipRecorder): the 5 s of
# audio behind every wake and near-miss. A false wake's decode can't be told from a real one; its audio can.
#
#   scripts/wake-clips.sh pull                    move new clips off the Portal into the inbox, each with its decode
#   scripts/wake-clips.sh list [N]                the newest N inbox clips (default 10): time, clip, decode
#   scripts/wake-clips.sh play <clip|latest>      play one on this Mac
#   scripts/wake-clips.sh label <true|false> <clip|latest>
#                                                 file it in the corpus as a real (true) or false wake
#
# <clip> is a file name from `list` (with or without .wav); `latest` is the newest wake_ clip in the inbox.
#
# The corpus is household audio, so it lives in .corpus/wake/ (gitignored: these repos are public):
#   inbox/          pulled, not yet labelled          true/   real wakes, and near-misses that were real wakes
#   false/          false wakes and near-misses that weren't the phrase
# Each clip has a .txt sidecar holding its debug.txt line (the decode), for regression replay.
#
# ADB defaults to `adb` when it's on PATH, else hzdb's (as setup.sh uses).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORPUS="${WAKE_CORPUS:-$ROOT/.corpus/wake}"
DEVICE_FILES="/sdcard/Android/data/com.portal.wake/files"
DEVICE_CLIPS="$DEVICE_FILES/wake-clips"
if [[ -z "${ADB:-}" ]]; then
  if command -v adb >/dev/null; then ADB="adb"; else ADB="npx -y @meta-quest/hzdb adb"; fi
fi
# A clip's wall-clock stamp is a few ms from its debug.txt line, before or after it (a wake's clip is saved before
# its line is logged, a near-miss's after); allow this much slack either way when joining them.
JOIN_SLACK_MS=2000

die() {
  echo "wake-clips: $*" >&2
  exit 1
}

adb_() {
  # shellcheck disable=SC2086 # ADB may be a multi-word command (the hzdb fallback)
  $ADB "$@"
}

# Epoch milliseconds in a clip name (wake_jarvis_1793330671218.wav → 1793330671218).
clip_ms() {
  local base="${1%.wav}"
  echo "${base##*_}"
}

local_time() {
  date -r "$(($1 / 1000))" "+%a %H:%M:%S"
}

# The debug.txt line for a clip: the matching decision logged closest to it, within JOIN_SLACK_MS either way.
decode_line() {
  local clip="$1" log="$2" ms pattern
  ms="$(clip_ms "$clip")"
  case "$clip" in
    wake_*) pattern="wake detected" ;;
    near_*) pattern="near-miss" ;;
    *) return 0 ;;
  esac
  awk -v ms="$ms" -v slack="$JOIN_SLACK_MS" -v pat="$pattern" \
    'index($0, pat) { d = $1 - ms; if (d < 0) d = -d; if (d <= slack + 0 && (!line || d < best)) { best = d; line = $0 } }
     END { if (line) print line }' "$log"
}

cmd_pull() {
  mkdir -p "$CORPUS/inbox"
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  local names
  names="$(adb_ shell "ls $DEVICE_CLIPS 2>/dev/null" | tr -d '\r' | grep '\.wav$' || true)"
  if [[ -z "$names" ]]; then
    echo "no new clips on the device"
    return 0
  fi
  adb_ shell "cat $DEVICE_FILES/debug.txt.1 $DEVICE_FILES/debug.txt 2>/dev/null" | tr -d '\r' >"$tmp/debug.txt"
  local n=0 pulled=()
  while IFS= read -r name; do
    if adb_ pull "$DEVICE_CLIPS/$name" "$tmp/$name" >/dev/null 2>&1; then
      decode_line "$name" "$tmp/debug.txt" >"$tmp/${name%.wav}.txt"
      mv "$tmp/$name" "$tmp/${name%.wav}.txt" "$CORPUS/inbox/"
      pulled+=("$name")
      n=$((n + 1))
    else
      echo "wake-clips: could not pull $name (left on the device)" >&2
    fi
  done <<<"$names"
  # Remove only what landed in the inbox, so the recorder's per-kind cap frees up.
  local i
  for ((i = 0; i < ${#pulled[@]}; i += 50)); do
    adb_ shell "cd $DEVICE_CLIPS && rm -f ${pulled[*]:i:50}"
  done
  echo "pulled $n clip(s) into $CORPUS/inbox"
}

cmd_list() {
  local count="${1:-10}" f name
  [[ -d "$CORPUS/inbox" ]] || die "no inbox yet: run pull"
  # Newest first by the stamp in the name, not the file time (pull order isn't capture order).
  find "$CORPUS/inbox" -name '*.wav' -exec basename {} \; |
    awk -F_ '{ s = $NF; sub(/\.wav$/, "", s); print s "\t" $0 }' | sort -rn | head -n "$count" | cut -f2 |
    while IFS= read -r name; do
      f="$CORPUS/inbox/${name%.wav}.txt"
      printf '%s  %-34s %s\n' "$(local_time "$(clip_ms "$name")")" "${name%.wav}" \
        "$(cut -d' ' -f2- "$f" 2>/dev/null)"
    done
}

# The inbox path for a <clip|latest> argument.
resolve() {
  local arg="$1" name
  if [[ "$arg" == latest ]]; then
    name="$(find "$CORPUS/inbox" -name 'wake_*.wav' -exec basename {} \; 2>/dev/null |
      awk -F_ '{ s = $NF; sub(/\.wav$/, "", s); print s "\t" $0 }' | sort -rn | head -n1 | cut -f2)"
    [[ -n "$name" ]] || die "no wake clip in the inbox: run pull"
  else
    name="${arg%.wav}.wav"
  fi
  [[ -f "$CORPUS/inbox/$name" ]] || die "$name is not in the inbox"
  echo "$CORPUS/inbox/$name"
}

cmd_play() {
  [[ $# -eq 1 ]] || die "usage: play <clip|latest>"
  local path
  path="$(resolve "$1")"
  echo "$(basename "$path"): $(cut -d' ' -f2- "${path%.wav}.txt" 2>/dev/null)"
  afplay "$path"
}

cmd_label() {
  [[ $# -eq 2 ]] || die "usage: label <true|false> <clip|latest>"
  local label="$1" path
  [[ "$label" == true || "$label" == false ]] || die "label is true or false, not '$label'"
  path="$(resolve "$2")"
  mkdir -p "$CORPUS/$label"
  mv "$path" "$CORPUS/$label/"
  [[ -f "${path%.wav}.txt" ]] && mv "${path%.wav}.txt" "$CORPUS/$label/"
  echo "$(basename "$path") → $label/"
}

case "${1:-}" in
  pull) cmd_pull ;;
  list) cmd_list "${@:2}" ;;
  play) cmd_play "${@:2}" ;;
  label) cmd_label "${@:2}" ;;
  *) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//' && exit 2 ;;
esac
