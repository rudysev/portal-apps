# Jarvis Cascade: a test app for the two-check "Hey Jarvis" chain

A standalone, sideloadable **test app** (`com.portal.jarviscascade`) that runs the two-check chain recommended by the
clean-room wake-word research on a Meta Portal, and logs every candidate so false accepts and false rejects can be
counted on the device. It is a prototype. It doesn't touch the production wake path (`portal-wake`, `portal-commons`),
and nothing in the workspace depends on it.

## The chain

```
AudioRecord (16 kHz mono, selectable source) in a foreground service, 80 ms frames
  └─ 2.5 s ring buffer
       ├─ Stage 1, every frame: openWakeWord hey_jarvis_v0.1 (ONNX Runtime), score ≥ threshold (default 0.3)
       │     opens a candidate; one candidate per 2 s
       └─ Stage 2, candidates only: sherpa-onnx keyword spotter (3.3M Zipformer, int8) on the last 2 s,
             ending 400 ms after the candidate opened, followed by 0.6 s of silence.
             Confirms the token sequence ▁HE Y ▁JA R VI S (threshold 0.15, boost 1.5 by default)
                  └─ confirmed = TRIGGER (beep + log line)
```

The defaults are the off-device cascade with the best recall: openWakeWord at 0.3 AND sherpa-onnx at (0.15, 1.5).
Off-device it kept 86 % quiet-room recall, cut sound-alike triggers to about 2-4 %, nearly eliminated bare "Jarvis"
triggers, and had 0 false accepts in 10.75 h of LibriSpeech.

Two details that matter, both found while building it and checked off-device with sherpa-onnx 2.0.1:

- **Stage 2 needs trailing silence.** The streaming spotter only flushes its last chunks when silence follows. Fed
  the bare 2 s window, it missed every TTS positive. With 0.6 s of padding it confirmed all of them.
- **Stage-2 thresholds are fixed when the spotter is built.** A per-stream `#threshold` only adds a second keyword
  next to the keywords file's, so a stage-2 settings change rebuilds the spotter on the next candidate.

## Layout

| Path | What |
|---|---|
| `cascade/` | Pure JVM: the chain (`CascadeChain`), the stage-1 port (`OpenWakeWord`), stage 2 (`SequenceVerifier`), the log line format (`LogLines`), capture stats, WAV I/O. Unit-tested on the JVM against the real models. |
| `app/` | The Android app: `CascadeService` (foreground service), `CaptureSession` (AudioRecord + chain), `AndroidSpotter` (sherpa-onnx Android binding), `EventLog`, `MainActivity`. |
| `fetch-deps.sh` | Downloads the models and the sherpa-onnx binaries, all pinned by SHA-256. |
| `tools/oww_parity_reference.py` | Regenerates `cascade/src/test/resources/oww_parity.txt` from openWakeWord itself. |
| `tools/make-tts-fixtures.sh` | Local-only TTS clips (macOS `say`) for the positive-speech test. |

## Build

JDK 17 or 21 and an Android SDK (`ANDROID_HOME` or `local.properties`). No NDK.

```bash
./fetch-deps.sh          # models + sherpa-onnx AAR/JVM jars, SHA-256 checked, gitignored
./gradlew check          # unit tests (incl. the real models), Android Lint, detekt, ktlint
./gradlew assembleDebug  # app/build/outputs/apk/debug/app-debug.apk, arm64-v8a + armeabi-v7a, ~85 MB
```

Nothing third-party is committed. `fetch-deps.sh` fetches these, each pinned by SHA-256:

| Artifact | Source | License |
|---|---|---|
| openWakeWord `melspectrogram.onnx`, `embedding_model.onnx`, `hey_jarvis_v0.1.onnx` | [openWakeWord v0.5.1 release](https://github.com/dscripka/openWakeWord/releases/tag/v0.5.1) | code Apache-2.0; pre-trained weights CC BY-NC-SA 4.0 (non-commercial). This test app is non-commercial. |
| `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` (int8 encoder/decoder/joiner, `tokens.txt`) | [sherpa-onnx kws-models release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/kws-models) | Apache-2.0 |
| `sherpa-onnx-static-link-onnxruntime-2.0.1.aar` | [sherpa-onnx v2.0.1](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1) | Apache-2.0 |
| `sherpa-onnx-jvm-2.0.1.jar` + this host's native-lib jar (tests only) | same release | Apache-2.0 |

The app uses the sherpa-onnx AAR that links ONNX Runtime statically, so its JNI library doesn't ship a second
`libonnxruntime.so` next to `onnxruntime-android`'s (stage 1, from Maven Central, 1.19.2, the version the off-device
evaluation used). Neither needs Google Play services.

