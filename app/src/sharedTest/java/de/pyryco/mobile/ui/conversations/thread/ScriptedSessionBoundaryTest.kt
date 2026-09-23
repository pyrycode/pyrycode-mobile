package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Layer 1b (#473, split from #435): a folded `ThreadItem.SessionBoundary` draws as a session-divider
 * delimiter on screen, positioned between the two messages that straddle it. Rides
 * [ScriptedThreadHarness] (#432), which injects the **real** [RemoteConversationRepository] fold (#336) —
 * so this exercises the production `session_transition` → `SessionBoundary` fold, not the boundary
 * `FakeConversationRepository` synthesizes (the two-folds gotcha #337: drive the real repo, not the fake
 * and not the dormant ViewModel path). Assertions are **tolerant** per the `docs/e2e-interactive-stream.md`
 * ladder-doc rule (substring / presence / coordinate-ordering, generous `waitUntil`), never on delta
 * counts or timing. Runs under `./gradlew connectedAndroidTest` (device/emulator required), alongside
 * [ScriptedThreadRenderTest].
 */
@RunWith(AndroidJUnit4::class)
class ScriptedSessionBoundaryTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: ScriptedThreadHarness

    @Before
    fun setUp() {
        harness = ScriptedThreadHarness(composeRule)
        harness.start()
    }

    @After
    fun tearDown() {
        harness.close()
    }

    // A scripted stream crossing a session boundary: message 1 (first session, finalized) → a
    // `session_transition` (reason `clear`, the simplest — workspace-null, label "New session — <time>")
    // → message 2 (second session, finalized). The two distinct-turn_id assistant turns are the "messages
    // in two consecutive sessions"; the harness needs no `message` scripting — the wired
    // `assistant_delta → turn_end` path produces rendered message rows (see ScriptedThreadRenderTest).
    // Texts are plain alphanumeric and collide with neither each other nor the delimiter strings, so
    // substring matching is reliable (MarkdownText renders plain text 1:1).
    @Test
    fun sessionBoundary_drawsDelimiterBetweenTwoSessionsMessages() {
        harness.pushAssistantDelta("t1", 0, M1)
        harness.pushTurnEnd("t1")
        harness.pushSessionTransition(previousSessionId = "s1", newSessionId = "s2", reason = "clear")
        harness.pushAssistantDelta("t2", 0, M2)
        harness.pushTurnEnd("t2")

        // Gate on the slowest-arriving signal (m2): once it renders, m1 and the boundary have already
        // folded into the same projected list, so every bounds read below is over a laid-out node.
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MS) {
            composeRule
                .onAllNodesWithText(M2, substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.waitForIdle()

        // AC #2 — the explanatory label renders: the reason-independent explanation AND the per-reason
        // label. Assert the hardcoded "New session" prefix, not the locale/timezone-formatted <time> tail,
        // to stay locale-robust.
        composeRule.onNodeWithText(EXPLANATION, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(CLEAR_LABEL_PREFIX, substring = true).assertIsDisplayed()

        // AC #1 (exactly one) — one `session_transition` → one folded boundary → one rendered delimiter.
        // A structural one-to-one (deterministic, timing-independent), not the kind of delta count the
        // ladder-doc forbids.
        assertEquals(
            1,
            composeRule
                .onAllNodesWithText(EXPLANATION, substring = true)
                .fetchSemanticsNodes()
                .size,
        )

        // AC #1 (between) — vertical order m1 < delimiter < m2. `reverseLayout` renders chronological order
        // top-to-bottom, so the first session's message sits above the boundary and the second below. Read
        // the tight text-node rects via the unmerged tree (MessageBubble's text resolves to its own node),
        // mirroring QueuedBacklogTest.
        val m1Top =
            composeRule
                .onNodeWithText(M1, substring = true, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
                .top
        val delimiterTop =
            composeRule
                .onNodeWithText(EXPLANATION, substring = true, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
                .top
        val m2Top =
            composeRule
                .onNodeWithText(M2, substring = true, useUnmergedTree = true)
                .getUnclippedBoundsInRoot()
                .top
        assertTrue(
            "delimiter must sit between m1 and m2 (m1Top=$m1Top, delimiterTop=$delimiterTop, m2Top=$m2Top)",
            m1Top < delimiterTop && delimiterTop < m2Top,
        )
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L

        // Plain-alphanumeric message bodies — distinct from each other and from every delimiter string.
        const val M1 = "alpha line"
        const val M2 = "omega line"

        // SessionBoundaryDelimiter's reason-independent explanation Text.
        const val EXPLANATION = "Claude doesn't remember messages above this line"

        // boundaryLabel(BoundaryReason.Clear) hardcoded prefix; the <time> tail is locale/timezone-formatted.
        const val CLEAR_LABEL_PREFIX = "New session"
    }
}
