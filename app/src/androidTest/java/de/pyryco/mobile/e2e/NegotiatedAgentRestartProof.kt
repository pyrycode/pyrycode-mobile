package de.pyryco.mobile.e2e

import android.util.Log
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadSupport
import de.pyryco.mobile.di.RelayConnectionRegistry
import de.pyryco.mobile.di.ThreadDestinationFactory
import de.pyryco.mobile.ui.conversations.components.SESSION_BOUNDARY_TEST_TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.koin.core.context.GlobalContext

/** Real cache/graph restart and transport retirement; shared by genuine and scripted Agent scenarios. */
internal fun ComposeTestRule.proveNegotiatedAgentRestart(
    host: String,
    conversation: String,
    rawAgent: String,
    restart: () -> AutoCloseable,
    openThread: () -> Unit,
    send: () -> Unit,
) {
    fun source() = GlobalContext.get().get<ThreadDestinationFactory>().repository(host)

    fun evidence(ready: (ThreadEvidence) -> Boolean): ThreadEvidence =
        runBlocking {
            withTimeout(120_000) {
                val repository = source()
                val route = requireNotNull(repository.negotiatedThread)
                route.support.first { it is ThreadSupport.Supported }
                harnessThreadEvidence(repository).observe(conversation).first {
                    it.owner.kind == ThreadEvidenceKind.Authoritative && it.owner.host == host && it.admitted && ready(it)
                }
            }
        }
    val finished =
        evidence { snapshot ->
            val family = snapshot.renderedAgent(rawAgent)
            val root = snapshot.records.singleOrNull { it.message.id == family?.agentId }
            root?.fact?.active == false && family?.children?.any { it.message.role == Role.Tool } == true
        }
    val family = requireNotNull(finished.renderedAgent(rawAgent))
    val root = finished.records.single { it.message.id == family.agentId }
    val rootId = requireNotNull(root.fact).id
    val session = requireNotNull(root.session)
    val toolIds =
        family.children
            .filter { it.message.role == Role.Tool }
            .map { requireNotNull(it.fact).id }
            .toSet()
    assertTrue(
        "daemon must supply parent links for the Agent's own tools",
        family.children.filter { it.message.role == Role.Tool }.all {
            (
                it.fact?.parent
                    ?: 0
            ) >
                0
        },
    )
    runBlocking {
        withTimeout(120_000) {
            val cache = GlobalContext.get().get<ConversationCache>()
            while (cache
                    .readAuthoritativeThread(host, conversation)
                    ?.toSnapshot()
                    ?.items
                    ?.any { it.id == rootId && !it.active } !=
                true
            ) {
                delay(50)
            }
        }
    }
    restart().use {
        openThread()

        fun checkFamily(verifyUi: Boolean = true): ThreadEvidence {
            val restored = evidence { snapshot -> snapshot.records.any { it.fact?.id == rootId && it.fact.active == false } }
            val agent = restored.records.single { it.fact?.id == rootId }
            val children = restored.records.filter { it.agentId == agent.message.id && it.message.id != agent.message.id }
            assertEquals(
                "restart retains every tool under its original Agent",
                toolIds,
                children
                    .filter {
                        it.message.role == Role.Tool
                    }.map { requireNotNull(it.fact).id }
                    .toSet(),
            )
            assertTrue(children.filter { it.message.role == Role.Tool }.all { it.session == session && (it.fact?.parent ?: 0) > 0 })
            val owned = children.first { it.message.role == Role.Tool }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            if (verifyUi) {
                verifyAgentRunNavigation(
                    agent.message.id,
                    requireNotNull(owned.toolRunId),
                    children.map { it.message.id },
                    hasText(requireNotNull(owned.message.toolCall).toolName) and
                        hasAnyAncestor(hasTestTag("background-agent-child:${agent.message.id}")),
                    context.getString(R.string.agent_go_to),
                    context.getString(R.string.tool_run_expand),
                    context.getString(R.string.tool_run_collapse),
                    evidence = {
                        Log.i("NegotiatedAgentRestart", it)
                    },
                )
            }
            return restored
        }
        checkFamily()
        onNode(hasContentDescription("More actions")).performClick()
        onAllNodesWithText("Reset session").onFirst().performClick()
        waitUntil(120_000) { onAllNodesWithTag(SESSION_BOUNDARY_TEST_TAG).fetchSemanticsNodes().isNotEmpty() }
        val bundle = requireNotNull(GlobalContext.get().get<RelayConnectionRegistry>().connectionFor(host))
        val route = requireNotNull(source().negotiatedThread)
        val oldConnection = (route.support.value as ThreadSupport.Supported).connection
        val scope = CoroutineScope(Dispatchers.Main.immediate)
        try {
            val reconnect =
                scope.async(start = CoroutineStart.UNDISPATCHED) {
                    val pending =
                        bundle.coordinator.authoritativeThreads
                            .observe(host, conversation)
                            .first { snapshot ->
                                snapshot.pendingSends.any {
                                    it.deviceId !=
                                        null
                                }
                            }.pendingSends
                            .first { it.deviceId != null }
                    // Let the synchronous enqueue finish before retiring the transport that owns its acknowledgement.
                    yield()
                    bundle.supervisor.close()
                    bundle.supervisor.retry()
                    pending.message.id
                }
            send()
            val messageId = runBlocking { withTimeout(120_000) { reconnect.await() } }
            runBlocking {
                withTimeout(
                    120_000,
                ) { route.support.first { it is ThreadSupport.Supported && it.connection !== oldConnection } }
            }

            fun ThreadEvidenceRecord.matchesSend() =
                fact
                    ?.content
                    ?.jsonObject
                    ?.get("message_id")
                    ?.jsonPrimitive
                    ?.content == messageId
            val settled =
                evidence { snapshot ->
                    snapshot.records.any {
                        it.fact?.kind == "user_message" &&
                            it.matchesSend() &&
                            it.fact.status == "delivered"
                    }
                }
            assertEquals("recovered send settles once", 1, settled.records.count { it.matchesSend() })
            assertNotEquals("the recovered send belongs to the new session", session, settled.records.single { it.matchesSend() }.session)
            for (tool in settled.records.filter { it.fact?.kind == "tool_call" && it.agentId != null }) {
                val agent = settled.records.single { it.message.id == tool.agentId }
                assertEquals("each tool retains its own Agent's session", agent.session, tool.session)
                assertTrue("nested tools need daemon parent links", (tool.fact?.parent ?: 0) > 0)
            }
            assertTrue(
                bundle.coordinator.authoritativeThreads
                    .current(host, conversation)
                    .pendingSends
                    .isEmpty(),
            )
            checkFamily(verifyUi = false)
            Log.i("NegotiatedAgentRestart", "event=thread_activation route=authoritative restart=passed settlement_reconnect=passed")
        } finally {
            scope.cancel()
        }
    }
}
