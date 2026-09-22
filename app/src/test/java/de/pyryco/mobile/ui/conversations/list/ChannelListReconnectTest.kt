package de.pyryco.mobile.ui.conversations.list

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.di.HostConversationConnection
import de.pyryco.mobile.di.HostConversationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The host row's reconnect control (#840), from the view model down to the source's retry seam.
 *
 * Its own file rather than a case in `HostChannelListViewModelTest`: that suite's fixture builds the
 * source positionally and is being edited by a concurrent branch, and this needs only a recording
 * `retry` and two hosts with rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelListReconnectTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val retried = mutableListOf<String>()
    private val offline = ConnectionStatus(RelayLinkStatus.Offline, PyrycodeLinkStatus.Down)

    private fun host(id: String): HostConversationConnection =
        HostConversationConnection(
            id,
            "Local $id",
            MutableStateFlow<ConversationRepository?>(FakeConversationRepository()),
            MutableStateFlow(offline),
        )

    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled
    private val logs = mutableListOf<String>()

    private val source by lazy {
        HostConversationSource(
            MutableStateFlow(listOf(host("a"), host("b"))),
            { null },
            dispatcher,
            retry = { retried += it },
        )
    }

    private val vm by lazy {
        ChannelListViewModel(
            AppPreferences(
                object : DataStore<Preferences> {
                    override val data = flowOf(emptyPreferences())

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = error("unused")
                },
            ),
            source,
            UnusedStore,
        )
    }

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After
    fun teardown() {
        vm.viewModelScope.cancel()
        source.dispose()
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
        Dispatchers.resetMain()
    }

    @Test
    fun reconnectRetriesOnlyTheTappedHostAndEveryHostKeepsItsRows() =
        runTest(dispatcher) {
            backgroundScope.launch { vm.hostState.collect {} }
            val before = vm.hostState.value.hosts
            assertEquals(listOf("a", "b"), before.map { it.host.serverId })
            assertTrue(before.all { it.host.channels.isNotEmpty() })

            vm.reconnectHost("b")

            assertEquals(listOf("b"), retried)
            assertTrue("event=tree_host_reconnect_tapped" in logs)
            val after = vm.hostState.value.hosts
            assertEquals(before.map { it.host.serverId }, after.map { it.host.serverId })
            assertEquals(before.map { it.host.channels }, after.map { it.host.channels })
            assertEquals(before.map { it.host.chats }, after.map { it.host.chats })
        }

    private object UnusedStore : PairedServerCollectionStore {
        override suspend fun load(): PairedServer? = error("unused")

        override suspend fun save(record: PairedServer) = error("unused")

        override suspend fun list(): List<PairedServerEntry> = error("unused")

        override suspend fun loadById(serverId: String): PairedServerEntry? = error("unused")

        override suspend fun setDisplayName(
            serverId: String,
            displayName: String?,
        ) = error("unused")

        override suspend fun remove(serverId: String) = error("unused")
    }
}
