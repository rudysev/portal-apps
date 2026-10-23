# Quality-tooling plan

Adds static analysis and runtime checks to `portal-commons`, `portal-wake` and `portal-assistant`, **one tool at a
time**. For each tool: run it manually, fix every finding, then enable it so it runs automatically (git hooks + GitHub
Actions). Tickets live in the local tracker at `.scratch/quality-tooling/issues/`.

## Ground rules

- **Repo order per tool:** `portal-commons` → `portal-wake` → `portal-assistant`, then bump the submodules in
  `portal-apps`. A step is done only when all three repos are done.
- **Zero findings.** Warnings count as errors. No baseline files. Suppression is inline only (`@Suppress` /
  `@SuppressLint` / `# shellcheck disable=`) with a comment saying why.
- **Config is copied, not shared.** Each repo builds standalone on GitHub, so each carries identical copies of the
  tool config (as it already does for `.editorconfig`). Keep them in sync by hand.
- **Hooks are the gate; CI is the alarm.** Work stays direct-to-`main`, with no PRs or branch protection.
  - `.githooks/` is committed in each repo and enabled with `git config core.hooksPath .githooks`. Each `setup.sh`
    runs that line, and so does a one-liner in `portal-apps` for all submodules.
  - **pre-commit:** fast checks only, on staged files, using the CLIs (`ktlint`, `shellcheck`). It verifies that the
    `ktlint` version matches the one kotlinter pins, fails with `brew install …` if a tool is missing, and on a
    finding rejects the commit and prints the fix command (no auto-fix and re-stage). `--no-verify` is the deliberate
    escape hatch.
  - **pre-push:** `./gradlew check`.
  - **CI:** one workflow per repo. Temurin JDK 21, `gradle/actions/setup-gradle` with caching. Triggers: push to
    `main`, `workflow_dispatch`, `repository_dispatch`. Runs `./gradlew check assembleDebug`. App workflows check out
    `portal-commons@main` as a sibling (the apps `includeBuild("../portal-commons")`). A green commons run dispatches
    both app workflows (needs a fine-grained PAT secret in `portal-commons`).
- **Definition of done in each repo:** (1) run the tool manually and record the finding count in the ticket;
  (2) fix commits grouped by rule or category, each passing tests; (3) a commit wiring the tool into Gradle, the hook
  and CI; (4) update `CLAUDE.md` (verification command becomes `./gradlew check`, plus hook setup); (5) push and see
  CI go green.

## Order

| # | Step | Why here |
|---|------|----------|
| 1 | **CI skeleton**: today's `test assembleDebug` in each repo, plus commons → apps dispatch | Nothing after it can be "automatic" without it. Proves the composite-build checkout. |
| 2 | **ktlint** via kotlinter (bundles ktlint 1.8.0), plus the pre-commit hook | Cheapest step: already configured and the code is close to clean. Gives clean diffs for every later fix. See [ADR 0001](adr/0001-ktlint-over-ktfmt.md). |
| 3 | **Android Lint**: `warningsAsErrors`, `abortOnError`, `checkReleaseBuilds`; `com.android.lint` on pure-JVM `:commons` | Most real bugs found per hour, with nothing to install. Today it only runs in the IDE (`lintVital` needs `assembleRelease`, which is never built). |
| 4 | **detekt 2.0.0-alpha.4**: `buildUponDefaultConfig` plus coroutine opt-ins (`GlobalCoroutineUsage`, `InjectDispatcher`, `SuspendFunSwallowedCancellation`, `SleepInsteadOfDelay`, TODO tracking); **compose-rules** in `portal-assistant` only | Most effort. Stable 1.23.8 doesn't support AGP 9.2.1 with built-in Kotlin. Type resolution is the point. |
| 5 | **shellcheck** for each repo's scripts **plus** `portal-apps/provisioning/` (the user-facing installer), with a shellcheck-only workflow and hook in `portal-apps` | The installer ships to end users. `.bat` files are out of scope. |
| 6 | **StrictMode + LeakCanary**: debug builds only, `penaltyLog()` | Runtime-only checks. "Fix all" means one scripted device session per app, reading `adb logcat -s StrictMode LeakCanary`. |
| 7 | **dependency-analysis** (`buildHealth`): CI only, fails on any advice | Slow and changes rarely, so it stays out of `check` and pre-push. |
| 8 | Follow-up: **detekt alpha → stable** once 2.0 ships | Tracks the alpha risk taken in step 4. |

## Out of scope (later phase)

Test tooling: `kotlinx-coroutines-test`, Lincheck (for the hand-rolled `@Volatile` / `Atomic*` / `synchronized`
concurrency), Kover. Also MobSF, Sonar, Qodana and Renovate/Dependabot.
