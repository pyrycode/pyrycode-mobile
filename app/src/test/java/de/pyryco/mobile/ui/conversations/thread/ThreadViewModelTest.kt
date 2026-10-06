package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.HostModalState
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.ModalAction
import de.pyryco.mobile.data.model.ModalEvent
import de.pyryco.mobile.data.model.ModalOption
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.model.reconnected
import de.pyryco.mobile.data.model.reduce
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.HistoryCoverage
import de.pyryco.mobile.data.repository.HistoryEntry
import de.pyryco.mobile.data.repository.HistoryPage
import de.pyryco.mobile.data.repository.HistoryPosition
import de.pyryco.mobile.data.repository.ModelMenu
import de.pyryco.mobile.data.repository.ModelMenuRow
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.ResetStatus
import de.pyryco.mobile.data.repository.SessionSettings
import de.pyryco.mobile.data.repository.StableConversationRepository
import de.pyryco.mobile.data.repository.SystemPromptLimit
import de.pyryco.mobile.data.repository.ThinkingProgress
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UsageLimitReading
import de.pyryco.mobile.data.repository.mergeCachedRows
import de.pyryco.mobile.data.repository.reduceHistoryPage
import de.pyryco.mobile.ui.conversations.ThrowingConversationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelTest {
    /**
     * The history walk's breadcrumbs (#778), captured rather than printed. Required, not optional:
     * `RelayLog`'s default sink is `android.util.Log.println`, which throws on plain JVM, and the walk
     * logs on every classified failure — which the opening ask hits in most of this file's tests, because
     * the inline doubles inherit the interface default.
     */
    private val logs = mutableListOf<String>()
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
    }

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
            // A repo that delegates every read to a seeded fake (so the state pipeline still
            // assembles) but reports mutationsSupported = false — the general non-supporting case.
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

    // ---- #1113: the conversation's agent rides the state, for the rows that name it ----

    @Test
    fun agent_followsTheConversation() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val fake = FakeConversationRepository()
            val repo =
                object : ConversationRepository by fake {
                    override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
                        fake.observeConversations(filter).map { rows -> rows.map { it.copy(agent = ConversationAgent.Codex) } }
                }
            val vm = makeVm(handle, repo)
            assertEquals(ConversationAgent.Claude, vm.state.value.agent) // initialValue: not yet known
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals(ConversationAgent.Codex, vm.state.value.agent)
            collector.cancel()
        }

    @Test
    fun agent_isClaudeForAClaudeConversation() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, FakeConversationRepository())
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals(ConversationAgent.Claude, vm.state.value.agent)
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

    // ---- #406 / #459 / #1313: isThinking and isBusy read the repository's held turn phase ------------

    @Test
    fun turnFlags_initialValue_areFalseWithNoLiveSource() =
        runTest {
            // A plain fake inherits observeTurnPhase's idle default, so both flags stay inert.
            val vm = makeVm(activeHandle(), FakeConversationRepository())
            assertFalse(vm.isThinking.value)
            assertFalse(vm.isBusy.value)
        }

    @Test
    fun turnFlags_followTheHeldPhase() =
        runTest {
            val repo = TurnPhaseControllableRepo()
            val vm = makeVm(activeHandle(), repo)
            val thinking = launch { vm.isThinking.collect {} }
            val busy = launch { vm.isBusy.collect {} }
            advanceUntilIdle()

            repo.phase.value = LiveSessionEvent.TurnState.Phase.Thinking
            advanceUntilIdle()
            assertTrue(vm.isThinking.value)
            assertTrue(vm.isBusy.value)

            // `responding` is busy but not thinking: the stop affordance must last the whole turn.
            repo.phase.value = LiveSessionEvent.TurnState.Phase.Responding
            advanceUntilIdle()
            assertFalse(vm.isThinking.value)
            assertTrue(vm.isBusy.value)

            repo.phase.value = LiveSessionEvent.TurnState.Phase.Idle
            advanceUntilIdle()
            assertFalse(vm.isThinking.value)
            assertFalse(vm.isBusy.value)
            thinking.cancel()
            busy.cancel()
        }

    @Test
    fun turnFlags_observeOnlyOwnConversationId() =
        runTest {
            val repo = TurnPhaseControllableRepo()
            val vm = makeVm(activeHandle(), repo)
            val thinking = launch { vm.isThinking.collect {} }
            val busy = launch { vm.isBusy.collect {} }
            advanceUntilIdle()

            assertTrue(repo.observedIds.isNotEmpty())
            assertTrue(repo.observedIds.all { it == ACTIVE_CONV })
            thinking.cancel()
            busy.cancel()
        }

    @Test
    fun turnFlags_ignoreLiveEvents() =
        runTest {
            // The per-ViewModel fold is gone: a live `turn_state` alone no longer moves the flags.
            val events = MutableSharedFlow<LiveSessionEvent>()
            val vm = vmWithLiveEvents(events)
            val thinking = launch { vm.isThinking.collect {} }
            val busy = launch { vm.isBusy.collect {} }
            advanceUntilIdle()

            events.emit(turnState(ACTIVE_CONV, LiveSessionEvent.TurnState.Phase.Thinking))
            advanceUntilIdle()
            assertFalse(vm.isThinking.value)
            assertFalse(vm.isBusy.value)
            thinking.cancel()
            busy.cancel()
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
            advanceUntilIdle()

            vm.onModalOption("reject_once")
            advanceUntilIdle()

            // The fail-safe-deny default answers on a single tap (AC #1) — no arm.
            assertEquals(listOf("m1" to "reject_once"), recorder.answers)
            assertTrue(recorder.cancels.isEmpty())
            assertNull(vm.armedOptionId.value)
            assertFalse(vm.answerRejected.value)
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

    // ---- #1340: the prompt closes on the tap; only a refused answer stays visible ------------------

    @Test
    fun answerAndCancel_closeThePromptBeforeTheDaemonReplies_andAnnounceNothing() =
        runTest {
            val gate = CompletableDeferred<Unit>() // the daemon has not replied yet
            val taps =
                listOf<(ThreadViewModel) -> Unit>(
                    { it.onModalOption("reject_once", "m1") }, // the default, single tap
                    {
                        it.onModalOption("allow_once", "m1") // arm
                        it.onModalOption("allow_once", "m1") // confirm
                    },
                    { it.onModalCancel("m1") },
                )
            for (tap in taps) {
                val host = MutableStateFlow(HostModalState(listOf(openModal(modalId = "m1"))))
                val vm =
                    hostChat(
                        ACTIVE_CONV,
                        host,
                        ModalSendRecorder(),
                        answerModal = { _, _, _ -> gate.await() },
                        cancelModal = { gate.await() },
                    )
                advanceUntilIdle()

                tap(vm)
                advanceUntilIdle()

                assertEquals("closed while the send is in flight", ModalUiState.Hidden, vm.currentModal.value)
                assertTrue(host.value.outstanding.isEmpty())

                // A repeated modal_shown for the answered id on this connection does not bring it back.
                host.value = host.value.reduce(shownEvent("m1"))
                advanceUntilIdle()
                assertEquals(ModalUiState.Hidden, vm.currentModal.value)

                // After a reconnect the daemon's re-send does.
                host.value = host.value.reconnected().reduce(shownEvent("m1"))
                advanceUntilIdle()
                assertEquals("m1", (vm.currentModal.value as ModalUiState.Open).modalId)
            }
        }

    @Test
    fun aRefusedAnswer_showsTheNoticeInItsOwnChatOnly_untilDismissed() =
        runTest {
            val host = MutableStateFlow(HostModalState(listOf(openModal(modalId = "a1"))))
            val refused = ModalSendRecorder(failWith = RelayErrorException(code = "device.not_granted", retryable = false, message = "no"))
            val chatA = hostChat(ACTIVE_CONV, host, refused)
            val chatB = hostChat(OTHER_CONV, host, ModalSendRecorder())
            advanceUntilIdle()

            chatA.onModalOption("reject_once", "a1")
            advanceUntilIdle()

            assertTrue(chatA.answerRejected.value)
            assertFalse("another chat shows nothing", chatB.answerRejected.value)
            assertEquals(ModalUiState.Hidden, chatA.currentModal.value)

            // It survives a reconnect, and a chat opened afterwards (Back and reopen) still reads it.
            host.value = host.value.reconnected()
            advanceUntilIdle()
            assertTrue(chatA.answerRejected.value)
            assertTrue(hostChat(ACTIVE_CONV, host, ModalSendRecorder()).answerRejected.value)

            chatA.onAnswerRejectionDismissed()
            advanceUntilIdle()
            assertFalse(chatA.answerRejected.value)
        }

    @Test
    fun aRefusedCancel_andAnUnsentAnswerOrCancel_showNothing() =
        runTest {
            val cases =
                listOf<Pair<Throwable, (ThreadViewModel) -> Unit>>(
                    RelayErrorException(code = "modal.stale", retryable = false, message = "no") to { it.onModalCancel("m1") },
                    IllegalStateException("not connected") to { it.onModalCancel("m1") },
                    IllegalStateException("not connected") to { it.onModalOption("reject_once", "m1") },
                )
            for ((failure, tap) in cases) {
                val host = MutableStateFlow(HostModalState(listOf(openModal(modalId = "m1"))))
                val vm = hostChat(ACTIVE_CONV, host, ModalSendRecorder(failWith = failure))
                advanceUntilIdle()

                tap(vm)
                advanceUntilIdle()

                assertFalse(vm.answerRejected.value)
                assertEquals(emptySet<String>(), host.value.rejectedConversations)
                assertEquals(ModalUiState.Hidden, vm.currentModal.value)
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

    // ---- #1321: a permission tap that races a disconnect sends nothing ---------------------------------
    // Each case switches the source just before the call and never collects vm.connectionState, whose
    // optimistic Connected seed must not open the gate.

    @Test
    fun onModalOption_whileNotConnected_neitherSendsNorArms_untilConnectedReturns() =
        runTest {
            val source = FakeConnectionStateSource()
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(MutableStateFlow(openModal(modalId = "m1")), recorder, source = source)

            source.emit(ConnectionState.Offline)
            vm.onModalOption("reject_once") // the default
            source.emit(ConnectionState.Reconnecting(secondsRemaining = 3))
            vm.onModalOption("allow_once") // a first tap that would arm
            advanceUntilIdle()
            assertTrue("no decision may be sent while not connected", recorder.answers.isEmpty())
            assertNull("a disabled option must not arm", vm.armedOptionId.value)

            source.emit(ConnectionState.Connected)
            vm.onModalOption("allow_once")
            assertEquals("the same first tap arms once connected", "allow_once", vm.armedOptionId.value)
            vm.onModalOption("reject_once")
            advanceUntilIdle()
            assertEquals(listOf("m1" to "reject_once"), recorder.answers)
        }

    @Test
    fun armedSecondTap_whileNotConnected_sendsNothing_andAnswersAfterReconnect() =
        runTest {
            val source = FakeConnectionStateSource()
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(MutableStateFlow(openModal(modalId = "m1")), recorder, source = source)
            vm.onModalOption("allow_once") // armed while connected

            source.emit(ConnectionState.Connecting)
            vm.onModalOption("allow_once")
            advanceUntilIdle()
            assertTrue(recorder.answers.isEmpty())
            assertEquals("the arm survives the outage", "allow_once", vm.armedOptionId.value)

            source.emit(ConnectionState.Connected)
            vm.onModalOption("allow_once")
            advanceUntilIdle()
            assertEquals(listOf("m1" to "allow_once"), recorder.answers)
            assertNull(vm.armedOptionId.value)
        }

    @Test
    fun onModalCancel_whileNotConnected_sendsNothing_andKeepsArmAndGrant() =
        runTest {
            val source = FakeConnectionStateSource()
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(MutableStateFlow(offeringModal("m1")), recorder, source = source)
            vm.onAlwaysAllowChanged("m1", true)
            vm.onModalOption("allow_always")

            source.emit(ConnectionState.Offline)
            vm.onModalCancel("m1")
            advanceUntilIdle()
            assertTrue(recorder.cancels.isEmpty())
            assertEquals("allow_always", vm.armedOptionId.value)
            assertTrue("the grant draft is not cleared by a blocked cancel", vm.alwaysAllowAccepted.value)

            source.emit(ConnectionState.Connected)
            vm.onModalCancel("m1")
            advanceUntilIdle()
            assertEquals(listOf("m1"), recorder.cancels)
        }

    @Test
    fun aConnectionSourceThatHasNotReported_gatesLikeOffline() =
        runTest {
            val silent =
                object : ConnectionStateSource {
                    override fun observe(): Flow<ConnectionState> = emptyFlow()

                    override suspend fun retry() = Unit
                }
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(MutableStateFlow(openModal(modalId = "m1")), recorder, source = silent)

            vm.onModalOption("reject_once")
            vm.onModalCancel("m1")
            advanceUntilIdle()
            assertTrue(recorder.answers.isEmpty())
            assertTrue(recorder.cancels.isEmpty())
        }

    // ---- #818: the don't-ask-again offer is scoped to the prompt that showed it --------------------

    private fun offeringModal(
        modalId: String,
        rules: List<String> = listOf("Bash(npm test)", "Read"),
        defaultOptionId: String = "reject_once",
    ): ModalUiState.Open = openModal(modalId = modalId, options = fourOptions, defaultOptionId = defaultOptionId, alwaysAllowRules = rules)

    @Test
    fun acceptedOffer_ridesTheArmedAllowAnswer() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(offeringModal("m1"))
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(modal, recorder)
            advanceUntilIdle()

            vm.onAlwaysAllowChanged("m1", true)
            advanceUntilIdle()
            assertTrue(vm.alwaysAllowAccepted.value)
            // Accepting the offer neither sends nor arms: it is not the second-tap confirmation.
            assertTrue(recorder.answers.isEmpty())
            assertNull(vm.armedOptionId.value)

            vm.onModalOption("allow_always") // arm
            vm.onModalOption("allow_always") // confirm
            advanceUntilIdle()

            assertEquals(listOf("m1" to "allow_always"), recorder.answers)
            assertEquals(listOf(true), recorder.grants)
        }

    @Test
    fun acceptedOffer_ridesAnAllowDefaultAnsweredOnOneTap() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(offeringModal("m1", defaultOptionId = "allow_once"))
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(modal, recorder)
            advanceUntilIdle()

            vm.onAlwaysAllowChanged("m1", true)
            vm.onModalOption("allow_once")
            advanceUntilIdle()

            assertEquals(listOf(true), recorder.grants)
        }

    @Test
    fun allowWithoutAcceptingTheOffer_sendsTheFlagUnset() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(offeringModal("m1"))
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(modal, recorder)
            advanceUntilIdle()

            vm.onAlwaysAllowChanged("m1", true)
            vm.onAlwaysAllowChanged("m1", false) // un-accepted before answering
            advanceUntilIdle()
            assertFalse(vm.alwaysAllowAccepted.value)
            vm.onModalOption("allow_once")
            vm.onModalOption("allow_once")
            advanceUntilIdle()

            assertEquals(listOf("m1" to "allow_once"), recorder.answers)
            assertEquals(listOf(false), recorder.grants)
        }

    // A deny never carries the grant, matching the desktop: the daemon would ignore it anyway.
    @Test
    fun acceptedOffer_doesNotRideADeny() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(offeringModal("m1"))
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(modal, recorder)
            advanceUntilIdle()

            vm.onAlwaysAllowChanged("m1", true)
            vm.onModalOption("reject_once") // the default: one tap
            advanceUntilIdle()

            assertEquals(listOf("m1" to "reject_once"), recorder.answers)
            assertEquals(listOf(false), recorder.grants)
        }

    @Test
    fun promptWithoutAnOffer_cannotBeAccepted() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(openModal(modalId = "m1", options = fourOptions))
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(modal, recorder)
            advanceUntilIdle()

            vm.onAlwaysAllowChanged("m1", true)
            advanceUntilIdle()
            assertFalse(vm.alwaysAllowAccepted.value)

            vm.onModalOption("allow_once")
            vm.onModalOption("allow_once")
            advanceUntilIdle()
            assertEquals(listOf(false), recorder.grants)
        }

    // Rules offered on a non-permission modal are no offer: nothing to accept, nothing sent.
    @Test
    fun nonPermissionModalWithRules_cannotBeAccepted() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(offeringModal("m1").copy(modalClass = "trust"))
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(modal, recorder)
            advanceUntilIdle()

            vm.onAlwaysAllowChanged("m1", true)
            vm.onModalOption("allow_once")
            vm.onModalOption("allow_once")
            advanceUntilIdle()

            assertFalse(vm.alwaysAllowAccepted.value)
            assertEquals(listOf(false), recorder.grants)
        }

    @Test
    fun replacedPrompt_startsUnaccepted_andItsAllowSendsTheFlagUnset() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(offeringModal("m1"))
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(modal, recorder)
            advanceUntilIdle()

            vm.onAlwaysAllowChanged("m1", true)
            advanceUntilIdle()
            assertTrue(vm.alwaysAllowAccepted.value)

            // Same rules, new prompt: the m1 acceptance does not carry over.
            modal.value = offeringModal("m2")
            advanceUntilIdle()
            assertFalse(vm.alwaysAllowAccepted.value)

            vm.onModalOption("allow_once")
            vm.onModalOption("allow_once")
            advanceUntilIdle()
            assertEquals(listOf("m2" to "allow_once"), recorder.answers)
            assertEquals(listOf(false), recorder.grants)
        }

    @Test
    fun samePromptReofferedWithDifferentRules_readsUnaccepted() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(offeringModal("m1", rules = listOf("Read")))
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(modal, recorder)
            advanceUntilIdle()

            vm.onAlwaysAllowChanged("m1", true)
            modal.value = offeringModal("m1", rules = listOf("Read", "Bash(rm -rf /)"))
            advanceUntilIdle()
            assertFalse(vm.alwaysAllowAccepted.value)

            vm.onModalOption("allow_once")
            vm.onModalOption("allow_once")
            advanceUntilIdle()
            assertEquals(listOf(false), recorder.grants)
        }

    // A toggle rendered for a prompt that has since been replaced must not accept its replacement.
    @Test
    fun toggleCarryingAStaleModalId_isIgnored() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(offeringModal("m2"))
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(modal, recorder)
            advanceUntilIdle()

            vm.onAlwaysAllowChanged("m1", true)
            advanceUntilIdle()

            assertFalse(vm.alwaysAllowAccepted.value)
        }

    @Test
    fun resolvedPrompt_leavesNothingAccepted() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(offeringModal("m1"))
            val vm = vmWithModal(modal)
            advanceUntilIdle()

            vm.onAlwaysAllowChanged("m1", true)
            advanceUntilIdle()
            assertTrue(vm.alwaysAllowAccepted.value)

            modal.value = ModalUiState.Dismissed("m1", "allow_once", "remote", ACTIVE_CONV)
            advanceUntilIdle()
            assertFalse(vm.alwaysAllowAccepted.value)
        }

    @Test
    fun cancel_neverCarriesTheGrant_andClearsTheAcceptance() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(offeringModal("m1"))
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(modal, recorder)
            advanceUntilIdle()

            vm.onAlwaysAllowChanged("m1", true)
            vm.onModalCancel()
            advanceUntilIdle()

            assertEquals(listOf("m1"), recorder.cancels)
            assertTrue(recorder.answers.isEmpty())
            assertFalse(vm.alwaysAllowAccepted.value)
        }

    // A failed send leaves the prompt open with the user's choice intact, so a retry sends the same intent.
    @Test
    fun failedSend_keepsTheAcceptanceForTheRetry() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(offeringModal("m1", defaultOptionId = "allow_once"))
            val recorder = ModalSendRecorder(failWith = IllegalStateException("not connected"))
            val vm = vmWithModalSendPath(modal, recorder)
            advanceUntilIdle()

            vm.onAlwaysAllowChanged("m1", true)
            vm.onModalOption("allow_once")
            advanceUntilIdle()
            assertTrue(vm.alwaysAllowAccepted.value)

            vm.onModalOption("allow_once")
            advanceUntilIdle()
            assertEquals(listOf(true, true), recorder.grants)
        }

    // ---- #1306: the grant draft outlives the destination; the arm and stale taps do not ------------

    @Test
    fun grantDraft_survivesLeavingAndReopening_andRidesTheNewDestinationsAllow() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(offeringModal("m1"))
            val store = PermissionDraftStore(Dispatchers.Unconfined)
            store.bind("host", owner = "coordinator", modals = modal.asHostModals())
            val first = vmWithModalSendPath(modal, ModalSendRecorder(), store)
            advanceUntilIdle()
            first.onAlwaysAllowChanged("m1", true)
            first.onModalOption("allow_once", "m1") // armed, then Back destroys this destination

            val recorder = ModalSendRecorder()
            val reopened = vmWithModalSendPath(modal, recorder, store)
            advanceUntilIdle()
            assertTrue("the checkbox draft returns", reopened.alwaysAllowAccepted.value)
            assertNull("the arm does not", reopened.armedOptionId.value)

            reopened.onModalOption("allow_once", "m1")
            advanceUntilIdle()
            assertTrue("one tap on the reopened thread only arms", recorder.answers.isEmpty())
            reopened.onModalOption("allow_once", "m1")
            advanceUntilIdle()
            assertEquals(listOf("m1" to "allow_once"), recorder.answers)
            assertEquals(listOf(true), recorder.grants)
        }

    @Test
    fun leavingTheConversation_clearsTheArm_soAllowingNeedsTwoFreshTaps() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(offeringModal("m1"))
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(modal, recorder)
            advanceUntilIdle()

            vm.onModalOption("allow_once", "m1")
            advanceUntilIdle()
            assertEquals("allow_once", vm.armedOptionId.value)
            vm.onConversationLeft()
            advanceUntilIdle()
            assertNull(vm.armedOptionId.value)

            vm.onModalOption("allow_once", "m1")
            advanceUntilIdle()
            assertTrue("the first tap after returning only re-arms", recorder.answers.isEmpty())
            vm.onModalOption("allow_once", "m1")
            advanceUntilIdle()
            assertEquals(listOf("m1" to "allow_once"), recorder.answers)
        }

    @Test
    fun tapsRenderedForAReplacedRequest_neitherAnswerNorCancelTheReplacement() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(offeringModal("m2"))
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(modal, recorder)
            advanceUntilIdle()

            vm.onModalOption("reject_once", "m1") // the default: one tap would answer m2
            vm.onModalOption("allow_once", "m1")
            vm.onModalOption("allow_once", "m1")
            vm.onModalCancel("m1")
            advanceUntilIdle()

            assertTrue(recorder.answers.isEmpty())
            assertTrue(recorder.cancels.isEmpty())
            assertNull(vm.armedOptionId.value)
        }

    @Test
    fun grantDraftForAReplacedRequest_isNotCarriedByTheReplacementsAllow() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(offeringModal("m1", defaultOptionId = "allow_once"))
            val store = PermissionDraftStore(Dispatchers.Unconfined)
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(modal, recorder, store)
            advanceUntilIdle()
            vm.onAlwaysAllowChanged("m1", true)

            modal.value = offeringModal("m2", defaultOptionId = "allow_once")
            advanceUntilIdle()
            assertFalse(vm.alwaysAllowAccepted.value)
            vm.onModalOption("allow_once", "m2")
            advanceUntilIdle()
            assertEquals(listOf(false), recorder.grants)
        }

    // ---- #816: the host's modal is scoped to the conversation that raised it ---------------------

    @Test
    fun modalForAnotherConversation_isHiddenAndCannotBeAnswered() =
        runTest {
            val modal =
                MutableStateFlow<ModalUiState>(
                    openModal(modalId = "m1", options = fourOptions, conversationId = "other-conv"),
                )
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(modal, recorder)
            advanceUntilIdle()

            assertEquals(ModalUiState.Hidden, vm.currentModal.value)

            vm.onModalOption("reject_once") // the default: would answer on a single tap if owned
            vm.onModalOption("allow_once")
            vm.onModalOption("allow_once") // arm + confirm: would answer if owned
            vm.onModalCancel()
            advanceUntilIdle()

            assertTrue("a foreign prompt must not be answerable", recorder.answers.isEmpty())
            assertTrue("a foreign prompt must not be cancellable", recorder.cancels.isEmpty())
            assertNull(vm.armedOptionId.value)
        }

    @Test
    fun modalWithoutConversation_rendersInNoThreadAndIsInert() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(openModal(modalId = "m1", conversationId = ""))
            val recorder = ModalSendRecorder()
            val vm = vmWithModalSendPath(modal, recorder)
            advanceUntilIdle()

            assertEquals(ModalUiState.Hidden, vm.currentModal.value)
            vm.onModalOption("reject_once")
            vm.onModalCancel()
            advanceUntilIdle()

            assertTrue(recorder.answers.isEmpty())
            assertTrue(recorder.cancels.isEmpty())
        }

    @Test
    fun modalScoping_followsTheHostModalAcrossConversations() =
        runTest {
            val modal = MutableStateFlow<ModalUiState>(ModalUiState.Hidden)
            val vm = vmWithModal(modal)
            advanceUntilIdle()

            // A prompt for another conversation, then its resolution, never reach this thread.
            modal.value = openModal(modalId = "m1", conversationId = "other-conv")
            advanceUntilIdle()
            assertEquals(ModalUiState.Hidden, vm.currentModal.value)
            modal.value = ModalUiState.Dismissed("m1", "reject_once", "remote", conversationId = "other-conv")
            advanceUntilIdle()
            assertEquals(ModalUiState.Hidden, vm.currentModal.value)

            // This thread's own prompt, and its resolution, do.
            val own = openModal(modalId = "m2")
            modal.value = own
            advanceUntilIdle()
            assertEquals(own, vm.currentModal.value)
            val dismissed = ModalUiState.Dismissed("m2", "allow_once", "local", conversationId = ACTIVE_CONV)
            modal.value = dismissed
            advanceUntilIdle()
            assertEquals(dismissed, vm.currentModal.value)
        }

    @Test
    fun modalScoping_holdsFromConstruction() =
        runTest {
            // The seeded value is scoped too, so a VM built while a foreign prompt is open never shows it.
            val vm = vmWithModal(MutableStateFlow(openModal(modalId = "m1", conversationId = "other-conv")))
            assertEquals(ModalUiState.Hidden, vm.currentModal.value)

            val own = openModal(modalId = "m2")
            assertEquals(own, vmWithModal(MutableStateFlow(own)).currentModal.value)
        }

    // ---- #1337: the host holds every chat's prompt; each chat shows and answers its own -----------

    @Test
    fun hostPrompts_eachChatShowsItsOwn_andKeepsItsOwnTick_andAnsweringOneLeavesTheOther() =
        runTest {
            val host =
                MutableStateFlow(
                    HostModalState(
                        listOf(
                            offeringModal("a1"),
                            offeringModal("b1").copy(conversationId = OTHER_CONV),
                        ),
                    ),
                )
            val store = PermissionDraftStore(Dispatchers.Unconfined)
            store.bind("host", owner = "coordinator", modals = host)
            val recorder = ModalSendRecorder()
            val chatA = hostChat(ACTIVE_CONV, host, recorder, store)
            val chatB = hostChat(OTHER_CONV, host, recorder, store)
            advanceUntilIdle()
            assertEquals("a1", (chatA.currentModal.value as ModalUiState.Open).modalId)
            assertEquals("b1", (chatB.currentModal.value as ModalUiState.Open).modalId)

            chatA.onAlwaysAllowChanged("a1", true)
            advanceUntilIdle()
            assertTrue(chatA.alwaysAllowAccepted.value)
            assertFalse("A's tick is A's alone", chatB.alwaysAllowAccepted.value)
            chatB.onAlwaysAllowChanged("b1", true)
            chatB.onAlwaysAllowChanged("b1", false)
            advanceUntilIdle()
            assertTrue("B's untick leaves A's", chatA.alwaysAllowAccepted.value)

            chatA.onModalOption("reject_once", "a1") // the default answers on one tap
            advanceUntilIdle()
            assertEquals(listOf("a1" to "reject_once"), recorder.answers)
            // #1340: A's prompt closes on the tap, and the daemon's later dismissal of it changes nothing.
            assertEquals(ModalUiState.Hidden, chatA.currentModal.value)
            host.value = host.value.reduce(ModalEvent.Dismissed("a1", "reject_once", "remote"))
            advanceUntilIdle()
            assertEquals(ModalUiState.Hidden, chatA.currentModal.value)
            assertEquals("b1", (chatB.currentModal.value as ModalUiState.Open).modalId)

            chatB.onModalCancel("b1")
            advanceUntilIdle()
            assertEquals(listOf("b1"), recorder.cancels)
        }

    @Test
    fun hostPrompts_aRepeatedShownForAHeldId_updatesOnlyThatChatsPrompt() =
        runTest {
            val b1 = openModal(modalId = "b1", conversationId = OTHER_CONV)
            val host = MutableStateFlow(HostModalState(listOf(openModal(modalId = "a1"), b1)))
            val chatA = hostChat(ACTIVE_CONV, host, ModalSendRecorder())
            val chatB = hostChat(OTHER_CONV, host, ModalSendRecorder())
            advanceUntilIdle()

            host.value =
                host.value.reduce(
                    ModalEvent.Shown("a1", "permission", "Run command?", "changed", fourOptions, "reject_once", ACTIVE_CONV),
                )
            advanceUntilIdle()

            assertEquals("changed", (chatA.currentModal.value as ModalUiState.Open).prompt)
            assertEquals(b1, chatB.currentModal.value)
        }

    @Test
    fun modalSend_scopeCancellationMidSend_recordsNoRejection() =
        runTest {
            // Regression guard (#451 rework): on the JVM `kotlinx.coroutines.CancellationException` is a
            // typealias for `j.u.c.CancellationException`, which extends `IllegalStateException` — so a bare
            // `catch (IllegalStateException)` would swallow `viewModelScope` teardown mid-send. The
            // `catch (CancellationException) { throw e }` rethrow keeps it structured, and nothing is recorded.
            val gate = CompletableDeferred<Unit>() // never completes — the send stays suspended in-flight
            val actions = mutableListOf<ModalAction>()
            val vm =
                makeVm(
                    SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV)),
                    FakeConversationRepository(),
                    currentModal = MutableStateFlow<ModalUiState>(openModal(modalId = "m1")), // default = reject_once
                    answerModal = { _, _, _ -> gate.await() },
                    recordModalAction = { actions += it },
                )
            // Host the VM in a store so store.clear() cancels its viewModelScope — the real teardown path.
            val store = ViewModelStore().apply { put("vm", vm) }
            advanceUntilIdle()

            vm.onModalOption("reject_once") // single-tap send; suspends on `gate`
            advanceUntilIdle()
            assertEquals(listOf<ModalAction>(ModalAction.AnsweredHere("m1")), actions)

            store.clear() // cancels viewModelScope → the awaiting send throws CancellationException
            advanceUntilIdle()

            assertEquals("teardown mid-send records nothing more", listOf<ModalAction>(ModalAction.AnsweredHere("m1")), actions)
        }

    // ---- #458: onInterrupt outbound send path -------------------------------------------------

    @Test
    fun onInterrupt_targetsOpenConversationAfterA_withoutClearingBusyState() =
        runTest {
            val recorder = InterruptRecorder()
            val repository = TurnPhaseControllableRepo()
            val previous =
                makeVm(
                    SavedStateHandle(mapOf("conversationId" to "c-a")),
                    repository,
                    interrupt = recorder.interrupt,
                )
            previous.onInterrupt()
            advanceUntilIdle()
            assertEquals(listOf("c-a"), recorder.targets)
            recorder.targets.clear()
            val vm =
                makeVm(
                    SavedStateHandle(mapOf("conversationId" to ACTIVE_CONV)),
                    repository,
                    interrupt = recorder.interrupt,
                )
            val busyCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.isBusy.collect {} }
            val stateCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            repository.phase.value = LiveSessionEvent.TurnState.Phase.Responding
            advanceUntilIdle()
            val before = vm.state.value
            assertTrue(vm.isBusy.value)

            vm.onInterrupt()
            advanceUntilIdle()

            assertEquals(listOf(ACTIVE_CONV), recorder.targets)
            assertTrue(vm.isBusy.value)
            assertEquals(before, vm.state.value)
            busyCollector.cancel()
            stateCollector.cancel()
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
                    val repository = TurnPhaseControllableRepo()
                    val vm =
                        makeVm(
                            SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV)),
                            repository,
                            interrupt = recorder.interrupt,
                        )
                    val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
                    val busyCollector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.isBusy.collect {} }
                    repository.phase.value = LiveSessionEvent.TurnState.Phase.Responding
                    advanceUntilIdle()
                    val before = vm.state.value
                    assertTrue(vm.isBusy.value)

                    vm.onInterrupt()
                    advanceUntilIdle()

                    assertEquals(listOf(ACTIVE_CONV), recorder.targets)
                    assertEquals(before, vm.state.value)
                    assertTrue(vm.isBusy.value)
                    collector.cancel()
                    busyCollector.cancel()
                }
                assertTrue("interrupt failures must be swallowed, not propagated: $uncaught", uncaught.isEmpty())
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            }
        }

    @Test
    fun onInterrupt_sendCancellationRemainsCancellation() =
        runTest {
            val cancellation = CancellationException("cancelled send")
            val completion = CompletableDeferred<Throwable?>()
            val vm =
                makeVm(
                    SavedStateHandle(mapOf("conversationId" to ACTIVE_CONV)),
                    FakeConversationRepository(),
                    interrupt = {
                        currentCoroutineContext()[Job]?.invokeOnCompletion { completion.complete(it) }
                        throw cancellation
                    },
                )

            vm.onInterrupt()
            advanceUntilIdle()

            assertEquals(cancellation, completion.await())
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

    // ---- #540: onNewSession — route New session to startNewSession + surface not-connected -----

    @Test
    fun onNewSession_routesOwnConversationIdToRepositoryAndSurfacesNoError() =
        runTest {
            val repo = NewSessionControllableRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            val vm = makeVm(handle, repo)
            val errors = mutableListOf<Unit>()
            val errorCollector = launch { vm.newSessionErrors.collect { errors += it } }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.NewSession)
            advanceUntilIdle()

            // AC #1: exactly one startNewSession, carrying the VM's own conversation id (never a
            // caller-supplied id) with workspace defaulted null.
            assertEquals(listOf(ACTIVE_CONV to null), repo.startCalls)
            // AC #4: success surfaces nothing — the #336 fold renders the delimiter, not this slice.
            assertTrue("a successful new session must not emit an error signal: $errors", errors.isEmpty())
            errorCollector.cancel()
        }

    @Test
    fun onNewSession_whenNotConnected_emitsErrorSurfaceWithoutCrashing() =
        runTest {
            // AC #2/#3/#5: startNewSession throws not-connected (IllegalStateException). Unlike the
            // interrupt/drop swallow twins, this SURFACES — newSessionErrors emits exactly once — and the
            // throw never escapes the launched coroutine. A `startCalls.size == 1` assertion alone would
            // false-green: a leaked throw reaches the default handler, not runTest (viewModelScope is a
            // separate SupervisorJob), so also capture uncaught exceptions and assert none fired — the only
            // proof the typed catch ran.
            val uncaught = mutableListOf<Throwable>()
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
            try {
                val repo = NewSessionControllableRepo(onStart = { throw IllegalStateException("not connected") })
                val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
                val vm = makeVm(handle, repo)
                val errors = mutableListOf<Unit>()
                val errorCollector = launch { vm.newSessionErrors.collect { errors += it } }
                advanceUntilIdle()

                vm.onOverflowEvent(ThreadEvent.NewSession)
                advanceUntilIdle()

                assertEquals(listOf(ACTIVE_CONV to null), repo.startCalls) // the attempt was made
                assertEquals("not-connected must surface exactly one error signal", 1, errors.size)
                assertTrue("the not-connected throw must be caught, not propagated: $uncaught", uncaught.isEmpty())
                errorCollector.cancel()
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            }
        }

    @Test
    fun onNewSession_scopeCancellationMidSend_doesNotEmitErrorSignal() =
        runTest {
            // Structured-cancellation guard mirroring #451/#458: `catch (CancellationException) { throw e }`
            // MUST precede the typed `catch (IllegalStateException)` — on the JVM j.u.c.CancellationException
            // extends IllegalStateException. The send suspends mid-flight; viewModelScope teardown must
            // neither crash, leak, nor fire a spurious not-connected error signal.
            val gate = CompletableDeferred<Unit>() // never completes — the send stays suspended in-flight
            val entered = CompletableDeferred<Unit>()
            val repo =
                NewSessionControllableRepo(
                    onStart = {
                        entered.complete(Unit)
                        gate.await()
                    },
                )
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            val vm = makeVm(handle, repo)
            val store = ViewModelStore().apply { put("vm", vm) }
            val errors = mutableListOf<Unit>()
            val errorCollector = launch { vm.newSessionErrors.collect { errors += it } }

            vm.onOverflowEvent(ThreadEvent.NewSession)
            advanceUntilIdle()
            assertTrue("the send must be in-flight", entered.isCompleted)

            store.clear() // cancels viewModelScope → the awaiting send throws CancellationException
            advanceUntilIdle()

            // The rethrow keeps cancellation structured: no spurious error signal fires.
            assertTrue("VM-scope cancellation mid-send must not emit an error signal: $errors", errors.isEmpty())
            errorCollector.cancel()
        }

    // ---- #490: one-shot repository-call guard (launchGuardedRepoCall) --------------------------

    @Test
    fun guardedRepoCalls_whenRepositoryThrowsEachHandledType_areSwallowedWithoutCrashing() =
        runTest {
            // AC #2/#3: the one-shot repo launches that still use the shared guard (sendMessage and the
            // DeleteConfirm / RenameSubmit overflow arms) swallow the three relay
            // failure types. Archive (since #556) and change-workspace (since #561) are deliberately excluded —
            // each routes through its own surfacing path (sendArchive / sendChangeWorkspace), which does not
            // catch UnsupportedOperationException, so calling either here would let that iteration escape as an
            // uncaught throw; their swallow-and-surface behavior is covered by the #556 / #561 tests below. A
            // throw escaping the launched coroutine reaches the default handler (not runTest — viewModelScope
            // is a separate SupervisorJob), so capture uncaught throws and assert none fired: the only proof
            // the typed catch ran.
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
                    vm.onOverflowEvent(ThreadEvent.DeleteConfirm)
                    vm.onOverflowEvent(ThreadEvent.RenameSubmit("new name"))
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
    fun archive_scopeCancellationMidCall_propagatesCancellationInertWithoutSurfacing() =
        runTest {
            // AC #3: `catch (CancellationException) { throw e }` MUST precede the typed catches —
            // j.u.c.CancellationException extends ISE on the JVM (the #451 rework). Archive (#556) now runs
            // on its own surfacing path (sendArchive); an archive suspends mid-call, and viewModelScope
            // teardown must neither crash, fire the success PopBack, nor mis-surface cancellation as an
            // archive failure (AC #3 — CancellationException is never a surfaced archive error).
            val gate = CompletableDeferred<Unit>() // never completes — the archive stays suspended in-flight
            val entered = CompletableDeferred<Unit>()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            val vm = makeVm(handle, GatingArchiveRepo(gate = gate, entered = entered))
            val nav = mutableListOf<ThreadNavigation>()
            val navCollector = launch { vm.navigationEvents.collect { nav += it } }
            val errors = mutableListOf<Unit>()
            val errorCollector = launch { vm.archiveErrors.collect { errors += it } }
            val store = ViewModelStore().apply { put("vm", vm) }

            vm.onOverflowEvent(ThreadEvent.Archive)
            advanceUntilIdle()
            assertTrue("the archive must be in-flight", entered.isCompleted)

            store.clear() // cancels viewModelScope → the awaiting archive throws CancellationException
            advanceUntilIdle()
            // No crash, no leaked exception (runTest fails otherwise), the success PopBack is skipped, and
            // cancellation is never mis-surfaced as an archive failure.
            assertTrue("cancellation must skip the PopBack side effect: $nav", nav.isEmpty())
            assertTrue("cancellation must not surface an archive error: $errors", errors.isEmpty())
            navCollector.cancel()
            errorCollector.cancel()
        }

    // ---- #556: archive failure surfaces on archiveErrors (own path, not the shared guard) --------

    @Test
    fun onOverflowEvent_archive_whenNotConnected_surfacesErrorStaysOnThreadWithoutCrashing() =
        runTest {
            // AC #1/#5: archive throws not-connected (IllegalStateException, the repo `live` path). Unlike
            // the shared guard's silent swallow, this SURFACES — archiveErrors emits exactly once — the
            // thread does NOT pop (PopBack is success-only), and the throw never escapes the launched
            // coroutine. A leaked throw reaches the default handler (not runTest — viewModelScope is a
            // separate SupervisorJob), so capture uncaught throws and assert none fired: the only proof the
            // typed catch ran.
            val uncaught = mutableListOf<Throwable>()
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
            try {
                val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
                val vm = makeVm(handle, ThrowingConversationRepository(IllegalStateException("not connected")))
                val errors = mutableListOf<Unit>()
                val errorCollector = launch { vm.archiveErrors.collect { errors += it } }
                val nav = mutableListOf<ThreadNavigation>()
                val navCollector = launch { vm.navigationEvents.collect { nav += it } }
                advanceUntilIdle()

                vm.onOverflowEvent(ThreadEvent.Archive)
                advanceUntilIdle()

                assertEquals("not-connected must surface exactly one archive-error signal", 1, errors.size)
                assertTrue("a failed archive must stay on the thread (no PopBack): $nav", nav.isEmpty())
                assertTrue("the not-connected throw must be caught, not propagated: $uncaught", uncaught.isEmpty())
                errorCollector.cancel()
                navCollector.cancel()
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            }
        }

    @Test
    fun onOverflowEvent_archive_whenServerError_surfacesErrorStaysOnThreadWithoutLeakingMessage() =
        runTest {
            // AC #1/#4: archive is request/reply, so a server `error` reply surfaces as RelayErrorException —
            // reachable here (unlike fire-and-forget new_session). It must be caught and surfaced on
            // archiveErrors (one signal), the thread must not pop, and the server-supplied message must never
            // reach the surface (the Unit signal carries no text; the render slice shows the fixed local
            // string). Capture uncaught throws to prove the typed catch ran.
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
                val errors = mutableListOf<Unit>()
                val errorCollector = launch { vm.archiveErrors.collect { errors += it } }
                val nav = mutableListOf<ThreadNavigation>()
                val navCollector = launch { vm.navigationEvents.collect { nav += it } }
                advanceUntilIdle()

                vm.onOverflowEvent(ThreadEvent.Archive)
                advanceUntilIdle()

                assertEquals("a server error must surface exactly one archive-error signal", 1, errors.size)
                assertTrue("a failed archive must stay on the thread (no PopBack): $nav", nav.isEmpty())
                assertTrue("the server-error throw must be caught, not propagated: $uncaught", uncaught.isEmpty())
                errorCollector.cancel()
                navCollector.cancel()
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            }
        }

    // ---- #561: change_workspace failure surfaces on changeWorkspaceErrors (own path) ---------

    @Test
    fun changeWorkspace_whenNotConnected_surfacesErrorStaysOnThreadWithoutCrashing() =
        runTest {
            // AC #3: change_workspace throws not-connected (IllegalStateException, the repo `live` path).
            // Unlike the shared guard's silent swallow, the dedicated path SURFACES — changeWorkspaceErrors
            // emits exactly once — the user stays on the thread (change_workspace never pops), and the throw
            // never escapes the launched coroutine. A leaked throw reaches the default handler (not runTest —
            // viewModelScope is a separate SupervisorJob), so capture uncaught throws and assert none fired:
            // the only proof the typed catch ran.
            val uncaught = mutableListOf<Throwable>()
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
            try {
                val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
                val vm = makeVm(handle, ThrowingConversationRepository(IllegalStateException("not connected")))
                val errors = mutableListOf<Unit>()
                val errorCollector = launch { vm.changeWorkspaceErrors.collect { errors += it } }
                advanceUntilIdle()

                vm.onWorkspacePicked("pyry-workspace/app")
                advanceUntilIdle()

                assertEquals("not-connected must surface exactly one change-workspace-error signal", 1, errors.size)
                assertTrue("the not-connected throw must be caught, not propagated: $uncaught", uncaught.isEmpty())
                errorCollector.cancel()
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            }
        }

    @Test
    fun changeWorkspace_whenServerError_surfacesErrorStaysOnThreadWithoutLeakingMessage() =
        runTest {
            // AC #2: change_workspace is request/reply, so a server `error` reply surfaces as
            // RelayErrorException — reachable here (unlike fire-and-forget new_session). It must be caught and
            // surfaced on changeWorkspaceErrors (one signal), and the server-supplied message must never reach
            // the surface (the Unit signal carries no text; the render slice shows the fixed local string).
            // Capture uncaught throws to prove the typed catch ran.
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
                val errors = mutableListOf<Unit>()
                val errorCollector = launch { vm.changeWorkspaceErrors.collect { errors += it } }
                advanceUntilIdle()

                vm.onWorkspacePicked("pyry-workspace/app")
                advanceUntilIdle()

                assertEquals("a server error must surface exactly one change-workspace-error signal", 1, errors.size)
                assertTrue("the server-error throw must be caught, not propagated: $uncaught", uncaught.isEmpty())
                errorCollector.cancel()
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            }
        }

    @Test
    fun changeWorkspace_scopeCancellationMidCall_propagatesCancellationInertWithoutSurfacing() =
        runTest {
            // AC #4: `catch (CancellationException) { throw e }` MUST precede the typed catches —
            // j.u.c.CancellationException extends ISE on the JVM (the #451 rework). A change_workspace suspends
            // mid-call; viewModelScope teardown must neither crash nor mis-surface cancellation as a
            // change-workspace failure (CancellationException is never a surfaced error). change_workspace
            // never pops, so there is no PopBack side effect to check (the divergence from the #556 archive
            // twin).
            val gate = CompletableDeferred<Session>() // never completes — the call stays suspended in-flight
            val entered = CompletableDeferred<Unit>()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            val vm = makeVm(handle, GatingWorkspaceRepo(gate = gate, entered = entered))
            val errors = mutableListOf<Unit>()
            val errorCollector = launch { vm.changeWorkspaceErrors.collect { errors += it } }
            val store = ViewModelStore().apply { put("vm", vm) }

            vm.onWorkspacePicked("pyry-workspace/app")
            advanceUntilIdle()
            assertTrue("the change_workspace must be in-flight", entered.isCompleted)

            store.clear() // cancels viewModelScope → the awaiting call throws CancellationException
            advanceUntilIdle()
            // No crash, no leaked exception (runTest fails otherwise), and cancellation is never mis-surfaced
            // as a change-workspace failure.
            assertTrue("cancellation must not surface a change-workspace error: $errors", errors.isEmpty())
            errorCollector.cancel()
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

    // ---- #597: isCompacting projection over repository.observeCompacting ----------------------

    @Test
    fun isCompacting_initialValue_isFalseWithNonCompactingRepo() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            // A plain fake inherits observeCompacting's flowOf(false) default — the flag stays inert, so a
            // conversation that never receives a `compacting` frame renders exactly as it does today (AC #3).
            val vm = makeVm(handle, FakeConversationRepository())
            assertFalse(vm.isCompacting.value)
        }

    @Test
    fun isCompacting_reflectsOnsetThenClear() =
        runTest {
            val repo = CompactingControllableRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.isCompacting.collect {} }
            advanceUntilIdle()

            repo.compacting.value = true
            advanceUntilIdle()
            assertTrue(vm.isCompacting.value) // AC #1 — onset shows the compacting status.

            repo.compacting.value = false
            advanceUntilIdle()
            assertFalse(vm.isCompacting.value) // AC #2 — clears on the falling edge; nothing sticks.
            collector.cancel()
        }

    @Test
    fun isCompacting_observesOnlyOwnConversationId() =
        runTest {
            val repo = CompactingControllableRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.isCompacting.collect {} }
            advanceUntilIdle()

            assertTrue(repo.observedIds.isNotEmpty())
            assertTrue(repo.observedIds.all { it == ACTIVE_CONV })
            collector.cancel()
        }

    // ---- #872: resetting projection over repository.observeResetting ----------------------------

    @Test
    fun resetting_initialValue_isNullWithPlainFake() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            // A plain fake inherits observeResetting's flowOf(null) default — no reset, no arm.
            val vm = makeVm(handle, FakeConversationRepository())
            assertNull(vm.resetting.value)
        }

    @Test
    fun resetting_reflectsPhaseChangeThenClear() =
        runTest {
            val repo = ResettingControllableRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.resetting.collect {} }
            advanceUntilIdle()

            // Each push is drained on its own, so the phase change is observed rather than conflated.
            val wrappingUp = ResetStatus(ResetStatus.Phase.WrappingUp, ResetStatus.Handoff.Pending)
            repo.resetting.value = wrappingUp
            advanceUntilIdle()
            assertEquals(wrappingUp, vm.resetting.value)

            val restarting = ResetStatus(ResetStatus.Phase.Restarting, ResetStatus.Handoff.Written)
            repo.resetting.value = restarting
            advanceUntilIdle()
            assertEquals(restarting, vm.resetting.value) // the phase change replaces the reading

            repo.resetting.value = null
            advanceUntilIdle()
            assertNull(vm.resetting.value) // the falling edge clears it; nothing sticks
            collector.cancel()
        }

    @Test
    fun resetting_observesOnlyOwnConversationId() =
        runTest {
            val repo = ResettingControllableRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.resetting.collect {} }
            advanceUntilIdle()

            assertTrue(repo.observedIds.isNotEmpty())
            assertTrue(repo.observedIds.all { it == ACTIVE_CONV })
            collector.cancel()
        }

    // ---- #803: thinkingProgress projection over repository.observeThinkingProgress ---------------

    @Test
    fun thinkingProgress_initialValue_isNullWithPlainFake() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            // A plain fake inherits observeThinkingProgress's flowOf(null) default — no reading, so the
            // thinking arm renders exactly as it does today (AC #1, second half).
            val vm = makeVm(handle, FakeConversationRepository())
            assertNull(vm.thinkingProgress.value)
        }

    @Test
    fun thinkingProgress_carriesAFallingReadingAndHoldsARepeatedOne() =
        runTest {
            val repo = ThinkingProgressControllableRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.thinkingProgress.collect {} }
            advanceUntilIdle()

            // Each push is drained on its own: a StateFlow projection conflates two pushes made within
            // one turn, so a sequence asserted without this interleave proves something weaker than it
            // reads (the measured trap in docs/knowledge/features/thinking-progress-state.md).
            repo.reading.value = ThinkingProgress(estimatedTokens = 184, estimatedTokensDelta = 64)
            advanceUntilIdle()
            assertEquals(184L, vm.thinkingProgress.value?.estimatedTokens)

            // The reading restarts near zero at every inference-request boundary — repeatedly inside one
            // turn — so a fall is a real reading and must never be clamped by a running maximum (AC #2).
            repo.reading.value = ThinkingProgress(estimatedTokens = 4, estimatedTokensDelta = 4)
            advanceUntilIdle()
            assertEquals(4L, vm.thinkingProgress.value?.estimatedTokens)

            // An identical repeat is dropped upstream by distinctUntilChanged, so the value simply holds
            // — which is what makes the rendered label hold without flicker (AC #2).
            repo.reading.value = ThinkingProgress(estimatedTokens = 4, estimatedTokensDelta = 4)
            advanceUntilIdle()
            assertEquals(4L, vm.thinkingProgress.value?.estimatedTokens)
            collector.cancel()
        }

    @Test
    fun thinkingProgress_observesOnlyOwnConversationId() =
        runTest {
            val repo = ThinkingProgressControllableRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.thinkingProgress.collect {} }
            advanceUntilIdle()

            assertTrue(repo.observedIds.isNotEmpty())
            assertTrue(repo.observedIds.all { it == ACTIVE_CONV })
            collector.cancel()
        }

    // ---- #804: usageLimit projection over repository.observeUsageLimit ---------------------------
    // The re-read ticker is perpetual while subscribed, so these cases step with runCurrent/advanceTimeBy
    // and never advanceUntilIdle, which would chase the ticker forever.

    @Test
    fun usageLimit_initialValue_isNullWithPlainFake() =
        runTest {
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            // A plain fake inherits observeUsageLimit's flowOf(null) default — nothing to render.
            val vm = makeVm(handle, FakeConversationRepository())
            assertNull(vm.usageLimit.value)
        }

    @Test
    fun usageLimit_reflectsReadingThenClear() =
        runTest {
            val repo = UsageLimitControllableRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.usageLimit.collect {} }
            runCurrent()

            repo.reading.value = WARNING_READING
            runCurrent()
            assertEquals(WARNING_READING, vm.usageLimit.value)

            repo.reading.value = null // the benign frame's removal
            runCurrent()
            assertNull(vm.usageLimit.value)
            collector.cancel()
        }

    @Test
    fun usageLimit_observesOnlyOwnConversationId() =
        runTest {
            val repo = UsageLimitControllableRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.usageLimit.collect {} }
            runCurrent()

            assertTrue(repo.observedIds.isNotEmpty())
            assertTrue(repo.observedIds.all { it == ACTIVE_CONV })
            collector.cancel()
        }

    // The projection applies expiry only when read and emits nothing at the deadline; the VM's fixed
    // re-read cadence is what takes a displayed reading down without re-deriving the rule.
    @Test
    fun usageLimit_expiredReading_leavesOnTheNextReRead() =
        runTest {
            val repo = UsageLimitControllableRepo()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.usageLimit.collect {} }
            repo.reading.value = WARNING_READING
            runCurrent()
            assertEquals(WARNING_READING, vm.usageLimit.value)

            repo.expired = true
            runCurrent()
            assertEquals(WARNING_READING, vm.usageLimit.value) // no upstream emission at the deadline

            advanceTimeBy(USAGE_LIMIT_REREAD_MS)
            runCurrent()
            assertNull(vm.usageLimit.value)
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

    // ---- #807: model and effort sourced from the daemon, never from AppPreferences ---------------
    //
    // #544's six device-default cases (selectedModel/selectedEffort follow and re-emit on
    // AppPreferences.defaultModel/defaultEffort, and an override outlives a later default change) are
    // deleted rather than rewritten: they asserted the sourcing this ticket retires. The ViewModel no
    // longer takes an AppPreferences at all.

    @Test
    fun state_initialValue_isUnknownRunConfigNotADeviceDefault() =
        runTest {
            val vm = makeVm(runConfigHandle(), FakeConversationRepository())
            // No collect{} — the stateIn(WhileSubscribed) initial value is the data-class defaults.
            val config = vm.state.value.runConfig
            assertEquals(UNKNOWN_RUN_CONFIG_LABEL, config.modelLabel)
            assertEquals(UNKNOWN_RUN_CONFIG_LABEL, config.effortLabel)
            assertFalse(config.settingsAvailable)
            assertFalse(config.menuAvailable)
            assertEquals("", config.sessionId)
        }

    @Test
    fun runConfig_withoutAnyReading_rendersUnknownAndOffersNothing() =
        runTest {
            // An unseeded Fake reads null for both, the same "unavailable" a live repository reports before
            // its first reply. Unknown is the whole point: never Model.OPUS_4_7, never Effort.HIGH.
            val vm = makeVm(runConfigHandle(), FakeConversationRepository())
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            val config = vm.state.value.runConfig
            assertEquals(UNKNOWN_RUN_CONFIG_LABEL, config.modelLabel)
            assertEquals(UNKNOWN_RUN_CONFIG_LABEL, config.effortLabel)
            assertEquals(emptyList<ThreadModelChoice>(), config.choices)
            assertEquals(emptyList<ThreadEffortChoice>(), config.effortChoices)
            assertFalse("an unaddressable session is read-only", config.writable)
            collector.cancel()
        }

    @Test
    fun runConfig_labelsComeFromTheReadingAndThePublishedRow() =
        runTest {
            val repo = FakeConversationRepository()
            repo.setModelMenu(RUN_CONFIG_CONV, menu(row("opus", "Opus 4.7"), row("sonnet", "Sonnet 4.6")))
            repo.setSessionSettingsReading(RUN_CONFIG_CONV, settings(model = "sonnet", effort = "high"))
            val vm = makeVm(runConfigHandle(), repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            val config = vm.state.value.runConfig
            // The label is the row's displayName; the identity is the row's value.
            assertEquals("Sonnet", config.modelLabel)
            assertEquals("sonnet", config.selectedModel)
            assertEquals("high", config.effortLabel)
            collector.cancel()
        }

    @Test
    fun runConfig_readingWithNoOverride_readsAsInheritedDefaultNotUnknown() =
        runTest {
            // "" is a real reported value — "no override, inherited default" — and is not the same reading
            // as an absent one, which is why settingsAvailable carries that distinction separately.
            val repo = FakeConversationRepository()
            repo.setSessionSettingsReading(RUN_CONFIG_CONV, settings(model = "", effort = ""))
            val vm = makeVm(runConfigHandle(), repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            val config = vm.state.value.runConfig
            assertTrue(config.settingsAvailable)
            assertEquals(UNAVAILABLE_MODEL_LABEL, config.modelLabel)
            // #889: an empty saved effort with no applied reading names the control, never "default".
            assertEquals(EFFORT_PLACEHOLDER_LABEL, config.effortLabel)
            collector.cancel()
        }

    @Test
    fun runConfig_savedValueTheMenuDidNotPublish_rendersTheValueItself() =
        runTest {
            val repo = FakeConversationRepository()
            repo.setModelMenu(RUN_CONFIG_CONV, menu(row("opus", "Opus 4.7")))
            repo.setSessionSettingsReading(RUN_CONFIG_CONV, settings(model = "opus[1m]"))
            val vm = makeVm(runConfigHandle(), repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            // No row names it, so the reported value stands in — never a device enum and never another row.
            assertEquals("opus[1m]", vm.state.value.runConfig.modelLabel)
            assertEquals(null, vm.state.value.runConfig.selectedChoice)
            collector.cancel()
        }

    @Test
    fun runConfig_choicesAreThePublishedRowsInWireOrder() =
        runTest {
            val repo = FakeConversationRepository()
            repo.setModelMenu(
                RUN_CONFIG_CONV,
                menu(row("haiku", "Haiku 4.5"), row("opus", "Opus 4.7"), row("sonnet", "Sonnet 4.6")),
            )
            repo.setSessionSettingsReading(RUN_CONFIG_CONV, settings())
            val vm = makeVm(runConfigHandle(), repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            val config = vm.state.value.runConfig
            assertTrue(config.menuAvailable)
            assertEquals(listOf("haiku", "opus", "sonnet"), config.choices.map { it.value })
            assertEquals(listOf("Haiku", "Opus", "Sonnet"), config.choices.map { it.label })
            collector.cancel()
        }

    @Test
    fun runConfig_effortLevelsComeFromTheSelectedRow() =
        runTest {
            val repo = FakeConversationRepository()
            repo.setModelMenu(
                RUN_CONFIG_CONV,
                menu(
                    row("opus", "Opus 4.7", effortLevels = listOf("low", "medium", "high")),
                    row("haiku", "Haiku 4.5", effortLevels = emptyList()),
                ),
            )
            repo.setSessionSettingsReading(RUN_CONFIG_CONV, settings(model = "opus"))
            val vm = makeVm(runConfigHandle(), repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals(
                listOf("low", "medium", "high"),
                vm.state.value.runConfig.effortChoices
                    .map { it.value },
            )

            // A row publishing no levels offers no effort choice — never the five Effort entries.
            repo.setSessionSettingsReading(RUN_CONFIG_CONV, settings(model = "haiku"))
            advanceUntilIdle()
            assertEquals(emptyList<ThreadEffortChoice>(), vm.state.value.runConfig.effortChoices)
            collector.cancel()
        }

    @Test
    fun runConfig_unsetSavedEffortStillOffersTheRowsLevels() =
        runTest {
            val repo = FakeConversationRepository()
            repo.setModelMenu(RUN_CONFIG_CONV, menu(row("opus", "Opus 4.7", effortLevels = listOf("low", "max"))))
            repo.setSessionSettingsReading(RUN_CONFIG_CONV, settings(model = "opus", effort = ""))
            val vm = makeVm(runConfigHandle(), repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            val config = vm.state.value.runConfig
            assertEquals("", config.selectedEffort) // nothing is selected...
            assertEquals(listOf("low", "max"), config.effortChoices.map { it.value }) // ...but both are offered
            collector.cancel()
        }

    @Test
    fun runConfig_droppedModelsIsCarriedAsReportedAndNeverRecomputed() =
        runTest {
            val repo = FakeConversationRepository()
            // A non-zero count beside a short row list is legal: the producer's cap is daemon-side.
            repo.setModelMenu(RUN_CONFIG_CONV, menu(row("opus", "Opus 4.7"), droppedModels = 44))
            repo.setSessionSettingsReading(RUN_CONFIG_CONV, settings())
            val vm = makeVm(runConfigHandle(), repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(44, vm.state.value.runConfig.droppedModels)
            assertEquals(0, vm.state.value.runConfig.hiddenChoices)
            collector.cancel()
        }

    @Test
    fun runConfig_oversizedMenuIsCappedIntoHiddenChoicesLeavingDroppedModelsUntouched() =
        runTest {
            // A buggy or hostile daemon can publish more rows than the sheet's non-lazy Column should lay
            // out. The client's own cut is reported separately so neither number is mistaken for the other.
            val repo = FakeConversationRepository()
            val rows = (1..40).map { row("m$it", "Model $it") }
            repo.setModelMenu(RUN_CONFIG_CONV, ModelMenu(rows = rows, droppedModels = 7))
            repo.setSessionSettingsReading(RUN_CONFIG_CONV, settings())
            val vm = makeVm(runConfigHandle(), repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            val config = vm.state.value.runConfig
            assertEquals(32, config.choices.size)
            assertEquals(8, config.hiddenChoices)
            assertEquals("the producer's own count is untouched", 7, config.droppedModels)
            collector.cancel()
        }

    @Test
    fun runConfig_claudeAuthoredTextIsRenderedInertWhileTheWriteArgumentStaysVerbatim() =
        runTest {
            // ModelMenuRow's strings cross the subprocess trust boundary unsanitized: the daemon bounds
            // them but strips no control character and no terminal escape, so this client owes both.
            val repo = FakeConversationRepository()
            val hostileLabel = "Op[31mus\nx4.7" + "y".repeat(400)
            val hostileLevel = "high"
            repo.setModelMenu(
                RUN_CONFIG_CONV,
                menu(
                    row(
                        "opus",
                        displayName = hostileLabel,
                        resolvedModel = "resolved",
                        effortLevels = listOf(hostileLevel),
                    ),
                ),
            )
            repo.setSessionSettingsReading(RUN_CONFIG_CONV, settings(model = "opus"))
            val vm = makeVm(runConfigHandle(), repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            val choice =
                vm.state.value.runConfig.choices
                    .single()
            assertFalse("no control character survives into the label", choice.label.any { it.isISOControl() })
            assertFalse("nor into the detail line", choice.detail.any { it.isISOControl() })
            assertFalse(
                "nor into an effort label",
                choice.effortChoices
                    .single()
                    .label
                    .any { it.isISOControl() },
            )
            assertTrue("the label is length-bounded", choice.label.length <= 128)
            // The write argument is the daemon's own string and is forwarded byte-identical.
            assertEquals("opus", choice.value)
            assertEquals(hostileLevel, choice.effortChoices.single().value)
            collector.cancel()
        }

    @Test
    fun runConfig_savedValueWithControlCharacters_isRenderedInert() =
        runTest {
            // The footer's fallback path renders SessionSettings.model itself when no row names it — the
            // same daemon-authored text, owed the same treatment.
            val repo = FakeConversationRepository()
            repo.setSessionSettingsReading(RUN_CONFIG_CONV, settings(model = "opus", effort = "hi\ngh"))
            val vm = makeVm(runConfigHandle(), repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            val config = vm.state.value.runConfig
            assertFalse(config.modelLabel.any { it.isISOControl() })
            assertFalse(config.effortLabel.any { it.isISOControl() })
            collector.cancel()
        }

    @Test
    fun runConfig_isScopedToItsOwnConversation() =
        runTest {
            // AC #4: opening another conversation starts with no carried-over selection, menu or session id.
            val repo = FakeConversationRepository()
            repo.setModelMenu(RUN_CONFIG_CONV, menu(row("opus", "Opus 4.7")))
            repo.setSessionSettingsReading(RUN_CONFIG_CONV, settings(model = "opus", effort = "high"))

            val mine = makeVm(runConfigHandle(), repo)
            val other = makeVm(SavedStateHandle(initialState = mapOf("conversationId" to "seed-discussion-a")), repo)
            val collectors = listOf(launch { mine.state.collect {} }, launch { other.state.collect {} })
            advanceUntilIdle()

            assertEquals("Opus", mine.state.value.runConfig.modelLabel)
            val neighbour = other.state.value.runConfig
            assertEquals(UNKNOWN_RUN_CONFIG_LABEL, neighbour.modelLabel)
            assertEquals(null, neighbour.selectedChoice)
            assertEquals(emptyList<ThreadModelChoice>(), neighbour.choices)
            assertEquals("", neighbour.sessionId)
            repo.setModelMenu("seed-discussion-a", menu(row("haiku", "Haiku 4.5")))
            repo.setSessionSettingsReading("seed-discussion-a", settings(model = "haiku"))
            advanceUntilIdle()
            assertEquals(
                "haiku",
                other.state.value.runConfig.selectedChoice
                    ?.value,
            )
            assertEquals(
                "opus",
                mine.state.value.runConfig.selectedChoice
                    ?.value,
            )
            collectors.forEach { it.cancel() }
        }

    // ---- #544/#807: send on change, addressed to the settings reading's session; revert on failure ---

    @Test
    fun onModelSelected_whenConnected_sendsOnlyModelFieldToTheSettingsReportedSession() =
        runTest {
            val repo = FakeConversationRepository()
            repo.setModelMenu(RUN_CONFIG_CONV, menu(row("opus", "Opus 4.7"), row("haiku", "Haiku 4.5")))
            repo.setSessionSettingsReading(RUN_CONFIG_CONV, settings(sessionId = "settings-s9", model = "opus"))
            val vm = makeVm(runConfigHandle(), repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals("opus", vm.state.value.runConfig.selectedModel)

            vm.onModelSelected("haiku")
            advanceUntilIdle()

            val call = repo.setSessionSettingsCalls.single()
            // The daemon's own argument, forwarded verbatim — no enum mapping in between.
            assertEquals("haiku", call.model)
            assertNull(call.effort)
            assertNull(call.yolo)
            // #807: the settings reading's session id, NOT the conversation's ("seed-session-personal").
            assertEquals("settings-s9", call.sessionId)
            collector.cancel()
        }

    @Test
    fun onModelSelected_acknowledgedChoiceRemembersExactPublishedValue() =
        runTest {
            val remembered = mutableListOf<String>()
            val repo = FakeConversationRepository()
            val published = "claude-published-model[1m]"
            repo.setModelMenu(RUN_CONFIG_CONV, menu(row("opus", "Opus"), row(published, "Published")))
            repo.setSessionSettingsReading(RUN_CONFIG_CONV, settings(model = "opus"))
            val vm = makeVm(runConfigHandle(), repo, rememberModel = { remembered += it })
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertTrue(remembered.isEmpty())

            vm.onModelSelected(published)
            advanceUntilIdle()

            assertEquals(listOf(published), remembered)
            assertEquals(published, repo.setSessionSettingsCalls.single().model)
            collector.cancel()
        }

    @Test
    fun onModelSelected_passiveReadingAndNoChangeLeaveRememberedChoiceIntact() =
        runTest {
            val remembered = mutableListOf<String>()
            val repo = FakeConversationRepository()
            repo.setModelMenu(RUN_CONFIG_CONV, menu(row("opus", "Opus"), row("haiku", "Haiku")))
            repo.setSessionSettingsReading(RUN_CONFIG_CONV, settings(model = "opus"))
            val vm = makeVm(runConfigHandle(), repo, rememberModel = { remembered += it })
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            repo.setSessionSettingsReading(RUN_CONFIG_CONV, settings(model = "haiku"))
            advanceUntilIdle()
            vm.onModelSelected("haiku")
            advanceUntilIdle()

            assertTrue(remembered.isEmpty())
            assertTrue(repo.setSessionSettingsCalls.isEmpty())
            collector.cancel()
        }

    @Test
    fun onModelSelected_rejectedChoiceDoesNotReplaceRememberedChoice() =
        runTest {
            var remembered = "previous-model"
            val backing = FakeConversationRepository()
            backing.setModelMenu(RUN_CONFIG_CONV, menu(row("opus", "Opus"), row("haiku", "Haiku")))
            backing.setSessionSettingsReading(RUN_CONFIG_CONV, settings(model = "opus"))
            val repo =
                object : ConversationRepository by backing {
                    override suspend fun setSessionSettings(
                        sessionId: String,
                        model: String?,
                        effort: String?,
                        yolo: Boolean?,
                        permissionMode: String?,
                    ): Unit = throw RelayErrorException(code = "session.not_found", retryable = false, message = "secret")
                }
            val vm = makeVm(runConfigHandle(), repo, rememberModel = { remembered = it })
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onModelSelected("haiku")
            advanceUntilIdle()

            assertEquals("previous-model", remembered)
            collector.cancel()
        }

    @Test
    fun onModelSelected_connectionFailureDoesNotReplaceRememberedChoice() =
        runTest {
            var remembered = "previous-model"
            val backing = FakeConversationRepository()
            backing.setModelMenu(RUN_CONFIG_CONV, menu(row("opus", "Opus"), row("haiku", "Haiku")))
            backing.setSessionSettingsReading(RUN_CONFIG_CONV, settings(model = "opus"))
            val repo =
                object : ConversationRepository by backing {
                    override suspend fun setSessionSettings(
                        sessionId: String,
                        model: String?,
                        effort: String?,
                        yolo: Boolean?,
                        permissionMode: String?,
                    ): Unit = throw IllegalStateException("not connected")
                }
            val vm = makeVm(runConfigHandle(), repo, rememberModel = { remembered = it })
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onModelSelected("haiku")
            advanceUntilIdle()

            assertEquals("previous-model", remembered)
            assertEquals("opus", vm.state.value.runConfig.selectedModel)
            collector.cancel()
        }

    @Test
    fun onEffortSelected_acknowledgedEffortDoesNotReplaceRememberedModel() =
        runTest {
            var remembered = "previous-model"
            val repo = FakeConversationRepository()
            repo.setModelMenu(RUN_CONFIG_CONV, menu(row("opus", "Opus", effortLevels = listOf("low", "max"))))
            repo.setSessionSettingsReading(RUN_CONFIG_CONV, settings(model = "opus", effort = "low"))
            val vm = makeVm(runConfigHandle(), repo, rememberModel = { remembered = it })
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onEffortSelected("max")
            advanceUntilIdle()

            assertEquals("previous-model", remembered)
            val call = repo.setSessionSettingsCalls.single()
            assertEquals("max", call.effort)
            assertNull(call.model)
            collector.cancel()
        }

    @Test
    fun onModelSelected_cancelledBeforeAcknowledgementDoesNotReplaceRememberedChoice() =
        runTest {
            var remembered = "previous-model"
            val entered = CompletableDeferred<Unit>()
            val gate = CompletableDeferred<Unit>()
            val backing = FakeConversationRepository()
            backing.setModelMenu(RUN_CONFIG_CONV, menu(row("opus", "Opus"), row("haiku", "Haiku")))
            backing.setSessionSettingsReading(RUN_CONFIG_CONV, settings(model = "opus"))
            val repo =
                object : ConversationRepository by backing {
                    override suspend fun setSessionSettings(
                        sessionId: String,
                        model: String?,
                        effort: String?,
                        yolo: Boolean?,
                        permissionMode: String?,
                    ) {
                        entered.complete(Unit)
                        gate.await()
                    }
                }
            val vm = makeVm(runConfigHandle(), repo, rememberModel = { remembered = it })
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            val owner = ViewModelStore().apply { put("vm", vm) }

            vm.onModelSelected("haiku")
            advanceUntilIdle()
            assertTrue(entered.isCompleted)
            owner.clear()
            advanceUntilIdle()

            assertEquals("previous-model", remembered)
            collector.cancel()
        }

    @Test
    fun onEffortSelected_whenConnected_sendsOnlyEffortFieldVerbatim() =
        runTest {
            val repo = FakeConversationRepository()
            repo.setModelMenu(RUN_CONFIG_CONV, menu(row("opus", "Opus 4.7", effortLevels = listOf("low", "max"))))
            repo.setSessionSettingsReading(
                RUN_CONFIG_CONV,
                settings(sessionId = "settings-s9", model = "opus", effort = "low"),
            )
            val vm = makeVm(runConfigHandle(), repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onEffortSelected("max")
            advanceUntilIdle()

            val call = repo.setSessionSettingsCalls.single()
            assertEquals("max", call.effort)
            assertNull(call.model)
            assertNull(call.yolo)
            assertEquals("settings-s9", call.sessionId)
            collector.cancel()
        }

    @Test
    fun onModelSelected_sameAsCurrentValue_doesNotSend() =
        runTest {
            val repo = FakeConversationRepository()
            repo.setModelMenu(RUN_CONFIG_CONV, menu(row("opus", "Opus 4.7")))
            repo.setSessionSettingsReading(RUN_CONFIG_CONV, settings(model = "opus"))
            val vm = makeVm(runConfigHandle(), repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals("opus", vm.state.value.runConfig.selectedModel)

            // Re-selecting the already-displayed value is a no-op — a radio onClick fires even when selected.
            vm.onModelSelected("opus")
            advanceUntilIdle()

            assertTrue("re-selecting the current value must not send", repo.setSessionSettingsCalls.isEmpty())
            collector.cancel()
        }

    @Test
    fun onModelSelected_withEmptySessionId_isReadOnlyAndSendsNothing() =
        runTest {
            // AC #3: "" means the daemon has no session to address, which the daemon itself rejects. The
            // tap is dropped rather than failed — a read-only session is not an error to surface.
            val repo = FakeConversationRepository()
            repo.setModelMenu(RUN_CONFIG_CONV, menu(row("opus", "Opus 4.7"), row("haiku", "Haiku 4.5")))
            repo.setSessionSettingsReading(RUN_CONFIG_CONV, settings(sessionId = "", model = "opus"))
            val vm = makeVm(runConfigHandle(), repo)
            val errors = mutableListOf<Unit>()
            val errorCollector = launch { vm.sessionSettingsErrors.collect { errors += it } }
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertFalse(vm.state.value.runConfig.writable)

            vm.onModelSelected("haiku")
            vm.onEffortSelected("high")
            advanceUntilIdle()

            assertTrue("nothing is sent to an unaddressable session", repo.setSessionSettingsCalls.isEmpty())
            assertTrue("and a read-only session surfaces no failure", errors.isEmpty())
            assertFalse("the control does not move either", vm.state.value.runConfig.pending)
            errorCollector.cancel()
            collector.cancel()
        }

    @Test
    fun onModelSelected_settledWrite_staysPendingUntilAFreshReadingLands() =
        runTest {
            // AC #3: the ack echoes only the input session id, so a settled write asks for a fresh reading
            // and the pending survives until that reading arrives. An acknowledgement is never the
            // confirmed reading.
            val repo = FakeConversationRepository()
            repo.setModelMenu(RUN_CONFIG_CONV, menu(row("opus", "Opus 4.7"), row("haiku", "Haiku 4.5")))
            repo.setSessionSettingsReading(RUN_CONFIG_CONV, settings(sessionId = "settings-s9", model = "opus"))
            val vm = makeVm(runConfigHandle(), repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onModelSelected("haiku")
            advanceUntilIdle()

            // Acked — but not confirmed: the tap still reads as pending, visibly distinct from settled.
            assertTrue("the ack alone does not confirm", vm.state.value.runConfig.pending)
            assertEquals("haiku", vm.state.value.runConfig.selectedModel)
            assertEquals("opus", vm.state.value.runConfig.savedModel)
            assertEquals("a settled write asks for a fresh reading", listOf(RUN_CONFIG_CONV), repo.sessionSettingsRefreshes)

            repo.setSessionSettingsReading(RUN_CONFIG_CONV, settings(sessionId = "settings-s9", model = "opus", effort = "low"))
            advanceUntilIdle()
            assertTrue("a stale reading cannot confirm the new model", vm.state.value.runConfig.pending)

            // The fresh reading lands; the display updates with no further user turn.
            repo.setSessionSettingsReading(RUN_CONFIG_CONV, settings(sessionId = "settings-s9", model = "haiku"))
            advanceUntilIdle()
            assertFalse(vm.state.value.runConfig.pending)
            assertEquals("haiku", vm.state.value.runConfig.savedModel)
            assertEquals("Haiku", vm.state.value.runConfig.modelLabel)
            collector.cancel()
        }

    @Test
    fun onModelSelected_whileAWriteIsPending_doesNotSendASecondTime() =
        runTest {
            val repo = FakeConversationRepository()
            repo.setModelMenu(
                RUN_CONFIG_CONV,
                menu(row("opus", "Opus 4.7"), row("haiku", "Haiku 4.5"), row("sonnet", "Sonnet 4.6")),
            )
            repo.setSessionSettingsReading(RUN_CONFIG_CONV, settings(sessionId = "settings-s9", model = "opus"))
            val vm = makeVm(runConfigHandle(), repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onModelSelected("haiku")
            advanceUntilIdle()
            vm.onModelSelected("sonnet")
            advanceUntilIdle()

            assertEquals("two writes for one control must never be outstanding", 1, repo.setSessionSettingsCalls.size)
            assertEquals("haiku", vm.state.value.runConfig.selectedModel)
            collector.cancel()
        }

    @Test
    fun onModelSelected_whenServerError_restoresConfirmedStateAndSurfacesErrorWithoutLeakingMessage() =
        runTest {
            // AC #3: set_session_settings is request/reply, so a server `error` surfaces as
            // RelayErrorException. It must be caught, the previously confirmed reading restored, and
            // exactly one payload-free signal surfaced — the server-supplied message never reaches the
            // surface. Capture uncaught throws to prove the typed catch ran (viewModelScope is a separate
            // SupervisorJob, not runTest's scope, so a leaked throw hits the default handler).
            val uncaught = mutableListOf<Throwable>()
            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
            try {
                val backing = FakeConversationRepository()
                backing.setModelMenu(RUN_CONFIG_CONV, menu(row("opus", "Opus 4.7"), row("haiku", "Haiku 4.5")))
                backing.setSessionSettingsReading(RUN_CONFIG_CONV, settings(sessionId = "settings-s9", model = "opus"))
                val repo =
                    object : ConversationRepository by backing {
                        override suspend fun setSessionSettings(
                            sessionId: String,
                            model: String?,
                            effort: String?,
                            yolo: Boolean?,
                            permissionMode: String?,
                        ): Unit = throw RelayErrorException(code = "protocol.malformed", retryable = false, message = "secret")
                    }
                val vm = makeVm(runConfigHandle(), repo)
                val errors = mutableListOf<Unit>()
                val errorCollector = launch { vm.sessionSettingsErrors.collect { errors += it } }
                val stateCollector = launch { vm.state.collect {} }
                advanceUntilIdle()
                assertEquals("opus", vm.state.value.runConfig.selectedModel)

                vm.onModelSelected("haiku")
                advanceUntilIdle()

                assertEquals("the control returns to the confirmed reading", "opus", vm.state.value.runConfig.selectedModel)
                assertEquals(
                    "opus",
                    vm.state.value.runConfig.selectedChoice
                        ?.value,
                )
                assertEquals("Opus", vm.state.value.runConfig.modelLabel)
                assertFalse("and is no longer pending", vm.state.value.runConfig.pending)
                assertEquals("a server error surfaces exactly one signal", 1, errors.size)
                assertTrue("the server-error throw must be caught, not propagated: $uncaught", uncaught.isEmpty())
                errorCollector.cancel()
                stateCollector.cancel()
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            }
        }

    @Test
    fun sessionSettings_scopeCancellationMidCall_isInertWithoutSurfacing() =
        runTest {
            // AC #4 correctness: `catch (CancellationException) { throw e }` MUST precede the typed catches
            // (j.u.c.CancellationException extends ISE on the JVM — the #451 rework). A send suspends
            // mid-call; viewModelScope teardown must neither crash nor mis-surface cancellation as a settings
            // failure (no revert, no signal). A bare `catch (IllegalStateException)` would false-fire here.
            val gate = CompletableDeferred<Unit>() // never completes — the send stays suspended in-flight
            val entered = CompletableDeferred<Unit>()
            val backing = FakeConversationRepository()
            backing.setModelMenu(RUN_CONFIG_CONV, menu(row("opus", "Opus 4.7"), row("haiku", "Haiku 4.5")))
            backing.setSessionSettingsReading(RUN_CONFIG_CONV, settings(sessionId = "settings-s9", model = "opus"))
            val repo =
                object : ConversationRepository by backing {
                    override suspend fun setSessionSettings(
                        sessionId: String,
                        model: String?,
                        effort: String?,
                        yolo: Boolean?,
                        permissionMode: String?,
                    ) {
                        entered.complete(Unit)
                        gate.await()
                    }
                }
            val vm = makeVm(runConfigHandle(), repo)
            val errors = mutableListOf<Unit>()
            val errorCollector = launch { vm.sessionSettingsErrors.collect { errors += it } }
            val stateCollector = launch { vm.state.collect {} }
            advanceUntilIdle()
            val store = ViewModelStore().apply { put("vm", vm) }

            vm.onModelSelected("haiku")
            advanceUntilIdle()
            assertTrue("the send must be in-flight", entered.isCompleted)

            store.clear() // cancels viewModelScope → the awaiting send throws CancellationException
            advanceUntilIdle()
            assertTrue("cancellation must not surface a settings error: $errors", errors.isEmpty())
            errorCollector.cancel()
            stateCollector.cancel()
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

    // ---- #789: per-chat composer drafts ---------------------------------------------------------

    @Test
    fun onDraftChange_writesThisChatsPairAndNoOther() =
        runTest {
            val store = ComposerDraftStore()
            val vm = makeVm(threadHandle("pyrybox", DRAFT_CONV), FakeConversationRepository(), draftStore = store)
            advanceUntilIdle()

            vm.onDraftChange("  half a thought\n")

            // Exact text, and under this VM's own pair only.
            assertEquals("  half a thought\n", store.draftFor("pyrybox", DRAFT_CONV))
            assertEquals(mapOf("pyrybox" to mapOf(DRAFT_CONV to "  half a thought\n")), store.drafts.value)
            assertEquals("  half a thought\n", vm.draft.value)
        }

    @Test
    fun draft_seedsFromTheStore_soAReturnedToChatRestoresItsText() =
        runTest {
            // AC #1: navigating away destroys the destination and its ViewModel; returning builds a new
            // one against the same app-scoped store, which is where the text was waiting.
            val store = ComposerDraftStore()
            val typing = makeVm(threadHandle("pyrybox", DRAFT_CONV), FakeConversationRepository(), draftStore = store)
            advanceUntilIdle()
            typing.onDraftChange("unsent")

            val returned = makeVm(threadHandle("pyrybox", DRAFT_CONV), FakeConversationRepository(), draftStore = store)
            advanceUntilIdle()

            // Seeded at construction — before any emission — so the initial value and the first
            // emission can never disagree.
            assertEquals("unsent", returned.draft.value)
        }

    @Test
    fun draft_isIndependentPerHost_forTheSameConversationId() =
        runTest {
            // AC #1: conversation ids are host-local, so the same id on two hosts is two chats. One
            // store, two pairs, neither visible in the other.
            val store = ComposerDraftStore()
            val onPyrybox = makeVm(threadHandle("pyrybox", DRAFT_CONV), FakeConversationRepository(), draftStore = store)
            val onLaptop = makeVm(threadHandle("laptop", DRAFT_CONV), FakeConversationRepository(), draftStore = store)
            advanceUntilIdle()

            onPyrybox.onDraftChange("for pyrybox")
            onLaptop.onDraftChange("for laptop")
            advanceUntilIdle()

            assertEquals("for pyrybox", onPyrybox.draft.value)
            assertEquals("for laptop", onLaptop.draft.value)
        }

    @Test
    fun sendMessage_whenAccepted_clearsTheDraft() =
        runTest {
            // AC #2, positive half: "accepted" is simply "the suspend call returned".
            val store = ComposerDraftStore()
            val vm = makeVm(threadHandle("pyrybox", DRAFT_CONV), FakeConversationRepository(), draftStore = store)
            advanceUntilIdle()
            vm.onDraftChange("ship it")

            vm.sendMessage("ship it")
            advanceUntilIdle()

            assertEquals("", vm.draft.value)
            // Cleared, not blanked: the entry — and its now-empty host bucket — are gone.
            assertTrue("an accepted send must remove the entry: ${store.drafts.value}", store.drafts.value.isEmpty())
        }

    @Test
    fun sendMessage_whenRefused_doesNotRestoreTheDraft() =
        runTest {
            // #1355 AC #3: as on desktop, the composer clears on tap and a refused send does not put the
            // text back — the echo stays in the thread instead (#789's restore-on-failure is dropped).
            // Each of the three failure types launchGuardedRepoCall swallows stays quiet. Uncaught throws
            // are captured because a throw escaping viewModelScope reaches the default handler, not runTest.
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
                    val store = ComposerDraftStore()
                    val vm =
                        makeVm(
                            threadHandle("pyrybox", DRAFT_CONV),
                            ThrowingConversationRepository(failure),
                            draftStore = store,
                        )
                    advanceUntilIdle()
                    vm.onDraftChange("worth keeping")

                    vm.sendMessage("worth keeping")
                    advanceUntilIdle()

                    assertEquals("refused by $failure must not restore the draft", "", vm.draft.value)
                    assertEquals("", store.draftFor("pyrybox", DRAFT_CONV))
                }
                assertTrue("a refused send must stay quiet, not crash: $uncaught", uncaught.isEmpty())
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            }
        }

    @Test
    fun sendMessage_trimsTheText_andClearsTheDraftBeforeAnyReply() =
        runTest {
            // #1355 AC #1: the send never completes, yet the composer is already clear and the
            // repository was handed the trimmed text.
            val store = ComposerDraftStore()
            val repository = NeverRepliesRepository()
            val vm = makeVm(threadHandle("pyrybox", DRAFT_CONV), repository, draftStore = store)
            advanceUntilIdle()
            vm.onDraftChange("  hi \n")

            vm.sendMessage("  hi \n")
            advanceUntilIdle()

            assertEquals(listOf("hi"), repository.sentTexts)
            assertEquals("", vm.draft.value)
            assertTrue(store.drafts.value.isEmpty())
        }

    @Test
    fun sendMessage_whenTheDraftChangedInFlight_leavesTheNewTextAlone() =
        runTest {
            // The draft clears before the send launches, so text typed while it is in flight survives it.
            val store = ComposerDraftStore()
            var vm: ThreadViewModel? = null
            val repository =
                TypingDuringSendRepository(
                    whileSending = { vm?.onDraftChange("sent text and a second thought") },
                )
            vm = makeVm(threadHandle("pyrybox", DRAFT_CONV), repository, draftStore = store)
            advanceUntilIdle()
            vm.onDraftChange("sent text")

            vm.sendMessage("sent text")
            advanceUntilIdle()

            assertEquals("sent text and a second thought", vm.draft.value)
        }

    @Test
    fun sendMessage_blankText_neverTouchesTheStore() =
        runTest {
            // The blank early-return precedes the launch, so a whitespace-only draft — which no send can
            // consume — is never cleared out from under the user.
            val store = ComposerDraftStore()
            val vm = makeVm(threadHandle("pyrybox", DRAFT_CONV), FakeConversationRepository(), draftStore = store)
            advanceUntilIdle()
            vm.onDraftChange("   ")

            vm.sendMessage("")
            vm.sendMessage("   \n\t ")
            advanceUntilIdle()

            assertEquals("   ", vm.draft.value)
        }

    // ---- #790: drafts go with their host or conversation -----------------------------------------

    @Test
    fun deleteConfirm_whenAccepted_dropsOnlyThisChatsDraft() =
        runTest {
            // #790 AC #2, positive half, driven from the overflow menu's confirmed delete. The drop
            // sits in the success continuation, so "accepted" is again "the suspend call returned".
            val store = ComposerDraftStore()
            store.setDraft("pyrybox", DRAFT_CONV, "unsent here")
            store.setDraft("pyrybox", "other-chat", "unsent next door")
            // Same conversation id on another host: two unrelated chats, ids being host-local.
            store.setDraft("laptop", DRAFT_CONV, "unsent on the laptop")
            val vm = makeVm(threadHandle("pyrybox", DRAFT_CONV), FakeConversationRepository(), draftStore = store)
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.DeleteConfirm)
            advanceUntilIdle()

            assertEquals("", vm.draft.value)
            // Cleared, not blanked — the entry is gone; this host's other chat and the other host's
            // draft for the same id both survive.
            assertEquals(
                mapOf(
                    "pyrybox" to mapOf("other-chat" to "unsent next door"),
                    "laptop" to mapOf(DRAFT_CONV to "unsent on the laptop"),
                ),
                store.drafts.value,
            )
        }

    @Test
    fun deleteConfirm_whenRefused_leavesTheDraftAlone() =
        runTest {
            // #790 AC #2, negative half. The drop is inside the guarded block after repository.delete,
            // so a failure jumps past it: a conversation that still exists keeps its unsent text.
            val store = ComposerDraftStore()
            val vm =
                makeVm(
                    threadHandle("pyrybox", DRAFT_CONV),
                    ThrowingConversationRepository(IllegalStateException("not connected")),
                    draftStore = store,
                )
            advanceUntilIdle()
            vm.onDraftChange("worth keeping")

            vm.onOverflowEvent(ThreadEvent.DeleteConfirm)
            advanceUntilIdle()

            assertEquals("worth keeping", vm.draft.value)
            assertEquals("worth keeping", store.draftFor("pyrybox", DRAFT_CONV))
        }

    @Test
    fun newSession_leavesTheDraftUntouched() =
        runTest {
            // AC #3: resetting the session is a conversation-level action with no claim on the
            // composer. Nothing in sendNewSession reads or writes the store — this pins that.
            val store = ComposerDraftStore()
            val vm = makeVm(threadHandle("pyrybox", DRAFT_CONV), FakeConversationRepository(), draftStore = store)
            advanceUntilIdle()
            vm.onDraftChange("survives the reset")

            vm.onOverflowEvent(ThreadEvent.NewSession)
            advanceUntilIdle()

            assertEquals("survives the reset", vm.draft.value)
            assertEquals("survives the reset", store.draftFor("pyrybox", DRAFT_CONV))
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
    fun state_agent_followsTheConversation_andIsClaudeByDefault() =
        runTest {
            fun conversation(
                id: String,
                agent: ConversationAgent,
            ) = Conversation(
                id = id,
                name = null,
                cwd = DEFAULT_SCRATCH_CWD,
                currentSessionId = "$id-s1",
                sessionHistory = listOf("$id-s1"),
                isPromoted = true,
                lastUsedAt = Instant.parse("2026-09-21T00:00:00Z"),
                agent = agent,
            )
            val repo =
                fixedRepo(listOf(conversation("c-codex", ConversationAgent.Codex), conversation("c-claude", ConversationAgent.Claude)))
            val codex = makeVm(SavedStateHandle(initialState = mapOf("conversationId" to "c-codex")), repo)
            val claude = makeVm(SavedStateHandle(initialState = mapOf("conversationId" to "c-claude")), repo)
            val collector =
                launch {
                    launch { codex.state.collect {} }
                    launch { claude.state.collect {} }
                }
            advanceUntilIdle()
            assertEquals(ConversationAgent.Codex, codex.state.value.agent)
            assertEquals(ConversationAgent.Claude, claude.state.value.agent)
            collector.cancel()
        }

    @Test
    fun state_agent_isTheConversationsAgent() =
        runTest {
            val codexChannel =
                Conversation(
                    id = "c-codex",
                    name = "codex",
                    cwd = "pyry-workspace/my-app",
                    currentSessionId = "c-codex-s1",
                    sessionHistory = listOf("c-codex-s1"),
                    isPromoted = true,
                    lastUsedAt = Instant.parse("2026-09-25T00:00:00Z"),
                    agent = ConversationAgent.Codex,
                )
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "c-codex"))
            val vm = makeVm(handle, fixedRepo(listOf(codexChannel)))
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals(ConversationAgent.Codex, vm.state.value.agent)
            collector.cancel()
        }

    @Test
    fun state_workspaceLabel_prefersConversationLabel_overCwdBasename() =
        runTest {
            val labelled =
                Conversation(
                    id = "d-labelled",
                    name = null,
                    cwd = "pyry-workspace/my-app",
                    currentSessionId = "d-labelled-s1",
                    sessionHistory = listOf("d-labelled-s1"),
                    isPromoted = false,
                    lastUsedAt = Instant.parse("2026-09-21T00:00:00Z"),
                    workspaceLabel = "Design system",
                )
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "d-labelled"))
            val vm = makeVm(handle, fixedRepo(listOf(labelled)))
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            // Label-first: the operator's chosen name wins over the "my-app" basename the cwd would yield.
            assertEquals("Design system", vm.state.value.workspaceLabel)
            collector.cancel()
        }

    // #1115: the thread names the conversation's own agent; Claude until the conversation is known.
    @Test
    fun state_agent_isClaudeUntilKnown_thenTheConversationsAgent() =
        runTest {
            val codex =
                Conversation(
                    id = "d-codex",
                    name = null,
                    cwd = "pyry-workspace/my-app",
                    currentSessionId = "d-codex-s1",
                    sessionHistory = listOf("d-codex-s1"),
                    isPromoted = false,
                    lastUsedAt = Instant.parse("2026-09-21T00:00:00Z"),
                    agent = ConversationAgent.Codex,
                )
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "d-codex"))
            val vm = makeVm(handle, fixedRepo(listOf(codex)))
            assertEquals(ConversationAgent.Claude, vm.state.value.agent)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals(ConversationAgent.Codex, vm.state.value.agent)
            collector.cancel()
        }

    @Test
    fun state_workspaceLabel_followsLiveRenameAndClear_onOneSubscription() =
        runTest {
            val unlabelled =
                Conversation(
                    id = "d-live",
                    name = null,
                    cwd = "pyry-workspace/my-app",
                    currentSessionId = "d-live-s1",
                    sessionHistory = listOf("d-live-s1"),
                    isPromoted = false,
                    lastUsedAt = Instant.parse("2026-09-21T00:00:00Z"),
                )
            val records = MutableStateFlow(listOf(unlabelled))
            var subscriptions = 0
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "d-live"))
            val vm = makeVm(handle, fixedRepo(records.onStart { subscriptions++ }))
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals("my-app", vm.state.value.workspaceLabel)

            // A rename on this thread's own host arrives as a re-emission of the already-subscribed
            // observeConversations(All) arm — #721 writes the label onto the owning host's projection.
            records.value = listOf(unlabelled.copy(workspaceLabel = "Design system"))
            advanceUntilIdle()
            assertEquals("Design system", vm.state.value.workspaceLabel)

            // Clearing the label returns the thread to the cwd-derived fallback.
            records.value = listOf(unlabelled.copy(workspaceLabel = null))
            advanceUntilIdle()
            assertEquals("my-app", vm.state.value.workspaceLabel)

            // Neither transition reopened the thread or resubscribed: one collector, still running,
            // over exactly one observeConversations subscription.
            assertTrue(collector.isActive)
            assertEquals(1, subscriptions)
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

            // A second trigger produces its own single event. #1399: a second Archive leaves no more than once,
            // so the second trigger is Delete.
            vm.onOverflowEvent(ThreadEvent.DeleteConfirm)
            advanceUntilIdle()
            assertEquals(2, navEvents.size)
            collector.cancel()
            navCollector.cancel()
        }

    @Test
    fun conversationArchivedElsewhere_popsBackOnce() =
        runTest {
            val repo = FakeConversationRepository()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.state.collect {} }
            val navEvents = mutableListOf<ThreadNavigation>()
            val navCollector = launch { vm.navigationEvents.collect { navEvents += it } }
            advanceUntilIdle()
            assertTrue(navEvents.isEmpty())

            // Another client archives it: the list reply now shows the row archived.
            repo.archive("seed-channel-personal")
            advanceUntilIdle()
            assertEquals(listOf(ThreadNavigation.PopBack), navEvents)

            // A later list update still showing it archived does not pop again.
            repo.rename("seed-channel-personal", "renamed")
            advanceUntilIdle()
            assertEquals(listOf(ThreadNavigation.PopBack), navEvents)
            collector.cancel()
            navCollector.cancel()
        }

    @Test
    fun ownArchive_whoseReplyMarksTheRowArchived_popsBackOnce() =
        runTest {
            val repo = FakeConversationRepository()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.state.collect {} }
            val navEvents = mutableListOf<ThreadNavigation>()
            val navCollector = launch { vm.navigationEvents.collect { navEvents += it } }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.Archive)
            advanceUntilIdle()

            assertEquals(listOf(ThreadNavigation.PopBack), navEvents)
            collector.cancel()
            navCollector.cancel()
        }

    @Test
    fun conversationRenamedElsewhere_doesNotPopBack() =
        runTest {
            val repo = FakeConversationRepository()
            val handle = SavedStateHandle(initialState = mapOf("conversationId" to "seed-channel-personal"))
            val vm = makeVm(handle, repo)
            val collector = launch { vm.state.collect {} }
            val navEvents = mutableListOf<ThreadNavigation>()
            val navCollector = launch { vm.navigationEvents.collect { navEvents += it } }
            advanceUntilIdle()

            repo.rename("seed-channel-personal", "renamed")
            advanceUntilIdle()

            assertEquals("renamed", vm.state.value.conversationName)
            assertTrue(navEvents.isEmpty())
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

    // ---- #957: Save as channel in the modal shell --------------------------------------------------

    private fun TestScope.saveAsChannelVm(repo: SaveAsChannelRepo): ThreadViewModel =
        makeVm(SavedStateHandle(initialState = mapOf("conversationId" to SAVE_AS_CONV)), repo)

    @Test
    fun saveAsChannel_opensSeededWithTheChatsOwnName() =
        runTest {
            val vm = saveAsChannelVm(SaveAsChannelRepo(name = "Release notes"))
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.SaveAsChannel)

            assertEquals(SaveAsChannelDialogState(initialName = "Release notes"), vm.state.value.saveAsChannelDialog)
            collector.cancel()
        }

    @Test
    fun saveAsChannel_opensSeededWithNewChannelWhenTheChatHasNoName() =
        runTest {
            for (unnamed in listOf(null, "  ")) {
                val vm = saveAsChannelVm(SaveAsChannelRepo(name = unnamed))
                val collector = launch { vm.state.collect {} }
                advanceUntilIdle()

                vm.onOverflowEvent(ThreadEvent.SaveAsChannel)

                assertEquals(SaveAsChannelDialogState(initialName = "New channel"), vm.state.value.saveAsChannelDialog)
                collector.cancel()
            }
        }

    @Test
    fun saveAsChannelSubmit_promotesInPlaceWithTheTrimmedNameAndClosesWithoutAPromptWrite() =
        runTest {
            val repo = SaveAsChannelRepo()
            val vm = saveAsChannelVm(repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.SaveAsChannel)
            vm.onOverflowEvent(ThreadEvent.SaveAsChannelSubmit(name = "  Ops  ", systemPrompt = " \n\t "))
            advanceUntilIdle()

            // workspace = null: the conversation keeps its own cwd, id and history.
            assertEquals(listOf("promote:$SAVE_AS_CONV:Ops:null"), repo.calls)
            assertNull(vm.state.value.saveAsChannelDialog)
            collector.cancel()
        }

    @Test
    fun saveAsChannelSubmit_writesANonBlankPromptVerbatimAfterTheConfirmedPromote() =
        runTest {
            val repo = SaveAsChannelRepo()
            val vm = saveAsChannelVm(repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.SaveAsChannel)
            vm.onOverflowEvent(ThreadEvent.SaveAsChannelSubmit(name = "Ops", systemPrompt = "  Be brief.\n"))
            advanceUntilIdle()

            assertEquals(
                listOf("promote:$SAVE_AS_CONV:Ops:null", "prompt:$SAVE_AS_CONV:  Be brief.\n"),
                repo.calls,
            )
            assertNull(vm.state.value.saveAsChannelDialog)
            collector.cancel()
        }

    @Test
    fun saveAsChannelSubmit_aFailedPromoteKeepsTheModalOpenAndOkPromotesAgain() =
        runTest {
            val repo = SaveAsChannelRepo(promoteFailures = 1)
            val vm = saveAsChannelVm(repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.SaveAsChannel)
            vm.onOverflowEvent(ThreadEvent.SaveAsChannelSubmit(name = "Ops", systemPrompt = "Be brief."))
            advanceUntilIdle()

            assertEquals(
                SaveAsChannelDialogState(initialName = "Chat", failure = SaveAsChannelFailure.Promote),
                vm.state.value.saveAsChannelDialog,
            )
            assertEquals(listOf("promote:$SAVE_AS_CONV:Ops:null"), repo.calls)

            vm.onOverflowEvent(ThreadEvent.SaveAsChannelSubmit(name = "Ops", systemPrompt = "Be brief."))
            advanceUntilIdle()

            assertEquals(
                listOf("promote:$SAVE_AS_CONV:Ops:null", "promote:$SAVE_AS_CONV:Ops:null", "prompt:$SAVE_AS_CONV:Be brief."),
                repo.calls,
            )
            assertNull(vm.state.value.saveAsChannelDialog)
            collector.cancel()
        }

    @Test
    fun saveAsChannelSubmit_aFailedPromptWriteKeepsTheModalOpenAndOkRetriesOnlyThatWrite() =
        runTest {
            val repo = SaveAsChannelRepo(promptFailures = 1)
            val vm = saveAsChannelVm(repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.SaveAsChannel)
            vm.onOverflowEvent(ThreadEvent.SaveAsChannelSubmit(name = "Ops", systemPrompt = "Be brief."))
            advanceUntilIdle()

            assertEquals(
                SaveAsChannelDialogState(initialName = "Chat", promoted = true, failure = SaveAsChannelFailure.SystemPrompt),
                vm.state.value.saveAsChannelDialog,
            )

            vm.onOverflowEvent(ThreadEvent.SaveAsChannelSubmit(name = "Ops", systemPrompt = "Be briefer."))
            advanceUntilIdle()

            assertEquals(
                listOf(
                    "promote:$SAVE_AS_CONV:Ops:null",
                    "prompt:$SAVE_AS_CONV:Be brief.",
                    "prompt:$SAVE_AS_CONV:Be briefer.",
                ),
                repo.calls,
            )
            assertNull(vm.state.value.saveAsChannelDialog)
            collector.cancel()
        }

    @Test
    fun saveAsChannelSubmit_whileAWriteIsInFlightIsIgnored() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repo = SaveAsChannelRepo(promoteGate = gate)
            val vm = saveAsChannelVm(repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.SaveAsChannel)
            vm.onOverflowEvent(ThreadEvent.SaveAsChannelSubmit(name = "Ops", systemPrompt = ""))
            advanceUntilIdle()
            assertEquals(
                true,
                vm.state.value.saveAsChannelDialog
                    ?.saving,
            )

            vm.onOverflowEvent(ThreadEvent.SaveAsChannelSubmit(name = "Ops", systemPrompt = ""))
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("promote:$SAVE_AS_CONV:Ops:null"), repo.calls)
            assertNull(vm.state.value.saveAsChannelDialog)
            collector.cancel()
        }

    @Test
    fun saveAsChannel_aResultLandingAfterCancelDoesNotReopenTheModal() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repo = SaveAsChannelRepo(promoteFailures = 1, promoteGate = gate)
            val vm = saveAsChannelVm(repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            vm.onOverflowEvent(ThreadEvent.SaveAsChannel)
            vm.onOverflowEvent(ThreadEvent.SaveAsChannelSubmit(name = "Ops", systemPrompt = ""))
            advanceUntilIdle()
            vm.onOverflowEvent(ThreadEvent.SaveAsChannelDismiss)
            gate.complete(Unit)
            advanceUntilIdle()

            assertNull(vm.state.value.saveAsChannelDialog)
            collector.cancel()
        }

    @Test
    fun saveAsChannel_cancelAndInvalidSubmitsSendNothing() =
        runTest {
            val repo = SaveAsChannelRepo()
            val vm = saveAsChannelVm(repo)
            val collector = launch { vm.state.collect {} }
            advanceUntilIdle()

            // A submit with no open modal, a blank name and an over-limit prompt are all refused.
            vm.onOverflowEvent(ThreadEvent.SaveAsChannelSubmit(name = "Ops", systemPrompt = ""))
            vm.onOverflowEvent(ThreadEvent.SaveAsChannel)
            vm.onOverflowEvent(ThreadEvent.SaveAsChannelSubmit(name = "   ", systemPrompt = ""))
            vm.onOverflowEvent(
                ThreadEvent.SaveAsChannelSubmit(name = "Ops", systemPrompt = "a".repeat(SystemPromptLimit.MAX_BYTES + 1)),
            )
            advanceUntilIdle()
            assertEquals(SaveAsChannelDialogState(initialName = "Chat"), vm.state.value.saveAsChannelDialog)

            vm.onOverflowEvent(ThreadEvent.SaveAsChannelDismiss)
            advanceUntilIdle()

            assertNull(vm.state.value.saveAsChannelDialog)
            assertTrue(repo.calls.isEmpty())
            collector.cancel()
        }

    @Test
    fun saveAsChannelSubmit_toStringRedactsThePrompt() {
        val event = ThreadEvent.SaveAsChannelSubmit(name = "Ops", systemPrompt = "sk-secret")

        assertFalse(event.toString().contains("sk-secret"))
    }

    // --- helpers ---

    // --- #777 / #1352: the history walk asks only when the reader does --------------------------------

    // --- #1569 / #1572: an open thread asks for the newest page whenever its host arrives ------------

    @Test
    fun history_openingANeverLoadedThread_asksTheNewestPageOnceAndItsRowsRender() =
        runTest {
            // The repository merges a served page into observeMessages; the double stands in for that.
            lateinit var repo: HistoryRepo
            repo =
                HistoryRepo {
                    repo.messages.value = listOf(messageItem("m1"))
                    page(cursor = "c1")
                }
            val vm = makeVm(historyHandle(), repo)
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(listOf(""), repo.asks)
            assertEquals(
                listOf<HistoryPosition?>(HistoryPosition("c1", atStart = false)),
                repo.positionWrites.map { it?.copy(coverage = null) },
            )
            assertEquals(
                listOf("m1"),
                vm.state.value.items
                    .filterIsInstance<ThreadItem.MessageItem>()
                    .map { it.message.id },
            )
            assertEquals(ThreadHistoryTail.None, vm.state.value.historyTail)
            collector.cancel()
        }

    @Test
    fun history_aNeverLoadedThreadOpenedOffline_asksWhenTheHostArrivesAndOnEachReturn() =
        runTest {
            val available = MutableStateFlow(false)
            val repo = HistoryRepo { page(cursor = "c1") }
            makeVm(historyHandle(), repo, repositoryAvailable = available)
            advanceUntilIdle()
            assertEquals(emptyList<String>(), repo.asks)

            available.value = true
            advanceUntilIdle()
            assertEquals(listOf(""), repo.asks)

            // #1572: each drop and return asks the newest page again; the page that arrived asks nothing.
            repeat(2) {
                available.value = false
                advanceUntilIdle()
                available.value = true
                advanceUntilIdle()
            }
            assertEquals(listOf("", "", ""), repo.asks)
            // Only the first page was the walk's own; the returns' asks leave its position alone.
            assertEquals(
                List<HistoryPosition?>(3) { HistoryPosition("c1", atStart = false) },
                repo.positionWrites.map { it?.copy(coverage = null) },
            )
        }

    @Test
    fun history_aPullBeforeTheOpeningAskClaimsTheSlot_isTheOnlyAsk() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val available = MutableStateFlow(true)
            val repo = HistoryRepo(readGate = gate) { page(cursor = "c1") }
            val vm = makeVm(historyHandle(), repo, repositoryAvailable = available)
            advanceUntilIdle()
            vm.onDemandOlderHistory()
            gate.complete(Unit)
            advanceUntilIdle()
            // One ask from the newest; the opening ask found the walk already started and asked nothing.
            assertEquals(listOf(""), repo.asks)
        }

    @Test
    fun history_eachDemand_asksOnceAndTheSettleAsksNothingFurther() =
        runTest {
            val repo = HistoryRepo { asked -> page(cursor = if (asked.isEmpty()) "c1" else "c2") }
            val vm = makeVm(historyHandle(), repo)
            advanceUntilIdle()
            // #1569: a never-loaded thread's opening ask. Empty cursor = "start at the newest"; the settle
            // does not chain another ask.
            assertEquals(listOf(""), repo.asks)
            vm.onDemandOlderHistory()
            advanceUntilIdle()
            // Echoed unexamined — the VM never parses or rebuilds what the daemon handed back.
            assertEquals(listOf("", "c1"), repo.asks)
        }

    @Test
    fun history_anAskArrivingDuringARequest_isDroppedNotQueued() =
        runTest {
            val gate = CompletableDeferred<HistoryPage>()
            val repo = HistoryRepo { gate.await() }
            val vm = makeVm(historyHandle(), repo)
            advanceUntilIdle()
            repeat(3) { vm.onDemandOlderHistory() }
            advanceUntilIdle()
            assertEquals(listOf(""), repo.asks)
            // Dropped, not queued: releasing the in-flight page issues no backlog of asks.
            gate.complete(page(cursor = "c1"))
            advanceUntilIdle()
            assertEquals(listOf(""), repo.asks)
        }

    @Test
    fun history_aPageReportingAtStart_endsTheWalk() =
        runTest {
            val repo = HistoryRepo { HistoryPage(entries = emptyList(), cursor = "", atStart = true) }
            val vm = makeVm(historyHandle(), repo)
            advanceUntilIdle()
            repeat(5) { vm.onDemandOlderHistory() }
            advanceUntilIdle()
            assertEquals(listOf(""), repo.asks)
        }

    @Test
    fun history_aNonAdvancingDaemon_cannotDriveAnUnboundedRequestLoop() =
        runTest {
            // atStart = false, no entries, and the same cursor every time — the shape that would spin.
            val repo = HistoryRepo { asked -> HistoryPage(entries = emptyList(), cursor = asked, atStart = false) }
            val vm = makeVm(historyHandle(), repo)
            advanceUntilIdle()
            repeat(50) {
                vm.onDemandOlderHistory()
                advanceUntilIdle()
            }
            assertEquals(1, repo.asks.size)
        }

    @Test
    fun history_anAlternatingCursorDaemon_isBoundedByTheClientSidePageCap() =
        runTest {
            // Alternating cursors defeat the non-advancing guard, so only the cap stops this one.
            val repo = HistoryRepo { asked -> page(cursor = if (asked == "a") "b" else "a") }
            val vm = makeVm(historyHandle(), repo)
            advanceUntilIdle()
            repeat(MAX_HISTORY_PAGES * 3) {
                vm.onDemandOlderHistory()
                advanceUntilIdle()
            }
            assertEquals(MAX_HISTORY_PAGES, repo.asks.size)
        }

    @Test
    fun history_loadingIsVisibleWhileAPageIsInFlight() =
        runTest {
            val gate = CompletableDeferred<HistoryPage>()
            val repo = HistoryRepo { gate.await() }
            val vm = makeVm(historyHandle(), repo)
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()
            vm.onDemandOlderHistory()
            advanceUntilIdle()
            assertEquals(ThreadHistoryTail.Loading, vm.state.value.historyTail)
            gate.complete(page(cursor = "c1"))
            advanceUntilIdle()
            assertEquals(ThreadHistoryTail.None, vm.state.value.historyTail)
            collector.cancel()
        }

    @Test
    fun history_aMessageLandingWhileAPageIsInFlight_rendersExactlyOnce() =
        runTest {
            // Nothing needs a second fold: the repository already merged the page into the thread the VM
            // reads through observeMessages. A page's entries must never become rows here as well.
            val gate = CompletableDeferred<HistoryPage>()
            val repo = HistoryRepo { gate.await() }
            val vm = makeVm(historyHandle(), repo)
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()
            vm.onDemandOlderHistory()
            repo.messages.value = listOf(messageItem("m1"))
            advanceUntilIdle()
            assertEquals(listOf("m1"), messageIds(vm))
            gate.complete(
                HistoryPage(
                    entries = listOf(HistoryEntry(1L, "message", buildJsonObject {}, Instant.parse("2026-09-22T00:00:00Z"))),
                    cursor = "c1",
                    atStart = false,
                ),
            )
            advanceUntilIdle()
            assertEquals(listOf("m1"), messageIds(vm))
            collector.cancel()
        }

    // --- #778 / #1352: failures, the retry and the refused cursor ----------------------------------

    @Test
    fun history_aRetryableFailure_retriesFromTheSameCursorAndKeepsEveryLoadedRow() =
        runTest {
            var fail = false
            val repo =
                HistoryRepo { asked ->
                    if (fail) throw RelayErrorException("history.unavailable", true, "boom")
                    page(cursor = if (asked.isEmpty()) "c1" else "c2")
                }
            val vm = makeVm(historyHandle(), repo)
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            repo.messages.value = listOf(messageItem("m1"))
            advanceUntilIdle()

            fail = true
            vm.onDemandOlderHistory()
            advanceUntilIdle()
            assertEquals(ThreadHistoryTail.Retry, vm.state.value.historyTail)

            fail = false
            vm.onRetryOlderHistory()
            advanceUntilIdle()
            // The retry asks with the SAME cursor, so it loads the page that failed...
            assertEquals(listOf("", "c1", "c1"), repo.asks)
            // ...and both the failure and the retry left every loaded row alone.
            assertEquals(listOf("m1"), messageIds(vm))
            assertEquals(ThreadHistoryTail.None, vm.state.value.historyTail)
            collector.cancel()
        }

    @Test
    fun history_afterAnyFailure_aFreshDemandAsksAgainFromTheSameCursor() =
        runTest {
            listOf(
                RelayErrorException("history.unavailable", true, "boom"),
                RelayErrorException("history.invalid_page_size", false, "nope"),
                IllegalArgumentException("unknown conversation"),
                IllegalStateException("not connected"),
            ).forEach { failure ->
                var fail = false
                val repo =
                    HistoryRepo { asked ->
                        if (fail) throw failure
                        page(cursor = if (asked.isEmpty()) "c1" else "c2")
                    }
                val vm = makeVm(historyHandle(), repo)
                val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
                advanceUntilIdle()
                fail = true
                vm.onDemandOlderHistory()
                advanceUntilIdle()
                assertNotEquals("$failure", ThreadHistoryTail.None, vm.state.value.historyTail)

                fail = false
                vm.onDemandOlderHistory()
                advanceUntilIdle()
                assertEquals("$failure", listOf("", "c1", "c1"), repo.asks)
                assertEquals("$failure", ThreadHistoryTail.None, vm.state.value.historyTail)
                collector.cancel()
            }
        }

    @Test
    fun history_aPermanentFailure_isVisibleAndOffersNoRetry() =
        runTest {
            val repo = HistoryRepo { throw RelayErrorException("history.invalid_page_size", false, "nope") }
            val vm = makeVm(historyHandle(), repo)
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(ThreadHistoryTail.DeadEnd, vm.state.value.historyTail)
            vm.onRetryOlderHistory()
            advanceUntilIdle()
            assertEquals(listOf(""), repo.asks)
            collector.cancel()
        }

    @Test
    fun history_aRefusedCursor_asksNothingAndTheNextDemandAsksFromTheNewestPage() =
        runTest {
            val repo =
                HistoryRepo { asked ->
                    if (asked.isEmpty()) page(cursor = "c1") else throw RelayErrorException("history.invalid_cursor", false, "stale")
                }
            val vm = makeVm(historyHandle(), repo)
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            repo.messages.value = listOf(messageItem("m1"))
            advanceUntilIdle()
            vm.onDemandOlderHistory()
            advanceUntilIdle()

            // The refusal does not ask by itself, surfaces no dead end, and drops no row...
            assertEquals(listOf("", "c1"), repo.asks)
            assertEquals(ThreadHistoryTail.None, vm.state.value.historyTail)
            assertEquals(listOf("m1"), messageIds(vm))
            // ...and the next gesture asks from the newest page.
            vm.onDemandOlderHistory()
            advanceUntilIdle()
            assertEquals(listOf("", "c1", ""), repo.asks)
            collector.cancel()
        }

    @Test
    fun history_aRefusedNewestPage_isADeadEndAndAsksNothingByItself() =
        runTest {
            val repo = HistoryRepo { throw RelayErrorException("history.invalid_cursor", false, "stale") }
            val vm = makeVm(historyHandle(), repo)
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(listOf(""), repo.asks)
            assertEquals(ThreadHistoryTail.DeadEnd, vm.state.value.historyTail)
            collector.cancel()
        }

    // --- #1352: reconnects and the offline host ---------------------------------------------------

    @Test
    fun history_aReconnect_asksTheNewestPageAndTheNextDemandKeepsTheWalksCursor() =
        runTest {
            val gate = CompletableDeferred<HistoryPage>()
            val available = MutableStateFlow(true)
            val repo =
                HistoryRepo { asked ->
                    when (asked) {
                        "" -> page(cursor = "c1")
                        "c1" -> gate.await()
                        else -> page(cursor = "c3")
                    }
                }
            val vm = makeVm(historyHandle(), repo, repositoryAvailable = available)
            advanceUntilIdle()
            // #1569: the never-loaded thread's opening ask.
            assertEquals(listOf(""), repo.asks)

            available.value = false
            advanceUntilIdle()
            available.value = true
            advanceUntilIdle()
            // #1572: regaining the repository asks the newest page...
            assertEquals(listOf("", ""), repo.asks)

            // ...and the next gesture still continues from the cursor the walk held.
            vm.onDemandOlderHistory()
            advanceUntilIdle()
            assertEquals(listOf("", "", "c1"), repo.asks)

            // A page in flight across a reconnect still settles into the walk: its cursor stays valid. The
            // return's newest-page ask waits for the outstanding page.
            available.value = false
            available.value = true
            advanceUntilIdle()
            gate.complete(page(cursor = "c2"))
            advanceUntilIdle()
            assertEquals(listOf("", "", "c1", ""), repo.asks)
            vm.onDemandOlderHistory()
            advanceUntilIdle()
            assertEquals(listOf("", "", "c1", "", "c2"), repo.asks)
        }

    @Test
    fun history_whileTheHostIsNotConnected_aDemandSendsNothingAndTheSlotSaysSo() =
        runTest {
            // #861's shape: the socket is up, but the repository is not published yet.
            val socket = FakeConnectionStateSource()
            val live = HistoryRepo { HistoryPage(entries = emptyList(), cursor = "", atStart = true) }
            val published = MutableStateFlow<ConversationRepository?>(null)
            val vm =
                makeVm(
                    historyHandle(),
                    StableConversationRepository(published),
                    source = socket,
                    repositoryAvailable = published.map { it != null },
                )
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            socket.emit(ConnectionState.Connected)
            advanceUntilIdle()

            vm.onDemandOlderHistory()
            vm.onRetryOlderHistory()
            advanceUntilIdle()
            assertEquals(emptyList<String>(), live.asks)
            assertEquals(ThreadHistoryTail.Offline, vm.state.value.historyTail)

            // #1569: the repository's arrival sends the never-loaded thread's one opening ask; the notice clears.
            published.value = live
            advanceUntilIdle()
            assertEquals(listOf(""), live.asks)
            assertEquals(ThreadHistoryTail.None, vm.state.value.historyTail)

            // Once this walk has reached the start of history, a pull asks nothing and going offline shows
            // no notice.
            vm.onDemandOlderHistory()
            advanceUntilIdle()
            assertEquals(listOf(""), live.asks)
            published.value = null
            advanceUntilIdle()
            assertEquals(ThreadHistoryTail.None, vm.state.value.historyTail)
            collector.cancel()
        }

    @Test
    fun history_breadcrumbsNameTheBranchAndCarryNothingTheDaemonWrote() =
        runTest {
            // Every value a hostile daemon controls on these paths — the cursor it minted, the code it
            // chose, the message it wrote — must be absent from the log. The labels are LOCAL literals
            // chosen by which branch fired, not e.code, so an attacker cannot write a log line.
            val available = MutableStateFlow(true)
            var asks = 0
            val repo =
                HistoryRepo {
                    when (asks++) {
                        0 -> page(cursor = "SECRET-CURSOR")
                        1 -> throw RelayErrorException("history.invalid_cursor", false, "SERVER-PROSE")
                        else -> throw RelayErrorException("history.unavailable", true, "SERVER-PROSE")
                    }
                }
            val vm = makeVm(historyHandle(), repo, repositoryAvailable = available)
            advanceUntilIdle()
            repeat(3) {
                vm.onDemandOlderHistory()
                advanceUntilIdle()
            }
            available.value = false
            advanceUntilIdle()
            vm.onDemandOlderHistory()
            advanceUntilIdle()

            assertTrue("$logs", logs.any { it == "event=history_cursor_refused" })
            assertTrue("$logs", logs.any { it == "event=history_ask_failed retryable=true" })
            assertTrue("$logs", logs.any { it == "event=history_ask_skipped reason=offline" })
            listOf("SECRET-CURSOR", "SERVER-PROSE", "history.invalid_cursor", "history.unavailable")
                .forEach { leaked ->
                    assertFalse("$leaked reached a log line: $logs", logs.any { leaked in it })
                }
        }

    // --- #1354: the walk resumes from the position saved beside the cached rows ------------------------

    @Test
    fun history_withASavedPosition_openingAsksTheNewestPageAndTheFirstPullStillAsksWithTheSavedCursor() =
        runTest {
            // #1572: the cached rows are drawn; the daemon stored a reply after them while the thread was
            // off-screen. The repository merges the newest page under the cached rows; the double stands in.
            lateinit var repo: HistoryRepo
            repo =
                HistoryRepo(saved = HistoryPosition("saved-cursor", atStart = false)) { asked ->
                    if (asked.isEmpty()) {
                        repo.messages.value = listOf(messageItem("m1"), messageItem("m2"), messageItem("m3"))
                        page(cursor = "newest")
                    } else {
                        page(cursor = "older")
                    }
                }
            repo.messages.value = listOf(messageItem("m1"), messageItem("m2"))
            val vm = makeVm(historyHandle(), repo)
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            // One newest-page ask with no gesture; the reply renders after the cached rows, none twice...
            assertEquals(listOf(""), repo.asks)
            assertEquals(listOf("m1", "m2", "m3"), messageIds(vm))
            assertEquals(ThreadHistoryTail.None, vm.state.value.historyTail)
            // ...and the walk's saved position is untouched.
            assertEquals(listOf<HistoryPosition?>(repo.saved?.copy(coverage = null)), repo.positionWrites.map { it?.copy(coverage = null) })

            vm.onDemandOlderHistory()
            advanceUntilIdle()

            assertEquals(listOf("", "saved-cursor"), repo.asks)
            assertEquals(
                listOf<HistoryPosition?>(HistoryPosition("saved-cursor", false), HistoryPosition("older", false)),
                repo.positionWrites.map { it?.copy(coverage = null) },
            )
            collector.cancel()
        }

    @Test
    fun history_withASavedPosition_eachReturnOfTheHostAsksTheNewestPageOnce() =
        runTest {
            val available = MutableStateFlow(false)
            val repo = HistoryRepo(saved = HistoryPosition("saved-cursor", atStart = false)) { page(cursor = "c9") }
            val vm = makeVm(historyHandle(), repo, repositoryAvailable = available)
            advanceUntilIdle()
            // Opened before the host is connected: nothing yet, then one ask when it connects.
            assertEquals(emptyList<String>(), repo.asks)
            available.value = true
            advanceUntilIdle()
            assertEquals(listOf(""), repo.asks)

            repeat(2) {
                available.value = false
                advanceUntilIdle()
                available.value = true
                advanceUntilIdle()
            }
            assertEquals(listOf("", "", ""), repo.asks)
            assertTrue("$logs", logs.count { it == "event=history_newest_ask reason=reconnect" } == 2)

            // None of them moved the walk: the pull still asks with the saved cursor.
            vm.onDemandOlderHistory()
            advanceUntilIdle()
            assertEquals(listOf("", "", "", "saved-cursor"), repo.asks)
            assertEquals(
                List<HistoryPosition?>(3) { HistoryPosition("saved-cursor", false) } + HistoryPosition("c9", false),
                repo.positionWrites.map { it?.copy(coverage = null) },
            )
        }

    @Test
    fun history_theNewestPageAsk_sharesTheWalksSingleOutstandingRequest() =
        runTest {
            var newest = CompletableDeferred<HistoryPage>()
            val available = MutableStateFlow(true)
            val repo =
                HistoryRepo(saved = HistoryPosition("saved-cursor", atStart = false)) { asked ->
                    if (asked.isEmpty()) newest.await() else throw RelayErrorException("history.unavailable", true, "busy")
                }
            val vm = makeVm(historyHandle(), repo, repositoryAvailable = available)
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            // While the opening newest-page ask is out, a pull is dropped, not queued.
            assertEquals(listOf(""), repo.asks)
            assertEquals(ThreadHistoryTail.Loading, vm.state.value.historyTail)
            repeat(3) { vm.onDemandOlderHistory() }
            advanceUntilIdle()
            newest.complete(page(cursor = "newest"))
            advanceUntilIdle()
            assertEquals(listOf(""), repo.asks)

            // A pull that fails retryably, then a return of the host while the Retry row shows: while the
            // return's ask is out the retry sends nothing, and once it settles the walk's Retry is back.
            vm.onDemandOlderHistory()
            advanceUntilIdle()
            assertEquals(ThreadHistoryTail.Retry, vm.state.value.historyTail)
            newest = CompletableDeferred()
            available.value = false
            advanceUntilIdle()
            available.value = true
            advanceUntilIdle()
            assertEquals(listOf("", "saved-cursor", ""), repo.asks)
            vm.onRetryOlderHistory()
            vm.onDemandOlderHistory()
            advanceUntilIdle()
            assertEquals(listOf("", "saved-cursor", ""), repo.asks)

            newest.complete(page(cursor = "newest"))
            advanceUntilIdle()
            assertEquals(ThreadHistoryTail.Retry, vm.state.value.historyTail)
            vm.onRetryOlderHistory()
            advanceUntilIdle()
            assertEquals(listOf("", "saved-cursor", "", "saved-cursor"), repo.asks)
            collector.cancel()
        }

    @Test
    fun history_aFailedNewestPageAsk_leavesTheWalkAndItsSavedPositionAlone() =
        runTest {
            val repo =
                HistoryRepo(saved = HistoryPosition("saved-cursor", atStart = false)) { asked ->
                    if (asked.isEmpty()) throw RelayErrorException("history.unavailable", true, "SERVER-PROSE")
                    page(cursor = "older")
                }
            val vm = makeVm(historyHandle(), repo)
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(listOf(""), repo.asks)
            // No Retry or dead end for a page the reader did not ask for, and no position written.
            assertEquals(ThreadHistoryTail.None, vm.state.value.historyTail)
            assertEquals(emptyList<HistoryPosition?>(), repo.positionWrites)
            assertTrue("$logs", logs.any { it == "event=history_newest_ask_failed" })
            assertFalse("$logs", logs.any { "SERVER-PROSE" in it || "history.unavailable" in it })

            vm.onDemandOlderHistory()
            advanceUntilIdle()
            assertEquals(listOf("", "saved-cursor"), repo.asks)
            collector.cancel()
        }

    @Test
    fun history_withNoSavedPosition_theFirstPullAsksFromTheNewestAndSavesThePage() =
        runTest {
            val repo = HistoryRepo { HistoryPage(entries = emptyList(), cursor = "", atStart = true) }
            val vm = makeVm(historyHandle(), repo)
            advanceUntilIdle()
            vm.onDemandOlderHistory()
            advanceUntilIdle()

            assertEquals(listOf(""), repo.asks)
            // Even an empty page sets the position.
            assertEquals(
                listOf<HistoryPosition?>(HistoryPosition("", atStart = true)),
                repo.positionWrites.map { it?.copy(coverage = null) },
            )
        }

    @Test
    fun history_aPullWhileTheSavedPositionIsBeingRead_asksWithTheSavedCursor() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repo = HistoryRepo(saved = HistoryPosition("saved-cursor", atStart = false), readGate = gate) { page(cursor = "older") }
            val vm = makeVm(historyHandle(), repo)
            advanceUntilIdle()
            repeat(3) { vm.onDemandOlderHistory() }
            advanceUntilIdle()
            assertEquals(emptyList<String>(), repo.asks)

            gate.complete(Unit)
            advanceUntilIdle()

            // #1572: the opening newest-page ask, waiting on the same read, claims the slot first and the
            // waiting pulls are dropped under the single-request rule. No ask carried the empty cursor as
            // the walk's: the next pull asks with the saved one.
            assertEquals(listOf(""), repo.asks)
            assertEquals(listOf<HistoryPosition?>(repo.saved?.copy(coverage = null)), repo.positionWrites.map { it?.copy(coverage = null) })
            vm.onDemandOlderHistory()
            advanceUntilIdle()
            assertEquals(listOf("", "saved-cursor"), repo.asks)
        }

    @Test
    fun history_aSavedAtStart_pullsAskNothingAndTheOfflineNoticeStaysHidden() =
        runTest {
            val available = MutableStateFlow(true)
            val repo = HistoryRepo(saved = HistoryPosition("", atStart = true)) { page(cursor = "c1") }
            val vm = makeVm(historyHandle(), repo, repositoryAvailable = available)
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()
            // #1572: the opening newest-page ask, whose page does not reopen the finished walk.
            assertEquals(listOf(""), repo.asks)

            repeat(3) { vm.onDemandOlderHistory() }
            advanceUntilIdle()
            assertEquals(listOf(""), repo.asks)
            assertEquals(listOf<HistoryPosition?>(repo.saved?.copy(coverage = null)), repo.positionWrites.map { it?.copy(coverage = null) })

            available.value = false
            advanceUntilIdle()
            assertEquals(ThreadHistoryTail.None, vm.state.value.historyTail)
            collector.cancel()
        }

    @Test
    fun history_aFailedAsk_leavesTheSavedPositionUnchanged() =
        runTest {
            val saved = HistoryPosition("saved-cursor", atStart = false)
            var asks = 0
            val repo =
                HistoryRepo(saved = saved) { asked ->
                    // #1572: the opening newest-page ask.
                    if (asked.isEmpty()) return@HistoryRepo page(cursor = "newest")
                    if (asks++ == 0) {
                        throw RelayErrorException("history.unavailable", true, "busy")
                    } else {
                        throw IllegalStateException("not connected")
                    }
                }
            val vm = makeVm(historyHandle(), repo)
            advanceUntilIdle()
            repeat(2) {
                vm.onDemandOlderHistory()
                advanceUntilIdle()
            }

            assertEquals(listOf("", "saved-cursor", "saved-cursor"), repo.asks)
            assertEquals(listOf<HistoryPosition?>(saved), repo.positionWrites.map { it?.copy(coverage = null) })
            assertEquals(saved, repo.saved?.copy(coverage = null))
        }

    @Test
    fun history_aRefusedSavedCursor_clearsTheSavedPositionAndTheNextPullAsksFromTheNewest() =
        runTest {
            val repo =
                HistoryRepo(saved = HistoryPosition("stale-cursor", atStart = false)) { cursor ->
                    if (cursor.isNotEmpty()) throw RelayErrorException("history.invalid_cursor", false, "stale")
                    page(cursor = "fresh")
                }
            val vm = makeVm(historyHandle(), repo)
            advanceUntilIdle()
            vm.onDemandOlderHistory()
            advanceUntilIdle()
            // The opening newest page saved coverage; the backwards cursor refusal clears the position.
            assertEquals(
                listOf<HistoryPosition?>(HistoryPosition("stale-cursor", false), null),
                repo.positionWrites.map { it?.copy(coverage = null) },
            )
            assertEquals(null, repo.saved)

            // The next open starts from the newest page...
            val reopened = HistoryRepo(saved = repo.saved) { page(cursor = "c1") }
            makeVm(historyHandle(), reopened)
            advanceUntilIdle()
            assertEquals(listOf(""), reopened.asks)

            // ...and so does the next pull.
            vm.onDemandOlderHistory()
            advanceUntilIdle()
            assertEquals(listOf("", "stale-cursor", ""), repo.asks)
        }

    @Test
    fun history_availabilityDuringAnOlderAsk_isDeferredExactlyOnce() =
        runTest {
            val older = CompletableDeferred<HistoryPage>()
            val available = MutableStateFlow(true)
            val repo =
                HistoryRepo(saved = HistoryPosition("older", false)) { cursor ->
                    if (cursor.isEmpty()) page("newest") else older.await()
                }
            val vm = makeVm(historyHandle(), repo, repositoryAvailable = available)
            advanceUntilIdle()
            vm.onDemandOlderHistory()
            advanceUntilIdle()
            available.value = false
            advanceUntilIdle()
            available.value = true
            advanceUntilIdle()
            assertEquals(listOf("", "older"), repo.asks)
            older.complete(page("next-older"))
            advanceUntilIdle()
            assertEquals(listOf("", "older", ""), repo.asks)
        }

    @Test
    fun history_multipleAvailabilityArrivalsDuringAnOlderAsk_areEachPreserved() =
        runTest {
            val gate = CompletableDeferred<HistoryPage>()
            val available = MutableStateFlow(true)
            val repo =
                HistoryRepo(saved = HistoryPosition("older", false)) { cursor ->
                    if (cursor.isEmpty()) {
                        page("newest")
                    } else {
                        gate.await()
                    }
                }
            val vm = makeVm(historyHandle(), repo, repositoryAvailable = available)
            advanceUntilIdle()
            vm.onDemandOlderHistory()
            advanceUntilIdle()
            repeat(2) {
                available.value = false
                advanceUntilIdle()
                available.value = true
                advanceUntilIdle()
            }
            assertEquals(listOf("", "older"), repo.asks)
            gate.complete(page("next-older"))
            advanceUntilIdle()
            assertEquals(listOf("", "older", "", ""), repo.asks)
        }

    @Test
    fun history_closedConversationCancelsDeferredAvailability_andCannotAskForAnotherThread() =
        runTest {
            val gate = CompletableDeferred<HistoryPage>()
            val available = MutableStateFlow(true)
            val repo =
                HistoryRepo(saved = HistoryPosition("older", false)) { cursor ->
                    if (cursor.isEmpty()) {
                        page("newest")
                    } else {
                        gate.await()
                    }
                }
            val vm = makeVm(historyHandle(), repo, repositoryAvailable = available)
            val store = ViewModelStore().apply { put("vm", vm) }
            advanceUntilIdle()
            vm.onDemandOlderHistory()
            advanceUntilIdle()
            available.value = false
            advanceUntilIdle()
            available.value = true
            advanceUntilIdle()
            store.clear()
            gate.complete(page("next-older"))
            advanceUntilIdle()
            available.value = false
            advanceUntilIdle()
            available.value = true
            advanceUntilIdle()
            assertEquals(listOf("", "older"), repo.asks)
        }

    @Test
    fun history_anEarlierEmptyTerminalPageCannotBlockDemandAfterReconnect() =
        runTest {
            val available = MutableStateFlow(true)
            var pages = 0
            val repo =
                HistoryRepo {
                    if (pages++ == 0) HistoryPage(emptyList(), "", true) else durablePage(9, cursor = "eight")
                }
            val vm = makeVm(historyHandle(), repo, repositoryAvailable = available)
            advanceUntilIdle()
            available.value = false
            advanceUntilIdle()
            available.value = true
            advanceUntilIdle()
            vm.onDemandOlderHistory()
            advanceUntilIdle()
            assertEquals(listOf("", "", "eight"), repo.asks)
        }

    @Test
    fun history_unknownAndKnownMarkersSharingAnEdge_remainInChronologicalOrder() =
        runTest {
            val coverage = HistoryCoverage(unknown = true).received(durablePage(1, cursor = "older"))
            val repo = HistoryRepo(saved = HistoryPosition("older", true, coverage)) { durablePage(9, cursor = "eight") }
            val vm = makeVm(historyHandle(), repo)
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals(
                listOf(0L, 1L),
                vm.state.value.historyMarkers
                    .map { it.anchor },
            )
            assertTrue(
                vm.state.value.historyMarkers
                    .all { it.beforeRow.isEmpty() },
            )
            collector.cancel()
        }

    @Test
    fun history_aRefusedBackwardsCursor_resetsOnlyThatWalk_andKeepsDurableGaps() =
        runTest {
            val coverage = HistoryCoverage().received(durablePage(1, 2, cursor = "oldest"))
            val repo =
                HistoryRepo(saved = HistoryPosition("oldest", false, coverage)) { cursor ->
                    if (cursor.isEmpty()) {
                        durablePage(8, 9, cursor = "seven")
                    } else {
                        throw RelayErrorException("history.invalid_cursor", false, "untrusted")
                    }
                }
            val vm = makeVm(historyHandle(), repo)
            advanceUntilIdle()
            val gaps = repo.saved?.coverage?.gaps
            vm.onDemandOlderHistory()
            advanceUntilIdle()
            assertEquals(listOf("", "oldest"), repo.asks)
            assertEquals("", repo.saved?.cursor)
            assertEquals(false, repo.saved?.atStart)
            assertEquals(gaps, repo.saved?.coverage?.gaps)
            assertEquals("seven", repo.saved?.coverage?.cursorFor(2))
        }

    @Test
    fun history_gapPullCostsOnePage_keepsSavedAtStart_andRefusalWaitsForAnotherPull() =
        runTest {
            val old = durablePage(1, 2, cursor = "oldest")
            var refuse = true
            val repo =
                HistoryRepo(saved = HistoryPosition("oldest", true, HistoryCoverage().received(old))) { cursor ->
                    when (cursor) {
                        "" -> durablePage(8, 9, cursor = "seven")
                        "seven" ->
                            if (refuse) {
                                refuse = false
                                throw RelayErrorException("history.invalid_cursor", false, "opaque-cursor-secret")
                            } else {
                                durablePage(6, 7, cursor = "five")
                            }
                        else -> durablePage(3, 4, 5, cursor = "two")
                    }
                }
            val vm = makeVm(historyHandle(), repo)
            advanceUntilIdle()
            assertEquals(9L, repo.saved?.coverage?.highWater)
            vm.onDemandOlderHistory()
            advanceUntilIdle()
            assertEquals(listOf(""), repo.asks)
            vm.onDemandHistoryGap(2)
            advanceUntilIdle()
            assertEquals(listOf("", "seven"), repo.asks)
            assertEquals(
                1,
                repo.saved
                    ?.coverage
                    ?.gaps
                    ?.size,
            )
            vm.onDemandHistoryGap(2)
            advanceUntilIdle()
            // The refused newest cursor cannot be reused; empty is the only usable fallback.
            assertEquals(listOf("", "seven", ""), repo.asks)
            vm.onDemandHistoryGap(2)
            advanceUntilIdle()
            assertEquals(listOf("", "seven", "", "seven"), repo.asks)
            assertEquals("five", repo.saved?.coverage?.cursorFor(2))
            vm.onDemandHistoryGap(2)
            advanceUntilIdle()
            assertEquals(listOf("", "seven", "", "seven", "five"), repo.asks)
            assertTrue(
                repo.saved
                    ?.coverage
                    ?.gaps
                    ?.isEmpty() == true,
            )
            assertEquals("oldest", repo.saved?.cursor)
            assertTrue(repo.saved?.atStart == true)
            assertTrue(logs.none { it.contains("seven") || it.contains("opaque-cursor-secret") })
        }

    @Test
    fun history_legacyOverlapMovesItsMarker_butOnlyAnEmptyTerminalPageClosesIt() =
        runTest {
            lateinit var repo: HistoryRepo
            repo =
                HistoryRepo(saved = HistoryPosition("", true, HistoryCoverage(unknown = true))) { cursor ->
                    val page =
                        when (cursor) {
                            "" -> durablePage(9, 10, cursor = "eight")
                            "eight" -> durablePage(8, 9, cursor = "terminal")
                            else -> HistoryPage(emptyList(), "", true)
                        }
                    repo.messages.value = repo.messages.value.mergeCachedRows(reduceHistoryPage(page.entries, true))
                    page
                }
            repo.messages.value = reduceHistoryPage(durablePage(9, cursor = "").entries, true)
            val vm = makeVm(historyHandle(), repo)
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()
            assertEquals(
                0L,
                vm.state.value.historyMarkers
                    .single()
                    .anchor,
            )
            assertTrue(repo.saved?.coverage?.unknown == true)
            vm.onDemandHistoryGap(0)
            advanceUntilIdle()
            assertTrue(repo.saved?.coverage?.unknown == true)
            assertEquals(8L, repo.saved?.coverage?.unknownEdge)
            assertEquals(3, vm.state.value.items.size)
            vm.onDemandHistoryGap(0)
            advanceUntilIdle()
            assertEquals(listOf("", "eight", "terminal"), repo.asks)
            assertTrue(
                vm.state.value.historyMarkers
                    .isEmpty(),
            )
            assertFalse(repo.saved?.coverage?.unknown == true)
            collector.cancel()
        }

    private fun durablePage(
        vararg ids: Long,
        cursor: String,
    ) = HistoryPage(
        ids.reversed().map { id ->
            HistoryEntry(
                id,
                "send_message",
                MobileJson.parseToJsonElement("""{"conversation_id":"$ACTIVE_CONV","message_id":"m$id","text":"message $id"}"""),
                Instant.fromEpochSeconds(id),
            )
        },
        cursor,
        false,
    )

    private fun historyHandle() = SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV))

    private fun page(cursor: String) = HistoryPage(entries = emptyList(), cursor = cursor, atStart = false)

    private fun messageItem(id: String) =
        ThreadItem.MessageItem(
            Message(
                id = id,
                sessionId = "s1",
                role = Role.Assistant,
                content = "hi",
                timestamp = Instant.parse("2026-09-22T00:00:00Z"),
                isStreaming = false,
            ),
        )

    /**
     * Delegates the whole [ConversationRepository] surface to a seeded [FakeConversationRepository] and
     * overrides [requestHistory] with a caller-supplied [answer] over the asked cursor, recording every
     * cursor the walk asks with (#777). [observeMessages] is controllable too, because the fake's own
     * thread cannot be driven on demand — and because a page's entries must be shown *not* to become
     * rows, which needs the two sides separable.
     */
    private class HistoryRepo(
        private val delegate: FakeConversationRepository = FakeConversationRepository(),
        /** #1354: the position saved beside the cached rows, read once at open and replaced by every write. */
        var saved: HistoryPosition? = null,
        /** #1354: when set, the saved-position read suspends until it completes. */
        private val readGate: CompletableDeferred<Unit>? = null,
        private val answer: suspend (String) -> HistoryPage,
    ) : ConversationRepository by delegate {
        val asks = mutableListOf<String>()
        val messages = MutableStateFlow<List<ThreadItem>>(emptyList())

        /** Every position write, in order; `null` is a clear. */
        val positionWrites = mutableListOf<HistoryPosition?>()

        override suspend fun readHistoryPosition(conversationId: String): HistoryPosition? {
            readGate?.await()
            return saved
        }

        override suspend fun writeHistoryPosition(
            conversationId: String,
            position: HistoryPosition?,
        ) {
            positionWrites += position
            saved = position
        }

        override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = messages

        override suspend fun requestHistory(
            conversationId: String,
            cursor: String,
            limit: Int,
        ): HistoryPage {
            asks += cursor
            return answer(cursor)
        }
    }

    /**
     * A thread destination's handle (#789): both route arguments, as `Routes.hostArguments` supplies
     * them. The ViewModel keys its draft on the pair, so a host-less handle would not exercise it.
     */
    private fun threadHandle(
        serverId: String,
        conversationId: String,
    ): SavedStateHandle = SavedStateHandle(initialState = mapOf("serverId" to serverId, "conversationId" to conversationId))

    /** Records each sent text and never returns: a daemon reply that never arrives (#1355). */
    private class NeverRepliesRepository(
        private val delegate: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by delegate {
        val sentTexts = mutableListOf<String>()

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
        ): Message {
            sentTexts += text
            awaitCancellation()
        }
    }

    /**
     * Runs [whileSending] inside the suspend `sendMessage` call, before it returns (#789) — the user
     * typing while their send is in flight. Reads delegate to a seeded fake so `state` still assembles.
     */
    private class TypingDuringSendRepository(
        private val whileSending: () -> Unit,
        private val delegate: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by delegate {
        override suspend fun sendMessage(
            conversationId: String,
            text: String,
        ): Message {
            whileSending()
            return delegate.sendMessage(conversationId, text)
        }
    }

    // ---- #807 fixtures: the daemon's two readings, in the shapes the wire actually produces ------

    private fun runConfigHandle(): SavedStateHandle = SavedStateHandle(initialState = mapOf("conversationId" to RUN_CONFIG_CONV))

    /** A settings reading. Every field is retained exactly as a daemon would report it — nothing here is
     *  defaulted to a device value, which is the lie #807 exists to remove. */
    private fun settings(
        sessionId: String = "settings-s9",
        model: String = "",
        effort: String = "",
    ): SessionSettings =
        SessionSettings(
            sessionId = sessionId,
            model = model,
            effort = effort,
            effectiveEffort = EffectiveEffort.Unavailable,
            permissionMode = "",
            yolo = false,
            usedTokens = 0,
            windowTokens = 0,
        )

    private fun menu(
        vararg rows: ModelMenuRow,
        droppedModels: Int = 0,
    ): ModelMenu = ModelMenu(rows = rows.toList(), droppedModels = droppedModels)

    /** One published row. [value] is the argument a write sends back; [displayName] is the label. */
    private fun row(
        value: String,
        displayName: String = value,
        resolvedModel: String = "",
        effortLevels: List<String> = listOf("low", "high"),
    ): ModelMenuRow =
        ModelMenuRow(
            resolvedModel = resolvedModel,
            value = value,
            displayName = displayName,
            effortLevels = effortLevels,
            supportsAutoMode = true,
            truncatedFields = null,
        )

    private fun TestScope.makeVm(
        handle: SavedStateHandle,
        repository: ConversationRepository,
        source: ConnectionStateSource = FakeConnectionStateSource(),
        // #789: defaulted to a fresh store so every pre-existing case is unaffected; the draft cases
        // pass their own to observe it.
        draftStore: ComposerDraftStore = ComposerDraftStore(),
        liveSessionEvents: Flow<LiveSessionEvent> = emptyFlow(),
        currentModal: StateFlow<ModalUiState> = MutableStateFlow(ModalUiState.Hidden),
        answerModal: suspend (String, String, Boolean) -> Unit = { _, _, _ -> },
        cancelModal: suspend (String) -> Unit = { _ -> },
        interrupt: suspend (String) -> Unit = { },
        recordModalAction: (ModalAction) -> Unit = {},
        // #861: whether the host's live repository is published. Available from the start by default, as
        // the demo path's fake is.
        repositoryAvailable: Flow<Boolean> = flowOf(true),
        rememberModel: suspend (String) -> Unit = {},
        permissionDraftStore: PermissionDraftStore? = null,
        // #1337: the host's whole prompt list; when absent, [currentModal] stands in as a one-prompt host.
        hostModals: StateFlow<HostModalState>? = null,
    ): ThreadViewModel =
        ThreadViewModel(
            handle,
            repository,
            source,
            draftStore,
            liveSessionEvents,
            hostModals ?: currentModal.asHostModals(),
            answerModal,
            cancelModal,
            interrupt,
            repositoryAvailable = repositoryAvailable,
            recordModalAction = recordModalAction,
            rememberModel = rememberModel,
            permissionDraftStore = permissionDraftStore,
        )

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
        permissionDraftStore: PermissionDraftStore? = null,
        source: ConnectionStateSource = FakeConnectionStateSource(),
    ): ThreadViewModel =
        makeVm(
            SavedStateHandle(initialState = mapOf("conversationId" to ACTIVE_CONV, "serverId" to "host")),
            FakeConversationRepository(),
            source = source,
            currentModal = currentModal,
            answerModal = recorder.answer,
            cancelModal = recorder.cancel,
            permissionDraftStore = permissionDraftStore,
        )

    /**
     * A chat on host "host" for [conversationId], reading the host's whole prompt list (#1337). A
     * [MutableStateFlow] host also takes the chat's own prompt actions (#1340), as the coordinator's does.
     */
    private fun TestScope.hostChat(
        conversationId: String,
        host: StateFlow<HostModalState>,
        recorder: ModalSendRecorder,
        permissionDraftStore: PermissionDraftStore? = null,
        answerModal: suspend (String, String, Boolean) -> Unit = recorder.answer,
        cancelModal: suspend (String) -> Unit = recorder.cancel,
    ): ThreadViewModel =
        makeVm(
            SavedStateHandle(initialState = mapOf("conversationId" to conversationId, "serverId" to "host")),
            FakeConversationRepository(),
            answerModal = answerModal,
            cancelModal = cancelModal,
            recordModalAction = { action -> (host as? MutableStateFlow<HostModalState>)?.update { it.reduce(action) } },
            permissionDraftStore = permissionDraftStore,
            hostModals = host,
        )

    /** The daemon's `modal_shown` for [modalId] in [ACTIVE_CONV]'s chat (#1340). */
    private fun shownEvent(modalId: String): ModalEvent.Shown =
        ModalEvent.Shown(modalId, "permission", "Run command?", "do the thing", fourOptions, "reject_once", ACTIVE_CONV)

    /**
     * A one-prompt host view of a single-modal flow (#1337), so the cases written against one modal keep
     * driving it: an [ModalUiState.Open] is the host's only outstanding prompt, a [ModalUiState.Dismissed]
     * its only resolved one. Synchronous, like the coordinator's `StateFlow`, so `.value` reads stay exact.
     */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private fun StateFlow<ModalUiState>.asHostModals(): StateFlow<HostModalState> {
        val source = this
        return object : StateFlow<HostModalState> {
            override val value: HostModalState get() = source.value.asHost()
            override val replayCache: List<HostModalState> get() = listOf(value)

            override suspend fun collect(collector: FlowCollector<HostModalState>): Nothing = source.collect { collector.emit(it.asHost()) }
        }
    }

    private fun ModalUiState.asHost(): HostModalState =
        when (this) {
            is ModalUiState.Open -> HostModalState(outstanding = listOf(this))
            is ModalUiState.Dismissed -> HostModalState(resolved = listOf(this))
            ModalUiState.Hidden -> HostModalState()
        }

    /** Records the outbound interrupt calls (#458), optionally throwing [failWith] after recording to
     *  exercise the inert-swallow path. */
    private class InterruptRecorder(
        private val failWith: Throwable? = null,
    ) {
        val targets = mutableListOf<String>()

        val interrupt: suspend (String) -> Unit = { conversationId ->
            targets += conversationId
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

        /** #818: the always-allow flag of each answer, index-aligned with [answers]. */
        val grants = mutableListOf<Boolean>()

        val answer: suspend (String, String, Boolean) -> Unit = { modalId, optionId, alwaysAllow ->
            answers += modalId to optionId
            grants += alwaysAllow
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
        // #816: owned by the thread under test unless a case says otherwise.
        conversationId: String = ACTIVE_CONV,
        alwaysAllowRules: List<String> = emptyList(),
    ): ModalUiState.Open =
        ModalUiState.Open(
            modalId,
            modalClass,
            title,
            prompt,
            options,
            defaultOptionId,
            conversationId,
            alwaysAllowRules = alwaysAllowRules,
        )

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

    /**
     * One conversation for Save as channel (#957), recording each write in call order. The first
     * [promoteFailures] promotes and [promptFailures] prompt writes throw; [promoteGate] holds every
     * promote open until it completes.
     */
    private class SaveAsChannelRepo(
        name: String? = "Chat",
        private var promoteFailures: Int = 0,
        private var promptFailures: Int = 0,
        private val promoteGate: CompletableDeferred<Unit>? = null,
    ) : ConversationRepository {
        val calls = mutableListOf<String>()
        private val conversation =
            Conversation(
                id = SAVE_AS_CONV,
                name = name,
                cwd = "scratch/chat",
                currentSessionId = "$SAVE_AS_CONV-s1",
                sessionHistory = listOf("$SAVE_AS_CONV-s1"),
                isPromoted = false,
                lastUsedAt = Instant.parse("2026-05-17T00:00:00Z"),
            )

        override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> = flowOf(listOf(conversation))

        override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = flowOf(emptyList())

        override fun observeLastMessage(conversationId: String): Flow<Message?> = flowOf(null)

        override suspend fun createDiscussion(workspace: String?): Conversation = TODO("not used")

        override suspend fun promote(
            conversationId: String,
            name: String,
            workspace: String?,
        ): Conversation {
            calls += "promote:$conversationId:$name:$workspace"
            promoteGate?.await()
            if (promoteFailures > 0) {
                promoteFailures--
                throw RelayErrorException(code = "server.error", retryable = false, message = "no")
            }
            return conversation.copy(name = name, isPromoted = true)
        }

        override suspend fun setSystemPrompt(
            conversationId: String,
            systemPrompt: String?,
        ) {
            calls += "prompt:$conversationId:$systemPrompt"
            if (promptFailures > 0) {
                promptFailures--
                throw IllegalStateException("not connected")
            }
        }

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

    private class RecordingRepo : ConversationRepository {
        val archiveCalls = mutableListOf<String>()
        val deleteCalls = mutableListOf<String>()
        val renameCalls = mutableListOf<Pair<String, String>>()
        val promoteCalls = mutableListOf<Triple<String, String, String?>>()
        val startNewSessionCalls = mutableListOf<Pair<String, String?>>()

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
        ): Session {
            startNewSessionCalls += conversationId to workspace
            return Session(
                id = "$conversationId-new",
                conversationId = conversationId,
                claudeSessionUuid = "uuid",
                startedAt = Instant.parse("2026-05-17T00:00:00Z"),
                endedAt = null,
            )
        }

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
     * The [StallControllableRepo] shape for #1313: delegates to a seeded [FakeConversationRepository] and
     * overrides only [observeTurnPhase] with a controllable held phase, recording each observed id.
     */
    private class TurnPhaseControllableRepo(
        private val delegate: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by delegate {
        val phase = MutableStateFlow(LiveSessionEvent.TurnState.Phase.Idle)
        val observedIds = mutableListOf<String>()

        override fun observeTurnPhase(conversationId: String): Flow<LiveSessionEvent.TurnState.Phase> {
            observedIds += conversationId
            return phase
        }
    }

    /**
     * The [StallControllableRepo] shape for #597: delegates the whole [ConversationRepository] surface to a
     * seeded [FakeConversationRepository] and overrides only [observeCompacting] with a controllable
     * [MutableStateFlow], recording each observed id for the routing assertion.
     */
    private class CompactingControllableRepo(
        private val delegate: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by delegate {
        val compacting = MutableStateFlow(false)
        val observedIds = mutableListOf<String>()

        override fun observeCompacting(conversationId: String): Flow<Boolean> {
            observedIds += conversationId
            return compacting
        }
    }

    /** [CompactingControllableRepo]'s shape for the #871 reset-phase reading (#872); `null` is no reset. */
    private class ResettingControllableRepo(
        private val delegate: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by delegate {
        val resetting = MutableStateFlow<ResetStatus?>(null)
        val observedIds = mutableListOf<String>()

        override fun observeResetting(conversationId: String): Flow<ResetStatus?> {
            observedIds += conversationId
            return resetting
        }
    }

    /**
     * [CompactingControllableRepo]'s shape for the #801 thinking-progress reading (#803). `null` is the
     * resting value — "no reading", never "claude is not thinking" — so the double starts there.
     */
    private class ThinkingProgressControllableRepo(
        private val delegate: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by delegate {
        val reading = MutableStateFlow<ThinkingProgress?>(null)
        val observedIds = mutableListOf<String>()

        override fun observeThinkingProgress(conversationId: String): Flow<ThinkingProgress?> {
            observedIds += conversationId
            return reading
        }
    }

    /**
     * [CompactingControllableRepo]'s shape for the #802 usage-limit reading (#804). [expired] emulates the
     * projection's read-time expiry: flipping it emits nothing, and only a fresh subscription sees it.
     */
    private class UsageLimitControllableRepo(
        private val delegate: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by delegate {
        val reading = MutableStateFlow<UsageLimitReading?>(null)
        val observedIds = mutableListOf<String>()

        @Volatile var expired = false

        override fun observeUsageLimit(conversationId: String): Flow<UsageLimitReading?> {
            observedIds += conversationId
            return reading.map { it?.takeUnless { expired } }
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
     * Delegates the whole [ConversationRepository] surface to a seeded [FakeConversationRepository] (so the
     * VM's `state` pipeline stays populated) and overrides only [startNewSession] to record each call and
     * optionally run [onStart] after recording (#540) — the [QueueControllableRepo] analog for new-session.
     * [onStart] defaults to a no-op (the success path returns a placeholder [Session] the VM discards); the
     * failure test passes a throwing body and the cancellation test a suspending gate. The return is a
     * synthetic [Session] rather than a delegate mint so the (unseeded) [ACTIVE_CONV] id never trips the
     * fake's "unknown conversation" [IllegalArgumentException].
     */
    private class NewSessionControllableRepo(
        private val delegate: FakeConversationRepository = FakeConversationRepository(),
        private val onStart: suspend () -> Unit = {},
    ) : ConversationRepository by delegate {
        val startCalls = mutableListOf<Pair<String, String?>>()

        override suspend fun startNewSession(
            conversationId: String,
            workspace: String?,
        ): Session {
            startCalls += conversationId to workspace
            onStart()
            return Session(
                id = "$conversationId-new",
                conversationId = conversationId,
                claudeSessionUuid = "uuid",
                startedAt = Instant.parse("2026-07-09T00:00:00Z"),
                endedAt = null,
            )
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
     * The [GatingArchiveRepo] twin for [changeWorkspace] (#561 AC #4): signals [entered] then suspends on a
     * never-completing [gate] — so a test can cancel viewModelScope while a change_workspace one-shot is
     * in-flight and prove the [CancellationException]-first rethrow keeps teardown inert. Gates on a
     * [Session]-typed deferred so the (never-reached) return is `gate.await()`, fabricating no placeholder.
     */
    private class GatingWorkspaceRepo(
        private val gate: CompletableDeferred<Session>,
        private val entered: CompletableDeferred<Unit>,
        private val delegate: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by delegate {
        override suspend fun changeWorkspace(
            conversationId: String,
            workspace: String,
        ): Session {
            entered.complete(Unit)
            return gate.await()
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

    private fun fixedRepo(conversations: List<Conversation>): ConversationRepository = fixedRepo(flowOf(conversations))

    /** Live-emitting variant: the same stub surface over a caller-owned conversations flow. */
    private fun fixedRepo(conversations: Flow<List<Conversation>>): ConversationRepository =
        object : ConversationRepository {
            override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> = conversations

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
        /** The conversation the #807 run-configuration cases seed. A Fake-seeded channel, so
         *  `observeConversations` resolves it and the state assembles as it does in production. */
        const val RUN_CONFIG_CONV = "seed-channel-personal"

        const val ACTIVE_CONV = "thread-406-active"
        const val OTHER_CONV = "thread-1337-other"
        const val SAVE_AS_CONV = "chat-957"

        val WARNING_READING =
            UsageLimitReading(
                status = "allowed_warning",
                limitType = "seven_day",
                resetsAt = 0L,
                utilization = 0.94,
                truncatedFields = null,
            )

        /** #789: a conversation the seeded fake actually knows, so sends and resets reach it. */
        const val DRAFT_CONV = "seed-channel-personal"
    }
}