## Install and run on a Portal

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.portal.jarviscascade android.permission.RECORD_AUDIO
```

Open **Jarvis Cascade Test**, choose an audio source, then tap **Start listening**.

- **Free the mic first.** On Android 9, capture is first-come-first-served. Stop `portal-wake` while testing
  (`adb shell am force-stop com.portal.wake`; its boot receiver restarts it after a reboot). On some Portals the
  native "Hey Alexa" detector `com.millennium` also holds the mic (see `portal-wake/setup.sh`).
- **Gen 1 (Android 9)** keeps listening in the background: the capture runs in a foreground service.
- **Gen 2 (Android 10)** only lets the app hear while it is on screen. The screen stays awake. Silencing changes are
  logged (`silenced` lines), and a silenced span is deaf, not quiet.
- **Audio source** (`VOICE_RECOGNITION` by default, as portal-wake uses; also `MIC`, `VOICE_COMMUNICATION`,
  `UNPROCESSED`, `CAMCORDER`, `DEFAULT`): changing it restarts capture. Every log line names the source, and a
  `stats` line each minute gives the level and the share of exact-zero samples, so a deaf source shows up.
- **Thresholds**: edit and tap **Apply**. Stage 1 applies to the next frame, stage 2 to the next candidate.
- **Ground truth**: **I said it, nothing triggered** logs a `missed` mark (a false reject). **Last trigger was false**
  logs a `false_trigger` mark naming the last trigger (a false accept).
- **Save each candidate's 2 s of audio** (off by default) writes `candidates/cand-<atMs>.wav` beside the log, to
  replay or label later. It is room audio and stays on the device.

## The log

`/sdcard/Android/data/com.portal.jarviscascade/files/cascade-log.jsonl` (`adb pull` works without root), one JSON
object per line, every line with `type`, `at` (ISO-8601 UTC) and `atMs`:

| `type` | Fields |
|---|---|
| `session` | `source`, `device`, `sdk`, the three settings |
| `candidate` | `streamMs`, `source`, `stage1Peak`, `stage1Threshold`, `stage2` (`confirmed`/`rejected`/`error`), `stage2Threshold`, `stage2Boost`, `stage2Keywords`, `stage2Ms`, `triggered`, optional `error`, `audio` |
| `mark` | `mark` (`missed`/`false_trigger`), optional `candidateAtMs` |
| `stats` (every 60 s, and at stop) | `frames`, `levelDbfs`, `zeroFraction`, `stage1MsMean`, `stage1MsMax` |
| `silenced` (Android 10) | `silenced` |
| `stopped` | `reason` |

Counting, as in report §8:

- **Positive session** (say "Hey Jarvis" N times): false rejects = N − triggered candidates. The `missed` marks
  timestamp the misses.
- **Negative session** (TV, conversation, no wake word): every `"triggered":true` is a false accept, so FA/h =
  triggers ÷ session hours (from `session` to `stopped`).
- **Per stage**: `stage1Peak` and `stage2` on every candidate give stage 1 alone (all candidates) against the chain
  (triggered ones), and saved windows can be replayed at other stage-2 settings.

## What the tests cover

`./gradlew :cascade:test`:

- **Chain logic** (`CascadeChainTest`): candidate → verify → trigger with a scripted stage 1 and synthetic frames
  whose samples encode their frame index. Covers the threshold, the window's exact span (2 s ending 400 ms after the
  opening frame), stage 2 only on candidates, the 2 s refractory, rejections and stage-2 failures that never
  trigger, live settings, and stage 2 off the capture thread.
- **Stage 1 parity** (`OpenWakeWordParityTest`): the Kotlin port against openWakeWord 0.6.0 itself, on a
  bit-identical deterministic signal. Every frame's score matches within 1e-5, and the 96-dim features within 1e-3.
- **Stage 2** (`SequenceVerifierTest`, `ModelChainTest`): scaling, tail padding and the spotter rebuild against a
  fake; the real spotter through sherpa-onnx's JVM binding, on the same model and keywords file as the app.
- **The whole chain on the real models** (`ModelChainTest`): room noise and tones open no candidate. With local TTS
  clips (`tools/make-tts-fixtures.sh`, macOS only; skipped in CI), "Hey Jarvis" by two voices triggers. "Jarvis",
  "Hey Travis" and a neutral sentence don't, though stage 1 opens candidates on some of them that stage 2 rejects.

`AndroidChainTest` (instrumented) runs the same chain through the Android bindings (onnxruntime-android, the
sherpa-onnx AAR reading the APK's assets) on a device or emulator.

## Validating locally

```bash
tools/make-tts-fixtures.sh           # macOS: TTS clips into fixtures/ (gitignored)
./gradlew :cascade:test              # the JVM chain now also replays them

# On an emulator or device (pick it by serial; connectedDebugAndroidTest would run on every attached device):
./gradlew assembleDebug assembleDebugAndroidTest
adb -s <serial> push fixtures/. /data/local/tmp/jarvis-cascade-fixtures/
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <serial> shell am instrument -w com.portal.jarviscascade.test/androidx.test.runner.AndroidJUnitRunner
```

Results from the change that added this app, all **off-Portal**:

- **JVM** (Apple M1): stage-1 parity with openWakeWord passes. On the 10 TTS clips, the 4 positives trigger and the 6
  negatives don't. "Hey Travis" (stage 1 0.41 and 0.96) and one bare "Jarvis" (0.63) open candidates that stage 2
  rejects.
- **Android 10 arm64 emulator** (`system-images;android-29;default;arm64-v8a`, on the M1):
  - The instrumented tests pass, with the same outcome as the JVM on every clip.
  - The app ran with `VOICE_RECOGNITION` capture from the host mic: 751 frames per 60 s, and stage 1 at 5.5 ms mean
    per 80 ms frame.
  - Played over the Mac speaker, three "Hey Jarvis" clips triggered (stage 2 in 60-73 ms). "Hey Travis" opened a
    candidate (0.35) that stage 2 rejected, and "Jarvis" opened none.
  - Switching the source restarted capture, and the marks, saved WAVs, Stop and the log lines all worked.

## Not validated

- **No Portal.** Nothing here has run on a Portal: capture sources, AEC/NS behaviour, mic coexistence with calls and
  "Hey Portal", Android 9 behaviour at all (the emulator was Android 10), Android 10 silencing, the Portal's
  foreground-only capture, CPU and thermal cost on its SoC, and on-device accuracy with real speakers are all
  unmeasured. That is what this app is for.
- The positives are macOS TTS, not people. CI has no TTS, so there it runs only the noise and parity tests.
