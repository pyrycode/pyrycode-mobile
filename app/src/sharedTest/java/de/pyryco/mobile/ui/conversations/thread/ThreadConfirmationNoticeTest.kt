package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreadConfirmationNoticeTest {
    @get:Rule val rule = createComposeRule()
    private var modal by mutableStateOf<ModalUiState>(ModalUiState.Hidden)
    private var visible by mutableStateOf(true)
    private var name by mutableStateOf("Thread")
    private val errors = Channel<Unit>(Channel.UNLIMITED)

    private fun show(
        accessibility: AccessibilityManager? = null,
        offline: Boolean = false,
    ) {
        val flow = errors.receiveAsFlow()
        rule.setContent {
            CompositionLocalProvider(LocalAccessibilityManager provides accessibility) {
                PyrycodeMobileTheme(dynamicColor = false) {
                    if (visible) {
                        ThreadScreen(
                            state = ThreadUiState("c1", name),
                            onBack = {},
                            onSendMessage = {},
                            connectionState = if (offline) ConnectionState.Offline else ConnectionState.Connected,
                            onRetry = {},
                            modalState = modal,
                            archiveErrors = flow,
                        )
                    }
                }
            }
        }
        rule.mainClock.autoAdvance = false
    }

    private fun dismiss(
        id: String,
        source: String = "remote",
    ) {
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { modal = ModalUiState.Dismissed(id, "allow", source, "c1") }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
    }

    private fun confirmation(text: String) =
        rule
            .onNodeWithText(text)
            .assert(hasTestTag("transient_confirmation_notice"))
            .assertIsDisplayed()
            .assertHasNoClickAction()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.Dismiss))

    @Test fun everyDismissalSource_usesItsExistingCopy_withoutASnackbar() {
        show()
        listOf(
            "remote" to "Resolved on another device",
            "local" to "Resolved on this device",
            "timeout" to "Request timed out",
            "unrecognized" to "Request resolved",
        ).forEachIndexed { i, (source, text) ->
            dismiss("m$i", source)
            confirmation(text)
            rule.onNodeWithTag("thread_confirmation_snackbar").assertDoesNotExist()
            rule.mainClock.advanceTimeBy(4_100)
            rule.onNodeWithTag("transient_confirmation_notice").assertDoesNotExist()
        }
    }

    @Test fun equalDismissals_areSeparateOccurrences_withFullTimeout_andNoRecompositionReplay() {
        show()
        dismiss("first")
        val first = confirmation("Resolved on another device").fetchSemanticsNode().id
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { name = "Renamed" }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        assertEquals(first, confirmation("Resolved on another device").fetchSemanticsNode().id)
        dismiss("second")
        rule.mainClock.advanceTimeBy(3_500)
        assertEquals(first, confirmation("Resolved on another device").fetchSemanticsNode().id)
        rule.mainClock.advanceTimeBy(600)
        val second = confirmation("Resolved on another device").fetchSemanticsNode().id
        assertNotEquals(first, second)
        rule.mainClock.advanceTimeBy(3_500)
        assertEquals(second, confirmation("Resolved on another device").fetchSemanticsNode().id)
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithTag("transient_confirmation_notice").assertDoesNotExist()
        rule.runOnIdle { name = "Again" }
        rule.mainClock.advanceTimeBy(64)
        rule.onNodeWithTag("transient_confirmation_notice").assertDoesNotExist()
    }

    @Test fun modalTransitions_keepDismissalsInArrivalOrder() {
        show()
        dismiss("first", "timeout")
        dismiss("second", "local")
        dismiss("third", "unknown")
        confirmation("Request timed out")
        rule.onNodeWithText("Resolved on this device").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(4_100)
        confirmation("Resolved on this device")
        rule.mainClock.advanceTimeBy(4_100)
        confirmation("Request resolved")
        rule.mainClock.advanceTimeBy(4_100)
        rule.onNodeWithTag("transient_confirmation_notice").assertDoesNotExist()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun confirmationIsBelowErrors_includingOffline_withoutMovingContent_orDelayingErrors() {
        show(offline = true)
        val contentBefore = rule.onNodeWithTag("thread-message-region").getUnclippedBoundsInRoot()
        dismiss("first")
        val offline = rule.onNodeWithTag("offline_retry_target").getUnclippedBoundsInRoot()
        rule.runOnIdle { errors.trySend(Unit) }
        rule.mainClock.advanceTimeBy(64)
        val error = rule.onNodeWithTag("transient_error_notice").assertIsDisplayed().getUnclippedBoundsInRoot()
        val pill = confirmation("Resolved on another device").getUnclippedBoundsInRoot()
        assertEquals(12f, (pill.top - error.bottom).value, 0.5f)
        assertEquals(24f, (pill.bottom - pill.top).value, 0.5f)
        assertEquals(offline.right, pill.right)
        assertEquals(contentBefore, rule.onNodeWithTag("thread-message-region").getUnclippedBoundsInRoot())
        rule.mainClock.advanceTimeBy(4_100)
        rule.onNodeWithTag("transient_confirmation_notice").assertDoesNotExist()
        rule.onNodeWithTag("transient_error_notice").assertDoesNotExist()
        rule.onNodeWithTag("offline_retry_target").assertIsDisplayed()
    }

    @Test fun accessibilityExtendsConfirmationTimeout_withMaterialShortFlags() {
        val requests = mutableListOf<List<Any>>()
        val manager =
            object : AccessibilityManager {
                override fun calculateRecommendedTimeoutMillis(
                    originalTimeoutMillis: Long,
                    containsIcons: Boolean,
                    containsText: Boolean,
                    containsControls: Boolean,
                ): Long {
                    requests += listOf(originalTimeoutMillis, containsIcons, containsText, containsControls)
                    return 12_000L
                }
            }
        show(manager)
        dismiss("first")
        assertEquals(listOf(listOf(4_000L, true, true, false)), requests)
        rule.mainClock.advanceTimeBy(4_100)
        confirmation("Resolved on another device")
        rule.mainClock.advanceTimeBy(8_100)
        rule.onNodeWithTag("transient_confirmation_notice").assertDoesNotExist()
    }

    @Test fun leavingCancelsActiveAndQueuedDismissals_reopeningShowsOnlyLatestModalAgain() {
        show()
        dismiss("first", "timeout")
        dismiss("second", "local")
        confirmation("Request timed out")
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { visible = false }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("transient_confirmation_notice").assertDoesNotExist()
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { visible = true }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        confirmation("Resolved on this device")
        rule.onNodeWithText("Request timed out").assertDoesNotExist()
        rule.mainClock.advanceTimeBy(4_100)
        rule.onNodeWithTag("transient_confirmation_notice").assertDoesNotExist()
    }
}
