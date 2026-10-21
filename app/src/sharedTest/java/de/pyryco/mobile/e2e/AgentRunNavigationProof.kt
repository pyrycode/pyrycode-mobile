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
import androidx.compose.ui.test.performScrollToIndex
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
    markerMatcher: SemanticsMatcher = hasText(goLabel) and hasClickAction(),
    ownedChildKey: String? = null,
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

    // The closed header now represents the first tool under that tool's message key (#1940).
    // All other child keys must disappear; opening restores every child's independent row.
    fun childMembership(expanded: Boolean): Boolean =
        childIds.zip(childIndexes()).all { (id, index) -> (index >= 0) == (expanded || id == runId) }

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
        assertTrue("only the first-tool representative may retain a collapsed child key", childMembership(expanded = false))
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
            waitUntil(10_000) { childMembership(expandedAfter) }
            // Opening a tall block can dispose its header. Reveal it once after the state transition.
            list.performScrollToNode(run)
            onNode(to).assertIsDisplayed()
        } finally {
            record("after-$stage")
        }
    }

    // Repository evidence can precede a reset/catch-up frame on the screen. Wait for the
    // exact family in the merged list; an absent list or a different Agent is not ready.
    var lastReadiness: String? = null
    waitUntil(10_000) {
        val lists = onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes()
        assertTrue("Agent proof needs one message list", lists.size <= 1)
        val index = lists.singleOrNull()?.config?.getOrNull(SemanticsProperties.IndexForKey)
        val keys = listOf("msg:$agentId", "agent-start:$agentId", "msg:$runId")
        val indexes = keys.map { index?.invoke(it) ?: -1 }
        val ready = indexes.all { it >= 0 }
        val sample = "event=agent_run_readiness ready=$ready lists=${lists.size} key_indexes=$indexes"
        if (sample != lastReadiness) {
            evidence(sample)
            lastReadiness = sample
        }
        ready
    }
    assertClosed("before-navigation")
    questionAnswerTap(markerMatcher, lazyKey = "agent-start:$agentId", evidence = evidence)
    waitUntil(10_000) { onAllNodes(root).fetchSemanticsNodes().size == 1 }
    onNode(root).assertIsDisplayed()
    assertClosed("after-navigation")
    tapAndAwait(closed, opened, "open", expandedAfter = true)
    assertTrue("opened run must contain every loaded child key", childIndexes().all { it >= 0 })
    // Positive owned visibility first: an off-screen paragraph is not evidence of collapse.
    if (ownedChildKey == null) {
        list.performScrollToNode(ownedChild)
    } else {
        assertTrue("reveal key belongs to this Agent", ownedChildKey in childIds)
        val index = list.fetchSemanticsNode().config[SemanticsProperties.IndexForKey]("msg:$ownedChildKey")
        assertTrue("the exact owned child remains in the opened run", index >= 0)
        list.performScrollToIndex(index)
    }
    // Claude may emit several paragraphs or split text around tools. Visibility, not a
    // single prescribed paragraph, is the navigation contract.
    val top = onNodeWithTag("thread-top-bar").fetchSemanticsNode().boundsInRoot.bottom
    val bottom = onNodeWithTag("thread-composer").fetchSemanticsNode().boundsInRoot.top
    assertTrue(
        "opened run must reveal attributed assistant text",
        onAllNodes(ownedChild, useUnmergedTree = true).fetchSemanticsNodes().any {
            it.boundsInRoot.height > 0 && it.boundsInRoot.bottom > top && it.boundsInRoot.top < bottom
        },
    )
    record("owned-child-revealed")
    tapAndAwait(opened, closed, "close", expandedAfter = false)
    assertClosed("closed-confirmed")
    assertEquals("one owned control after close", 1, onAllNodes(closed).fetchSemanticsNodes().size)
}
