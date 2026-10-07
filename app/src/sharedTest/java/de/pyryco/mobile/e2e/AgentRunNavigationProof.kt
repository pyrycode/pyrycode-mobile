package de.pyryco.mobile.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.security.MessageDigest

/** A single pointer tap per transition; list membership distinguishes collapse from lazy disposal. */
internal fun ComposeTestRule.verifyAgentRunNavigation(
    agentId: String,
    runId: String,
    childIds: List<String>,
    ownedChild: SemanticsMatcher,
    goLabel: String,
    expandLabel: String,
    collapseLabel: String,
    evidence: (String) -> Unit = {},
) {
    require(childIds.isNotEmpty()) { "Agent proof needs loaded owned children" }
    val list = onNode(hasScrollToNodeAction())
    val root = hasTestTag("background-agent:$agentId")
    val run = hasText("Using tools:", substring = true) and hasClickAction() and hasAnyAncestor(hasTestTag("tool-run:$runId"))

    fun expansion(label: String) =
        run and
            SemanticsMatcher("owned run expansion action") {
                it.config.getOrNull(SemanticsActions.OnClick)?.label == label
            }
    val closed = expansion(expandLabel)
    val opened = expansion(collapseLabel)

    fun childIndexes(): List<Int> {
        val index = list.fetchSemanticsNode().config[SemanticsProperties.IndexForKey]
        return childIds.map { index("msg:$it") }
    }

    fun identity(id: String) =
        MessageDigest
            .getInstance("SHA-256")
            .digest(id.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
    val identities = "agent_hash=${identity(agentId)} run_hash=${identity(runId)} children=${childIds.size}"

    fun record(stage: String) {
        val control = onAllNodes(run).fetchSemanticsNodes().singleOrNull()
        val action = control?.config?.getOrNull(SemanticsActions.OnClick)
        val state =
            when (action?.label) {
                expandLabel -> "closed"
                collapseLabel -> "open"
                else -> "missing"
            }
        val top = onNodeWithTag("thread-top-bar").fetchSemanticsNode().boundsInRoot.bottom
        val bottom = onNodeWithTag("thread-composer").fetchSemanticsNode().boundsInRoot.top
        val bounds = control?.boundsInRoot
        val children = onAllNodes(ownedChild, useUnmergedTree = true).fetchSemanticsNodes()
        val visible = children.count { it.boundsInRoot.height > 0 && it.boundsInRoot.center.y in top..bottom }
        evidence(
            "event=agent_run_proof stage=$stage $identities clickable=${action != null} state=$state " +
                "target=$bounds tap=${bounds?.center} header_bottom=$top composer_top=$bottom " +
                "child_indexes=${childIndexes()} owned_composed=${children.size} owned_visible=$visible",
        )
    }

    fun assertClosed(stage: String) {
        questionAnswerTarget(closed).assertIsDisplayed()
        record(stage)
        assertTrue("collapsed owned children must be removed from the list, not disposed", childIndexes().all { it < 0 })
        onAllNodes(ownedChild, useUnmergedTree = true).assertCountEquals(0)
    }

    fun tapAndAwait(
        from: SemanticsMatcher,
        to: SemanticsMatcher,
        stage: String,
        expandedAfter: Boolean,
    ) {
        val target = questionAnswerTarget(from)
        record("before-$stage")
        target.performTouchInput { click(center) }
        try {
            waitUntil(10_000) { childIndexes().all { (it >= 0) == expandedAfter } }
            // Opening a tall block can dispose its header. Reveal it once after the state transition.
            list.performScrollToNode(run)
            onNode(to).assertIsDisplayed()
        } finally {
            record("after-$stage")
        }
    }

    assertClosed("before-navigation")
    val marker = questionAnswerTarget(hasText(goLabel) and hasClickAction())
    marker.performTouchInput { click(center) }
    waitUntil(10_000) { onAllNodes(root).fetchSemanticsNodes().size == 1 }
    onNode(root).assertIsDisplayed()
    assertClosed("after-navigation")
    tapAndAwait(closed, opened, "open", expandedAfter = true)
    assertTrue("opened run must contain every loaded child key", childIndexes().all { it >= 0 })
    // Positive owned visibility first: an off-screen paragraph is not evidence of collapse.
    list.performScrollToNode(ownedChild)
    questionAnswerTarget(ownedChild).assertIsDisplayed()
    onAllNodes(ownedChild, useUnmergedTree = true).assertCountEquals(1)
    record("owned-child-revealed")
    tapAndAwait(opened, closed, "close", expandedAfter = false)
    assertClosed("closed-confirmed")
    assertEquals("one owned control after close", 1, onAllNodes(closed).fetchSemanticsNodes().size)
}
