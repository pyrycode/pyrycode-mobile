package de.pyryco.mobile.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.rules.ActivityScenarioRule
import de.pyryco.mobile.MainActivity
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.historyKeys
import de.pyryco.mobile.ui.conversations.components.MESSAGE_BUBBLE_TEST_TAG
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.koin.core.context.GlobalContext

/** Shared rung-3/4 proof; all rows and requests come from the app's existing repository tap. */
internal class DurableGapProof(
    private val rule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>,
    private val serverId: String,
    private val conversationId: String,
) {
    private val cache get() = GlobalContext.get().get<ConversationCache>()
    private val fault = DaemonFaultControl()
    private var finalRows: List<ThreadItem>? = null
    val prefix = "e2e1833-" + System.currentTimeMillis()
    val olderPost = "$prefix-a-000"
    private val batchSize = 120
    val lastOlderPost = "$prefix-a-${(batchSize - 1).toString().padStart(3, '0')}"
    val newestPost = "$prefix-b-${(batchSize - 1).toString().padStart(3, '0')}"

    fun cacheBaseline() {
        DurableHistoryProbe.begin(conversationId)
        runBlocking {
            withTimeout(30_000) {
                while (true) {
                    val rows = cache.readThread(serverId, conversationId).filterIsInstance<ThreadItem.MessageItem>()
                    val coverage = cache.readHistoryPosition(serverId, conversationId)?.coverage
                    // A stale opening page can cover state frames without covering the settled ping.
                    if (rows.isNotEmpty() &&
                        coverage != null &&
                        coverage.spans.isNotEmpty() &&
                        rows.all { row -> row.historyKeys().all { coverage.rowEntries[it].orEmpty().isNotEmpty() } }
                    ) {
                        break
                    }
                    delay(50)
                }
            }
        }
        DurableHistoryProbe.end()
    }

    fun missPages(
        name: String,
        completedReply: () -> Unit,
    ) {
        fault.posts(name, "$prefix-a", batchSize)
        completedReply()
        fault.posts(name, "$prefix-b", batchSize)
        // The same durable home survives, but a fresh process cannot replay the missing ring.
        fault.stop()
        fault.start()
        DurableHistoryProbe.begin(conversationId)
    }

    fun catchUp(
        reconnect: () -> Unit,
        promptText: String,
        replyText: String,
        cachedRows: List<SemanticsMatcher>,
    ) {
        try {
            reconnect()
            try {
                rule.waitUntil(30_000) { DurableHistoryProbe.completed() >= 1 }
            } catch (failure: Throwable) {
                throw AssertionError(
                    "newest completion: asks=${DurableHistoryProbe.asks().size}, pages=${DurableHistoryProbe.completed()}, rows=${messages().size}, gap=${coverageGap()}",
                    failure,
                )
            }
            rule.waitUntil(30_000) { messages().any { it == newestPost } && coverageGap() }
            assertEquals("only availability asks without a gesture", listOf(true), DurableHistoryProbe.asks())
            assertTrue("older post must be outside newest page and empty replay", olderPost !in messages())
            assertTrue("reply must be outside newest page and empty replay", messages().none { it.contains(replyText, ignoreCase = true) })
            // A history page can retain the cached reader anchor. Reveal the already delivered row
            // through semantics, which must not become history demand or certify replay coverage.
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(newestPost))
            rule.onNodeWithText(newestPost, useUnmergedTree = true).assertIsDisplayed()
            assertEquals("newest row visibility is inert", listOf(true), DurableHistoryProbe.asks())
            val expected =
                (0 until batchSize).map { "$prefix-a-${it.toString().padStart(3, '0')}" } +
                    (0 until batchSize).map { "$prefix-b-${it.toString().padStart(3, '0')}" }

            fun recoveredFixture(): Boolean {
                val held = DurableHistoryProbe.rows().filterIsInstance<ThreadItem.MessageItem>()
                val texts = held.map { it.message.content }.toSet()
                return expected.all { it in texts } &&
                    held.any {
                        it.message.role == Role.Assistant && it.message.content.contains(replyText, ignoreCase = true)
                    }
            }
            var pulls = 0
            // Hidden cache fragments are still unresolved metadata, but cannot initiate reader demand.
            // Walk the missing fixture content, then independently prove the displayed marker closes.
            while (!recoveredFixture()) {
                assertTrue("bounded durable gap walk", pulls < 6)
                val count = DurableHistoryProbe.asks().size
                // Semantics scrolling makes the gap visible without a physical gesture.
                rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Load earlier messages"))
                rule.waitForIdle()
                assertEquals("marker visibility is inert", count, DurableHistoryProbe.asks().size)
                pull()
                pulls++
                rule.waitUntil(30_000) { DurableHistoryProbe.completed() == count + 1 }
                rule.waitForIdle()
                // A quiescent interval exposes an accidental page-arrival catch-up loop.
                runBlocking { delay(500) }
                assertEquals("one page per pull; page arrival is inert", count + 1, DurableHistoryProbe.asks().size)
                assertEquals(false, DurableHistoryProbe.asks().last())
            }
            assertTrue("gap spans multiple older pages", pulls >= 2)
            rule.waitUntil(30_000) {
                try {
                    rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Load earlier messages"))
                    false
                } catch (missing: AssertionError) {
                    if (missing.message?.startsWith("No node found that matches") != true) throw missing
                    true
                }
            }
            val posts = messages().filter { it.startsWith(prefix) }
            assertEquals("chronological posts, each once", expected, posts)
            val rows = messages()
            val messageRows = DurableHistoryProbe.rows().filterIsInstance<ThreadItem.MessageItem>()
            // The live user prompt also contains the requested reply text; it is not a reply.
            val reply =
                messageRows.indices.filter {
                    messageRows[it].message.role == Role.Assistant && rows[it].contains(replyText, ignoreCase = true)
                }
            assertEquals("completed reply once", 1, reply.size)
            assertTrue("recovered reply is settled", !messageRows[reply.single()].message.isStreaming)
            assertTrue(
                "reply between the two durable post batches",
                reply.single() > rows.indexOf(lastOlderPost) &&
                    reply.single() < rows.indexOf("$prefix-b-000"),
            )
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(olderPost))
            rule.onNodeWithText(olderPost, useUnmergedTree = true).assertIsDisplayed()
            val asks = DurableHistoryProbe.asks()
            val bubble = hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG))
            assertRenderedOrder(
                cachedRows +
                    listOf(
                        hasText(lastOlderPost) and bubble,
                        hasText(promptText) and bubble,
                        hasText(replyText, ignoreCase = true) and bubble,
                        hasText("$prefix-b-000") and bubble,
                    ),
            )
            assertEquals("rendered row reveals are inert", asks, DurableHistoryProbe.asks())
        } finally {
            finalRows = DurableHistoryProbe.rows()
            DurableHistoryProbe.end()
        }
    }

    fun messages(): List<String> =
        (finalRows ?: DurableHistoryProbe.rows()).filterIsInstance<ThreadItem.MessageItem>().map {
            it.message.content
        }

    /** Compare rendered positions across lazy viewports, rather than repository row indices. */
    private fun assertRenderedOrder(matchers: List<SemanticsMatcher>) {
        val positions =
            matchers.map { matcher ->
                val list = rule.onNode(hasScrollToNodeAction())
                list.performScrollToNode(matcher)
                rule.onAllNodes(matcher, useUnmergedTree = true).assertCountEquals(1)
                val row = rule.onNode(matcher, useUnmergedTree = true)
                row.assertIsDisplayed()
                // ThreadScreen reverses its lazy list: increasing scroll position means older content.
                list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() to
                    row.fetchSemanticsNode().boundsInRoot.top
            }
        positions.zipWithNext().forEachIndexed { index, (older, newer) ->
            assertTrue(
                "rendered rows $index and ${index + 1} are distinct and chronological",
                older.first > newer.first || (older.first == newer.first && older.second < newer.second),
            )
        }
    }

    private fun coverageGap(): Boolean =
        runBlocking {
            cache.readHistoryPosition(serverId, conversationId)?.coverage?.let {
                it.gaps.isNotEmpty() ||
                    it.unknown
            } ==
                true
        }

    private fun pull() =
        rule.onNodeWithTag("thread-message-region").performTouchInput {
            swipeDown(
                startY = height * 0.3f,
                endY =
                    height * 0.7f,
            )
        }
}
