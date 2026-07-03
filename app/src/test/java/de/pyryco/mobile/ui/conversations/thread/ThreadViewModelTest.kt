package de.pyryco.mobile.ui.conversations.thread

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.ModalOption
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.preferences.Effort
import de.pyryco.mobile.data.preferences.Model
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.ThrowingConversationRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import org.junit.Assert.assertNull
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

    // ---- #507: mutationsSupported capability snapshotted from the repository into ThreadUiState ----

    @Test
    fun mutationsSupported_fromFakeRepository_isTrueInInitialValueAndCombine() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository())
            // initialValue path (no collector) — the fake inherits the interface default true.
            assertTrue(vm.state.value.mutationsSupported)
            // combine path (after subscription) — the captured snapshot rides the recomputed state.
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertTrue(vm.state.value.mutationsSupported)
            collector.cancel()
        }

    @Test
    fun mutationsSupported_fromNonSupportingRepository_isFalse() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            // A relay-shaped repo: delegates every read to a seeded fake (so the state pipeline still
            // assembles) but reports mutationsSupported = false, as RemoteConversationRepository does.
            val repo =
                object : ConversationRepository by FakeConversationRepository() {
                    override val mutationsSupported: Boolean = false
                }
            val vm = makeVm(handle, repo)
            assertFalse(vm.state.value.mutationsSupported) // initialValue
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertFalse(vm.state.value.mutationsSupported) // combine
            collector.cancel()
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

            // assistant_delta / tool_use / tool_result are not phase transitions — flag holds. The
            // control-derived replay-gap (#417) is likewise not a thinking transition — flag holds.
            events.emit(LiveSessionEvent.AssistantDelta(ACTIVE_CONV, turnId = "t1", seq = 0, text = "hi"))
            events.emit(LiveSessionEvent.ToolUse(ACTIVE_CONV, turnId = "t1", toolUseId = "u1", name = "read", inputSummary = "f"))
            events.emit(LiveSessionEvent.ToolResult(ACTIVE_CONV, turnId = "t1", toolUseId = "u1", isError = false, resultSummary = "ok"))
            events.emit(LiveSessionEvent.ReplayGap(ACTIVE_CONV))
            advanceUntilIdle()
            assertTrue(vm.isThinking.value)
            collector.cancel()
        }

    // ---- #459: isBusy reduction over live turn-state events (thinking OR responding) -------------

    @Test
    fun isBusy_initialValue_isFalseWithNoLiveSource() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            // No live source: the flag defaults inert, like isThinking (AC #1 — hidden before any turn).
            val vm = makeVm(handle, FakeConversationRepository())
            assertFalse(vm.isBusy.value)
        }

    @Test
    fun isBusy_turnStateThinking_becomesTrue() =
        runTest {
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = vmWithLiveEvents(events)
            val collector = launch { vm.isBusy.collect {} }
            advanceUntilIdle()

            events.emit(turnState(ACTIVE_CONV, LiveSessionEvent.TurnState.Phase.Thinking))
            advanceUntilIdle()

            assertTrue(vm.isBusy.value)
            collector.cancel()
        }

    @Test
    fun isBusy_turnStateResponding_becomesTrue() =
        runTest {
            // The case that distinguishes isBusy from isThinking: `responding` is busy (true), but
            // isThinking treats it as false. The interrupt affordance must show across the whole turn.
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = vmWithLiveEvents(events)
            val collector = launch { vm.isBusy.collect {} }
            advanceUntilIdle()

            events.emit(turnState(ACTIVE_CONV, LiveSessionEvent.TurnState.Phase.Responding))
            advanceUntilIdle()

            assertTrue(vm.isBusy.value)
            assertFalse(vm.isThinking.value)
            collector.cancel()
        }

    @Test
    fun isBusy_turnStateIdle_isFalse() =
        runTest {
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = vmWithLiveEvents(events)
            val collector = launch { vm.isBusy.collect {} }
            advanceUntilIdle()

            events.emit(turnState(ACTIVE_CONV, LiveSessionEvent.TurnState.Phase.Responding))
            advanceUntilIdle()
            assertTrue(vm.isBusy.value)

            events.emit(turnState(ACTIVE_CONV, LiveSessionEvent.TurnState.Phase.Idle))
            advanceUntilIdle()
            assertFalse(vm.isBusy.value)
            collector.cancel()
        }

    @Test
    fun isBusy_turnEnd_resetsToFalse() =
        runTest {
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = vmWithLiveEvents(events)
            val collector = launch { vm.isBusy.collect {} }
            advanceUntilIdle()

            events.emit(turnState(ACTIVE_CONV, LiveSessionEvent.TurnState.Phase.Responding))
            advanceUntilIdle()
            assertTrue(vm.isBusy.value)

            // AC #3: the turn ending hides the affordance with no further input.
            events.emit(LiveSessionEvent.TurnEnd(ACTIVE_CONV, turnId = "t1", stopReason = "end_turn"))
            advanceUntilIdle()
            assertFalse(vm.isBusy.value)
            collector.cancel()
        }

    @Test
    fun isBusy_otherConversation_doesNotAffectFlag() =
        runTest {
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = vmWithLiveEvents(events)
            val collector = launch { vm.isBusy.collect {} }
            advanceUntilIdle()

            events.emit(turnState(ACTIVE_CONV, LiveSessionEvent.TurnState.Phase.Responding))
            advanceUntilIdle()
            assertTrue(vm.isBusy.value)

            // Another conversation going idle must not flip the active flag.
            events.emit(turnState("other-conversation", LiveSessionEvent.TurnState.Phase.Idle))
            advanceUntilIdle()
            assertTrue(vm.isBusy.value)
            collector.cancel()
        }

    @Test
    fun isBusy_nonPhaseEvents_leaveFlagUnchanged() =
        runTest {
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = vmWithLiveEvents(events)
            val collector = launch { vm.isBusy.collect {} }
            advanceUntilIdle()

            events.emit(turnState(ACTIVE_CONV, LiveSessionEvent.TurnState.Phase.Responding))
            advanceUntilIdle()
            assertTrue(vm.isBusy.value)

            // assistant_delta / tool_use / tool_result / replay-gap are not phase transitions — flag holds.
            events.emit(LiveSessionEvent.AssistantDelta(ACTIVE_CONV, turnId = "t1", seq = 0, text = "hi"))
            events.emit(LiveSessionEvent.ToolUse(ACTIVE_CONV, turnId = "t1", toolUseId = "u1", name = "read", inputSummary = "f"))
            events.emit(LiveSessionEvent.ToolResult(ACTIVE_CONV, turnId = "t1", toolUseId = "u1", isError = false, resultSummary = "ok"))
            events.emit(LiveSessionEvent.ReplayGap(ACTIVE_CONV))
            advanceUntilIdle()
            assertTrue(vm.isBusy.value)
            collector.cancel()
        }

    // ---- #492: currentModal is the hoisted projection injected from the coordinator -------------
    // The fold itself moved to the process-scoped coordinator: the pure-fold behaviours (Shown→Open,
    // matching/non-matching Dismissed, last-shown-wins) are covered by ModalUiStateTest, and the
    // pre-subscriber accumulation by RelayRepositoryCoordinatorTest. Here the VM only re-exposes it.

    @Test
    fun currentModal_initialValue_isHiddenWithNoModalSource() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            // 6-arg makeVm (no modal source) → the inert MutableStateFlow(Hidden) default (AC #3).
            val vm = makeVm(handle, FakeConversationRepository())
            assertEquals(ModalUiState.Hidden, vm.currentModal.value)
        }

    @Test
    fun currentModal_reExposesInjectedProjection() =
        runTest {
            // The VM consumes the coordinator's hoisted StateFlow rather than folding a raw event stream
            // (AC #3): whatever the injected projection holds surfaces verbatim on vm.currentModal.
            val modal = MutableStateFlow<ModalUiState>(ModalUiState.Hidden)
            val vm = vmWithModal(modal)
            assertEquals(ModalUiState.Hidden, vm.currentModal.value)

            val open = openModal(modalId = "m1")
            modal.value = open
            assertEquals(open, vm.currentModal.value)
        }

    // ---- #451: modal answer/cancel + arming/second-confirm + error → live send path -------------
    // The open modal is established by setting the injected StateFlow's `.value` directly (#492) — the VM
    // no longer folds a raw event stream, so there is no Shown to emit. arm/answer/cancel is unchanged.

    @Test
    fun onModalOption_default_sendsAnswerOnSingleTap() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(openModal(modalId = "m1")) // default = reject_once
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(modal, recorder)
            val errors = mutableListOf<Unit>()
            val errorCollector = launch { vm.modalSendErrors.collect { errors += it } }
            advanceUntilIdle()

            vm.onModalOption("reject_once")
            advanceUntilIdle()

            // The fail-safe-deny default answers on a single tap (AC #1) — no arm.
            assertEquals(listOf("m1" to "reject_once"), recorder.answers)
            assertTrue(recorder.cancels.isEmpty())
            assertNull(vm.armedOptionId.value)
            assertTrue(errors.isEmpty())
            errorCollector.cancel()
        }

    @Test
    fun onModalOption_nonDefault_armsWithoutSending() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(openModal(modalId = "m1"))
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(modal, recorder)
            advanceUntilIdle()

            vm.onModalOption("allow_once")
            advanceUntilIdle()

            // A single tap of a non-default option arms it; it does not send (AC #2).
            assertTrue("a single non-default tap must not send", recorder.answers.isEmpty())
            assertEquals("allow_once", vm.armedOptionId.value)
        }

    @Test
    fun onModalOption_secondTapOfArmedOption_sendsAndClears() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(openModal(modalId = "m1"))
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(modal, recorder)
            advanceUntilIdle()

            vm.onModalOption("allow_once") // arm
            advanceUntilIdle()
            vm.onModalOption("allow_once") // second confirm of the same armed option
            advanceUntilIdle()

            // The second confirm sends exactly one answer and clears the arm (AC #2).
            assertEquals(listOf("m1" to "allow_once"), recorder.answers)
            assertNull(vm.armedOptionId.value)
        }

    @Test
    fun onModalOption_reTapDifferentOption_reArmsWithoutSending() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(openModal(modalId = "m1", options = fourOptions))
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(modal, recorder)
            advanceUntilIdle()

            vm.onModalOption("allow_once") // arm
            advanceUntilIdle()
            vm.onModalOption("allow_always") // tapping a different option re-arms — never sends the old one
            advanceUntilIdle()

            assertTrue("re-arming a different option must not send", recorder.answers.isEmpty())
            assertEquals("allow_always", vm.armedOptionId.value)
        }

    @Test
    fun onModalCancel_sendsCancelAndClearsArm() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(openModal(modalId = "m1"))
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(modal, recorder)
            advanceUntilIdle()

            vm.onModalOption("allow_once") // arm
            advanceUntilIdle()

            vm.onModalCancel()
            advanceUntilIdle()

            // Cancel sends modal_cancel and clears the arm (AC #3).
            assertEquals(listOf("m1"), recorder.cancels)
            assertTrue(recorder.answers.isEmpty())
            assertNull(vm.armedOptionId.value)
        }

    @Test
    fun modalSend_onFailure_emitsErrorSignalWithoutMutatingModal() =
        runTest {
            // Both documented throws (server `error` incl. the ungranted-device reject; not-connected).
            val failures =
                listOf<Throwable>(
                    RelayErrorException(code = "device.not_granted", retryable = false, message = "no"),
                    IllegalStateException("not connected"),
                )
            for (failure in failures) {
                val modal = MutableStateFlow<ModalUiState>(openModal(modalId = "m1"))
                val recorder = ModalSendRecorder(failWith = failure)
                val vm = vmWithModalSendPath(modal, recorder)
                val errors = mutableListOf<Unit>()
                val errorCollector = launch { vm.modalSendErrors.collect { errors += it } }
                advanceUntilIdle()

                vm.onModalOption("reject_once") // default → single-tap answer that fails
                advanceUntilIdle()

                // Exactly one non-crashing error signal; the modal is untouched so the user can re-answer (AC #4).
                assertEquals(1, errors.size)
                assertEquals("m1", (vm.currentModal.value as ModalUiState.Open).modalId)
                errorCollector.cancel()
            }
        }

    @Test
    fun staleArm_cannotPreArmAFreshModal() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(openModal(modalId = "m1", options = fourOptions))
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(modal, recorder)
            advanceUntilIdle()

            vm.onModalOption("allow_once") // arm on m1
            advanceUntilIdle()
            assertEquals("allow_once", vm.armedOptionId.value)

            // A fresh modal supersedes m1; the m1 arm is scoped to m1, so it never surfaces on m2.
            modal.value = openModal(modalId = "m2", options = fourOptions)
            advanceUntilIdle()
            assertNull("the m1 arm does not pre-arm m2", vm.armedOptionId.value)

            // Tapping the same option on m2 only arms it — the stale m1 arm cannot auto-confirm m2.
            vm.onModalOption("allow_once")
            advanceUntilIdle()
            assertTrue("the stale arm must not auto-confirm a fresh modal", recorder.answers.isEmpty())
            assertEquals("allow_once", vm.armedOptionId.value)
        }

    @Test
    fun modalDecisionMethods_areInertWithNoOpenModal() =
        runTest {
            // A VM with no modal source holds Hidden; the no-op send defaults stay inert (AC #5).
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            val vm = makeVm(handle, FakeConversationRepository())
            advanceUntilIdle()

            vm.onModalOption("anything")
            vm.onModalCancel()
            advanceUntilIdle()

            assertEquals(ModalUiState.Hidden, vm.currentModal.value)
            assertNull(vm.armedOptionId.value)
        }

    @Test
    fun modalSend_scopeCancellationMidSend_doesNotEmitErrorSignal() =
        runTest {
            // Regression guard (#451 rework): on the JVM `kotlinx.coroutines.CancellationException` is a
            // typealias for `j.u.c.CancellationException`, which extends `IllegalStateException` — so a bare
            // `catch (IllegalStateException)` would swallow `viewModelScope` teardown mid-send and fire a
            // spurious error. The `catch (CancellationException) { throw e }` rethrow prevents that.
            val gate = CompletableDeferred<Unit>() // never completes — the send stays suspended in-flight
            val modal = MutableStateFlow<ModalUiState>(openModal(modalId = "m1")) // default = reject_once
            val vm =
                makeVm(
                    SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV)),
                    FakeConversationRepository(),
                    currentModal = modal,
                    answerModal = { _, _ -> gate.await() },
                )
            // Host the VM in a store so store.clear() cancels its viewModelScope — the real teardown path.
            val store = ViewModelStore().apply { put("vm", vm) }
            val errors = mutableListOf<Unit>()
            val errorCollector = launch { vm.modalSendErrors.collect { errors += it } }
            advanceUntilIdle()

            vm.onModalOption("reject_once") // single-tap send; suspends on `gate`
            advanceUntilIdle()
            assertTrue("the send must still be suspended in-flight", errors.isEmpty())

            store.clear() // cancels viewModelScope → the awaiting send throws CancellationException
            advanceUntilIdle()

            // The rethrow keeps cancellation structured: no spurious error signal fires (AC #4 invariant).
            assertTrue("VM-scope cancellation mid-send must not emit an error signal", errors.isEmpty())
            errorCollector.cancel()
        }

    // ---- #458: onInterrupt outbound send path -------------------------------------------------

    @Test
    fun onInterrupt_sendsInterruptExactlyOnce() =
        runTest {
            val recorder = InterruptRecorder()
            val vm =
                makeVm(
                    SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV)),
                    FakeConversationRepository(),
                    interrupt = recorder.interrupt,
                )
            advanceUntilIdle()

            vm.onInterrupt()
            advanceUntilIdle()

            // AC #4 happy path: the injected send is invoked exactly once.
            assertEquals(1, recorder.count)
        }

    @Test
    fun onInterrupt_whenSendFailsInert_isSwallowedWithoutCrashing() =
        runTest {
            // AC #4: both inert throws — not-connected (IllegalStateException) and the retained relay-error
            // path (RelayErrorException, unreachable on the real fire-and-forget path but caught for parity)
            // — are swallowed: the attempt is made, the throw never escapes, no error signal exists.
            //
            // A throw that escaped the launched coroutine would NOT fail runTest (viewModelScope is a
            // separate SupervisorJob scope, not the test's), so a `count == 1` assertion alone would
            // false-green. Capture uncaught coroutine exceptions via the default handler (the
            // Unconfined-Main coroutine reports through `currentThread().uncaughtExceptionHandler`) and
            // assert none fired — the only signal that the typed catch actually ran.
            val uncaught = mutableListOf<Throwable>()
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
            try {
                val failures =
                    listOf<Throwable>(
                        IllegalStateException("not connected"),
                        RelayErrorException(code = "server.error", retryable = false, message = "no"),
                    )
                for (failure in failures) {
                    val recorder = InterruptRecorder(failWith = failure)
                    val vm =
                        makeVm(
                            SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV)),
                            FakeConversationRepository(),
                            interrupt = recorder.interrupt,
                        )
                    advanceUntilIdle()

                    vm.onInterrupt()
                    advanceUntilIdle()

                    assertEquals(1, recorder.count) // the attempt was made
                }
                assertTrue("interrupt failures must be swallowed, not propagated: $uncaught", uncaught.isEmpty())
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            }
        }

    @Test
    fun onInterrupt_scopeCancellationMidSend_propagatesCancellationInert() =
        runTest {
            // AC #3 structured-cancellation guard mirroring #451: `catch (CancellationException) { throw e }`
            // MUST precede the typed `catch (IllegalStateException)` — on the JVM j.u.c.CancellationException
            // extends IllegalStateException. Interrupt's catches are empty (no error channel), so teardown
            // mid-send is inert; this exercises the real viewModelScope teardown path and asserts it neither
            // crashes nor leaks an exception (runTest fails otherwise).
            val gate = CompletableDeferred<Unit>() // never completes — the send stays suspended in-flight
            val entered = CompletableDeferred<Unit>()
            val vm =
                makeVm(
                    SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV)),
                    FakeConversationRepository(),
                    interrupt = {
                        entered.complete(Unit)
                        gate.await()
                    },
                )
            val store = ViewModelStore().apply { put("vm", vm) }

            vm.onInterrupt()
            advanceUntilIdle()
            assertTrue("the send must be in-flight", entered.isCompleted)

            store.clear() // cancels viewModelScope → the awaiting send throws CancellationException
            advanceUntilIdle()
            // No crash, no leaked exception: structured cancellation preserved.
        }

    // ---- #467: onDropQueued — drop a queued-backlog message via the #466 facade send -----------

    @Test
    fun onDropQueued_routesOwnConversationIdAndIdVerbatimToRepository() =
        runTest {
            val repo = QueueControllableRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            val vm = makeVm(handle, repo)
            advanceUntilIdle()

            vm.onDropQueued(42L)
            advanceUntilIdle()

            // AC #2: exactly one drop, carrying the VM's own conversation id and the queued-message id
            // passed straight through (never a caller-supplied conversation id).
            assertEquals(listOf(ACTIVE_CONV to 42L), repo.dropCalls)
        }

    @Test
    fun onDropQueued_doesNotMutateBacklog_rowLeavesOnlyOnNextQueueState() =
        runTest {
            val repo = QueueControllableRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            val a = QueuedMessage(1L, "a", Instant.parse("2026-06-23T10:00:00Z"))
            val b = QueuedMessage(2L, "b", Instant.parse("2026-06-23T10:00:01Z"))
            repo.queue.value = listOf(a, b)
            advanceUntilIdle()
            assertEquals(listOf(a, b), vm.state.value.queuedMessages)

            // AC #3: no optimistic removal — the drop fires but the backlog is unchanged afterwards.
            vm.onDropQueued(a.id)
            advanceUntilIdle()
            assertEquals(listOf(ACTIVE_CONV to 1L), repo.dropCalls)
            assertEquals(listOf(a, b), vm.state.value.queuedMessages)

            // AC #5 (disappears half): the row leaves only when the daemon broadcasts the next queue_state.
            repo.queue.value = listOf(b)
            advanceUntilIdle()
            assertEquals(listOf(b), vm.state.value.queuedMessages)
            collector.cancel()
        }

    @Test
    fun onDropQueued_whenDropFailsInert_isSwallowedWithoutCrashing() =
        runTest {
            // AC #4: both inert throws — not-connected (IllegalStateException) and a server-error reply
            // (RelayErrorException) — are swallowed: the attempt is made, the throw never escapes. A
            // `dropCalls.size == 1` assertion alone would false-green (the throw escapes the launched
            // coroutine into the default handler, not runTest), so capture uncaught exceptions and assert
            // none fired — the only proof the typed catch actually ran. Mirrors the #458 swallow test.
            val uncaught = mutableListOf<Throwable>()
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
            try {
                val failures =
                    listOf<Throwable>(
                        IllegalStateException("not connected"),
                        RelayErrorException(code = "server.error", retryable = false, message = "no"),
                    )
                for (failure in failures) {
                    val repo = QueueControllableRepo(onDrop = { throw failure })
                    val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
                    val vm = makeVm(handle, repo)
                    advanceUntilIdle()

                    vm.onDropQueued(7L)
                    advanceUntilIdle()

                    assertEquals(listOf(ACTIVE_CONV to 7L), repo.dropCalls) // the attempt was made
                }
                assertTrue("drop failures must be swallowed, not propagated: $uncaught", uncaught.isEmpty())
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            }
        }

    @Test
    fun onDropQueued_scopeCancellationMidSend_propagatesCancellationInert() =
        runTest {
            // Structured-cancellation guard mirroring #458: `catch (CancellationException) { throw e }` MUST
            // precede the typed `catch (IllegalStateException)` — j.u.c.CancellationException extends ISE on
            // the JVM. The drop suspends mid-send; viewModelScope teardown must neither crash nor swallow the
            // cancellation (runTest fails otherwise).
            val gate = CompletableDeferred<Unit>() // never completes — the send stays suspended in-flight
            val entered = CompletableDeferred<Unit>()
            val repo =
                QueueControllableRepo(
                    onDrop = {
                        entered.complete(Unit)
                        gate.await()
                    },
                )
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            val vm = makeVm(handle, repo)
            val store = ViewModelStore().apply { put("vm", vm) }

            vm.onDropQueued(1L)
            advanceUntilIdle()
            assertTrue("the send must be in-flight", entered.isCompleted)

            store.clear() // cancels viewModelScope → the awaiting drop throws CancellationException
            advanceUntilIdle()
            // No crash, no leaked exception: structured cancellation preserved.
        }

    // ---- #490: one-shot repository-call guard (launchGuardedRepoCall) --------------------------

    @Test
    fun guardedRepoCalls_whenRepositoryThrowsEachHandledType_areSwallowedWithoutCrashing() =
        runTest {
            // AC #2/#3: every one-shot repo launch in this VM (sendMessage, onWorkspacePicked, and the
            // Archive / DeleteConfirm / RenameSubmit / SaveAsChannelSubmit overflow arms) swallows the three
            // relay failure types. A throw escaping the launched coroutine reaches the default handler (not
            // runTest — viewModelScope is a separate SupervisorJob), so capture uncaught throws and assert
            // none fired: the only proof the typed catch ran. Mirrors onDropQueued_whenDropFailsInert.
            val uncaught = mutableListOf<Throwable>()
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
            try {
                val failures =
                    listOf<Throwable>(
                        IllegalStateException("not connected"),
                        RelayErrorException(code = "server.error", retryable = false, message = "no"),
                        UnsupportedOperationException("not wired"),
                    )
                for (failure in failures) {
                    val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
                    val vm = makeVm(handle, ThrowingConversationRepository(failure))
                    advanceUntilIdle()

                    vm.sendMessage("hi")
                    vm.onWorkspacePicked("pyry-workspace/app")
                    vm.onOverflowEvent(ThreadEvent.Archive)
                    vm.onOverflowEvent(ThreadEvent.DeleteConfirm)
                    vm.onOverflowEvent(ThreadEvent.RenameSubmit("new name"))
                    vm.onOverflowEvent(ThreadEvent.SaveAsChannelSubmit("chan", WorkspaceChoice.SCRATCH))
                    advanceUntilIdle()
                }
                assertTrue("guarded repo-call failures must be swallowed, not propagated: $uncaught", uncaught.isEmpty())
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            }
        }

    @Test
    fun guardedRepoCalls_whenArchiveOrDeleteThrows_doNotPopBack() =
        runTest {
            // AC #4: the follow-on navigationChannel.send(PopBack) sits inside the guarded block, after the
            // repo call, so a throwing archive/delete jumps to the catch and the pop-back is skipped. Also
            // assert no uncaught throw escaped — the failure is quiet, not a crash.
            val uncaught = mutableListOf<Throwable>()
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
            try {
                val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
                val vm =
                    makeVm(
                        handle,
                        ThrowingConversationRepository(
                            RelayErrorException(code = "server.error", retryable = false, message = "no"),
                        ),
                    )
                val nav = mutableListOf<ThreadNavigation>()
                val navCollector = launch { vm.navigationEvents.collect { nav += it } }
                advanceUntilIdle()

                vm.onOverflowEvent(ThreadEvent.Archive)
                vm.onOverflowEvent(ThreadEvent.DeleteConfirm)
                advanceUntilIdle()

                assertTrue("a failed archive/delete must not pop back: $nav", nav.isEmpty())
                assertTrue("archive/delete failures must be swallowed: $uncaught", uncaught.isEmpty())
                navCollector.cancel()
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            }
        }

    @Test
    fun guardedRepoCall_scopeCancellationMidCall_propagatesCancellationInert() =
        runTest {
            // AC #3 (the guard is shared, so one path proves the ordering for all): `catch
            // (CancellationException) { throw e }` MUST precede the typed `catch (IllegalStateException)` —
            // j.u.c.CancellationException extends ISE on the JVM (the #451 rework). An archive suspends
            // mid-call; viewModelScope teardown must neither crash nor let the PopBack side effect fire.
            val gate = CompletableDeferred<Unit>() // never completes — the archive stays suspended in-flight
            val entered = CompletableDeferred<Unit>()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            val vm = makeVm(handle, GatingArchiveRepo(gate = gate, entered = entered))
            val nav = mutableListOf<ThreadNavigation>()
            val navCollector = launch { vm.navigationEvents.collect { nav += it } }
            val store = ViewModelStore().apply { put("vm", vm) }

            vm.onOverflowEvent(ThreadEvent.Archive)
            advanceUntilIdle()
            assertTrue("the archive must be in-flight", entered.isCompleted)

            store.clear() // cancels viewModelScope → the awaiting archive throws CancellationException
            advanceUntilIdle()
            // No crash, no leaked exception (runTest fails otherwise), and the guarded side effect is skipped.
            assertTrue("cancellation must skip the PopBack side effect: $nav", nav.isEmpty())
            navCollector.cancel()
        }

    // ---- #396: isStalled projection over repository.observeStall ------------------------------

    @Test
    fun isStalled_initialValue_isFalseWithNonStallingRepo() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            // A plain fake inherits observeStall's flowOf(false) default — the flag stays inert.
            val vm = makeVm(handle, FakeConversationRepository())
            assertFalse(vm.isStalled.value)
        }

    @Test
    fun isStalled_reflectsOnsetThenClear() =
        runTest {
            val repo = StallControllableRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.isStalled.collect {} }
            advanceUntilIdle()

            repo.stall.value = true
            advanceUntilIdle()
            assertTrue(vm.isStalled.value) // AC #1 — onset promotes.

            repo.stall.value = false
            advanceUntilIdle()
            assertFalse(vm.isStalled.value) // AC #3 — clears when the stall resolves.
            collector.cancel()
        }

    @Test
    fun isStalled_observesOnlyOwnConversationId() =
        runTest {
            val repo = StallControllableRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.isStalled.collect {} }
            advanceUntilIdle()

            assertTrue(repo.observedIds.isNotEmpty())
            assertTrue(repo.observedIds.all { it == ACTIVE_CONV })
            collector.cancel()
        }

    // ---- #461: queuedMessages projection over repository.observeQueue ----------------------------

    @Test
    fun queuedMessages_initialValue_isEmptyWithNonQueueingRepo() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            // A plain fake inherits observeQueue's flowOf(emptyList()) default — the backlog stays empty.
            val vm = makeVm(handle, FakeConversationRepository())
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals(emptyList<QueuedMessage>(), vm.state.value.queuedMessages)
            collector.cancel()
        }

    @Test
    fun queuedMessages_reflectsOrderedSnapshotThenFullReplaceThenClear() =
        runTest {
            val repo = QueueControllableRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            val a = QueuedMessage(1L, "a", Instant.parse("2026-06-23T10:00:00Z"))
            val b = QueuedMessage(2L, "b", Instant.parse("2026-06-23T10:00:01Z"))
            repo.queue.value = listOf(a, b)
            advanceUntilIdle()
            // AC #1 ordered (wire order preserved), AC #3/#4 reactive surfacing through UiState.
            assertEquals(listOf(a, b), vm.state.value.queuedMessages)

            val c = QueuedMessage(3L, "c", Instant.parse("2026-06-23T10:00:02Z"))
            repo.queue.value = listOf(c)
            advanceUntilIdle()
            // Full-snapshot semantics flow through: the new snapshot replaces, not appends.
            assertEquals(listOf(c), vm.state.value.queuedMessages)

            repo.queue.value = emptyList()
            advanceUntilIdle()
            assertEquals(emptyList<QueuedMessage>(), vm.state.value.queuedMessages)
            collector.cancel()
        }

    @Test
    fun queuedMessages_observesOnlyOwnConversationId() =
        runTest {
            val repo = QueueControllableRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            assertTrue(repo.observedIds.isNotEmpty())
            assertTrue(repo.observedIds.all { it == ACTIVE_CONV })
            collector.cancel()
        }

    // ---- #337: accumulate assistant_delta into a growing streaming MessageItem -------------------

    @Test
    fun assistantDeltas_produceSingleGrowingStreamingMessage() =
        runTest {
            val repo = MessagesControllableRepo()
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = makeVm(activeHandle(), repo, liveSessionEvents = events)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            events.emit(turnState(ACTIVE_CONV, LiveSessionEvent.TurnState.Phase.Responding))
            events.emit(LiveSessionEvent.AssistantDelta(ACTIVE_CONV, turnId = "t1", seq = 0, text = "Hel"))
            advanceUntilIdle()
            assertEquals(listOf("Hel"), streamingContents(vm))

            events.emit(LiveSessionEvent.AssistantDelta(ACTIVE_CONV, turnId = "t1", seq = 1, text = "lo"))
            advanceUntilIdle()
            // One growing streaming item — accumulated text, isStreaming == true (AC #1).
            assertEquals(listOf("Hello"), streamingContents(vm))
            assertEquals(
                1,
                vm.state.value.items
                    .count { it is ThreadItem.MessageItem },
            )
            collector.cancel()
        }

    @Test
    fun assistantDeltas_outOfOrderOrDuplicateSeq_areIgnored() =
        runTest {
            val repo = MessagesControllableRepo()
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = makeVm(activeHandle(), repo, liveSessionEvents = events)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            events.emit(LiveSessionEvent.AssistantDelta(ACTIVE_CONV, "t1", seq = 0, text = "A"))
            events.emit(LiveSessionEvent.AssistantDelta(ACTIVE_CONV, "t1", seq = 1, text = "B"))
            advanceUntilIdle()
            assertEquals(listOf("AB"), streamingContents(vm))

            // A replayed seq (== lastSeq) and an out-of-order seq (< lastSeq) leave the text unchanged (AC #2).
            events.emit(LiveSessionEvent.AssistantDelta(ACTIVE_CONV, "t1", seq = 1, text = "X"))
            events.emit(LiveSessionEvent.AssistantDelta(ACTIVE_CONV, "t1", seq = 0, text = "Y"))
            advanceUntilIdle()
            assertEquals(listOf("AB"), streamingContents(vm))
            collector.cancel()
        }

    @Test
    fun assistantDelta_forOtherConversation_neverAddsItem() =
        runTest {
            val repo = MessagesControllableRepo()
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = makeVm(activeHandle(), repo, liveSessionEvents = events)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            // A delta for a different conversation must never surface in this thread (AC #2 confidentiality).
            events.emit(LiveSessionEvent.AssistantDelta("other-conversation", "t1", seq = 0, text = "leak"))
            advanceUntilIdle()
            assertTrue(
                vm.state.value.items
                    .isEmpty(),
            )
            collector.cancel()
        }

    @Test
    fun turnEndThenFinishedMessage_settlesToFinishedWithoutDuplicate() =
        runTest {
            val repo = MessagesControllableRepo()
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = makeVm(activeHandle(), repo, liveSessionEvents = events)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            events.emit(LiveSessionEvent.AssistantDelta(ACTIVE_CONV, "t1", seq = 0, text = "Done"))
            advanceUntilIdle()
            assertEquals(listOf("Done"), streamingContents(vm))

            // turn_end settles the typewriter but the item stays until the finished message arrives (AC #3).
            events.emit(LiveSessionEvent.TurnEnd(ACTIVE_CONV, "t1", stopReason = "end_turn"))
            advanceUntilIdle()
            val settled =
                vm.state.value.items
                    .filterIsInstance<ThreadItem.MessageItem>()
                    .single()
            assertFalse(settled.message.isStreaming)
            assertEquals("Done", settled.message.content)

            // The persisted finished message (distinct id) replaces the streaming item — no duplicate (AC #3/#5).
            repo.messages.value = listOf(assistantMessage(id = "m1", content = "Done"))
            advanceUntilIdle()
            assertEquals(listOf("m1"), messageIds(vm))
            assertTrue(
                vm.state.value.items
                    .none { it is ThreadItem.MessageItem && it.message.isStreaming },
            )
            collector.cancel()
        }

    @Test
    fun finishedMessageBeforeTurnEnd_dropsStreamingWithoutDuplicate() =
        runTest {
            val repo = MessagesControllableRepo()
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = makeVm(activeHandle(), repo, liveSessionEvents = events)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            events.emit(LiveSessionEvent.AssistantDelta(ACTIVE_CONV, "t1", seq = 0, text = "Done"))
            advanceUntilIdle()
            assertEquals(listOf("Done"), streamingContents(vm))

            // The finished message wins the race against turn_end → streaming item dropped immediately (AC #3).
            repo.messages.value = listOf(assistantMessage(id = "m1", content = "Done"))
            advanceUntilIdle()
            assertEquals(listOf("m1"), messageIds(vm))

            // The later turn_end is a no-op — no resurrected streaming item, still single.
            events.emit(LiveSessionEvent.TurnEnd(ACTIVE_CONV, "t1", stopReason = "end_turn"))
            advanceUntilIdle()
            assertEquals(listOf("m1"), messageIds(vm))
            collector.cancel()
        }

    @Test
    fun assistantDelta_turnIdEqualsPersistedMessageId_noDuplicateKeySettlesToSingle() =
        runTest {
            val repo = MessagesControllableRepo()
            val events = MutableSharedFlow<LiveSessionEvent>()
            // The persisted assistant message is already in the baseline at turn start, and the daemon's
            // turnId equals its id — the live-failure ordering #421 surfaced (the colliding id is in the
            // baseline set, so the structural finalise never sees a "new" id and never drops the synthetic).
            repo.messages.value = listOf(assistantMessage(id = "t1", content = "Done"))
            val vm = makeVm(activeHandle(), repo, liveSessionEvents = events)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            events.emit(LiveSessionEvent.AssistantDelta(ACTIVE_CONV, "t1", seq = 0, text = "Done"))
            advanceUntilIdle()

            // AC #1: exactly one item, and no duplicate list key (a duplicate id is a duplicate "msg:<id>" key).
            assertEquals(listOf("t1"), messageIds(vm))
            assertEquals(messageIds(vm).distinct(), messageIds(vm))
            // AC #2: the synthetic is suppressed — no transient streaming bubble, no double-render.
            assertTrue(streamingContents(vm).isEmpty())

            // A later turn_end for the colliding turn keeps it single — no resurrected synthetic.
            events.emit(LiveSessionEvent.TurnEnd(ACTIVE_CONV, "t1", stopReason = "end_turn"))
            advanceUntilIdle()
            assertEquals(listOf("t1"), messageIds(vm))
            assertEquals(messageIds(vm).distinct(), messageIds(vm))
            collector.cancel()
        }

    @Test
    fun streamingThenFinishedMessageWithSameTurnId_settlesWithoutDuplicate() =
        runTest {
            val repo = MessagesControllableRepo()
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = makeVm(activeHandle(), repo, liveSessionEvents = events)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            // AC #3: the synthetic still appears and grows while the turn is in flight (no collision yet).
            events.emit(LiveSessionEvent.AssistantDelta(ACTIVE_CONV, "t1", seq = 0, text = "Don"))
            events.emit(LiveSessionEvent.AssistantDelta(ACTIVE_CONV, "t1", seq = 1, text = "e"))
            advanceUntilIdle()
            assertEquals(listOf("Done"), streamingContents(vm))
            assertEquals(listOf("t1"), messageIds(vm))

            // The finished message arrives with id == turnId — settles to exactly one, no duplicate key.
            repo.messages.value = listOf(assistantMessage(id = "t1", content = "Done"))
            advanceUntilIdle()
            assertEquals(listOf("t1"), messageIds(vm))
            assertEquals(messageIds(vm).distinct(), messageIds(vm))
            assertTrue(streamingContents(vm).isEmpty())
            collector.cancel()
        }

    @Test
    fun streamingTurn_appendsAtEndWithoutReorderingBackfill() =
        runTest {
            val repo = MessagesControllableRepo()
            val events = MutableSharedFlow<LiveSessionEvent>()
            repo.messages.value =
                listOf(
                    userMessage(id = "u1", content = "hi"),
                    assistantMessage(id = "a1", content = "prior answer"),
                )
            val vm = makeVm(activeHandle(), repo, liveSessionEvents = events)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            events.emit(LiveSessionEvent.AssistantDelta(ACTIVE_CONV, "t2", seq = 0, text = "new"))
            advanceUntilIdle()
            // The backfilled items keep their order; exactly one streaming item is appended last (AC #4).
            assertEquals(listOf("u1", "a1", "t2"), messageIds(vm))
            assertEquals(listOf("new"), streamingContents(vm))

            // Finalising with the new persisted message leaves the prior items untouched.
            repo.messages.value =
                listOf(
                    userMessage(id = "u1", content = "hi"),
                    assistantMessage(id = "a1", content = "prior answer"),
                    assistantMessage(id = "a2", content = "new"),
                )
            advanceUntilIdle()
            assertEquals(listOf("u1", "a1", "a2"), messageIds(vm))
            collector.cancel()
        }

    @Test
    fun cancelledTurn_withNoFinishedMessage_keepsSettledPartial() =
        runTest {
            val repo = MessagesControllableRepo()
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = makeVm(activeHandle(), repo, liveSessionEvents = events)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            events.emit(LiveSessionEvent.AssistantDelta(ACTIVE_CONV, "t1", seq = 0, text = "partial"))
            events.emit(LiveSessionEvent.TurnEnd(ACTIVE_CONV, "t1", stopReason = "cancelled"))
            advanceUntilIdle()

            // No finished message ever arrives — the settled partial stays as a non-streaming message, no orphan.
            val item =
                vm.state.value.items
                    .filterIsInstance<ThreadItem.MessageItem>()
                    .single()
            assertEquals("partial", item.message.content)
            assertFalse(item.message.isStreaming)
            collector.cancel()
        }

    @Test
    fun inertLiveEvents_itemsEqualObserveMessagesProjection() =
        runTest {
            val repo = MessagesControllableRepo()
            repo.messages.value =
                listOf(
                    userMessage(id = "u1", content = "hi"),
                    assistantMessage(id = "a1", content = "answer"),
                )
            // Default emptyFlow() live source → behaves exactly as the #313 finished-message thread (AC #5).
            val vm = makeVm(activeHandle(), repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(repo.messages.value, vm.state.value.items)
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
        currentModal: StateFlow<ModalUiState> = MutableStateFlow(ModalUiState.Hidden),
        answerModal: suspend (String, String) -> Unit = { _, _ -> },
        cancelModal: suspend (String) -> Unit = { _ -> },
        interrupt: suspend () -> Unit = { },
    ): ThreadViewModel =
        ThreadViewModel(handle, repository, source, prefs, liveSessionEvents, currentModal, answerModal, cancelModal, interrupt)

    /** A VM whose active conversation is [ACTIVE_CONV], wired to a controllable live-event source. */
    private fun TestScope.vmWithLiveEvents(events: Flow<LiveSessionEvent>): ThreadViewModel =
        makeVm(
            SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV)),
            FakeConversationRepository(),
            liveSessionEvents = events,
        )

    /** A VM whose active conversation is [ACTIVE_CONV], wired to a hoisted current-modal projection (#492). */
    private fun TestScope.vmWithModal(currentModal: StateFlow<ModalUiState>): ThreadViewModel =
        makeVm(
            SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV)),
            FakeConversationRepository(),
            currentModal = currentModal,
        )

    /** A VM (#451) wired to a hoisted current-modal projection plus a [recorder] capturing the outbound
     *  answer/cancel send path. */
    private fun TestScope.vmWithModalSendPath(
        currentModal: StateFlow<ModalUiState>,
        recorder: ModalSendRecorder,
    ): ThreadViewModel =
        makeVm(
            SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV)),
            FakeConversationRepository(),
            currentModal = currentModal,
            answerModal = recorder.answer,
            cancelModal = recorder.cancel,
        )

    /** Records the outbound interrupt calls (#458), optionally throwing [failWith] after recording to
     *  exercise the inert-swallow path. */
    private class InterruptRecorder(
        private val failWith: Throwable? = null,
    ) {
        var count = 0
            private set

        val interrupt: suspend () -> Unit = {
            count++
            failWith?.let { throw it }
        }
    }

    /** Records the outbound modal answer/cancel calls (#451), optionally throwing [failWith] after
     *  recording to exercise the caught-error path. */
    private class ModalSendRecorder(
        private val failWith: Throwable? = null,
    ) {
        val answers = mutableListOf<Pair<String, String>>()
        val cancels = mutableListOf<String>()

        val answer: suspend (String, String) -> Unit = { modalId, optionId ->
            answers += modalId to optionId
            failWith?.let { throw it }
        }
        val cancel: suspend (String) -> Unit = { modalId ->
            cancels += modalId
            failWith?.let { throw it }
        }
    }

    /** A four-option permission modal (`reject_once` is the fail-safe-deny default) for the #451 arm tests. */
    private val fourOptions: List<ModalOption> =
        listOf(
            ModalOption("allow_once", "Allow once"),
            ModalOption("allow_always", "Allow always"),
            ModalOption("reject_once", "Reject once"),
            ModalOption("reject_always", "Reject always"),
        )

    private fun openModal(
        modalId: String,
        modalClass: String = "permission",
        title: String = "Run command?",
        prompt: String = "do the thing",
        options: List<ModalOption> =
            listOf(
                ModalOption("allow_once", "Allow once"),
                ModalOption("reject_once", "Reject once"),
            ),
        defaultOptionId: String = "reject_once",
    ): ModalUiState.Open = ModalUiState.Open(modalId, modalClass, title, prompt, options, defaultOptionId)

    private fun turnState(
        conversationId: String,
        phase: LiveSessionEvent.TurnState.Phase,
    ): LiveSessionEvent = LiveSessionEvent.TurnState(conversationId, phase)

    private fun activeHandle(): SavedStateHandle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))

    private fun assistantMessage(
        id: String,
        content: String,
    ): ThreadItem.MessageItem =
        ThreadItem.MessageItem(
            Message(
                id = id,
                sessionId = "s1",
                role = Role.Assistant,
                content = content,
                timestamp = Instant.parse("2026-06-17T00:00:00Z"),
                isStreaming = false,
            ),
        )

    private fun userMessage(
        id: String,
        content: String,
    ): ThreadItem.MessageItem =
        ThreadItem.MessageItem(
            Message(
                id = id,
                sessionId = "s1",
                role = Role.User,
                content = content,
                timestamp = Instant.parse("2026-06-17T00:00:00Z"),
                isStreaming = false,
            ),
        )

    /** Ids of every [ThreadItem.MessageItem] in the VM's items, in order. */
    private fun messageIds(vm: ThreadViewModel): List<String> =
        vm.state.value.items
            .filterIsInstance<ThreadItem.MessageItem>()
            .map { it.message.id }

    /** Content of every currently-streaming assistant message in the VM's items, in order. */
    private fun streamingContents(vm: ThreadViewModel): List<String> =
        vm.state.value.items
            .filterIsInstance<ThreadItem.MessageItem>()
            .filter { it.message.isStreaming }
            .map { it.message.content }

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

    /**
     * Delegates the whole [ConversationRepository] surface to a seeded [FakeConversationRepository]
     * (so the VM's `state` pipeline stays populated) and overrides only [observeStall] with a
     * controllable [MutableStateFlow], recording each observed id for the routing assertion.
     */
    private class StallControllableRepo(
        private val delegate: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by delegate {
        val stall = MutableStateFlow(false)
        val observedIds = mutableListOf<String>()

        override fun observeStall(conversationId: String): Flow<Boolean> {
            observedIds += conversationId
            return stall
        }
    }

    /**
     * Delegates the whole [ConversationRepository] surface to a seeded [FakeConversationRepository]
     * (so the VM's `state` pipeline stays populated) and overrides [observeQueue] with a controllable
     * [MutableStateFlow] (#461) plus [dropQueuedMessage] to record each call and optionally run [onDrop]
     * after recording (#467) — the analog of [InterruptRecorder], but on the repo double since drop is a
     * facade method. [onDrop] defaults to a no-op, so the existing `QueueControllableRepo()` callers stay
     * inert; the swallow test passes a throwing body and the cancellation test a suspending gate.
     */
    private class QueueControllableRepo(
        private val delegate: FakeConversationRepository = FakeConversationRepository(),
        private val onDrop: suspend () -> Unit = {},
    ) : ConversationRepository by delegate {
        val queue = MutableStateFlow<List<QueuedMessage>>(emptyList())
        val observedIds = mutableListOf<String>()
        val dropCalls = mutableListOf<Pair<String, Long>>()

        override fun observeQueue(conversationId: String): Flow<List<QueuedMessage>> {
            observedIds += conversationId
            return queue
        }

        override suspend fun dropQueuedMessage(
            conversationId: String,
            queuedMessageId: Long,
        ) {
            dropCalls += conversationId to queuedMessageId
            onDrop()
        }
    }

    /**
     * Delegates the whole [ConversationRepository] surface to a seeded [FakeConversationRepository] and
     * overrides only [archive] to signal [entered] then suspend on a never-completing [gate] — so a test
     * can cancel viewModelScope while a guarded one-shot repo call is in-flight (#490 AC #3).
     */
    private class GatingArchiveRepo(
        private val gate: CompletableDeferred<Unit>,
        private val entered: CompletableDeferred<Unit>,
        private val delegate: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by delegate {
        override suspend fun archive(conversationId: String) {
            entered.complete(Unit)
            gate.await()
        }
    }

    /**
     * Delegates the whole [ConversationRepository] surface to a seeded [FakeConversationRepository]
     * (so the VM's `state` pipeline stays populated) and overrides only [observeMessages] with a
     * controllable [MutableStateFlow], so a test can push a finished-message projection mid-turn — the
     * fake cannot drive a finished message into the thread on demand.
     */
    private class MessagesControllableRepo(
        private val delegate: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by delegate {
        val messages = MutableStateFlow<List<ThreadItem>>(emptyList())

        override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = messages
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
