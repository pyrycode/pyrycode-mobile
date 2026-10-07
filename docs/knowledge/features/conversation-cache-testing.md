# Conversation cache — Testing

Split from [Conversation cache](conversation-cache.md); this topic retains the section anchors.

## Testing

[`FileConversationCacheTest.kt`](../../../app/src/test/java/de/pyryco/mobile/data/cache/FileConversationCacheTest.kt)
is a plain JVM unit test on a `TemporaryFolder` — no Robolectric, no device — because the
implementation takes a root `File` rather than a `Context`. 20 cases cover field-for-field and
null-field round-trips, write-order preservation, every graceful-empty-read shape (never
written, truncated, non-JSON, unsupported version, unparseable timestamp, duplicate id), host
and conversation isolation, replace semantics, the traversal-shaped server id, absence of any
identifier from the filesystem namespace, absence of credential-shaped strings and identifiers
from stored bytes, absence of identifiers from `RelayLog` output across both success and
failure paths, and a coded, causeless exception on a forced write failure.

**Every persistence assertion reads through a second `FileConversationCache` constructed over
the same root**, not the instance that wrote it. Nothing in this implementation caches
in-memory, so a same-instance read happens to be honest today — but it proves nothing about "a
fresh process that did not write it," and the first in-memory field anyone adds would make a
same-instance assertion pass while lying. This is the JVM-root equivalent of the paired-server
store's ["recreating the store over the same DataStore can pass on cached preferences"](paired-server-store.md#testing)
lesson. `RelayLog.sink`/`enabled` are captured and restored around each test, the same idiom
`SettingsViewModelTest` uses, which is what makes the "no identifier reaches a log" assertion
real rather than a convention.

