package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.di.AttentionAlert
import de.pyryco.mobile.di.ConversationAttention
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadAttentionTest {
    private val previousSink = RelayLog.sink
    private val logs = mutableListOf<String>()

    @Before fun captureLogs() {
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After fun restoreLogs() {
        RelayLog.sink = previousSink
    }

    private val current = HostConversationTarget("a", "same")
    private val other = HostConversationTarget("b", "same")
    private val third = HostConversationTarget("a", "third")
    private val snapshots =
        MutableStateFlow(listOf(host("a", row("same", "Current"), row("third", "Third")), host("b", row("same", "Other"))))
    private val attention = MutableStateFlow<Map<String, Map<String, ConversationAttention>>>(emptyMap())
    private val alerts = MutableSharedFlow<AttentionAlert>(extraBufferCapacity = 10)
    private var shown: ThreadAttention? = null

    private fun TestScope.start() =
        backgroundScope.launch {
            observeThreadAttention(current, snapshots, attention, alerts).collect {
                shown =
                    it
            }
        }

    private fun waiting(vararg targets: HostConversationTarget) {
        attention.value =
            targets.groupBy { it.serverId }.mapValues { (_, rows) ->
                rows.associate {
                    it.conversationId to
                        ConversationAttention.WaitingForAnswer
                }
            }
    }

    private fun finish(
        target: HostConversationTarget,
        key: String = "turn",
    ) {
        alerts.tryEmit(AttentionAlert(target.serverId, target.conversationId, AttentionAlert.Kind.TurnCompleted, key))
    }

    private fun updateOther(
        name: String? = "Other",
        muted: Boolean = false,
    ) {
        snapshots.value =
            snapshots.value.map {
                if (it.serverId ==
                    other.serverId
                ) {
                    it.copy(chats = listOf(row(other.conversationId, name, muted)))
                } else {
                    it
                }
            }
    }

    @Test fun waitingAggregatesAcrossHosts_excludesOnlyCurrentPair_andTracksNamesAndMute() =
        runTest {
            waiting(current, other)
            start()
            runCurrent()
            assertEquals(ThreadAttention(1, other, "Other"), shown)
            updateOther("Renamed")
            runCurrent()
            assertEquals("Renamed", shown?.name)
            waiting(current, other, third)
            runCurrent()
            assertEquals(ThreadAttention(2), shown)
            updateOther(muted = true)
            runCurrent()
            assertEquals(ThreadAttention(1, third, "Third"), shown)
            waiting(current)
            runCurrent()
            assertNull(shown)
            updateOther(muted = false)
            waiting(other)
            runCurrent()
            assertEquals(other, shown?.target)
        }

    @Test fun waitingWithMissingOrEmptyName_keepsTheTargetForLocalFallback() =
        runTest {
            updateOther(null)
            waiting(other)
            start()
            runCurrent()
            assertEquals(ThreadAttention(1, other, null), shown)
            updateOther("\u0000\n")
            runCurrent()
            assertEquals(ThreadAttention(1, other, null), shown)
        }

    @Test fun finishesExpireExactlyAtFiveSeconds_andReplacementCancelsOldTimer() =
        runTest {
            start()
            runCurrent()
            finish(other)
            runCurrent()
            assertEquals(ThreadAttention(0, other, "Other"), shown)
            advanceTimeBy(4_999)
            runCurrent()
            assertEquals(other, shown?.target)
            finish(third, "new")
            runCurrent()
            advanceTimeBy(1)
            runCurrent()
            assertEquals(third, shown?.target)
            advanceTimeBy(4_998)
            runCurrent()
            assertEquals(third, shown?.target)
            advanceTimeBy(1)
            runCurrent()
            assertNull(shown)
        }

    @Test fun anotherFinishForSameConversation_restartsExpiry() =
        runTest {
            start()
            runCurrent()
            finish(other, "first")
            runCurrent()
            advanceTimeBy(4_000)
            finish(other, "second")
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(other, shown?.target)
            advanceTimeBy(4_000)
            runCurrent()
            assertNull(shown)
        }

    @Test fun waitingClearsFinish_suppressesNewFinishes_andNeverReplaysThem() =
        runTest {
            start()
            runCurrent()
            finish(other)
            runCurrent()
            waiting(third)
            runCurrent()
            assertEquals(ThreadAttention(1, third, "Third"), shown)
            finish(other, "discarded")
            runCurrent()
            waiting()
            runCurrent()
            assertNull(shown)
            advanceTimeBy(10_000)
            runCurrent()
            assertNull(shown)
        }

    @Test fun alertReadsCurrentWaitingBeforeItsProjectionCollectorRuns() =
        runTest {
            start()
            runCurrent()
            waiting(third)
            finish(other)
            runCurrent()
            assertEquals(ThreadAttention(1, third, "Third"), shown)
            waiting()
            runCurrent()
            assertNull(shown)
        }

    @Test fun muteClearsVisibleFinish_andUnmuteDoesNotResurrectIt() =
        runTest {
            start()
            runCurrent()
            finish(other)
            runCurrent()
            updateOther("New name")
            runCurrent()
            assertEquals("New name", shown?.name)
            updateOther(muted = true)
            runCurrent()
            assertNull(shown)
            updateOther(muted = false)
            runCurrent()
            assertNull(shown)
        }

    @Test fun currentMutedPromptAndUnreadEventsCannotCreateFinished() =
        runTest {
            start()
            runCurrent()
            finish(current)
            alerts.tryEmit(AttentionAlert(other.serverId, other.conversationId, AttentionAlert.Kind.Prompt, "modal"))
            attention.value = mapOf(other.serverId to mapOf(other.conversationId to ConversationAttention.Unread))
            runCurrent()
            assertNull(shown)
            updateOther(muted = true)
            finish(other)
            runCurrent()
            assertNull(shown)
            updateOther(muted = false)
            finish(other)
            runCurrent()
            assertEquals(other, shown?.target)
        }

    @Test fun cancelledCollectorCannotReplayOldOrBackgroundFinishes_butReReadsWaiting() =
        runTest {
            val first = start()
            runCurrent()
            finish(other)
            runCurrent()
            first.cancel()
            runCurrent()
            finish(third, "background")
            start()
            runCurrent()
            assertNull(shown)
            waiting(other)
            runCurrent()
            assertEquals(other, shown?.target)
        }

    @Test fun unknownConversationUsesNullNameAndUnknownMutePolicy() =
        runTest {
            val missing = HostConversationTarget("unknown", "id")
            waiting(missing)
            start()
            runCurrent()
            assertEquals(ThreadAttention(1, missing, null), shown)
            waiting()
            finish(missing)
            runCurrent()
            assertEquals(ThreadAttention(0, missing, null), shown)
        }

    @Test fun finishAtOldExpiryKeepsNewTimer_andWaitingAtExpiryKeepsWaiting() =
        runTest {
            start()
            runCurrent()
            finish(other)
            runCurrent()
            advanceTimeBy(5_000)
            finish(third, "new-at-expiry")
            runCurrent()
            assertEquals(third, shown?.target)
            advanceTimeBy(5_000)
            waiting(other)
            runCurrent()
            assertEquals(ThreadAttention(1, other, "Other"), shown)
        }

    @Test fun lifecycleAndFilterLogsContainOnlyStaticCodesAndCounts() =
        runTest {
            val job = start()
            runCurrent()
            updateOther("SECRET-NAME")
            finish(other, "SECRET-TURN")
            runCurrent()
            waiting(third)
            runCurrent()
            finish(other, "SECRET-SECOND-TURN")
            runCurrent()
            job.cancel()
            runCurrent()
            assertTrue(logs.isNotEmpty())
            assertTrue(logs.all { it.matches(Regex("event=thread_attention_[a-z_]+( reason=[a-z_]+| count=[0-9]+)?")) })
            assertTrue(logs.none { "SECRET" in it || "serverId" in it || "conversationId" in it })
        }

    private fun host(
        id: String,
        vararg rows: Conversation,
    ) = HostConversationSnapshot(id, id, ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected), chats = rows.toList())

    private fun row(
        id: String,
        name: String?,
        muted: Boolean = false,
    ) = Conversation(id, name, "/scratch", "session", emptyList(), false, Instant.fromEpochSeconds(0), muted = muted)
}
