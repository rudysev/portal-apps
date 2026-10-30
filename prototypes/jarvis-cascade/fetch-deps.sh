#!/usr/bin/env bash
# Downloads the pinned third-party artifacts the build needs, verifies each against its SHA-256 and puts it where the
# build reads it. None of them is committed (see .gitignore). Re-running is cheap: a file already in place with the
# right hash is kept.
#
#   ./fetch-deps.sh
#
# What it fetches (all from GitHub releases):
#   - openWakeWord v0.5.1 models (stage 1): melspectrogram, embedding_model, hey_jarvis_v0.1 (ONNX)
#       -> app/src/main/assets/oww/
#   - sherpa-onnx KWS model sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01 (stage 2): the int8
#     encoder/decoder/joiner and tokens.txt -> app/src/main/assets/kws/
#   - sherpa-onnx 2.0.1 Android AAR with ONNX Runtime linked statically into its JNI library, so it doesn't clash
#     with the onnxruntime-android libonnxruntime.so stage 1 uses -> app/libs/
#   - sherpa-onnx 2.0.1 JVM jar and this host's native-lib jar, for the :cascade unit tests -> cascade/libs/
set -euo pipefail

cd "$(dirname "$0")"

SHERPA_VERSION="2.0.1"
SHERPA_RELEASE="https://github.com/k2-fsa/sherpa-onnx/releases/download/v$SHERPA_VERSION"
OWW_RELEASE="https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
KWS_MODEL="sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
KWS_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/$KWS_MODEL.tar.bz2"
KWS_SHA256="f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a"
# "<file in the tarball> <its SHA-256>": the files the app needs, each checked after extraction too.
KWS_FILES=(
  "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx 1e721676515bcd42a186979733981213c66c80db680e1cc582dfedf3be76e678"
  "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx e40ff43297abe815e8898494c17e71bba2152d9d40fa3eb803f75d0f7533329a"
  "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx eae9da0c7e1e6c6a3f4cc42d167899c388f6c6701b94cb96320e4f55df79624c"
  "tokens.txt fd2ded4050a55d2b1578870ba8697d02371980217806b7558bd0a5cc60f3ba53"
)

sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

# fetch <url> <sha256> <destination>: downloads to a temp file, checks the hash, then moves it into place.
fetch() {
  local url=$1 want=$2 dest=$3 got
  if [[ -f "$dest" && "$(sha256 "$dest")" == "$want" ]]; then
    echo "ok       $dest"
    return
  fi
  mkdir -p "$(dirname "$dest")"
  echo "fetching $url"
  curl -fsSL --retry 3 -o "$dest.part" "$url"
  got=$(sha256 "$dest.part")
  if [[ "$got" != "$want" ]]; then
    rm -f "$dest.part"
    echo "SHA-256 mismatch for $url: expected $want, got $got" >&2
    exit 1
  fi
  mv "$dest.part" "$dest"
  echo "ok       $dest"
}

# The sherpa-onnx native-lib jar for the machine running the :cascade tests.
host_native() {
  case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) echo "osx-aarch64 718a4ea4799341146ef9a0cc15daa70e6622ae4a16911952604035f396703499" ;;
    Darwin-x86_64) echo "osx-x64 617a6263b4793495ea6a5f882cfc5dbf9e57cf4ad1e126104bddf8cf90846f72" ;;
    Linux-x86_64) echo "linux-x64 744330a68a7024d9412f029c23b06f847b8444787c94e5118e0fdaf1b4b2cb3f" ;;
    Linux-aarch64) echo "linux-aarch64 c09d51e7190f5b8bcc20cf0d25bcb2e66324737ace5ce9d678ae37b8c79d6c2f" ;;
    *)
      echo "No sherpa-onnx native-lib jar is pinned for $(uname -s)-$(uname -m)" >&2
      exit 1
      ;;
  esac
}

fetch "$OWW_RELEASE/melspectrogram.onnx" \
  ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f app/src/main/assets/oww/melspectrogram.onnx
fetch "$OWW_RELEASE/embedding_model.onnx" \
  70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f app/src/main/assets/oww/embedding_model.onnx
fetch "$OWW_RELEASE/hey_jarvis_v0.1.onnx" \
  94a13cfe60075b132f6a472e7e462e8123ee70861bc3fb58434a73712ee0d2cb app/src/main/assets/oww/hey_jarvis_v0.1.onnx

fetch "$SHERPA_RELEASE/sherpa-onnx-static-link-onnxruntime-$SHERPA_VERSION.aar" \
  07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575 \
  "app/libs/sherpa-onnx-static-link-onnxruntime-$SHERPA_VERSION.aar"
fetch "$SHERPA_RELEASE/sherpa-onnx-jvm-$SHERPA_VERSION.jar" \
  8320e2457b6e38dd624fb619d3552e11ef78ffeb394622056126b16f2fd99d12 "cascade/libs/sherpa-onnx-jvm-$SHERPA_VERSION.jar"
read -r platform platform_sha <<<"$(host_native)"
fetch "$SHERPA_RELEASE/sherpa-onnx-native-lib-$platform-$SHERPA_VERSION.jar" "$platform_sha" \
  "cascade/libs/sherpa-onnx-native-lib-$SHERPA_VERSION.jar"

kws_stale=0
for entry in "${KWS_FILES[@]}"; do
  read -r f want <<<"$entry"
  if [[ ! -f "app/src/main/assets/kws/$f" || "$(sha256 "app/src/main/assets/kws/$f")" != "$want" ]]; then
    kws_stale=1
  fi
done
if [[ $kws_stale == 1 ]]; then
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  fetch "$KWS_URL" "$KWS_SHA256" "$tmp/kws.tar.bz2"
  mkdir -p app/src/main/assets/kws
  for entry in "${KWS_FILES[@]}"; do
    read -r f want <<<"$entry"
    tar -xjf "$tmp/kws.tar.bz2" -C "$tmp" "$KWS_MODEL/$f"
    if [[ "$(sha256 "$tmp/$KWS_MODEL/$f")" != "$want" ]]; then
      echo "SHA-256 mismatch for $KWS_MODEL/$f" >&2
      exit 1
    fi
    mv "$tmp/$KWS_MODEL/$f" "app/src/main/assets/kws/$f"
  done
fi
echo "ok       app/src/main/assets/kws/"
