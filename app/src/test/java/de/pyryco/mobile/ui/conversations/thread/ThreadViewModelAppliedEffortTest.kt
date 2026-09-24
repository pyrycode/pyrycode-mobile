package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.SetSessionSettingsPayloadDto
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ModelMenu
import de.pyryco.mobile.data.repository.ModelMenuRow
import de.pyryco.mobile.data.repository.SessionSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #889: the effort control shows Claude's applied effort once the daemon reports it, and the saved choice
 * only when that reading is missing. Display-only — nothing here may cause a write or a turn.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelAppliedEffortTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun appliedValueWins_overADisagreeingSavedChoice() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo, reading(effort = "high", applied = EffectiveEffort.Applied("low")))

            val config = vm.state.value.runConfig
            assertEquals("low", config.effortLabel)
            assertEquals("low", config.selectedEffort)
            assertNull(config.effortNote)
        }

    @Test
    fun anOlderDaemonOmittingTheKey_showsTheSavedChoiceAndExplainsIt() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo, reading(effort = "high", applied = EffectiveEffort.Unavailable))

            val config = vm.state.value.runConfig
            assertEquals("high", config.effortLabel)
            assertEquals(EffortNote.SelectedRunningUnavailable, config.effortNote)
        }

    @Test
    fun aReadingAlone_sendsNoWrite_noRefresh_andNoMessage() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo, reading(effort = "", applied = EffectiveEffort.Applied("high")))
            repo.readings.emit(reading(effort = "", applied = EffectiveEffort.NotReported))
            repo.readings.emit(reading(effort = "max", applied = EffectiveEffort.Unavailable))
            runCurrent()

            assertEquals("max", vm.state.value.runConfig.effortLabel)
            assertTrue(repo.calls.isEmpty())
            assertEquals(0, repo.refreshes)
            assertEquals(0, repo.sentMessages)
        }

    @Test
    fun aLaterReadingOmittingTheKey_dropsTheEarlierAppliedValue() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo, reading(effort = "", applied = EffectiveEffort.Applied("max")))
            assertEquals("max", vm.state.value.runConfig.effortLabel)

            repo.readings.emit(reading(effort = "", applied = EffectiveEffort.Unavailable))

            assertEquals("Effort", vm.state.value.runConfig.effortLabel)
            assertEquals(EffortNote.DefaultRunningUnavailable, vm.state.value.runConfig.effortNote)
        }

    @Test
    fun aLateReplyForAReplacedSession_neverShowsItsAppliedValue() =
        runTest {
            val repo = ScriptedRepo()
            repo.liveSession.value = "sess-b"
            val vm = collectedVm(repo, reading(effort = "high", applied = EffectiveEffort.Applied("max"), sessionId = SESSION))

            val config = vm.state.value.runConfig
            assertEquals("high", config.effortLabel)
            assertEquals(EffortNote.SelectedRunningUnavailable, config.effortNote)

            // Once a reading for the live session lands, its applied value shows.
            repo.readings.emit(reading(effort = "high", applied = EffectiveEffort.Applied("low"), sessionId = "sess-b"))
            assertEquals("low", vm.state.value.runConfig.effortLabel)
        }

    @Test
    fun aHostSwitchOrReconnect_showsNoStaleAppliedValue() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo, reading(effort = "", applied = EffectiveEffort.Applied("max")))

            repo.readings.emit(null) // the head of a fresh subscription

            assertEquals(UNKNOWN_RUN_CONFIG_LABEL, vm.state.value.runConfig.effortLabel)
            assertNull(vm.state.value.runConfig.effortNote)
        }

    @Test
    fun aPendingTapShows_thenTheConfirmedWritesRefreshSettlesOnTheAppliedValue() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo, reading(effort = "low", applied = EffectiveEffort.Applied("max")))
            val gate = CompletableDeferred<Unit>()
            repo.ackGate = gate
            // Claude has not yet run the new level: the fresh reading still applies "max".
            repo.onRefresh = { reading(effort = "high", applied = EffectiveEffort.Applied("max")) }

            vm.onEffortSelected("high")
            assertEquals("high", vm.state.value.runConfig.effortLabel)
            assertEquals(listOf(SetSessionSettingsPayloadDto(SESSION, effort = "high")), repo.calls)

            gate.complete(Unit)
            runCurrent()

            assertEquals(1, repo.refreshes)
            assertEquals("the acknowledged tap does not mask the fresh applied value", "max", vm.state.value.runConfig.effortLabel)
        }

    @Test
    fun aRejectedWrite_restoresTheAppliedValue() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo, reading(effort = "low", applied = EffectiveEffort.Applied("max")))
            repo.failWith = RelayErrorException("session.not_found", false, "secret")

            vm.onEffortSelected("high")

            assertNull(vm.state.value.runConfig.pendingEffort)
            assertEquals("max", vm.state.value.runConfig.effortLabel)
            assertEquals("max", vm.state.value.runConfig.selectedEffort)
        }

    @Test
    fun aRejectedWrite_restoresTheSavedFallback() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo, reading(effort = "low", applied = EffectiveEffort.Unavailable))
            repo.failWith = IllegalStateException("offline")

            vm.onEffortSelected("high")

            assertEquals("low", vm.state.value.runConfig.effortLabel)
            assertEquals(EffortNote.SelectedRunningUnavailable, vm.state.value.runConfig.effortNote)
        }

    @Test
    fun aRejectedWrite_restoresNoSelection() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo, reading(effort = "high", applied = EffectiveEffort.NotReported))
            repo.failWith = IllegalStateException("offline")

            vm.onEffortSelected("high")

            assertEquals(listOf(SetSessionSettingsPayloadDto(SESSION, effort = "high")), repo.calls)
            assertEquals("Effort", vm.state.value.runConfig.effortLabel)
            assertEquals("", vm.state.value.runConfig.selectedEffort)
            assertEquals(EffortNote.NotReported, vm.state.value.runConfig.effortNote)
        }

    @Test
    fun theWriteArgumentIsTheTappedLevel_neverTheAppliedValue() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo, reading(effort = "", applied = EffectiveEffort.Applied("max")))

            vm.onEffortSelected("max") // already what Claude runs: nothing to send
            vm.onEffortSelected("low")

            assertEquals(listOf(SetSessionSettingsPayloadDto(SESSION, effort = "low")), repo.calls)
        }

    // ---- fixtures -------------------------------------------------------------------------------

    private fun TestScope.collectedVm(
        repo: ScriptedRepo,
        initial: SessionSettings,
    ): ThreadViewModel {
        repo.backing.setModelMenu(CONV, ModelMenu(rows = listOf(opusRow), droppedModels = 0))
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("serverId" to "host-a", "conversationId" to CONV)),
                repo,
                FakeConnectionStateSource(),
                ComposerDraftStore(),
            )
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        repo.readings.tryEmit(initial)
        runCurrent()
        return vm
    }

    private fun reading(
        effort: String,
        applied: EffectiveEffort,
        sessionId: String = SESSION,
    ) = SessionSettings(
        sessionId = sessionId,
        model = "opus",
        effort = effort,
        effectiveEffort = applied,
        permissionMode = "",
        yolo = false,
        usedTokens = 0,
        windowTokens = 0,
    )

    private val opusRow =
        ModelMenuRow(
            resolvedModel = "",
            value = "opus",
            displayName = "Opus",
            effortLevels = listOf("low", "high", "max"),
            supportsAutoMode = false,
            truncatedFields = null,
        )

    /** Readings arrive when the test emits them; a refresh is answered by [onRefresh]. */
    private class ScriptedRepo(
        val backing: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by backing {
        val readings = MutableSharedFlow<SessionSettings?>(replay = 1, extraBufferCapacity = 64)
        val liveSession = MutableStateFlow(SESSION)
        val calls = mutableListOf<SetSessionSettingsPayloadDto>()
        var refreshes = 0
        var sentMessages = 0
        var onRefresh: (Int) -> SessionSettings? = { null }
        var ackGate: CompletableDeferred<Unit>? = null
        var failWith: Throwable? = null

        override fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> = readings

        override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
            combine(backing.observeConversations(filter), liveSession) { list, session ->
                list.map { if (it.id == CONV) it.copy(currentSessionId = session) else it }
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
        const val SESSION = "sess-a"
    }
}
