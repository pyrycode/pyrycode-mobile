package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.conversations.components.formatUsageLimitReset
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Rung 2 (#804): claude's usage-limit report driven through the real [RemoteConversationRepository]
 * `rate_limited` projection (#802) and rendered as the Top overlay's usage pill (#1002) on the
 * [ScriptedThreadHarness], in [ScriptedApiRetryTest]'s shape. Live behaviour is #679's.
 *
 * Assertions are on the pill's content description, which is its visible label. Since #1002 the pill is a
 * notice beside the status row, not an arm of it, so every status-row signal still shows while a reading
 * is live. Where a scenario proves an **absence**, a later `turn_state` frame is the sync point: frames
 * fold in order on the one inbound collector.
 */
@RunWith(AndroidJUnit4::class)
class ScriptedUsageLimitTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: ScriptedThreadHarness

    private val thinkingDescription: String = string(R.string.cd_thread_thinking)

    private val compactingDescription: String = string(R.string.cd_thread_compacting)

    @Before
    fun setUp() {
        harness = ScriptedThreadHarness(composeRule)
        harness.start()
    }

    @After
    fun tearDown() {
        harness.close()
    }

    // AC #1: claude's status shows verbatim, inside the attributed lead; #1002 keeps thinking beside it.
    @Test
    fun reading_showsClaudesStatus_besideTheThinkingLabel() {
        harness.pushTurnState("thinking")
        awaitDisplayed(thinkingDescription)

        harness.pushRateLimited(status = "allowed_warning", utilization = 0.94)

        awaitDisplayed(label("allowed_warning", spent = 94))
        composeRule.onNodeWithContentDescription(thinkingDescription).assertIsDisplayed()
    }

    // AC #1: conversation-level, not turn-scoped — it shows while the turn is idle too.
    @Test
    fun reading_showsWhileTheTurnStateIsIdle() {
        harness.pushTurnState("idle")
        harness.pushRateLimited(status = "allowed_warning")

        awaitDisplayed(label("allowed_warning"))
    }

    // AC #2: a usable resets_at names when claude says the limit lifts.
    @Test
    fun futureResetsAt_namesTheReset() {
        val resetsAt = Clock.System.now().epochSeconds + 2 * 60 * 60
        harness.pushRateLimited(status = "allowed_warning", resetsAt = resetsAt)

        val resets = formatUsageLimitReset(resetsAt, Clock.System.now(), TimeZone.currentSystemDefault(), Locale.getDefault())
        awaitDisplayed(label("allowed_warning", resets = resets))
    }

    // AC #2: `0` and an absurd far-future value render without a fabricated date.
    @Test
    fun zeroAndAbsurdResetsAt_renderNoResetClause() {
        harness.pushRateLimited(status = "allowed_warning", resetsAt = 0L)
        awaitDisplayed(label("allowed_warning"))
        assertNoTextContaining(RESETS_FRAGMENT)

        harness.pushRateLimited(status = "rejected", resetsAt = 1_200_000_000_000L)
        awaitDisplayed(label("rejected"))
        assertNoTextContaining(RESETS_FRAGMENT)
    }

    // AC #4 (expiry): a reading whose resets_at has already passed never takes the slot.
    @Test
    fun pastResetsAt_neverShows() {
        harness.pushRateLimited(status = "allowed_warning", resetsAt = Clock.System.now().epochSeconds - 60)
        harness.pushTurnState("thinking")

        awaitDisplayed(thinkingDescription)
        composeRule.onNodeWithContentDescription(label("allowed_warning")).assertDoesNotExist()
    }

    // AC #3: an absent utilization renders no spent figure, and an out-of-range one drives none either.
    @Test
    fun absentOrOutOfRangeUtilization_rendersNoSpentFigure() {
        harness.pushRateLimited(status = "allowed_warning", utilization = null)
        awaitDisplayed(label("allowed_warning"))
        assertNoTextContaining("%")

        harness.pushRateLimited(status = "rejected", utilization = 1.5)
        awaitDisplayed(label("rejected"))
        assertNoTextContaining("%")
    }

    // AC #4: the benign frame clears the arm, even naming a different limit than the warning it clears.
    @Test
    fun benignFrame_clearsTheArm() {
        harness.pushRateLimited(status = "allowed_warning", limitType = "seven_day")
        awaitDisplayed(label("allowed_warning"))

        harness.pushRateLimited(status = "allowed", limitType = "five_hour")
        // The sync point: thinking first shows only once the frame after the benign one has folded.
        harness.pushTurnState("thinking")

        awaitDisplayed(thinkingDescription)
        composeRule.onNodeWithContentDescription(label("allowed_warning")).assertDoesNotExist()
    }

    // #1002: api-retry and the reading show together — the pill is not a status-row arm.
    @Test
    fun apiRetry_andTheReading_showTogether() {
        harness.pushRateLimited(status = "allowed_warning")
        awaitDisplayed(label("allowed_warning"))

        harness.pushApiRetry(active = true, current = 3, total = 10)

        awaitDisplayed(string(R.string.cd_thread_api_retry, 3, 10))
        composeRule.onNodeWithContentDescription(label("allowed_warning")).assertIsDisplayed()
    }

    // #1002: compaction keeps the status row while a reading is live.
    @Test
    fun compaction_andTheReading_showTogether() {
        harness.pushCompacting(active = true)
        awaitDisplayed(compactingDescription)

        harness.pushRateLimited(status = "allowed_warning")

        awaitDisplayed(label("allowed_warning"))
        composeRule.onNodeWithContentDescription(compactingDescription).assertIsDisplayed()
    }

    private fun label(
        status: String,
        spent: Int? = null,
        resets: String? = null,
    ): String =
        buildString {
            append(string(R.string.thread_usage_limit_label, string(R.string.agent_name_claude), status))
            if (spent != null) append(string(R.string.thread_usage_limit_spent, spent))
            if (resets != null) append(string(R.string.thread_usage_limit_resets, resets))
        }

    private fun string(
        id: Int,
        vararg formatArgs: Any,
    ): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(id, *formatArgs)

    private fun awaitDisplayed(description: String) {
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule
                .onAllNodesWithContentDescription(description)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
    }

    /** Searched on the **unmerged** tree so the raw label `Text` cannot hide behind the merged node. */
    private fun assertNoTextContaining(fragment: String) {
        composeRule
            .onNodeWithText(fragment, substring = true, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val RESETS_FRAGMENT = "resets"
    }
}
