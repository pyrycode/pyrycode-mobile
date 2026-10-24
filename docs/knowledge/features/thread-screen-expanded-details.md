# Thread screen — expanded details

Bounded expanded-row behavior for the [thread screen](thread-screen.md).

## Missing tool bodies on expansion (#2388)

Only visible, individually expanded Tool use rows demand missing details, including an agent-launch body where the
existing tool presentation needs it. Closed rows, hidden descendants and opening a
grouped run alone issue no body reads. Complete content for the exact held identity
and revision renders immediately, including unchanged reopen and offline reuse.
Fetched bodies remain in the host snapshot's in-memory ID/revision map; existing
complete-fact disk persistence is unchanged. A process restart cannot restore that
fetched-body map. Hosts without expansion support retain complete delivery.

The expanded row uses its existing body/code surface for Loading, loaded contents,
a generic failure with explicit Retry, or offline missing-details text. An
explicitly empty input/output object becomes loaded only after validated data and
its separate matching success marker. Refusal, missing/partial data, send failure,
timeout and cancellation cannot become empty success. Failure is sticky across
recomposition; Retry applies only to the current still-demanded body.

The bound screen consumer follows the current presentation and producing connection
through the ViewModel and negotiated repository. Equal active/queued targets
coalesce. Collapse, scrolling out of visibility and destination/lifecycle retirement
withdraw demand. A current binding must withdraw even while frame pacing holds an
older presentation: the lane intersects stale targets with that consumer's already
admitted keys. Stale snapshots cannot add targets or authorize Retry. Checking
snapshot freshness before withdrawals would let a collapsed queued body start when
another body completes. Other consumers keep their own demand; abandoned reads
still drain before lane reuse.

Loading and body completion grant neither history coverage nor read sight. The
existing viewport, exact rendered revision and certified-checkpoint requirements
still apply after contents render. See [connection-owned body queries](remote-conversation-repository.md#missing-tool-body-queries-2388)
and [focused evidence for the parent compact handoff](../../e2e-interactive-stream.md#missing-tool-body-evidence-2388).

## Retained expansion capacity (#2390)

Bodies and child pages share inclusive
16,777,216-byte/1000-summary limits per host across conversations, open and closed.
Costs count serialized UTF-8 body objects, original raw summaries and cursors once
per entry. Loaded expanded demand and descendant ancestry stay protected across
consumers. Closed entries use cross-kind LRU; retained reopen refreshes use without
refetch, while recomposition does not. Atomic capacity rejection keeps prior
children/cursor and shows sticky inline Retry. Closing demand can free capacity
for explicit Retry; evicted reopen reads again. See [retention rules](remote-conversation-repository.md#retained-expansion-budget-2390).

## Direct agent children (#2389)

A visible, individually expanded agent demands
its first missing page of direct-child summaries through the same owned lane.
Nested agents demand only their own children when individually expanded. Opening
a group exposes loaded member summaries with bodies closed; recomposition and
hidden descendants initiate neither speculative child pages nor closed-body reads.
Body demand remains independent. Unchanged retained pages reopen without a read,
including offline, within the current in-memory snapshot.

Loading, loaded children, authoritative empty, explicit Retry and offline missing
data appear inside the expanded parent. Load more follows that parent's displayed
descendants, uses the existing inline history-control styling and requests one
certified continuation per tap; it is separate from thread-history paging. A short
page does not mean complete. Only validated separate success with `more: false`
certifies the enumeration, including marker-only empty success. Failures remain
sticky across recomposition. Cursor rejection offers Retry from the first page for
the current owner, retaining old rows until a validated replacement succeeds.

Child demand uses independent consumer targets and the current presentation,
connection and resumed destination binding. Collapse and retirement withdraw even
before the Loading presentation reaches a display frame; held presentations may
withdraw prior targets but cannot add demand or authorize Retry/Load more. Other
consumers retain their demand; abandoned requests drain before lane reuse.

Certified order-zero children stay under their parent, never in the root
queued/lost tail. A full fact without order cannot borrow placement from an older
summary. Summary `shown: false` participates in cursor accounting but renders no
row. Its inert headline stays independent of tool input and survives body loading.
Summary conversion supplies no read sight or history coverage. Parent identity and ancestry govern descendant reachability in either page
arrival order. Same-owner stale rows remain visible during refresh, but their
retained page cannot certify completion, continuation or read sight. See the [page admission rules](remote-conversation-repository.md#direct-child-pages-2389)
and [counted evidence](../../e2e-interactive-stream.md#direct-child-page-evidence-2389).

## Invalidation, Retry and reopen (#2391)

A newly accepted source observation invalidates fetched details when the source
revision changes or applicable ancestor freshness changes or is missing. An
unchanged ancestor revision does not prove its descendants unchanged. Known-zero
freshness is a value; missing freshness proves nothing. Only currently visible,
individually expanded bodies/pages refresh. Closed or hidden descendants perform
neither reads nor full display conversion. Loading publications and recomposition
are not source observations, so unknown freshness cannot create a refresh loop.

Within the same owner, retained stale bodies and descendants stay visible beside
Loading, sticky inline Retry after failure, or offline status. Offline stale
contents remain distinguishable from absent details. Invalidated pages provide
neither authoritative empty nor usable Load more. Invalidated or refused
continuations restart at page one; validated replacement atomically replaces the
old enumeration, including authoritative empty. Failure keeps old rows visible.
Retry uses current demand; recomposition never retries it. A successful replacement
remains usable until another source invalidation. Unchanged proven-valid details
reopen without refetch, including offline.

Collapse, hidden ancestry, background, navigation, reset, reconnect, deletion and
host/connection replacement retire obsolete demand. Consumers contribute a union:
one withdrawal cannot cancel another open row. Held presentations can withdraw
previous targets but cannot add targets or authorize Retry/Load more. Retired
callbacks cannot publish or clear a replacement owner's Retry, even with colliding
host, conversation, item and request IDs. Abandoned reads discard staged data and
drain the remote slot before new work under the existing timeout/retirement rules.

Complete source descendants under complete source ancestry remain usable at their
exact revisions when expansion support is absent. Fetched bodies and descendants
behind omitted/summary ancestry still require freshness; previous compact
invalidation floors remain binding. Retained display never substitutes for exact
rendered revisions and existing certified read-sight checks. Refresh changes no
root/history coverage, recovery checkpoint, join watermark or read mark.

See [validity and capture fences](remote-conversation-repository.md#expanded-detail-validity-2391),
[stale retention protection](conversation-cache.md#the-contract), and
[counted Linux evidence and the #2393 handoff](../../e2e-interactive-stream.md#expanded-detail-validity-evidence-2391).
