package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.di.AttentionAlert
import de.pyryco.mobile.di.ConversationAttention
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import de.pyryco.mobile.ui.conversations.thread.ThreadAttention
import de.pyryco.mobile.ui.conversations.thread.observeThreadAttention
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AttentionHostIsolationTest {
    private val previousSink = RelayLog.sink

    @Before fun silenceAndroidLog() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun restoreLog() {
        RelayLog.sink = previousSink
    }

    @Test fun inheritedPermissionOnAnotherHost_cannotReplaceBsSingleWaitingWithCount() =
        runTest {
            val store = Store()
            val original = store.list()
            val snapshots = MutableStateFlow<List<HostConversationSnapshot>>(emptyList())
            val attention = MutableStateFlow<Map<String, Map<String, ConversationAttention>>>(emptyMap())
            var shown: ThreadAttention? = null

            // The fake registry publishes only saved hosts; each daemon retains its held prompt.
            fun publishHosts() {
                snapshots.value =
                    store.entries.map {
                        HostConversationSnapshot(
                            it.record.serverId,
                            it.displayName,
                            ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected),
                        )
                    }
                attention.value =
                    store.entries.associate {
                        it.record.serverId to mapOf("B" to ConversationAttention.WaitingForAnswer)
                    }
            }
            publishHosts()
            backgroundScope.launch {
                observeThreadAttention(
                    HostConversationTarget("answer", "A"),
                    snapshots,
                    attention,
                    MutableSharedFlow<AttentionAlert>(),
                ).collect { shown = it }
            }
            runCurrent()
            assertEquals("old drive renders a count, so the exact B label never appears", ThreadAttention(2), shown)

            withAttentionHostIsolation(store, "answer") {
                publishHosts()
                runCurrent()
                assertEquals(ThreadAttention(1, HostConversationTarget("answer", "B"), null), shown)
                assertEquals(listOf("answer"), store.list().map { it.record.serverId })
            }
            assertEquals(original, store.list())
        }

    @Test fun assertionFailure_restoresCredentialsNamesAndOrder_andPreservesFailure() =
        runTest {
            val store = Store()
            val original = store.list()
            val failure = AssertionError("scenario failed")
            val caught =
                runCatching {
                    withAttentionHostIsolation(store, "answer") {
                        assertEquals(listOf("answer"), store.list().map { it.record.serverId })
                        throw failure
                    }
                }.exceptionOrNull()
            assertSame(failure, caught)
            assertEquals(original, store.list())
        }

    @Test fun cancellation_restoresAllPairingsBeforeScopeFinishes() =
        runTest {
            val store = Store()
            val original = store.list()
            val isolated = CompletableDeferred<Unit>()
            val job =
                launch {
                    withAttentionHostIsolation(store, "answer") {
                        isolated.complete(Unit)
                        awaitCancellation()
                    }
                }
            isolated.await()
            assertEquals(listOf("answer"), store.list().map { it.record.serverId })
            job.cancelAndJoin()
            assertEquals(original, store.list())
        }

    @Test fun failedIsolationAfterRemoval_restoresSnapshotWithoutStartingScenario() =
        runTest {
            val store = Store()
            val original = store.list()
            val failure = IllegalStateException("remove failed")
            store.afterRemove = { throw failure }
            var started = false
            val caught = runCatching { withAttentionHostIsolation(store, "answer") { started = true } }.exceptionOrNull()
            assertSame(failure, caught)
            assertEquals(false, started)
            assertEquals(original, store.list())
        }

    @Test fun originalHostSelectionAndNullableNames_areRestoredOnSuccess() =
        runTest {
            val store = Store()
            store.entries += Store.entry("last", "Last").copy(displayName = null)
            val original = store.list()
            val result =
                withAttentionHostIsolation(store, "answer") {
                    assertEquals(listOf("answer"), store.list().map { it.record.serverId })
                    "complete"
                }
            assertEquals("complete", result)
            assertEquals(original, store.list())
            assertEquals(original.last().record, store.load())
        }

    @Test fun restorationFailure_preservesScenarioFailure_andStillRestoresLaterHosts() =
        runTest {
            val store = Store()
            val originalAnswer = store.loadById("answer")
            val failure = AssertionError("scenario failed")
            val restoreFailure = IllegalStateException("restore failed")
            val caught =
                runCatching {
                    withAttentionHostIsolation(store, "answer") {
                        store.afterRemove = { throw restoreFailure }
                        throw failure
                    }
                }.exceptionOrNull()
            assertSame(failure, caught)
            assertEquals(listOf(restoreFailure), caught?.suppressed?.toList())
            assertEquals(originalAnswer, store.loadById("answer"))
        }

    private class Store : PairedServerCollectionStore {
        val entries = mutableListOf(entry("primary", "Original primary"), entry("answer", "Answer"))
        var afterRemove: (() -> Unit)? = null

        override suspend fun list() = entries.toList()

        override suspend fun load() = entries.lastOrNull()?.record

        override suspend fun loadById(serverId: String) = entries.firstOrNull { it.record.serverId == serverId }

        override suspend fun remove(serverId: String) {
            entries.removeAll { it.record.serverId == serverId }
            val callback = afterRemove
            afterRemove = null
            callback?.invoke()
        }

        override suspend fun save(record: PairedServer) {
            remove(record.serverId)
            entries += PairedServerEntry(record)
        }

        override suspend fun setDisplayName(
            serverId: String,
            displayName: String?,
        ) {
            val index = entries.indexOfFirst { it.record.serverId == serverId }
            if (index >= 0) entries[index] = entries[index].copy(displayName = displayName)
        }

        companion object {
            fun entry(
                id: String,
                name: String,
            ) = PairedServerEntry(PairedServer(id, "test-token-$id", "test-relay", "test-key-$id"), name)
        }
    }
}
