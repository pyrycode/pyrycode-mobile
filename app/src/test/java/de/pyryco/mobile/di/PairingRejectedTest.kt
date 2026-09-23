package de.pyryco.mobile.di

import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.repository.ConversationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** #843: the thread's Re-pair signal reads its own host's relay leg, by server id, through the registry. */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingRejectedTest {
    private val rejected = ConnectionStatus(RelayLinkStatus.PairingRejected, PyrycodeLinkStatus.Down)
    private val offline = ConnectionStatus(RelayLinkStatus.Offline, PyrycodeLinkStatus.Down)
    private val connected = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)

    private fun host(
        serverId: String,
        status: ConnectionStatus,
    ) = HostConversationConnection(
        serverId = serverId,
        displayName = null,
        repositories = MutableStateFlow<ConversationRepository?>(null),
        status = MutableStateFlow(status),
    )

    private fun observe(
        hosts: MutableStateFlow<List<HostConversationConnection>>,
        serverId: String = "host-a",
    ): List<Boolean> {
        val seen = mutableListOf<Boolean>()
        runTest(UnconfinedTestDispatcher()) {
            backgroundScope.launch { pairingRejected(hosts, serverId).collect { seen += it } }
        }
        return seen
    }

    @Test
    fun ownHostRejected_isTrue() {
        val hosts = MutableStateFlow(listOf(host("host-a", rejected)))
        assertEquals(listOf(true), observe(hosts))
    }

    @Test
    fun anotherHostRejected_isFalse() {
        val hosts = MutableStateFlow(listOf(host("host-a", connected), host("host-b", rejected)))
        assertEquals(listOf(false), observe(hosts))
    }

    @Test
    fun networkLoss_isFalse() {
        val hosts = MutableStateFlow(listOf(host("host-a", offline)))
        assertEquals(listOf(false), observe(hosts))
    }

    @Test
    fun missingHost_isFalse() {
        val hosts = MutableStateFlow(listOf(host("host-b", rejected)))
        assertEquals(listOf(false), observe(hosts))
    }

    @Test
    fun ownHostRecovering_clearsIt() =
        runTest(UnconfinedTestDispatcher()) {
            val a = host("host-a", rejected)
            val hosts = MutableStateFlow(listOf(a))
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch { pairingRejected(hosts, "host-a").collect { seen += it } }
            (a.status as MutableStateFlow).value = connected
            assertEquals(listOf(true, false), seen)
        }

    @Test
    fun aRePairReplacingTheHostEntry_isObserved() =
        runTest(UnconfinedTestDispatcher()) {
            // A successful re-pair changes the saved record, so reconcile closes the old bundle and
            // publishes a new entry: a signal read from the old entry's status would stay rejected.
            val old = host("host-a", rejected)
            val hosts = MutableStateFlow(listOf(old))
            val seen = mutableListOf<Boolean>()
            backgroundScope.launch { pairingRejected(hosts, "host-a").collect { seen += it } }
            hosts.value = listOf(host("host-a", connected))
            // The replaced entry's later updates no longer reach the thread.
            (old.status as MutableStateFlow).value = rejected.copy(pyrycode = PyrycodeLinkStatus.Handshaking)
            assertEquals(listOf(true, false), seen)
        }
}
