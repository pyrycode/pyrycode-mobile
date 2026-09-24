package de.pyryco.mobile.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.MainActivity
import de.pyryco.mobile.R
import de.pyryco.mobile.di.AttentionAlert
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File

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
            assertEquals(app.getString(R.string.app_name), notification.extras.getString(Notification.EXTRA_TITLE))
            assertEquals(ATTENTION_CHANNEL_ID, notification.channelId)
            assertNotNull(manager.getNotificationChannel(ATTENTION_CHANNEL_ID))
            val tap = notification.contentIntent
            assertTrue(tap.isImmutable)
            val intent = shadowOf(tap).savedIntent
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            assertEquals(HostConversationTarget("host-a", "conv"), NotificationTap.target(intent))
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
                AttentionNotifier(app, alerts, enabled, { foreground }, ledger, UnconfinedTestDispatcher(testScheduler))
            try {
                block()
            } finally {
                notifier.dispose()
            }
        }

    private companion object {
        val TURN = AttentionAlert("host-a", "conv", AttentionAlert.Kind.TurnCompleted, "t1")
    }
}
