# 0002: A silent wake stream is a capture loss with its own cause, and it ends capture

**Status:** accepted (2026-10-23)

## Context

On a Portal+ gen1, denying the RECORD_AUDIO app-op while capture was running left reads succeeding with digital
silence. Wake detection went deaf, and nothing reported it. The idle watchdog only catches *missing* frames. Until now a
capture loss meant "capture is not running" and had two causes: *not granted* and *dropped*.

## Decision

A wake capture stream that delivers only exact-zero samples for one continuous window (equal to
`DEFAULT_IDLE_REBUILD_MS`) is a **capture loss** with a third cause, **silenced**. The session ends capture, with no
rebuild first, and reports it like any other loss: at most once per `start()`, leaving the engine idle. Detection is
opt-in for each `PcmCaptureSession`, and only `WakeMicEngine` turns it on.

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
