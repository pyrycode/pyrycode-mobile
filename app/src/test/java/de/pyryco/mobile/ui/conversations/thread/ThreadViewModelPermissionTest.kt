package de.pyryco.mobile.ui.conversations.thread

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.SetSessionSettingsPayloadDto
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ModelMenu
import de.pyryco.mobile.data.repository.ModelMenuRow
import de.pyryco.mobile.data.repository.SessionFacts
import de.pyryco.mobile.data.repository.SessionSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * #650: the composer's permission control. The label follows only the confirmed reading, a write sends
 * one posture field, an ack runs desktop #1544's settle rule, and a context change cancels it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelPermissionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val logs = mutableListOf<String>()
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
    }

    @Test
    fun label_followsTheReading_andIsHiddenWithoutAConfirmation() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo)

            assertNull("no reading: hidden", permissionModeLabel(vm.state.value.runConfig))
            // A non-empty session id beside "" and yolo=false proves nothing about approvals.
            repo.readings.emit(reading(""))
            assertEquals("", vm.state.value.runConfig.permissionMode)
            assertNull(permissionModeLabel(vm.state.value.runConfig))
            repo.readings.emit(reading("bypassPermissions", yolo = true))
            assertEquals("Bypass approvals", permissionModeLabel(vm.state.value.runConfig))
            repo.readings.emit(reading("plan"))
            assertEquals("Plan", permissionModeLabel(vm.state.value.runConfig))
        }

    @Test
    fun bypass_sendsYoloTrueWithNoPermissionMode() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo, reading("plan"))

            vm.onPermissionModeSelected("bypassPermissions")

            assertEquals(listOf(SetSessionSettingsPayloadDto(SESSION, yolo = true)), repo.calls)
        }

    @Test
    fun otherModes_sendTheirWireValueWithNoYolo() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo, reading("bypassPermissions", yolo = true))

            vm.onPermissionModeSelected("acceptEdits")

            assertEquals(listOf(SetSessionSettingsPayloadDto(SESSION, permissionMode = "acceptEdits")), repo.calls)
        }

    @Test
    fun nothingIsSent_forTheConfirmedMode_anUnknownValue_orAnUnsupportedAuto() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo, reading("plan"))

            vm.onPermissionModeSelected("plan")
            vm.onPermissionModeSelected("someFutureMode")
            vm.onPermissionModeSelected("")
            vm.onPermissionModeSelected("auto") // the selected row does not support it

            assertTrue(repo.calls.isEmpty())
        }

    @Test
    fun auto_isSent_whenTheSelectedRowSupportsIt() =
        runTest {
            val repo = ScriptedRepo()
            repo.backing.setModelMenu(CONV, ModelMenu(listOf(autoRow), droppedModels = 0))
            val vm = collectedVm(repo, reading("default", model = autoRow.value))

            vm.onPermissionModeSelected("auto")

            assertEquals(listOf(SetSessionSettingsPayloadDto(SESSION, permissionMode = "auto")), repo.calls)
        }

    @Test
    fun nothingIsSent_withoutASessionOrWithoutAConfirmedMode() =
        runTest {
            val repo = ScriptedRepo()
            repo.liveSession.value = ""
            val vm = collectedVm(repo, reading("plan", sessionId = ""))

            vm.onPermissionModeSelected("default")
            repo.readings.emit(reading(""))
            vm.onPermissionModeSelected("default")

            assertTrue(repo.calls.isEmpty())
        }

    @Test
    fun label_staysOnTheConfirmedReading_whilePendingAndAfterTheAck() =
        runTest {
            val repo = ScriptedRepo()
            val gate = CompletableDeferred<Unit>()
            repo.ackGate = gate
            val vm = collectedVm(repo, reading("plan"))

            vm.onPermissionModeSelected("default")
            assertEquals("plan", vm.state.value.runConfig.permissionMode)
            assertEquals("default", vm.state.value.runConfig.pendingPermission)
            assertFalse(
                "no second write while one is outstanding",
                footerControlEnabled(FooterControl.Permission, vm.state.value.runConfig),
            )
            vm.onPermissionModeSelected("acceptEdits")
            assertEquals(1, repo.calls.size)

            gate.complete(Unit)
            runCurrent()
            assertEquals("the ack is not a reading", "plan", vm.state.value.runConfig.permissionMode)
            assertEquals("default", vm.state.value.runConfig.pendingPermission)
        }

    @Test
    fun settle_rereadsAtOnce_thenEvery500ms_andStopsOnTheRequestedMode() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo, reading("plan"))
            repo.onRefresh = { n -> if (n < 3) reading("plan") else reading("default") }

            vm.onPermissionModeSelected("default")
            assertEquals("an immediate re-read after the ack", 1, repo.refreshes)
            assertEquals("an early old-mode reading changes nothing", "plan", vm.state.value.runConfig.permissionMode)
            advanceTimeBy(PERMISSION_SETTLE_INTERVAL_MS - 1)
            runCurrent()
            assertEquals(1, repo.refreshes)
            advanceTimeBy(1)
            runCurrent()
            assertEquals("nor ends the retries", 2, repo.refreshes)
            advanceTimeBy(PERMISSION_SETTLE_INTERVAL_MS)
            runCurrent()
            assertEquals(3, repo.refreshes)

            assertEquals("default", vm.state.value.runConfig.permissionMode)
            assertEquals("Manual approval", permissionModeLabel(vm.state.value.runConfig))
            assertNull(vm.state.value.runConfig.pendingPermission)
            advanceTimeBy(PERMISSION_SETTLE_WINDOW_MS)
            runCurrent()
            assertEquals("no read after the requested mode was reported", 3, repo.refreshes)
            assertTrue("event=permission_write outcome=acked" in logs)
            assertTrue("event=permission_settle outcome=confirmed reads=3" in logs)
        }

    @Test
    fun settle_givesUpAfterTheWindow_andLeavesTheLastReadingShowing() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo, reading("plan"))
            repo.onRefresh = { reading("plan") }

            vm.onPermissionModeSelected("default")
            advanceTimeBy(PERMISSION_SETTLE_WINDOW_MS + 1)
            runCurrent()
            val reads = repo.refreshes
            assertTrue("about every 500 ms for 15 s: $reads", reads in 29..31)
            assertNull(vm.state.value.runConfig.pendingPermission)
            assertEquals("plan", vm.state.value.runConfig.permissionMode)

            advanceTimeBy(PERMISSION_SETTLE_WINDOW_MS)
            runCurrent()
            assertEquals(reads, repo.refreshes)
            assertTrue(logs.any { it.startsWith("event=permission_settle outcome=expired") })
        }

    @Test
    fun settle_readsAreSerialized() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo, reading("plan"))
            repo.onRefresh = { null } // the first read never answers

            vm.onPermissionModeSelected("default")
            advanceTimeBy(5_000)
            runCurrent()

            assertEquals("no second read while the first is outstanding", 1, repo.refreshes)
        }

    @Test
    fun refusalOrSendFailure_rereadsOnce_andSurfacesTheFailureSignal() =
        runTest {
            for (failure in listOf(RelayErrorException("session.not_found", false, "secret"), IllegalStateException("offline"))) {
                val repo = ScriptedRepo()
                repo.failWith = failure
                val vm = collectedVm(repo, reading("plan"))
                val errors = mutableListOf<Unit>()
                val collector = launch { vm.sessionSettingsErrors.collect { errors += it } }

                vm.onPermissionModeSelected("default")
                advanceTimeBy(PERMISSION_SETTLE_WINDOW_MS)
                runCurrent()

                assertEquals("one re-read", 1, repo.refreshes)
                assertEquals("one failure signal", 1, errors.size)
                assertEquals("plan", vm.state.value.runConfig.permissionMode)
                assertNull(vm.state.value.runConfig.pendingPermission)
                collector.cancel()
            }
            assertTrue(logs.none { "secret" in it || "offline" in it })
        }

    @Test
    fun aNullReading_hidesTheButton_andCancelsTheRetries() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo, reading("plan"))
            repo.onRefresh = { reading("plan") }
            vm.onPermissionModeSelected("default")
            advanceTimeBy(PERMISSION_SETTLE_INTERVAL_MS)
            runCurrent()

            repo.readings.emit(null) // a host switch or an owning-host reconnect
            val reads = repo.refreshes
            assertNull(permissionModeLabel(vm.state.value.runConfig))
            assertNull(vm.state.value.runConfig.pendingPermission)
            advanceTimeBy(PERMISSION_SETTLE_WINDOW_MS)
            runCurrent()

            assertEquals("no retry after the context changed", reads, repo.refreshes)
        }

    @Test
    fun aReadingForAnotherSession_cancelsTheRetries() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo, reading("plan"))
            repo.onRefresh = { reading("plan") }
            vm.onPermissionModeSelected("default")

            repo.liveSession.value = "sess-new"
            repo.readings.emit(reading("", sessionId = "sess-new"))
            val reads = repo.refreshes
            advanceTimeBy(PERMISSION_SETTLE_WINDOW_MS)
            runCurrent()

            assertEquals(reads, repo.refreshes)
            assertNull(vm.state.value.runConfig.pendingPermission)
        }

    @Test
    fun aLateAckFromThePreviousContext_startsNoSettle() =
        runTest {
            val repo = ScriptedRepo()
            val gate = CompletableDeferred<Unit>()
            repo.ackGate = gate
            val vm = collectedVm(repo, reading("plan"))
            vm.onPermissionModeSelected("default")

            repo.readings.emit(null)
            repo.readings.emit(reading("plan"))
            gate.complete(Unit)
            advanceTimeBy(PERMISSION_SETTLE_WINDOW_MS)
            runCurrent()

            assertEquals("the abandoned write never re-reads", 0, repo.refreshes)
            assertEquals("plan", vm.state.value.runConfig.permissionMode)
            assertNull(vm.state.value.runConfig.pendingPermission)
        }

    @Test
    fun aSessionReset_hidesTheOldMode_untilTheNewSessionReports_butKeepsModelAndEffort() =
        runTest {
            val repo = ScriptedRepo()
            repo.backing.setModelMenu(CONV, ModelMenu(listOf(autoRow), droppedModels = 0))
            val vm = collectedVm(repo, reading("plan", model = autoRow.value))
            assertEquals("Plan", permissionModeLabel(vm.state.value.runConfig))

            repo.liveSession.value = "sess-new" // session_transition lands before the re-read
            assertNull(permissionModeLabel(vm.state.value.runConfig))
            assertEquals("Opus", vm.state.value.runConfig.modelLabel)
            vm.onPermissionModeSelected("default")
            assertTrue("a hidden control sends nothing", repo.calls.isEmpty())

            repo.readings.emit(reading("acceptEdits", sessionId = "sess-new", model = autoRow.value))
            assertEquals("Auto-approve edits", permissionModeLabel(vm.state.value.runConfig))
        }

    /**
     * #687: the current daemon acknowledges a `default` write without touching a child whose stored mode is
     * already `default`, even when that child runs in operator bypass. The ack is not a reading, so every
     * re-read still reports bypass, and the label ends where it started once the settle window runs out.
     */
    @Test
    fun aNoOpDefaultAck_leavesTheConfirmedBypass_withNoPendingMark() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo, reading("bypassPermissions", yolo = true))
            repo.onRefresh = { reading("bypassPermissions", yolo = true) }

            vm.onPermissionModeSelected("default")
            advanceTimeBy(PERMISSION_SETTLE_WINDOW_MS + 1)
            runCurrent()

            assertEquals(listOf(SetSessionSettingsPayloadDto(SESSION, permissionMode = "default")), repo.calls)
            assertTrue("event=permission_write outcome=acked" in logs)
            assertTrue(logs.any { it.startsWith("event=permission_settle outcome=expired") })
            assertEquals("bypassPermissions", vm.state.value.runConfig.permissionMode)
            assertEquals("Bypass approvals", permissionModeLabel(vm.state.value.runConfig))
            assertNull(vm.state.value.runConfig.pendingPermission)
        }

    /**
     * #687: a reading with a session but no confirmation (`""`, `yolo=false`) shows no control, even beside
     * claude's own `session_facts` claim of `default` and with Settings → Default YOLO on. The preferences
     * reach the view model the way production passes them, through [asRememberedEffortStore].
     */
    @Test
    fun anUnknownReading_showsNoControl_despiteAFactsClaimAndDefaultYolo() =
        runTest {
            val storeScope = CoroutineScope(Dispatchers.IO + Job())
            val file = tmp.root.resolve("prefs.preferences_pb")
            val preferences = AppPreferences(PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { file }))
            preferences.setDefaultYolo(true)
            assertTrue("Default YOLO is on", preferences.defaultYolo.first())
            val repo = ScriptedRepo()
            repo.facts.value = SessionFacts(claudeCodeVersion = "2.1.0", permissionMode = "default", truncatedFields = null)
            val vm = collectedVm(repo, reading("", yolo = false), preferences.asRememberedEffortStore())

            assertEquals(SESSION, vm.state.value.runConfig.sessionId)
            assertEquals("", vm.state.value.runConfig.permissionMode)
            assertNull(permissionModeLabel(vm.state.value.runConfig))
            PermissionModeOption.entries.forEach { vm.onPermissionModeSelected(it.wire) }
            assertTrue("a hidden control sends nothing", repo.calls.isEmpty())
            storeScope.cancel()
        }

    // ---- fixtures -------------------------------------------------------------------------------

    private fun TestScope.collectedVm(
        repo: ScriptedRepo,
        initial: SessionSettings? = null,
        rememberedEffort: RememberedEffortStore = RememberedEffortStore.None,
    ): ThreadViewModel {
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("serverId" to "host-a", "conversationId" to CONV)),
                repo,
                FakeConnectionStateSource(),
                ComposerDraftStore(),
                rememberedEffort = rememberedEffort,
            )
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        if (initial != null) repo.readings.tryEmit(initial)
        runCurrent()
        return vm
    }

    private fun reading(
        permissionMode: String,
        sessionId: String = SESSION,
        yolo: Boolean = false,
        model: String = "",
    ) = SessionSettings(
        sessionId = sessionId,
        model = model,
        effort = "",
        effectiveEffort = EffectiveEffort.Unavailable,
        permissionMode = permissionMode,
        yolo = yolo,
        usedTokens = 0,
        windowTokens = 0,
    )

    private val autoRow =
        ModelMenuRow(
            resolvedModel = "",
            value = "opus",
            displayName = "Opus",
            effortLevels = listOf("high"),
            supportsAutoMode = true,
            truncatedFields = null,
        )

    /**
     * A settings wire the test drives: readings arrive when the test emits them, a refresh is answered by
     * [onRefresh] (`null` holds the read), and the conversation's current session is [liveSession].
     */
    private class ScriptedRepo(
        val backing: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by backing {
        val readings = MutableSharedFlow<SessionSettings?>(replay = 1, extraBufferCapacity = 64)
        val liveSession = MutableStateFlow(SESSION)
        val facts = MutableStateFlow<SessionFacts?>(null)
        val calls = mutableListOf<SetSessionSettingsPayloadDto>()
        var refreshes = 0
        var onRefresh: (Int) -> SessionSettings? = { null }
        var ackGate: CompletableDeferred<Unit>? = null
        var failWith: Throwable? = null

        override fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> = readings

        override fun observeSessionFacts(conversationId: String): Flow<SessionFacts?> = facts

        override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
            combine(backing.observeConversations(filter), liveSession) { list, session ->
                list.map { if (it.id == CONV) it.copy(currentSessionId = session) else it }
            }

        override fun refreshSessionSettings(conversationId: String) {
            refreshes++
            onRefresh(refreshes)?.let { readings.tryEmit(it) }
        }

        override suspend fun setSessionSettings(
            sessionId: String,
            model: String?,
            effort: String?,
            yolo: Boolean?,
            permissionMode: String?,
        ) {
            calls += SetSessionSettingsPayloadDto(sessionId, model, effort, yolo, permissionMode)
            ackGate?.await()
            failWith?.let { throw it }
        }
    }

    private companion object {
        const val CONV = "seed-channel-personal"
        const val SESSION = "sess-a"
    }
}
