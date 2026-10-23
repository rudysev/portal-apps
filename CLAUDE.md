# portal-apps

Workspace superproject pinning `portal-assistant`, `portal-wake` and `portal-commons` as submodules (see
`README.md`). Each submodule has its own `CLAUDE.md`; read it before working inside that repo.

## Agent skills

### Issue tracker

Local markdown under `.scratch/<feature>/` (one file per ticket in `issues/`, gitignored). See `docs/agents/issue-tracker.md`.

### Triage labels

Default five roles (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`), recorded as a `Status:` line in each ticket. See `docs/agents/triage-labels.md`.

### Domain docs

Multi-context: root `GLOSSARY-MAP.md` points at each submodule's `GLOSSARY.md`; cross-repo ADRs live in root `docs/adr/`. See `docs/agents/domain.md`.

### Grilling (`/grill-with-docs`)

Ask **one question at a time, then STOP** and wait for the user's answer. Don't answer your own questions,
don't generate the next round ahead of the answer, and don't begin implementation without the user's
explicit permission.
