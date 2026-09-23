package de.pyryco.mobile.ui.conversations.list

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.viewModelScope
import de.pyryco.mobile.data.cache.AttachmentStore
import de.pyryco.mobile.data.cache.FileConversationCache
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.data.crypto.PairedServerStoreException
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.repository.AttachmentContent
import de.pyryco.mobile.data.repository.AttachmentFetchResult
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.StableConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.di.ConversationAttention
import de.pyryco.mobile.di.HostConversationConnection
import de.pyryco.mobile.di.HostConversationSource
import de.pyryco.mobile.di.KoinHostSources
import de.pyryco.mobile.di.ObservablePairedServerStore
import de.pyryco.mobile.di.appModule
import de.pyryco.mobile.di.conversationRepositoryModule
import de.pyryco.mobile.di.forgetRemovedHost
import de.pyryco.mobile.ui.conversations.thread.ComposerDraftStore
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koin.core.KoinApplication
import org.koin.dsl.module

@OptIn(ExperimentalCoroutinesApi::class)
class HostChannelListViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

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
    fun addWorkspaceReadsAndWritesOnlyItsOwnHostAndStartsInTheCreatedFolder() =
        runTest(dispatcher) {
            val f = fixture(flow { error("preference must not be read") })
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            backgroundScope.launch(dispatcher) { f.vm.hostNavigationEvents.collect { f.nav += it } }
            f.a.repo.recents.value = listOf("/a/recent")
            f.b.repo.recents.value = listOf("/b/recent")
            // The selected adapter points at the other host: nothing below may follow it.
            f.selected.value = f.b.repo

            f.vm.openAddWorkspace("Host")
            runCurrent()
            assertEquals(AddWorkspaceState("Host"), f.vm.hostState.value.addWorkspace)
            assertEquals(listOf("/a/recent"), f.vm.hostState.value.addWorkspaceRecent)

            // A created folder becomes the selection and starts nothing.
            f.vm.createAddWorkspaceFolder("  fresh  ")
            runCurrent()
            assertEquals(listOf("fresh"), f.a.repo.createdFolders)
            assertEquals(AddWorkspaceState("Host", selected = "/Host/fresh"), f.vm.hostState.value.addWorkspace)
            assertTrue(
                f.a.repo.workspaces
                    .isEmpty(),
            )
            assertTrue(f.nav.isEmpty())

            // OK starts exactly that folder on that host, closes and opens the thread.
            f.vm.submitAddWorkspace()
            runCurrent()
            assertEquals(listOf<String?>("/Host/fresh"), f.a.repo.workspaces)
            assertNull(f.vm.hostState.value.addWorkspace)
            assertEquals(listOf(HostConversationTarget("Host", "returned-id")), f.nav)
            assertEquals(HostConversationTarget("Host", "returned-id"), f.vm.hostState.value.selected)

            // A recent folder of the second host, opened on the second host, goes to the second host.
            f.vm.openAddWorkspace("host")
            runCurrent()
            assertEquals(listOf("/b/recent"), f.vm.hostState.value.addWorkspaceRecent)
            f.vm.selectAddWorkspaceFolder("/b/recent")
            f.vm.submitAddWorkspace()
            runCurrent()
            assertEquals(listOf<String?>("/b/recent"), f.b.repo.workspaces)
            assertEquals(listOf<String?>("/Host/fresh"), f.a.repo.workspaces)
            assertTrue(
                f.b.repo.createdFolders
                    .isEmpty(),
            )
        }

    @Test
    fun addWorkspaceRecentsNeverShowAnotherHostsListAndAreCapped() =
        runTest(dispatcher) {
            val f = fixture()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            f.a.repo.recents.value = (1..80).map { "/a/$it" }
            f.b.repo.recents.value = listOf("/b/only")
            f.vm.openAddWorkspace("Host")
            runCurrent()
            assertEquals((1..50).map { "/a/$it" }, f.vm.hostState.value.addWorkspaceRecent)

            // Retargeted without closing: the first host's list is never published with the second's state.
            val seen = mutableListOf<HostChannelListState>()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect { seen += it } }
            f.vm.openAddWorkspace("host")
            runCurrent()
            assertTrue(seen.none { it.addWorkspace?.serverId == "host" && it.addWorkspaceRecent.any { path -> path.startsWith("/a/") } })
            assertEquals(listOf("/b/only"), f.vm.hostState.value.addWorkspaceRecent)

            // Closed: no list.
            f.vm.dismissAddWorkspace()
            runCurrent()
            assertTrue(
                f.vm.hostState.value.addWorkspaceRecent
                    .isEmpty(),
            )

            // An unknown host opens nothing.
            f.vm.openAddWorkspace("HOST")
            runCurrent()
            assertNull(f.vm.hostState.value.addWorkspace)
        }

    @Test
    fun addWorkspaceFailuresStayOpenWithSelectionAndStaticFlags() =
        runTest(dispatcher) {
            val f = fixture()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            backgroundScope.launch(dispatcher) { f.vm.hostNavigationEvents.collect { f.nav += it } }
            f.a.repo.recents.value = listOf("/a/secret-path")
            f.vm.openAddWorkspace("Host")
            f.vm.selectAddWorkspaceFolder("/a/secret-path")
            runCurrent()

            f.a.repo.failure = RelayErrorException("server.error", false, "server-secret")
            f.vm.createAddWorkspaceFolder("typed-secret")
            runCurrent()
            assertEquals(
                AddWorkspaceState("Host", selected = "/a/secret-path", createFailed = true),
                f.vm.hostState.value.addWorkspace,
            )

            f.vm.submitAddWorkspace()
            runCurrent()
            assertEquals(
                AddWorkspaceState("Host", selected = "/a/secret-path", startFailed = true),
                f.vm.hostState.value.addWorkspace,
            )
            assertTrue(f.nav.isEmpty())
            assertTrue(logs.any { "add_workspace_create_failed" in it } && logs.any { "add_workspace_start_failed" in it })
            assertTrue(
                "no server message, path, name or id may reach a log line: $logs",
                logs.none { "server-secret" in it || "secret-path" in it || "typed-secret" in it || "Host" in it },
            )

            // A new selection clears the failure; a retry that succeeds closes and navigates.
            f.a.repo.failure = null
            f.vm.selectAddWorkspaceFolder("/a/secret-path")
            assertEquals(AddWorkspaceState("Host", selected = "/a/secret-path"), f.vm.hostState.value.addWorkspace)
            f.vm.submitAddWorkspace()
            runCurrent()
            assertNull(f.vm.hostState.value.addWorkspace)
            assertEquals(listOf(HostConversationTarget("Host", "returned-id")), f.nav)
        }

    @Test
    fun addWorkspaceIgnoresPressesWhileBusyAndALateResultCannotReopenOrNavigate() =
        runTest(dispatcher) {
            val f = fixture()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            backgroundScope.launch(dispatcher) { f.vm.hostNavigationEvents.collect { f.nav += it } }

            // Nothing is open, or nothing is selected: nothing is sent.
            f.vm.submitAddWorkspace()
            f.vm.createAddWorkspaceFolder("orphan")
            f.vm.openAddWorkspace("Host")
            f.vm.submitAddWorkspace()
            f.vm.createAddWorkspaceFolder("   ")
            runCurrent()
            assertTrue(
                f.a.repo.workspaces
                    .isEmpty() &&
                    f.a.repo.createdFolders
                        .isEmpty(),
            )

            f.vm.selectAddWorkspaceFolder("/first")
            val gate = CompletableDeferred<Unit>()
            f.a.repo.createGate = gate
            f.vm.submitAddWorkspace()
            f.vm.submitAddWorkspace()
            f.vm.createAddWorkspaceFolder("mid-flight")
            f.vm.selectAddWorkspaceFolder("/second")
            runCurrent()
            val busy = requireNotNull(f.vm.hostState.value.addWorkspace)
            assertTrue(busy.busy)
            assertEquals("/first", busy.selected)
            assertEquals(listOf<String?>("/first"), f.a.repo.workspaces)
            assertTrue(
                f.a.repo.createdFolders
                    .isEmpty(),
            )

            // Cancel while in flight: the late result neither reopens nor navigates.
            f.vm.dismissAddWorkspace()
            gate.complete(Unit)
            runCurrent()
            assertNull(f.vm.hostState.value.addWorkspace)
            assertTrue(f.nav.isEmpty())

            // A folder created after a dismissal and reopen cannot overwrite the new state.
            f.a.repo.createGate = null
            val folderGate = CompletableDeferred<Unit>()
            f.a.repo.folderGate = folderGate
            f.vm.openAddWorkspace("Host")
            f.vm.createAddWorkspaceFolder("late")
            runCurrent()
            f.vm.dismissAddWorkspace()
            f.vm.openAddWorkspace("Host")
            folderGate.complete(Unit)
            runCurrent()
            assertEquals(AddWorkspaceState("Host"), f.vm.hostState.value.addWorkspace)

            // Dismissing sends nothing.
            f.vm.selectAddWorkspaceFolder("/third")
            f.vm.dismissAddWorkspace()
            runCurrent()
            assertEquals(listOf<String?>("/first"), f.a.repo.workspaces)
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
                f.vm.openAddWorkspace(target)
                f.vm.selectAddWorkspaceFolder("explicit")
                f.a.available = false
                if (reason == "removed") f.hosts.value = listOf(f.b.entry)
                if (reason == "disconnected") f.a.status.value = ConnectionStatus(RelayLinkStatus.Offline, PyrycodeLinkStatus.Down)
                if (reason == "handshaking") f.a.status.value = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Handshaking)
                preferences.emit(emptyPreferences())
                f.vm.createAddWorkspaceFolder("folder")
                f.vm.submitAddWorkspace()
                runCurrent()
                // A known host that is unavailable at the press fails it and stays open.
                f.vm.hostState.value.addWorkspace
                    ?.let { assertTrue(it.startFailed) }
                f.vm.dismissAddWorkspace()
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
                    f.vm.openAddWorkspace("Host")
                    f.vm.selectAddWorkspaceFolder("explicit")
                    f.vm.submitAddWorkspace()
                    runCurrent()
                    f.vm.dismissAddWorkspace()
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
    fun rowTapsAndCreationTargetTheirNamedHostRegardlessOfTheSelectedAdapter() =
        runTest(dispatcher) {
            val f = fixture()
            f.a.repo.rows.value = listOf(row("A-channel", true))
            f.b.repo.rows.value = listOf(row("B-channel", true))
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            backgroundScope.launch(dispatcher) { f.vm.hostNavigationEvents.collect { f.nav += it } }
            runCurrent()
            // The selected-host adapter the retired button's two paths resolved through is still bound and
            // still moves (#738). Nothing this view model does may follow it any more: the flat projection
            // that did, and the bare-id stream it navigated on, went with the button.
            f.selected.value = f.b.repo
            runCurrent()
            val target = HostConversationTarget("Host", "same")
            f.vm.onHostRowTapped(target)
            f.vm.createHostDiscussion("Host")
            runCurrent()
            assertEquals(listOf(target, HostConversationTarget("Host", "returned-id")), f.nav)
            assertEquals(listOf(DEFAULT_SCRATCH_CWD), f.a.repo.workspaces)
            assertTrue(
                f.b.repo.workspaces
                    .isEmpty(),
            )
        }

    @Test
    fun appModuleInjectsSharedDemoSourceAndCreatesThroughExistingFakeSingleton() =
        runTest(dispatcher) {
            // The Koin source publishes from Dispatchers.Default. An unconfined Main would run the view
            // model, and through it this body and its finally, on that worker (#892); dispatching Main
            // queues them for this thread instead.
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
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

    @Test
    fun foldStateStartsExpandedAndFoldsEachSectionAndHostIndependently() =
        runTest(dispatcher) {
            val f = fixture()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            f.a.repo.rows.value = listOf(row("a-channel", promoted = true), row("a-chat"))
            runCurrent()
            // Nothing starts folded: the device suites reach the seeded channel by tapping its row.
            assertTrue(
                f.vm.hostState.value.collapsed
                    .isEmpty(),
            )

            val channelsHost = TreeFoldKey(ConversationTreeSection.Channels, "Host")
            f.vm.onFoldToggled(channelsHost)
            runCurrent()
            assertEquals(setOf(channelsHost), f.vm.hostState.value.collapsed)
            // The same host's row in the other section, and every other host, stay open.
            assertFalse(TreeFoldKey(ConversationTreeSection.Chats, "Host") in f.vm.hostState.value.collapsed)
            assertFalse(TreeFoldKey(ConversationTreeSection.Channels, "host") in f.vm.hostState.value.collapsed)

            f.vm.onFoldToggled(channelsHost)
            runCurrent()
            assertTrue(
                f.vm.hostState.value.collapsed
                    .isEmpty(),
            )
        }

    @Test
    fun foldStateKeysOnServerIdAndExactCwdAndSurvivesARelabelAndAListUpdate() =
        runTest(dispatcher) {
            val f = fixture()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            f.a.repo.rows.value = listOf(row("a-1", promoted = true))
            f.b.repo.rows.value = listOf(row("b-1", promoted = true))
            runCurrent()
            val shared =
                f.vm.hostState.value.hosts
                    .first()
                    .channelGroups
                    .single()
                    .cwd
            // Both hosts hold the same path; each is its own node.
            assertEquals(
                shared,
                f.vm.hostState.value.hosts[1]
                    .channelGroups
                    .single()
                    .cwd,
            )
            val workspace = TreeFoldKey(ConversationTreeSection.Channels, "Host", shared)
            f.vm.onFoldToggled(workspace)
            runCurrent()
            assertEquals(setOf(workspace), f.vm.hostState.value.collapsed)
            assertFalse(TreeFoldKey(ConversationTreeSection.Channels, "host", shared) in f.vm.hostState.value.collapsed)

            // A relabel moves display text only, and the added row is an incoming list update: the fold holds.
            f.a.repo.rows.value = listOf(row("a-1", promoted = true, label = "Renamed"), row("a-2", promoted = true))
            runCurrent()
            assertEquals(
                "Renamed",
                f.vm.hostState.value.hosts
                    .first()
                    .channelGroups
                    .single()
                    .displayName,
            )
            assertEquals(setOf(workspace), f.vm.hostState.value.collapsed)
        }

    @Test
    fun selectionRecordsEachOpenedTargetAndSurvivesAnIncomingSnapshot() =
        runTest(dispatcher) {
            val f = fixture()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            f.a.repo.rows.value = listOf(row("a-1", promoted = true))
            runCurrent()
            assertNull(f.vm.hostState.value.selected)

            val first = HostConversationTarget("Host", "a-1")
            f.vm.onHostRowTapped(first)
            runCurrent()
            assertEquals(first, f.vm.hostState.value.selected)

            f.b.repo.rows.value = listOf(row("b-1"))
            runCurrent()
            assertEquals(first, f.vm.hostState.value.selected)

            val second = HostConversationTarget("host", "b-1")
            f.vm.onHostRowTapped(second)
            runCurrent()
            assertEquals(second, f.vm.hostState.value.selected)

            // A discussion created from this list is opened from it too, so it takes the highlight.
            f.vm.createHostDiscussion("Host")
            runCurrent()
            assertEquals(HostConversationTarget("Host", "returned-id"), f.vm.hostState.value.selected)
        }

    @Test
    fun editorOpensOnTheRowsOwnStoredRecordAndSurvivesAnIncomingSnapshot() =
        runTest(dispatcher) {
            val f = fixture()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            runCurrent()
            assertNull(f.vm.hostState.value.hostEditor)

            f.vm.openHostEditor("Host")
            runCurrent()
            val editor = requireNotNull(f.vm.hostState.value.hostEditor)
            assertEquals("Host", editor.serverId)
            assertEquals("Host", editor.serverIdentity)
            assertEquals("wss://first.example:8443", editor.relayAddress)
            assertEquals("Pyrybox", editor.initialName)
            assertFalse(editor.saving)
            assertFalse(editor.failed)

            // The open state is the list's, so an arriving snapshot must not disturb the open modal.
            f.a.repo.rows.value = listOf(row("a-1", promoted = true))
            runCurrent()
            assertSame(editor, f.vm.hostState.value.hostEditor)

            // An unnamed host opens an empty field — never its id, and never the row's placeholder.
            f.vm.openHostEditor("host")
            runCurrent()
            assertEquals(
                "",
                f.vm.hostState.value.hostEditor
                    ?.initialName,
            )

            // A stored blank is unnamed too, exactly as the row reads it.
            f.store.put("host", "wss://second.example", "   ")
            f.vm.openHostEditor("host")
            runCurrent()
            assertEquals(
                "",
                f.vm.hostState.value.hostEditor
                    ?.initialName,
            )
            assertEquals(
                "host",
                f.vm.hostState.value.hostEditor
                    ?.serverIdentity,
            )
        }

    @Test
    fun editorOpenIgnoresAnUnknownIdAndAnOpenSupersededByALaterTap() =
        runTest(dispatcher) {
            val f = fixture()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }

            f.vm.openHostEditor("absent")
            runCurrent()
            assertNull(f.vm.hostState.value.hostEditor)

            // Two pencils tapped while the first read is still decrypting: the second must win, or the
            // modal renames a host the operator did not tap last.
            val gate = CompletableDeferred<Unit>()
            f.store.readGate = gate
            f.vm.openHostEditor("Host")
            runCurrent()
            assertNull(f.vm.hostState.value.hostEditor)

            f.store.readGate = null
            f.vm.openHostEditor("host")
            runCurrent()
            assertEquals(
                "host",
                f.vm.hostState.value.hostEditor
                    ?.serverId,
            )

            gate.complete(Unit)
            runCurrent()
            assertEquals(
                "host",
                f.vm.hostState.value.hostEditor
                    ?.serverId,
            )
        }

    @Test
    fun submitSavesTheTrimmedClampedNameOrClearsItAndClosesTheEditor() =
        runTest(dispatcher) {
            val f = fixture()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }

            // No open editor: nothing to save, and no store write to attribute to a stale target.
            f.vm.submitHostName("orphan")
            runCurrent()
            assertTrue(f.store.renames.isEmpty())

            f.vm.openHostEditor("Host")
            runCurrent()
            f.vm.submitHostName("  Renamed  ")
            runCurrent()
            assertEquals(listOf("Host" to "Renamed"), f.store.renames)
            assertNull(f.vm.hostState.value.hostEditor)

            // Blank clears the name, so the row falls back to its unnamed treatment.
            f.vm.openHostEditor("Host")
            runCurrent()
            f.vm.submitHostName("   ")
            runCurrent()
            assertEquals("Host" to null, f.store.renames.last())
            assertNull(f.vm.hostState.value.hostEditor)

            // Clamped at the write: the stored value is the only one any surface could render.
            f.vm.openHostEditor("Host")
            runCurrent()
            f.vm.submitHostName("x".repeat(MAX_WORKSPACE_LABEL_CHARS + 40))
            runCurrent()
            assertEquals(
                "x".repeat(MAX_WORKSPACE_LABEL_CHARS),
                f.store.renames
                    .last()
                    .second,
            )
        }

    @Test
    fun failedSaveKeepsTheEditorOpenAndActionableWhileADismissedOneStaysClosed() =
        runTest(dispatcher) {
            val f = fixture()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            f.vm.openHostEditor("Host")
            runCurrent()

            f.store.failWrite = true
            f.vm.submitHostName("Renamed")
            runCurrent()
            val failed = requireNotNull(f.vm.hostState.value.hostEditor)
            assertTrue(failed.failed)
            assertFalse(failed.saving)
            // The identity keys the component's own name buffer: changing it would discard the typing.
            assertEquals("Host", failed.serverIdentity)
            assertEquals("Pyrybox", requireNotNull(f.store.loadById("Host")).displayName)
            assertTrue(logs.any { it.contains("host_name_save_failed") })
            assertTrue(logs.none { it.contains("Renamed") })

            // Still actionable: a retry that succeeds clears the failure and closes.
            f.store.failWrite = false
            f.vm.submitHostName("Renamed")
            runCurrent()
            assertEquals("Host" to "Renamed", f.store.renames.last())
            assertNull(f.vm.hostState.value.hostEditor)

            // A save that lands after a dismissal must not resurrect the modal.
            f.vm.openHostEditor("Host")
            runCurrent()
            val write = CompletableDeferred<Unit>()
            f.store.writeGate = write
            f.vm.submitHostName("Later")
            runCurrent()
            f.vm.dismissHostEditor()
            runCurrent()
            assertNull(f.vm.hostState.value.hostEditor)
            write.complete(Unit)
            runCurrent()
            assertNull(f.vm.hostState.value.hostEditor)
            assertEquals("Host" to "Later", f.store.renames.last())
        }

    @Test
    fun unpairIsGatedOnAConfirmationAndDecliningRemovesNothing() =
        runTest(dispatcher) {
            val f = fixture()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            f.prefs.setDefaultWorkspace("Host", "/w/host")
            f.prefs.setDefaultWorkspace("host", "/w/other")

            // No open editor: a stray request must arm nothing, because there is no target to arm it on.
            f.vm.requestHostUnpair()
            runCurrent()
            assertNull(f.vm.hostState.value.hostEditor)

            f.vm.openHostEditor("Host")
            runCurrent()
            f.vm.requestHostUnpair()
            runCurrent()
            assertTrue(requireNotNull(f.vm.hostState.value.hostEditor).confirmingUnpair)
            // Arming the confirmation writes nothing at all.
            assertTrue(f.store.removals.isEmpty())

            f.vm.declineHostUnpair()
            runCurrent()
            val editor = requireNotNull(f.vm.hostState.value.hostEditor)
            // Declining returns to the editor rather than closing it, and the target is still the same host.
            assertFalse(editor.confirmingUnpair)
            assertEquals("Host", editor.serverId)
            assertTrue(f.store.removals.isEmpty())
            assertEquals("Pyrybox", requireNotNull(f.store.loadById("Host")).displayName)
            assertEquals("/w/host", f.prefs.defaultWorkspace("Host").first())
            assertEquals("/w/other", f.prefs.defaultWorkspace("host").first())
        }

    @Test
    fun confirmingRemovesThePairingThenItsWorkspaceAndClosesTheEditor() =
        runTest(dispatcher) {
            val f = fixture()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            f.prefs.setDefaultWorkspace("Host", "/w/host")
            f.prefs.setDefaultWorkspace("host", "/w/other")
            f.vm.openHostEditor("Host")
            runCurrent()
            f.vm.requestHostUnpair()
            runCurrent()

            // Gated mid-removal: the host-owned workspace is still there, because clearing it before the
            // pairing removal reports success would leave a cleared cache behind a failed write.
            val gate = CompletableDeferred<Unit>()
            f.store.removeGate = gate
            f.vm.confirmHostUnpair()
            runCurrent()
            assertTrue(requireNotNull(f.vm.hostState.value.hostEditor).saving)
            assertEquals("/w/host", f.prefs.defaultWorkspace("Host").first())

            gate.complete(Unit)
            runCurrent()
            assertEquals(listOf("Host"), f.store.removals)
            assertNull(f.store.loadById("Host"))
            assertEquals(DEFAULT_SCRATCH_CWD, f.prefs.defaultWorkspace("Host").first())
            assertNull(f.vm.hostState.value.hostEditor)

            // Every other host keeps its pairing, its name and its own cached workspace.
            assertEquals(listOf("host"), f.store.list().map { it.record.serverId })
            assertEquals("/w/other", f.prefs.defaultWorkspace("host").first())
        }

    @Test
    fun confirmingUnpairDropsThatHostsDraftsAndLeavesEveryOtherHostsAlone() =
        runTest(dispatcher) {
            // #790 AC #1, driven from the gesture: openHostEditor → requestHostUnpair →
            // confirmHostUnpair, through the real ObservablePairedServerStore the fixture now binds.
            val f = fixture()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }

            // "Host" and "host" differ only in case, and both hold a draft under the SAME conversation
            // id — the cross-host half of the AC. Conversation ids are host-local, so these are two
            // unrelated chats that an id-only key would have collapsed.
            f.drafts.setDraft("Host", "c1", "unsent to Host")
            f.drafts.setDraft("Host", "c2", "a second thought")
            f.drafts.setDraft("host", "c1", "unsent to host")

            f.vm.openHostEditor("Host")
            runCurrent()
            f.vm.requestHostUnpair()
            runCurrent()

            // A failed removal drops nothing: the pairing is still there, so neither is the text.
            f.store.failRemove = true
            f.vm.confirmHostUnpair()
            runCurrent()
            assertTrue(requireNotNull(f.vm.hostState.value.hostEditor).unpairFailed)
            assertEquals("unsent to Host", f.drafts.draftFor("Host", "c1"))
            assertEquals("a second thought", f.drafts.draftFor("Host", "c2"))
            assertEquals("unsent to host", f.drafts.draftFor("host", "c1"))

            // Gated mid-removal: the text is still there until the removal reports success, for the
            // reason the host-owned workspace is — a cleared draft is never evidence the host is gone.
            f.store.failRemove = false
            val gate = CompletableDeferred<Unit>()
            f.store.removeGate = gate
            f.vm.confirmHostUnpair()
            runCurrent()
            assertTrue(requireNotNull(f.vm.hostState.value.hostEditor).saving)
            assertEquals("unsent to Host", f.drafts.draftFor("Host", "c1"))

            gate.complete(Unit)
            runCurrent()

            // Every draft this host held is gone, bucket included — not blanked, absent.
            assertNull(f.store.loadById("Host"))
            assertEquals("", f.drafts.draftFor("Host", "c1"))
            assertEquals("", f.drafts.draftFor("Host", "c2"))
            // And the other host keeps its own draft for the same conversation id.
            assertEquals("unsent to host", f.drafts.draftFor("host", "c1"))
            assertEquals(mapOf("host" to mapOf("c1" to "unsent to host")), f.drafts.drafts.value)

            // A draft is private message content: none of it may reach a log line.
            assertTrue(
                "draft text must never be logged: $logs",
                logs.none { "unsent to" in it || "a second thought" in it },
            )
        }

    @Test
    fun confirmingUnpairRemovesThatHostsCachedContentAndOnlyAfterTheRemovalSucceeded() =
        runTest(dispatcher) {
            // #798 AC #1 and #4, driven from the gesture through the production removal hook.
            val f = fixture()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            val thread = listOf<ThreadItem>(ThreadItem.MessageItem(message("cached reply")))
            // "Host" and "host" differ only in case and hold a conversation of the same id.
            for (id in listOf("Host", "host")) {
                f.cache.writeConversations(id, listOf(row("c1", promoted = true, label = "cached name")))
                f.cache.writeThread(id, "c1", thread)
            }

            f.vm.openHostEditor("Host")
            runCurrent()
            f.vm.requestHostUnpair()
            runCurrent()

            // A failed removal leaves the host paired, so its content stays readable.
            f.store.failRemove = true
            f.vm.confirmHostUnpair()
            runCurrent()
            assertTrue(requireNotNull(f.vm.hostState.value.hostEditor).unpairFailed)
            assertEquals(listOf("c1"), f.cache.readConversations("Host").map { it.id })
            assertEquals(thread, f.cache.readThread("Host", "c1"))

            f.store.failRemove = false
            f.vm.confirmHostUnpair()
            runCurrent()

            assertNull(f.store.loadById("Host"))
            assertNull(f.vm.hostState.value.hostEditor)
            assertEquals(emptyList<Conversation>(), f.cache.readConversations("Host"))
            assertEquals(emptyList<ThreadItem>(), f.cache.readThread("Host", "c1"))
            // Every other paired host keeps its content.
            assertEquals(listOf("c1"), f.cache.readConversations("host").map { it.id })
            assertEquals(thread, f.cache.readThread("host", "c1"))
            assertTrue(
                "no server id, conversation id or cached text may reach a log line: $logs",
                logs.none { "Host" in it || "c1" in it || "cached" in it },
            )
        }

    @Test
    fun confirmingUnpairRemovesThatHostsAttachmentFilesBeforeTheConfirmationCloses() =
        runTest(dispatcher) {
            // #900, driven from the gesture through the production removal hook.
            val f = fixture()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            // "Host" and "host" differ only in case and keep an attachment of the same ids.
            for (id in listOf("Host", "host")) keepAttachment(f, id)

            f.vm.openHostEditor("Host")
            runCurrent()
            f.vm.requestHostUnpair()
            runCurrent()
            f.vm.confirmHostUnpair()

            // The pairing is gone but the store's removal is still queued: the confirmation stays open.
            assertNull(f.store.loadById("Host"))
            assertTrue(requireNotNull(f.vm.hostState.value.hostEditor).confirmingUnpair)
            assertEquals(2, f.attachmentsRoot.listFiles()?.size)

            runCurrent()
            assertNull(f.vm.hostState.value.hostEditor)
            assertEquals(1, f.attachmentsRoot.listFiles()?.size)
            var fetches = 0
            f.seeding.retrieve("Host", ATTACHMENT_CONVERSATION, ATTACHMENT) {
                fetches++
                attachmentBytes()
            }
            assertEquals("the removed host's file is gone, so it is fetched again", 1, fetches)
            // Every other paired host keeps its files.
            f.seeding.retrieve("host", ATTACHMENT_CONVERSATION, ATTACHMENT) { error("must not fetch") }
            assertTrue(
                "no server id or attachment id may reach a log line: $logs",
                logs.none { "Host" in it || ATTACHMENT in it },
            )
        }

    @Test
    fun aFailedAttachmentRemovalIsLoggedWithoutAnIdAndTheUnpairStillSucceeds() =
        runTest(dispatcher) {
            val f = fixture()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            keepAttachment(f, "Host")
            f.vm.openHostEditor("Host")
            runCurrent()
            f.vm.requestHostUnpair()
            runCurrent()

            // A read-only root cannot lose the host's directory.
            f.attachmentsRoot.setWritable(false)
            try {
                f.vm.confirmHostUnpair()
                runCurrent()
            } finally {
                f.attachmentsRoot.setWritable(true)
            }

            assertNull(f.store.loadById("Host"))
            assertNull(f.vm.hostState.value.hostEditor)
            assertTrue(logs.contains("event=host_attachments_remove_failed"))
            assertTrue(logs.none { it.contains("host_unpair_failed") })
            assertTrue(
                "no server id or attachment id may reach a log line: $logs",
                logs.none { "Host" in it || ATTACHMENT in it },
            )
        }

    private suspend fun keepAttachment(
        f: Fixture,
        serverId: String,
    ) {
        f.seeding.retrieve(serverId, ATTACHMENT_CONVERSATION, ATTACHMENT) { attachmentBytes() }
    }

    private fun attachmentBytes() =
        AttachmentFetchResult.Fetched(AttachmentContent(listOf(byteArrayOf(1, 2, 3))), "notes.txt", "text/plain")

    @Test
    fun aFailedUnpairStaysOnTheConfirmationAndChangesNothing() =
        runTest(dispatcher) {
            val f = fixture()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            f.prefs.setDefaultWorkspace("Host", "/w/host")
            f.vm.openHostEditor("Host")
            runCurrent()
            f.vm.requestHostUnpair()
            runCurrent()

            f.store.failRemove = true
            f.vm.confirmHostUnpair()
            runCurrent()
            val failed = requireNotNull(f.vm.hostState.value.hostEditor)
            assertTrue(failed.unpairFailed)
            assertFalse(failed.saving)
            // Still on the confirmation, so the shell's OK retries the removal rather than saving a name,
            // and the rename's own flag is untouched so the screen resolves the unpair string.
            assertTrue(failed.confirmingUnpair)
            assertFalse(failed.failed)
            assertEquals("Pyrybox", requireNotNull(f.store.loadById("Host")).displayName)
            assertEquals("/w/host", f.prefs.defaultWorkspace("Host").first())
            assertTrue(logs.any { it.contains("host_unpair_failed") })
            assertTrue(logs.none { it.contains("Host") || it.contains("Pyrybox") })

            // A decline and a second request arriving mid-write are both ignored, so the write's own
            // terminal transition still lands instead of stranding the modal on a step the store never took.
            f.store.failRemove = false
            val gate = CompletableDeferred<Unit>()
            f.store.removeGate = gate
            f.vm.confirmHostUnpair()
            runCurrent()
            f.vm.declineHostUnpair()
            f.vm.requestHostUnpair()
            runCurrent()
            assertTrue(requireNotNull(f.vm.hostState.value.hostEditor).confirmingUnpair)
            gate.complete(Unit)
            runCurrent()
            assertNull(f.vm.hostState.value.hostEditor)
            assertEquals(listOf("Host"), f.store.removals)

            // A failure landing after a dismissal must not resurrect the modal either.
            f.vm.openHostEditor("host")
            runCurrent()
            f.vm.requestHostUnpair()
            runCurrent()
            f.store.failRemove = true
            val late = CompletableDeferred<Unit>()
            f.store.removeGate = late
            f.vm.confirmHostUnpair()
            runCurrent()
            f.vm.dismissHostEditor()
            runCurrent()
            late.complete(Unit)
            runCurrent()
            assertNull(f.vm.hostState.value.hostEditor)
        }

    @Test
    fun dismissClosesTheEditorWithoutWriting() =
        runTest(dispatcher) {
            val f = fixture()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            f.vm.openHostEditor("Host")
            runCurrent()
            assertEquals(
                "Host",
                f.vm.hostState.value.hostEditor
                    ?.serverId,
            )

            f.vm.dismissHostEditor()
            runCurrent()
            assertNull(f.vm.hostState.value.hostEditor)
            assertTrue(f.store.renames.isEmpty())
            assertEquals("Pyrybox", requireNotNull(f.store.loadById("Host")).displayName)
        }

    /**
     * Both hosts hold a chat with the same id and different names (#827). Globally unique ids would hide a
     * rename sent to the wrong host, so every chat-editor test starts from this collision.
     */
    private fun Fixture.seedCollidingChats() {
        a.repo.rows.value = listOf(row("same").copy(name = "A chat"), row("nameless"), row("A-channel", true).copy(name = "A channel"))
        b.repo.rows.value = listOf(row("same").copy(name = "B chat"))
    }

    @Test
    fun chatEditorOpensOnTheRowsOwnHostAndConversationWithoutSelectingOrNavigating() =
        runTest(dispatcher) {
            val f = fixture()
            f.seedCollidingChats()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            backgroundScope.launch(dispatcher) { f.vm.hostNavigationEvents.collect { f.nav += it } }
            runCurrent()

            f.vm.openChatEditor(HostConversationTarget("Host", "same"))
            runCurrent()
            assertEquals(ChatEditorState("Host", "same", "A chat"), f.vm.hostState.value.chatEditor)

            f.vm.openChatEditor(HostConversationTarget("host", "same"))
            runCurrent()
            assertEquals(ChatEditorState("host", "same", "B chat"), f.vm.hostState.value.chatEditor)

            // A chat with no name opens empty — never the list's placeholder text.
            f.vm.openChatEditor(HostConversationTarget("Host", "nameless"))
            runCurrent()
            assertEquals(
                "",
                f.vm.hostState.value.chatEditor
                    ?.initialName,
            )

            // Neither a chat on another host nor a channel is a Chats row this editor can open.
            f.vm.dismissChatEditor()
            for (target in listOf(HostConversationTarget("host", "nameless"), HostConversationTarget("Host", "A-channel"))) {
                f.vm.openChatEditor(target)
                runCurrent()
                assertNull(f.vm.hostState.value.chatEditor)
            }

            assertNull(f.vm.hostState.value.selected)
            assertTrue(f.nav.isEmpty())
            assertTrue(
                f.a.repo.renames
                    .isEmpty() &&
                    f.b.repo.renames
                        .isEmpty(),
            )
        }

    @Test
    fun chatSubmitRenamesOnlyTheEditorsOwnHostWithTheTrimmedNameAndCloses() =
        runTest(dispatcher) {
            val f = fixture()
            f.seedCollidingChats()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }

            // No open editor: nothing to rename.
            f.vm.submitChatName("orphan")
            runCurrent()
            assertTrue(
                f.a.repo.renames
                    .isEmpty(),
            )

            f.vm.openChatEditor(HostConversationTarget("host", "same"))
            runCurrent()
            f.vm.submitChatName("  Renamed  ")
            runCurrent()

            assertEquals(listOf("same" to "Renamed"), f.b.repo.renames)
            // The other host's chat with the same id is never touched.
            assertTrue(
                f.a.repo.renames
                    .isEmpty(),
            )
            assertNull(f.vm.hostState.value.chatEditor)
            val hosts = f.vm.hostState.value.hosts
            assertEquals(
                "Renamed",
                hosts
                    .single { it.host.serverId == "host" }
                    .host.chats
                    .single()
                    .name,
            )
            assertEquals(
                "A chat",
                hosts
                    .single { it.host.serverId == "Host" }
                    .host.chats
                    .first()
                    .name,
            )
        }

    @Test
    fun chatEditorFollowsItsOwnHostsConnectionWithoutClosing() =
        runTest(dispatcher) {
            val f = fixture()
            f.seedCollidingChats()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            f.vm.openChatEditor(HostConversationTarget("Host", "same"))
            runCurrent()
            val open = f.vm.hostState.value.chatEditor
            assertTrue(
                f.vm.hostState.value
                    .isHostConnected("Host"),
            )

            f.a.status.value = ConnectionStatus(RelayLinkStatus.Offline, PyrycodeLinkStatus.Down)
            runCurrent()
            assertFalse(
                f.vm.hostState.value
                    .isHostConnected("Host"),
            )
            // The other host's connection is its own.
            assertTrue(
                f.vm.hostState.value
                    .isHostConnected("host"),
            )
            assertEquals(open, f.vm.hostState.value.chatEditor)

            f.a.status.value = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Handshaking)
            runCurrent()
            assertFalse(
                f.vm.hostState.value
                    .isHostConnected("Host"),
            )

            f.a.status.value = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)
            runCurrent()
            assertTrue(
                f.vm.hostState.value
                    .isHostConnected("Host"),
            )
            assertEquals(open, f.vm.hostState.value.chatEditor)

            // Lost between the last status and the press: nothing is sent, and the modal says it failed.
            f.a.available = false
            f.vm.submitChatName("Renamed")
            runCurrent()
            assertTrue(requireNotNull(f.vm.hostState.value.chatEditor).failed)
            assertTrue(
                f.a.repo.renames
                    .isEmpty() &&
                    f.b.repo.renames
                        .isEmpty(),
            )
        }

    @Test
    fun failedChatRenameStaysOpenQuietlyAndALateCompletionCannotReopen() =
        runTest(dispatcher) {
            val f = fixture()
            f.seedCollidingChats()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            f.vm.openChatEditor(HostConversationTarget("Host", "same"))
            runCurrent()

            f.a.repo.failure = RelayErrorException("server.error", false, "server-secret")
            f.vm.submitChatName("Typed")
            runCurrent()
            val failed = requireNotNull(f.vm.hostState.value.chatEditor)
            assertTrue(failed.failed)
            assertFalse(failed.saving)
            assertEquals("same", failed.conversationId)
            assertEquals(
                "A chat",
                f.a.repo.rows.value!!
                    .first()
                    .name,
            )
            assertTrue(logs.any { "chat_rename_failed" in it })
            assertTrue(
                "no name, id or server message may reach a log line: $logs",
                logs.none { "server-secret" in it || "Typed" in it || "A chat" in it || "same" in it || "Host" in it },
            )

            // Still actionable: a retry that succeeds closes.
            f.a.repo.failure = null
            f.vm.submitChatName("Typed")
            runCurrent()
            assertEquals(listOf("same" to "Typed"), f.a.repo.renames)
            assertNull(f.vm.hostState.value.chatEditor)

            // A write landing after a dismissal must not resurrect the modal, and a second OK mid-write is ignored.
            f.vm.openChatEditor(HostConversationTarget("Host", "same"))
            runCurrent()
            val gate = CompletableDeferred<Unit>()
            f.a.repo.renameGate = gate
            f.vm.submitChatName("Later")
            f.vm.submitChatName("Twice")
            runCurrent()
            assertTrue(requireNotNull(f.vm.hostState.value.chatEditor).saving)
            f.vm.dismissChatEditor()
            gate.complete(Unit)
            runCurrent()
            assertNull(f.vm.hostState.value.chatEditor)
            assertEquals(listOf("same" to "Typed", "same" to "Later"), f.a.repo.renames)
        }

    @Test
    fun dismissingTheChatEditorSendsNothing() =
        runTest(dispatcher) {
            val f = fixture()
            f.seedCollidingChats()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            f.vm.openChatEditor(HostConversationTarget("Host", "same"))
            runCurrent()
            f.vm.dismissChatEditor()
            runCurrent()
            assertNull(f.vm.hostState.value.chatEditor)
            assertTrue(
                f.a.repo.renames
                    .isEmpty() &&
                    f.b.repo.renames
                        .isEmpty(),
            )
            assertEquals(
                "A chat",
                f.a.repo.rows.value!!
                    .first()
                    .name,
            )
        }

    @Test
    fun chatArchiveArchivesOnlyTheEditorsOwnHostAndCloses() =
        runTest(dispatcher) {
            val f = fixture()
            f.seedCollidingChats()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }

            // No open editor: nothing to archive.
            f.vm.archiveChat()
            runCurrent()
            assertTrue(
                f.a.repo.archives
                    .isEmpty() &&
                    f.b.repo.archives
                        .isEmpty(),
            )

            f.vm.openChatEditor(HostConversationTarget("Host", "same"))
            runCurrent()
            f.vm.archiveChat()
            runCurrent()

            assertEquals(listOf("same"), f.a.repo.archives)
            // The other host's chat with the same id is never archived, and nothing else is sent.
            assertTrue(
                f.b.repo.archives
                    .isEmpty(),
            )
            assertTrue(
                f.a.repo.renames
                    .isEmpty() &&
                    f.a.repo.others
                        .isEmpty(),
            )
            assertNull(f.vm.hostState.value.chatEditor)
            val hosts = f.vm.hostState.value.hosts
            assertEquals(
                listOf("nameless"),
                hosts
                    .single { it.host.serverId == "Host" }
                    .host.chats
                    .map { it.id },
            )
            assertEquals(
                listOf("same"),
                hosts
                    .single { it.host.serverId == "host" }
                    .host.chats
                    .map { it.id },
            )
            // Where that host's Archive screen reads it from, and Restore returns it.
            assertEquals(
                listOf("same"),
                f.a.repo
                    .observeConversations(ConversationFilter.Archived)
                    .first()
                    .map { it.id },
            )
            assertTrue(logs.any { "chat_archived" in it })
        }

    @Test
    fun chatArchiveOnAnUnavailableHostFailsWithoutSending() =
        runTest(dispatcher) {
            val f = fixture()
            f.seedCollidingChats()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            f.vm.openChatEditor(HostConversationTarget("Host", "same"))
            runCurrent()

            f.a.available = false
            f.vm.archiveChat()
            runCurrent()

            val editor = requireNotNull(f.vm.hostState.value.chatEditor)
            assertTrue(editor.archiveFailed)
            assertFalse(editor.failed)
            assertFalse(editor.saving)
            assertTrue(
                f.a.repo.archives
                    .isEmpty() &&
                    f.b.repo.archives
                        .isEmpty(),
            )
        }

    @Test
    fun failedChatArchiveStaysOpenQuietlyAndALateCompletionCannotReopen() =
        runTest(dispatcher) {
            val f = fixture()
            f.seedCollidingChats()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            f.vm.openChatEditor(HostConversationTarget("Host", "same"))
            runCurrent()

            for (error in listOf(
                RelayErrorException("server.error", false, "server-secret"),
                IllegalStateException("not connected secret"),
            )) {
                f.a.repo.failure = error
                f.vm.archiveChat()
                runCurrent()
                val failed = requireNotNull(f.vm.hostState.value.chatEditor)
                assertTrue(failed.archiveFailed)
                assertFalse(failed.failed)
                assertFalse(failed.saving)
                assertEquals("same", failed.conversationId)
            }
            // Still active on its host.
            assertEquals(
                false,
                f.a.repo.rows.value!!
                    .first()
                    .archived,
            )
            assertTrue(logs.any { "chat_archive_failed" in it })
            assertTrue(
                "no name, id or server message may reach a log line: $logs",
                logs.none { "secret" in it || "A chat" in it || "same" in it || "Host" in it },
            )

            // A rename after a failed archive shows only the rename's own outcome.
            f.vm.submitChatName("Typed")
            runCurrent()
            assertFalse(requireNotNull(f.vm.hostState.value.chatEditor).archiveFailed)

            // Still actionable: a retry that succeeds closes.
            f.a.repo.failure = null
            f.vm.openChatEditor(HostConversationTarget("Host", "same"))
            runCurrent()
            val gate = CompletableDeferred<Unit>()
            f.a.repo.archiveGate = gate
            f.vm.archiveChat()
            // Mid-write, a second archive and a rename are both ignored.
            f.vm.archiveChat()
            f.vm.submitChatName("Twice")
            runCurrent()
            assertTrue(requireNotNull(f.vm.hostState.value.chatEditor).saving)
            f.vm.dismissChatEditor()
            gate.complete(Unit)
            runCurrent()
            // Landing after the dismissal, it must not resurrect the modal.
            assertNull(f.vm.hostState.value.chatEditor)
            assertEquals(listOf("same"), f.a.repo.archives)
            assertTrue(
                f.a.repo.renames
                    .isEmpty(),
            )
        }

    /**
     * Reads the supplied snapshot until something writes, then the written value.
     *
     * The unpair path clears the removed host's cached workspace through `DataStore.edit`, which the
     * previous read-only stub could not serve. Every other test here supplies a static flow and writes
     * nothing, so they read exactly what they did before.
     */
    @Test
    fun everyRowCarriesItsOwnHostsAttentionAndTappingClearsOnlyThatHost() =
        runTest(dispatcher) {
            val f = fixture()
            backgroundScope.launch(dispatcher) { f.vm.hostState.collect {} }
            f.a.repo.rows.value = listOf(row("chat"), row("other"))
            f.b.repo.rows.value = listOf(row("chat"))
            runCurrent()
            val previews =
                f.vm.hostState.value.hosts[0]
                    .recentChats

            f.a.events.emit(LiveSessionEvent.TurnEnd("chat", "turn-1", "end_turn"))
            f.b.events.emit(LiveSessionEvent.TurnEnd("chat", "turn-1", "end_turn"))
            runCurrent()
            val (hostA, hostB) = f.vm.hostState.value.hosts
            assertEquals(ConversationAttention.Unread, hostA.attentionFor("chat"))
            assertEquals(ConversationAttention.Idle, hostA.attentionFor("other"))
            assertEquals(ConversationAttention.Unread, hostB.attentionFor("chat"))
            // An attention change is not a new snapshot, so the rows and their previews are untouched.
            assertSame(previews, hostA.recentChats)

            f.vm.onHostRowTapped(HostConversationTarget("Host", "chat"))
            runCurrent()
            assertEquals(
                ConversationAttention.Idle,
                f.vm.hostState.value.hosts[0]
                    .attentionFor("chat"),
            )
            assertEquals(
                ConversationAttention.Unread,
                f.vm.hostState.value.hosts[1]
                    .attentionFor("chat"),
            )
        }

    private fun preferences(values: Flow<Preferences>): AppPreferences {
        val written = MutableStateFlow<Preferences?>(null)
        return AppPreferences(
            object : DataStore<Preferences> {
                override val data = combine(values, written) { seeded, edited -> edited ?: seeded }

                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    transform(data.first()).also { written.value = it }
            },
        )
    }

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

        // Bound explicitly: appModule's own binding is the Keystore-backed store, which needs a
        // Context the JVM suite has none of, and the view model now resolves this type.
        val store =
            Store().apply {
                put("Host", "wss://first.example:8443", "Pyrybox")
                put("host", "wss://second.example", null)
            }

        // Hoisted out of the module so a test can seed and read back the host-owned workspace the
        // unpair path clears.
        val prefs = preferences(preferences)

        // #790: hoisted for the same reason, so a test can seed drafts and read back which survived.
        val drafts = ComposerDraftStore()

        // #798: the real file cache, so a test can seed conversation content and read back which host's
        // content survived an unpair. Bound through the production hook below, not a restatement of it.
        val cache = FileConversationCache(tmp.newFolder(), dispatcher)

        // #900: the real attachment store, on a queued dispatcher over the test's scheduler so a test can
        // observe the unpair confirmation still open while the host's files are being removed.
        val attachmentsRoot = tmp.newFolder()
        val attachments = AttachmentStore(attachmentsRoot, StandardTestDispatcher(dispatcher.scheduler))

        // Seeds and reads back over the same root without leaving the test's unconfined dispatcher: a test
        // body resumed from the queued one runs inside its task, where the view model's launches never start.
        val seeding = AttachmentStore(attachmentsRoot, dispatcher)
        val app =
            KoinApplication.init().modules(
                appModule,
                module {
                    single<ConversationRepository> { StableConversationRepository(selected) }
                    single { prefs }
                    single { source }
                    // Wrapped in the real observable decorator (#790), which production always has and
                    // this fixture previously bypassed — the removal-driven draft eviction lives on it.
                    // Transparent to every other case here: delegation forwards the reads, and the
                    // revision it bumps has no observer in this file.
                    single<PairedServerCollectionStore> {
                        ObservablePairedServerStore(
                            store,
                            forgetRemovedHost(drafts, lazyOf(cache), lazyOf(attachments)),
                        )
                    }
                },
            )
        val vm = app.koin.get<ChannelListViewModel>()
        val nav = mutableListOf<HostConversationTarget>()
    }

    private class Host(
        id: String,
    ) {
        val repo = Repo(id)
        var available = true
        val live = MutableStateFlow<ConversationRepository?>(repo)
        val status = MutableStateFlow(ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected))
        val events = MutableSharedFlow<LiveSessionEvent>(extraBufferCapacity = 16)
        val entry = HostConversationConnection(id, "Local $id", live, status, events)
    }

    /** In-memory paired-server store: the two reads and the one write this screen makes, plus gates. */
    private class Store : PairedServerCollectionStore {
        private var entries = emptyList<PairedServerEntry>()
        val renames = mutableListOf<Pair<String, String?>>()
        val removals = mutableListOf<String>()
        var failWrite = false
        var failRemove = false
        var readGate: CompletableDeferred<Unit>? = null
        var writeGate: CompletableDeferred<Unit>? = null
        var removeGate: CompletableDeferred<Unit>? = null

        fun put(
            serverId: String,
            relayUrl: String,
            displayName: String?,
        ) {
            val record = PairedServer(serverId, "token-$serverId", relayUrl, "key-$serverId")
            entries = entries.filterNot { it.record.serverId == serverId } + PairedServerEntry(record, displayName)
        }

        override suspend fun load(): PairedServer? = entries.lastOrNull()?.record

        override suspend fun list(): List<PairedServerEntry> = entries.toList()

        override suspend fun loadById(serverId: String): PairedServerEntry? {
            readGate?.await()
            return entries.find { it.record.serverId == serverId }
        }

        override suspend fun save(record: PairedServer) = error("unused")

        override suspend fun remove(serverId: String) {
            removeGate?.await()
            if (failRemove) throw PairedServerStoreException("test failure")
            removals += serverId
            // Id-exact, as the real store is: a removal can never take a second entry with it.
            entries = entries.filterNot { it.record.serverId == serverId }
        }

        override suspend fun setDisplayName(
            serverId: String,
            displayName: String?,
        ) {
            writeGate?.await()
            if (failWrite) throw PairedServerStoreException("test failure")
            renames += serverId to displayName
            entries = entries.map { if (it.record.serverId == serverId) it.copy(displayName = displayName) else it }
        }
    }

    private class Repo(
        val hostId: String = "replacement",
    ) : ConversationRepository by FakeConversationRepository() {
        val rows = MutableStateFlow<List<Conversation>?>(null)
        val previews = mutableMapOf<String, Flow<Message?>>()
        val workspaces = mutableListOf<String?>()
        var failure: Throwable? = null
        var actionJob: Job? = null
        var createGate: CompletableDeferred<Unit>? = null
        val recents = MutableStateFlow<List<String>>(emptyList())
        val createdFolders = mutableListOf<String>()
        var folderGate: CompletableDeferred<Unit>? = null

        override fun recentWorkspaces(): Flow<List<String>> = recents

        override suspend fun createWorkspaceFolder(name: String): String {
            folderGate?.await()
            failure?.let { throw it }
            createdFolders += name
            return "/$hostId/$name"
        }

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

        // Records every rename and applies it to this repo's own rows, as the daemon's re-emitted list would.
        override suspend fun rename(
            conversationId: String,
            name: String,
        ): Conversation {
            renameGate?.await()
            failure?.let { throw it }
            renames += conversationId to name
            rows.value = rows.value?.map { if (it.id == conversationId) it.copy(name = name) else it }
            return requireNotNull(rows.value).first { it.id == conversationId }
        }

        val renames = mutableListOf<Pair<String, String>>()
        var renameGate: CompletableDeferred<Unit>? = null

        // Records every archive and flips this repo's own row, as the daemon's re-emitted list would (#828).
        override suspend fun archive(conversationId: String) {
            archiveGate?.await()
            failure?.let { throw it }
            archives += conversationId
            rows.value = rows.value?.map { if (it.id == conversationId) it.copy(archived = true) else it }
        }

        // Recorded only so a test can prove the archive path sends neither.
        override suspend fun unarchive(conversationId: String) {
            others += "unarchive"
        }

        override suspend fun delete(conversationId: String) {
            others += "delete"
        }

        val archives = mutableListOf<String>()
        val others = mutableListOf<String>()
        var archiveGate: CompletableDeferred<Unit>? = null
    }

    companion object {
        private const val ATTACHMENT_CONVERSATION = "9d4e7a21-8c05-4f3b-b6e2-1a7c9e30d5f4"
        private const val ATTACHMENT = "7c1d5e92-4a30-4b8f-9e21-6d4c3b0a8f55"

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
