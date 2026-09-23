package de.pyryco.mobile.di

import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.ui.conversations.thread.ComposerDraftStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Application store binding: notify connection ownership only after persistence succeeds.
 *
 * [onHostRemoved] runs with the removed id after a successful [remove] (#790), for app state keyed by
 * host id that [revision] alone cannot express: the revision is a bare counter that bumps on every
 * mutation and names no id, so an observer cannot tell a removal from a rename, nor which host moved.
 * It receives **only** the `serverId` — never the [PairedServer] that was removed, which also carries
 * the pairing token and the server static key, and which this hook's consumers have no business
 * holding.
 *
 * Required rather than defaulted, for the reason #789 made `ThreadViewModel`'s draft store required: a
 * forgotten binding is the one failure mode that leaves every test green while production does
 * nothing. A caller with nothing to clean up says so with `{}`.
 *
 * **It must not throw, and must suspend rather than block.** It runs inside [remove], after the
 * pairing is already gone, so an exception would surface at `HostEditorController.confirmUnpair` as a
 * failed unpair on a removal that in fact succeeded, and a blocking call would stall the store on the
 * dispatcher the delegate completed on. It is awaited (#798): when [remove] returns, the removed host's
 * app state is gone, so the unpair confirmation closes only after its cleanup. Its production binding
 * is [forgetRemovedHost].
 *
 * Keep this a plain class: a `data class` here would render the bound hook's receiver — and therefore
 * every live composer draft — into any crash trace that prints the store.
 */
class ObservablePairedServerStore(
    private val delegate: PairedServerCollectionStore,
    private val onHostRemoved: suspend (String) -> Unit,
) : PairedServerCollectionStore by delegate {
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()

    override suspend fun save(record: PairedServer) {
        delegate.save(record)
        changes.update { it + 1 }
    }

    override suspend fun remove(serverId: String) {
        delegate.remove(serverId)
        // The bump precedes the hook: it is the custody notification this class exists to deliver —
        // it is what closes the removed host's connection bundle — so no hook may delay or skip it.
        changes.update { it + 1 }
        onHostRemoved(serverId)
    }

    override suspend fun setDisplayName(
        serverId: String,
        displayName: String?,
    ) {
        delegate.setDisplayName(serverId, displayName)
        changes.update { it + 1 }
    }
}

/**
 * The production [ObservablePairedServerStore.onHostRemoved]: a removed pairing takes its host's unsent
 * composer text (#790) and its cached conversation content (#798) with it.
 *
 * Named rather than written inline in `appModule` so the JVM unpair test binds this exact function —
 * a restated lambda would stay green while production forgot a step.
 *
 * [cache] is [Lazy] so resolving the store never constructs the cache: its root is a Context directory
 * the store itself does not need, and JVM tests resolve this binding without a Context.
 *
 * The cache removal runs `NonCancellable`: the pairing is already gone, so a view model cleared
 * mid-cleanup must not leave the forgotten machine's conversation names and rows on disk. A failed
 * removal is logged and not surfaced, for the reason `confirmUnpair` gives for the workspace clear —
 * reporting it would claim the host is still paired. Never logs the id or the cache's message.
 */
internal fun forgetRemovedHost(
    drafts: ComposerDraftStore,
    cache: Lazy<ConversationCache>,
): suspend (String) -> Unit =
    { serverId ->
        drafts.clearHost(serverId)
        withContext(NonCancellable) { cache.value.removeHost(serverId) }
            .onFailure { RelayLog.d { "event=host_cache_remove_failed" } }
    }
