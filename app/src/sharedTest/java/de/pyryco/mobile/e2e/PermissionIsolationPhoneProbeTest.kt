package de.pyryco.mobile.e2e

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.ModalOption
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.thread.ThreadScreen
import de.pyryco.mobile.ui.conversations.thread.ThreadUiState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Probe the live scenario's original physical tap sequence; semantic clicks cannot expose chrome interception. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h892dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PermissionIsolationPhoneProbeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun short_permission_confirms_with_the_original_physical_sequence() = probe(1)

    @Test fun medium_permission_confirms_with_the_original_physical_sequence() = probe(5)

    @Test fun long_permission_confirms_with_the_original_physical_sequence() = probe(20)

    private fun probe(lines: Int) {
        val armed = mutableStateOf<String?>(null)
        val answers = mutableListOf<String>()
        val modal =
            ModalUiState.Open(
                modalId = "A-modal",
                modalClass = "permission",
                title = "Permission required",
                prompt = List(lines) { "Run the requested foreground command." }.joinToString("\n"),
                options =
                    listOf(
                        ModalOption("allow_once", "Allow once"),
                        ModalOption("allow_always", "Allow always"),
                        ModalOption("reject_once", "Reject once"),
                        ModalOption("reject_always", "Reject always"),
                    ),
                defaultOptionId = "reject_once",
                conversationId = "A",
            )
        val message =
            Message(
                "user",
                "session",
                Role.User,
                "Run the foreground command and report its output.",
                Instant.parse("2026-10-10T00:00:00Z"),
                isStreaming = false,
            )
        compose.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state =
                        ThreadUiState(
                            conversationId = "A",
                            displayName = "A",
                            isPromoted = true,
                            hasMessages = true,
                            items = listOf(ThreadItem.MessageItem(message)),
                        ),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    isThinking = true,
                    modalState = modal,
                    armedOptionId = armed.value,
                    onModalOption = { modalId, optionId ->
                        if (armed.value == optionId) answers += "$modalId/$optionId" else armed.value = optionId
                    },
                )
            }
        }
        val allow = hasText("Allow once") and hasClickAction() and hasAnyAncestor(hasTestTag("permission-request-card"))
        originalTap(allow)
        compose.runOnIdle {
            assertEquals("first physical tap must arm", "allow_once", armed.value)
            assertTrue("arming must not answer", answers.isEmpty())
        }
        originalTap(allow)
        compose.runOnIdle { assertEquals("confirmation must answer A exactly once", listOf("A-modal/allow_once"), answers) }
    }

    private fun originalTap(matcher: SemanticsMatcher) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(matcher)
        compose.onNode(matcher).performTouchInput { click(center) }
    }
}
