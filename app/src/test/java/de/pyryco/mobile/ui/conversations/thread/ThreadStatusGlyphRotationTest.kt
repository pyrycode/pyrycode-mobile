package de.pyryco.mobile.ui.conversations.thread

import android.animation.ValueAnimator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.repository.ApiRetryStatus
import de.pyryco.mobile.ui.conversations.components.STATUS_GLYPH_TEST_TAG
import de.pyryco.mobile.ui.conversations.components.StatusGlyphRotation
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * #1312: the status snowflake turns once every 1.6 s, linearly, while the turn is busy or the local send is
 * pending, and is still otherwise. JVM only, not `sharedTest`: it sets the system animator duration scale
 * through the framework's hidden `ValueAnimator.setDurationScale`, which a device refuses to an app.
 */
@RunWith(AndroidJUnit4::class)
class ThreadStatusGlyphRotationTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var isBusy by mutableStateOf(false)
    private var isThinking by mutableStateOf(false)
    private var localSendStage by mutableStateOf(LocalSendStage.None)
    private var apiRetry by mutableStateOf<ApiRetryStatus>(ApiRetryStatus.NotRetrying)

    /** What "Remove animations" sets: `ValueAnimator.areAnimatorsEnabled()` reads false at scale 0. */
    private fun setAnimatorDurationScale(scale: Float) {
        ReflectionHelpers.callStaticMethod<Any?>(
            ValueAnimator::class.java,
            "setDurationScale",
            ClassParameter.from(Float::class.javaPrimitiveType, scale),
        )
    }

    @Before
    fun animatorsOn() {
        setAnimatorDurationScale(1f)
    }

    @After
    fun restoreAnimators() {
        setAnimatorDurationScale(1f)
    }

    private fun setThread() {
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ThreadScreen(
                    state = ThreadUiState(conversationId = "c1", displayName = "Test channel", isPromoted = true),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    isThinking = isThinking,
                    isBusy = isBusy,
                    localSendStage = localSendStage,
                    apiRetry = apiRetry,
                )
            }
        }
        composeTestRule.mainClock.advanceTimeByFrame()
    }

    private fun angle(): Float =
        composeTestRule
            .onNodeWithTag(STATUS_GLYPH_TEST_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[StatusGlyphRotation]

    /** Applies the test's state writes, as an unpaused frame would, then moves the paused clock. */
    private fun advance(millis: Long) {
        Snapshot.sendApplyNotifications()
        composeTestRule.mainClock.advanceTimeBy(millis)
    }

    /** The turn between two readings, folded to (-180, 180]. */
    private fun turned(
        from: Float,
        to: Float,
    ): Float = ((to - from) % 360f + 540f) % 360f - 180f

    private fun label(
        id: Int,
        vararg args: Any,
    ) = InstrumentationRegistry
        .getInstrumentation()
        .targetContext
        .getString(id, *args)

    @Test
    fun busy_turnsLinearly_unbrokenAcrossThinkingWorkingAndApiRetry() {
        isBusy = true
        isThinking = true
        setThread()
        advance(200)
        val first = angle()
        advance(400)
        val thinking = angle()
        // 400 ms of a 1600 ms turn is 90°; a frame of slack either way.
        assertEquals(90f, turned(first, thinking), 4f)

        isThinking = false
        advance(400)
        composeTestRule.onNodeWithText(label(R.string.thread_working_label)).assertIsDisplayed()
        val working = angle()
        assertEquals(90f, turned(thinking, working), 4f)

        apiRetry = ApiRetryStatus.Attempt(current = 2, total = 10)
        advance(400)
        composeTestRule.onNodeWithText(label(R.string.thread_api_retry_label, 2, 10)).assertIsDisplayed()
        assertEquals(90f, turned(working, angle()), 4f)
    }

    @Test
    fun localStages_turnTheGlyph_withoutRestartingOnAcknowledgement_thenStopOnClosure() {
        localSendStage = LocalSendStage.Sending
        setThread()
        composeTestRule.onNodeWithText(label(R.string.thread_sending_label)).assertIsDisplayed()
        advance(200)
        val first = angle()
        advance(400)
        val sending = angle()
        assertEquals(90f, turned(first, sending), 4f)

        localSendStage = LocalSendStage.Waiting
        advance(400)
        composeTestRule.onNodeWithText(label(R.string.thread_waiting_label)).assertIsDisplayed()
        assertEquals(90f, turned(sending, angle()), 4f)

        localSendStage = LocalSendStage.None
        advance(100)
        val stopped = angle()
        advance(700)
        assertEquals(stopped, angle())
    }

    @Test
    fun removeAnimations_keepsBothLocalLabels_andTheGlyphStill() {
        setAnimatorDurationScale(0f)
        localSendStage = LocalSendStage.Sending
        setThread()
        advance(700)
        composeTestRule.onNodeWithText(label(R.string.thread_sending_label)).assertIsDisplayed()
        assertEquals(0f, angle())

        localSendStage = LocalSendStage.Waiting
        advance(700)
        composeTestRule.onNodeWithText(label(R.string.thread_waiting_label)).assertIsDisplayed()
        assertEquals(0f, angle())
    }

    @Test
    fun idle_andIdleApiRetry_holdTheGlyphStill() {
        setThread()
        assertEquals(0f, angle())
        advance(700)
        assertEquals(0f, angle())

        apiRetry = ApiRetryStatus.AttemptUnknown
        advance(700)
        composeTestRule.onNodeWithText(label(R.string.thread_api_retry_label_unknown)).assertIsDisplayed()
        assertEquals(0f, angle())
    }

    @Test
    fun turnEnd_stopsTheGlyph() {
        isBusy = true
        setThread()
        advance(500)
        isBusy = false
        advance(100)
        val stopped = angle()
        advance(700)
        assertEquals(stopped, angle())
    }

    @Test
    fun removeAnimations_holdsTheGlyphStill_andKeepsTheLabels() {
        setAnimatorDurationScale(0f)
        isBusy = true
        isThinking = true
        setThread()
        advance(700)
        assertEquals(0f, angle())
        composeTestRule.onNodeWithText(label(R.string.thread_thinking_label)).assertIsDisplayed()

        isThinking = false
        advance(100)
        composeTestRule.onNodeWithText(label(R.string.thread_working_label)).assertIsDisplayed()
        assertEquals(0f, angle())
    }
}
