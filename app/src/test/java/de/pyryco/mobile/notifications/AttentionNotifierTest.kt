package de.pyryco.mobile.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.MainActivity
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.di.AttentionAlert
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.xmlpull.v1.XmlPullParser
import java.io.File
import kotlin.math.roundToInt

/**
 * The alert publisher (#685): dedupe first, then the foreground, preference and permission gates, then
 * one fixed-copy notification per conversation per host whose tap opens only that thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AttentionNotifierTest {
    @get:Rule val folder = TemporaryFolder()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private val alerts = MutableSharedFlow<AttentionAlert>(extraBufferCapacity = 16)
    private val enabled = MutableStateFlow(true)
    private var foreground = false
    private val muted = mutableSetOf<Pair<String, String>>()
    private val agents = mutableMapOf(("host-a" to "conv") to ConversationAgent.Claude, ("host-b" to "conv") to ConversationAgent.Claude)
    private val names = mutableMapOf<Pair<String, String>, String>()
    private lateinit var ledger: File

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ledger = File(folder.root, "attention_alerts")
    }

    @Test
    fun aBackgroundedTurnPostsOneFixedCopyNotificationWhoseTapOpensThatThread() =
        withNotifier {
            alerts.emit(TURN)

            val notification = posted().single()
            assertEquals(app.getString(R.string.notification_turn_completed), notification.extras.getString(Notification.EXTRA_TEXT))
            assertEquals("Pyrycode", notification.extras.getString(Notification.EXTRA_TITLE))
            assertEquals(ATTENTION_CHANNEL_ID, notification.channelId)
            assertNotNull(manager.getNotificationChannel(ATTENTION_CHANNEL_ID))
            val tap = notification.contentIntent
            assertTrue(tap.isImmutable)
            val intent = shadowOf(tap).savedIntent
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            assertEquals(HostConversationTarget("host-a", "conv"), NotificationTap.target(intent))
        }

    @Test
    fun theSmallIconIsTheSquareSingleColourNotificationMark() =
        withNotifier {
            alerts.emit(TURN)

            assertEquals(R.drawable.ic_notification, posted().single().smallIcon.resId)
            val icon = checkNotNull(ContextCompat.getDrawable(app, R.drawable.ic_notification))
            val px = (24 * app.resources.displayMetrics.density).roundToInt()
            assertEquals(px to px, icon.intrinsicWidth to icon.intrinsicHeight)
            val colours = mutableListOf<String>()
            app.resources.getXml(R.drawable.ic_notification).use { xml ->
                while (xml.next() != XmlPullParser.END_DOCUMENT) {
                    if (xml.eventType != XmlPullParser.START_TAG) continue
                    for (i in 0 until xml.attributeCount) {
                        if (xml.getAttributeName(i).endsWith("Color")) colours += xml.getAttributeValue(i).lowercase()
                    }
                }
            }
            assertEquals(listOf("#ffffffff"), colours.distinct())
        }

    @Test
    fun aPromptUsesItsOwnFixedCopyAndNeverDaemonText() =
        withNotifier {
            alerts.emit(AttentionAlert("host-a", "conv", AttentionAlert.Kind.Prompt, "modal:rm -rf ~"))

            val text = posted().single().extras
            assertEquals(app.getString(R.string.notification_prompt), text.getString(Notification.EXTRA_TEXT))
            assertTrue(text.keySet().none { key -> text.get(key)?.toString()?.contains("rm -rf") == true })
        }

    @Test
    fun nothingIsPostedInTheForegroundWhenDisabledOrWithoutPermission() =
        withNotifier {
            foreground = true
            alerts.emit(TURN.copy(key = "t1"))
            foreground = false
            enabled.value = false
            alerts.emit(TURN.copy(key = "t2"))
            enabled.value = true
            shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            alerts.emit(TURN.copy(key = "t3"))

            assertEquals(emptyList<Notification>(), posted())
        }

    @Test
    fun aMutedConversationPostsNothingForATurnOrAPrompt() =
        withNotifier {
            muted += "host-a" to "conv"
            alerts.emit(TURN)
            alerts.emit(AttentionAlert("host-a", "conv", AttentionAlert.Kind.Prompt, "modal:m"))

            assertEquals(emptyList<Notification>(), posted())
        }

    @Test
    fun aMutedAlertIsSpentAndNeverPostsAfterUnmuting() =
        withNotifier {
            muted += "host-a" to "conv"
            alerts.emit(TURN)
            muted.clear()
            alerts.emit(TURN)

            assertEquals(emptyList<Notification>(), posted())
        }

    @Test
    fun theSameConversationIdUnmutedOnAnotherHostStillAlerts() =
        withNotifier {
            muted += "host-a" to "conv"
            alerts.emit(TURN)
            alerts.emit(TURN.copy(serverId = "host-b"))

            val target = NotificationTap.target(shadowOf(posted().single().contentIntent).savedIntent)
            assertEquals(HostConversationTarget("host-b", "conv"), target)
        }

    @Test
    fun theMuteLookupReadsOnlyTheAlertsOwnHostAndFailsOpen() {
        val hosts =
            listOf(
                host("host-a", channels = listOf(row("chan", muted = true)), chats = listOf(row("chat", muted = true), row("loud"))),
                host("host-b", chats = listOf(row("conv"))),
            )

        assertTrue(hosts.isMuted("host-a", "chan"))
        assertTrue(hosts.isMuted("host-a", "chat"))
        assertFalse(hosts.isMuted("host-a", "loud"))
        assertFalse(hosts.isMuted("host-a", "missing"))
        assertFalse(hosts.isMuted("host-b", "chan"))
        assertFalse(hosts.isMuted("host-c", "chan"))
        assertFalse(emptyList<HostConversationSnapshot>().isMuted("host-a", "chan"))
    }

    @Test
    fun aCodexConversationsAlertsNameCodex() =
        withNotifier {
            agents["host-a" to "conv"] = ConversationAgent.Codex
            alerts.emit(TURN)
            assertEquals(
                app.getString(R.string.notification_turn_completed_codex),
                posted().single().extras.getString(Notification.EXTRA_TEXT),
            )
            alerts.emit(AttentionAlert("host-a", "conv", AttentionAlert.Kind.Prompt, "batch:q"))
            assertEquals(app.getString(R.string.notification_prompt_codex), posted().single().extras.getString(Notification.EXTRA_TEXT))
            assertEquals("Codex finished a reply", app.getString(R.string.notification_turn_completed_codex))
            assertEquals("Codex is waiting for your answer", app.getString(R.string.notification_prompt_codex))
        }

    @Test
    fun anAlertForAConversationMissingFromTheHostsListReadsNeutrally() =
        withNotifier {
            agents.clear()
            alerts.emit(TURN)
            assertEquals("A reply finished", posted().single().extras.getString(Notification.EXTRA_TEXT))
            alerts.emit(AttentionAlert("host-a", "conv", AttentionAlert.Kind.Prompt, "batch:q"))
            assertEquals("An answer is needed", posted().single().extras.getString(Notification.EXTRA_TEXT))
        }

    @Test
    fun aClaudeConversationReadsAsBeforeAndTheChannelDescriptionIsNeutral() =
        withNotifier {
            alerts.emit(TURN)
            assertEquals("claude finished a reply", posted().single().extras.getString(Notification.EXTRA_TEXT))
            assertEquals("claude is waiting for your answer", app.getString(R.string.notification_prompt))
            assertEquals(
                "When a reply finishes or an answer is needed",
                manager.getNotificationChannel(ATTENTION_CHANNEL_ID).description,
            )
        }

    @Test
    fun theAgentLookupReadsOnlyTheAlertsOwnHostAndIsNullWhenMissing() {
        val hosts =
            listOf(
                host(
                    "host-a",
                    channels = listOf(row("chan", agent = ConversationAgent.Codex)),
                    chats = listOf(row("chat", agent = ConversationAgent.Codex), row("claude")),
                ),
                host("host-b", chats = listOf(row("conv"))),
            )

        assertEquals(ConversationAgent.Codex, hosts.agentOf("host-a", "chan"))
        assertEquals(ConversationAgent.Codex, hosts.agentOf("host-a", "chat"))
        assertEquals(ConversationAgent.Claude, hosts.agentOf("host-a", "claude"))
        assertNull(hosts.agentOf("host-a", "missing"))
        assertNull(hosts.agentOf("host-b", "chan"))
        assertNull(hosts.agentOf("host-c", "chan"))
    }

    @Test
    fun aNamedConversationsAlertIsTitledWithItsOwnHostsNameAndKeepsItsBody() =
        withNotifier {
            names["host-a" to "conv"] = "Release\u0007 notes"
            names["host-b" to "conv"] = "Other host"
            alerts.emit(TURN)
            val a = posted().single().extras
            assertEquals("Release notes", a.getString(Notification.EXTRA_TITLE))
            assertEquals(app.getString(R.string.notification_turn_completed), a.getString(Notification.EXTRA_TEXT))
            manager.cancelAll()

            alerts.emit(TURN.copy(serverId = "host-b"))
            assertEquals("Other host", posted().single().extras.getString(Notification.EXTRA_TITLE))
        }

    @Test
    fun anUnnamedOrBlankNamedConversationIsTitledWithTheAppName() =
        withNotifier {
            names["host-a" to "conv"] = " \u0000\n\u009f "
            alerts.emit(TURN)
            assertEquals(app.getString(R.string.app_name), posted().single().extras.getString(Notification.EXTRA_TITLE))
            names.clear()
            alerts.emit(TURN.copy(key = "t2"))
            assertEquals(app.getString(R.string.app_name), posted().single().extras.getString(Notification.EXTRA_TITLE))
        }

    @Test
    fun theTitleDropsControlsCapsAt80CodePointsAndNeverSplitsASurrogatePair() {
        assertNull(notificationTitle(null))
        assertNull(notificationTitle(""))
        assertNull(notificationTitle(" \u0001\u007f\t "))
        assertEquals("ab c", notificationTitle("  a\u0000b\u001b c\r\n"))
        assertEquals("x".repeat(80), notificationTitle("x".repeat(81)))
        // Dropped controls do not count toward the cap.
        assertEquals("x".repeat(80), notificationTitle("\u0001".repeat(10) + "x".repeat(90)))
        val emoji = "😀"
        assertEquals(emoji.repeat(80), notificationTitle(emoji.repeat(81)))
        assertEquals("x".repeat(79) + emoji, notificationTitle("x".repeat(79) + emoji + "y"))
        // The trim strips U+FEFF as desktop's JS trim does; inner format characters stay, as on desktop.
        val bom = 0xFEFF.toChar()
        assertNull(notificationTitle("$bom $bom"))
        assertEquals("a${bom}b", notificationTitle("${bom}a${bom}b$bom"))
    }

    @Test
    fun theNameLookupReadsOnlyTheAlertsOwnHostAndIsNullWhenMissing() {
        val hosts =
            listOf(
                host(
                    "host-a",
                    channels = listOf(row("chan", name = "Channel")),
                    chats = listOf(row("chat", name = "Chat"), row("unnamed")),
                ),
                host("host-b", chats = listOf(row("conv", name = "B"))),
            )

        assertEquals("Channel", hosts.nameOf("host-a", "chan"))
        assertEquals("Chat", hosts.nameOf("host-a", "chat"))
        assertNull(hosts.nameOf("host-a", "unnamed"))
        assertNull(hosts.nameOf("host-a", "missing"))
        assertNull(hosts.nameOf("host-b", "chan"))
        assertNull(hosts.nameOf("host-c", "conv"))
    }

    @Test
    fun anAlertSuppressedByAGateIsSpentAndNeverPostsLater() =
        withNotifier {
            foreground = true
            alerts.emit(TURN)
            foreground = false
            alerts.emit(TURN)

            assertEquals(emptyList<Notification>(), posted())
        }

    @Test
    fun theSameAlertPostsOnceAcrossRepeatsAndAFreshProcess() {
        withNotifier {
            alerts.emit(TURN)
            alerts.emit(TURN)
            assertEquals(1, posted().size)
        }
        manager.cancelAll()

        // A new process after a push wake: a new notifier over the same ledger file sees the replay.
        withNotifier {
            alerts.emit(TURN)
            assertEquals(emptyList<Notification>(), posted())
        }
    }

    @Test
    fun theSameConversationIdOnTwoHostsPostsTwoNotifications() =
        withNotifier {
            alerts.emit(TURN)
            alerts.emit(TURN.copy(serverId = "host-b"))

            val targets = posted().map { NotificationTap.target(shadowOf(it.contentIntent).savedIntent) }
            assertEquals(setOf(HostConversationTarget("host-a", "conv"), HostConversationTarget("host-b", "conv")), targets.toSet())
        }

    @Test
    fun aLaterAlertForTheSameConversationReplacesItsNotification() =
        withNotifier {
            alerts.emit(TURN)
            alerts.emit(AttentionAlert("host-a", "conv", AttentionAlert.Kind.Prompt, "batch:q"))

            assertEquals(app.getString(R.string.notification_prompt), posted().single().extras.getString(Notification.EXTRA_TEXT))
        }

    @Test
    fun anUnreadableLedgerStartsEmpty() {
        ledger.mkdirs()
        withNotifier {
            alerts.emit(TURN)
            assertEquals(1, posted().size)
        }
    }

    @Test
    fun aTapTargetIsAcceptedOnlyFromAWellFormedIntent() {
        fun intent(
            action: String? = NotificationTap.ACTION_OPEN_CONVERSATION,
            server: Any? = "host-a",
            conversation: Any? = "conv",
        ) = Intent(action).apply {
            (server as? String)?.let { putExtra(NotificationTap.EXTRA_SERVER_ID, it) }
            (server as? Int)?.let { putExtra(NotificationTap.EXTRA_SERVER_ID, it) }
            (conversation as? String)?.let { putExtra(NotificationTap.EXTRA_CONVERSATION_ID, it) }
        }

        assertEquals(HostConversationTarget("host-a", "conv"), NotificationTap.target(intent()))
        assertNull(NotificationTap.target(null))
        assertNull(NotificationTap.target(intent(action = Intent.ACTION_MAIN)))
        assertNull(NotificationTap.target(intent(server = null)))
        assertNull(NotificationTap.target(intent(server = 7)))
        assertNull(NotificationTap.target(intent(conversation = " ")))
        assertNull(NotificationTap.target(intent(conversation = "c".repeat(MAX_TAP_ID_CHARS + 1))))
    }

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    private fun withNotifier(block: suspend TestScope.() -> Unit) =
        runTest {
            val notifier =
                AttentionNotifier(
                    app,
                    alerts,
                    enabled,
                    { server, conversation -> (server to conversation) in muted },
                    { server, conversation -> agents[server to conversation] },
                    { server, conversation -> names[server to conversation] },
                    { foreground },
                    ledger,
                    UnconfinedTestDispatcher(testScheduler),
                )
            try {
                block()
            } finally {
                notifier.dispose()
            }
        }

    private companion object {
        val TURN = AttentionAlert("host-a", "conv", AttentionAlert.Kind.TurnCompleted, "t1")

        fun row(
            id: String,
            muted: Boolean = false,
            agent: ConversationAgent = ConversationAgent.Claude,
            name: String? = null,
        ) = Conversation(
            id,
            name,
            "~",
            "s",
            emptyList(),
            isPromoted = false,
            lastUsedAt = Instant.fromEpochSeconds(0),
            muted = muted,
            agent = agent,
        )

        fun host(
            serverId: String,
            channels: List<Conversation> = emptyList(),
            chats: List<Conversation> = emptyList(),
        ) = HostConversationSnapshot(
            serverId,
            null,
            ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected),
            channels,
            chats,
        )
    }
}
