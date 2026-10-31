# 0004: A hybrid wake detector runs beside Vosk, behind two ML seams, switchable at runtime

**Status:** accepted for the prototype (2026-10-31). Revisits ADR 0003, as its consequences asked once a second wake
engine arrived.

## Context

The wakeword benchmark on the gen-1 Portal+ (2026-10-31) found that Vosk catches every bare "Hey Jarvis" but wakes on
26 % of hard sound-alikes, and that a **hybrid** (openWakeWord gate at 0.3, then a candidate wakes if sherpa-onnx with
competing keywords confirms HEY JARVIS alone, or if the production Vosk check accepts the same ~2 s window and sherpa
heard no rival) reached 94 % bare recall with 14 % sound-alikes. Its gate opened on only 2 of 6 real household wakes,
so the next step is days of real use on the Portal with evidence logging, with an instant fallback to Vosk.

The hybrid needs Vosk anyway: the window check uses the production grammar and gates, and the comparison needs Vosk's
own decision on every utterance (the **Vosk shadow**).

## Decision

- **portal-commons** owns the hybrid's orchestration and policy, beside the Vosk wake detector and sharing its model:
  `WakeMicConfig.hybrid` (null = Vosk alone, unchanged for portal-assistant) turns on the `HybridWakeDetector`, which
  runs the per-frame policy, the gate and verify threads, the window check, the decision rule, the shadow and the
  disagreement log. The two ML engines sit behind two small interfaces, `WakeGate` and `KeywordSpotter`: the seam is
  around the two adapters that actually vary, as ADR 0003 asked.
- **portal-wake** supplies the adapters (openWakeWord on onnxruntime-android, sherpa-onnx from its GitHub AAR), so
  commons and portal-assistant take on no ONNX dependency and no fetched artifact.
- The **wake mode** (hybrid or Vosk) and the shadow are read at every frame from a setting adb changes at runtime; Vosk
  mode is the unchanged Vosk-only path. If the hybrid's models can't load, Vosk decides.
- The Vosk model is shared through a reference-counted lease, so the window check on its own thread never decodes on a
  model the engine has freed.

## Considered options

- **Everything in commons, ONNX dependencies included.** One place for detection, but every consumer (portal-assistant
  on gen-2) would ship and CI-fetch sherpa-onnx's non-Maven AAR for a detector it doesn't run.
- **Everything in portal-wake.** Needs the Vosk internals (the wake detector, the decoder seam, the matcher's decoding)
  made public, a wider surface than the two ML seams.
- **The lab's continuous Vosk decision instead of a window check.** Cheaper, but not the measured configuration.

## Consequences

- Always-on cost on a Portal+ rises from Vosk's ~45 % of a core to ~95 % with the shadow on (measured: capture thread
  ~60 %, gate thread ~34 %), spread over two of eight cores. The shadow can be turned off.
- portal-wake's build needs `./fetch-hybrid-deps.sh` (the AAR to compile, the models to run the hybrid).
- If the hybrid ships for good, Vosk stays as its window check and the wake mode setting can go.
