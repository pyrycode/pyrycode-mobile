package de.pyryco.mobile.ui.conversations.thread

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.repository.ResetStatus
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UsageLimitReading
import de.pyryco.mobile.ui.conversations.components.TurnRecoveryNotice
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The native usage/Re-pair regression must stay runnable without [Ignore] (#1760).
 *
 * #1002: notices draw as pills in the thread's Top overlay, and the status row keeps only live turn status.
 */
@RunWith(AndroidJUnit4::class)
class ThreadTopOverlayTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val dismissDescription: String = context.getString(R.string.thread_notice_dismiss)

    private val warning =
        UsageLimitReading(
            status = "allowed_warning",
            limitType = "seven_day",
            resetsAt = 0L,
            utilization = 0.8,
            truncatedFields = null,
        )

    private var state by mutableStateOf(ThreadUiState(conversationId = "c1", displayName = "Overlay"))
    private var usageLimit by mutableStateOf<UsageLimitReading?>(null)
    private var dismissed by mutableStateOf(emptySet<UsageLimitDismissals.Key>())
    private var showRePair by mutableStateOf(false)
    private var isBusy by mutableStateOf(false)
    private var resetting by mutableStateOf<ResetStatus?>(null)
    private var turnOutcome by mutableStateOf<TurnRecoveryNotice?>(null)
    private var connectionState by mutableStateOf<ConnectionState>(ConnectionState.Connected)
    private var mcpFailure by mutableStateOf<String?>(null)
    private var sessionError by mutableStateOf<String?>(null)
    private var view: View? = null
    private var errorContainer = Color.Unspecified
    private var errorText = Color.Unspecified
    private var dismissTaps = 0
    private var rePairTaps = 0
    private var mcpTaps = 0
    private var retryTaps = 0

    private fun setScreen(darkTheme: Boolean = false) {
        composeRule.setContent {
            PyrycodeMobileTheme(darkTheme = darkTheme, dynamicColor = false) {
                view = LocalView.current
                errorContainer = MaterialTheme.colorScheme.errorContainer
                errorText = MaterialTheme.colorScheme.error
                ThreadScreen(
                    state = state,
                    onBack = {},
                    onSendMessage = {},
                    connectionState = connectionState,
                    onRetry = { retryTaps++ },
                    usageLimit = usageLimit,
                    dismissedUsageLimits = dismissed,
                    onDismissUsageLimit = {
                        dismissTaps++
                        dismissed = dismissed + it.dismissalKey()
                    },
                    showRePair = showRePair,
                    onRePair = { rePairTaps++ },
                    mcpFailure = mcpFailure,
                    onOpenMcpFailure = { mcpTaps++ },
                    isBusy = isBusy,
                    resetting = resetting,
                    turnOutcome = turnOutcome,
                    sessionError = sessionError,
                )
            }
        }
    }

    @Test
    fun recoveryNotice_sitsBelowOtherNotices_withTheSameGapAndRightEdge() {
        usageLimit = warning
        mcpFailure = "github"
        sessionError = "session.blocked"
        turnOutcome = TurnRecoveryNotice.ContextTooLong
        setScreen()

        val outcome = composeRule.onNodeWithContentDescription("Context too long - Compact")
        outcome.assertIsDisplayed()
        val previous = composeRule.onNodeWithContentDescription(context.getString(R.string.thread_session_blocked))
        val before = previous.getUnclippedBoundsInRoot()
        val after =
            composeRule
                .onNodeWithContentDescription(
                    "Context too long - Compact",
                    useUnmergedTree = true,
                ).getUnclippedBoundsInRoot()
        assertEquals(12f, (after.top - before.bottom).value, 0.5f)
        assertEquals(before.right.value, after.right.value, 0.5f)
        composeRule.onNodeWithText("Compact").assertDoesNotExist()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun recoveryNotice_usesTheHeaderClearance_withoutMovingTheComposer() {
        setScreen()
        val composer = composeRule.onNodeWithTag("thread-composer").getUnclippedBoundsInRoot()
        val header = composeRule.onNodeWithTag("thread-top-bar").getUnclippedBoundsInRoot()
        turnOutcome = TurnRecoveryNotice.ContextTooLong
        val bounds =
            composeRule
                .onNodeWithContentDescription(
                    "Context too long - Compact",
                    useUnmergedTree = true,
                ).getUnclippedBoundsInRoot()
        assertEquals(28f, (bounds.top - header.bottom).value, 0.5f)
        assertEquals(20f, (header.right - bounds.right).value, 0.5f)
        assertEquals(24f, bounds.height.value, 0.5f)
        assertEquals(composer, composeRule.onNodeWithTag("thread-composer").getUnclippedBoundsInRoot())
        composeRule.onNodeWithContentDescription(dismissDescription).assertDoesNotExist()
    }

    @Test
    fun recoveryNotice_doesNotPreemptActiveOrConnectionReadings() {
        turnOutcome = TurnRecoveryNotice.ContextTooLong
        isBusy = true
        state = state.copy(hasMessages = true, items = listOf(runningTool("Bash")))
        setScreen()
        val pill = composeRule.onNodeWithContentDescription("Context too long - Compact")
        pill.assertIsDisplayed()
        composeRule.onNodeWithText("Running Bash…").assertIsDisplayed()

        resetting = ResetStatus(ResetStatus.Phase.WrappingUp, ResetStatus.Handoff.Pending)
        composeRule.onNodeWithContentDescription(context.getString(R.string.thread_resetting_wrapping_up)).assertIsDisplayed()
        pill.assertIsDisplayed()

        connectionState = ConnectionState.Connecting
        composeRule.onNodeWithText("Connecting…").assertIsDisplayed()
        pill.assertIsDisplayed()
    }

    // #1519: client-owned copy; the status picks the lead and is never drawn.
    private fun label(status: String): String = if (status == "rejected") REACHED_LABEL else NEARLY_LABEL

    @Test
    fun noNotices_drawNoPill() {
        setScreen()

        composeRule.onNodeWithContentDescription(dismissDescription).assertDoesNotExist()
        composeRule.onNodeWithText(RE_PAIR_LABEL).assertDoesNotExist()
    }

    @Test
    fun sessionError_sitsBelowExistingPersistentNotices_andLeavesTheirActionsWorking() {
        sessionError = "session.blocked"
        usageLimit = warning
        mcpFailure = "github"
        setScreen()
        val errorLabel = context.getString(R.string.thread_session_blocked)
        val pill = composeRule.onNodeWithContentDescription(errorLabel)
        pill.assertIsDisplayed().assertHasNoClickAction()
        val mcpBounds = composeRule.onNodeWithContentDescription(MCP_FAILED_LABEL).getUnclippedBoundsInRoot()
        val errorBounds = pill.getUnclippedBoundsInRoot()
        assertEquals(12f, (errorBounds.top - mcpBounds.bottom).value, 0.5f)
        composeRule.onAllNodesWithContentDescription(dismissDescription).assertCountEquals(1)
        composeRule.onNodeWithText(MCP_FAILED_LABEL).performClick()
        composeRule.runOnIdle { assertEquals(1, mcpTaps) }

        showRePair = true
        val pairingBounds = composeRule.onNodeWithContentDescription(RE_PAIR_LABEL).getUnclippedBoundsInRoot()
        assertEquals(12f, (pill.getUnclippedBoundsInRoot().top - pairingBounds.bottom).value, 0.5f)
        composeRule.onNodeWithText(RE_PAIR_LABEL).performClick()
        composeRule.runOnIdle { assertEquals(1, rePairTaps) }
        pill.assertIsDisplayed()

        showRePair = false
        connectionState = ConnectionState.Offline
        // Figma's 12dp gap is between visible pills, independently of Retry's expanded touch box.
        val retryBounds = composeRule.onNodeWithText(OFFLINE_RETRY_LABEL).getUnclippedBoundsInRoot()
        assertEquals(12f, (pill.getUnclippedBoundsInRoot().top - retryBounds.bottom).value, 0.5f)
        composeRule.onNodeWithTag("offline_retry_target").performClick()
        composeRule.runOnIdle { assertEquals(1, retryTaps) }
        pill.assertIsDisplayed()

        sessionError = null
        pill.assertDoesNotExist()
        composeRule.onNodeWithText(OFFLINE_RETRY_LABEL).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(label("allowed_warning")).assertIsDisplayed()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun sessionError_reusesErrorColors_andRightAlignedBodySmall_withNoDismissAction() {
        sessionError = "session.blocked"
        setScreen(darkTheme = true)
        val label = context.getString(R.string.thread_session_blocked)
        val pill = composeRule.onNodeWithContentDescription(label)
        pill.assertIsDisplayed().assertHasNoClickAction()
        composeRule.onNodeWithContentDescription(dismissDescription).assertDoesNotExist()
        val results = mutableListOf<TextLayoutResult>()
        composeRule
            .onNodeWithText(
                label,
                useUnmergedTree = true,
            ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val text = results.single().layoutInput.style
        assertEquals(errorText, text.color)
        assertEquals(12f, text.fontSize.value, 0.01f)
        assertEquals(TextAlign.End, text.textAlign)
        val bounds = pill.fetchSemanticsNode().boundsInRoot
        val inset = with(composeRule.density) { 2.dp.toPx() }
        composeRule.runOnIdle {
            val root = checkNotNull(view)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val actual = Color(bitmap.getPixel(bounds.center.x.toInt(), (bounds.top + inset).toInt()))
            assertEquals(errorContainer.red, actual.red, 1f / 255f)
            assertEquals(errorContainer.green, actual.green, 1f / 255f)
            assertEquals(errorContainer.blue, actual.blue, 1f / 255f)
            System.getenv("SESSION_ERROR_CAPTURE")?.let { path ->
                File(path).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            bitmap.recycle()
        }
    }

    @Test
    fun aWarning_isADismissiblePill_thatReturnsWhenTheResetTimeChanges() {
        usageLimit = warning
        setScreen()
        composeRule.onNodeWithContentDescription(label("allowed_warning")).assertIsDisplayed()

        composeRule.onNodeWithContentDescription(dismissDescription).performClick()

        composeRule.onNodeWithContentDescription(label("allowed_warning")).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(dismissDescription).assertDoesNotExist()

        usageLimit = warning.copy(resetsAt = 1L)
        composeRule.onNodeWithContentDescription(label("allowed_warning")).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(dismissDescription).assertIsDisplayed()
    }

    // #1519: the copy names no agent and no percent, so a Codex conversation reads the same as a Claude one.
    @Test
    fun theUsagePill_readsTheSameForEveryAgent() {
        usageLimit = warning.copy(status = "rejected")
        setScreen()
        composeRule.onNodeWithContentDescription(REACHED_LABEL).assertIsDisplayed()

        state = state.copy(agent = ConversationAgent.Codex)
        composeRule.onNodeWithContentDescription(REACHED_LABEL).assertIsDisplayed()

        usageLimit = warning.copy(status = "")
        composeRule.onNodeWithContentDescription(NEARLY_LABEL).assertIsDisplayed()
        composeRule.onNodeWithText("Codex", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("%", substring = true).assertDoesNotExist()
    }

    @Test
    fun anyOtherStatus_isAnErrorPill_withNoX() {
        usageLimit = warning.copy(status = "rejected")
        setScreen()
        composeRule.onNodeWithContentDescription(label("rejected")).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(dismissDescription).assertDoesNotExist()

        usageLimit = warning.copy(status = "something_new")
        composeRule.onNodeWithContentDescription(label("something_new")).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(dismissDescription).assertDoesNotExist()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun theUsagePill_sitsAboveThePairingPill_whichStartsRePair() {
        usageLimit = warning
        showRePair = true
        setScreen()

        val usageBounds = composeRule.onNodeWithContentDescription(label("allowed_warning")).getUnclippedBoundsInRoot()
        val pairingTop = composeRule.onNodeWithContentDescription(RE_PAIR_LABEL).getUnclippedBoundsInRoot().top
        val usageTop = usageBounds.top
        assertTrue("usage pill at $usageTop should sit above the pairing pill at $pairingTop", usageTop < pairingTop)
        assertEquals(12f, (pairingTop - usageBounds.bottom).value, 0.5f)
        val dismissTouch = composeRule.onNodeWithContentDescription(dismissDescription).fetchSemanticsNode().touchBoundsInRoot
        val pairingTouch = composeRule.onNodeWithContentDescription(RE_PAIR_LABEL).fetchSemanticsNode().touchBoundsInRoot
        assertTrue(
            "dismiss $dismissTouch and re-pair $pairingTouch touch bounds must not overlap",
            dismissTouch.bottom <= pairingTouch.top || dismissTouch.right <= pairingTouch.left,
        )

        val minimumWidth = with(composeRule.density) { 48.dp.toPx() }
        assertTrue("dismiss keeps its horizontal target: $dismissTouch", dismissTouch.width >= minimumWidth)
        assertTrue("Re-pair keeps its horizontal target: $pairingTouch", pairingTouch.width >= minimumWidth)
        val pairing = composeRule.onNodeWithContentDescription(RE_PAIR_LABEL)
        val dismiss = composeRule.onNodeWithContentDescription(dismissDescription)
        pairing.performTouchInput { click(center) }
        composeRule.runOnIdle {
            assertEquals(1, rePairTaps)
            assertEquals(0, dismissTaps)
            assertTrue(dismissed.isEmpty())
        }
        val pairingNode = pairing.fetchSemanticsNode()
        pairing.performTouchInput {
            click(Offset(center.x, pairingNode.touchBoundsInRoot.top - pairingNode.boundsInRoot.top + 1f))
        }
        composeRule.runOnIdle {
            assertEquals(2, rePairTaps)
            assertEquals(0, dismissTaps)
            assertTrue(dismissed.isEmpty())
        }
        val dismissNode = dismiss.fetchSemanticsNode()
        // The usage Surface clips input outside its background, so use its facing visible edge.
        val dismissBottom = minOf(dismissNode.touchBoundsInRoot.bottom, with(composeRule.density) { usageBounds.bottom.toPx() })
        dismiss.performTouchInput {
            click(Offset(center.x, dismissBottom - dismissNode.boundsInRoot.top - 1f))
        }
        composeRule.runOnIdle {
            assertEquals(2, rePairTaps)
            assertEquals(1, dismissTaps)
            assertEquals(setOf(warning.dismissalKey()), dismissed)
        }
        dismiss.assertDoesNotExist()
        composeRule.runOnIdle { dismissed = emptySet() }
        dismiss.performTouchInput { click(center) }
        composeRule.runOnIdle {
            assertEquals(2, rePairTaps)
            assertEquals(2, dismissTaps)
            assertEquals(setOf(warning.dismissalKey()), dismissed)
        }
        dismiss.assertDoesNotExist()
        pairing.assertIsDisplayed()
    }

    // #1345: a failed MCP server is an Error pill with no X below the usage pill; its tap opens Channel info.
    @Test
    fun aFailedMcpServer_isATappablePillBelowTheUsagePill() {
        usageLimit = warning
        mcpFailure = "github"
        setScreen()

        val usageBounds = composeRule.onNodeWithContentDescription(label("allowed_warning")).getUnclippedBoundsInRoot()
        val mcpBounds = composeRule.onNodeWithContentDescription(MCP_FAILED_LABEL).getUnclippedBoundsInRoot()
        assertEquals(12f, (mcpBounds.top - usageBounds.bottom).value, 0.5f)
        composeRule.onAllNodesWithContentDescription(dismissDescription).assertCountEquals(1)

        composeRule.onNodeWithText(MCP_FAILED_LABEL).performClick()
        composeRule.runOnIdle { assertEquals(1, mcpTaps) }

        mcpFailure = null
        composeRule.onNodeWithContentDescription(MCP_FAILED_LABEL).assertDoesNotExist()
    }

    @Test
    fun aFailedMcpServer_isNeverShownBesideThePairingOrOfflinePill() {
        mcpFailure = "github"
        showRePair = true
        setScreen()
        composeRule.onNodeWithContentDescription(RE_PAIR_LABEL).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(MCP_FAILED_LABEL).assertDoesNotExist()

        showRePair = false
        connectionState = ConnectionState.Offline
        composeRule.onNodeWithContentDescription(OFFLINE_RETRY_LABEL).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(MCP_FAILED_LABEL).assertDoesNotExist()

        connectionState = ConnectionState.Connected
        composeRule.onNodeWithContentDescription(MCP_FAILED_LABEL).assertIsDisplayed()
    }

    @Test
    fun aLongServerName_isBounded() {
        mcpFailure = "x".repeat(300)
        setScreen()

        composeRule.onNodeWithContentDescription("MCP server ${"x".repeat(256)}… failed").assertExists()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun offlineRetry_hasA48dpTarget_belowAUsagePill_withoutStealingDismissTaps() {
        usageLimit = warning
        connectionState = ConnectionState.Offline
        setScreen()

        val retry = composeRule.onNodeWithTag("offline_retry_target")
        val retryTouch = retry.fetchSemanticsNode().touchBoundsInRoot
        val dismissTouch = composeRule.onNodeWithContentDescription(dismissDescription).fetchSemanticsNode().touchBoundsInRoot
        val usageBounds = composeRule.onNodeWithContentDescription(label("allowed_warning")).getUnclippedBoundsInRoot()
        val retryPillBounds = composeRule.onNodeWithText(OFFLINE_RETRY_LABEL).getUnclippedBoundsInRoot()
        val retryTextBounds = composeRule.onNodeWithText(OFFLINE_RETRY_LABEL, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(12f, (retryPillBounds.top - usageBounds.bottom).value, 0.5f)
        assertTrue(
            "retry pill $retryPillBounds text $retryTextBounds should show its short label on one line",
            retryPillBounds.height <= 30.dp,
        )
        // #1499: the pill hugs its label, so legible means the label is drawn in full inside its padding.
        assertTrue(
            "retry pill $retryPillBounds should show its whole label $retryTextBounds",
            retryPillBounds.width >= retryTextBounds.width + 16.dp - 0.5.dp,
        )
        val minimumHeightPx = with(composeRule.density) { 48.dp.toPx() }
        assertTrue("retry target $retryTouch must be at least 48dp high", retryTouch.height >= minimumHeightPx)
        assertTrue("dismiss $dismissTouch must end before retry $retryTouch", dismissTouch.bottom <= retryTouch.top)

        retry.performTouchInput { click(Offset(center.x, bottom - 2.dp.toPx())) }
        composeRule.runOnIdle { assertEquals(1, retryTaps) }
        composeRule.onNodeWithContentDescription(dismissDescription).performClick()
        composeRule.runOnIdle { assertEquals(1, retryTaps) }
        composeRule.onNodeWithContentDescription(label("allowed_warning")).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(OFFLINE_RETRY_LABEL).assertIsDisplayed()
    }

    // #1499, Figma 627:4910: the drawn pill hugs its label at the target's top-right; only the touch box is wider.
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun offlineRetry_drawsATextWidePill_atTheTargetsTopRight() {
        connectionState = ConnectionState.Offline
        setScreen()

        val targetBounds = composeRule.onNodeWithTag("offline_retry_target").getUnclippedBoundsInRoot()
        val pillBounds = composeRule.onNodeWithText(OFFLINE_RETRY_LABEL).getUnclippedBoundsInRoot()
        val textBounds = composeRule.onNodeWithText(OFFLINE_RETRY_LABEL, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue("pill $pillBounds should be narrower than its target $targetBounds", pillBounds.width < targetBounds.width)
        assertEquals(targetBounds.right.value, pillBounds.right.value, 0.5f)
        assertEquals(targetBounds.top.value, pillBounds.top.value, 0.5f)
        assertEquals((textBounds.width + 16.dp).value, pillBounds.width.value, 0.5f)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun enlargedOfflinePill_isEnclosedByRetry_andItsLeftEdgeReceivesPointerTaps() {
        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 892.dp))) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    PyrycodeMobileTheme(dynamicColor = false) {
                        ThreadTopOverlay(
                            usageLimit = null,
                            usageLimitDismissed = false,
                            onDismissUsageLimit = {},
                            showRePair = false,
                            onRePair = {},
                            connectionState = ConnectionState.Offline,
                            onRetryConnection = { retryTaps++ },
                            transientError = "Couldn't open file",
                        )
                    }
                }
            }
        }
        val offline = composeRule.onNodeWithText(OFFLINE_RETRY_LABEL)
        val pill = offline.getUnclippedBoundsInRoot()
        val retry = composeRule.onNodeWithTag("offline_retry_target")
        val target = retry.getUnclippedBoundsInRoot()
        assertTrue("enlarged pill must exercise width beyond the old target: $pill", pill.width > 144.dp)
        // A physical tap on the formerly exposed surface must activate Retry.
        offline.performTouchInput { click(Offset(2.dp.toPx(), center.y)) }
        composeRule.runOnIdle { assertEquals(1, retryTaps) }
        assertTrue("target $target must enclose pill $pill", target.left <= pill.left && target.bottom >= pill.bottom)
        assertTrue("Retry retains its minimum height: $target", target.height >= 48.dp - 0.5.dp)
        assertEquals(pill.top.value, target.top.value, 0.5f)
        assertEquals(pill.right.value, target.right.value, 0.5f)
        val error = composeRule.onNodeWithTag("transient_error_notice")
        assertEquals(12f, (error.getUnclippedBoundsInRoot().top - pill.bottom).value, 0.5f)
        error.assertHasNoClickAction().performTouchInput { click(center) }
        composeRule.runOnIdle { assertEquals(1, retryTaps) }
        retry.performTouchInput { click(Offset(2.dp.toPx(), bottom - 2.dp.toPx())) }
        composeRule.runOnIdle { assertEquals(2, retryTaps) }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun confirmationFollowsNavigationAndThreadErrors_belowStoppedTurn_withoutInheritingActions() {
        var compactTaps = 0
        composeRule.setContent {
            val navigation = rememberTransientErrorNoticeState()
            LaunchedEffect(navigation) { navigation.enqueue(this, "navigation error") }
            CompositionLocalProvider(LocalNavigationErrorNotice provides navigation) {
                PyrycodeMobileTheme(dynamicColor = false) {
                    ThreadTopOverlay(
                        usageLimit = warning,
                        usageLimitDismissed = false,
                        onDismissUsageLimit = {},
                        showRePair = false,
                        onRePair = {},
                        sessionError = "session.blocked",
                        turnOutcome = TurnRecoveryNotice.ContextTooLong,
                        onCompact = { compactTaps++ },
                        transientError = "thread error",
                        confirmation = "File saved",
                    )
                }
            }
        }
        composeRule.mainClock.autoAdvance = false
        val threadError = composeRule.onNodeWithText("thread error").getUnclippedBoundsInRoot()
        val navigationError = composeRule.onNodeWithText("navigation error").getUnclippedBoundsInRoot()
        val confirmation = composeRule.onNodeWithTag("transient_confirmation_notice").assertHasNoClickAction()
        val bounds = confirmation.getUnclippedBoundsInRoot()
        assertEquals(12f, (navigationError.top - threadError.bottom).value, 0.5f)
        assertEquals(12f, (bounds.top - navigationError.bottom).value, 0.5f)
        confirmation.performTouchInput { click(center) }
        composeRule.runOnIdle { assertEquals(0, compactTaps) }
        composeRule.mainClock.advanceTimeBy(4_100)
        composeRule.onNodeWithText("navigation error").assertDoesNotExist()
        val after = confirmation.getUnclippedBoundsInRoot()
        assertEquals(12f, (after.top - threadError.bottom).value, 0.5f)
    }

    // AC #4: a live usage reading no longer masks live turn status.
    @Test
    fun aLiveReading_leavesTheRunningTool_theWrapUp_andInterruptedInTheStatusRow() {
        usageLimit = warning
        isBusy = true
        state =
            state.copy(
                isPromoted = true,
                hasMessages = true,
                items = listOf(runningTool("Bash")),
            )
        setScreen()
        composeRule.onNodeWithContentDescription(label("allowed_warning")).assertIsDisplayed()
        composeRule.onNodeWithText("Running Bash…").assertIsDisplayed()

        resetting = ResetStatus(ResetStatus.Phase.WrappingUp, ResetStatus.Handoff.Pending)
        composeRule
            .onNodeWithContentDescription(context.getString(R.string.thread_resetting_wrapping_up))
            .assertIsDisplayed()

        resetting = null
        isBusy = false
        turnOutcome = TurnRecoveryNotice.ContextTooLong
        composeRule
            .onNodeWithText(context.getString(R.string.thread_recovery_context), substring = true, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(label("allowed_warning")).assertIsDisplayed()
    }

    private fun runningTool(name: String): ThreadItem.MessageItem =
        ThreadItem.MessageItem(
            Message(
                id = "t1",
                sessionId = "s1",
                role = Role.Tool,
                content = "",
                timestamp = Instant.parse("2026-09-24T10:00:00Z"),
                isStreaming = false,
                toolCall = ToolCall(toolName = name, input = "", output = "", status = ToolCallStatus.Running),
            ),
        )

    private companion object {
        const val RE_PAIR_LABEL = "Pairing error - Re-pair"
        const val OFFLINE_RETRY_LABEL = "Offline · Retry"
        const val MCP_FAILED_LABEL = "MCP server github failed"
        const val NEARLY_LABEL = "Nearly at usage limit - 7-day window"
        const val REACHED_LABEL = "Usage limit reached - 7-day window"
    }
}
