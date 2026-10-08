package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Pairing scope for the single-target attention scenario; other hosts may retain suite prompts. */
internal suspend fun <T> withAttentionHostIsolation(
    store: PairedServerCollectionStore,
    serverId: String,
    block: suspend () -> T,
): T {
    val original = store.readSnapshot().getOrThrow()
    check(original.any { it.record.serverId == serverId }) { "attention scenario host is not paired" }
    var failure: Throwable? = null
    try {
        original.filter { it.record.serverId != serverId }.forEach { store.remove(it.record.serverId) }
        return block()
    } catch (e: Throwable) {
        failure = e
        throw e
    } finally {
        withContext(NonCancellable) {
            // Re-saving in original order also restores the store's compatibility selection.
            var restoreFailure: Throwable? = null
            original.forEach { entry ->
                try {
                    store.save(entry.record)
                    store.setDisplayName(entry.record.serverId, entry.displayName)
                } catch (e: Throwable) {
                    val previous = restoreFailure
                    if (previous == null) restoreFailure = e else previous.addSuppressed(e)
                }
            }
            restoreFailure?.let { e ->
                val scenarioFailure = failure
                if (scenarioFailure == null) throw e
                scenarioFailure.addSuppressed(e)
            }
        }
    }
}
