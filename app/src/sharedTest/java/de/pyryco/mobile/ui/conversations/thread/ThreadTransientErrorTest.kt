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
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.repository.UsageLimitReading
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
class ThreadTransientErrorTest {
    @get:Rule
    val rule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val errors = List(5) { Channel<Unit>(Channel.UNLIMITED) }
    private val refusals = Channel<AttachmentRefusal>(Channel.UNLIMITED)
    private val sends = Channel<AttachmentSendFailure>(Channel.UNLIMITED)
    private var visible by mutableStateOf(true)

    private fun show(
        persistent: Boolean = false,
        accessibility: AccessibilityManager? = null,
        dismissedElsewhere: Boolean = false,
    ) {
        val flows = errors.map { it.receiveAsFlow() }
        val refusalFlow = refusals.receiveAsFlow()
        val sendFlow = sends.receiveAsFlow()
        rule.setContent {
            CompositionLocalProvider(LocalAccessibilityManager provides accessibility) {
                PyrycodeMobileTheme {
                    if (visible) {
                        ThreadScreen(
                            state = ThreadUiState("c1", "Thread"),
                            onBack = {},
                            onSendMessage = {},
                            connectionState = ConnectionState.Connected,
                            onRetry = {},
                            modalState =
                                if (dismissedElsewhere) {
                                    ModalUiState.Dismissed("modal", "allow", "remote", "c1")
                                } else {
                                    ModalUiState.Hidden
                                },
                            newSessionErrors = flows[0],
                            archiveErrors = flows[1],
                            changeWorkspaceErrors = flows[2],
                            sessionSettingsErrors = flows[3],
                            markdownOpenFailures = flows[4],
                            attachmentRefusals = refusalFlow,
                            attachmentSendFailures = sendFlow,
                            usageLimit = if (persistent) UsageLimitReading("allowed_warning", "seven_day", 0L, 0.8, null) else null,
                            sessionError = if (persistent) "session.blocked" else null,
                        )
                    }
                }
            }
        }
        rule.mainClock.autoAdvance = false
    }

    private fun assertPill(text: String) {
        rule.mainClock.advanceTimeByFrame()
        rule
            .onNodeWithTag("transient_error_notice")
            .assertIsDisplayed()
            .assertHasNoClickAction()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.Dismiss))
        rule
            .onNodeWithText(text)
            .assertIsDisplayed()
            .assert(hasTestTag("transient_error_notice"))
    }

    private fun expire() {
        rule.mainClock.advanceTimeBy(4_100)
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun shortErrorPill_preservesTheDesigns24dpLineBox() {
        rule.setContent {
            PyrycodeMobileTheme(dynamicColor = false) { TransientErrorPill("Couldn't open file") }
        }
        assertEquals(
            24f,
            rule
                .onNodeWithTag("transient_error_notice")
                .getUnclippedBoundsInRoot()
                .height.value,
            0.5f,
        )
    }

    @Test fun allUnitErrorRoutes_useInertPills_withExistingCopy() {
        show()
        val strings =
            listOf(
                R.string.new_session_failed,
                R.string.archive_failed,
                R.string.change_workspace_failed,
                R.string.session_settings_failed,
                AttachmentNotice.OPEN_FAILED.message,
            )
        errors.zip(strings).forEach { (channel, resource) ->
            rule.runOnIdle { channel.trySend(Unit) }
            assertPill(context.getString(resource))
            expire()
            rule.onNodeWithTag("transient_error_notice").assertDoesNotExist()
        }
    }

    @Test fun allAttachmentSendReasons_useTheirClientOwnedCopy() {
        show()
        AttachmentSendFailure.entries.forEach { failure ->
            rule.runOnIdle { sends.trySend(failure) }
            assertPill(failure.text(context.resources))
            expire()
        }
    }

    @Test fun bothRefusalReasons_areVisibleInOrder_forTheirFullTimeout() {
        show()
        rule.runOnIdle { refusals.trySend(AttachmentRefusal(tooLarge = 2, tooMany = 3)) }
        val large = context.resources.getQuantityString(R.plurals.thread_attachments_too_large, 2, 2)
        val many = context.resources.getQuantityString(R.plurals.thread_attachments_too_many, 3, 3)
        assertPill(large)
        rule.onNodeWithText(many).assertDoesNotExist()
        rule.mainClock.advanceTimeBy(3_000)
        rule.onNodeWithText(large).assertIsDisplayed()
        rule.mainClock.advanceTimeBy(1_100)
        assertPill(many)
        rule.onNodeWithText(large).assertDoesNotExist()
        rule.mainClock.advanceTimeBy(3_000)
        rule.onNodeWithText(many).assertIsDisplayed()
        expire()
        rule.onNodeWithTag("transient_error_notice").assertDoesNotExist()
    }

    @Test fun expiryOnlyRemovesTransient_belowPersistentSessionError() {
        show(persistent = true)
        val session = context.getString(R.string.thread_session_blocked)
        val before = rule.onNodeWithContentDescription(session).getUnclippedBoundsInRoot()
        rule.runOnIdle { errors[1].trySend(Unit) }
        assertPill(context.getString(R.string.archive_failed))
        val pill = rule.onNodeWithTag("transient_error_notice").getUnclippedBoundsInRoot()
        assertEquals(12f, (pill.top - before.bottom).value, 0.5f)
        assertEquals(before, rule.onNodeWithContentDescription(session).getUnclippedBoundsInRoot())
        expire()
        rule.onNodeWithTag("transient_error_notice").assertDoesNotExist()
        rule.onNodeWithContentDescription(session).assertIsDisplayed()
        rule.onNodeWithContentDescription("Dismiss notice").assertIsDisplayed()
    }

    @Test fun dismissedElsewhere_keepsItsSnackbar_whileAnErrorPillShows() {
        show(dismissedElsewhere = true)
        rule.runOnIdle { errors[1].trySend(Unit) }
        assertPill(context.getString(R.string.archive_failed))
        rule.mainClock.advanceTimeBy(300)
        rule
            .onNodeWithText("Resolved on another device")
            .assertIsDisplayed()
            .assert(hasAnyAncestor(hasTestTag("thread_confirmation_snackbar")))
    }

    @Test fun accessibilityAdjustment_extendsTheShortTimeout_withTextAndNoControls() {
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
        show(accessibility = manager)
        rule.runOnIdle { errors[0].trySend(Unit) }
        assertPill(context.getString(R.string.new_session_failed))
        assertEquals(listOf(listOf(4_000L, true, true, false)), requests)
        expire()
        rule.onNodeWithTag("transient_error_notice").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(8_100)
        rule.onNodeWithTag("transient_error_notice").assertDoesNotExist()
    }

    @Test fun leavingTheScreen_cancelsActiveAndQueuedErrors() {
        show()
        rule.runOnIdle {
            errors[0].trySend(Unit)
            errors[1].trySend(Unit)
        }
        assertPill(context.getString(R.string.new_session_failed))
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { visible = false }
        rule.waitForIdle()
        rule.onNodeWithTag("transient_error_notice").assertDoesNotExist()
        rule.runOnIdle { visible = true }
        rule.mainClock.advanceTimeByFrame()
        expire()
        rule.onNodeWithTag("transient_error_notice").assertDoesNotExist()
    }
}
