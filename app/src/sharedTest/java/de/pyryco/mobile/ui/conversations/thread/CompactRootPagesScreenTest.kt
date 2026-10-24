package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.CompactRootFixture
import de.pyryco.mobile.data.repository.ConversationReadMarks
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CompactRootPagesScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun generated8333SourceDeliversOnlyClosedRootsAcrossInitialAndOlderPages() =
        runTest {
            val source = deferredLargeSnapshot()
            assertEquals(8333, source.items.size)
            val agent = source.items.single { it.kind == "agent" }
            val summary =
                JsonObject(
                    agent.raw.filterKeys { it !in setOf("content", "summary", "order") } +
                        mapOf(
                            "order" to JsonPrimitive(20),
                            "parent" to JsonPrimitive(0),
                            "headline" to JsonPrimitive("agent-1"),
                            "task_id" to (agent.content as JsonObject).getValue("task_id"),
                            "detail_omitted" to JsonPrimitive(true),
                            "public_child_count" to JsonPrimitive(8332),
                            "has_public_children" to JsonPrimitive(true),
                            "subtree_version" to JsonPrimitive(9000),
                        ),
                )
            val f = CompactRootFixture(backgroundScope)
            f.repo.openAuthoritativeThread("c")
            runCurrent()
            val first = f.batch(headers = listOf(summary), version = 10000)
            f.push(first)
            f.push(f.marker(20, 10001, version = 10000))
            runCurrent()
            val conversions = mutableListOf<Long>()
            val demands = mutableListOf<Set<String>>()
            var sight = 0
            lateinit var presentation: AuthoritativeThreadPresentation
            val snapshots = f.repo.observeAuthoritativeThread("c")
            compose.setContent {
                val snapshot by snapshots.collectAsState(AuthoritativeThreadSnapshot())
                presentation =
                    remember(snapshot) {
                        projectDeferredAuthoritativeThread(
                            "h",
                            "c",
                            snapshot,
                            onFullConversion = { conversions += it },
                            sourceConnection = f.repo,
                        )
                    }
                PyrycodeMobileTheme {
                    ThreadScreen(
                        state =
                            ThreadUiState(
                                "c",
                                "Channel",
                                authoritativeThread = presentation,
                                readMarks = ConversationReadMarks(0u, 9001u, 9001u, true),
                            ),
                        onBack = {},
                        onSendMessage = {},
                        onRetry = {},
                        connectionState = ConnectionState.Connected,
                        collapseToolUses = false,
                        onOverflowEvent = { event ->
                            when (event) {
                                is ThreadEvent.ToolBodyDemand -> {
                                    demands += event.keys
                                    f.repo.demandToolBodies(
                                        "c",
                                        this,
                                        event.presentation.sourceSnapshot,
                                        event.keys.mapNotNull { event.presentation.bodyItem(it)?.id }.toSet(),
                                    )
                                }
                                is ThreadEvent.AgentChildrenDemand -> {
                                    demands += event.keys
                                    f.repo.demandAgentChildren(
                                        "c",
                                        this,
                                        event.presentation.sourceSnapshot,
                                        event.keys.mapNotNull { event.presentation.bodyItem(it)?.id }.toSet(),
                                    )
                                }
                                is ThreadEvent.NewestContentPresented -> sight++
                                else -> Unit
                            }
                        },
                    )
                }
            }
            compose.waitForIdle()
            assertEquals(listOf("task-1"), presentation.tasks.tasks.map { it.taskId })
            assertEquals(1, presentation.tasks.liveCount)
            val launch = presentation.rows.filterIsInstance<ThreadRow.AgentStartMarker>().single()
            assertTrue(!launch.finished)
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Go to agent ↓"))
            compose.onNodeWithText("Go to agent ↓").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("background-agent:${launch.agentId}").assertIsDisplayed()
            compose.onAllNodesWithText("hidden-output").assertCountEquals(0)
            assertTrue(conversions.isEmpty())
            assertTrue(demands.all { it.isEmpty() })
            assertTrue(f.details.isEmpty())
            assertTrue(f.repo.requestOlderAuthoritativeThread("c"))
            runCurrent()
            val older = f.batch(headers = listOf(CompactRootFixture.header(9001, 10)), version = 10000)
            f.push(older)
            f.push(f.marker(10, 20, false, 10000))
            runCurrent()
            compose.waitForIdle()
            assertEquals(listOf(9001L, 1L), presentation.sourceSnapshot.expansionItems.map { it.id })
            assertTrue(conversions.isEmpty())
            assertTrue(demands.all { it.isEmpty() })
            assertTrue(f.details.isEmpty())
            assertEquals(0, sight)
            val delivered = listOf(first, older)
            assertEquals(2, delivered.sumOf { (it.getValue("headers") as kotlinx.serialization.json.JsonArray).size })
            assertEquals(0, delivered.sumOf { (it.getValue("items") as kotlinx.serialization.json.JsonArray).size })
            assertTrue(presentation.sourceSnapshot.bodyContents.isEmpty() && presentation.sourceSnapshot.childPages.isEmpty())
            assertEquals(2, f.roots.size)
            // Positive demand control: the installed screen callback reaches the real repository lane.
            compose.onNodeWithText("Root 9001", substring = true).performClick()
            compose.waitForIdle()
            runCurrent()
            compose.waitUntil(5000) { f.details.size == 1 }
            assertEquals(1, f.details.size)
            assertEquals("thread_read_tool_body", f.details.single().type)
        }
}
