package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.AuthoritativePendingSend
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthoritativeSendScreenTest {
    @get:Rule val compose = createComposeRule()
    private var snapshot by mutableStateOf(authoritativeSnapshot())
    private val events = mutableListOf<ThreadEvent>()

    private fun mount(sendNow: Boolean = true) {
        compose.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state =
                        ThreadUiState(
                            "c",
                            "Channel",
                            authoritativeThread = projectAuthoritativeThread("host", "c", snapshot),
                            runConfig =
                                ThreadRunConfig(
                                    settingsAvailable = true,
                                    sessionId = "s",
                                    capabilities =
                                        de.pyryco.mobile.data.repository.SessionCapabilities(
                                            emptyList(),
                                            emptyList(),
                                            midTurnInput = sendNow,
                                        ),
                                ),
                        ),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    onOverflowEvent = { events += it },
                    onDropQueued = { error("unbound authoritative drop") },
                    onSendQueuedNow = { error("unbound authoritative send") },
                )
            }
        }
    }

    @Test fun waitingCallbacksCaptureGenuineHandleAndAcceptanceAndLostHasNoActions() {
        snapshot = authoritativeSnapshot(queued())
        mount()
        compose.onNodeWithContentDescription("Drop this queued message").performClick()
        compose.onNodeWithContentDescription("Send now").performClick()
        val actions = events.filterIsInstance<ThreadEvent.AuthoritativeQueueAction>()
        assertEquals(listOf(false, true), actions.map { it.sendNow })
        assertEquals(18446744073709551615uL, actions.first().target.queuedMessageId)
        assertEquals(1L, actions.first().target.acceptedItemId)
        compose.runOnIdle { snapshot = authoritativeSnapshot(queued(status = "lost", active = false)) }
        compose.onNodeWithText("queue text\nLost").assertIsDisplayed()
        compose.onAllNodes(hasContentDescription("Drop this queued message")).assertCountEquals(0)
        compose.onAllNodes(hasContentDescription("Send now")).assertCountEquals(0)
        assertEquals(2, actions.size)
    }

    @Test fun pendingIsVisiblySendingAndHiddenDropRemovesIt() {
        snapshot =
            authoritativeSnapshot().copy(
                pendingSends =
                    listOf(
                        AuthoritativePendingSend(
                            "device",
                            Message("client", "", Role.User, "provisional", Instant.fromEpochMilliseconds(0), false),
                        ),
                    ),
            )
        mount()
        compose.onNodeWithText("provisional\nSending").assertIsDisplayed()
        compose.onAllNodes(hasContentDescription("Drop this queued message")).assertCountEquals(0)
        compose.runOnIdle { snapshot = authoritativeSnapshot(queued(status = "dropped", active = false, shown = false)) }
        compose.onAllNodes(hasText("provisional", substring = true)).assertCountEquals(0)
        compose.onAllNodes(hasText("queue text", substring = true)).assertCountEquals(0)
    }

    @Test fun invalidHandleAndMissingSendNowSupportExposeNoUnboundFallback() {
        snapshot = authoritativeSnapshot(queued(handle = "\"7\""))
        mount(sendNow = false)
        compose.onNodeWithText("queue text").assertIsDisplayed()
        compose.onAllNodes(hasContentDescription("Drop this queued message")).assertCountEquals(0)
        compose.runOnIdle { snapshot = authoritativeSnapshot(queued()) }
        compose.onNodeWithContentDescription("Drop this queued message").assertIsDisplayed()
        compose.onAllNodes(hasContentDescription("Send now")).assertCountEquals(0)
    }

    private fun queued(
        status: String = "queued",
        active: Boolean = true,
        shown: Boolean = true,
        handle: String = "18446744073709551615",
    ) = authoritativeItem(
        1,
        "user_message",
        order = null,
        status = status,
        active = active,
        shown = shown,
        content = """{"text":"queue text","queued_msg_id":$handle,"message_id":"client","device_id":"device"}""",
    )
}
