package de.pyryco.mobile.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.HostModalState
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.repository.ConversationReadMarks
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.SessionPump
import de.pyryco.mobile.di.ConversationAttention
import de.pyryco.mobile.di.HostConversationConnection
import de.pyryco.mobile.di.HostConversationSource
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File

/** Real repository frames -> host read facts/alerts -> Android notifications, with no open thread. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AttentionNotifierSourceTest {
    @get:Rule val folder = TemporaryFolder()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    @Test
    fun peerPushAndListRefreshCancelOnlyTheirHostsPostedNotification() =
        withSource { a, b, source ->
            a.list(0u, 5u)
            b.list(0u, 5u)
            a.end("first", 5u)
            b.end("first", 5u)
            runCurrent()
            assertEquals(setOf(target("a"), target("b")), postedTargets())

            a.read(5u)
            runCurrent()
            assertEquals(setOf(target("b")), postedTargets())
            assertEquals(ConversationReadMarks(5u, 5u), source.readMarks.value["a"]?.get("same"))
            a.read(5u)
            a.end("first", 5u)
            runCurrent()
            assertEquals(setOf(target("b")), postedTargets())

            b.list(5u, 5u)
            runCurrent()
            assertTrue(postedTargets().isEmpty())
            assertEquals(
                setOf("list_conversations"),
                a.pump.sent
                    .map { it.type }
                    .toSet(),
            )
            assertEquals(
                setOf("list_conversations"),
                b.pump.sent
                    .map { it.type }
                    .toSet(),
            )
        }

    @Test
    fun readBeforeReplayCannotPostButALaterUnreadDurableCompletionDoes() =
        withSource { a, _, source ->
            a.list(5u, 5u)
            runCurrent()
            assertEquals(ConversationReadMarks(5u, 5u), source.readMarks.value["a"]?.get("same"))
            // Batch delivery deliberately permits read/event collectors to run in either order.
            a.end("already-read", 5u)
            a.read(5u)
            a.end("already-read", 5u)
            runCurrent()
            assertTrue(postedTargets().isEmpty())

            a.end("future", 6u)
            runCurrent()
            assertEquals(setOf(target("a")), postedTargets())
            a.read(6u)
            a.end("future", 6u)
            runCurrent()
            assertTrue(postedTargets().isEmpty())
        }

    @Test
    fun aReadReplayBehindANewerUnreadEntryPreservesThePromptAndTheNextUnreadCompletionPosts() =
        withSource { a, _, _ ->
            a.list(5u, 6u)
            runCurrent()
            a.prompt("outstanding")
            runCurrent()
            val prompt = shadowOf(manager).allNotifications.single()
            a.end("unseen-read-replay", 5u)
            runCurrent()
            assertEquals(prompt, shadowOf(manager).allNotifications.single())
            a.read(5u)
            a.end("unseen-read-replay", 5u)
            runCurrent()
            assertEquals(prompt, shadowOf(manager).allNotifications.single())
            a.end("subsequent-unread", 7u)
            runCurrent()
            assertEquals(setOf(target("a")), postedTargets())
            assertEquals(
                app.getString(de.pyryco.mobile.R.string.notification_turn_completed),
                shadowOf(manager)
                    .allNotifications
                    .single()
                    .extras
                    .getString(Notification.EXTRA_TEXT),
            )
        }

    @Test
    fun initialReadConfirmationCancelsAPostedReplayBehindNewerActivityOnlyOnItsHost() =
        withSource { a, b, source ->
            a.list(null, 6u)
            b.list(null, 6u)
            a.end("unseen-replay", 5u)
            b.end("unseen-replay", 5u)
            runCurrent()
            assertEquals(setOf(target("a"), target("b")), postedTargets())

            a.list(5u, 6u)
            runCurrent()
            assertEquals(ConversationReadMarks(5u, 6u), source.currentReadMarks("a", "same"))
            assertEquals(setOf(target("b")), postedTargets())
            a.read(5u)
            a.end("unseen-replay", 5u)
            runCurrent()
            assertEquals(setOf(target("b")), postedTargets())

            a.end("subsequent-unread", 7u)
            runCurrent()
            assertEquals(setOf(target("a"), target("b")), postedTargets())
        }

    @Test
    fun anAdvancingReadMarkCancelsAPostedReplayWhileTheLatestEntryRemainsUnread() =
        withSource { a, _, source ->
            a.list(4u, 6u)
            a.end("unseen-replay", 5u)
            runCurrent()
            assertEquals(setOf(target("a")), postedTargets())
            a.read(5u)
            runCurrent()
            assertEquals(ConversationReadMarks(5u, 6u), source.currentReadMarks("a", "same"))
            assertTrue(postedTargets().isEmpty())
            a.end("unseen-replay", 5u)
            a.read(5u)
            runCurrent()
            assertTrue(postedTargets().isEmpty())
        }

    @Test
    fun lateReadConfirmationPreservesAPromptThatReplacedTheCoveredCompletion() =
        withSource { a, _, source ->
            a.list(null, 6u)
            a.end("unseen-replay", 5u)
            runCurrent()
            assertEquals(setOf(target("a")), postedTargets())
            a.prompt("replacement")
            runCurrent()
            val prompt = shadowOf(manager).allNotifications.single()
            assertEquals(
                app.getString(de.pyryco.mobile.R.string.notification_prompt),
                prompt.extras.getString(Notification.EXTRA_TEXT),
            )
            a.list(5u, 6u)
            runCurrent()
            assertEquals(prompt, shadowOf(manager).allNotifications.single())
            assertEquals(ConversationAttention.WaitingForAnswer, source.attention.value["a"]?.get("same"))
            assertEquals(
                "replacement",
                a.modals.value.outstanding
                    .single()
                    .modalId,
            )
            a.end("unseen-replay", 5u)
            a.read(5u)
            runCurrent()
            assertEquals(prompt, shadowOf(manager).allNotifications.single())
        }

    @Test
    fun lateReadConfirmationPreservesANewerCompletionThatReplacedTheCoveredReplay() =
        withSource { a, _, source ->
            a.list(4u, 6u)
            a.end("unseen-replay", 5u)
            runCurrent()
            assertEquals(setOf(target("a")), postedTargets())
            a.end("newer-unread", 7u)
            runCurrent()
            val newer = shadowOf(manager).allNotifications.single()
            a.read(5u)
            runCurrent()
            assertEquals(ConversationReadMarks(5u, 7u), source.currentReadMarks("a", "same"))
            assertEquals(newer, shadowOf(manager).allNotifications.single())
            a.end("unseen-replay", 5u)
            runCurrent()
            assertEquals(newer, shadowOf(manager).allNotifications.single())
            a.read(7u)
            runCurrent()
            assertTrue(postedTargets().isEmpty())
        }

    @Test
    fun aCompletionDeliveredBeforeTheReadFactCollectorStillPostsItsNewUnreadCheckpoint() =
        withSource(eager = true) { a, _, source ->
            a.list(5u, 5u)
            runCurrent()
            assertEquals(ConversationReadMarks(5u, 5u), source.readMarks.value["a"]?.get("same"))
            // Unconfined consumers run inside tryEmit, before the inbound producer continues.
            a.end("future", 6u)
            runCurrent()
            assertEquals(ConversationReadMarks(5u, 6u), source.readMarks.value["a"]?.get("same"))
            assertEquals(setOf(target("a")), postedTargets())
        }

    @Test
    fun anInitialConfirmedListSnapshotCancelsAnAlertPostedBeforeReadSupportWasKnown() =
        withSource { a, _, _ ->
            a.end("first", 5u)
            runCurrent()
            assertEquals(setOf(target("a")), postedTargets())
            a.list(5u, 5u)
            runCurrent()
            assertTrue(postedTargets().isEmpty())
        }

    @Test
    fun readCancellationLeavesRunningAndOutstandingPromptsUntouchedAndNewPromptsDeliver() =
        withSource { a, b, source ->
            a.list(0u, 5u)
            a.end("first", 5u)
            runCurrent()
            assertEquals(setOf(target("a")), postedTargets())
            a.state("thinking")
            runCurrent()
            a.read(5u)
            runCurrent()
            assertTrue(postedTargets().isEmpty())
            assertEquals(ConversationAttention.Running, source.attention.value["a"]?.get("same"))

            a.prompt("first-prompt")
            runCurrent()
            assertEquals(setOf(target("a")), postedTargets())
            a.list(6u, 6u)
            runCurrent()
            assertTrue(postedTargets().isEmpty())
            assertEquals(ConversationAttention.WaitingForAnswer, source.attention.value["a"]?.get("same"))
            assertEquals(
                "first-prompt",
                a.modals.value.outstanding
                    .single()
                    .modalId,
            )

            a.prompt("second-prompt")
            runCurrent()
            assertEquals(setOf(target("a")), postedTargets())
            b.list(6u, 6u)
            a.read(6u)
            runCurrent()
            assertEquals(setOf(target("a")), postedTargets())
            assertEquals(
                setOf("list_conversations"),
                a.pump.sent
                    .map { it.type }
                    .toSet(),
            )
        }

    @Test
    fun anOlderDaemonAndAFreshRepositoryKeepLocalFallbackWithoutOldReadSupport() =
        withSource { a, b, source ->
            a.list(5u, 5u)
            b.list(null, null)
            runCurrent()
            b.end("legacy", null)
            runCurrent()
            source.markOpened("b", "same")
            runCurrent()
            assertEquals(setOf(target("b")), postedTargets())

            a.repositories.value = null
            runCurrent()
            assertEquals(ConversationReadMarks(5u, 5u), source.currentReadMarks("a", "same"))
            a.repositories.value = FakeConversationRepository()
            runCurrent()
            assertTrue(
                source.readMarks.value["a"]
                    .orEmpty()
                    .isEmpty(),
            )
            assertEquals(null, source.currentReadMarks("a", "same"))
            assertTrue(
                source.readMarks.value["b"]
                    .orEmpty()
                    .isEmpty(),
            )
            a.read(9u)
            runCurrent()
            assertTrue(
                source.readMarks.value["a"]
                    .orEmpty()
                    .isEmpty(),
            )
        }

    private fun postedTargets() =
        shadowOf(manager)
            .allNotifications
            .map {
                NotificationTap.target(shadowOf(it.contentIntent).savedIntent)
            }.toSet()

    private fun target(host: String) = HostConversationTarget(host, "same")

    private fun withSource(
        eager: Boolean = false,
        block: suspend TestScope.(Host, Host, HostConversationSource) -> Unit,
    ) = runTest {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val dispatcher = if (eager) UnconfinedTestDispatcher(testScheduler) else StandardTestDispatcher(testScheduler)
        val aPump = Pump()
        val a =
            Host(
                "a",
                RemoteConversationRepository(aPump, backgroundScope, negotiatedCapabilities = { setOf(CAPABILITY_INTERACTIVE) }),
                aPump,
            )
        val bPump = Pump()
        val b =
            Host(
                "b",
                RemoteConversationRepository(bPump, backgroundScope, negotiatedCapabilities = { setOf(CAPABILITY_INTERACTIVE) }),
                bPump,
            )
        val source = HostConversationSource(MutableStateFlow(listOf(a.connection, b.connection)), { null }, dispatcher)
        val notifier =
            AttentionNotifier(
                context = app,
                alerts = source.alerts,
                notificationsEnabled = MutableStateFlow(true),
                isMuted = { host, id -> source.snapshots.value.isMuted(host, id) },
                agentOf = { host, id -> source.snapshots.value.agentOf(host, id) },
                nameOf = { host, id -> source.snapshots.value.nameOf(host, id) },
                isForeground = { false },
                ledgerFile = File(folder.root, "alerts"),
                dispatcher = dispatcher,
                readMarks = source.readMarks,
                readMarksOf = source::currentReadMarks,
            )
        try {
            runCurrent()
            block(a, b, source)
        } finally {
            notifier.dispose()
            source.dispose()
            assertTrue(source.readMarks.value.isEmpty())
        }
    }

    private class Host(
        id: String,
        repository: RemoteConversationRepository,
        val pump: Pump,
    ) {
        val repositories = MutableStateFlow<ConversationRepository?>(repository)
        val modals = MutableStateFlow(HostModalState())
        val connection =
            HostConversationConnection(
                id,
                null,
                repositories,
                MutableStateFlow(ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)),
                repository.liveSessionEvents,
                modals,
            )

        fun list(
            read: ULong?,
            latest: ULong?,
        ) {
            val fields = listOfNotNull(read?.let { "\"read_up_to\":$it" }, latest?.let { "\"latest_entry_id\":$it" })
            val extra = if (fields.isEmpty()) "" else "," + fields.joinToString(",")
            pump.push(
                "conversations",
                """{"conversations":[{"id":"same","name":"test","is_promoted":true,"cwd":"/test","last_message_ts":"$TS","last_used_at":"$TS"$extra}]}""",
            )
        }

        fun read(mark: ULong) =
            pump.push(
                "conversation_updated",
                """{"id":"same","name":"test","is_promoted":true,"cwd":"/test","last_used_at":"$TS","read_up_to":$mark}""",
            )

        fun end(
            turn: String,
            entry: ULong?,
        ) = pump.push("turn_end", """{"conversation_id":"same","turn_id":"$turn","stop_reason":"end_turn"}""", entry)

        fun state(phase: String) = pump.push("turn_state", """{"conversation_id":"same","state":"$phase"}""")

        fun prompt(id: String) {
            modals.value = HostModalState(listOf(ModalUiState.Open(id, "permission", "t", "p", emptyList(), "deny", "same")))
        }
    }

    private class Pump : SessionPump {
        private val input = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound = input.receiveAsFlow()
        val sent = mutableListOf<Envelope>()
        private var id = 1L

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            return true
        }

        fun push(
            type: String,
            payload: String,
            entry: ULong? = null,
        ) {
            input.trySend(Envelope(id++, type, TS, MobileJson.parseToJsonElement(payload), historyEntryId = entry))
        }
    }

    private companion object {
        const val TS = "2026-10-08T00:00:00Z"
    }
}
