# Remote conversation repository — reads and the thread store

## Supported content route

Production content comes only from daemon-built items through `NegotiatedThread`,
`AuthoritativeThreadRoute`, `AuthoritativeThreadQueries` and the host-owned store.
Open/reconnect synchronizes certified history; reader demand requests authoritative
older pages. [Local cache windows](caching-conversation-repository.md) can satisfy
older demand before a server query.

Runtime `observeMessages`, thread snapshots, raw history requests/replies, replay
cursors, hello replay advertisement, resync/backfill and raw event reduction were
removed in #2367. Obsolete message/chunk/content frames cannot populate content,
previews or read proof, or trigger a legacy request, even on an unbound connection.
No empty-success compatibility API replaces them. Dormant serialized cache types
remain only for storage retirement in #2364.

The single inbound collector still handles conversation metadata, request replies,
[session-owned live readings and prompts](remote-conversation-repository-live-stream-and-modals.md),
attachments and [control sends](remote-conversation-repository-control-sends.md).
These paths cannot certify content. List metadata and explicit send previews remain
separate from authoritative thread rendering.

<a id="authoritative-daemon-items-alongside-the-raw-event-projection"></a>

## Authoritative daemon items

`RemoteConversationRepository.authoritativeThreads` is the runtime content store.
Authoritative items
never become raw history events. `observe(host, conversation)` selects one atomic
snapshot containing complete `ThreadItemDto` items, epoch, `completedVersion`,
resumable `checkpoint`, certified `coverage`, `repairNeeded`, `resetNeeded`,
local `generation` and process-memory `pendingSends`. Publication retires pending
feedback only against admitted `user_message` facts with exact string device/client
IDs in the same host/conversation, including hidden terminal items. Missing device
identity and peer client-ID collisions never match. Newest admitted revisions own
lifecycle/content/placement; older, duplicate and partial/overlapping inputs retain
unmatched sends and held queued/lost items. Omission is neither deletion nor loss.
Acknowledgements/errors cannot recreate retired feedback. The coordinator shares
this store across connections; reset preserves unresolved sends while generation
fences retired facts/actions. No automatic resend or cross-host transfer occurs;
provisional feedback is never cached and ends at process death. See
[send registration](remote-conversation-repository-send-create-promote-rename.md#sendmessageconversationid-text--the-first-mutation-346).
Synchronous synchronized mutations publish a single StateFlow map; recollection
reads held facts; accepted-item persistence uses the existing authoritative cache contract. The daemon's
[supplied delivery contract](https://github.com/pyrycode/pyrycode/blob/main/docs/protocol-mobile.md#daemon-thread-updates-v2-supplied-delivery-contract)
and [ADR 042](https://github.com/pyrycode/pyrycode/blob/main/docs/knowledge/decisions/042-daemon-built-thread.md)
remain the authority for wire facts; query shapes and ordering belong to the
daemon’s [Thread query messages](https://github.com/pyrycode/pyrycode/blob/main/docs/protocol-mobile.md#thread-query-messages).

### Supplied route and connection ownership

The defaulted `authoritativeHostId` constructor argument is empty. The three live
thread verbs reach `AuthoritativeThreadRoute` only with a nonempty supplied source
host and both `thread` and `interactive` in the negotiated capability set. Production requires this negotiated support; unsupported hosts receive the
daemon update-required verdict. An absent binding cannot select a raw-content route.

The route reuses the [bounded thread decoder](mobile-protocol-v2-wire-layer.md#supplied-thread-updates-and-continuation-admission).
Only complete live updates apply; pending assembly captures the conversation's
generation without publishing fragments. Repair marks its owner, or all known
snapshots for that host when no owner can be recovered. `AuthoritativeThreadQueries`
uses the existing `ThreadQueryDecoder` through the same inbound collector.

The internal repository seams are `openAuthoritativeThread` (open or synchronize),
`requestOlderAuthoritativeThread`, `cancelAuthoritativeThread`,
`resetAuthoritativeThread` and `observeAuthoritativeThread`. Open, older and reset
return admission booleans, not completion evidence. Observation is a cold projection
of held host/conversation snapshots and sends no request; collecting it again does
not replay raw history. Older admission requires an opened conversation with
certified coverage whose `olderExists` is true. Catch-up reads the latest certified
epoch/checkpoint at dispatch; without a checkpoint it requests version zero.

One connection owns a deduplicated FIFO of conversation/query intents and one
remotely occupied query slot. Every attempt allocates a fresh identity through
`RelayRequests.nextRequestId`. Local cancellation, repair, reset, expiry or rejected
application abandons assembly but cannot release the daemon's slot. Pending intents
wait while discarded replies drain without mutation or certification. Only a
validated correlated terminal success, reset or error proves remote completion;
send refusal never occupies the remote slot. Request ownership lasts for the
connection, so late authoritative errors are consumed by their query owner,
while unrelated errors retain ordinary request/control correlation.

Connection-scoped expiry children tick every second without inbound traffic. A
query has a 30-second overall deadline as well as bounded fragment assembly; after
local abandonment, a further 30-second drain deadline closes admission, discards
pending intents and retires the connection. The default retirement callback closes
a managed pump and cancels its scope; supplied non-managed pumps must provide a
callback that retires their connection. Collector termination cancels expiry and
abandons both decoders, including cancellation before collection starts.

The internal constructor accepts a defaulted shared `AuthoritativeThreadStore` for
connection owners. Reconnect creates fresh route/query decoders and reopens
desired conversations using held certified checkpoints and coverage; neither queued
intents nor fragment assembly crosses connections. Held upserts may include newer
live facts from an interrupted query, but they do not authorize a newer resume
version. Atomic save/restore uses #2149; #1986 adds pending-send reconciliation.
The coordinator forwards this store through `NegotiatedThread` and the stable/cache
wrappers to the destination. Reconnect never creates a second content source.

### Item application and explicit clears

Identity includes host, conversation, epoch and item id. Full items and supplied
batches are atomic upserts: only strictly newer revisions replace held content,
repeated delivery adds no duplicate, and omitted items remain held. Kind is stable
within an epoch. Positive delivered order is the sole sort key; absent order stays
absent, and an explicit zero clear is not a position. Equal-order ties and unordered
items gain no inferred daemon ordering. Parent, session, agent, no-child, active,
shown, required summaries and unknown kind/status/content facts remain as supplied;
neither a roster nor the current session fills missing attribution.

Changes require the held revision to equal `base_rev` and a strictly newer target
revision. Named values replace whole values, including explicit clears; omitted
keys remain unchanged and `content` is never deep-merged. `ThreadItemDto.decodeReplacement`
retains optional null clears in raw JSON while exposing typed optional facts as
unset. Reusing the ordinary full-item decoder here would reject valid clears;
weakening that decoder would loosen wire admission. Append accepts only
`user_message`/`assistant_message` content objects with string text, preserves other
content keys, and adds the exact suffix once, including an empty suffix. A replayed
patch requests repair rather than reporting idempotent success. Missing bases,
invalid known fields, immutable id/kind changes or invalid history numbers leave
completed facts/progress unchanged. Item ids are positive, revision is at least id,
version is at least revision, and history numbers stay below 2^53.

### Repair, checkpoints and reset generations

A complete query batch or newer live version raises applied `completedVersion`
but never certifies covered history or advances the resumable checkpoint. Complete
batches expose their items immediately; ignored older upserts also count as
successfully applied. `requestRepair` stays sticky across later valid live updates.
The direct `establishComplete` seam requires a version at least held progress;
query `certifyQuery` instead uses only the request's captured terminal checkpoint,
after every batch applied successfully. If live progress has overtaken that
captured version, catch-up retains its prior checkpoint and leaves repair needed.
Substituting the newer live number would certify history the query never supplied.

`AuthoritativeThreadCoverage` independently exposes a continuous certified
`[lowerOrder, upperOrder)` range, numeric `continuation` and explicit `olderExists`.
Only validated successful terminal markers publish coverage. Newest windows merge
with held coverage only when ranges overlap or touch; a disjoint window replaces
it rather than filling an unproved gap. An older page uses the certified continuation
as exclusive `before_order` and must end exactly there to extend the held lower
boundary. Captured versions and every coverage number must fit the inclusive
safe-integer domain, zero through 9007199254740991. Rejection preserves previous
certified facts, even for a marker-only reply with no items to validate.

Active items and required parent extras outside a page remain held upserts; they
never expand the certified range or determine its continuation. Lower-bound ties
stay supplied by the daemon. Short and empty successful pages use the explicit
marker and `olderExists`, never item count, to determine completeness. A page can
certify older coverage while leaving the catch-up checkpoint and repair state
untouched, even if its items reveal newer history. Cache and queue consumers must
read coverage independently of returned extras and applied progress.

Malformed/failed batches, incomplete assembly, missing terminal markers, expiry,
send refusal and disconnect preserve prior checkpoint and page boundary. Retries
use fresh request identities and those certified facts, retaining newer held live
revisions absent from retried batches. Provider errors require an explicit retry
rather than an automatic loop. Live repair of an opened conversation queues
catch-up and retires its active query: a missing-base patch can discover a gap
without raising progress, so its older marker must not clear the new repair.

An unexpected epoch clears only the affected host/conversation's items, progress
and coverage, increments generation and enters reset-needed state. Application is
blocked until explicit reset installs an epoch. Reset discards that conversation's
live assembly, clears items/checkpoint/coverage and leaves repair needed; the query
coordinator follows with a zero-version window in the reported epoch. Every
application/certification carries a captured generation, so delayed replies cannot
repopulate reset state even when the epoch string is reused.

Live repair notifications capture affected generations while holding the route
lock, then call the coordinator after releasing it. This preserves coordinator →
route/store lock order and lets the coordinator ignore a notification delayed
across reset. Capturing only an epoch, or looking up generation after unlocking,
would let a reused-epoch notification retire replacement work. Outcomes distinguish
`Applied`, `Ignored`, `RepairNeeded`, `ResetNeeded` and `StaleGeneration`. Snapshot
strings redact contents and diagnostics use static codes.

`AuthoritativeThreadStoreTest` probes identity/revision convergence, isolation,
exact-base patches, optional clears, unknown facts, sticky repair and reset fencing.
`RemoteConversationRepositoryAuthoritativeTest` covers supplied capability gates,
obsolete-frame rejection and live assembly. `AuthoritativeThreadQueriesTest` drives the real
repository/decoder/store with interrupted queries overlapping newer live revisions,
out-of-range parents, numeric ties, empty completion, unsupported coverage, retired
errors and reset/reconnect. Lifecycle fakes must hold the provider slot until
terminal delivery: a pump that always accepts sends cannot prove remote release.
Existing legacy repository tests stay enabled. These are deterministic supplied-input
probes; production activation and combined live proof belong to #1988, not this seam.
See the [query architecture plan](../../specs/architecture/2148-authoritative-thread-queries.md)
and [item-store plan](../../specs/architecture/2043-authoritative-thread-store.md).

<a id="recovering-unavailable-shown-metadata-2467"></a>

### Recovering unavailable shown metadata (#2493)

A replacement repository can certify held completion items without receiving a
new live completion. Its opening `list_conversations` response and its current
completion certification are independent inputs. `RemoteConversationRepository`
records the actual opening request and the host's store generations at dispatch;
only decoded rows correlated to that request can create a recovery observation.
The summary supplies shown/read availability, never an epoch or certification.
Either arrival order can complete the join. Held content, an unsolicited summary,
a foreign request/conversation or a retired repository cannot supply authority.

Once the same generation has current certified completion and the answered summary
still lacks shown metadata, mark that observation consumed and queue one fresh
summary. Keep the same observation identity in its queued state until dispatch,
where the coroutine rechecks current certification and consumes that exact owner.
Withdrawal permanently removes the queued owner; restored terminal items and later
recertification cannot recreate its authority. A genuinely fresh opening observation
remains eligible. The follow-up is not registered as another recovery observation. Repeated
certification, duplicate responses or another unavailable response cannot poll.
Known metadata, including explicit zero, needs no follow-up. The retained
live-completion summary path uses the same bounded recovery. Only returned daemon
facts restore availability; recovery sends no `mark_conversation_read` and cannot
clear unread without daemon-confirmed read coverage.

Reset, replacement and retirement invalidate old authority. Completion withdrawal
also consumes answered observations and fences outstanding opening requests in the
generation that actually published certification. Check withdrawal after each
accepted query frame, before a later frame can recertify; later certification must
not revive the old observation. A newly dispatched opening after reset remains
eligible but waits for its own fresh certificate. Retirement clears connection-local
request and certification records; the scheduled send rechecks current authority.

`NegotiatedThreadActivationTest` covers both arrival orders, duplicate/unavailable
follow-ups, available/zero metadata, request/host/conversation isolation, multiple
subscriptions, reset, retirement, withdrawal and intermediate-frame recertification.
The retained macOS candidate `f5a29a43d9ef3b75f2b443a030a9def052aa711c`
passed 40/40 activation cases, zero failures/errors/skips. Earlier causal and
fixture-defective failures remain separate. Integrated candidate `52e60852785d8fdb3c7003843bb532a10fc2011f`
passed the configured gates under the dispatcher's flaky-test policy, including all
six required replays, but independent review found the queued-authority race above.
A buffered-order regression now selects summary-before-withdrawal, restoration and
recertification before the queued send can run; it also requires fresh-observation
recovery. The earlier green gates did not certify that subsequent source repair.
On repaired `51738b973`, focused macOS activation passed
41/41, including the causal buffered-order probe and fresh-observation recovery;
recorded model-change passed 1/1 and scripted ping passed 2/2, with zero
failures/errors/skips. These scoped author checks retain their own source identity.

Independent review accepted integrated macOS head
`07b3eaadbc8b20ca6a3fbe64987454a5fcbc9cee`. Its configured JVM/build run
executed/passed 5,522 tests, zero failures/errors/skips, including activation
41/41 and both opening arrival orders, the queued withdrawal/recertification probe
and fresh-observation recovery. Read receipts passed 20/20 and tape passed 49/49.
The six required recorded methods passed in the original full UI run; UI reruns
and inherited scripted failures were accepted separately, without converting the
original failures into passes. See the
[revision-bound acceptance and artifact limits](../../recorded-tests.md#accepted-macos-integration-2493) and the
[plan and evidence boundaries](../../specs/architecture/2493-opening-read-metadata.md)
and [recording contracts](../../recorded-tests.md#unavailable-summary-recording-integration-2467).
