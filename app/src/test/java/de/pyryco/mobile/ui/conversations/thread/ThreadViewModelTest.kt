package de.pyryco.mobile.ui.conversations.thread

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.preferences.Effort
import de.pyryco.mobile.data.preferences.Model
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun TestScope.newDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { tmp.newFile("prefs_${UUID.randomUUID()}.preferences_pb") },
        )

    @Test
    fun state_initialValue_isConversationIdPlaceholderBeforeSubscription() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository())
            // No collect{} — the stateIn(WhileSubscribed) initial value is the conversationId fallback.
            assertEquals(
                ThreadUiState(conversationId = "seed-channel-personal", displayName = "seed-channel-personal"),
                vm.state.value,
            )
        }

    @Test
    fun connectionState_initialValue_isConnected() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository())
            // No collect{} — the stateIn(WhileSubscribed) initialValue matches the fake's seeded value.
            assertEquals(ConnectionState.Connected, vm.connectionState.value)
        }

    @Test
    fun connectionState_reemitsOnSourceChange() =
        runTest {
            val source = FakeConnectionStateSource()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository(), source)
            val collector = launch { vm.connectionState.collect {} }
            advanceUntilIdle()
            source.emit(ConnectionState.Offline)
            advanceUntilIdle()
            assertEquals(ConnectionState.Offline, vm.connectionState.value)
            collector.cancel()
        }

    @Test
    fun retry_invokesSourceRetry() =
        runTest {
            val source = RecordingConnectionStateSource()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository(), source)
            vm.retry()
            advanceUntilIdle()
            assertEquals(1, source.retryCallCount)
        }

    // ---- #406: isThinking reduction over live turn-state events ---------------------------------

    @Test
    fun isThinking_initialValue_isFalseWithNoLiveSource() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            // 4-arg makeVm (no live source) still compiles; the flag defaults inert (AC #5).
            val vm = makeVm(handle, FakeConversationRepository())
            assertFalse(vm.isThinking.value)
        }

    @Test
    fun isThinking_turnStateThinking_becomesTrue() =
        runTest {
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = vmWithLiveEvents(events)
            val collector = launch { vm.isThinking.collect {} }
            advanceUntilIdle()

            events.emit(turnState(ACTIVE_CONV, LiveSessionEvent.TurnState.Phase.Thinking))
            advanceUntilIdle()

            assertTrue(vm.isThinking.value)
            collector.cancel()
        }

    @Test
    fun isThinking_turnStateRespondingAndIdle_areFalse() =
        runTest {
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = vmWithLiveEvents(events)
            val collector = launch { vm.isThinking.collect {} }
            advanceUntilIdle()

            events.emit(turnState(ACTIVE_CONV, LiveSessionEvent.TurnState.Phase.Responding))
            advanceUntilIdle()
            assertFalse(vm.isThinking.value)

            events.emit(turnState(ACTIVE_CONV, LiveSessionEvent.TurnState.Phase.Thinking))
            advanceUntilIdle()
            assertTrue(vm.isThinking.value)

            events.emit(turnState(ACTIVE_CONV, LiveSessionEvent.TurnState.Phase.Idle))
            advanceUntilIdle()
            assertFalse(vm.isThinking.value)
            collector.cancel()
        }

    @Test
    fun isThinking_turnEnd_resetsToFalse() =
        runTest {
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = vmWithLiveEvents(events)
            val collector = launch { vm.isThinking.collect {} }
            advanceUntilIdle()

            events.emit(turnState(ACTIVE_CONV, LiveSessionEvent.TurnState.Phase.Thinking))
            advanceUntilIdle()
            assertTrue(vm.isThinking.value)

            // A turn that ends straight out of thinking (no responding/idle between) still resets.
            events.emit(LiveSessionEvent.TurnEnd(ACTIVE_CONV, turnId = "t1", stopReason = "end_turn"))
            advanceUntilIdle()
            assertFalse(vm.isThinking.value)
            collector.cancel()
        }

    @Test
    fun isThinking_latestPhaseWins() =
        runTest {
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = vmWithLiveEvents(events)
            val collector = launch { vm.isThinking.collect {} }
            advanceUntilIdle()

            events.emit(turnState(ACTIVE_CONV, LiveSessionEvent.TurnState.Phase.Thinking))
            events.emit(turnState(ACTIVE_CONV, LiveSessionEvent.TurnState.Phase.Responding))
            advanceUntilIdle()

            assertFalse(vm.isThinking.value)
            collector.cancel()
        }

    @Test
    fun isThinking_otherConversation_doesNotAffectFlag() =
        runTest {
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = vmWithLiveEvents(events)
            val collector = launch { vm.isThinking.collect {} }
            advanceUntilIdle()

            // The active conversation enters thinking.
            events.emit(turnState(ACTIVE_CONV, LiveSessionEvent.TurnState.Phase.Thinking))
            advanceUntilIdle()
            assertTrue(vm.isThinking.value)

            // Another conversation going idle must not flip the active flag (AC #3).
            events.emit(turnState("other-conversation", LiveSessionEvent.TurnState.Phase.Idle))
            advanceUntilIdle()
            assertTrue(vm.isThinking.value)
            collector.cancel()
        }

    @Test
    fun isThinking_nonPhaseEvents_leaveFlagUnchanged() =
        runTest {
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = vmWithLiveEvents(events)
            val collector = launch { vm.isThinking.collect {} }
            advanceUntilIdle()

            events.emit(turnState(ACTIVE_CONV, LiveSessionEvent.TurnState.Phase.Thinking))
            advanceUntilIdle()
            assertTrue(vm.isThinking.value)

            // assistant_delta / tool_use / tool_result are not phase transitions — flag holds.
            events.emit(LiveSessionEvent.AssistantDelta(ACTIVE_CONV, turnId = "t1", seq = 0, text = "hi"))
            events.emit(LiveSessionEvent.ToolUse(ACTIVE_CONV, turnId = "t1", toolUseId = "u1", name = "read", inputSummary = "f"))
            events.emit(LiveSessionEvent.ToolResult(ACTIVE_CONV, turnId = "t1", toolUseId = "u1", isError = false, resultSummary = "ok"))
            advanceUntilIdle()
            assertTrue(vm.isThinking.value)
            collector.cancel()
        }

    @Test
    fun state_resolvedTitle_isChannelNameForSeededChannel() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository())
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals("Personal", vm.state.value.displayName)
            assertEquals("seed-channel-personal", vm.state.value.conversationId)
            collector.cancel()
        }

    @Test
    fun state_resolvedTitle_isUntitledDiscussionForUnnamedDiscussion() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-discussion-a"))
            val vm = makeVm(handle, FakeConversationRepository())
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals("Untitled discussion", vm.state.value.displayName)
            collector.cancel()
        }

    @Test
    fun state_resolvedTitle_isUntitledChannelForUnnamedChannel() =
        runTest {
            val unnamedChannel =
                Conversation(
                    id = "x",
                    name = null,
                    cwd = "~/x",
                    currentSessionId = "x-s1",
                    sessionHistory = listOf("x-s1"),
                    isPromoted = true,
                    lastUsedAt = Instant.parse("2026-05-12T00:00:00Z"),
                )
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "x"))
            val vm = makeVm(handle, fixedRepo(listOf(unnamedChannel)))
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals("Untitled channel", vm.state.value.displayName)
            collector.cancel()
        }

    @Test
    fun state_resolvedTitle_fallsBackToConversationIdWhenConversationMissing() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "ghost-id"))
            val vm = makeVm(handle, fixedRepo(emptyList()))
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals("ghost-id", vm.state.value.displayName)
            collector.cancel()
        }

    @Test
    fun state_displayName_reemitsOnRename() =
        runTest {
            val repository = FakeConversationRepository()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repository)
            val emissions = mutableListOf<ThreadUiState>()
            val collector = launch { vm.state.collect { emissions += it } }
            advanceUntilIdle()
            assertEquals("Personal", vm.state.value.displayName)
            repository.rename("seed-channel-personal", "Personal — renamed")
            advanceUntilIdle()
            assertEquals("Personal — renamed", vm.state.value.displayName)
            collector.cancel()
        }

    @Test
    fun state_collapsesAbsentConversationIdToEmptyString() =
        runTest {
            val handle = SavedStateHandle(initialState = emptyMap())
            val vm = makeVm(handle, FakeConversationRepository())
            assertEquals("", vm.state.value.conversationId)
        }

    @Test
    fun state_initialValue_includesDefaultModelEffortAndTokenPercentDefaults() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository())
            // No collect{} — the stateIn(WhileSubscribed) initial value is the data-class defaults.
            assertEquals(Model.OPUS_4_7, vm.state.value.selectedModel)
            assertEquals(Effort.HIGH, vm.state.value.selectedEffort)
            assertFalse(vm.state.value.yoloEnabled)
            assertEquals(0, vm.state.value.tokenPercent)
            assertEquals(0, vm.state.value.tokensUsed)
            assertEquals(0, vm.state.value.tokensTotal)
        }

    @Test
    fun state_postSubscription_emitsDefaultModelEffortAndTokenPercent() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository())
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals(Model.OPUS_4_7, vm.state.value.selectedModel)
            assertEquals(Effort.HIGH, vm.state.value.selectedEffort)
            assertFalse(vm.state.value.yoloEnabled)
            assertEquals(73, vm.state.value.tokenPercent)
            assertEquals(146_000, vm.state.value.tokensUsed)
            assertEquals(200_000, vm.state.value.tokensTotal)
            collector.cancel()
        }

    @Test
    fun selectedModel_followsAppPreferencesDefault() =
        runTest {
            val prefs = AppPreferences(newDataStore())
            prefs.setDefaultModel(Model.SONNET_4_6)
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository(), prefs = prefs)
            val seen =
                withTimeout(2.seconds) {
                    vm.state.first { it.selectedModel == Model.SONNET_4_6 }
                }
            assertEquals(Model.SONNET_4_6, seen.selectedModel)
        }

    @Test
    fun selectedModel_reemitsWhenAppPreferencesDefaultChanges() =
        runTest {
            val prefs = AppPreferences(newDataStore())
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository(), prefs = prefs)
            val initial =
                withTimeout(2.seconds) {
                    vm.state.first { it.selectedModel == Model.OPUS_4_7 }
                }
            assertEquals(Model.OPUS_4_7, initial.selectedModel)
            prefs.setDefaultModel(Model.HAIKU_4_5)
            val updated =
                withTimeout(2.seconds) {
                    vm.state.first { it.selectedModel == Model.HAIKU_4_5 }
                }
            assertEquals(Model.HAIKU_4_5, updated.selectedModel)
        }

    @Test
    fun onModelSelected_overridesPerConversationWithoutMutatingPreferences() =
        runTest {
            val prefs = AppPreferences(newDataStore())
            // Sanity: the default starts at OPUS_4_7 (unparseable/missing → OPUS_4_7).
            assertEquals(Model.OPUS_4_7, prefs.defaultModel.first())
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository(), prefs = prefs)
            withTimeout(2.seconds) {
                vm.state.first { it.selectedModel == Model.OPUS_4_7 }
            }
            vm.onModelSelected(Model.HAIKU_4_5)
            val seen =
                withTimeout(2.seconds) {
                    vm.state.first { it.selectedModel == Model.HAIKU_4_5 }
                }
            assertEquals(Model.HAIKU_4_5, seen.selectedModel)
            // The AC's verification line: Settings default is unchanged.
            assertEquals(Model.OPUS_4_7, prefs.defaultModel.first())
        }

    @Test
    fun onModelSelected_overrideWinsOverSubsequentDefaultChange() =
        runTest {
            val prefs = AppPreferences(newDataStore())
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository(), prefs = prefs)
            withTimeout(2.seconds) {
                vm.state.first { it.selectedModel == Model.OPUS_4_7 }
            }
            vm.onModelSelected(Model.HAIKU_4_5)
            withTimeout(2.seconds) {
                vm.state.first { it.selectedModel == Model.HAIKU_4_5 }
            }
            prefs.setDefaultModel(Model.SONNET_4_6)
            advanceUntilIdle()
            // The override sticks; the Settings default change does not override it.
            assertEquals(Model.HAIKU_4_5, vm.state.value.selectedModel)
            assertEquals(Model.SONNET_4_6, prefs.defaultModel.first())
        }

    @Test
    fun selectedEffort_followsAppPreferencesDefault() =
        runTest {
            val prefs = AppPreferences(newDataStore())
            prefs.setDefaultEffort(Effort.LOW)
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository(), prefs = prefs)
            val seen =
                withTimeout(2.seconds) {
                    vm.state.first { it.selectedEffort == Effort.LOW }
                }
            assertEquals(Effort.LOW, seen.selectedEffort)
        }

    @Test
    fun selectedEffort_reemitsWhenAppPreferencesDefaultChanges() =
        runTest {
            val prefs = AppPreferences(newDataStore())
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository(), prefs = prefs)
            val initial =
                withTimeout(2.seconds) {
                    vm.state.first { it.selectedEffort == Effort.HIGH }
                }
            assertEquals(Effort.HIGH, initial.selectedEffort)
            prefs.setDefaultEffort(Effort.MAX)
            val updated =
                withTimeout(2.seconds) {
                    vm.state.first { it.selectedEffort == Effort.MAX }
                }
            assertEquals(Effort.MAX, updated.selectedEffort)
        }

    @Test
    fun onEffortSelected_overridesPerConversationWithoutMutatingPreferences() =
        runTest {
            val prefs = AppPreferences(newDataStore())
            assertEquals(Effort.HIGH, prefs.defaultEffort.first())
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository(), prefs = prefs)
            withTimeout(2.seconds) {
                vm.state.first { it.selectedEffort == Effort.HIGH }
            }
            vm.onEffortSelected(Effort.MAX)
            val seen =
                withTimeout(2.seconds) {
                    vm.state.first { it.selectedEffort == Effort.MAX }
                }
            assertEquals(Effort.MAX, seen.selectedEffort)
            // The AC's verification line: Settings default is unchanged.
            assertEquals(Effort.HIGH, prefs.defaultEffort.first())
        }

    @Test
    fun onEffortSelected_overrideWinsOverSubsequentDefaultChange() =
        runTest {
            val prefs = AppPreferences(newDataStore())
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository(), prefs = prefs)
            withTimeout(2.seconds) {
                vm.state.first { it.selectedEffort == Effort.HIGH }
            }
            vm.onEffortSelected(Effort.MAX)
            withTimeout(2.seconds) {
                vm.state.first { it.selectedEffort == Effort.MAX }
            }
            prefs.setDefaultEffort(Effort.LOW)
            advanceUntilIdle()
            assertEquals(Effort.MAX, vm.state.value.selectedEffort)
            assertEquals(Effort.LOW, prefs.defaultEffort.first())
        }

    @Test
    fun yoloEnabled_initialValueIsFalseRegardlessOfAppPreferencesDefault() =
        runTest {
            val prefs = AppPreferences(newDataStore())
            // The dormant defaultYolo preference is set to true; the VM must ignore it.
            prefs.setDefaultYolo(true)
            assertTrue(prefs.defaultYolo.first())
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository(), prefs = prefs)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertFalse(vm.state.value.yoloEnabled)
            collector.cancel()
        }

    @Test
    fun onYoloToggled_flipsStateAndDoesNotMutatePreferences() =
        runTest {
            val prefs = AppPreferences(newDataStore())
            assertFalse(prefs.defaultYolo.first())
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository(), prefs = prefs)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertFalse(vm.state.value.yoloEnabled)
            vm.onYoloToggled(true)
            advanceUntilIdle()
            assertTrue(vm.state.value.yoloEnabled)
            // AC verification: Settings default is unchanged by the per-conversation toggle.
            assertFalse(prefs.defaultYolo.first())
            vm.onYoloToggled(false)
            advanceUntilIdle()
            assertFalse(vm.state.value.yoloEnabled)
            collector.cancel()
        }

    @Test
    fun yoloEnabled_remainsFalseWhenAppPreferencesDefaultYoloChanges() =
        runTest {
            val prefs = AppPreferences(newDataStore())
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository(), prefs = prefs)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertFalse(vm.state.value.yoloEnabled)
            prefs.setDefaultYolo(true)
            advanceUntilIdle()
            assertFalse(vm.state.value.yoloEnabled)
            collector.cancel()
        }

    @Test
    fun sendMessage_blankText_isNoOp() =
        runTest {
            val repository = FakeConversationRepository()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repository)
            val observed = mutableListOf<List<ThreadItem>>()
            val collector =
                launch {
                    repository.observeMessages("seed-channel-personal").collect { observed += it }
                }
            advanceUntilIdle()
            val initialCount = observed.last().size
            vm.sendMessage("")
            vm.sendMessage("   \n\t ")
            advanceUntilIdle()
            assertEquals(initialCount, observed.last().size)
            collector.cancel()
        }

    @Test
    fun sendMessage_nonBlankText_appendsToConversation() =
        runTest {
            val repository = FakeConversationRepository()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repository)
            val observed = mutableListOf<List<ThreadItem>>()
            val collector =
                launch {
                    repository.observeMessages("seed-channel-personal").collect { observed += it }
                }
            advanceUntilIdle()
            val initialCount = observed.last().size
            vm.sendMessage("Hello world")
            advanceUntilIdle()
            val latest = observed.last()
            assertEquals(initialCount + 1, latest.size)
            val appended = (latest.last() as ThreadItem.MessageItem).message
            assertEquals("Hello world", appended.content)
            assertEquals(Role.User, appended.role)
            assertEquals("seed-session-personal", appended.sessionId)
            collector.cancel()
        }

    @Test
    fun state_workspaceLabel_isScratch_whenCwdIsEmptyString() =
        runTest {
            val repository = FakeConversationRepository()
            val freshDiscussion = repository.createDiscussion(workspace = null)
            // Pre-condition: createDiscussion(null) yields cwd = "" (see FakeConversationRepository.createDiscussion).
            assertEquals("", freshDiscussion.cwd)
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to freshDiscussion.id))
            val vm = makeVm(handle, repository)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals("scratch", vm.state.value.workspaceLabel)
            collector.cancel()
        }

    @Test
    fun state_workspaceLabel_isScratch_whenCwdIsDefaultScratchSentinel() =
        runTest {
            val scratchDiscussion =
                Conversation(
                    id = "d-scratch",
                    name = null,
                    cwd = DEFAULT_SCRATCH_CWD,
                    currentSessionId = "d-scratch-s1",
                    sessionHistory = listOf("d-scratch-s1"),
                    isPromoted = false,
                    lastUsedAt = Instant.parse("2026-05-12T00:00:00Z"),
                )
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "d-scratch"))
            val vm = makeVm(handle, fixedRepo(listOf(scratchDiscussion)))
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals("scratch", vm.state.value.workspaceLabel)
            collector.cancel()
        }

    @Test
    fun state_workspaceLabel_isBasename_forArbitraryCwd() =
        runTest {
            val boundDiscussion =
                Conversation(
                    id = "d-my-app",
                    name = null,
                    cwd = "pyry-workspace/my-app",
                    currentSessionId = "d-my-app-s1",
                    sessionHistory = listOf("d-my-app-s1"),
                    isPromoted = false,
                    lastUsedAt = Instant.parse("2026-05-12T00:00:00Z"),
                )
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "d-my-app"))
            val vm = makeVm(handle, fixedRepo(listOf(boundDiscussion)))
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals("my-app", vm.state.value.workspaceLabel)
            collector.cancel()
        }

    @Test
    fun state_items_reflectsObserveMessagesStream() =
        runTest {
            val repository = FakeConversationRepository()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repository)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertTrue(
                vm.state.value.items
                    .isNotEmpty(),
            )
            assertTrue(
                vm.state.value.items
                    .first() is ThreadItem.MessageItem,
            )
            collector.cancel()
        }

    @Test
    fun state_chipFields_reflectChannelAndMessagePresence() =
        runTest {
            // Seeded channel: isPromoted = true; hasMessages = true (currentMessages are seeded).
            val channelHandle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val channelRepo = FakeConversationRepository()
            val channelVm = makeVm(channelHandle, channelRepo)
            val channelCollector = launch { channelVm.state.collect {} }
            advanceUntilIdle()
            assertTrue(channelVm.state.value.isPromoted)
            assertTrue(channelVm.state.value.hasMessages)
            channelCollector.cancel()

            // Fresh discussion: isPromoted = false; hasMessages flips from false → true on sendMessage.
            val discussionRepo = FakeConversationRepository()
            val freshDiscussion = discussionRepo.createDiscussion(workspace = null)
            val discussionHandle =
                SavedStateHandle(initialState = mapOf("conversationId" to freshDiscussion.id))
            val discussionVm = makeVm(discussionHandle, discussionRepo)
            val discussionCollector = launch { discussionVm.state.collect {} }
            advanceUntilIdle()
            assertFalse(discussionVm.state.value.isPromoted)
            assertFalse(discussionVm.state.value.hasMessages)

            discussionVm.sendMessage("hi")
            advanceUntilIdle()
            assertFalse(discussionVm.state.value.isPromoted)
            assertTrue(discussionVm.state.value.hasMessages)
            discussionCollector.cancel()
        }

    @Test
    fun onWorkspacePicked_callsChangeWorkspaceOnceAndClearsPickerFlag() =
        runTest {
            val repository = FakeConversationRepository()
            val freshDiscussion = repository.createDiscussion(workspace = null)
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to freshDiscussion.id))
            val vm = makeVm(handle, repository)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onWorkspaceChipTapped()
            advanceUntilIdle()
            assertTrue(vm.state.value.workspacePickerVisible)

            vm.onWorkspacePicked("pyry-workspace/my-app")
            advanceUntilIdle()

            assertFalse(vm.state.value.workspacePickerVisible)
            val persisted =
                repository
                    .observeConversations(ConversationFilter.All)
                    .first()
                    .first { it.id == freshDiscussion.id }
            assertEquals("pyry-workspace/my-app", persisted.cwd)
            collector.cancel()
        }

    @Test
    fun onWorkspacePickerDismissed_clearsFlagWithoutCallingChangeWorkspace() =
        runTest {
            val repository = FakeConversationRepository()
            val freshDiscussion = repository.createDiscussion(workspace = null)
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to freshDiscussion.id))
            val vm = makeVm(handle, repository)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onWorkspaceChipTapped()
            advanceUntilIdle()
            assertTrue(vm.state.value.workspacePickerVisible)

            vm.onWorkspacePickerDismissed()
            advanceUntilIdle()

            assertFalse(vm.state.value.workspacePickerVisible)
            val persisted =
                repository
                    .observeConversations(ConversationFilter.All)
                    .first()
                    .first { it.id == freshDiscussion.id }
            assertEquals(freshDiscussion.cwd, persisted.cwd)
            collector.cancel()
        }

    @Test
    fun onOverflowEvent_archive_archivesClosesSheetAndPopsBack() =
        runTest {
            val repo = RecordingRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.state.collect {} }
            val navEvents = mutableListOf<ThreadNavigation>()
            val navCollector = launch { vm.navigationEvents.collect { navEvents += it } }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.ChannelInfo)
            advanceUntilIdle()
            assertTrue(vm.state.value.channelInfoOpen)

            vm.onOverflowEvent(ThreadEvent.Archive)
            advanceUntilIdle()

            assertEquals(listOf("seed-channel-personal"), repo.archiveCalls)
            assertFalse(vm.state.value.channelInfoOpen)
            assertEquals(listOf(ThreadNavigation.PopBack), navEvents)
            collector.cancel()
            navCollector.cancel()
        }

    @Test
    fun onOverflowEvent_delete_opensConfirmDialogWithoutDeletingOrNavigating() =
        runTest {
            val repo = RecordingRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.state.collect {} }
            val navEvents = mutableListOf<ThreadNavigation>()
            val navCollector = launch { vm.navigationEvents.collect { navEvents += it } }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.ChannelInfo)
            vm.onOverflowEvent(ThreadEvent.Delete)
            advanceUntilIdle()

            assertTrue(vm.state.value.deleteConfirmVisible)
            assertTrue(vm.state.value.channelInfoOpen)
            assertTrue(repo.deleteCalls.isEmpty())
            assertTrue(navEvents.isEmpty())
            collector.cancel()
            navCollector.cancel()
        }

    @Test
    fun onOverflowEvent_deleteDismiss_closesConfirmKeepsSheetWithoutDeleting() =
        runTest {
            val repo = RecordingRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.ChannelInfo)
            vm.onOverflowEvent(ThreadEvent.Delete)
            advanceUntilIdle()
            assertTrue(vm.state.value.deleteConfirmVisible)

            vm.onOverflowEvent(ThreadEvent.DeleteDismiss)
            advanceUntilIdle()

            assertFalse(vm.state.value.deleteConfirmVisible)
            assertTrue(vm.state.value.channelInfoOpen)
            assertTrue(repo.deleteCalls.isEmpty())
            collector.cancel()
        }

    @Test
    fun onOverflowEvent_deleteConfirm_deletesClosesAllAndPopsBack() =
        runTest {
            val repo = RecordingRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.state.collect {} }
            val navEvents = mutableListOf<ThreadNavigation>()
            val navCollector = launch { vm.navigationEvents.collect { navEvents += it } }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.ChannelInfo)
            vm.onOverflowEvent(ThreadEvent.Delete)
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.DeleteConfirm)
            advanceUntilIdle()

            assertEquals(listOf("seed-channel-personal"), repo.deleteCalls)
            assertFalse(vm.state.value.deleteConfirmVisible)
            assertFalse(vm.state.value.channelInfoOpen)
            assertEquals(listOf(ThreadNavigation.PopBack), navEvents)
            collector.cancel()
            navCollector.cancel()
        }

    @Test
    fun navigationEvents_eachPopBackDeliveredExactlyOnce_notReplayed() =
        runTest {
            val repo = RecordingRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.state.collect {} }
            val navEvents = mutableListOf<ThreadNavigation>()
            val navCollector = launch { vm.navigationEvents.collect { navEvents += it } }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.Archive)
            advanceUntilIdle()
            assertEquals(1, navEvents.size)

            // Continued collection does not replay the consumed PopBack (one-shot).
            advanceUntilIdle()
            assertEquals(1, navEvents.size)

            // A second trigger produces its own single event.
            vm.onOverflowEvent(ThreadEvent.Archive)
            advanceUntilIdle()
            assertEquals(2, navEvents.size)
            collector.cancel()
            navCollector.cancel()
        }

    @Test
    fun onOverflowEvent_otherCases_doNotCallArchive() =
        runTest {
            val repo = RecordingRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.NewSession)
            advanceUntilIdle()

            assertTrue(repo.archiveCalls.isEmpty())
            collector.cancel()
        }

    @Test
    fun onOverflowEvent_changeWorkspace_opensWorkspacePicker() =
        runTest {
            val repo = RecordingRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.ChangeWorkspace)
            advanceUntilIdle()

            assertTrue(vm.state.value.workspacePickerVisible)
            assertTrue(repo.archiveCalls.isEmpty())
            collector.cancel()
        }

    @Test
    fun onOverflowEvent_channelInfo_opensSheetWithoutArchiving() =
        runTest {
            val repo = RecordingRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.ChannelInfo)
            advanceUntilIdle()

            assertTrue(vm.state.value.channelInfoOpen)
            assertTrue(repo.archiveCalls.isEmpty())
            collector.cancel()
        }

    @Test
    fun onOverflowEvent_channelInfoDismiss_closesSheet() =
        runTest {
            val repo = RecordingRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.ChannelInfo)
            advanceUntilIdle()
            assertTrue(vm.state.value.channelInfoOpen)

            vm.onOverflowEvent(ThreadEvent.ChannelInfoDismiss)
            advanceUntilIdle()

            assertFalse(vm.state.value.channelInfoOpen)
            collector.cancel()
        }

    @Test
    fun state_populatesChannelInfoIngredients_fromSeededConversation() =
        runTest {
            val channel =
                Conversation(
                    id = "c-info",
                    name = "Infra",
                    cwd = "pyry-workspace/channels/infra",
                    currentSessionId = "c-info-s3",
                    sessionHistory = listOf("c-info-s1", "c-info-s2", "c-info-s3"),
                    isPromoted = true,
                    lastUsedAt = Instant.parse("2026-05-20T08:00:00Z"),
                )
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "c-info"))
            val vm = makeVm(handle, fixedRepo(listOf(channel)))
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals("pyry-workspace/channels/infra", vm.state.value.workspacePath)
            assertEquals(Instant.parse("2026-05-20T08:00:00Z"), vm.state.value.lastUsedAt)
            assertEquals(3, vm.state.value.sessionCount)
            collector.cancel()
        }

    @Test
    fun onOverflowEvent_rename_setsShowRenameDialogFlag() =
        runTest {
            val repo = RecordingRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.Rename)
            advanceUntilIdle()

            assertTrue(vm.state.value.showRenameDialog)
            assertTrue(repo.renameCalls.isEmpty())
            collector.cancel()
        }

    @Test
    fun onOverflowEvent_renameSubmit_callsRepositoryAndClearsFlag() =
        runTest {
            val repo = RecordingRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.Rename)
            advanceUntilIdle()
            assertTrue(vm.state.value.showRenameDialog)

            vm.onOverflowEvent(ThreadEvent.RenameSubmit("new name"))
            advanceUntilIdle()

            assertFalse(vm.state.value.showRenameDialog)
            assertEquals(listOf("seed-channel-personal" to "new name"), repo.renameCalls)
            collector.cancel()
        }

    @Test
    fun onOverflowEvent_renameDismiss_clearsFlagWithoutRepositoryCall() =
        runTest {
            val repo = RecordingRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.Rename)
            advanceUntilIdle()
            assertTrue(vm.state.value.showRenameDialog)

            vm.onOverflowEvent(ThreadEvent.RenameDismiss)
            advanceUntilIdle()

            assertFalse(vm.state.value.showRenameDialog)
            assertTrue(repo.renameCalls.isEmpty())
            collector.cancel()
        }

    @Test
    fun onOverflowEvent_saveAsChannel_setsDialogStateWithSeededName() =
        runTest {
            val repo = RecordingRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.SaveAsChannel)
            advanceUntilIdle()

            assertEquals(
                SaveAsChannelDialogState(initialName = "New channel"),
                vm.state.value.saveAsChannelDialog,
            )
            assertTrue(repo.promoteCalls.isEmpty())
            collector.cancel()
        }

    @Test
    fun onOverflowEvent_saveAsChannelSubmit_dedicated_callsPromoteWithSlugPath() =
        runTest {
            val repo = RecordingRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.SaveAsChannel)
            advanceUntilIdle()

            vm.onOverflowEvent(
                ThreadEvent.SaveAsChannelSubmit(
                    name = "Investment Strategy Review",
                    workspace = WorkspaceChoice.DEDICATED,
                ),
            )
            advanceUntilIdle()

            assertEquals(null, vm.state.value.saveAsChannelDialog)
            assertEquals(
                listOf(
                    Triple(
                        "seed-channel-personal",
                        "Investment Strategy Review",
                        "pyry-workspace/channels/investment-strategy-review",
                    ),
                ),
                repo.promoteCalls,
            )
            collector.cancel()
        }

    @Test
    fun onOverflowEvent_saveAsChannelSubmit_scratch_callsPromoteWithNullWorkspace() =
        runTest {
            val repo = RecordingRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.SaveAsChannel)
            advanceUntilIdle()

            vm.onOverflowEvent(
                ThreadEvent.SaveAsChannelSubmit(
                    name = "kitchenclaw refactor",
                    workspace = WorkspaceChoice.SCRATCH,
                ),
            )
            advanceUntilIdle()

            assertEquals(null, vm.state.value.saveAsChannelDialog)
            assertEquals(
                listOf(
                    Triple("seed-channel-personal", "kitchenclaw refactor", null),
                ),
                repo.promoteCalls,
            )
            collector.cancel()
        }

    @Test
    fun onOverflowEvent_saveAsChannelDismiss_clearsDialogWithoutPromote() =
        runTest {
            val repo = RecordingRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.SaveAsChannel)
            advanceUntilIdle()
            assertEquals(
                SaveAsChannelDialogState(initialName = "New channel"),
                vm.state.value.saveAsChannelDialog,
            )

            vm.onOverflowEvent(ThreadEvent.SaveAsChannelDismiss)
            advanceUntilIdle()

            assertEquals(null, vm.state.value.saveAsChannelDialog)
            assertTrue(repo.promoteCalls.isEmpty())
            collector.cancel()
        }

    // --- helpers ---

    private fun TestScope.makeVm(
        handle: SavedStateHandle,
        repository: ConversationRepository,
        source: ConnectionStateSource = FakeConnectionStateSource(),
        prefs: AppPreferences = AppPreferences(newDataStore()),
        liveSessionEvents: Flow<LiveSessionEvent> = emptyFlow(),
    ): ThreadViewModel = ThreadViewModel(handle, repository, source, prefs, liveSessionEvents)

    /** A VM whose active conversation is [ACTIVE_CONV], wired to a controllable live-event source. */
    private fun TestScope.vmWithLiveEvents(events: Flow<LiveSessionEvent>): ThreadViewModel =
        makeVm(
            SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV)),
            FakeConversationRepository(),
            liveSessionEvents = events,
        )

    private fun turnState(
        conversationId: String,
        phase: LiveSessionEvent.TurnState.Phase,
    ): LiveSessionEvent = LiveSessionEvent.TurnState(conversationId, phase)

    private class RecordingRepo : ConversationRepository {
        val archiveCalls = mutableListOf<String>()
        val deleteCalls = mutableListOf<String>()
        val renameCalls = mutableListOf<Pair<String, String>>()
        val promoteCalls = mutableListOf<Triple<String, String, String?>>()

        override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> = flowOf(emptyList())

        override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = flowOf(emptyList())

        override fun observeLastMessage(conversationId: String): Flow<Message?> = flowOf(null)

        override suspend fun createDiscussion(workspace: String?): Conversation = TODO("not used")

        override suspend fun promote(
            conversationId: String,
            name: String,
            workspace: String?,
        ): Conversation {
            promoteCalls += Triple(conversationId, name, workspace)
            return Conversation(
                id = conversationId,
                name = name,
                cwd = workspace ?: "",
                currentSessionId = "$conversationId-s1",
                sessionHistory = listOf("$conversationId-s1"),
                isPromoted = true,
                lastUsedAt = Instant.parse("2026-05-17T00:00:00Z"),
            )
        }

        override suspend fun archive(conversationId: String) {
            archiveCalls += conversationId
        }

        override suspend fun unarchive(conversationId: String): Unit = TODO("not used")

        override suspend fun delete(conversationId: String) {
            deleteCalls += conversationId
        }

        override suspend fun rename(
            conversationId: String,
            name: String,
        ): Conversation {
            renameCalls += conversationId to name
            return Conversation(
                id = conversationId,
                name = name,
                cwd = "",
                currentSessionId = "$conversationId-s1",
                sessionHistory = listOf("$conversationId-s1"),
                isPromoted = false,
                lastUsedAt = Instant.parse("2026-05-17T00:00:00Z"),
            )
        }

        override suspend fun startNewSession(
            conversationId: String,
            workspace: String?,
        ): Session = TODO("not used")

        override suspend fun changeWorkspace(
            conversationId: String,
            workspace: String,
        ): Session = TODO("not used")

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
        ): Message = TODO("not used")
    }

    private class RecordingConnectionStateSource : ConnectionStateSource {
        private val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
        var retryCallCount: Int = 0
            private set

        override fun observe(): Flow<ConnectionState> = state.asStateFlow()

        override suspend fun retry() {
            retryCallCount++
        }
    }

    private fun fixedRepo(conversations: List<Conversation>): ConversationRepository =
        object : ConversationRepository {
            override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> = flowOf(conversations)

            override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = flowOf(emptyList())

            override fun observeLastMessage(conversationId: String): Flow<Message?> = flowOf(null)

            override suspend fun createDiscussion(workspace: String?): Conversation = TODO("not used")

            override suspend fun promote(
                conversationId: String,
                name: String,
                workspace: String?,
            ): Conversation = TODO("not used")

            override suspend fun archive(conversationId: String): Unit = TODO("not used")

            override suspend fun unarchive(conversationId: String): Unit = TODO("not used")

            override suspend fun rename(
                conversationId: String,
                name: String,
            ): Conversation = TODO("not used")

            override suspend fun startNewSession(
                conversationId: String,
                workspace: String?,
            ): Session = TODO("not used")

            override suspend fun changeWorkspace(
                conversationId: String,
                workspace: String,
            ): Session = TODO("not used")

            override suspend fun sendMessage(
                conversationId: String,
                text: String,
            ): Message = TODO("not used")
        }

    private companion object {
        const val ACTIVE_CONV = "thread-406-active"
    }
}
