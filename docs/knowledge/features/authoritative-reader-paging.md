# Authoritative saved-history reader paging (#2334)

The production [repository](caching-conversation-repository.md#older-authoritative-saved-pages-2334)
uses bounded saved pages before the existing daemon history coordinator. The full
[cache contract](conversation-cache-contract.md) retains storage, ownership and writer rules.

## Storage continuation contract

`beforeAuthoritativeWindow` returns up to 200 additional display contributions plus validated
active/parent/placement extras. Content and launch contributions are selected independently;
non-rendering facts consume no row budget. `AuthoritativeDisplayCursor` is distinct from the
raw-fact continuation and daemon coverage, and binds host, conversation, epoch, incarnation,
storage-owned seek bound/generation. Storage retains one content-free record per live cursor
in a weak-key registry,
under its existing mutex. The owned writer's already-required affected-component traversal
compares each old contribution position with the reader's pre-mutation bound, extending that
bound to include the new placement. Reader bounds and the new header generation publish only
after that transaction commits; a rollback preserves the old receipt. It tracks older saved
components even when the boundary survives, nearer predecessors belong to other components,
or the moved group lies beyond a fixed lookahead. No reader-side full scan or payload lookahead
is added, and obsolete cursor records retain no payloads or accumulated identity lists.

A bounded page starts at its registered bound, filters admitted content/launch identities and
mints its continuation at the oldest newly selected contribution. Missing generation custody,
a replacement or a write by another storage instance makes the old cursor unavailable/retry;
it cannot authorize daemon work. The production migration-aware cache has one shared instance
for readers and writers. Fresh reopen selects current committed placement and mints a new
cursor. Exact owner, epoch, incarnation and observation lifetime fences remain in force.

One reader movement or explicit retry admits one local page independently of newest/catch-up;
completion/reconnect never walks history automatically. Failed local reads keep held content and
position for retry. Only successful local exhaustion forwards that demand to the existing
coordinator at its certified continuous daemon boundary. Local exhaustion changes no
`olderExists`, daemon continuation, checkpoint or coverage. Fragmented saved groups remain
reachable without making holes continuous. Subsequent owned saves retain unloaded facts and
durable certification; explicit retirement retains its existing checkpoint-zero/null-coverage
contract. Numeric read results include skipped admitted metadata candidates and
extras; selected payload/decode work stays bounded to the requested page and required closure.
[Paging and reopen evidence](#native-and-deterministic-proof)
records exact test/source identities and preserves historical failures.

## Native and deterministic proof

`SavedThreadFirstDrawDeviceTest.databaseSavedThreads_upwardDemandAndRetry_offlineAndHeldNewest_coldReopen`
uses the real production negotiated destination and on-disk 18000-display-row fixture. Upward
pointer demand and explicit retry must bring exact older text inside the clear message viewport
on offline and held-newest openings and cold reopens. Each arm admits one successful 200-row
page, retains 400 displayed rows, keeps request counts independent of the held network response
and starts no extra page at rest. Gestures use the measured reading area; Retry is revealed
before the pointer click. The **1000 ms product target**, renderer-specific `firstDrawBoundMs`,
36000-entry/18000-span full-file fixture, delayed projection and slow control remain unchanged.

The production repository/ViewModel disk tests complement the native viewport check. The
18000-row traversal uses 120 saved groups of 150 separated by 250 genuinely absent identities,
plus 1000 non-rendering facts. Every page retains exact raw revisions/clears, display
identities/text/order and numeric query/result/decode counts, unchanged certification and daemon
continuation, owned-write preservation and fresh reopen under colliding ids on another host.
Retired-boundary tests cover ancestor movement/reranking and mixed standalone successors.
`survivingBoundaryRecoversMovedOlderComponents` adds the surviving-boundary case with 600
children and 200 standalone rows, plus 220 standalone rows whose nearest 20 predecessors do
not move and 420 standalone rows that put the moved group beyond the entire lookahead. Each
requires offline demand, exact ordering, no extra requests at rest, bounded
metadata/payload work, unchanged certification and complete fresh-reopen traversal.

The initial manual test-first run at `b7837a9df` executed **1 / 0 passed / 1 failed / 0 errors/skipped**
on Linux/build-fractal-2: no older children reached the selected projection. Retained XML is
`/tmp/manual-2334/red/TEST-de.pyryco.mobile.data.cache.AuthoritativeReaderPagingTest.xml` in the
mobile dispatcher container; this temporary location is copied into durable ticket evidence
before handoff. A passing repaired run and complete configured acceptance must be recorded
separately below; the previous review's green gates do not accept the new manual candidate.

The bounded-predecessor repair then passed the 200/220-standalone variants but failed the
new 420-standalone regression at `4b4c0ee11`: **1 executed / 0 passed / 1 failed / 0 errors/skipped**.
The intervening full paging run at `51e7a87bc` recorded **12 executed / 11 passed / 1 failed**,
with its remaining failure in the exact metadata-cost assertion after adding predecessor
lookups. Neither result is counted as acceptance of the final committed-bound design.

### Manual committed-bound paging result

The tested tree committed as **`e189309842e7d4eeea08fbea36f65c1efb1a14a9`** ran
`AuthoritativeReaderPagingTest`: **12 executed / 12 passed / 0 failures/errors/skips** on
Linux/build-fractal-2. Each of the 89 fragmented-history pages returned/decoded **200 payloads**,
with **200 metadata results / 6 queries / 1 evidence result and decode**, including the first
page after an owned write. Every 200/220/420-standalone movement variant returned/decoded
**201 payloads** (200 selected children plus their required parent), with **200 metadata
results / 7 queries / 1 evidence result and decode** on each of its three child pages.
Exact offline content/order, certification, request independence and full reopen walks passed.

The passing XML and all raw page records are retained at
`/work/Projects/pyrycode-mobile-agents/logs/evidence/2334/manual-20261021/paging/`.
Historical red XML/logs remain alongside it in `red/`, `frontier-red/` and `predecessor-run/`.
The final main merge adds unused test-fixture helpers and a separate screen regression;
production paging code is identical. Remaining writer/native checks and independent complete
configured acceptance must be recorded separately before final acceptance is claimed.
