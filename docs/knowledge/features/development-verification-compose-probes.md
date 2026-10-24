# Development verification — Compose evidence probes

Part of [Compose evidence](development-verification-compose-evidence.md). The section below
moved verbatim from that overview on 2026-10-24 to keep it under the 50000-byte cap.

## Probe the evidence itself

Supported-flow fixtures must supply the ownership that makes their negatives meaningful
(#2366). Admit both conversations and current producing sessions before testing foreign
session errors, reset or turn state; otherwise rejecting an unknown producer can conceal an
isolation bug. A valid foreign suggestion must stay hidden here and appear from held state
when its own conversation opens. Replacement settings and suggestions require fresh current
producer delivery; old readings cannot become current through a session switch. Preserve
explicit producer tags and retired-session negatives.

The authoritative harness route probe requires rendered text, an actual catch-up request,
completed coverage and no legacy content request. Its revision probe requires one newest
row after duplicate and older facts; local-send acceptance checks device plus message identity.
The destination-only parser-gap tap must fire on a supplied notice without creating another
subscription or query. Passive replacement checks reject retired evidence even if connection
identity is reused. See the [checked 18-client / 91-method inventory](../../e2e-interactive-stream.md#authoritative-fixture-migration-evidence-2366)
and [recorded observation contract](../../recorded-tests.md#passive-thread-assertion-evidence-2317).

Keep the three missing/reordered/duplicate delta-sequence negatives as literal presentation
inputs until runtime removal #2367. Authoritative items carry no client-reduced delta metadata;
inventing it would let those probes test a path the screen does not use. Their literals never
enter the repository, ViewModel or rendered screen. Positive reply order and duplicate-row
negatives instead use admitted `ThreadEvidence` facts and scoped presentation identities.

An injection or sanitizer test must forge the exact line shape its reader matches.
For the unrecognized-row sentinel, the hostile value must include the report's row
prefix, not only a newline. Assert the total report line count as an independent
fence. Temporarily widen the sanitizer, run the test and read the resulting failure
before restoring the guard. This proves the assertion can fail instead of merely
proving that the current implementation passes.
