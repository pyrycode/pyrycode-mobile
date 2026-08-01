# #614 — Pin and report the interactive runner in the e2e harness (isolated-HOME only)

**Ticket:** https://github.com/pyrycode/pyrycode-mobile/issues/614
**Size:** S · **Labels:** `bug`, `size:s`, `security-sensitive`
**Files touched:** `scripts/e2e-emulator.sh`, `docs/e2e-interactive-stream.md` (two files, no Kotlin, no new fixtures)

## Design source

N/A — this ticket changes a bash harness and its operator documentation. Nothing renders on a device, so
there is no Figma anchor and the visual-fidelity check is intentionally not applicable. (The ticket body
carries no `## Figma` section; that is correct here, not a PO gap.)

## Files to read first

Codegraph indexes Kotlin only, so it returns the rung-3 instrumented tests for this query and nothing
about the harness itself — the reading list below is grep/Read-derived. Do not spend turns asking
codegraph about shell symbols.

- `scripts/e2e-emulator.sh:74-155` — `set -euo pipefail`, the config block, the `LIVE`/`DETERMINISTIC`
  branch that computes relay URLs and instance names, and the `log()` / `die()` helpers you will reuse.
- `scripts/e2e-emulator.sh:176-245` — the **preflight block**. Both new guards land here. Note it runs
  before `ISO_HOME` is created (`:255`) and before the relay starts (`:280`).
- `scripts/e2e-emulator.sh:247-274` — deterministic setup: where `ISO_HOME` is minted and `SESSIONS_DIR`
  computed. Establishes that `ISO_HOME` is a fresh `mktemp -d /tmp/pyry-e2e-det.XXXXXX`.
- `scripts/e2e-emulator.sh:342-362` — **step 2b**, the post-pairing / pre-daemon seam. `CONV_DIR` is
  `${ISO_HOME}/.pyry/${PYRY_NAME}` — your new write goes **one level above** it. Read the path trap below.
- `scripts/e2e-emulator.sh:364-394` — the three daemon-spawn branches. The reporting call goes immediately
  before `:365`. **Do not edit the `LIVE` branch (`:380-386`)** — see § Security review.
- `scripts/e2e-emulator.sh:406-424` — the existing `|| true` count idiom and the comment explaining why
  `|| echo 0` is wrong. The same `set -e` trap applies to your config read; see § Error handling.
- `scripts/e2e-preship-gate.sh:21-24` — `exec env LIVE=1 bash …`. Confirms the environment passes through
  untouched. **This file needs no edit.**
- `docs/e2e-interactive-stream.md:485-538` — the Deterministic-mode section and its "What the host does"
  numbered list, which gains a cross-reference. New `##` section lands before `## Verification status`
  (`:701`).

Upstream pyrycode (sibling checkout at `~/Workspace/Projects/pyrycode`, read-only — do **not** edit):

- `cmd/pyry/main.go:667-677` — `selectInteractiveRunner`. The accepted set and the exact upstream error
  string. Verified 2026-07-30.
- `cmd/pyry/pair.go:52-58` — `resolveConfigPath()` → `<HOME>/.pyry/config.json`. Per-user; no instance
  segment. This is the whole reason the ticket exists.
- `internal/config/config.go` — `DefaultConfig()` / `Load()`. Missing file → defaults, **no error**;
  malformed file → error; present file overlays defaults, so a partial file is legal.
- `internal/e2e/harness.go:388-405` — `StartStreamInteractiveWithRelay`, the closest in-repo precedent for
  the write (literal JSON, `0o700` dir / `0o600` file, before spawn).

## Context

The daemon selects its interactive runner from `interactive_runner` in `$HOME/.pyry/config.json`. There is
no command-line flag, and `resolveConfigPath()` is per-user, so `-pyry-name=e2e-live` does not namespace
it. The only lever is `$HOME`.

That splits the harness's three modes cleanly:

| Mode | HOME | Config the daemon reads | Today |
|---|---|---|---|
| DETERMINISTIC (rung 4) | isolated `ISO_HOME` (`:255`) | none — falls back to the daemon default | pinnable |
| LIVE (rung 3, prod relay) | **real** HOME (`:380-386`) | the operator's own | not pinnable |
| default rung 3 | **real** HOME (`:388-390`) | the operator's own | not pinnable |

