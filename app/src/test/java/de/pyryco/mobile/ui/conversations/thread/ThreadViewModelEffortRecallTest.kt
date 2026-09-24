package de.pyryco.mobile.ui.conversations.thread

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
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
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * #686: one phone-local remembered effort, set only by a successful effort write and recalled at most
 * once per thread opening through the normal write path — never shown on its own, never sent for a
 * session that already has a saved choice, and never added to a message.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelEffortRecallTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val oldSink = RelayLog.sink
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        RelayLog.sink = { _, _, message -> synchronized(logs) { logs += message } }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        RelayLog.sink = oldSink
    }

    // ---- first use and the inherit cases --------------------------------------------------------

    @Test
    fun firstUse_withNothingRemembered_sendsNoWrite_andShowsNothing() =
        runTest {
            val repo = ScriptedRepo()
            val store = MemoryStore(null)
            val vm = collectedVm(repo, store, reading(effort = ""))

            assertTrue(repo.calls.isEmpty())
            assertEquals(EFFORT_PLACEHOLDER_LABEL, vm.state.value.runConfig.effortLabel)
            assertNull(vm.state.value.runConfig.pendingEffort)
            assertTrue(store.writes.isEmpty())
        }

    @Test
    fun aSavedChoice_isPreserved_evenWhenAnotherLevelIsRemembered() =
        runTest {
            val repo = ScriptedRepo()
            val store = MemoryStore("high")
            val vm = collectedVm(repo, store, reading(effort = "low"))

            assertTrue(repo.calls.isEmpty())
            assertEquals("low", vm.state.value.runConfig.effortLabel)
            assertTrue(store.writes.isEmpty())
        }

    @Test
    fun aRememberedLevelTheSelectedRowDoesNotPublish_isNotSent() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo, MemoryStore("xhigh"), reading(effort = ""))

            assertTrue(repo.calls.isEmpty())
            assertEquals(EFFORT_PLACEHOLDER_LABEL, vm.state.value.runConfig.effortLabel)
        }

    @Test
    fun recall_withNoModelOverride_writesALevelTheDefaultRowPublishes() =
        runTest {
            val repo = ScriptedRepo()
            val inheritedMenu = ModelMenu(rows = menu.rows + row("default", listOf("medium", "xhigh")), droppedModels = 0)
            val vm = newVm(repo, MemoryStore("xhigh"), menu = inheritedMenu)
            collect(vm, repo, reading(effort = "", model = ""))

            assertEquals(listOf(SetSessionSettingsPayloadDto(SESSION, effort = "xhigh")), repo.calls)
        }

    @Test
    fun aReadingWithNoSessionToAddress_isNotWritten() =
        runTest {
            val repo = ScriptedRepo()
            repo.liveSession.value = ""
            collectedVm(repo, MemoryStore("high"), reading(effort = "", sessionId = ""))

            assertTrue(repo.calls.isEmpty())
        }

    // ---- recall ---------------------------------------------------------------------------------

    @Test
    fun recall_writesTheRememberedLevelOnce_showsItPending_thenSettlesOnTheReading() =
        runTest {
            val repo = ScriptedRepo()
            val store = MemoryStore("high")
            val gate = CompletableDeferred<Unit>()
            repo.ackGate = gate
            repo.onRefresh = { reading(effort = "high", applied = EffectiveEffort.Applied("high")) }
            val vm = collectedVm(repo, store, reading(effort = ""))

            assertEquals(listOf(SetSessionSettingsPayloadDto(SESSION, effort = "high")), repo.calls)
            assertEquals("high", vm.state.value.runConfig.pendingEffort)
            assertEquals("high", vm.state.value.runConfig.effortLabel)

            gate.complete(Unit)
            runCurrent()

            assertEquals(1, repo.refreshes)
            assertNull(vm.state.value.runConfig.pendingEffort)
            assertEquals("high", vm.state.value.runConfig.effortLabel)
            assertEquals(listOf("high"), store.writes)

            assertTrue(
                "decisions are logged by static code only",
                synchronized(logs) { logs.contains("event=effort_recall outcome=started") && logs.none { "high" in it || SESSION in it } },
            )

            // Later readings of the same opening never write again.
            repo.readings.emit(reading(effort = ""))
            runCurrent()
            assertEquals(1, repo.calls.size)
        }

    @Test
    fun aRejectedRecall_revertsAndSignals_remembersNothing_andIsNotRetried() =
        runTest {
            val repo = ScriptedRepo()
            val store = MemoryStore("high")
            repo.failWith = RelayErrorException("session.not_found", false, "secret")
            val errors = mutableListOf<Unit>()
            val vm = newVm(repo, store)
            backgroundScope.launch { vm.sessionSettingsErrors.collect { errors += it } }
            collect(vm, repo, reading(effort = ""))

            assertEquals(1, repo.calls.size)
            assertEquals(1, errors.size)
            assertNull(vm.state.value.runConfig.pendingEffort)
            assertEquals(EFFORT_PLACEHOLDER_LABEL, vm.state.value.runConfig.effortLabel)
            assertTrue(store.writes.isEmpty())

            repo.failWith = null
            repo.readings.emit(reading(effort = ""))
            runCurrent()

            assertEquals("a later reading in the same opening does not retry", 1, repo.calls.size)
        }

    @Test
    fun aFreshOpening_triesAgainAfterARejectedRecall() =
        runTest {
            val store = MemoryStore("high")
            val first = ScriptedRepo()
            first.failWith = IllegalStateException("offline")
            collectedVm(first, store, reading(effort = ""))
            assertEquals(1, first.calls.size)

            val second = ScriptedRepo()
            collectedVm(second, store, reading(effort = ""))
            assertEquals(listOf(SetSessionSettingsPayloadDto(SESSION, effort = "high")), second.calls)
        }

    @Test
    fun anEffortTapBeforeTheDecision_cancelsTheRecall() =
        runTest {
            val repo = ScriptedRepo()
            val store = MemoryStore("high")
            val vm = newVm(repo, store, menu = null)
            collect(vm, repo, reading(effort = ""))

            vm.onEffortSelected("low") // the menu has not arrived, so the recall is still undecided
            runCurrent()
            repo.backing.setModelMenu(CONV, menu)
            repo.readings.emit(reading(effort = "low"))
            runCurrent()

            assertEquals(listOf(SetSessionSettingsPayloadDto(SESSION, effort = "low")), repo.calls)
            assertEquals(listOf("low"), store.writes)
        }

    @Test
    fun anOutstandingModelTap_defersTheDecisionUntilAReadingSettlesIt() =
        runTest {
            val repo = ScriptedRepo()
            val gate = CompletableDeferred<Unit>()
            val vm = newVm(repo, MemoryStore("high"), menu = null)
            collect(vm, repo, reading(effort = ""))
            repo.ackGate = gate
            vm.onModelSelected("sonnet")
            repo.backing.setModelMenu(CONV, menu)
            runCurrent()

            assertEquals("only the model write is in flight", 1, repo.calls.size)

            repo.ackGate = null
            gate.complete(Unit)
            repo.readings.emit(reading(effort = "", model = "sonnet"))
            runCurrent()

            assertEquals(
                listOf(
                    SetSessionSettingsPayloadDto(SESSION, model = "sonnet"),
                    SetSessionSettingsPayloadDto(SESSION, effort = "high"),
                ),
                repo.calls,
            )
        }

    // ---- what is remembered ---------------------------------------------------------------------

    @Test
    fun aSuccessfulTap_isRemembered_butAFailedTapAndAModelWriteAreNot() =
        runTest {
            val repo = ScriptedRepo()
            val store = MemoryStore(null)
            val vm = collectedVm(repo, store, reading(effort = "low"))

            repo.failWith = IllegalStateException("offline")
            vm.onEffortSelected("max")
            runCurrent()
            assertTrue(store.writes.isEmpty())

            repo.failWith = null
            vm.onModelSelected("sonnet")
            repo.readings.emit(reading(effort = "low", model = "sonnet"))
            runCurrent()
            assertTrue("a model-only write remembers nothing", store.writes.isEmpty())

            vm.onEffortSelected("high")
            runCurrent()
            assertEquals(listOf("high"), store.writes)
        }

    @Test
    fun passiveReadings_neverChangeTheRememberedLevel() =
        runTest {
            val repo = ScriptedRepo()
            val store = MemoryStore("high")
            collectedVm(repo, store, reading(effort = "low", applied = EffectiveEffort.Applied("max")))
            repo.readings.emit(reading(effort = "", applied = EffectiveEffort.NotReported))
            repo.readings.emit(reading(effort = "max", applied = EffectiveEffort.Applied("low")))
            runCurrent()

            assertTrue(repo.calls.isEmpty())
            assertTrue(store.writes.isEmpty())
            assertEquals("high", store.level)
        }

    @Test
    fun aSuccessfulTap_survivesAnAppRestart() =
        runTest {
            val file = tmp.newFile("remembered_effort_vm.preferences_pb")
            val scope1 = CoroutineScope(Dispatchers.IO + Job())
            val prefs1 = AppPreferences(PreferenceDataStoreFactory.create(scope = scope1, produceFile = { file }))
            val repo = ScriptedRepo()
            val vm = collectedVm(repo, prefs1.asRememberedEffortStore(), reading(effort = "low"))

            vm.onEffortSelected("max")
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { prefs1.rememberedEffort.first { it == "max" } }
            }
            scope1.cancel()

            val scope2 = CoroutineScope(Dispatchers.IO + Job())
            val prefs2 = AppPreferences(PreferenceDataStoreFactory.create(scope = scope2, produceFile = { file }))
            val restored = withContext(Dispatchers.Default) { prefs2.rememberedEffort.first() }
            scope2.cancel()

            assertEquals("max", restored)
        }

    // ---- the first message ----------------------------------------------------------------------

    @Test
    fun aMessageSentDuringTheRecallWrite_waitsForItsAck() =
        runTest {
            val repo = ScriptedRepo()
            val gate = CompletableDeferred<Unit>()
            repo.ackGate = gate
            val vm = collectedVm(repo, MemoryStore("high"), reading(effort = ""))

            vm.sendMessage("hello")
            runCurrent()
            assertEquals(0, repo.sentMessages)

            gate.complete(Unit)
            runCurrent()
            assertEquals(1, repo.sentMessages)
        }

    @Test
    fun aMessageSentDuringAFailingRecallWrite_isSentAfterTheFailure() =
        runTest {
            val repo = ScriptedRepo()
            val gate = CompletableDeferred<Unit>()
            repo.ackGate = gate
            repo.failWith = IllegalStateException("offline")
            val vm = collectedVm(repo, MemoryStore("high"), reading(effort = ""))

            vm.sendMessage("hello")
            runCurrent()
            assertEquals(0, repo.sentMessages)

            gate.complete(Unit)
            runCurrent()
            assertEquals(1, repo.sentMessages)
        }

    @Test
    fun aMessageSentBeforeAnyReading_isNotHeld() =
        runTest {
            val repo = ScriptedRepo()
            val vm = newVm(repo, MemoryStore("high"))
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()

            vm.sendMessage("hello")
            runCurrent()

            assertEquals(1, repo.sentMessages)
            assertTrue(repo.calls.isEmpty())
        }

    // ---- isolation ------------------------------------------------------------------------------

    @Test
    fun twoConversations_onlyTheUnsetOneIsWritten_atItsOwnSession() =
        runTest {
            val store = MemoryStore("high")
            val unset = ScriptedRepo(conversationId = CONV, session = SESSION)
            val saved = ScriptedRepo(conversationId = OTHER_CONV, session = OTHER_SESSION)

            collectedVm(unset, store, reading(effort = ""), conversationId = CONV)
            collectedVm(saved, store, reading(effort = "low", sessionId = OTHER_SESSION), conversationId = OTHER_CONV)

            assertEquals(listOf(SetSessionSettingsPayloadDto(SESSION, effort = "high")), unset.calls)
            assertTrue(saved.calls.isEmpty())
        }

    @Test
    fun aReadingForAReplacedSession_triggersNothing_untilTheLiveSessionsReadingArrives() =
        runTest {
            val repo = ScriptedRepo()
            repo.liveSession.value = "sess-b"
            val store = MemoryStore("high")
            collectedVm(repo, store, reading(effort = "", sessionId = SESSION))

            assertTrue(repo.calls.isEmpty())

            repo.readings.emit(reading(effort = "", sessionId = "sess-b"))
            runCurrent()

            assertEquals(listOf(SetSessionSettingsPayloadDto("sess-b", effort = "high")), repo.calls)
        }

    // ---- fixtures -------------------------------------------------------------------------------

    private fun newVm(
        repo: ScriptedRepo,
        store: RememberedEffortStore,
        menu: ModelMenu? = this.menu,
        conversationId: String = CONV,
    ): ThreadViewModel {
        repo.backing.setModelMenu(conversationId, menu)
        return ThreadViewModel(
            SavedStateHandle(mapOf("serverId" to "host-a", "conversationId" to conversationId)),
            repo,
            FakeConnectionStateSource(),
            ComposerDraftStore(),
            rememberedEffort = store,
        )
    }

    private fun TestScope.collect(
        vm: ThreadViewModel,
        repo: ScriptedRepo,
        initial: SessionSettings,
    ) {
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        repo.readings.tryEmit(initial)
        runCurrent()
    }

    private fun TestScope.collectedVm(
        repo: ScriptedRepo,
        store: RememberedEffortStore,
        initial: SessionSettings,
        conversationId: String = CONV,
    ): ThreadViewModel {
        val vm = newVm(repo, store, conversationId = conversationId)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        repo.readings.tryEmit(initial)
        runCurrent()
        return vm
    }

    private fun reading(
        effort: String,
        applied: EffectiveEffort = EffectiveEffort.Unavailable,
        sessionId: String = SESSION,
        model: String = "opus",
    ) = SessionSettings(
        sessionId = sessionId,
        model = model,
        effort = effort,
        effectiveEffort = applied,
        permissionMode = "",
        yolo = false,
        usedTokens = 0,
        windowTokens = 0,
    )

    private val menu =
        ModelMenu(
            rows =
                listOf(
                    row("opus", listOf("low", "high", "max")),
                    row("sonnet", listOf("low", "high")),
                ),
            droppedModels = 0,
        )

    private fun row(
        value: String,
        levels: List<String>,
    ) = ModelMenuRow(
        resolvedModel = "",
        value = value,
        displayName = value,
        effortLevels = levels,
        supportsAutoMode = false,
        truncatedFields = null,
    )

    private class MemoryStore(
        var level: String?,
    ) : RememberedEffortStore {
        val writes = mutableListOf<String>()

        override suspend fun read(): String? = level

        override suspend fun remember(level: String) {
            writes += level
            this.level = level
        }
    }

    /** Readings arrive when the test emits them; a refresh is answered by [onRefresh]. */
    private class ScriptedRepo(
        val conversationId: String = CONV,
        session: String = SESSION,
        val backing: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by backing {
        val readings = MutableSharedFlow<SessionSettings?>(replay = 1, extraBufferCapacity = 64)
        val liveSession = MutableStateFlow(session)
        val calls = mutableListOf<SetSessionSettingsPayloadDto>()
        var refreshes = 0
        var sentMessages = 0
        var onRefresh: (Int) -> SessionSettings? = { null }
        var ackGate: CompletableDeferred<Unit>? = null
        var failWith: Throwable? = null

        override fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> = readings

        override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
            combine(backing.observeConversations(filter), liveSession) { list, session ->
                list.map { if (it.id == conversationId) it.copy(currentSessionId = session) else it }
            }

        override fun refreshSessionSettings(conversationId: String) {
            refreshes++
            onRefresh(refreshes)?.let { readings.tryEmit(it) }
        }

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
        ): Message {
            sentMessages++
            return backing.sendMessage(conversationId, text)
        }

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
            attachments: List<MessageAttachment>,
        ): Message {
            sentMessages++
            return backing.sendMessage(conversationId, text, attachments)
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
        const val OTHER_CONV = "seed-channel-pyrycode-mobile"
        const val SESSION = "sess-a"
        const val OTHER_SESSION = "sess-other"
    }
}
