# 0002: A silent wake stream is a capture loss with its own cause, and it ends capture

**Status:** accepted (2026-10-23); amended 2026-10-24: a loss is counted per **capture request**, not per `start()`;
amended 2026-10-24: an opt-in **rebuild on silence** for the assistant's conversation capture; amended 2026-10-27:
the capture policies' **names**, now one `CapturePolicy`

## Context

On a Portal+ gen1, denying the RECORD_AUDIO app-op while capture was running left reads succeeding with digital
silence. Wake detection went deaf, and nothing reported it. The idle watchdog only catches *missing* frames. Until now a
capture loss meant "capture is not running" and had two causes: *not granted* and *dropped*.

## Decision

A wake capture stream that delivers only exact-zero samples for one continuous window (equal to
`DEFAULT_IDLE_REBUILD_MS`) is a **capture loss** with a third cause, **silenced**. The session ends capture, with no
rebuild first, and reports it like any other loss: at most once per **capture request**, ending the request and
leaving the engine idle. Detection is opt-in for each `PcmCaptureSession`, and only `WakeMicEngine` turns it on.

## Considered options

- **Report it as *dropped*.** Consumers would need no changes, but they couldn't tell "capture died" from "capture is
  up but the system withholds audio". Those call for different retry policies.
- **Keep the stream open and report a separate "deaf" signal.** Silencing can lift without a reopen, but then a loss
  would no longer always mean an idle engine, and every consumer would need an "audio is back" event.
- **Rebuild first, as the idle watchdog does.** On gen1 a rebuild under a denied app-op fails to open, and a failed
  rebuild counts as *dropped*, so the case this ADR is about would get the wrong label.

## Consequences

- `CaptureLoss` is a public sealed type used in portal-commons, portal-wake and portal-assistant. Adding or removing a
  cause changes all three.
- portal-wake's retry streak must outlast the silence window. A silenced retry runs the whole window before it fails,
  so "one poll without a loss" would reset the backoff every time.
- AGC or noise processing on wake capture would turn the zeros into noise and hide the silence, so it must stay off.
- The rule relies on real mics never producing exact zeros for the whole window. A probe on Portal+ gen1 confirmed it:
  the longest exact-zero run in ~75 min of room audio was 2,187 samples, against the 32,000 needed. A denied app-op
  gave exact zeros. The privacy mic-mute gives |sample| ≤ 3, never zero, so it is not reported as silenced.

## Amendment: rebuild on silence (2026-10-24)

On Portal+ gen1, pressing Home mid-conversation left the orange bar up but Jarvis deaf: about 0.7 s after the app
leaves the foreground, the platform's `AudioPolicyService` (`ProcessPolicy: onUidForeground() silencing for <uid>`)
silences the input it already has open, even though it is held by a microphone foreground service. Unlike a denied
app-op, a **reopened** input is live again, and so is a capture opened while the app is already in the background.

So `PcmCaptureSession` gains an opt-in `rebuildOnSilence`: a full silence window first rebuilds the device, once per
silence (any non-zero sample allows another rebuild), and only a window that follows the rebuild with no sample heard
ends capture as *silenced*. The wake engine leaves it off, so this ADR's rule for wake stands. The assistant opts in
with a 500 ms window, short enough to keep the user's first words after Home and still well above the longest
natural zero run (2,187 samples ≈ 137 ms).

## Amendment: names (2026-10-27)

The capture policies are now one `CapturePolicy` value per consumer, so the names above have moved. Nothing else
changes.

- The silence opt-in is `CapturePolicy.silence`, a `CapturePolicy.Silence(windowMs, rebuildFirst)`.
- Rebuild on silence is `Silence.rebuildFirst`; it can only be set together with a window.
- The wake window is `WakeMicEngine.SILENCE_WINDOW_MS` (still equal to the idle rebuild window,
  `CapturePolicy.DEFAULT_IDLE_REBUILD_MS`), and portal-wake's retry streak reads it.
