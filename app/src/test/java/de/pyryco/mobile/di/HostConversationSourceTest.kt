package de.pyryco.mobile.di

import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostConversationSourceTest {
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled
    private val logs = mutableListOf<String>()

    @Before
    fun captureLogs() {
        RelayLog.enabled = true
        RelayLog.sink = { _, _, text -> logs += text }
    }

    @After
    fun restoreLogs() {
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
    }

    @Test
    fun silentHostsDoNotBlockRowsOrStatusAndIdentityAndOrderRemainVerbatim() =
        runTest {
            val a = Host("Host")
            val b = Host("host")
            val hosts = MutableStateFlow(listOf(a.entry, b.entry))
            val source = HostConversationSource(hosts, { null }, StandardTestDispatcher(testScheduler))
            try {
                runCurrent()
                assertEquals(listOf("Host", "host"), source.snapshots.value.map { it.serverId })
                assertTrue(source.snapshots.value.all { it.channels.isEmpty() && it.chats.isEmpty() })
                val channel = row("same", promoted = true)
                val older = row("older", promoted = true)
                val chat = row("chat", promoted = false)
                a.repo.emit(listOf(older, chat, channel, row("archived", true).copy(archived = true), chat.copy(archived = true)))
                b.status.value = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Handshaking)
                runCurrent()
                assertEquals(listOf(older, channel), source.snapshots.value[0].channels)
                assertSame(
                    channel,
                    source.snapshots.value[0]
                        .channels
                        .last(),
                )
                assertEquals(listOf(chat), source.snapshots.value[0].chats)
                assertEquals(b.status.value, source.snapshots.value[1].connectionStatus)
                assertTrue(
                    source.snapshots.value[1]
                        .channels
                        .isEmpty(),
                )
                b.repo.emit(listOf(channel))
                assertEquals(listOf(channel), source.snapshots.value[1].channels)
                assertEquals(listOf(older, channel), source.snapshots.value[0].channels)
                a.repo.emit(emptyList())
                assertEquals(listOf(channel), source.snapshots.value[1].channels)
                assertEquals(
                    " /Projects/Case/../Case ",
                    source.snapshots.value[1]
                        .channels
                        .single()
                        .cwd,
                )
                assertEquals(listOf(ConversationFilter.All), a.repo.filters)
                assertEquals(listOf(ConversationFilter.All), b.repo.filters)
                assertTrue(logs.none { it.contains("Host") || it.contains("Projects") || it.contains("same") })
            } finally {
                source.dispose()
            }
        }

    @Test
    fun cacheOutlivesSubscribersAndReconnectSilenceButEmptyReplyReplacesRows() =
        runTest {
            val a = Host("A")
            val hosts = MutableStateFlow(listOf(a.entry))
            val source = HostConversationSource(hosts, { a.repositories.value }, StandardTestDispatcher(testScheduler))
            try {
                runCurrent()
                a.repo.emit(listOf(row("old", true)))
                assertEquals(
                    "old",
                    source.snapshots
                        .first()
                        .single()
                        .channels
                        .single()
                        .id,
                )
                assertEquals(1, a.repo.collectors)
                a.repositories.value = null
                runCurrent()
                assertNull(source.repositoryFor("A"))
                assertEquals(0, a.repo.collectors)
                assertEquals(
                    "old",
                    source.snapshots.value
                        .single()
                        .channels
                        .single()
                        .id,
                )
                a.repositories.value = ManualRepository(fail = true)
                runCurrent()
                assertEquals("event=host_snapshot_list_failed", logs.last())
                val fresh = ManualRepository()
                a.repositories.value = fresh
                runCurrent()
                assertSame(fresh, source.repositoryFor("A"))
                a.repo.emit(listOf(row("retired", true)))
                assertEquals(
                    "old",
                    source.snapshots.value
                        .single()
                        .channels
                        .single()
                        .id,
                )
                fresh.emit(emptyList())
                assertTrue(
                    source.snapshots.value
                        .single()
                        .channels
                        .isEmpty(),
                )
                fresh.emit(listOf(row("new", false)))
                assertEquals(
                    "new",
                    source.snapshots
                        .first()
                        .single()
                        .chats
                        .single()
                        .id,
                )
            } finally {
                source.dispose()
            }
        }

    @Test
    fun renameRetainsCollectorsReplacementAndRemovalRejectLateUpdatesAndDisposeClearsCache() =
        runTest {
            val a = Host("A")
            val hosts = MutableStateFlow(listOf(a.entry))
            val source = HostConversationSource(hosts, { a.repositories.value }, StandardTestDispatcher(testScheduler))
            runCurrent()
            a.repo.emit(listOf(row("old", true)))
            hosts.value = listOf(a.entry.copy(displayName = "Local name"))
            runCurrent()
            assertEquals(
                "Local name",
                source.snapshots.value
                    .single()
                    .displayName,
            )
            assertEquals(
                "old",
                source.snapshots.value
                    .single()
                    .channels
                    .single()
                    .id,
            )
            assertEquals(1, a.repo.collectors)
            assertEquals(1, a.repo.filters.size)
            val replacement = Host("A")
            hosts.value = listOf(replacement.entry)
            a.repo.emit(listOf(row("late-before-reconcile", true)))
            assertEquals(
                "old",
                source.snapshots.value
                    .single()
                    .channels
                    .single()
                    .id,
            )
            runCurrent()
            assertEquals(0, a.repo.collectors)
            assertEquals(0, a.status.subscriptionCount.value)
            assertTrue(
                source.snapshots.value
                    .single()
                    .channels
                    .isEmpty(),
            )
            replacement.repo.emit(listOf(row("replacement", true)))
            a.repo.emit(listOf(row("retired-generation", true)))
            assertEquals(
                "replacement",
                source.snapshots.value
                    .single()
                    .channels
                    .single()
                    .id,
            )
            hosts.value = emptyList()
            runCurrent()
            replacement.repo.emit(listOf(row("removed", true)))
            assertTrue(source.snapshots.value.isEmpty())
            assertEquals(0, replacement.repo.collectors)
            hosts.value = listOf(replacement.entry)
            runCurrent()
            replacement.repo.emit(listOf(row("again", true)))
            source.dispose()
            source.dispose()
            runCurrent()
            replacement.repo.emit(listOf(row("after-disposal", true)))
            assertTrue(source.snapshots.value.isEmpty())
            assertNull(source.repositoryFor("A"))
            assertEquals(0, replacement.repo.collectors)
            assertEquals(0, hosts.subscriptionCount.value)
            assertEquals(0, replacement.status.subscriptionCount.value)
        }

    @Test
    fun demoUsesOnlyExistingFakeAndDisposesItsCache() =
        runTest {
            val fake = FakeConversationRepository()
            val source = HostConversationSource.demo(fake, StandardTestDispatcher(testScheduler))
            runCurrent()
            assertEquals(
                "demo",
                source.snapshots.value
                    .single()
                    .serverId,
            )
            assertEquals(
                "Demo",
                source.snapshots.value
                    .single()
                    .displayName,
            )
            assertSame(fake, source.repositoryFor("demo"))
            assertNull(source.repositoryFor("Demo"))
            assertNull(source.repositoryFor("saved-host"))
            assertEquals(
                fake.observeConversations(ConversationFilter.Channels).first(),
                source.snapshots.value
                    .single()
                    .channels,
            )
            assertEquals(
                fake.observeConversations(ConversationFilter.Discussions).first(),
                source.snapshots.value
                    .single()
                    .chats,
            )
            source.dispose()
            runCurrent()
            assertTrue(source.snapshots.value.isEmpty())
            assertNull(source.repositoryFor("demo"))
        }

    private class Host(
        id: String,
    ) {
        val repo = ManualRepository()
        val repositories = MutableStateFlow<ConversationRepository?>(repo)
        val status = MutableStateFlow(ConnectionStatus(RelayLinkStatus.Idle, PyrycodeLinkStatus.Down))
        val entry = HostConversationConnection(id, null, repositories, status)
    }

    // Deliberately permits a retired collector callback to exercise the generation guard itself.
    private class ManualRepository(
        private val fail: Boolean = false,
    ) : ConversationRepository by FakeConversationRepository() {
        private lateinit var collector: FlowCollector<List<Conversation>>
        var collectors = 0
        val filters = mutableListOf<ConversationFilter>()

        override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
            object : Flow<List<Conversation>> {
                override suspend fun collect(collector: FlowCollector<List<Conversation>>) {
                    this@ManualRepository.collector = collector
                    filters += filter
                    collectors++
                    try {
                        check(!fail) { "sensitive-list-body" }
                        awaitCancellation()
                    } finally {
                        collectors--
                    }
                }
            }

        suspend fun emit(rows: List<Conversation>) = collector.emit(rows)
    }

    private fun row(
        id: String,
        promoted: Boolean,
    ) = Conversation(
        id,
        null,
        " /Projects/Case/../Case ",
        "session",
        emptyList(),
        promoted,
        Instant.parse("2026-09-01T00:00:00Z"),
    )
}
