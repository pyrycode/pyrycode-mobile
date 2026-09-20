package de.pyryco.mobile.di

import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Application store binding: notify connection ownership only after persistence succeeds. */
class ObservablePairedServerStore(
    private val delegate: PairedServerCollectionStore,
) : PairedServerCollectionStore by delegate {
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()

    override suspend fun save(record: PairedServer) {
        delegate.save(record)
        changes.update { it + 1 }
    }

    override suspend fun remove(serverId: String) {
        delegate.remove(serverId)
        changes.update { it + 1 }
    }

    override suspend fun setDisplayName(
        serverId: String,
        displayName: String?,
    ) {
        delegate.setDisplayName(serverId, displayName)
        changes.update { it + 1 }
    }
}
