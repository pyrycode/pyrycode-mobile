package de.pyryco.mobile.e2e

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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

/** Later reply rendering can displace a finished marker after its first reveal. */
internal fun ComposeTestRule.assertFinishedAgentMarker(
    agentId: String,
    markerMatcher: SemanticsMatcher,
    finishedLabel: String,
    readinessTimeoutMillis: Long = 10_000,
    resolveAgentId: (Long) -> String? = { agentId },
    evidence: (String) -> Unit = {},
) {
    val deadline = System.nanoTime() + readinessTimeoutMillis * 1_000_000
    var lastReadiness: String? = null

    fun remainingMillis(): Long {
        val remaining = (deadline - System.nanoTime()) / 1_000_000
        if (remaining <= 0) throw ComposeTimeoutException("finished Agent readiness exceeded its original deadline")
        return remaining
    }

    fun currentMarkerKey(): String {
        var readyKey: String? = null
        waitUntil(remainingMillis()) {
            val key = resolveAgentId(remainingMillis())?.let { "agent-start:$it" }
            val lists = onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes()
            assertTrue("finished marker needs one message list", lists.size <= 1)
            val index =
                key?.let {
                    lists
                        .singleOrNull()
                        ?.config
                        ?.getOrNull(SemanticsProperties.IndexForKey)
                        ?.invoke(it)
                } ?: -1
            val sample = "event=finished_agent_readiness lists=${lists.size} key_present=${index >= 0}"
            if (sample != lastReadiness) {
                evidence(sample)
                lastReadiness = sample
            }
            if (index >= 0) readyKey = key
            index >= 0
        }
        return requireNotNull(readyKey)
    }

    fun revealCurrentMarker(): Int {
        var revealedIndex = -1
        waitUntil(remainingMillis()) {
            val key = currentMarkerKey()
            val lists = onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes()
            assertTrue("finished marker needs one message list", lists.size <= 1)
            lists.singleOrNull()?.let { node ->
                // Validate and dispatch together: a generation may change after readiness.
                runOnIdle {
                    if (node.layoutInfo.isAttached) {
                        val config = node.config
                        val index = config.getOrNull(SemanticsProperties.IndexForKey)?.invoke(key) ?: -1
                        if (index >= 0) {
                            val scroll = requireNotNull(config.getOrNull(SemanticsActions.ScrollToIndex)?.action)
                            assertTrue("the owned marker scroll must be accepted", scroll(index))
                            revealedIndex = index
                        }
                    }
                }
            }
            revealedIndex >= 0
        }
        return revealedIndex
    }
    val target = questionAnswerTargetWithCurrentKey(markerMatcher, ::currentMarkerKey, ::revealCurrentMarker, evidence)
    currentMarkerKey()
    target.assert(hasText(finishedLabel)).assertIsDisplayed()
}