[`FileConversationCacheThreadTest.kt`](../../../app/src/test/java/de/pyryco/mobile/data/cache/FileConversationCacheThreadTest.kt)
(#797) is a sibling file rather than an extension of the test above, with the same second-instance
and log-capture discipline. 23 cases cover: a field-for-field round trip (message, a tool call in
both a settled and a failed status, a boundary with and without `workspaceCwd`); that unrecognized,
streaming and running-tool rows are dropped on write; per-conversation and per-host isolation;
whole-thread replace on a second write; that two boundaries sharing a session pair but differing in
`occurredAt` read back rather than being rejected (#775, a double idle-evict of the same session);
every graceful-empty-read shape including a duplicate message id, a duplicate boundary identity
(the full triple, not the pair) and a running-status document (left on disk, unrepaired);
`removeConversation` removing one thread and leaving siblings; removal still working against host
metadata that was never written; `removeHost` removing every thread under it and no other host's;
no conversation id in a path or a log line, success or failure; and a coded, causeless exception on
a forced write failure. #1353 added: a field-for-field round trip of a banner (both levels,
`truncated` true), a compaction row (null and non-null token counts, `manual` true/false) and a
refusal, in their original positions among a message and a boundary; a refusal with and a refusal
without a fallback model on one shared `occurredAt` both reading back, since their key also carries
`fallbackModel != null`; a literal pre-#1353 document holding only a message and a boundary still
reading; a document repeating a banner, compaction or refusal key, or a row setting two kinds, each
reading empty; the 100000 limit on `MAX_CACHED_THREAD_ROWS`; and that `cacheableThreadRows`, called
directly rather than through a 100k-row file write, keeps the newest rows past the limit.

[`FileConversationCacheReadPositionTest.kt`](../../../app/src/test/java/de/pyryco/mobile/data/cache/FileConversationCacheReadPositionTest.kt)
(#877) is a third sibling file, same second-instance and log-capture discipline. It covers a
round trip; host isolation, with `removeHost` dropping a host's positions and leaving a sibling
host's untouched; `removeConversation` dropping exactly one conversation's entry; and every
graceful-empty-read shape (never written, corrupt, duplicate conversation id) reading back empty
rather than throwing or repairing.

[`FileConversationCacheThreadTest.kt`](../../../app/src/test/java/de/pyryco/mobile/data/cache/FileConversationCacheThreadTest.kt)
also carries #1354's position cases: a round trip through a fresh instance; a row write keeping an
existing position and a position write keeping existing rows; a thread fed only live rows storing
none; a literal pre-#1354 document (rows, no `history` key) still reading as rows with no
position; `null` clearing a stored position; a write trimmed at `MAX_CACHED_THREAD_ROWS` dropping
a legacy position (coverage-bearing positions reset backwards state and invalidate lost claims);
`removeConversation` and `removeHost` removing it along with the rows; and no cursor
reaching `RelayLog` on either the read or the write-side re-read failure path.

[`CachingConversationRepositoryTest.kt`](../../../app/src/test/java/de/pyryco/mobile/data/repository/CachingConversationRepositoryTest.kt)
proves the same rules through the wrapper, against a real `FileConversationCache`: a position
written through `writeHistoryPosition` survives a concurrent row write from `observeMessages` and
reads back under the wrapper's own `serverId`; a deleted conversation's position write is skipped,
the same guard the row writer already has; and — added after the first verifier pass flagged that
the production path never exercised the trim rule — a drawn thread trimmed at
`MAX_CACHED_THREAD_ROWS` drops a legacy saved position when written through
`CachingConversationRepository.observeMessages` itself, not only through a direct call to the
cache. See [Caching conversation repository](caching-conversation-repository.md) for why
`observeMessages` now hands `writeThread` the untrimmed drawn rows rather than pre-trimming them.

`HistoryDurabilityTest` (#1832) covers fresh-instance coverage/high-water and cursor restore,
partial fills, conservative legacy migration, cache exclusions, failed row writes and interruption
between row/state writes. `HistoryCacheReworkTest` exercises complete production paths for durable
order, saved cursor/stop trim reset and deletion during suspended writers; see
[wrapper testing](caching-conversation-repository.md#testing).

Real app process death is proved on a device: [#1833](https://github.com/pyrycode/pyrycode-mobile/issues/1833)'s
external force-stop proof stops the app without clearing data, and a post made meanwhile appears
once after relaunch without scrolling. See the [evidence](../../e2e-interactive-stream.md#verification-status).

The two `HistoryCacheReworkTest` trim-reset cases reconcile 100,001 rows, write the production
100,000-row retained file and restore through fresh cache/repository instances. Keep that full-cap
fixture and its saved cursor/stop, coverage and reader-demand assertions. Its real disk work can
exceed `runTest`'s default one-minute wall deadline under load without a suspended ViewModel
handoff; [#1842](https://github.com/pyrycode/pyrycode-mobile/pull/1915#issuecomment-6038977116)
scopes a bounded three-minute timeout to `trimmingResetsWalk`, rather than shrinking the fixture
or changing cache production behavior.

No Compose UI test and no emulator scenario for the original two storage families — #796's
restored conversation rows
and #797's restored thread rows both draw through the same composables a live row does, so the
screen needs no cache-specific coverage. See [dependency injection §
Testing](dependency-injection.md#testing) for `HostConversationSourceTest`'s restore/live-race
cases and for why every other instrumented container built from `appModule` overrides this binding
with a shared `InertConversationCache` fake rather than supplying a real `Context`. See [Caching
conversation repository § Testing](caching-conversation-repository.md#testing) for the restore
merge's own unit coverage. Live continuity across a real reconnect — a loaded conversation staying
readable while its host link is cut and reconciling a peer's turn once the link is restored — is
proven live by [#850](https://github.com/pyrycode/pyrycode-mobile/issues/850)
(`InteractiveStreamE2ETest.interactiveTurn_offlineRead_reconcilesPeerTurnOnReconnect`), not this
cache's or the wrapper's own unit suite.