Measured on this machine 2026-07-30, the operator's real `~/.pyry/config.json` holds exactly two keys —
`relay_url` and `interactive_runner: "stream-json"`. So the live gate's runner is a property of the
operator's machine, not of the test. Flip that key back to the terminal path for a rollback and the gate
silently starts measuring the other runner with no signal in its output. A gate whose subject changes
without its result changing is not a gate.

This ticket ships the mechanism — pin on the isolated path, report everywhere — and deliberately leaves
the rung-4 default where it is (that is #613, blocked on an upstream `fakeclaude` capability).

## Design

One new env tunable, two preflight guards, one conditional seed write, one reporting call, one doc
section. No new files.

### The variable

`INTERACTIVE_RUNNER` — named to match the config key it drives, so a rollback comparison is a
one-variable change. Accepted values: `pty`, `stream-json`. Unset (or empty — `${VAR:-}` makes these
indistinguishable, which is correct) means *do nothing*: no validation, no seed, no behaviour change in
any mode. Declare it alongside the other tunables in the config block (`:77-150`) and add it to the
`Tunables (env)` header comment (`:65-72`) plus one usage example (`:55-64`).

### The centrepiece: report by reading back, never by echoing the request

The reporting requirement (AC4) and the path trap (below) are solved by the same decision:

> **Resolve the reported runner by reading the config file the daemon is about to read — in every mode,
> including after we just wrote it.**

Never report the requested value. Read it back from disk. This is what makes the pin self-verifying: if
the seed lands one directory too deep, the read-back reports `pty (daemon default)` instead of
`stream-json`, and the operator sees on stdout that the pin did not take. The ticket's named false-green
becomes structurally observable rather than a comment warning the developer not to make a mistake.

Contract — one function, both paths:

```bash
# resolve_runner_from_config <config-path>
#   Echoes exactly one line: "<runner>\t<reason>". Never writes. Never exits non-zero.
#   runner ∈ { pty, stream-json, unrecognised, unknown }
```

Cases it must distinguish (`reason` is the operator-facing explanation):

| Situation | runner | reason |
|---|---|---|
| file absent | `pty` | daemon default — no config file at `<path>` |
| file present, key absent or `""` | `pty` | daemon default — `interactive_runner` unset in `<path>` |
| file present, key `pty` / `stream-json` | that value | from `<path>` |
| file present, key some other string | `unrecognised` | value in `<path>` is outside the accepted set — the daemon will refuse to start |
| unreadable / malformed JSON | `unknown` | could not read `<path>` |

The last two rows are **reports, not aborts** on the real-HOME paths — the ticket is explicit that a config
the harness cannot read is something to report. (They are also a useful early hint: upstream `config.Load`
returns an error on malformed JSON, so the daemon would have died three seconds later with an opaque
message.)

`python3` is already a hard preflight prerequisite (`:181`), so use it for the read — no `jq` dependency.

**Call site:** exactly one, immediately before `log "starting pyry daemon…"` (`:365`), passing
`${ISO_HOME}/.pyry/config.json` on the deterministic path and `${HOME}/.pyry/config.json` otherwise. One
call site, all three modes, guaranteed pre-spawn. Emit one `log` line naming the runner and the reason.

### Preflight guards (`:176-183`)

Both land in the preflight block, which runs before `ISO_HOME` exists, before pairing, and before the
relay starts. **Order is load-bearing — validate the value first, then the mode.**

1. **Value validation (AC1).** If `INTERACTIVE_RUNNER` is non-empty and not in `{pty, stream-json}`, `die`
   naming the offending value *and* the accepted set. Mirror the upstream wording so the two agree:
   upstream says `interactive_runner %q not recognized (accepted: "pty", "stream-json")`.
2. **Real-HOME guard (AC3).** If `INTERACTIVE_RUNNER` is non-empty and `DETERMINISTIC` is empty, `die`
   naming *why*: the config is per-user, this run is under the real HOME, and honouring the request would
   edit the operator's production configuration. Point at `DETERMINISTIC=1` as the mode that supports it.

Why validate first: AC1 promises the typo check is unconditional. If the mode guard ran first,
`INTERACTIVE_RUNNER=ptty LIVE=1` would report the mode error, the operator would switch modes, re-run, and
*then* discover the typo. Validating first surfaces the cheaper problem first.

`scripts/e2e-preship-gate.sh` needs **no change**: `exec env LIVE=1 bash …` inherits the caller's
environment, so an operator with `INTERACTIVE_RUNNER` exported gets this abort and its explanation instead
of a pre-ship gate silently measuring the other runner. That is the intended outcome — do not add a scrub.

### The seed write (`:342-362`, step 2b)

Inside the existing `if [ -n "${DETERMINISTIC}" ]` block, and **only when `INTERACTIVE_RUNNER` is
non-empty**. Leaving it unset must write nothing at all — today there is no config file on that path, and
AC2 requires byte-for-byte identical behaviour when unset. Do not write `{"interactive_runner":""}` as a
"harmless" default.

**Path trap — the single most likely way to ship a false green.** The adjacent seed three lines away
writes to `${CONV_DIR}` = `${ISO_HOME}/.pyry/${PYRY_NAME}/conversations.json` (per-instance). The config is
**per-user**:

```
✅  ${ISO_HOME}/.pyry/config.json                  ← resolveConfigPath()
❌  ${ISO_HOME}/.pyry/${PYRY_NAME}/config.json     ← one level too deep; config.Load reads it as
                                                     a missing file and returns defaults WITH NO ERROR
```

Copying the adjacent line's path puts the file where `config.Load` silently returns defaults, the run goes
green on the default runner, and the pin did nothing. The read-back reporting above is the safety net that
makes this visible; the correct path is the fix.

Content, mirroring `internal/e2e/harness.go:388-405`: a partial config carrying only the one field —
`{"interactive_runner":"<value>"}`. `config.Load` overlays a partial file onto `DefaultConfig()`, so every
other field keeps its default. That is safe for `relay_url` specifically because the harness already passes
`PYRY_RELAY_URL` on this path (`:371`) and upstream resolves relay URL as flag → env → config
(`main.go:855`, `resolveRelayURL`). Proof it already works this way: today's deterministic path has **no**
config file, so `relay_url` is already the built-in default `wss://relay.pyrycode.dev`, and the run still
reaches the local relay.

Permissions per the upstream precedent: `0700` on the `.pyry` dir, `0600` on the file. `mkdir -p` the dir
even though `pyry pair` has already created it — the existing `CONV_DIR` assertion at `:358` proves the
parent exists, but the `mkdir` keeps the write self-contained.

**Ordering:** the config is read exactly once at daemon startup (`main.go:769`). Step 2b is post-pairing and
pre-daemon, so it is inside the window. `pyry pair` only ever *reads* the config (`pair.go:169`), so seeding
either side of pairing would be safe; step 2b is chosen because it is the established seeding seam.

### Documentation (AC5)

New top-level `## Interactive runner selection` section in `docs/e2e-interactive-stream.md`, placed after
the Deterministic-mode block and before `## Verification status` (`:701`) — it is cross-cutting, so it does
not belong inside either mode's section. It must state: the accepted values; that pinning applies on the
DETERMINISTIC path only; what a real-HOME run does with it (aborts in preflight, and why); that reporting
happens in every mode including when no config file exists; and the no-write guarantee. Add a one-line
cross-reference from step 2 of "What the host does (deterministic seams)" (`:521-530`) to the new section.

## State + concurrency model

Not a Kotlin surface — no coroutines, no flows. The only ordering constraint is that the config is read
once at daemon startup, so the seed must land before spawn; step 2b satisfies this structurally. The
background fixture-drop watcher (`:405-452`) starts *after* the daemon and never touches the config, so
there is no interaction. The reporting read is synchronous and completes before the spawn branch is
entered.

## Error handling

Three abort paths and one never-abort path.

**Aborts (both via the existing `die`, both in preflight, both before the relay starts and before
`ISO_HOME` exists):** unrecognised value; runner requested on a real-HOME path.

**Never aborts:** the reporting read. On the real-HOME paths the operator's config is not ours to
validate — an unreadable or malformed file is reported (`unknown`), not fatal.

**`set -euo pipefail` trap — read this before writing the read.** The script runs under `set -e`, so a
command substitution whose command exits non-zero kills the script. A reporting function that is supposed
to never abort will abort if `python3` exits 1 on a malformed file. Make the Python side catch everything
and always exit 0, printing exactly one line. Do **not** patch it at the call site with `|| echo unknown`:
the script already documents at `:414` that an `|| echo <token>` fallback appends a *second* token and
breaks the consumer. `|| true` is the idiom used at `:421` if you need a belt.

**Do not echo file contents.** Report the one field and a fixed reason token. The reason string must not
interpolate a raw parser exception — see § Security review, finding 3.

## Testing strategy

This repo has no bash test framework, and the ticket scopes out new fixtures. Verification is an operator
run plus an explicit proof of the no-write guarantee. Record the results in the PR body.

Scenarios (each is a single harness invocation; the first four need no emulator — they abort or can be
observed in the pre-spawn log lines):

- **Unset, DETERMINISTIC** — no `config.json` anywhere under `ISO_HOME`; report names `pty` with the
  "no config file" reason. This is the AC2 byte-for-byte case *and* it exercises the missing-file branch of
  the resolver on every run.
- **`INTERACTIVE_RUNNER=stream-json`, DETERMINISTIC** — `${ISO_HOME}/.pyry/config.json` exists with exactly
  `{"interactive_runner":"stream-json"}`; report names `stream-json`. Assert the file is at that path and
  **not** at `${ISO_HOME}/.pyry/${PYRY_NAME}/config.json`.
- **`INTERACTIVE_RUNNER=pty`, DETERMINISTIC** — same, with `pty`. Confirms both accepted values round-trip.
- **`INTERACTIVE_RUNNER=bogus`** (any mode) — preflight abort naming `bogus` and the accepted set; exits
  before the relay starts.
- **`INTERACTIVE_RUNNER=pty LIVE=1`** — preflight abort naming the real-HOME reason. **Then prove the
  no-write guarantee:** `shasum -a 256 ~/.pyry/config.json` before and after; the digests must match and
  the file must still contain `relay_url`. A test-caused edit to production configuration is the failure
  this ticket must not introduce.
- **Unset, LIVE** (or default rung 3) — report reads the operator's config read-only and names
  `stream-json` on this machine; `shasum` unchanged across the run.

The full green rung-4 run (`DETERMINISTIC=1 SCENARIO=ping`) should be run once with the pin set to
`stream-json` to confirm the seeded config does not disturb relay resolution. Note that rung 4's *default*
runner is out of scope (#613) — a pinned `stream-json` rung-4 run is not expected to be green end-to-end
and is not a gate here; what matters is that the report names `stream-json`, proving the seed landed at the
path the daemon reads.

## Open questions

1. **Does a pinned `stream-json` rung-4 run actually pass?** Almost certainly not — that is exactly the
   `fakeclaude` capability gap #613 is parked on. Out of scope. The AC is that the harness *reports* the
   runner correctly, not that every pin produces a green run. Do not chase a red rung-4 run into #613's
   territory.
2. **Reporting placement vs. the `-pyry-relay` flag.** The resolver reads the config only for
   `interactive_runner`. If a future ticket wants the harness to report the effective *relay* too, it must
   replicate upstream's flag → env → config precedence rather than reading the config alone. Explicitly not
   done here.

## Security review

**Verdict:** PASS

**Findings:**

- **[Trust boundaries]** One new boundary: the operator's `~/.pyry/config.json` (a file the harness does not
  own) → a harness log line. It is explicit and single-sited — the `resolve_runner_from_config` function is
  the only reader, and it returns a value constrained to a four-token set (`pty`, `stream-json`,
  `unrecognised`, `unknown`) rather than passing arbitrary file content downstream. Nothing else in the
  script consumes the result; it is logged and discarded. The reverse boundary (harness → config file) is
  covered under File/storage below.
- **[File / storage] — the ticket's primary sensitive surface, addressed structurally.** The no-write
  guarantee on the operator's production config rests on two independent mechanisms of *different fabric*,
  per the pipeline principle: (a) the AC3 preflight guard, an explicit check; and (b) the write target being
  derived from `${ISO_HOME}` only — a fresh `mktemp -d` — and never from `$HOME`, so even a bypassed guard
  cannot land a write on the real HOME. Upstream corroborates the invariant from the other side:
  `resolveConfigPath()` has **no writer anywhere in pyrycode** (verified 2026-07-30 — its only callers are
  `config.Load` at `pair.go:169` and `main.go:769`, both reads), so the harness is the only actor that could
  ever write that path. No path traversal: the config path is assembled from `${ISO_HOME}` and a literal,
  with no operator-controlled path segment — `INTERACTIVE_RUNNER` reaches the file *contents*, never the
  path, and is validated against a two-element set before it gets there. No TOCTOU: the write is
  unconditional-on-branch, not check-then-write. `ISO_HOME` is a fresh private temp dir, so no symlink
  pre-seeding is possible.
- **[Error messages, logs, telemetry] — SHOULD FIX, designed out above.** Two concrete leaks to avoid, both
  cheap: (1) the reporting must print *only* the `interactive_runner` field, never the file body — the same
  file holds `relay_url` today and is the natural home for future secrets; (2) the malformed-file reason
  must be a fixed token, not an interpolated parser exception, since exception text can carry a fragment of
  the document. Both are specified in § Error handling. Code-review should check the implementation
  actually honours them.
- **[Error messages, logs] — SHOULD FIX, terminal-escape clamp.** The reported value on the real-HOME path
  originates in a file the harness does not control. A config whose `interactive_runner` held ANSI escapes
  would inject them into the operator's terminal via the `log` line. This is not a privilege boundary (it is
  the operator's own file on the operator's own machine, and the daemon would echo the same value in its own
  startup error), and it has not been observed — so it is not a gate. But the fix is free given the resolver
  already classifies the value: report the raw string only when it matches the accepted set, and emit the
  bare token `unrecognised` otherwise, without echoing. That is the design specified in the resolver table
  above; no extra code.
