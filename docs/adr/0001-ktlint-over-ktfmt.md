# 0001: ktlint, not ktfmt, as the Kotlin formatter

**Status:** accepted (2026-10-23)

## Context

`portal-commons`, `portal-wake` and `portal-assistant` need one enforced Kotlin formatter (see
[quality-plan](../quality-plan.md)). The two mainstream choices are **ktlint** (configurable, reads `.editorconfig`)
and **ktfmt** (Google's deterministic, nearly configuration-free formatter, used by AOSP).

All three repos already carry `.editorconfig` files with `ktlint_code_style = intellij_idea`, 4-space Kotlin indents
and `max_line_length = off`. The code was written to that style and has been checked with ktlint by hand.

## Decision

Use **ktlint**, enforced through the **kotlinter** Gradle plugin (pinned to a version that bundles the same ktlint as
the pre-commit hook's CLI), with the existing `.editorconfig` as its only configuration.

## Consequences

- No mass reformat: the code is already close to passing.
- Style stays configurable, for example the `@Composable` naming exemption in `portal-assistant`.
- Some formatting nondeterminism is accepted, which ktfmt would have removed.
- Switching to ktfmt later means a full reformat of every file in all three repos, plus giving up the 4-space style
  (ktfmt's default is 2 spaces). That cost is why this is recorded.
