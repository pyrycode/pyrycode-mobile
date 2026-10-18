# Caching conversation repository — wiring

Split from [Caching conversation repository](caching-conversation-repository.md).

## Wiring — under `decorateRepository`, not in it

`ThreadDestinationFactory.repository(serverId, bundle)` (`di/AppModule.kt`) wraps the
`StableConversationRepository` it builds in `CachingConversationRepository` **before** handing the
result to the `decorateRepository` hook:

```kotlin
decorateRepository(
    if (cache != null && serverId.isNotEmpty()) CachingConversationRepository(stable, cache, serverId, attachments) else stable,
)
```

`attachments` (#899) follows `cache` through the same conditional — a blank `serverId` gets neither, so
retrieved files, like restored rows, are never filed under the empty id's namespace.

`E2eTestApplication` replaces `decorateRepository` with `::TappingConversationRepository` for its
instrumented harness. Because the cache sits *underneath* that hook rather than inside it, the
tapping decorator still observes the restored, merged thread — a cache wired the other way round
would be invisible to exactly the harness [#850](https://github.com/pyrycode/pyrycode-mobile/issues/850)
uses to prove offline reading and reconnect reconciliation live. The demo branch
(`FakeConversationRepository`) never reaches this code path and gets no
cache. A blank `serverId` (a malformed route) also gets no cache, so restored rows are never filed
under the empty id's namespace.

`hostConversationModule(useRelay, decorateRepository)` passes `cache = if (useRelay) get() else
null` into `ThreadDestinationFactory`'s constructor — the same `useRelay` gate
[`HostConversationSource.relay(get(), cache = get())`](dependency-injection-host-conversation-source.md#restore-from-the-on-disk-cache-796)
already follows for the host-list restore. No new Koin binding was added for this ticket; both
consumers resolve the single `ConversationCache` #796 bound.

`ThreadDestinationFactory` gained the matching `attachments: AttachmentStore? = null` constructor
param (#899), gated `if (useRelay) get() else null` the same way as `cache`, resolving the app's single
`AttachmentStore` bound in `AppModule` over `File(androidContext().noBackupFilesDir, "attachments")`.
Because that `get()` runs inside the `single { ThreadDestinationFactory(...) }` block, any Koin
container that resolves a `ThreadDestinationFactory` under `useRelay = true` now needs an
`AttachmentStore` binding too — see [Dependency injection §
AttachmentStore](dependency-injection.md#attachmentstore-and-context-free-thread-destination-containers-899)
for the four test containers that needed the same `InertConversationCache`-shaped override.

Three `RelayConnectionFactoryTest` containers build a thread destination under `useRelay = true`
with no `androidContext()`. Resolving the cache in `ThreadDestinationFactory` meant those
containers now need the same `single<ConversationCache> { InertConversationCache }` override their
sibling container already carried for #796 — before this change only `HostConversationSource`'s
containers needed it. Any future container built the same way inherits this requirement.
