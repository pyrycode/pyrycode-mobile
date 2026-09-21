package de.pyryco.mobile.ui.conversations.list

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.StableConversationRepository
import de.pyryco.mobile.di.HostConversationConnection
import de.pyryco.mobile.di.HostConversationSource
import de.pyryco.mobile.di.KoinHostSources
import de.pyryco.mobile.di.appModule
import de.pyryco.mobile.di.conversationRepositoryModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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
import org.koin.dsl.module

@OptIn(ExperimentalCoroutinesApi::class)
class HostChannelListViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val fixtures = mutableListOf<Fixture>()
    private val hostSources = KoinHostSources()
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
            it.app.close()
        }
        // Must precede resetMain: a Koin-built source collects on Dispatchers.Default, so a publish
        // that outlives this line resumes a Main-bound collector into a torn-down dispatcher (#726).
        hostSources.assertAllClosed()
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
        Dispatchers.resetMain()
    }

    @Test
    fun projectionPreservesHostPairsOrderMetadataAndCachedRowsWithoutWaitingForPreviews() =
        runTest(dispatcher) {
            val f = fixture()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            val channel = row("same", true)
            val chats = listOf(row("z"), row("same"), row("a"), row("fourth"))
            f.a.repo.previews["same"] = MutableSharedFlow()
            f.a.repo.rows.value = listOf(channel) + chats + row("archived").copy(archived = true)
            runCurrent()
            val first = f.vm.hostState.value.hosts
            assertEquals(listOf("Host", "host"), first.map { it.host.serverId })
            assertEquals("Local Host", first[0].host.displayName)
            assertEquals(f.a.status.value, first[0].host.connectionStatus)
            assertEquals(listOf(channel), first[0].host.channels)
            assertEquals(chats, first[0].host.chats)
            assertEquals(chats.take(3), first[0].recentChats)
            assertEquals(4, first[0].chatCount)
            assertTrue(first[1].host.chats.isEmpty())
            assertTrue(first[0].recentChatLastMessages.isEmpty())

            val aMessage = message("A-private")
            val bMessage = message("B-private")
            f.b.repo.previews["same"] = flowOf(bMessage)
            f.b.repo.rows.value = listOf(channel, chats[1])
            (
                f.a.repo.previews
                    .getValue("same") as MutableSharedFlow
            ).emit(aMessage)
            runCurrent()
            assertEquals(
                aMessage,
                f.vm.hostState.value.hosts[0]
                    .recentChatLastMessages["same"],
            )
            assertEquals(
                bMessage,
                f.vm.hostState.value.hosts[1]
                    .recentChatLastMessages["same"],
            )
            assertSame(
                channel,
                f.vm.hostState.value.hosts[1]
                    .host.channels
                    .single(),
            )
            assertEquals(
                " /same/../Path ",
                f.vm.hostState.value.hosts[1]
                    .host.chats
                    .single()
                    .cwd,
            )

            f.a.available = false
            f.a.live.value = null
            f.a.status.value = ConnectionStatus(RelayLinkStatus.Offline, PyrycodeLinkStatus.Down)
            runCurrent()
            assertEquals(
                chats,
                f.vm.hostState.value.hosts[0]
                    .host.chats,
            )
            assertTrue(
                f.vm.hostState.value.hosts[0]
                    .recentChatLastMessages
                    .isEmpty(),
            )
            assertEquals(
                bMessage,
                f.vm.hostState.value.hosts[1]
                    .recentChatLastMessages["same"],
            )
            f.hosts.value = listOf(f.b.entry, f.a.entry)
            runCurrent()
            assertEquals(
                listOf("host", "Host"),
                f.vm.hostState.value.hosts
                    .map { it.host.serverId },
            )
            f.hosts.value = emptyList()
            runCurrent()
            assertTrue(
                f.vm.hostState.value.hosts
                    .isEmpty(),
            )
        }

    @Test
    fun workspaceGroupsCoverEveryActiveRowInSourceOrderAndNeverCrossHosts() =
        runTest(dispatcher) {
            val f = fixture()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            val alphaOne = row("c1", true, "/w/alpha")
            val beta = row("c2", true, "/w/beta")
            val alphaTwo = row("c3", true, "/w/alpha")
            val chats =
                listOf(
                    row("d1", cwd = "/w/beta"),
                    row("d2", cwd = DEFAULT_SCRATCH_CWD),
                    row("d3", cwd = "/w/beta"),
                    row("d4", cwd = "/w/alpha"),
                    row("d5"),
                )
            f.a.repo.rows.value =
                listOf(alphaOne, beta, alphaTwo) + chats +
                listOf(
                    row("archived-channel", true, "/w/alpha").copy(archived = true),
                    row("archived-chat", cwd = "/w/beta").copy(archived = true),
                )
            f.b.repo.rows.value = listOf(row("c1", true, "/w/alpha"))
            runCurrent()

            val host = f.vm.hostState.value.hosts[0]
            // A group takes the position of its first conversation, and gathers rows that are not
            // adjacent in the source list without re-sorting either level.
            assertEquals(listOf("/w/alpha", "/w/beta"), host.channelGroups.map { it.cwd })
            assertEquals(
                listOf(listOf("c1", "c3"), listOf("c2")),
                host.channelGroups.map { group -> group.conversations.map { it.conversation.id } },
            )
            assertSame(alphaTwo, host.channelGroups[0].conversations[1].conversation)
            // The full active chat list, not the recent-three slice; exact cwd, never normalised.
            assertEquals(
                listOf("/w/beta", DEFAULT_SCRATCH_CWD, "/w/alpha", " /same/../Path "),
                host.chatGroups.map { it.cwd },
            )
            assertEquals(chats.size, host.chatGroups.sumOf { it.conversations.size })
            assertEquals(listOf("d1", "d3"), host.chatGroups[0].conversations.map { it.conversation.id })
            assertEquals(3, host.recentChats.size)
            assertEquals(chats.size, host.chatCount)
            val everyRow = (host.channelGroups + host.chatGroups).flatMap { it.conversations }
            assertTrue(everyRow.none { it.conversation.archived })
            assertTrue((host.channelGroups + host.chatGroups).all { it.serverId == "Host" })
            assertTrue(everyRow.all { it.serverId == "Host" })

            val other = f.vm.hostState.value.hosts[1]
            assertEquals(listOf("/w/alpha"), other.channelGroups.map { it.cwd })
            assertEquals("host", other.channelGroups.single().serverId)
            assertEquals(
                listOf("host"),
                other.channelGroups
                    .single()
                    .conversations
                    .map { it.serverId },
            )
            assertTrue(other.chatGroups.isEmpty())
        }

    @Test
    fun groupDisplayNamesFollowTheSharedRuleAndRelabelTouchesOnlyTheOwningHostsGroup() =
        runTest(dispatcher) {
            val f = fixture()
            // Deny both live repositories while the source keeps collecting rows: observeHostEntry
            // then short-circuits before subscribing to any preview, so complete groups here prove
            // the grouping subscribes to nothing of its own.
            f.a.available = false
            f.b.available = false
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            val rows =
                listOf(
                    row("c1", true, "/w/secret-path", "Secret Label"),
                    row("c2", true, "/w/plain"),
                    row("c3", true, "/w/blank", "   "),
                    row("c4", true, DEFAULT_SCRATCH_CWD),
                    row("c5", true, "", "Named scratch"),
                    row("d1", cwd = "/w/secret-path", label = "Secret Label"),
                )
            f.a.repo.rows.value = rows
            f.b.repo.rows.value = listOf(row("c1", true, "/w/secret-path"))
            runCurrent()

            assertEquals(
                listOf("Secret Label", "plain", "blank", "scratch", "Named scratch"),
                f.vm.hostState.value.hosts[0]
                    .channelGroups
                    .map { it.displayName },
            )
            assertEquals(
                "Secret Label",
                f.vm.hostState.value.hosts[0]
                    .chatGroups
                    .single()
                    .displayName,
            )
            assertTrue(
                f.vm.hostState.value.hosts
                    .all { it.recentChatLastMessages.isEmpty() },
            )
            // Same path on the other host is its own group, unlabelled there.
            assertEquals(
                "secret-path",
                f.vm.hostState.value.hosts[1]
                    .channelGroups
                    .single()
                    .displayName,
            )

            val relabel = { label: String? ->
                f.a.repo.rows.value = rows.map { if (it.cwd == "/w/secret-path") it.copy(workspaceLabel = label) else it }
                runCurrent()
            }
            relabel("Renamed")
            val renamed =
                f.vm.hostState.value.hosts[0]
                    .channelGroups
            assertEquals(listOf("Renamed", "plain", "blank", "scratch", "Named scratch"), renamed.map { it.displayName })
            assertEquals("/w/secret-path", renamed[0].cwd)
            assertEquals(listOf("c1"), renamed[0].conversations.map { it.conversation.id })
            assertEquals(
                "Renamed",
                f.vm.hostState.value.hosts[0]
                    .chatGroups
                    .single()
                    .displayName,
            )
            assertEquals(
                "secret-path",
                f.vm.hostState.value.hosts[1]
                    .channelGroups
                    .single()
                    .displayName,
            )

            relabel(null)
            val cleared =
                f.vm.hostState.value.hosts[0]
                    .channelGroups
            assertEquals(listOf("secret-path", "plain", "blank", "scratch", "Named scratch"), cleared.map { it.displayName })
            assertEquals("/w/secret-path", cleared[0].cwd)
            assertEquals(listOf("c1"), cleared[0].conversations.map { it.conversation.id })
            assertTrue(logs.none { "Secret" in it || "Renamed" in it || "secret-path" in it })
        }

    @Test
    fun previewFailureIsLocalAndRetiredPreviewCannotCrossIntoAnotherHost() =
        runTest(dispatcher) {
            val f = fixture()
            f.a.repo.previews["same"] = flow { error("sensitive-preview") }
            f.b.repo.previews["same"] = flowOf(message("B-private"))
            f.a.repo.rows.value = listOf(row("same"))
            f.b.repo.rows.value = listOf(row("same"))
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            runCurrent()
            assertTrue(
                f.vm.hostState.value.hosts[0]
                    .recentChatLastMessages
                    .isEmpty(),
            )
            assertEquals(
                message("B-private"),
                f.vm.hostState.value.hosts[1]
                    .recentChatLastMessages["same"],
            )
            f.hosts.value = listOf(f.a.entry)
            runCurrent()
            assertEquals(
                "Host",
                f.vm.hostState.value.hosts
                    .single()
                    .host.serverId,
            )
            assertTrue(
                f.vm.hostState.value.hosts
                    .single()
                    .recentChatLastMessages
                    .isEmpty(),
            )
            assertTrue(logs.none { "sensitive" in it || "private" in it || "/Path" in it })
        }

    @Test
    fun defaultCreationCapturesHostBeforePreferenceSuspensionAndResolvesFreshRepositoryAtSend() =
        runTest(dispatcher) {
            val preferences = MutableSharedFlow<Preferences>()
            val f = fixture(preferences)
            backgroundScope.launch(dispatcher) { f.vm.hostNavigationEvents.collect { f.nav += it } }
            backgroundScope.launch(dispatcher) { f.vm.navigationEvents.collect { f.legacy += it } }
            f.vm.createHostDiscussion("Host")
            runCurrent()
            assertTrue(f.lookups.isEmpty())
            f.selected.value = f.b.repo
            val replacement = Repo()
            f.a.live.value = replacement
            val defaults =
                preferencesOf(
                    stringPreferencesKey("default_workspace") to "wrong-global",
                    stringPreferencesKey("default_workspace_host:Host") to "chosen-default",
                    stringPreferencesKey("default_workspace_host:host") to "other-default",
                )
            preferences.emit(defaults)
            runCurrent()
            assertEquals(listOf("chosen-default"), replacement.workspaces)
            assertTrue(
                f.a.repo.workspaces
                    .isEmpty(),
            )
            assertTrue(
                f.b.repo.workspaces
                    .isEmpty(),
            )
            assertEquals(listOf("Host"), f.lookups)
            assertEquals(listOf(HostConversationTarget("Host", "returned-id")), f.nav)
            assertTrue(f.legacy.isEmpty())

            f.vm.createHostDiscussion("host")
            preferences.emit(defaults)
            runCurrent()
            assertEquals(listOf("other-default"), f.b.repo.workspaces)
            assertEquals(HostConversationTarget("host", "returned-id"), f.nav.last())
            f.vm.createHostDiscussion("Host")
            preferences.emit(emptyPreferences())
            runCurrent()
            assertEquals(listOf("chosen-default", DEFAULT_SCRATCH_CWD), replacement.workspaces)
            assertEquals(HostConversationTarget("Host", "returned-id"), f.nav.last())
        }

    @Test
    fun pickerCapturesHostOverridesPreferenceClearsSynchronouslyAndDismissesWithoutCreating() =
        runTest(dispatcher) {
            val f = fixture(flow { error("preference must not be read") })
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            backgroundScope.launch(dispatcher) { f.vm.hostNavigationEvents.collect { f.nav += it } }
            f.vm.openHostWorkspacePicker("Host")
            runCurrent()
            assertEquals("Host", f.vm.hostState.value.workspacePickerServerId)
            f.selected.value = f.b.repo
            val completion = CompletableDeferred<Unit>()
            f.a.repo.createGate = completion
            f.vm.pickHostWorkspace("explicit")
            f.vm.pickHostWorkspace("duplicate")
            runCurrent()
            assertNull(f.vm.hostState.value.workspacePickerServerId)
            assertEquals(listOf("explicit"), f.a.repo.workspaces)
            assertTrue(f.nav.isEmpty())
            completion.complete(Unit)
            runCurrent()
            assertEquals(listOf(HostConversationTarget("Host", "returned-id")), f.nav)
            f.vm.openHostWorkspacePicker("host")
            f.vm.dismissHostWorkspacePicker()
            f.vm.pickHostWorkspace("dismissed")
            runCurrent()
            assertNull(f.vm.hostState.value.workspacePickerServerId)
            assertTrue(
                f.b.repo.workspaces
                    .isEmpty(),
            )
        }

    @Test
    fun unavailableTargetsNeverCreateOrNavigateEvenWithCachedRowsAndConnectedIndicators() =
        runTest(dispatcher) {
            val preferences = MutableSharedFlow<Preferences>()
            val f = fixture(preferences)
            backgroundScope.launch(dispatcher) { f.vm.hostNavigationEvents.collect { f.nav += it } }
            f.a.repo.rows.value = listOf(row("cached"))
            for (reason in listOf("unknown", "removed", "disconnected", "handshaking")) {
                f.hosts.value = listOf(f.a.entry, f.b.entry)
                f.a.available = true
                f.a.status.value = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)
                val target = if (reason == "unknown") "HOST" else "Host"
                f.vm.createHostDiscussion(target)
                f.vm.openHostWorkspacePicker(target)
                f.a.available = false
                if (reason == "removed") f.hosts.value = listOf(f.b.entry)
                if (reason == "disconnected") f.a.status.value = ConnectionStatus(RelayLinkStatus.Offline, PyrycodeLinkStatus.Down)
                if (reason == "handshaking") f.a.status.value = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Handshaking)
                preferences.emit(emptyPreferences())
                f.vm.pickHostWorkspace("explicit")
                runCurrent()
            }
            assertTrue(
                f.a.repo.workspaces
                    .isEmpty(),
            )
            assertTrue(
                f.b.repo.workspaces
                    .isEmpty(),
            )
            assertTrue(f.nav.isEmpty())
        }

    @Test
    fun guardedFailuresRemainQuietAndCancellationCancelsTheActionJob() =
        runTest(dispatcher) {
            val f = fixture()
            backgroundScope.launch(dispatcher) { f.vm.hostNavigationEvents.collect { f.nav += it } }
            val uncaught = mutableListOf<Throwable>()
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught += error }
            try {
                for (failure in listOf(
                    IllegalStateException("sensitive"),
                    UnsupportedOperationException("sensitive"),
                    RelayErrorException("server.error", false, "sensitive"),
                    CancellationException("sensitive"),
                )) {
                    f.a.repo.failure = failure
                    f.vm.createHostDiscussion("Host")
                    runCurrent()
                    assertEquals(
                        failure is CancellationException,
                        f.a.repo.actionJob!!
                            .isCancelled,
                    )
                    f.vm.openHostWorkspacePicker("Host")
                    f.vm.pickHostWorkspace("explicit")
                    runCurrent()
                    assertEquals(
                        failure is CancellationException,
                        f.a.repo.actionJob!!
                            .isCancelled,
                    )
                }
                assertTrue(uncaught.isEmpty())
                assertTrue(f.nav.isEmpty())
                assertTrue(logs.none { "sensitive" in it })
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previous)
            }
        }

    @Test
    fun rowTargetsAndLegacySelectedProjectionAndActionsUseSeparateNavigationStreams() =
        runTest(dispatcher) {
            val f = fixture()
            f.a.repo.rows.value = listOf(row("A-channel", true))
            f.b.repo.rows.value = listOf(row("B-channel", true))
            backgroundScope.launch(dispatcher) { f.vm.state.collect {} }
            backgroundScope.launch(dispatcher) { f.vm.hostNavigationEvents.collect { f.nav += it } }
            backgroundScope.launch(dispatcher) { f.vm.navigationEvents.collect { f.legacy += it } }
            runCurrent()
            assertEquals(listOf("A-channel"), (f.vm.state.value as ChannelListUiState.Loaded).channels.map { it.id })
            f.selected.value = f.b.repo
            runCurrent()
            assertEquals(listOf("B-channel"), (f.vm.state.value as ChannelListUiState.Loaded).channels.map { it.id })
            val target = HostConversationTarget("Host", "same")
            f.vm.onHostRowTapped(target)
            f.vm.onEvent(ChannelListEvent.CreateDiscussionTapped)
            runCurrent()
            assertEquals(listOf(target), f.nav)
            assertEquals(listOf(ChannelListNavigation.ToThread("returned-id")), f.legacy)
            assertEquals(listOf(DEFAULT_SCRATCH_CWD), f.b.repo.workspaces)
            assertTrue(
                f.a.repo.workspaces
                    .isEmpty(),
            )
        }

    @Test
    fun appModuleInjectsSharedDemoSourceAndCreatesThroughExistingFakeSingleton() =
        runTest(dispatcher) {
            val values = MutableStateFlow(emptyPreferences())
            val prefs =
                AppPreferences(
                    object : DataStore<Preferences> {
                        override val data = values

                        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                            transform(values.value).also { values.value = it }
                    },
                )
            prefs.setDefaultWorkspace("/paired-host-only")
            prefs.migrateDefaultWorkspace(setOf("saved-host")).getOrThrow()
            // Built inside the guard: resolving the ViewModel starts a Dispatchers.Default-backed
            // source and attaches a Main-bound collector, so it must never happen on a path with no
            // finally to close it (#726).
            var viewModel: ChannelListViewModel? = null
            try {
                val app =
                    KoinApplication.init().modules(
                        appModule,
                        conversationRepositoryModule(false),
                        module {
                            single { prefs }
                        },
                    )
                val source = hostSources.source(app)
                val vm = app.koin.get<ChannelListViewModel>().also { viewModel = it }
                val fake = app.koin.get<FakeConversationRepository>()
                assertSame(source, app.koin.get<HostConversationSource>())
                assertSame(fake, source.repositoryFor("demo"))
                assertNull(source.repositoryFor("saved-host"))
                val state =
                    vm.hostState.first {
                        it.hosts
                            .singleOrNull()
                            ?.host
                            ?.channels
                            ?.isNotEmpty() == true
                    }
                assertEquals(listOf("demo"), state.hosts.map { it.host.serverId })
                assertEquals(
                    "Demo",
                    state.hosts
                        .single()
                        .host.displayName,
                )
                assertEquals(
                    fake.observeConversations(ConversationFilter.Channels).first(),
                    state.hosts
                        .single()
                        .host.channels,
                )
                val navigation = async { vm.hostNavigationEvents.first() }
                vm.createHostDiscussion("demo")
                val target = navigation.await()
                assertEquals("demo", target.serverId)
                val created = fake.observeConversations(ConversationFilter.Discussions).first().single { it.id == target.conversationId }
                assertEquals(DEFAULT_SCRATCH_CWD, created.cwd)
                prefs.setDefaultWorkspace("demo", "/demo-only").getOrThrow()
                val nextNavigation = async { vm.hostNavigationEvents.first() }
                vm.createHostDiscussion("demo")
                val next = nextNavigation.await()
                assertEquals("demo", next.serverId)
                assertEquals(
                    "/demo-only",
                    fake
                        .observeConversations(ConversationFilter.Discussions)
                        .first()
                        .single { it.id == next.conversationId }
                        .cwd,
                )
                assertEquals("/paired-host-only", prefs.defaultWorkspace("saved-host").first())
            } finally {
                viewModel?.viewModelScope?.cancel()
                hostSources.closeAndAssertStopped()
            }
        }

    private fun preferences(values: Flow<Preferences>) =
        AppPreferences(
            object : DataStore<Preferences> {
                override val data = values

                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("unused")
            },
        )

    private fun fixture(preferences: Flow<Preferences> = flowOf(emptyPreferences())) = Fixture(preferences).also { fixtures += it }

    private inner class Fixture(
        preferences: Flow<Preferences>,
    ) {
        val a = Host("Host")
        val b = Host("host")
        val hosts = MutableStateFlow(listOf(a.entry, b.entry))
        val selected = MutableStateFlow<ConversationRepository?>(a.repo)
        val lookups = mutableListOf<String>()
        val source =
            HostConversationSource(hosts, { id ->
                lookups += id
                listOf(a, b).find { it.entry.serverId == id && it.available && it.entry in hosts.value }?.live?.value
            }, dispatcher)
        val app =
            KoinApplication.init().modules(
                appModule,
                module {
                    single<ConversationRepository> { StableConversationRepository(selected) }
                    single { preferences(preferences) }
                    single { source }
                },
            )
        val vm = app.koin.get<ChannelListViewModel>()
        val nav = mutableListOf<HostConversationTarget>()
        val legacy = mutableListOf<ChannelListNavigation>()
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

    private class Repo : ConversationRepository by FakeConversationRepository() {
        val rows = MutableStateFlow<List<Conversation>?>(null)
        val previews = mutableMapOf<String, Flow<Message?>>()
        val workspaces = mutableListOf<String?>()
        var failure: Throwable? = null
        var actionJob: Job? = null
        var createGate: CompletableDeferred<Unit>? = null

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

        override fun observeLastMessage(conversationId: String): Flow<Message?> = previews[conversationId] ?: flowOf(null)

        override suspend fun createDiscussion(workspace: String?): Conversation {
            actionJob = currentCoroutineContext().job
            workspaces += workspace
            failure?.let { throw it }
            createGate?.await()
            return row("returned-id")
        }
    }

    companion object {
        private fun row(
            id: String,
            promoted: Boolean = false,
            cwd: String = " /same/../Path ",
            label: String? = null,
        ) = Conversation(
            id,
            null,
            cwd,
            "session",
            emptyList(),
            promoted,
            Instant.parse("2026-09-01T00:00:00Z"),
            workspaceLabel = label,
        )

        private fun message(content: String) =
            Message("message", "session", Role.Assistant, content, Instant.parse("2026-09-01T00:00:00Z"), false)
    }
}
