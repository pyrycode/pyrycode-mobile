# #527 — Live-mode rung-3 e2e harness (`LIVE=1`) against the real pyry daemon over the live relay

## Files to read first

- `scripts/e2e-emulator.sh:63-118` — config block. This is where the `LIVE` flag, the relay-URL
  asymmetry (`DAEMON_RELAY_URL` vs `PHONE_RELAY_URL`), and the instance-name defaults (`PAIR_NAME` /
  `PYRY_NAME`) are set. **All of the new logic hangs off this block.**
- `scripts/e2e-emulator.sh:74-80` — `DETERMINISTIC` → `TEST_CLASS` selection. LIVE is **not**
  deterministic, so it already resolves to `InteractiveStreamE2ETest` (the rung-3 class). No change here;
  read it to confirm.
- `scripts/e2e-emulator.sh:128-143` — `cleanup()` trap. `RELAY_PID`/`ISO_HOME` stay empty on the LIVE
  path, so their guards already no-op. Confirm no change needed.
- `scripts/e2e-emulator.sh:145-149` — preflight `command -v` checks. The `RELAY_BIN` check must become
  conditional (LIVE spawns no local relay).
- `scripts/e2e-emulator.sh:214-241` — the DETERMINISTIC isolated-HOME (`ISO_HOME`) machinery. **Read this
  to understand why LIVE must NOT reuse it** (see Design § "Real HOME, not isolated HOME").
- `scripts/e2e-emulator.sh:243-255` — local relay start + `/healthz` wait. Must be skipped on LIVE.
- `scripts/e2e-emulator.sh:257-301` — `pyry pair` + payload parse. LIVE takes the existing non-deterministic
  (`else`) branch **unchanged** — the isolation is by `-pyry-name`, under the real HOME.
- `scripts/e2e-emulator.sh:325-348` — daemon start. The current non-deterministic branch (line 342) sets
  `PYRY_ALLOW_INSECURE_RELAY=1`; LIVE needs a sibling branch that **omits** it and dials `wss://`.
- `scripts/e2e-emulator.sh:408-423` — the Gradle Managed-Device run + instrumentation-arg injection seam.
  Unchanged in shape; LIVE only scopes `TEST_TARGET` to the ping method and injects the `wss://` base as
  `relayUrl`.
- `app/src/androidTest/.../e2e/InteractiveStreamE2ETest.kt:62-97` — the ping `@Test` method
  `interactiveTurn_pingPrompt_streamsPingReplyIntoThread`. This is the exact method LIVE targets (one
  real-claude turn). **Extract:** the method name, verbatim, for `TEST_TARGET`.
- `app/src/androidTest/.../e2e/E2eTestApplication.kt:34-72` — the credential seam. **Extract:** it stores
  the injected `relayUrl` arg **verbatim** into `PairedServer.relayUrl` (line 54); it does not parse or
  special-case the scheme. Confirms the phone needs **no** code change to accept `wss://`.
- `app/src/test/.../data/network/OkHttpRelayTransportTest.kt:189` +
  `connect_dialsV1ClientVerbatimWithAllHeaders` (same file) — proves the transport appends `/v1/client`
  to the given origin and rides TLS for `wss` (OkHttp handles it), so a **base** URL is correct for the
  phone. This is why `PHONE_RELAY_URL` is the base, not `…/v1/client`.
- `docs/e2e-interactive-stream.md` — the "How to run" (`:143-161`) and "Deterministic mode (rung 4)"
  (`:163-215`) sections. **Mirror their structure** for the new live-mode section, and update the ladder
  rung-3 bullet (`:20-25`).
- Memory `[[pairedserver-relayurl-is-origin]]` — `relayUrl` is an origin (`wss://host`, no path);
  `OkHttpRelayTransport` validates scheme ∈ {ws, wss} and appends `/v1/client`. The production relay host
  is `pyrycode-relay.pyryco.de` (CLAUDE.md status; live 2026-05-29).

## Context

