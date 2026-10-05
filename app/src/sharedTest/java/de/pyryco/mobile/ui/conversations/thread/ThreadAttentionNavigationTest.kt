package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.Routes
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.di.AttentionAlert
import de.pyryco.mobile.di.ConversationAttention
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.openAttentionTarget
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The actual entry lifecycle, production route encoding and pill navigation action, without network I/O. */
@RunWith(AndroidJUnit4::class)
class ThreadAttentionNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val a = HostConversationTarget("host/a", "same /?#%")
    private val b = HostConversationTarget("host/b", a.conversationId)
    private val c = HostConversationTarget("host/a", "third")
    private val snapshots = MutableStateFlow(listOf(host(a, "A"), host(b, "B")))
    private val attention = MutableStateFlow<Map<String, Map<String, ConversationAttention>>>(emptyMap())
    private val alerts = MutableSharedFlow<AttentionAlert>(extraBufferCapacity = 10)
    private lateinit var nav: NavHostController
    private lateinit var owner: LifecycleOwner
    private lateinit var lifecycle: LifecycleRegistry
    private var sends = 0
    private var answers = 0

    private fun start() {
        owner =
            object : LifecycleOwner {
                override val lifecycle: Lifecycle get() = this@ThreadAttentionNavigationTest.lifecycle
            }
        lifecycle = LifecycleRegistry.createUnsafe(owner)
        lifecycle.currentState = Lifecycle.State.RESUMED
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                PyrycodeMobileTheme(dynamicColor = false) {
                    nav = rememberNavController()
                    NavHost(nav, startDestination = Routes.CHANNEL_LIST) {
                        composable(Routes.CHANNEL_LIST) { }
                        composable(Routes.CONVERSATION_THREAD, arguments = Routes.hostArguments()) { entry ->
                            val target = Routes.target(entry.arguments)
                            val notice by rememberThreadAttention(target, snapshots, attention, alerts, entry.lifecycle)
                            ThreadScreen(
                                state = ThreadUiState(target.conversationId, target.serverId),
                                onBack = { nav.popBackStack() },
                                onSendMessage = { sends++ },
                                onModalOption = { _, _ -> answers++ },
                                connectionState = ConnectionState.Connected,
                                onRetry = {},
                                attentionPill = notice?.let { reading -> { ThreadAttentionNotice(reading, nav::openAttentionTarget) } },
                            )
                        }
                    }
                }
            }
        }
        compose.runOnIdle { nav.navigate(Routes.thread(a)) }
        awaitCollector()
    }

    private fun waitFor(vararg targets: HostConversationTarget) {
        attention.value =
            targets.groupBy { it.serverId }.mapValues { (_, values) ->
                values.associate {
                    it.conversationId to
                        ConversationAttention.WaitingForAnswer
                }
            }
    }

    private fun finish(
        target: HostConversationTarget,
        key: String,
    ) {
        compose.runOnIdle { alerts.tryEmit(AttentionAlert(target.serverId, target.conversationId, AttentionAlert.Kind.TurnCompleted, key)) }
    }

    private fun awaitCollector() {
        compose.waitForIdle()
        compose.waitUntil(5_000) { alerts.subscriptionCount.value == 1 }
        compose.waitForIdle()
    }

    @Test fun waitingTapOpensCollidingIdOnExactOtherHost_andBackRestoresWaiting_withoutAnswerOrSend() {
        waitFor(b)
        start()
        compose.onNodeWithText("B needs your answer").assertIsDisplayed().performTouchInput { click(center) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(b, Routes.target(nav.currentBackStackEntry?.arguments))
            assertEquals(0, sends)
            assertEquals(0, answers)
        }
        compose.onNodeWithTag("thread_attention_pill").assertDoesNotExist()
        compose.runOnIdle { nav.popBackStack() }
        awaitCollector()
        compose.onNodeWithText("B needs your answer").assertIsDisplayed()
    }

    @Test fun finishedTapOpensExactOtherHost_andCountTapReturnsToList() {
        start()
        finish(b, "first")
        compose.onNodeWithText("B finished").assertIsDisplayed().performTouchInput { click(center) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(b, Routes.target(nav.currentBackStackEntry?.arguments))
            nav.popBackStack()
        }
        awaitCollector()
        compose.runOnIdle { waitFor(b, c) }
        compose.onNodeWithText("2 conversations need you").assertIsDisplayed().performTouchInput { click(center) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(Routes.CHANNEL_LIST, nav.currentDestination?.route)
            assertEquals(0, sends)
            assertEquals(0, answers)
        }
        compose.waitUntil(5_000) { alerts.subscriptionCount.value == 0 }
    }

    @Test fun coveringThreadCancelsOldFinish_andBackDoesNotReplayCoveredEvents() {
        start()
        finish(b, "first")
        compose.onNodeWithText("B finished").assertIsDisplayed()
        compose.runOnIdle { nav.navigate(Routes.thread(b)) }
        awaitCollector()
        compose.onNodeWithTag("thread_attention_pill").assertDoesNotExist()
        finish(a, "while-covered")
        compose.onNodeWithText("A finished").assertIsDisplayed()
        compose.runOnIdle { nav.popBackStack() }
        awaitCollector()
        compose.onNodeWithTag("thread_attention_pill").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(6_000)
        compose.onNodeWithTag("thread_attention_pill").assertDoesNotExist()
    }

    @Test fun backgroundCancelsTimerAndSubscription_resumeReadsWaitingOnly() {
        start()
        finish(b, "first")
        compose.onNodeWithText("B finished").assertIsDisplayed()
        compose.runOnIdle { lifecycle.currentState = Lifecycle.State.CREATED }
        compose.waitUntil(5_000) { alerts.subscriptionCount.value == 0 }
        compose.runOnIdle {
            alerts.tryEmit(AttentionAlert(b.serverId, b.conversationId, AttentionAlert.Kind.TurnCompleted, "background"))
            lifecycle.currentState = Lifecycle.State.RESUMED
        }
        awaitCollector()
        compose.onNodeWithTag("thread_attention_pill").assertDoesNotExist()
        compose.runOnIdle {
            lifecycle.currentState = Lifecycle.State.CREATED
            waitFor(b)
        }
        compose.waitUntil(5_000) { alerts.subscriptionCount.value == 0 }
        compose.runOnIdle { lifecycle.currentState = Lifecycle.State.RESUMED }
        awaitCollector()
        compose.onNodeWithText("B needs your answer").assertIsDisplayed()
    }

    private fun host(
        target: HostConversationTarget,
        name: String,
    ) = HostConversationSnapshot(
        target.serverId,
        target.serverId,
        ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected),
        chats = listOf(Conversation(target.conversationId, name, "/scratch", "session", emptyList(), false, Instant.fromEpochSeconds(0))),
    )
}
