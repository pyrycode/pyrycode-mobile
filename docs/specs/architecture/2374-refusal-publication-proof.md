# #2374: refusal publication diagnosis and Linux proof

## Scope and decision

The refusal switch-back timeout on PR #2371 exposed a producer-side publication
boundary. Core [#3725](https://github.com/pyrycode/pyrycode/issues/3725) owns that
repair; [PR #3726](https://github.com/pyrycode/pyrycode/pull/3726) merged it as
`3d5379ec4b778c569c27b7639a2ae495b32cee0d`. This Mobile change records the
diagnosis and downstream proof. No new Mobile production defect is established.

Keep the existing test, fixture, UI and live-only recovery contract unchanged.
The smallest deliverable is this evidence record and a documentation handoff.
The earlier unknown-cause investigation is superseded by the controlled Core
regression. Original scheduling uncertainty remains; neither a focused pass nor
the Core repair proves suite-order independence.

## Retained failure and causal evidence

The historical full-run refusal method executed once and failed its original
90-second combined reply/control condition. The phone then had a producer
continuity reset: the daemon logged `baseline_required` and close code 4414,
followed by history restoration without a live refusal offer. The raw history
segments were not retained, so the original publication batching is unknown.
This trace fits the demonstrated defect but does not reconstruct every event.

The retained Mobile diagnostic fed the actual fold and daemon publisher in
coalesced and separated schedules. Coalesced publication passed; separated
publication retired the peer and lost the live refusal. Core strengthened this
into a real producer/reader/store/publisher regression across physical history
segments. Its committed evidence was recounted for this continuation:

| Evidence | Executed | Passed | Failed | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Historical Mobile full-run refusal method | 1 | 0 | 1 | 0 |
| Historical candidate exact comparison | 1 | 1 | 0 | 0 |
| Historical pinned-main exact comparison | 1 | 1 | 0 | 0 |
| Core committed publication regression before repair | 2 | 1 | 1 | 0 |
| Core committed publication regression after repair | 2 | 2 | 0 | 0 |

Both historical Mobile comparisons used Core
`647a4e7526f581f78d422195cb22b3e9b979e767`; they are not post-repair acceptance.
All 25 entries in the retained Mobile diagnosis `SHA256SUMS` matched on
2026-10-24. The persistent evidence root on Fractal is
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2374/`;
the original diagnosis is `builder-linux-20261021/`.

Core's `operatorMessageHistory` puts the committed acceptance reference in the
delivery message before publication. `Fold.deliverSend` preserves that permanent
item identity and removes the intermediate written outcome consistently from
inline and source-backed folds. Genuine-gap retirement remains in force.
The [final independent Core review](https://github.com/pyrycode/pyrycode/pull/3726#issuecomment-6322486428)
confirms the later written-prefix correction, failing-before/passing-after
prefix evidence, and both publication schedules on the reviewed source.
The committed publication logs report one live refusal, no retirement and no
duplicate delivery in each repaired schedule. Core's offline proof does not
substitute for the phone/transport check below.

## Focused downstream verification

Run `python3 scripts/android-test-gate.py scripted refusal` on Linux from the
preserved `feature/2374` checkout, through normal admission. The selected method
is `DeterministicInteractiveStreamE2ETest.interactiveTurn_seededChannel_refusalSwitchBackRestoresOriginalModel`.
It keeps the 90000 ms reply/control deadline, reply assertion, exactly one
clickable Switch back control, disappearance after tapping, and fresh unheld
session settings returning `haiku`. Restored history never arms a new offer.
The managed harness owns isolated daemon, relay and device lifetimes and uses
fakeclaude; zero real Claude turns are required.

On 2026-10-24 the command exited 0: **1 executed, 1 passed, 0 failed, 0 skipped**.
The fresh device XML identifies `pixel2Api33Atd` and records 5.221 seconds for
the selected method. Admission waited 208 seconds before the run; that waiting
is not test execution. App and test APK assembly also completed successfully.

| Identity | Recorded value |
| --- | --- |
| Mobile test source | `585e5734e234d399250e51a78d6124605e0ba3b8` |
| Core source and embedded binary revision | `64ef0e0fba71a723b7fdc97141cfc144f6355664` |
| Core binary SHA-256 | `9d1b74bbfa1163c2043ecdbb53b321c3574cfaf60af4bdced90a62c447476d66` |
| Relay source and embedded binary revision | `2fd9338dd1662dbd68f3710a33e6a8cc4e7a0bc1` |
| Relay binary SHA-256 | `79c5e14b4b52c10c6b5523ca56f8d3354b2d6bbcb2672be68588f199a1b61973` |
| Device image | Android API 33, Google ATD, x86_64, image revision 1 |
| Emulator | 37.2.12, build 16428233 |

Both Go binaries report `vcs.modified=false`; Core contains the merged repair.
The post-run source heads match the runtime identities. This result-only
documentation update leaves the tested Mobile source, fixture and assertions
unchanged; it does not require repeating the passing device check.

The retained phone trace records `refusal_offer outcome=armed`, followed by
`refusal_switch_back outcome=sent` and `outcome=acked`. The isolated daemon trace
contains no `reason=baseline_required` event. These observations accompany the
native assertions; absence of a log event alone is not the acceptance proof.

The continuation evidence destination is `bounded-20261024/` under the root above:
`gates/scripted-tmij09ce/` contains fresh raw and counted XML, phone log and phase
timestamps; `gates/harness/fault-9729c632652e99621108c502486e58f5/` contains daemon,
relay, fault log and runtime inputs. `result.json` records APK/binary hashes and
source heads; Go build metadata and SDK image/emulator metadata are retained
beside it. The complete `SHA256SUMS` manifest was verified. Scenario-specific
daemon-b, answer and bypass logs were unavailable and are not claimed as evidence.

## Security review

Verdict: PASS for this bounded evidence-only plan (author review).

- Trust boundary: keep live refusal occurrence identity and session ownership;
  restored rows cannot authorize a model write. The existing method and
  `ThreadViewModel.onLiveRefusalEvent` contract remain unchanged.
- Credentials and logs: use the managed fakeclaude harness without fetching
  provider credentials. Keep raw phone, daemon and relay evidence private;
  publish only source identities, hashes, counts and diagnostic event names.
- Storage: retain raw artifacts in the persistent ticket evidence directory,
  with owner-only permissions and a verified hash manifest. No raw capture,
  pairing material, environment dump or message payload enters Git.
- Android, cryptography and network: no manifest, component, Noise, Keystore,
  frame decoder, TLS or production-daemon change. Existing transport and
  native storage remain exercised by the focused managed test.
- Concurrency: the accepted ticket-specific manual hold prevents another builder;
  normal shared test admission and cleanup ownership preserve unrelated work.
  Do not clear a quarantine, override a denial or bypass a held test slot.
- Threat model: malicious relay/frame, rooted-device theft and UI leakage
  protections are unchanged. This deterministic test adds no proof against
  those threats and does not broaden the accepted security model.

## Acceptance and documentation handoff

Independent Mobile review and complete configured gates remain dispatcher-owned
and pending. Author checks do not accept the ticket. Preserve the manual hold
until normal review custody is established; no merge or deployment is authorized
by this continuation.

The documentation stage should update `docs/e2e-interactive-stream.md`, under
**Authoritative fixture migration evidence (#2366)** and **Scenarios (#454)**,
to link this diagnosis and its final counted downstream result while retaining
the original failed run and old-daemon comparison limits. Update
`docs/knowledge/features/model-refusal-row.md`, **Required daemon thread identity**,
with the accepted-delivery publication prerequisite and the distinction between
live refusal delivery and restored history. Keep normal documentation acceptance
pending until that stage actually completes it.
