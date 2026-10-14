# #2145 — Retain private harness diagnostics before disposal

## Files read

- `scripts/e2e-emulator.sh`: `cleanup`, executable selection and revision capture; owns child shutdown, temporary data and isolated credential homes.
- `scripts/e2e_fault_evidence.py`: `retain` and `digest`; extend the landed #2137 facility and preserve `E2E_FAULT_EVIDENCE_DIR`.
- `scripts/test_e2e_emulator_cleanup.py`: `EmulatorCleanupTest`; extracted EXIT-trap regression seam.
- `scripts/test_e2e_fault_evidence.py`: `FaultEvidenceTest`; existing allowlist, permissions, hashes and frozen PATH selection coverage.
- `docs/knowledge/features/development-verification-emulator-evidence.md`: Device-gate artifact retention requires durable private evidence; XML/phone copying is a separate boundary.
- `../pyrycode/docs/protocol-mobile.md`: Security model; diagnostics do not change the wire or authorization boundary.

## Context

Passing harness cleanup destroys diagnostics; #2137 introduced private retention but its default excludes detached/main runs and failures can replace an original failure result. Managed cleanup must retain diagnostics on every outcome and always remove credential homes. No decision record is needed.

Overlap: #1766, #2052, #2088, #2090, #2097, #2104, #2114 and #2121 touch the shell harness. Their changes are scenario admission/selection or additive capture/shutdown steps; extend the existing retention block locally without changing their facilities.

## Design

Extend `retain` with original exit status and optional managed-root validation. All managed branches select a destination beneath `AGENTS_REPO_PATH/logs/evidence`: keep numbered feature destinations `<ticket>/harness`, otherwise use `harness`. An explicit `E2E_FAULT_EVIDENCE_DIR` remains authoritative. An absent/unusable managed agents root fails retention instead of creating a replacement root. Unmanaged runs without a configured destination preserve their existing policy.

Retain only the six existing constant log names into a unique `fault-` directory. Open source directories and files without following symlinks, confirm regular files from opened descriptors, stream-copy and verify SHA-256. Traverse destination components with directory descriptors and no symlink following; create missing evidence ancestors with 0700, enforce 0700 on the destination/run and 0600 on files at creation. Never enumerate/archive the source tree. `inputs.json` records validated Mobile and binary revisions, binary SHA-256, configured source revision (explicit unavailable when unknown), original exit status, completion and availability per allowlisted file. `SHA256SUMS` records verified logs and metadata hashes. Missing/non-regular logs are unavailable, not a retention error; copy/hash/write errors mean partial/failed retention.

The helper prints only an escaped destination and fixed completion/availability fields. Exceptions never print their payload or traceback. No log content, credential path, pairing output or transcript is printed or published.

## State and concurrency model

No Android state, flows or jobs change. `cleanup` captures the original exit code once, sends the existing owned-child termination signals and waits/reaps before calling retention. Retention runs synchronously before disposal. Its failure cannot skip home removal or overwrite an original nonzero result. Original success removes WORK_DIR even if retention fails; original failure retains WORK_DIR under the existing policy. Managed isolated homes are removed on every outcome; unmanaged failure keeps its existing isolated-home policy. Managed cleanup does not call the separate bypass-transcript copier.

## State transitions and identity reuse

| Event | Contract and regression |
| --- | --- |
| Successful EXIT | `test_managed_success_retains_verified_private_allowlist`: logs retained before WORK_DIR disappears; hashes/modes/identities/availability verified. |
| Original failed EXIT | `test_managed_failure_preserves_result_and_removes_homes`: exact nonzero result and original status in metadata; all isolated homes removed. |
| Destination absent/unusable or copy failure | `test_retention_failure_preserves_original_failure_and_disposes_homes`, helper partial-copy regression: success becomes nonzero, original failure unchanged, homes removed, fixed failure/partial report. |
| Optional log absent, symlink or non-regular | Success allowlist test and helper exclusion test: availability explicit, forbidden content never copied. |
| Owned child final writes on termination | `test_children_are_reaped_before_capture_on_every_outcome`: retained log includes final write; no child remains or can write afterwards. |
| Repeated run / detached main comparison | `test_managed_detached_runs_have_unique_destinations`: fresh unique private directory independent of branch identity. |
| Unmanaged EXIT | Existing result-policy test: no new retention requirement; old success/failure disposal retained. |

