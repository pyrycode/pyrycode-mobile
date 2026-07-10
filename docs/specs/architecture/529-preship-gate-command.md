# 529 — A documented pre-ship gate command for the mobile real-claude e2e

**Ticket:** [#529](https://github.com/pyrycode/pyrycode-mobile/issues/529) · **Size:** XS · **Security-sensitive:** no · **Design source:** N/A — not UI-visible (a shell entry point + Markdown docs), per ticket body.

## Files to read first

Shell + Markdown only — codegraph (symbol-level Kotlin) does not apply here. Read these before editing:

- `scripts/e2e-emulator.sh:52-66` — the Usage header + Tunables. Confirms the existing invocation surface is **env-var driven** (`LIVE=1`, `DETERMINISTIC=1`, `SCENARIO=…`), no positional-argument convention. Line 65 records `LIVE=1 → PAIR_NAME/PYRY_NAME default to e2e-live`.
- `scripts/e2e-emulator.sh:92-115` — the `LIVE` config branch. **Load-bearing:** setting `LIVE=1` alone already defaults `PAIR_NAME`/`PYRY_NAME` to `e2e-live` (lines 103-104) and derives both relay URLs from `LIVE_RELAY_HOST`. So the wrapper needs to bake in **only** `LIVE=1` — the `e2e-live` isolation is already the `LIVE=1` default.
- `scripts/e2e-emulator.sh:71` and `:117` — the `set -euo pipefail` idiom and the `REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"` dir-resolution idiom. Mirror both in the wrapper so it resolves its sibling regardless of caller CWD.
- `scripts/e2e-emulator.sh:174` — the `LIVE=1 && DETERMINISTIC=1` mutual-exclusion preflight. The underlying script **already** dies fast on the conflict; the wrapper must **not** re-implement any guard. Wrap it, don't re-check it.
- `README.md` (whole, 28 lines) — insertion point for the new `## Pre-ship gate` section (after `## Build`, before `## License`) and the terse operator-facing tone to match.
- `docs/e2e-interactive-stream.md:7-36` — "The ladder"; rung 3 + the `LIVE=1` variant framing at lines 25-28 that the new gate section names. The ladder currently frames rung 3 as running "rarely, after Layers 1–2 pass" — the problem this ticket fixes.
- `docs/e2e-interactive-stream.md:166-224` — the existing `## Live mode (rung 3, live relay)` section. This is the **mechanics** home (relay URLs, isolation, prerequisites, first-run assumptions) the new gate section cross-references. Anchor `#live-mode-rung-3-live-relay` stays unchanged; cost line at :209.
- `docs/specs/architecture/527-live-mode-e2e-harness.md` — prior spec for the wrapped harness. Context on what LIVE mode does and, critically, what it does **not** re-implement.

## Context

Org policy (2026-07-08): every operator-facing happy-path flow ships with a real-claude e2e that **runs** in a pre-ship gate. There is no CI (org policy), so the gate is a command the operator runs next to `./gradlew check` — the mobile parallel of the daemon's `make e2e-realclaude`.

The harness already exists: #527 shipped the `LIVE=1 bash scripts/e2e-emulator.sh` path (rung-3 ping against the production relay over `wss://`, isolated `e2e-live` instance), and pyrycode#854 fixed the daemon so it answers a live send. Both blockers are closed and the harness is on `main`. What's missing is that **nothing names that path a gate**: it's an env-var incantation buried in a doc that frames rung 3 as running "rarely." Three shipped real-claude tests sat unrun for two weeks for exactly this reason. This ticket turns the existing invocation into one memorable command and documents when the operator must run it.

This is **one deliverable, not a split**: a command nobody is told to run re-creates the "nothing names it a gate" problem, and a doc pointing at a non-existent command is dead prose. Shell alias + Markdown prose, one PR, no cross-module seam.

## Design

Three changes, all additive. No behavior change to the wrapped harness.

### 1. New thin wrapper: `scripts/e2e-preship-gate.sh`

**Decision — a named wrapper script, not a positional argument to `e2e-emulator.sh`.** The existing script dispatches modes purely by environment variable (`LIVE`, `DETERMINISTIC`, `SCENARIO`); a positional `gate` argument would introduce a second, clashing convention into an already-479-line mode dispatcher. A named entry-point script is the direct structural parallel to the daemon's `make e2e-realclaude` target the ticket cites, is self-locating in `git`, and keeps the wrapped harness untouched (satisfying "wrap it, don't re-implement it").

**Contract** — the wrapper's entire job is to bake in `LIVE=1` and hand off to the harness. Sketch (the whole deliverable is ~10 lines; exact comment wording is the developer's):

