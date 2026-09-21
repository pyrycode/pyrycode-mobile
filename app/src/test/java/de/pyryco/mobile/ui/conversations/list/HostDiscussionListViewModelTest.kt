package de.pyryco.mobile.ui.conversations.list

import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.StableConversationRepository
import de.pyryco.mobile.di.HostConversationConnection
import de.pyryco.mobile.di.HostConversationSource
import de.pyryco.mobile.di.appModule
import de.pyryco.mobile.di.conversationRepositoryModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.koin.core.KoinApplication

@OptIn(ExperimentalCoroutinesApi::class)
class HostDiscussionListViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val fixtures = mutableListOf<Fixture>()
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled
    private val logs = mutableListOf<String>()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After
    fun teardown() {
        fixtures.forEach {
            it.vm.viewModelScope.cancel()
            it.source.dispose()
        }
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
        Dispatchers.resetMain()
    }

    @Test
    fun projectionPreservesCompleteChatsHostOrderMetadataAndCachedRows() =
        runTest(dispatcher) {
            val f = fixture()
            val chats = listOf(row("z"), row("same"), row("a"), row("fourth"))
            f.a.repo.rows.value = chats + row("channel").copy(isPromoted = true) + row("archived").copy(archived = true)
            runCurrent()
            assertEquals(
                listOf("Host", "host"),
                f.vm.hostState.value.hosts
                    .map { it.serverId },
            )
            assertEquals(
                "Local Host",
                f.vm.hostState.value.hosts[0]
                    .displayName,
            )
            assertEquals(
                chats,
                f.vm.hostState.value.hosts[0]
                    .chats,
            )
            assertTrue(
                f.vm.hostState.value.hosts[1]
                    .chats
                    .isEmpty(),
            )
            val collision = row("same", "B-private")
            f.b.repo.rows.value = listOf(collision)
            f.a.available = false
            f.a.live.value = null
            f.a.status.value = ConnectionStatus(RelayLinkStatus.Offline, PyrycodeLinkStatus.Down)
            runCurrent()
            assertEquals(
                chats,
                f.vm.hostState.value.hosts[0]
                    .chats,
            )
            assertSame(
                collision,
                f.vm.hostState.value.hosts[1]
                    .chats
                    .single(),
            )
            assertEquals(" /same/../Path ", collision.cwd)
            assertEquals(
                f.a.status.value,
                f.vm.hostState.value.hosts[0]
                    .connectionStatus,
            )
            assertEquals(
                f.b.status.value,
                f.vm.hostState.value.hosts[1]
                    .connectionStatus,
            )
            f.hosts.value = listOf(f.b.entry.copy(displayName = "Renamed locally"), f.a.entry)
            runCurrent()
            assertEquals(
                listOf("host", "Host"),
                f.vm.hostState.value.hosts
                    .map { it.serverId },
            )
            assertEquals(
                "Renamed locally",
                f.vm.hostState.value.hosts[0]
                    .displayName,
            )
            f.hosts.value = emptyList()
            runCurrent()
            assertTrue(
                f.vm.hostState.value.hosts
                    .isEmpty(),
            )
        }

    @Test
    fun collidingRowTargetsAndLegacySelectionKeepSeparateNavigationAndPendingState() =
        runTest(dispatcher) {
            val f = fixture()
            f.a.repo.rows.value = listOf(row("same", "A-private"))
            f.b.repo.rows.value = listOf(row("same", "B-private"))
            backgroundScope.launch(dispatcher) { f.vm.state.collect {} }
            backgroundScope.launch(dispatcher) { f.vm.hostNavigationEvents.collect { f.nav += it } }
            backgroundScope.launch(dispatcher) { f.vm.navigationEvents.collect { f.legacy += it } }
            runCurrent()
            assertEquals("A-private", (f.vm.state.value as DiscussionListUiState.Loaded).discussions.single().name)
            f.selected.value = f.b.repo
            runCurrent()
            assertEquals("B-private", (f.vm.state.value as DiscussionListUiState.Loaded).discussions.single().name)
            f.vm.onHostRowTapped(target)
            f.vm.onHostRowTapped(target.copy(serverId = "host"))
            f.vm.onEvent(DiscussionListEvent.RowTapped("same"))
            f.vm.requestHostPromotion(target)
            f.vm.onEvent(DiscussionListEvent.PromoteConfirmed)
            runCurrent()
            assertTrue(
                f.a.repo.calls
                    .isEmpty(),
            )
            f.vm.onEvent(DiscussionListEvent.SaveAsChannelRequested("same"))
            f.vm.cancelHostPromotion()
            f.vm.onEvent(DiscussionListEvent.PromoteConfirmed)
            runCurrent()
            assertEquals(listOf(target, target.copy(serverId = "host")), f.nav)
            assertEquals(listOf(DiscussionListNavigation.ToThread("same")), f.legacy)
            assertEquals(listOf(Call("same", "B-private", null)), f.b.repo.calls)
            assertTrue(
                f.a.repo.calls
                    .isEmpty(),
            )
        }

    @Test
    fun promotionCapturesNameAndHostClearsBeforeCallAndWaitsForRepositoryListUpdate() =
        runTest(dispatcher) {
            val f = fixture()
            f.a.repo.rows.value = listOf(row("same", " A-private "))
            f.b.repo.rows.value = listOf(row("same", "B-private"))
            backgroundScope.launch(dispatcher) { f.vm.hostNavigationEvents.collect { f.nav += it } }
            backgroundScope.launch(dispatcher) { f.vm.navigationEvents.collect { f.legacy += it } }
            runCurrent()
            f.vm.requestHostPromotion(target)
            runCurrent()
            assertEquals(PendingHostPromotion(target, " A-private "), f.vm.hostState.value.pendingPromotion)
            f.a.repo.rows.value = listOf(row("same", "changed-private"))
            f.selected.value = f.b.repo
            val gate = CompletableDeferred<Unit>()
            f.a.repo.gate = gate
            f.a.repo.beforePromote = { f.vm.confirmHostPromotion() }
            f.vm.confirmHostPromotion()
            f.vm.confirmHostPromotion()
            runCurrent()
            assertNull(f.vm.hostState.value.pendingPromotion)
            assertEquals(listOf(Call("same", " A-private ", null)), f.a.repo.calls)
            assertTrue(
                f.b.repo.calls
                    .isEmpty(),
            )
            assertEquals(
                "same",
                f.vm.hostState.value.hosts[0]
                    .chats
                    .single()
                    .id,
            )
            gate.complete(Unit)
            runCurrent()
            assertEquals(
                "same",
                f.vm.hostState.value.hosts[0]
                    .chats
                    .single()
                    .id,
            )
            f.a.repo.rows.value = listOf(row("same").copy(isPromoted = true))
            runCurrent()
            assertTrue(
                f.vm.hostState.value.hosts[0]
                    .chats
                    .isEmpty(),
            )
            assertTrue(f.nav.isEmpty())
            assertTrue(f.legacy.isEmpty())
        }

    @Test
    fun nullAndBlankNamesUseFallbackAndCancelNeverPromotes() =
        runTest(dispatcher) {
            val f = fixture()
            for (name in listOf(null, " \t ")) {
                f.a.repo.rows.value = listOf(row("same", name))
                runCurrent()
                f.vm.requestHostPromotion(target)
                f.vm.confirmHostPromotion()
                runCurrent()
            }
            assertEquals(List(2) { Call("same", "Untitled channel", null) }, f.a.repo.calls)
            f.vm.requestHostPromotion(target)
            f.vm.cancelHostPromotion()
            f.vm.confirmHostPromotion()
            runCurrent()
            assertNull(f.vm.hostState.value.pendingPromotion)
            assertEquals(2, f.a.repo.calls.size)
        }

    @Test
    fun removedHostOrInactiveChatClearsPendingWithoutSubscribersAndCannotRevive() =
        runTest(dispatcher) {
            val f = fixture()
            for (reason in listOf("host", "row", "archive", "promote")) {
                f.hosts.value = listOf(f.a.entry, f.b.entry)
                f.a.repo.rows.value = listOf(row("same"), row("survivor"))
                f.b.repo.rows.value = listOf(row("same"))
                runCurrent()
                f.vm.requestHostPromotion(target)
                runCurrent()
                assertEquals(
                    target,
                    f.vm.hostState.value.pendingPromotion
                        ?.target,
                )
                if (reason == "host") {
                    f.hosts.value = listOf(f.b.entry)
                } else {
                    f.a.repo.rows.value = listOf(row("survivor")) +
                        when (reason) {
                            "archive" -> listOf(row("same").copy(archived = true))
                            "promote" -> listOf(row("same").copy(isPromoted = true))
                            else -> emptyList()
                        }
                }
                runCurrent()
                assertNull(f.vm.hostState.value.pendingPromotion)
                f.hosts.value = listOf(f.a.entry, f.b.entry)
                f.a.repo.rows.value = listOf(row("same"))
                runCurrent()
                f.vm.confirmHostPromotion()
                runCurrent()
            }
            assertTrue(
                f.a.repo.calls
                    .isEmpty(),
            )
            assertTrue(
                f.b.repo.calls
                    .isEmpty(),
            )
        }

    @Test
    fun unknownAndUnavailableTargetsNeverSendDespiteCachedRowsOrConnectedIndicators() =
        runTest(dispatcher) {
            val f = fixture()
            f.a.repo.rows.value = listOf(row("same"))
            runCurrent()
            for (unknown in listOf(target.copy(serverId = "HOST"), target.copy(conversationId = "missing"))) {
                f.vm.requestHostPromotion(unknown)
                runCurrent()
                assertNull(f.vm.hostState.value.pendingPromotion)
                f.vm.confirmHostPromotion()
            }
            for (status in listOf(
                ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected),
                ConnectionStatus(RelayLinkStatus.Offline, PyrycodeLinkStatus.Down),
                ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Handshaking),
            )) {
                f.a.available = true
                f.vm.requestHostPromotion(target)
                f.a.available = false
                f.a.status.value = status
                f.vm.confirmHostPromotion()
                runCurrent()
                assertNull(f.vm.hostState.value.pendingPromotion)
            }
            assertEquals(List(3) { "Host" }, f.lookups)
            assertTrue(
                f.a.repo.calls
                    .isEmpty(),
            )
            assertTrue(
                f.b.repo.calls
                    .isEmpty(),
            )
        }

    @Test
    fun queuedConfirmationRechecksMembershipAndLooksUpTheCurrentRepositoryAtSend() =
        runTest(dispatcher) {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val f = fixture()
            f.a.repo.rows.value = listOf(row("same"))
            runCurrent()
            f.vm.requestHostPromotion(target)
            runCurrent()
            f.vm.confirmHostPromotion()
            f.a.repo.rows.value = listOf(row("survivor"))
            runCurrent()
            assertTrue(
                f.a.repo.calls
                    .isEmpty(),
            )
            assertTrue(f.lookups.isEmpty())
            f.a.repo.rows.value = listOf(row("same", "captured-private"))
            runCurrent()
            f.vm.requestHostPromotion(target)
            runCurrent()
            f.vm.confirmHostPromotion()
            val replacement = Repo().apply { rows.value = listOf(row("same", "replacement-private")) }
            f.a.live.value = replacement
            runCurrent()
            assertEquals(listOf(Call("same", "captured-private", null)), replacement.calls)
            assertTrue(
                f.a.repo.calls
                    .isEmpty(),
            )
        }

    @Test
    fun guardedFailuresStayQuietCancellationPropagatesAndLogsContainNoContent() =
        runTest(dispatcher) {
            val f = fixture()
            f.a.repo.rows.value = listOf(row("same", "sensitive-private"))
            backgroundScope.launch(dispatcher) { f.vm.hostNavigationEvents.collect { f.nav += it } }
            runCurrent()
            for (failure in listOf(
                IllegalStateException("sensitive-private"),
                UnsupportedOperationException("sensitive-private"),
                RelayErrorException("server.error", false, "sensitive-private"),
                CancellationException("sensitive-private"),
            )) {
                f.a.repo.failure = failure
                f.vm.requestHostPromotion(target)
                f.vm.confirmHostPromotion()
                runCurrent()
                assertEquals(
                    failure is CancellationException,
                    f.a.repo.actionJob!!
                        .isCancelled,
                )
                assertNull(f.vm.hostState.value.pendingPromotion)
            }
            f.a.repo.failure = null
            f.a.repo.gate = CompletableDeferred()
            f.vm.requestHostPromotion(target)
            f.vm.confirmHostPromotion()
            runCurrent()
            f.vm.viewModelScope.cancel()
            runCurrent()
            assertTrue(
                f.a.repo.actionJob!!
                    .isCancelled,
            )
            assertTrue(f.nav.isEmpty())
            assertTrue(logs.any { "event=host_promotion_failed" in it })
            assertTrue(logs.none { "private" in it || "same" in it || "Host" in it || "Path" in it })
        }

    @Test
    fun appModuleInjectsSharedDemoSourceAndPromotesThroughExistingFakeSingleton() =
        runTest(dispatcher) {
            val app = KoinApplication.init().modules(appModule, conversationRepositoryModule(false))
            val vm = app.koin.get<DiscussionListViewModel>()
            try {
                val source = app.koin.get<HostConversationSource>()
                val fake = app.koin.get<FakeConversationRepository>()
                assertSame(source, app.koin.get<HostConversationSource>())
                assertSame(fake, source.repositoryFor("demo"))
                assertNull(source.repositoryFor("saved-host"))
                val state =
                    vm.hostState.first {
                        it.hosts
                            .singleOrNull()
                            ?.chats
                            ?.isNotEmpty() == true
                    }
                assertEquals(listOf("demo"), state.hosts.map { it.serverId })
                assertEquals("Demo", state.hosts.single().displayName)
                assertEquals(fake.observeConversations(ConversationFilter.Discussions).first(), state.hosts.single().chats)
                val chat =
                    state.hosts
                        .single()
                        .chats
                        .first()
                vm.requestHostPromotion(HostConversationTarget("demo", chat.id))
                vm.confirmHostPromotion()
                val promoted =
                    fake
                        .observeConversations(ConversationFilter.Channels)
                        .first { rows -> rows.any { it.id == chat.id } }
                        .single { it.id == chat.id }
                assertEquals(derivedChannelName(chat.name), promoted.name)
                vm.hostState.first { hosts ->
                    hosts.hosts
                        .single()
                        .chats
                        .none { it.id == chat.id }
                }
            } finally {
                vm.viewModelScope.cancel()
                app.close()
            }
        }

    private fun fixture() = Fixture().also { fixtures += it }

    private inner class Fixture {
        val a = Host("Host")
        val b = Host("host")
        val hosts = MutableStateFlow(listOf(a.entry, b.entry))
        val selected = MutableStateFlow<ConversationRepository?>(a.repo)
        val lookups = mutableListOf<String>()
        val source =
            HostConversationSource(hosts, { id ->
                lookups += id
                listOf(a, b)
                    .find { host ->
                        host.entry.serverId == id && host.available && hosts.value.any { it.serverId == id }
                    }?.live
                    ?.value
            }, dispatcher)
        val vm = DiscussionListViewModel(StableConversationRepository(selected), source)
        val nav = mutableListOf<HostConversationTarget>()
        val legacy = mutableListOf<DiscussionListNavigation>()
    }

    private class Host(
        id: String,
    ) {
        val repo = Repo()
        var available = true
        val live = MutableStateFlow<ConversationRepository?>(repo)
        val status = MutableStateFlow(ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected))
        val entry = HostConversationConnection(id, "Local $id", live, status)
    }

    private data class Call(
        val id: String,
        val name: String,
        val workspace: String?,
    )

    private class Repo : ConversationRepository by FakeConversationRepository() {
        val rows = MutableStateFlow<List<Conversation>?>(null)
        val calls = mutableListOf<Call>()
        var failure: Throwable? = null
        var actionJob: Job? = null
        var gate: CompletableDeferred<Unit>? = null
        var beforePromote: () -> Unit = {}

        override fun observeConversations(filter: ConversationFilter) =
            rows.filterNotNull().map { rows ->
                rows.filter { row ->
                    when (filter) {
                        ConversationFilter.All -> true
                        ConversationFilter.Channels -> row.isPromoted && !row.archived
                        ConversationFilter.Discussions -> !row.isPromoted && !row.archived
                        ConversationFilter.Archived -> row.archived
                    }
                }
            }

        override suspend fun promote(
            conversationId: String,
            name: String,
            workspace: String?,
        ): Conversation {
            actionJob = currentCoroutineContext().job
            calls += Call(conversationId, name, workspace)
            beforePromote()
            failure?.let { throw it }
            gate?.await()
            return row(conversationId, name).copy(isPromoted = true)
        }
    }

    companion object {
        private val target = HostConversationTarget("Host", "same")

        private fun row(
            id: String,
            name: String? = null,
        ) = Conversation(id, name, " /same/../Path ", "session", emptyList(), false, Instant.parse("2026-09-01T00:00:00Z"))
    }
}
