package de.pyryco.mobile.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import org.junit.Assert.assertTrue

/** Resolve the original held-Agent proof without changing its source or exact owned run matcher. */
internal fun ComposeTestRule.revealHeldAgentRun(
    timeoutMillis: Long,
    resolve: (Long) -> AgentReplyEvidence?,
    evidence: (String) -> Unit = {},
): AgentReplyEvidence {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000

    fun remainingMillis(): Long {
        val remaining = (deadline - System.nanoTime()) / 1_000_000
        if (remaining <= 0) throw ComposeTimeoutException("held Agent run reveal exceeded its original deadline")
        return remaining
    }
    var displayed: AgentReplyEvidence? = null
    var samples = 0
    waitUntil(remainingMillis()) {
        val candidate = resolve(remainingMillis()) ?: return@waitUntil false
        val lists = onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes()
        assertTrue("held Agent proof needs one message list", lists.size <= 1)
        val node = lists.singleOrNull() ?: return@waitUntil false
        val key = "msg:${candidate.runId}"
        val index = node.config.getOrNull(SemanticsProperties.IndexForKey)?.invoke(key) ?: -1
        if (index < 0) return@waitUntil false
        if (samples++ < 12) evidence("event=held_agent_run stage=ready")
        var scrolled = false
        // A reset may replace the generation after readiness. Check and dispatch together.
        runOnIdle {
            if (node.layoutInfo.isAttached) {
                val config = node.config
                val currentIndex = config.getOrNull(SemanticsProperties.IndexForKey)?.invoke(key) ?: -1
                if (currentIndex >= 0) {
                    val scroll = requireNotNull(config.getOrNull(SemanticsActions.ScrollToIndex)?.action)
                    assertTrue("the exact owned run scroll must be accepted", scroll(currentIndex))
                    scrolled = true
                }
            }
        }
        if (!scrolled) return@waitUntil false
        val latest = resolve(remainingMillis()) ?: return@waitUntil false
        if (latest.runId != candidate.runId || latest.agent.block != candidate.agent.block) return@waitUntil false
        val run =
            hasText("Using tools:", substring = true) and hasClickAction() and hasAnyAncestor(hasTestTag("tool-run:${latest.runId}"))
        val matches = onAllNodes(run).fetchSemanticsNodes().size
        assertTrue("the exact owned run must be unique", matches <= 1)
        if (matches == 1) displayed = latest
        matches == 1
    }
    evidence("event=held_agent_run stage=revealed owned=1")
    return checkNotNull(displayed)
}