/** One admitted snapshot supplies the rendered identity of the same raw owned Agent. */
internal data class AgentRunNavigationFamily(
    val agentId: String,
    val runId: String,
    val childIds: List<String>,
    val ownedChild: SemanticsMatcher,
    val ownedChildKey: String? = null,
)

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
    currentFamily: ((Long) -> AgentRunNavigationFamily?)? = null,
    evidence: (String) -> Unit = {},
) {
    require(childIds.isNotEmpty()) { "Agent proof needs loaded owned children" }
    var family = AgentRunNavigationFamily(agentId, runId, childIds, ownedChild, ownedChildKey)
    val list = onNode(hasScrollToNodeAction())
    val root = SemanticsMatcher("current owned Agent") { hasTestTag("background-agent:${family.agentId}").matches(it) }
    val run =
        SemanticsMatcher("current owned run") {
            (
                hasText(
                    "Using tools:",
                    substring = true,
                ) and hasClickAction() and hasAnyAncestor(hasTestTag("tool-run:${family.runId}"))
            ).matches(it)
        }
    val currentOwnedChild = SemanticsMatcher("current owned child") { family.ownedChild.matches(it) }
    var lastReadiness: String? = null

    fun remainingMillis(deadline: Long): Long {
        val remaining = (deadline - System.nanoTime()) / 1_000_000
        if (remaining <= 0) throw ComposeTimeoutException("Agent navigation readiness exceeded its original deadline")
        return remaining
    }

    fun awaitFamily(deadline: Long): AgentRunNavigationFamily {
        waitUntil(remainingMillis(deadline)) {
            val next = if (currentFamily == null) family else currentFamily.invoke(remainingMillis(deadline))
            val lists = onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes()
            assertTrue("Agent proof needs one message list", lists.size <= 1)
            val index = lists.singleOrNull()?.config?.getOrNull(SemanticsProperties.IndexForKey)
            val indexes =
                next?.let { f ->
                    listOf("msg:${f.agentId}", "agent-start:${f.agentId}", "msg:${f.runId}").map {
                        index?.invoke(it)
                            ?: -1
                    }
                }
            val ready = indexes != null && indexes.all { it >= 0 }
            val sample = "event=agent_run_readiness ready=$ready lists=${lists.size} key_indexes=$indexes"
            if (sample != lastReadiness) {
                evidence(sample)
                lastReadiness = sample
            }
            if (ready) {
                val readyFamily = checkNotNull(next)
                require(readyFamily.childIds.isNotEmpty() && readyFamily.runId in readyFamily.childIds) {
                    "Agent proof needs its exact owned run and children"
                }
                family = readyFamily
            }
            ready
        }
        return family
    }

    // Only the generation-aware caller opts in; the default helper keeps strict removed-key failure.
    fun <T> withCurrentReveal(
        deadline: Long,
        key: (AgentRunNavigationFamily) -> String,
        operation: (() -> String, () -> Int) -> T,
    ): T {
        fun currentKey(): String = key(awaitFamily(deadline))

        fun reveal(): Int {
            var revealed = -1
            waitUntil(remainingMillis(deadline)) {
                val ownedKey = currentKey()
                val lists = onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes()
                assertTrue("Agent proof needs one message list", lists.size <= 1)
                lists.singleOrNull()?.let { node ->
                    runOnIdle {
                        if (node.layoutInfo.isAttached) {
                            val config = node.config
                            val index = config.getOrNull(SemanticsProperties.IndexForKey)?.invoke(ownedKey) ?: -1
                            if (index >= 0) {
                                assertTrue(
                                    "owned navigation scroll must be accepted",
                                    requireNotNull(config.getOrNull(SemanticsActions.ScrollToIndex)?.action)(index),
                                )
                                revealed = index
                            }
                        }
                    }
                }
                revealed >= 0
            }
            return revealed
        }
        return operation(::currentKey, ::reveal)
    }

    fun runTarget(
        matcher: SemanticsMatcher,
        deadline: Long,
    ) = if (currentFamily == null) {
        questionAnswerTarget(matcher)
    } else {
        withCurrentReveal(
            deadline,
            { "msg:${it.runId}" },
        ) { key, reveal -> questionAnswerTargetWithCurrentKey(matcher, key, reveal, evidence) }
    }

    fun expansion(label: String) =
        run and
            SemanticsMatcher("owned run expansion action") {
                it.config.getOrNull(SemanticsActions.OnClick)?.label == label
            }
    val closed = expansion(expandLabel)
    val opened = expansion(collapseLabel)

    fun childIndexes(): List<Int> {
        val index = list.fetchSemanticsNode().config[SemanticsProperties.IndexForKey]
        return family.childIds.map { index("msg:$it") }
    }

    // The closed header now represents the first tool under that tool's message key (#1940).
    // All other child keys must disappear; opening restores every child's independent row.
    fun childMembership(expanded: Boolean): Boolean =
        family.childIds.zip(childIndexes()).all { (id, index) -> (index >= 0) == (expanded || id == family.runId) }

    fun identity(id: String) =
        MessageDigest
            .getInstance("SHA-256")
            .digest(id.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }

    fun identities() = "agent_hash=${identity(family.agentId)} run_hash=${identity(family.runId)} children=${family.childIds.size}"

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
        val children = onAllNodes(currentOwnedChild, useUnmergedTree = true).fetchSemanticsNodes()
        val visible = children.count { it.boundsInRoot.height > 0 && it.boundsInRoot.center.y in top..bottom }
        evidence(
            "event=agent_run_proof stage=$stage ${identities()} clickable=${action != null} state=$state " +
                "target=$bounds tap=${bounds?.center} header_bottom=$top composer_top=$bottom " +
                "child_indexes=${childIndexes()} owned_composed=${children.size} owned_visible=$visible",
        )
    }

    fun assertClosed(
        stage: String,
        deadline: Long,
    ) {
        runTarget(closed, deadline).assertIsDisplayed()
        record(stage)
        assertTrue("only the first-tool representative may retain a collapsed child key", childMembership(expanded = false))
        onAllNodes(currentOwnedChild, useUnmergedTree = true).assertCountEquals(0)
    }

    fun tapAndAwait(
        from: SemanticsMatcher,
        to: SemanticsMatcher,
        stage: String,
        expandedAfter: Boolean,
    ): Long {
        val deadline = System.nanoTime() + 10_000L * 1_000_000
        val target = runTarget(from, deadline)
        record("before-$stage")
        target.performTouchInput { click(center) }
        try {
            waitUntil(if (currentFamily == null) 10_000 else remainingMillis(deadline)) { childMembership(expandedAfter) }
            // Opening a tall block can dispose its header. Reveal it once after the state transition.
            if (currentFamily == null) list.performScrollToNode(run) else runTarget(to, deadline)
            onNode(to).assertIsDisplayed()
        } finally {
            record("after-$stage")
        }
        return deadline
    }

    // A repository sample is not a render fence; later generation-aware reveals resample too.
    val navigationDeadline = System.nanoTime() + 10_000L * 1_000_000
    awaitFamily(navigationDeadline)
    assertClosed("before-navigation", navigationDeadline)
    if (currentFamily == null) {
        questionAnswerTap(markerMatcher, lazyKey = "agent-start:${family.agentId}", evidence = evidence)
    } else {
        withCurrentReveal(navigationDeadline, {
            "agent-start:${it.agentId}"
        }) { key, reveal -> questionAnswerTapWithCurrentKey(markerMatcher, key, reveal, evidence) }
    }
    waitUntil(
        if (currentFamily ==
            null
        ) {
            10_000
        } else {
            remainingMillis(navigationDeadline)
        },
    ) { onAllNodes(root).fetchSemanticsNodes().size == 1 }
    onNode(root).assertIsDisplayed()
    assertClosed("after-navigation", navigationDeadline)
    tapAndAwait(closed, opened, "open", expandedAfter = true)
    assertTrue("opened run must contain every loaded child key", childIndexes().all { it >= 0 })
    // Positive owned visibility first: an off-screen paragraph is not evidence of collapse.
    if (family.ownedChildKey == null) {
        list.performScrollToNode(currentOwnedChild)
    } else {
        assertTrue("reveal key belongs to this Agent", family.ownedChildKey in family.childIds)
        val index = list.fetchSemanticsNode().config[SemanticsProperties.IndexForKey]("msg:${family.ownedChildKey}")
        assertTrue("the exact owned child remains in the opened run", index >= 0)
        list.performScrollToIndex(index)
    }
    // Claude may emit several paragraphs or split text around tools. Visibility, not a
    // single prescribed paragraph, is the navigation contract.
    val top = onNodeWithTag("thread-top-bar").fetchSemanticsNode().boundsInRoot.bottom
    val bottom = onNodeWithTag("thread-composer").fetchSemanticsNode().boundsInRoot.top
    assertTrue(
        "opened run must reveal attributed assistant text",
        onAllNodes(currentOwnedChild, useUnmergedTree = true).fetchSemanticsNodes().any {
            it.boundsInRoot.height > 0 && it.boundsInRoot.bottom > top && it.boundsInRoot.top < bottom
        },
    )
    record("owned-child-revealed")
    val closeDeadline = tapAndAwait(opened, closed, "close", expandedAfter = false)
    assertClosed("closed-confirmed", closeDeadline)
    assertEquals("one owned control after close", 1, onAllNodes(closed).fetchSemanticsNodes().size)
}