```bash
#!/usr/bin/env bash
# e2e-preship-gate.sh — the mobile pre-ship gate.
# Runs the live rung-3 real-claude e2e (ping scenario, production relay over
# wss://, isolated e2e-live instance) by wrapping `LIVE=1 scripts/e2e-emulator.sh`.
# When to run + cost: see README § Pre-ship gate and docs/e2e-interactive-stream.md.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec env LIVE=1 bash "${HERE}/e2e-emulator.sh"
```

Contract requirements the developer must honor:

- **Bake in `LIVE=1` and nothing else.** The `e2e-live` `PAIR_NAME`/`PYRY_NAME` isolation is already the `LIVE=1` default in `e2e-emulator.sh:103-104` — do not re-set it here (that would duplicate a default and risk drift).
- **`exec` the underlying script** (do not plain-call it). `exec` replaces the wrapper process so the harness's own `EXIT`/`INT`/`TERM` teardown trap, its exit code, and Ctrl-C all propagate directly with no signal-forwarding boilerplate.
- **Resolve the sibling via `BASH_SOURCE`**, not a relative path, so the gate runs correctly regardless of the caller's CWD.
- **No new guards, no arguments consumed.** Do not re-check the `LIVE`/`DETERMINISTIC` conflict (the harness preflight at `:174` owns it). Operator env overrides (e.g. `LIVE_RELAY_HOST=…`) flow through the `exec` untouched — that's the intended override surface; the wrapper does nothing to enable or block it.
- **Match the harness file mode.** `e2e-emulator.sh` is `0755`; make the wrapper executable too (`chmod +x`, or `git update-index --chmod=+x` at add time). Not load-bearing — the docs invoke it as `bash scripts/e2e-preship-gate.sh` — but keeps the two scripts consistent.

