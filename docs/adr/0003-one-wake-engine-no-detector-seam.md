# 0003: Commons runs one wake engine, Vosk, with no public detector seam

**Status:** accepted (2026-10-28); revisited by [0004](0004-hybrid-wake-detector-beside-vosk.md), now that a second wake engine has arrived

## Context

`WakeMicEngine` took a list of `WakeDetector.Factory` so engines could run side by side for benchmarking, and every
wake, readiness and unavailability callback carried a detector id. Vosk has been the only detector in the history of
the repo, and portal-wake and portal-assistant each pass exactly `listOf(WakeDetectors.vosk(...))`. Both apps paid for
a wide public interface (Host, Events, Factory, a long threading contract) that only `WakeMicEngineTest` varied.

## Decision

`WakeMicConfig` names a **wake model** (`WakeModel.Bundled` or `WakeModel.Unpacked(dir)`) instead of a list of
detectors, and the engine always runs one **wake detector** on it. `WakeEvent` and the `onReady` / `onUnavailable`
callbacks carry no detector id. The `WakeDetector` interface and `WakeDetectors` are deleted. The engine's tests
substitute fakes at the `VoskModelLoader` seam, where libvosk and the test fakes are the two adapters.

## Considered options

- **One pluggable engine at a time** (`WakeMicConfig` takes a single factory). Drops the fan-out but keeps Host,
  Events and Factory public for an engine that doesn't exist.
- **Keep `WakeDetector` as an internal interface** for the engine's tests. Layers a second seam over the one that
  actually varies, and the engine tests would keep exercising a fake's idea of readiness and replay.

## Consequences

- A breaking change to `WakeMicConfig` and `WakeEvent` in portal-commons, portal-wake and portal-assistant.
- Revisit when a second wake engine actually arrives: design the seam around the two adapters then, not before.
