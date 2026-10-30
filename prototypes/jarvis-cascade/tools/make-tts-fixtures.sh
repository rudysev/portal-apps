#!/usr/bin/env bash
# Writes local TTS test clips (macOS `say`, 16 kHz mono 16-bit) to fixtures/ for ModelChainTest's positive-speech
# test, which skips when fixtures/ is empty. The clips stay local (gitignored): TTS output isn't ours to publish.
#
#   tools/make-tts-fixtures.sh            # then ./gradlew :cascade:test
#
# positive_* must trigger the chain; negative_* must not. Negatives are the sound-alikes stage 2 exists to reject
# (report §4.3). "Okay Jarvis" is left out: sherpa-onnx accepts it 22-39 % of the time off-device, so it's a known
# false accept, not a pass/fail case.
set -euo pipefail

cd "$(dirname "$0")/.."

if ! command -v say >/dev/null 2>&1; then
  echo "This needs macOS 'say'." >&2
  exit 1
fi

mkdir -p fixtures
clip() {
  local name=$1 voice=$2 text=$3
  say -v "$voice" -o "fixtures/${name}_$voice.wav" --data-format=LEI16@16000 "$text"
  echo "fixtures/${name}_$voice.wav"
}

for voice in Samantha Daniel; do
  clip positive_hey_jarvis "$voice" "Hey Jarvis"
  clip positive_hey_jarvis_command "$voice" "Hey Jarvis, what's the weather like today?"
  clip negative_jarvis "$voice" "Jarvis"
  clip negative_hey_travis "$voice" "Hey Travis"
  clip negative_neutral "$voice" "The weather is nice today"
done