The wrapper adds **no new behavior**: a down or stale relay must still surface as the same live-gate red signal (the phone's `awaitConnected()` timing out) — that's the failure class the mode exists to catch, and the wrapper is transparent to it.

### 2. README `## Pre-ship gate` section

Insert between `## Build` and `## License`. Anchor: `#pre-ship-gate`. Content:

- One sentence naming the gate and the command: `bash scripts/e2e-preship-gate.sh`.
- **When to run** — the two triggers, stated plainly:
  - (a) before installing a new APK build on a device;
  - (b) whenever a daemon or relay change touching the mobile surface lands — run it alongside the daemon's own `make e2e-realclaude`.
- **Cost** — one real claude turn (the ping scenario), a few minutes of wall clock, subscription-covered (does **not** meter tokens).
- Cross-reference: link to `docs/e2e-interactive-stream.md#pre-ship-gate` for prerequisites and full mechanics.

Match the README's existing terse register (short prose, a fenced command block). No CI reference; a fresh clone must be able to follow these lines with no tribal knowledge.

### 3. `docs/e2e-interactive-stream.md` `## Pre-ship gate` section

Insert a **new** `## Pre-ship gate` section immediately **before** the existing `## Live mode (rung 3, live relay)` section (at line 166). Anchor: `#pre-ship-gate`. **Do not rename the existing Live mode section** — its anchor `#live-mode-rung-3-live-relay` is referenced from three live sites (the ladder at :28, Verification status at :450, and the harness comment at `scripts/e2e-emulator.sh:91`); renaming would cascade edits (and touch documentation-phase-owned knowledge files). Keep the mechanics where they are; the new section is the **policy/when** home, Live mode remains the **mechanism/how** home.

New section content:

- One sentence: this names the live rung-3 real-claude e2e as the pre-ship gate; the command is `bash scripts/e2e-preship-gate.sh` (which bakes in the `LIVE=1` + `e2e-live` defaults).
- The fenced command block.
- **When to run** — the same two triggers as the README (a: before installing a new APK; b: on a daemon/relay change touching the mobile surface, alongside `make e2e-realclaude`).
- **Cost** — one real claude turn, a few minutes of wall clock, subscription-covered.
- Cross-references: the existing mechanics section, `[Live mode (rung 3, live relay)](#live-mode-rung-3-live-relay)` (relay URLs, isolation, prerequisites, first-run assumptions), **and** the README's operator-facing summary at `[README § Pre-ship gate](../README.md#pre-ship-gate)`.

**Minor consistency edit (recommended, low-risk):** append "a few minutes of wall clock" to the existing Live-mode cost line at `:209` so the two cost statements don't contradict. One clause; leave the rest of the Live mode section untouched.

**Optional (developer's call):** the ladder mention at `:25-28` may gain a parenthetical pointing at the wrapper, but it already describes the *mode* accurately (`LIVE=1`), so leaving it is fine. Do not expand scope here.

### Anti-drift mechanism (AC3)

Both docs necessarily contain the command literal (both AC-required to name it). Drift is contained by:
1. **A single canonical command string** — `bash scripts/e2e-preship-gate.sh` — that appears identically in the wrapper filename, the README, and the e2e doc. The developer greps to confirm the three agree before committing.
2. **Mutual cross-links** — the README section links to the e2e-doc section and vice versa, so an editor touching one is pointed at the other.
3. **Mechanics single-sourced** — relay URLs, isolation, and prerequisites live **only** in the existing Live mode section; both Pre-ship gate sections link to it rather than restating it. Only the command + when-to-run + cost are (deliberately, minimally) duplicated.

## State + concurrency model

N/A — no Kotlin, no ViewModel, no Compose surface, no coroutines. The wrapper is a stateless `exec` hand-off; the harness it wraps owns all process lifecycle (relay/daemon/emulator start + the `EXIT`/`INT`/`TERM` teardown trap), unchanged.

## Error handling

The wrapper is transparent to failure by design. `set -euo pipefail` guards its own two lines (the `cd` dir-resolution). Everything after `exec` is the harness's existing error surface: preflight `die` on missing binaries or the `LIVE`/`DETERMINISTIC` conflict, the `/v1/server` registration path, and the red signal this gate exists to catch — the phone's connect timeout against a down or stale relay. The wrapper must **not** intercept, retry, or reinterpret any of it.

## Testing strategy

No unit or instrumented tests — this is a shell entry point + Markdown. `./gradlew test` / `connectedAndroidTest` are irrelevant here; do not add a Kotlin test.

Verifiable in the worktree (no infra), mirroring #527/#454/#431's static gate:

- `bash -n scripts/e2e-preship-gate.sh` — the wrapper parses.
- `shellcheck scripts/e2e-preship-gate.sh` — clean (repo standard; the harness passes it).
- Static read-through: the wrapper sets `LIVE=1`, `exec`s the sibling resolved via `BASH_SOURCE`, sets no other env, and consumes no arguments.
- Markdown cross-check: `grep -rn 'e2e-preship-gate.sh' README.md docs/e2e-interactive-stream.md scripts/` shows the identical command string in all three homes; the two `#pre-ship-gate` anchors and the `#live-mode-rung-3-live-relay` back-link resolve.
- `.github/workflows/` remains absent (AC5).

**Green is operator-gated**, exactly as the wrapped harness: a full `bash scripts/e2e-preship-gate.sh` run needs a booted emulator with outbound internet + DNS + a system-trusted TLS cert, the operator's authenticated claude, and a healthy production relay. The developer does not (cannot) run it end-to-end in the worktree; the static checks above are the deliverable's acceptance surface.

## Acceptance criteria mapping

- **AC1** (one memorable command, `LIVE=1` + `e2e-live` baked in, no incantation, no new scenario) → the wrapper (§1).
- **AC2** (README "Pre-ship gate" section, command + two triggers) → §2.
- **AC3** (e2e doc same naming + when-to-run, cross-referenced, no drift) → §3 + anti-drift mechanism.
- **AC4** (both docs state cost: one turn, a few minutes, subscription-covered) → README §2 + e2e §3 (+ the :209 consistency edit).
- **AC5** (no `.github/workflows`, no CI, fresh-clone-followable) → nothing added under `.github/`; verified absent above.

## Open questions

None blocking. One judgment call left to the developer: whether to add the optional ladder parenthetical (§3) — recommend skipping it to keep scope minimal. The wrapper filename is prescribed as `scripts/e2e-preship-gate.sh` and **must** match the command string used in both docs.
