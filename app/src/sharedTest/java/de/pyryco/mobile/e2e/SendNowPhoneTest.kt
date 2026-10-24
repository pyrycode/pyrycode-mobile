package de.pyryco.mobile.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.SessionCapabilities
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.thread.ThreadRunConfig
import de.pyryco.mobile.ui.conversations.thread.ThreadScreen
import de.pyryco.mobile.ui.conversations.thread.ThreadUiState
import de.pyryco.mobile.ui.conversations.thread.withLiteralRows
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SendNowPhoneTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sendBehindComposerIsRevealedBeforeItsSinglePhysicalTap() {
        val sent = mutableListOf<Long>()
        val dropped = mutableListOf<Long>()
        val time = Instant.parse("2026-10-12T12:00:00Z")
        val history =
            (0 until 20).map {
                ThreadItem.MessageItem(Message("m$it", "s", Role.Assistant, "Earlier reply $it", time, false))
            }
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 892.dp))) {
                PyrycodeMobileTheme {
                    ThreadScreen(
                        state =
                            (
                                ThreadUiState(
                                    conversationId = "c",
                                    displayName = "Send now regression",
                                    hasMessages = true,
                                    items = history,
                                    queuedMessages = listOf(QueuedMessage(42L, "In your final reply include this marker", time)),
                                    runConfig =
                                        ThreadRunConfig(
                                            sessionId = "s",
                                            settingsAvailable = true,
                                            capabilities = SessionCapabilities(emptyList(), emptyList(), midTurnInput = true),
                                        ),
                                )
                            ).withLiteralRows(),
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        onSendQueuedNow = { sent += it },
                        onDropQueued = { dropped += it },
                    )
                }
            }
        }
        val matcher = hasContentDescription("Send now")
        val before = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot
        val composerTop =
            compose
                .onNodeWithTag("thread-composer")
                .fetchSemanticsNode()
                .boundsInRoot.top
        // Reproduce the retained live geometry: Compose calls the node displayed,
        // but its center falls below the readable viewport, under the composer.
        compose.onNode(hasScrollToIndexAction()).performSemanticsAction(SemanticsActions.ScrollBy) {
            it(0f, composerTop - before.top + 4f)
        }
        val obscured =
            compose
                .onNode(matcher)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue("fixture must place Send now behind the composer: $obscured / $composerTop", obscured.center.y > composerTop)
        compose.tapSendNowOnce(matcher)
        assertEquals("the real Send now target receives one action", listOf(42L), sent)
        assertTrue("Cancel must not receive the tap", dropped.isEmpty())
    }
}