- **[Network & I/O] — the ticket's second named sensitive surface.** The LIVE branch's TLS posture is
  enforced *by omission*: `PYRY_ALLOW_INSECURE_RELAY` is deliberately never set at `:380-386`, unlike the
  deterministic (`:371`) and default (`:388`) branches. This design touches none of the three spawn branches
  — the reporting call is inserted *before* `:365` and the seed lives in step 2b — so the omission is
  preserved by not editing the code that contains it, rather than by re-asserting it. The spec instructs the
  developer explicitly not to edit `:380-386`; code-review should diff that range and confirm it is
  untouched. No new sockets, no TLS configuration, no timeout surface: this ticket adds one local file read
  and one local file write.
- **[Tokens, secrets, credentials]** Not applicable, and specifically so: `INTERACTIVE_RUNNER` is a mode
  name from a closed two-element set, not a credential. The seeded config carries exactly one non-secret
  field. The harness's real credential material — the pairing token and `server_static_pubkey` parsed at
  `:312-339` — is untouched by this change and remains passed as instrumentation arguments exactly as
  before.
- **[Cryptographic primitives]** Not applicable — no randomness, no hashing, no key handling is introduced.
  The one hash in the verification procedure (`shasum -a 256`) is an operator-run integrity check, not
  product code.
- **[Inter-process / Android attack surface]** Not applicable — no Kotlin, no manifest change, no exported
  component, no deep link, no `PendingIntent`. The change is confined to a developer-run shell script and a
  markdown doc; neither ships in the APK.
- **[Concurrency]** No coroutines and no shared mutable state are introduced. The one ordering requirement —
  seed before spawn, because the daemon reads the config exactly once at startup — is satisfied structurally
  by placing the write in step 2b, a seam that is already post-pairing and pre-daemon. The background
  fixture watcher starts after the daemon and never touches the config path, so there is no writer race on
  the seeded file.
- **[Threat model alignment]** No wire-protocol surface changes, so `protocol-mobile.md` § Security model is
  not engaged. The ticket names its own two sensitive surfaces — the no-write guarantee and the LIVE TLS
  omission — and both are addressed above, each structurally rather than by a branch being taken correctly.
  Out of scope and named: changing rung 4's default runner (#613, blocked upstream on a `fakeclaude`
  capability); reporting the effective relay URL (Open question 2).

**Reviewer:** architect (self-review per `architect/security-review.md`)
**Date:** 2026-07-30
