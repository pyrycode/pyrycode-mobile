# 420 — Rung-3 e2e harness polish: four host fixes into `scripts/e2e-emulator.sh`

**Ticket:** [#420](https://github.com/pyrycode/pyrycode-mobile/issues/420) · size:s · split from #419
**Type:** mechanical port of four operator-proven host fixes. Not a redesign.

## Files to read first

> Bash + Python, not Kotlin — codegraph does not index these. Read the two files directly.

- `scripts/e2e-emulator.sh` (whole file, 156 lines) — the only file you edit. The four fix sites are lines **47** (daemon relay URL), **103** (`pyry pair` invocation), **107–132** (the inline python payload parser), **138** (daemon launch env). Read top-to-bottom once so the four edits land in the right places.
- `docs/e2e-interactive-stream.md:95–111` — the "Assumptions to confirm on first run" section. Context only: it explains why pairing runs before the daemon and what the relay-path asymmetry is. **Do not edit** (shared doc; out of scope for this ticket).
- Memory `pairedserver-relayurl-is-origin.md` / `phase4-wire-v1-path-trap.md` (background, already in your context): the **phone** dials an *origin* (`OkHttpRelayTransport` appends `/v1/client` itself); the **daemon/server** side path is `/v1/server`. AC#3 is the server-side half of that asymmetry.

No other files are touched. No Kotlin, no Gradle, no new files.

## Context

The rung-3 emulator e2e (`scripts/e2e-emulator.sh` + the `app/src/androidTest/.../e2e/` harness + `docs/e2e-interactive-stream.md`) landed on `main` in merge `f887814`. During the 2026-06-17 live session the operator found four host-side fixes the committed script still lacks — without them the script mis-orders the `pyry pair` invocation, can't keep a plaintext local relay, points a v0.14.0-era daemon at a pathless relay URL, and mis-parses the pairing payload. All four are mechanical ports of fixes already proven on the host.

This is the unblocked, pipeline-doable half of #419. The "reach green" half (live-run diagnosis + timeout tuning) is operator-run and tracked in the sibling ticket — **explicitly out of scope here**.

## Design

Four independent edits, all in `scripts/e2e-emulator.sh`. None interact except that AC#3 changes a shared constant that AC#1's pair invocation also reads (noted below — harmless). Keep each edit surgical; do not reflow surrounding code.

### Fix 1 (AC#1) — `pair` subcommand before the global `-pyry-*` flags

Current `pyry` requires the subcommand ahead of global flags. Line 103 currently has `-pyry-name=…` before `pair`. Reorder so `pair` leads.

- **Before:** `… "${PYRY_BIN}" -pyry-name="${PYRY_NAME}" pair --name="${PAIR_NAME}" \`
- **After:**  `… "${PYRY_BIN}" pair -pyry-name="${PYRY_NAME}" --name="${PAIR_NAME}" \`

The leading `PYRY_RELAY_URL="${DAEMON_RELAY_URL}"` env assignment and the trailing `>"${PAIR_OUT}" 2>&1 || { … }` stay exactly as they are. Only the token order between binary and `--name` changes.

### Fix 2 (AC#2) — daemon gets `PYRY_ALLOW_INSECURE_RELAY=1`

The relay listens on plain `ws://` (no TLS, line 86). A daemon refuses a cleartext relay unless this env opt-in is set. Add it to the daemon's env prefix at line 138.

- **Before:** `PYRY_MOBILE_V2=1 PYRY_RELAY_URL="${DAEMON_RELAY_URL}" "${PYRY_BIN}" -pyry-name="${PYRY_NAME}" \`
- **After:**  `PYRY_ALLOW_INSECURE_RELAY=1 PYRY_MOBILE_V2=1 PYRY_RELAY_URL="${DAEMON_RELAY_URL}" "${PYRY_BIN}" -pyry-name="${PYRY_NAME}" \`

The daemon keeps its `-pyry-name` global flag here (it is *not* a subcommand invocation, so AC#1's ordering rule does not apply to this line). Leave it.

### Fix 3 (AC#3) — explicit `/v1/server` path on the daemon's relay URL

v0.14.0-era daemons do **not** append the relay path themselves; newer ones do. Add the path to the `DAEMON_RELAY_URL` constant (line 47) and update the adjacent comment to record why.

- **Before:** `DAEMON_RELAY_URL="ws://127.0.0.1:${PORT}"`
- **After:**  `DAEMON_RELAY_URL="ws://127.0.0.1:${PORT}/v1/server"`

Update the comment block at lines 46–49 so it states: the daemon (server side) needs the explicit `/v1/server` because v0.14.0-era daemons don't append it (newer daemons append it automatically); the phone side stays an **origin** (`ws://10.0.2.2:${PORT}`, no path) because `OkHttpRelayTransport` appends `/v1/client` itself. AC#3 requires the inline "newer daemons append it automatically" note — put it on the `DAEMON_RELAY_URL` line.

**Shared-constant note (deliberate non-change):** `DAEMON_RELAY_URL` is also read by the pair invocation (line 103) via `PYRY_RELAY_URL`. After this edit the minted token's embedded `relay` field carries the server path. The script **already ignores** the token's `relay` (lines 99–101) and the phone dials `PHONE_RELAY_URL` instead, so this is harmless. Do **not** introduce a second variable to keep the pair on a pathless origin — that is scope creep against an unobserved failure mode. One-line edit, single source of truth.

### Fix 4 (AC#4) — parser reads `pair.out` by file path, not via heredoc-plus-stdin

The current invocation (line 107) is `python3 - <"${PAIR_OUT}" <<'PY' … PY`. That redirects fd 0 (stdin) twice — once to the file, once to the heredoc — and the two collide: depending on shell and redirect order, Python ends up reading either the heredoc text or the file *as its program source*, and `sys.stdin` no longer points at `pair.out`. Net effect: the parser never sees the pairing payload and the run fails. (Verified on-host: with `<file <<heredoc`, Python read `pair.out` as its program and raised `SyntaxError` on the QR line.)

Fix by passing the file as **argv** so there is exactly one stdin source (the heredoc = the program) and no redirect collision:

- **Invocation — before:** `PARSED="$(python3 - <"${PAIR_OUT}" <<'PY'`
- **Invocation — after:**  `PARSED="$(python3 - "${PAIR_OUT}" <<'PY'`

  (drop the `<` stdin redirect; add `"${PAIR_OUT}"` as a positional arg)

- **Parser body — before:** `for line in sys.stdin:` (lines 113–123)
- **Parser body — after:** open `sys.argv[1]` and iterate its lines instead:
  - signature shift: read the path from `sys.argv[1]`, `with open(path) as f:` then `for line in f:`
  - everything inside the loop (the `b64url` re-pad, `json.loads`, the `{"server","token","server_static_pubkey"} <= obj.keys()` membership gate, the `payload = obj; break`) is **unchanged**
  - the three `print("SERVER_ID=" + shlex.quote(…))` / `TOKEN` / `SERVER_STATIC_PUBKEY` output lines are **unchanged**

The closing `)" || { cat "${PAIR_OUT}" >&2; die … }`, the `eval "${PARSED}"`, and the non-empty guard (lines 131–133) all stay. This is the **only behaviour-bearing change**; the other three are config/ordering.

**Why argv beats re-adding a clean stdin redirect:** `python3 - <fixture` would put the *fixture* on stdin and leave no stdin for the heredoc program — you'd have to drop the heredoc and ship the parser some other way. argv keeps the parser inline in the heredoc (one self-contained block, no new file) while removing the fd-0 collision entirely. It also makes the parser runnable standalone for verification (below).

## Verification strategy

No Gradle, no emulator, no daemon, no relay. Two checks, both host-local.

### AC#4 — parser against a synthetic fixture (the only behaviour test)

The fixed parser is the inline `python3 - "${PATH}" <<'PY' … PY` block — invoke that exact block standalone with a synthetic `pair.out` (python3 only; no `pyry`). Steps:

1. **Build a synthetic fixture** mirroring real `pyry pair` output: a noise/QR line that is *not* base64url JSON, plus one base64url-**no-pad** line (Go `RawURLEncoding`, per memory `pairing-qr-two-base64-alphabets.md`) that decodes to `{"server","relay","token","server_static_pubkey"}`. Build the encoded line with `base64.urlsafe_b64encode(json.dumps(obj).encode()).decode().rstrip("=")`.
2. **Run the fixed parser block** (the literal heredoc from the script) with the fixture path as argv.
3. **Assert** stdout is exactly three lines: `SERVER_ID=<server>`, `TOKEN=<token>`, `SERVER_STATIC_PUBKEY=<server_static_pubkey>`, each matching the fixture's values, and exit code 0.

Scenarios to cover (bullet form — not test-function bodies):
- **Happy path:** fixture with a leading noise line + one valid no-pad payload → all three fields extracted, exit 0. (This is the AC#4 acceptance check.)
- **No-pad re-padding:** confirm the no-pad encoded line decodes (the parser's `s + "=" * (-len(s) % 4)` re-pads). A no-pad fixture is the faithful mirror of real output; if it decodes, padding is handled.
- **Negative / selectivity (optional, recommended):** a fixture whose only base64url-decodable JSON line is missing `server_static_pubkey` → parser skips it, prints the "could not find … payload line" stderr message, exit 1. Confirms the key-membership gate still works and the parser doesn't false-positive on a partial object.

The architect ran the happy path on-host against `{"server":"srv-e2e","token":"tok-abc123","server_static_pubkey":"key-xyz789", …}` and got exactly the three expected lines — the developer should reproduce it as the AC#4 evidence.

### AC#5 — syntax

`bash -n scripts/e2e-emulator.sh` exits 0 after all four edits. Run it last; it catches any stray quote/heredoc/continuation-line breakage from the edits.

## Out of scope

- **Live end-to-end run** (headless emulator + host daemon + real claude reaching green) — operator-run, sibling "reach green" ticket. Do not attempt; you have no device/daemon/relay.
- **`docs/e2e-interactive-stream.md`** — shared doc, do not edit. The four fixes are script-internal; the doc's prose already describes the intended flow correctly.
- **Extracting the parser to its own file, a `make`/Gradle wrapper, rung-4 determinism** — listed as follow-ups in the doc; not this ticket. Keep the parser inline.
- **Any Kotlin / androidTest change** — the harness sources are unchanged by this ticket.

## Open questions

- **None blocking.** All four fixes are operator-proven and the parser fix is reproduced on-host above. If `bash -n` flags anything after the edits, it is a transcription slip in one of the four sites — re-check the before/after snippets, not the design.
