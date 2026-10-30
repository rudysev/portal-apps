"""Writes the expected stage-1 values for cascade's OpenWakeWordParityTest, from openWakeWord itself.

The Kotlin port and this script build the same deterministic integer signal (an LCG plus square waves, no floating
point, so both sides get bit-identical input), seed openWakeWord's feature buffer from the same deterministic warm-up
noise, then stream 80 ms frames. Records every frame's score and the newest 96-dim feature after a few frames.

Usage (models from ../fetch-deps.sh):
  python3 -m venv /tmp/oww && /tmp/oww/bin/pip install openwakeword==0.6.0 onnxruntime==1.19.2
  /tmp/oww/bin/python -I tools/oww_parity_reference.py app/src/main/assets/oww \
      > cascade/src/test/resources/oww_parity.txt
"""
import sys

import numpy as np
import openwakeword

FRAME = 1280
FRAMES = 48
FEATURE_FRAMES = (10, 30, 48)


def lcg(seed, n):
    s = seed
    out = []
    for _ in range(n):
        s = (s * 1103515245 + 12345) % (1 << 31)
        out.append((s >> 16) % 2001 - 1000)
    return out


def signal(n):
    noise = lcg(1, n)
    out = []
    for i in range(n):
        if (i // 8000) % 2 == 0:
            tone = 3000 if (i // 40) % 2 == 0 else -3000
        else:
            tone = 1500 if (i // 16) % 2 == 0 else -1500
        out.append(noise[i] + tone)
    return np.array(out, dtype=np.int16)


def main():
    d = sys.argv[1]
    m = openwakeword.Model(wakeword_models=[f"{d}/hey_jarvis_v0.1.onnx"], inference_framework="onnx",
                           melspec_model_path=f"{d}/melspectrogram.onnx",
                           embedding_model_path=f"{d}/embedding_model.onnx")
    key = list(m.models.keys())[0]
    m.reset()
    m.preprocessor.feature_buffer = m.preprocessor._get_embeddings(np.array(lcg(7, 64000), dtype=np.int16))
    x = signal(FRAMES * FRAME)
    print("# openwakeword 0.6.0, onnxruntime 1.19.2; tools/oww_parity_reference.py")
    for f in range(FRAMES):
        score = m.predict(x[f * FRAME:(f + 1) * FRAME])[key]
        print(f"score {f + 1} {float(score):.9g}")
        if f + 1 in FEATURE_FRAMES:
            vec = m.preprocessor.feature_buffer[-1]
            print(f"feature {f + 1} " + " ".join(f"{float(v):.9g}" for v in vec))


if __name__ == "__main__":
    main()
