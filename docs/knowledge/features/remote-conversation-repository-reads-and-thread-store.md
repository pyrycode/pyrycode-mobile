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

### Recovering unavailable shown metadata (#2467)

An admitted live completion requests a daemon conversation summary independently
of its thread catch-up. That summary can legitimately omit shown metadata while
recovery is in progress. Successful content certification does not make the held
shown/read facts available: without another summary edge, a present list row can
remain Idle after a new durable post. A first list emission proves neither post
delivery nor metadata availability.

`RemoteConversationRepository` retains the latest live-summary request identity
and store generation per conversation on its connection. It registers before
scheduling the send and removes refused requests. The follow-up waits for the
original correlated summary to answer. Once its actual response is applied and a
current completion certificate covers all applied progress, either edge can finish
the join: response after certification, or certification after response. Only
existing thread-supported facts with unavailable shown metadata authorize the
additional summary request. Missing facts and available shown values, including
explicit zero, preserve the original request contract.

The answered trigger is atomically consumed before requesting that one follow-up.
The follow-up is not another live trigger, so a repeated certificate, duplicate
response or still-unavailable reply cannot create a polling loop. Retirement
clears the connection-local ledger; request dispatch and certification retain
their current-owner/generation fences. Neither certification nor request admission
advances shown/read values. Only daemon-supplied metadata can restore availability
or confirm a read; cached content, local sight and pending writes grant no authority.
See [host attention](dependency-injection-host-conversation-source.md#attention-state-877).

`NegotiatedThreadActivationTest` drives this through the real host source:
`unreadPostRefreshesUnavailableSummaryAfterRecoveryOnReplacement` keeps the row
present and Idle with unavailable shown metadata, then accepts shown/read 13/9 as
Unread and peer read 13 as Idle without a phone read. The available-summary case,
both response orders and repeated-certification probes protect request count and
one-time consumption. Reversed facts and retired-owner replies cannot manufacture
confirmation. Corrected causal-red evidence and the separate native final-read,
post/unread and peer-idle proofs are [recorded here](../../recorded-tests.md#unavailable-summary-recording-integration-2467).