## Error handling

Missing identities are `unavailable`; never infer binary identity from source HEAD. Retention errors use fixed failed/partial labels and nonzero helper status. Per-file failures are recorded if metadata storage is still possible. The shell preserves original nonzero status exactly and promotes original zero to 1 on configured retention failure. Removal is attempted independently of retention success.

## Testing strategy

Write credential-free Python regressions first and observe red against the landed behavior. Run `scripts.test_e2e_emulator_cleanup` and `scripts.test_e2e_fault_evidence`, including existing executable-selection coverage, with explicit executed/failed/skipped counts. Synthetic owned children exercise real termination, final writes and reaping without an emulator/account. Inject file-copy/digest failures in helper tests to prove partial reporting and no exception disclosure; reject destination/source symlink traversal. Run shell syntax, focused Python suites, lint, assembleDebug and required forced Spotless/compile pre-verification. No live-Claude, scripted-stream or device scenario changes; synthetic cleanup evidence proves this contract, with no new live evidence required by the ticket.

## Open Questions

None.

## Documentation handoff

Pending documentation stage: update `docs/knowledge/features/development-verification-emulator-evidence.md`, under “Device-gate artifact retention”, with destination/default and override, allowlist, manifest/availability semantics, cleanup/failure lifecycle, standard-launcher use, private access and safe publication limits. Do not require a custom process-inspection wrapper or new live-Claude evidence. XML and phone-log retention remain separate.

## Security review

**Verdict:** PASS

- [Trust boundaries] `retain` is the sole capture boundary; names are constants and opened descriptors must identify regular non-symlink files. Raw bytes remain private.
- [Tokens] No credential storage is introduced. Managed cleanup removes ISO_HOME, BYPASS_HOME and ANSWER_HOME regardless of retention; pairing/token files and credential homes never enter the allowlist.
- [Files/storage] Resolved finding: check-then-open in the old helper could follow a replaced symlink. Use no-follow descriptor opens and directory-relative destination operations; private modes apply at creation. A configured agents root must already exist. No encryption claim is made: logs are private diagnostic data on the trusted agents volume, not a secret store.
- [Android attack surface] No Android components, providers, intents, UI or backup policy change.
- [Cryptography] SHA-256 uses Python hashlib; hashes prove copy integrity, not authenticity. No keys, nonces or Noise changes.
- [Network/I/O] No network path changes. File reads stream in bounded chunks; regular-file checks prevent FIFO blocking; git metadata errors are unavailable with captured output.
- [Errors/logging] Resolved finding: a helper traceback could expose arbitrary path/error text. CLI reports only escaped destination and fixed completion/availability summaries, including partial failure; no raw logs or exception payloads.
- [Concurrency] Existing owned-child kill/wait remains before capture. Unique directories prevent run collision; no asynchronous retention job can outlive cleanup. Interrupted partial writes are never claimed complete.
- [Threat model] Relay denial/reordering and hostile frames remain covered by existing protocol defenses; private capture does not trust their contents. Rooted-device token theft and UI screenshot/accessibility leakage are unaffected. An attacker already controlling the agents account can read its private diagnostics; permissions protect other accounts, not a compromised owner.

**Reviewer:** builder (self-review per `builder/security-review.md`)
**Date:** 2026-10-14

## Revisions

- 2026-10-14: Descriptor traversal encountered macOS's root-owned `/var` and `/tmp` aliases. Resolve only root-owned symlinks at the filesystem's top level, then pin the canonical directory; caller-owned evidence ancestors and all source log symlinks remain rejected. Directory/FIFO log entries are checked with `fstat` before wrapping a file reader and are unavailable rather than copy failures.
- 2026-10-14: An existing 0755 managed evidence ancestor failed the permission regression. Enforce 0700 through the managed evidence subtree on descriptor traversal, preserving unrelated agents directories. Partial CLI output uses `retention=partial`, and metadata/manifest read-back verification completes before the completion summary.
