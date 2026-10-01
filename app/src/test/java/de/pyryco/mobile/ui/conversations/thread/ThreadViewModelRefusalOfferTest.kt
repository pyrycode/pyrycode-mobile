package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.SetSessionSettingsPayloadDto
import de.pyryco.mobile.data.repository.AnnouncedModel
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.LiveRefusalEvent
import de.pyryco.mobile.data.repository.SessionSettings
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.SwitchBackOffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The switch-back offer (#1360), after desktop's `reduceRefusalOffer`, `subscribeRefusalRecovery` and
 * `switchBack`: what arms it, what one tap sends, and what ends it. Live refusals arrive through
 * [ConversationRepository.observeLiveRefusalEvents]; rows never arm it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelRefusalOfferTest {
    private val logs = mutableListOf<String>()
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled
    private val source = FakeConnectionStateSource()
    private val repo = RecordingRepo()

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

    // ---- arming -----------------------------------------------------------------------------------

    @Test
    fun aLiveSessionScopedFallback_armsAnOfferForItsRow() =
        runTest {
            val vm = collectedVm()

            repo.refusals.emit(refused(ORIGINAL, FALLBACK, T1))

            assertEquals(SwitchBackOffer(T1, ORIGINAL, pending = false, failed = false), vm.switchBackOffer.value)
            assertTrue(vm.switchBackOffer.value!!.armedBy(row(ORIGINAL, FALLBACK, T1)))
            assertFalse(vm.switchBackOffer.value!!.armedBy(row(ORIGINAL, null, T1)))
            assertFalse(vm.switchBackOffer.value!!.armedBy(row(ORIGINAL, FALLBACK, T2)))
            assertTrue("event=refusal_offer outcome=armed" in logs)
            assertTrue("model names never reach a log line", logs.none { ORIGINAL in it || FALLBACK in it })
        }

    @Test
    fun onlyScopeExactlySessionWithBothModels_arms() =
        runTest {
            val vm = collectedVm()

            for (event in listOf(
                refused(ORIGINAL, FALLBACK, T1, scope = "local"),
                refused(ORIGINAL, FALLBACK, T1, scope = "Session"),
                refused(ORIGINAL, FALLBACK, T1, scope = " session"),
                refused("", FALLBACK, T1),
                refused(ORIGINAL, "", T1),
            )) {
                repo.refusals.emit(event)
                assertNull("$event must not arm", vm.switchBackOffer.value)
            }
        }

    @Test
    fun aNewerUnqualifiedFallback_clears_andANoFallbackLeavesTheOffer() =
        runTest {
            val vm = collectedVm()
            repo.refusals.emit(refused(ORIGINAL, FALLBACK, T1))

            repo.refusals.emit(LiveRefusalEvent.Refused(row("claude-x", null, T2), scope = null))
            assertEquals(T1, vm.switchBackOffer.value?.occurredAt)

            repo.refusals.emit(refused(ORIGINAL, FALLBACK, T3, scope = "local"))
            assertNull(vm.switchBackOffer.value)
            assertTrue("event=refusal_offer outcome=cleared reason=unqualified" in logs)
        }

    @Test
    fun aNewerQualifyingFallback_movesTheOfferToItsRow() =
        runTest {
            val vm = collectedVm()
            repo.refusals.emit(refused(ORIGINAL, FALLBACK, T1))

            repo.refusals.emit(refused("claude-haiku-4-5", FALLBACK, T2))

            assertEquals(SwitchBackOffer(T2, "claude-haiku-4-5", pending = false, failed = false), vm.switchBackOffer.value)
        }

    @Test
    fun refusalRowsInTheThread_neverArm() =
        runTest {
            repo.rows.value = listOf(row(ORIGINAL, FALLBACK, T1))
            val vm = collectedVm()

            assertTrue(
                vm.state.value.items
                    .any { it is ThreadItem.ModelRefusal },
            )
            assertNull(vm.switchBackOffer.value)
        }

    // ---- tap --------------------------------------------------------------------------------------

    @Test
    fun aTap_sendsTheOriginalModelVerbatimToTheReadingsSession_evenWhenTheReadingNamesIt() =
        runTest {
            repo.readings.value = reading(model = ORIGINAL)
            val vm = collectedVm()
            repo.refusals.emit(refused(ORIGINAL, FALLBACK, T1))

            vm.onSwitchBack()
            runCurrent()

            assertEquals(listOf(SetSessionSettingsPayloadDto(SESSION, model = ORIGINAL)), repo.settings)
            assertTrue("event=refusal_switch_back outcome=sent" in logs)
        }

    @Test
    fun whileTheWriteIsOutstanding_theOfferIsPending_andASecondTapSendsNothing() =
        runTest {
            val vm = collectedVm()
            repo.refusals.emit(refused(ORIGINAL, FALLBACK, T1))
            val ack = CompletableDeferred<Unit>()
            repo.hold = ack

            vm.onSwitchBack()
            runCurrent()
            assertTrue(vm.switchBackOffer.value!!.pending)

            vm.onSwitchBack()
            runCurrent()
            assertEquals(1, repo.settings.size)
            assertTrue("event=refusal_switch_back outcome=skipped reason=pending" in logs)

            ack.complete(Unit)
            runCurrent()
            assertNull("an acknowledged write ends the offer", vm.switchBackOffer.value)
            assertEquals(1, repo.refreshes)
        }

    @Test
    fun aPendingMenuWrite_disablesTheTap() =
        runTest {
            val vm = collectedVm()
            repo.hold = CompletableDeferred()
            vm.onModelSelected("haiku")
            runCurrent()
            repo.refusals.emit(refused(ORIGINAL, FALLBACK, T1))

            assertTrue(vm.switchBackOffer.value!!.pending)
            vm.onSwitchBack()
            runCurrent()

            assertEquals(listOf(SetSessionSettingsPayloadDto(SESSION, model = "haiku")), repo.settings)
        }

    @Test
    fun aTap_sendsNothing_offlineOrWithoutASessionOrWithoutAnOffer() =
        runTest {
            val vm = collectedVm()
            vm.onSwitchBack()
            runCurrent()
            assertTrue("no offer", repo.settings.isEmpty())

            repo.refusals.emit(refused(ORIGINAL, FALLBACK, T1))
            source.emit(ConnectionState.Offline)
            vm.onSwitchBack()
            runCurrent()
            assertTrue("offline", repo.settings.isEmpty())

            source.emit(ConnectionState.Connected)
            repo.readings.value = reading(sessionId = "")
            runCurrent()
            vm.onSwitchBack()
            runCurrent()
            assertTrue("no session", repo.settings.isEmpty())
            assertEquals(SwitchBackOffer(T1, ORIGINAL, pending = false, failed = false), vm.switchBackOffer.value)
        }

    @Test
    fun aRefusedOrFailedWrite_keepsTheOfferMarkedFailed_untilTheNextTap() =
        runTest {
            val vm = collectedVm()
            repo.refusals.emit(refused(ORIGINAL, FALLBACK, T1))

            repo.failWith = RelayErrorException("protocol.malformed", false, "requested model is not offered")
            vm.onSwitchBack()
            runCurrent()
            assertEquals(SwitchBackOffer(T1, ORIGINAL, pending = false, failed = true), vm.switchBackOffer.value)
            assertTrue("event=refusal_switch_back outcome=failed" in logs)

            repo.failWith = IllegalStateException("not connected")
            repo.hold = CompletableDeferred()
            vm.onSwitchBack()
            runCurrent()
            assertEquals(
                "the next tap clears the retry message",
                SwitchBackOffer(T1, ORIGINAL, pending = true, failed = false),
                vm.switchBackOffer.value,
            )
            repo.hold!!.complete(Unit)
            runCurrent()
            assertEquals(SwitchBackOffer(T1, ORIGINAL, pending = false, failed = true), vm.switchBackOffer.value)
            assertEquals(2, repo.settings.size)
        }

    @Test
    fun anAckForAnOlderOffer_leavesANewerOneArmed() =
        runTest {
            val vm = collectedVm()
            repo.refusals.emit(refused(ORIGINAL, FALLBACK, T1))
            val ack = CompletableDeferred<Unit>()
            repo.hold = ack
            vm.onSwitchBack()
            runCurrent()

            repo.refusals.emit(refused("claude-haiku-4-5", FALLBACK, T2))
            ack.complete(Unit)
            runCurrent()

            assertEquals(T2, vm.switchBackOffer.value?.occurredAt)
        }

    // ---- lifetime ---------------------------------------------------------------------------------

    @Test
    fun anAnnouncedModelOtherThanTheFallback_clears_theFallbackOrARepeatDoesNot() =
        runTest {
            repo.announced.value = AnnouncedModel(ORIGINAL, truncated = false)
            val vm = collectedVm()
            repo.refusals.emit(refused(ORIGINAL, FALLBACK, T1))

            repo.announced.value = AnnouncedModel(FALLBACK, truncated = false)
            runCurrent()
            assertEquals(T1, vm.switchBackOffer.value?.occurredAt)

            // A held reading handed over again on a reconnect is not a new announcement.
            repo.announced.value = null
            repo.announced.value = AnnouncedModel(FALLBACK, truncated = false)
            runCurrent()
            assertEquals(T1, vm.switchBackOffer.value?.occurredAt)

            repo.announced.value = AnnouncedModel(ORIGINAL, truncated = false)
            runCurrent()
            assertNull(vm.switchBackOffer.value)
            assertTrue("event=refusal_offer outcome=cleared reason=announced" in logs)
        }

    @Test
    fun aSessionTransition_clears() =
        runTest {
            val vm = collectedVm()
            repo.refusals.emit(refused(ORIGINAL, FALLBACK, T1))

            repo.refusals.emit(LiveRefusalEvent.SessionReplaced)

            assertNull(vm.switchBackOffer.value)
            assertTrue("event=refusal_offer outcome=cleared reason=session" in logs)
        }

    @Test
    fun aModelWriteFromTheMenu_clears() =
        runTest {
            val vm = collectedVm()
            repo.refusals.emit(refused(ORIGINAL, FALLBACK, T1))

            vm.onModelSelected("haiku")
            runCurrent()

            assertNull(vm.switchBackOffer.value)
            assertTrue("event=refusal_offer outcome=cleared reason=menu" in logs)
        }

    @Test
    fun aReconnect_keepsTheOffer_andAbandonsAPendingWrite() =
        runTest {
            val vm = collectedVm()
            repo.refusals.emit(refused(ORIGINAL, FALLBACK, T1))
            repo.hold = CompletableDeferred()
            vm.onSwitchBack()
            runCurrent()
            assertTrue(vm.switchBackOffer.value!!.pending)

            source.emit(ConnectionState.Reconnecting(secondsRemaining = 3))
            repo.readings.value = reading().copy(held = true)
            source.emit(ConnectionState.Connected)
            repo.readings.value = reading()
            runCurrent()

            assertEquals(SwitchBackOffer(T1, ORIGINAL, pending = false, failed = false), vm.switchBackOffer.value)
        }

    // ---- fixtures -------------------------------------------------------------------------------

    private fun TestScope.collectedVm(): ThreadViewModel {
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("serverId" to SERVER, "conversationId" to CONV)),
                repo,
                source,
                ComposerDraftStore(),
            )
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return vm
    }

    private fun row(
        original: String,
        fallback: String?,
        at: Instant,
    ) = ThreadItem.ModelRefusal(original, fallback, "Retried.", bannerTruncated = false, occurredAt = at)

    private fun refused(
        original: String,
        fallback: String,
        at: Instant,
        scope: String = "session",
    ) = LiveRefusalEvent.Refused(row(original, fallback, at), scope)

    private fun reading(
        sessionId: String = SESSION,
        model: String = "",
    ) = SessionSettings(
        sessionId = sessionId,
        model = model,
        effort = "",
        effectiveEffort = EffectiveEffort.Unavailable,
        permissionMode = "default",
        yolo = false,
        usedTokens = 0,
        windowTokens = 0,
    )

    private inner class RecordingRepo(
        val fake: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by fake {
        val readings = MutableStateFlow<SessionSettings?>(reading())
        val announced = MutableStateFlow<AnnouncedModel?>(null)
        val refusals = MutableSharedFlow<LiveRefusalEvent>()
        val rows = MutableStateFlow<List<ThreadItem>>(emptyList())
        val settings = mutableListOf<SetSessionSettingsPayloadDto>()
        var hold: CompletableDeferred<Unit>? = null
        var failWith: Exception? = null
        var refreshes = 0

        override fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> = readings

        override fun observeAnnouncedModel(conversationId: String): Flow<AnnouncedModel?> = announced

        override fun observeLiveRefusalEvents(conversationId: String): Flow<LiveRefusalEvent> = refusals

        override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> = rows

        override fun refreshSessionSettings(conversationId: String) {
            refreshes++
        }

        override suspend fun setSessionSettings(
            sessionId: String,
            model: String?,
            effort: String?,
            yolo: Boolean?,
            permissionMode: String?,
        ) {
            settings += SetSessionSettingsPayloadDto(sessionId, model, effort, yolo, permissionMode)
            hold?.await()
            failWith?.let { throw it }
        }
    }

    private companion object {
        const val SERVER = "host-a"
        const val CONV = "seed-channel-personal"
        const val SESSION = "sess-a"
        const val ORIGINAL = "claude-opus-5-5"
        const val FALLBACK = "claude-sonnet-5"
        val T1: Instant = Instant.parse("2026-09-23T10:00:00Z")
        val T2: Instant = Instant.parse("2026-09-23T10:00:05Z")
        val T3: Instant = Instant.parse("2026-09-23T10:00:09Z")
    }
}