The org real-claude policy (2026-07-08) requires every operator-facing happy-path flow to have a
real-claude e2e that runs in a pre-ship gate, and the operator must never be the **first** real-stack
execution. Today's rung 3 (`scripts/e2e-emulator.sh`, default mode) spawns a **local** relay on plain
`ws://` with `PYRY_ALLOW_INSECURE_RELAY=1` plus a scratch daemon — a rig that structurally cannot catch
the live-environment failure class (the 2026-07-03 connect-drop loop was a five-week-stale relay deploy,
invisible to any local-relay run, found only by a real connection).

This ticket adds a **`LIVE=1` branch** to the existing script that runs the same instrumented rung-3
**ping** scenario, on the same Gradle Managed Device, against the **real installed `pyry` binary** over
the **live relay** (`wss://pyrycode-relay.pyryco.de`). No new test scenarios, no new harness — this is
the harness-as-its-own-ticket split (mirrors daemon pyrycode#860, desktop #40); the existing #421/#481/#482
scenarios ride the mode as-is. The prerequisite (pyrycode#854 — daemon interactive-bootstrap deadlock +
liveness gate) has landed, so the real-daemon round-trip is unblocked.

**Key finding — zero app-side change.** `E2eTestApplication` stores the injected `relayUrl` verbatim into
`PairedServer.relayUrl`, and `OkHttpRelayTransport` appends `/v1/client` and rides TLS for `wss` without
special-casing the scheme. The whole deliverable is therefore two files: a branch in
`scripts/e2e-emulator.sh` and a section in `docs/e2e-interactive-stream.md`.

## Design source

N/A — infrastructure / test-harness ticket, not UI-visible (PO confirmed no Figma).

## Design

### The mode matrix after this change

| Mode | Relay | Claude | Insecure flag | HOME | Instance name (default) | Test target |
| --- | --- | --- | --- | --- | --- | --- |
| default (rung 3) | local `ws://127.0.0.1` | real | `=1` | real `$HOME` | `e2e-emulator` | whole `InteractiveStreamE2ETest` |
| `DETERMINISTIC=1` (rung 4) | local `ws://` | scripted `fakeclaude` | `=1` | isolated `/tmp` | `e2e-emulator` | one `Deterministic…` method |
| **`LIVE=1` (rung 3, new)** | **`wss://pyrycode-relay.pyryco.de`** | **real** | **never set** | **real `$HOME`** | **`e2e-live`** | **the ping method only** |

`LIVE` is a rung-3 (real-claude) mode. It is **mutually exclusive with `DETERMINISTIC`** — the two
contradict (real vs scripted claude). Guard: if both set, `die` early with a clear message.

### 1. Config (`:63-118`) — the flag, the URL asymmetry, the instance name

Add `LIVE="${LIVE:-}"` and the exclusivity guard. Then make the **relay-URL** and **instance-name**
blocks branch on `LIVE`. Contract (sketch, ~12 lines — the shape, not the final text):

```bash
LIVE="${LIVE:-}"
[ -n "${LIVE}" ] && [ -n "${DETERMINISTIC:-}" ] && die "LIVE=1 and DETERMINISTIC=1 are mutually exclusive"

if [ -n "${LIVE}" ]; then
  LIVE_RELAY_HOST="${LIVE_RELAY_HOST:-pyrycode-relay.pyryco.de}"   # host only; keep the asymmetry in-script
  DAEMON_RELAY_URL="wss://${LIVE_RELAY_HOST}/v1/server"            # daemon: /v1/server BAKED IN
  PHONE_RELAY_URL="wss://${LIVE_RELAY_HOST}"                       # phone: BASE only, appends /v1/client
  PAIR_NAME="${PAIR_NAME:-e2e-live}"; PYRY_NAME="${PYRY_NAME:-e2e-live}"
else
  DAEMON_RELAY_URL="ws://127.0.0.1:${PORT}/v1/server"             # existing loopback shape (unchanged)
  PHONE_RELAY_URL="ws://10.0.2.2:${PORT}"
fi
```

**Load-bearing invariants:**

- **Relay-URL asymmetry.** The daemon needs `/v1/server` baked into the URL it dials; a base URL silently
  404s into a dial-retry loop. The phone gets the **base** and appends `/v1/client` itself. Derive both
  from a single `LIVE_RELAY_HOST` so an operator override cannot break the asymmetry.
- **No `PORT` in the LIVE URLs** — `wss` defaults to 443. `PORT` stays a loopback-only tunable.
- **Instance name defaults to `e2e-live`**, distinct from the default-mode `e2e-emulator`, so a live run
  is obvious in `pyry pair list` / process listings and never shares identity with a local run. Still
  `PYRY_NAME`/`PAIR_NAME`-overridable.
- Move the existing lines 66-72 name/URL assignments into (or above) this branch as needed; keep the
  non-LIVE branch byte-identical to today so default rung 3 and rung 4 are unaffected.

### 2. Real HOME, **not** isolated HOME (the critical divergence from rung 4)

Rung 4 (`DETERMINISTIC`) runs under a short isolated `/tmp/pyry-e2e-det.*` HOME (`:214-241`) because
`fakeclaude` needs no auth and the isolated HOME sandboxes the scratch `.pyry`/`.claude` and keeps the
daemon's control-socket path short. **LIVE must NOT do this**: LIVE spawns **real claude**, which reads the
operator's `~/.claude` subscription auth. An isolated HOME would strip that auth and the daemon's claude
would fail to start. So LIVE runs under the **real `$HOME`**, exactly like default rung 3 — meaning the
`ISO_HOME` block stays `DETERMINISTIC`-gated and LIVE never enters it.

Isolation on the LIVE path is therefore by **instance name** (`-pyry-name=e2e-live`), not by HOME. `pyry`
keys identity + `devices.json` + `conversations.json` off the pyry-name: `pyry pair -pyry-name=e2e-live`
and the `-pyry-name=e2e-live` daemon read/write only `~/.pyry/e2e-live/`, never the production instances'
directories (default-name on this Mac, and pyrybox). This satisfies AC #3 ("its own isolated instance
name, identity, and `devices.json`; no production identity or `devices.json` read or written") — the
production instances use different names, so they are structurally untouched. This mirrors how default
rung 3 already isolates via `-pyry-name=e2e-emulator`; LIVE only changes the name.

### 3. Preflight (`:145-149`) — relay binary conditional

The `RELAY_BIN` `command -v` check must run **only when not LIVE** (LIVE spawns no local relay). Keep the
`pyry`, `python3`, and `gradlew` checks unconditional. The claude-auth prerequisite (real claude on the
host) still applies — documented, not enforced in-script, same as default rung 3.

### 4. Relay start + healthz (`:243-255`) — skip on LIVE

Wrap the local-relay spawn and the `/healthz` poll in `if [ -z "${LIVE}" ]; then … fi`. On LIVE,
`RELAY_PID` stays empty, so `cleanup()`'s `[ -n "${RELAY_PID}" ]` guard already skips the kill —
no cleanup change. (Optional, **not required**: a lightweight `wss` reachability probe to fail fast if
the live relay is down — see Open questions. The full round-trip already surfaces a down relay via the
connect timeout, which is the whole point of the mode.)

### 5. Pair (`:257-301`) — unchanged, LIVE takes the `else` branch

The isolated-HOME pairing branch is `DETERMINISTIC`-gated. LIVE is not deterministic → it takes the
existing `else` branch (line 268) **verbatim**: pairs under the real HOME with `-pyry-name="${PYRY_NAME}"`
(now `e2e-live`) and `PYRY_RELAY_URL="${DAEMON_RELAY_URL}"` (now the `wss://…/v1/server`). The payload
parser and `pyry pair`-before-daemon ordering are unchanged — the pair-then-start order is load-bearing
(the daemon loads `devices.json` once at boot, never reloads), and the script already honours it.

### 6. Daemon (`:325-348`) — a LIVE branch with **no insecure flag**

Split the current non-deterministic `else` into two arms. LIVE dials `wss://` and must **never** set
`PYRY_ALLOW_INSECURE_RELAY` (AC #2). Contract (sketch):

```bash
if [ -n "${DETERMINISTIC}" ]; then
  … unchanged (fakeclaude, isolated HOME, insecure flag) …
elif [ -n "${LIVE}" ]; then
  PYRY_MOBILE_V2=1 PYRY_RELAY_URL="${DAEMON_RELAY_URL}" "${PYRY_BIN}" -pyry-name="${PYRY_NAME}" \
    >"${DAEMON_LOG}" 2>&1 &
  DAEMON_PID=$!
else
  PYRY_ALLOW_INSECURE_RELAY=1 PYRY_MOBILE_V2=1 PYRY_RELAY_URL="${DAEMON_RELAY_URL}" "${PYRY_BIN}" -pyry-name="${PYRY_NAME}" \
    >"${DAEMON_LOG}" 2>&1 &
  DAEMON_PID=$!
fi
```

The `sleep 3; kill -0` liveness check (`:346-348`) is unchanged. It only asserts the process survived
boot; the test itself waits for `ConnectionState.Connected`. (The daemon's first dial to the internet
relay may be marginally slower than loopback, but the check is process-alive, not connected — no change.)
LIVE does **not** pass `-pyry-workdir` (parity with default rung 3); the ping scenario uses no tools, so
claude's cwd is inert.

### 7. Fixture watcher (`:350-406`) — skipped on LIVE

Wholly `DETERMINISTIC`-gated → LIVE never arms it. No change. (`WATCHER_PID` stays empty; cleanup no-ops.)

### 8. Test run (`:408-423`) — scope to the ping method, inject the `wss` base

LIVE runs the **ping** scenario only (AC #1 + the cost AC: "one real claude turn per run"). The default
rung-3 path runs the whole `InteractiveStreamE2ETest` class, which also includes the #481 tool-use test
(a **second** real-claude turn) and `@Ignore`d cases. For LIVE, set:

```bash
TEST_TARGET="${TEST_CLASS}#interactiveTurn_pingPrompt_streamsPingReplyIntoThread"
```

so exactly one turn is spent. Reuse the existing `-Pandroid.testInstrumentationRunnerArguments.*`
injection verbatim; `relayUrl` now carries the `wss://` base, and the phone dials `…/v1/client` over TLS.
The emulator reaches the public relay directly over its NAT'd internet (not the `10.0.2.2` host alias,
which is loopback-only). Keep the PASS log line (`:428`); optionally note "over the live relay".

### 9. Header comment / usage (`:1-61`)

Update the top-of-file block comment: add the LIVE mode to the "What it does" summary, the Usage examples
(`LIVE=1 bash scripts/e2e-emulator.sh`), the Prerequisites (real claude auth; no relay binary needed;
emulator internet), and the Tunables (`LIVE=`, `LIVE_RELAY_HOST=`). This is part of the script edit, not
the doc file.

## State + concurrency model

Shell orchestration, not a ViewModel — the relevant "state" is the process lifecycle and teardown:

- **Processes on the LIVE path:** the daemon (background) + the foreground Gradle run. **No** local relay
  and **no** fixture watcher (both `DETERMINISTIC`/local-only). So `RELAY_PID` and `WATCHER_PID` stay
  empty; only `DAEMON_PID` is set.
- **Teardown:** the existing `cleanup()` EXIT/INT/TERM trap kills whatever PIDs are set and `wait`s. The
  empty-guard pattern means LIVE reuses it unchanged. On success it `rm -rf`s `WORK_DIR`; on failure it
  keeps `WORK_DIR` (holds `daemon.log`, `pair.out`; **no** `relay.log` on LIVE). `ISO_HOME` empty on LIVE
  → its cleanup line no-ops.
- **Isolation lifetime:** `~/.pyry/e2e-live/` (identity + `devices.json` + `conversations.json`) persists
  across runs by design — a subsequent LIVE run reuses the same test identity. It is never removed by
  `cleanup()` (it lives under the real HOME, not `WORK_DIR`). That is acceptable (a stable test identity),
  but note it in the doc so the operator knows where it lives.

## Error handling

Every LIVE failure mode degrades to a **test timeout with logs kept in `WORK_DIR`** — no data loss, no
production impact. The failure classes and where they surface:

| Failure | Symptom | Note |
| --- | --- | --- |
| Live relay down / stale deploy | phone `awaitConnected()` times out (30s) | **This is the signal the mode exists to catch** — a red run here is a real finding, not a harness bug. |
| TLS handshake fails (cert not trusted by emulator) | phone connect fails → timeout | Production relay has a valid public cert; the emulator trusts standard CAs. First-run assumption. |
| Daemon `/v1/server` registration rejected by relay | daemon loops or exits; `kill -0` catches early exit, else phone never connects → timeout | Relay is content-blind + token-gated; a normal pyry daemon (as the operator's production ones do). First-run assumption. |
| Base URL vs `/v1/server` mistake | daemon 404 dial-retry loop | Prevented by construction — the asymmetry is derived in-script from `LIVE_RELAY_HOST`. |
| Emulator has no outbound internet / DNS | phone connect fails → timeout | AVD has NAT'd internet by default; corporate proxy/firewall could block. First-run assumption. |
| `LIVE=1` + `DETERMINISTIC=1` | early `die` with a clear message | Guard in config. |

The daemon start (`:346`) already surfaces an early daemon exit by dumping `daemon.log` and `die`ing.

## Testing strategy

- **Verifiable in the worktree (no infra):** `bash -n scripts/e2e-emulator.sh` (syntax) and, if available,
  `shellcheck scripts/e2e-emulator.sh` (the repo has no CI gate on it, so run locally). Confirm the default
  and `DETERMINISTIC` paths are byte-unchanged (diff the non-LIVE branches).
- **No new Kotlin / instrumented tests.** AC #1 forbids new scenarios; the phone side is unchanged. The
  existing `InteractiveStreamE2ETest` ping method is the test body, reused as-is.
- **Operator-run (needs the operator's infra — emulator + real claude auth + live relay):**
  `LIVE=1 bash scripts/e2e-emulator.sh` → green ping round-trip over the live relay, one claude turn. This
  is the acceptance run; it cannot be exercised in the developer's worktree (matches how rung 3/4 are
  "operator-run" in the existing Verification-status section).
- **Doc:** `docs/e2e-interactive-stream.md` gains a "Live mode (rung 3, live relay)" section — what it
  runs (ping only), prerequisites (real claude auth, no relay binary, emulator internet), cost (one real
  claude turn, subscription-covered), the instance-name isolation, and the first-run assumptions below.
  Update the ladder rung-3 bullet (`:20-25`) to mention the live variant. Mirror the existing "How to run"
  / "Deterministic mode" structure; keep it tight.

## Open questions (first-run assumptions to record in the doc)

- **Live relay accepts the test daemon's `/v1/server` registration.** Expected — it is a normal pyry daemon
  dialing the production relay, exactly as the operator's real instances do, and the relay is content-blind
  + token-gated. Confirm on first run; if the relay allowlists server identities, that is a finding to
  surface (do not weaken isolation to work around it).
- **Emulator outbound internet + DNS + TLS.** The managed AVD must resolve `pyrycode-relay.pyryco.de` and
  complete a TLS handshake against a system-trusted CA. Confirm; if a proxy/firewall blocks it, that is
  environmental, not a harness bug.
- **`~/.pyry/e2e-live/` is fully separate from production.** Confirm on first run that `pyry pair` and the
  daemon under `-pyry-name=e2e-live` touch only `~/.pyry/e2e-live/` and never read/write the production
  instance's `devices.json`. (This is the AC #3 guarantee; verifying the name-isolation once is cheap.)
- **Optional fail-fast reachability probe.** A `curl`/`nc` check that the `wss` relay is reachable before
  booting the daemon would fail faster than the 30s connect timeout. Deferred as a nicety (no observed
  need; the round-trip already surfaces a down relay). Left as an operator follow-up, not built here.
- **Stale KDoc in `E2eTestApplication.kt:29`** ("dials the host relay at `ws://10.0.2.2:<port>`") describes
  only the loopback case. It is not wrong for the default path. Optional one-line touch if the developer
  is already in the file; not a required deliverable (keep scope minimal).

## Security review

**Verdict: PASS.** The security-relevant acceptance criteria (TLS-only transport, no insecure flag,
isolated identity, no hardcoded secret) are enforced **structurally** by the design, not left to
convention.

**Trust boundaries.** The live relay is a public, internet-exposed, **content-blind, token-gated** router
— it sees only Noise-encrypted frames and routes phone↔server by the paired token + server id. The
untrusted surface is the relay itself; confidentiality/authentication of message content is provided
**end-to-end** by the Noise_IK session layer (`data/crypto/` + `data/network/`), independent of the relay.
This ticket does not change that boundary — it points the existing paired path at the production relay.
The only new exposure is a **throwaway test daemon** registered on the live relay; the ticket's own risk
assessment ("the relay is content-blind and token-gated, so a test daemon on the live relay is bounded
exposure") is the accepted mitigation, and the daemon serves only the single paired test phone.

**Transport security (AC #2 — the centerpiece).** Both legs ride TLS: the daemon dials
`wss://…/v1/server` and the phone dials `wss://…` + `/v1/client`. `PYRY_ALLOW_INSECURE_RELAY` is **never**
set on the LIVE path — enforced by a **dedicated daemon branch** (Design § 6) that omits the flag the
loopback branch carries, not by a runtime conditional that could be fumbled. There is **no `ws://`
anywhere on the LIVE path** — the config branch (§ 1) derives both URLs with the `wss` scheme from a
single host var, so no plaintext scheme can leak in. OkHttp validates the relay's TLS certificate against
the emulator's system trust store (confirmed: `OkHttpRelayTransport` treats the origin opaquely and lets
OkHttp negotiate TLS for `wss` — `connect_dialsV1ClientVerbatimWithAllHeaders`). File:line enforcing the
invariant: `scripts/e2e-emulator.sh` daemon-start LIVE arm (new) + config branch (new).

**Credential / secret handling.** No secret is hardcoded (confirmed `E2eTestApplication.kt:31,50-56` and
its KDoc — the token + static pubkey are minted at run time by `pyry pair`). They flow to the app as
Gradle instrumentation arguments (`-Pandroid.testInstrumentationRunnerArguments.token=…`), visible in
`ps`/Gradle console output. This is the **same exposure class as the existing rung-3/rung-4 path** — LIVE
introduces **no new** secret-handling mechanism; the only change is the token now authorizes a live-relay
pairing rather than a loopback one. Exposure is bounded: the token authorizes only the `e2e-live` test
daemon's Noise session (not any production instance), and it is a local-process-only leak on the
operator's own machine. `pair.out` (token + keys) is written under a `mktemp -d` `WORK_DIR`, removed on
success and kept only on failure for debugging — unchanged existing behavior. **No change required.**

**Isolation (AC #3).** Isolation is by pyry **instance name** (`-pyry-name=e2e-live`), which namespaces
identity + `devices.json` + `conversations.json` under `~/.pyry/e2e-live/`. Production instances use
different names, so no production identity or `devices.json` is read or written — structurally, not by
care. The design deliberately runs under the **real `$HOME`** (not an isolated HOME) because real claude
needs the operator's `~/.claude` auth (Design § 2); the boundary is the pyry-name, and the daemon writes
only under `~/.pyry/e2e-live/` and claude's own `~/.claude/projects/` transcripts. First-run confirmation
of the name-isolation is listed under Open questions.

**Injection / input handling.** No new untrusted input is parsed. The base64url pairing-payload parser is
unchanged. `LIVE_RELAY_HOST` is operator-supplied and trusted (defaults to the production host); it is
interpolated into a URL string, never `eval`'d — no shell-injection surface beyond what already exists.

**File operations.** No untrusted path reaches a filesystem sink. The isolated `~/.pyry/e2e-live/` path is
name-derived from the operator-controlled `PYRY_NAME`. N/A beyond the isolation finding above.

No unmitigated finding survives the walk → **PASS**.
