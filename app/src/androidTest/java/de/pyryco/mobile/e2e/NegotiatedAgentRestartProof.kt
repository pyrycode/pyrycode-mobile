package de.pyryco.mobile.e2e

import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadSupport
import de.pyryco.mobile.data.repository.matchesPending
import de.pyryco.mobile.di.RelayConnectionRegistry
import de.pyryco.mobile.di.ThreadDestinationFactory
import de.pyryco.mobile.ui.conversations.components.TOOL_ROW_TAG
import de.pyryco.mobile.ui.conversations.thread.awaitDisplayedSessionBoundary
import de.pyryco.mobile.ui.conversations.thread.projectAuthoritativeThread
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
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.koin.core.context.GlobalContext
import java.security.MessageDigest

/** Real cache/graph restart and transport retirement; shared by genuine and scripted Agent scenarios. */
internal fun ComposeTestRule.proveNegotiatedAgentRestart(
    host: String,
    conversation: String,
    rawAgent: String,
    taskId: String,
    restart: (() -> Unit) -> AutoCloseable,
    openThread: () -> Unit,
    send: () -> Unit,
) {
    fun source() = GlobalContext.get().get<ThreadDestinationFactory>().repository(host)

    fun receipt(
        stage: String,
        reading: ThreadEvidence,
    ) {
        fun hash(value: String) =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray())
                .take(8)
                .joinToString("") { "%02x".format(it) }
        Log.i(
            "NegotiatedAgentRestart",
            "event=restart_owner stage=$stage host_hash=${hash(host)} conversation_hash=${hash(conversation)} " +
                "epoch_hash=${hash(reading.owner.epoch.orEmpty())} connection=${System.identityHashCode(reading.owner.connection)} " +
                "generation=${reading.owner.generation} checkpoint=${reading.sourceSnapshot?.checkpoint} route=authoritative",
        )
    }

    fun evidence(ready: (ThreadEvidence) -> Boolean): ThreadEvidence =
        runBlocking {
            withTimeout(120_000) {
                val repository = source()
                val route = requireNotNull(repository.negotiatedThread)
                route.support.first { it is ThreadSupport.Supported }
                harnessThreadEvidence(repository).observe(conversation).first {
                    val support = route.support.value as? ThreadSupport.Supported
                    it.owner.kind == ThreadEvidenceKind.Authoritative &&
                        it.owner.host == host &&
                        it.owner.conversation == conversation &&
                        it.owner.connection === support?.connection &&
                        it.admitted &&
                        ready(it)
                }
            }
        }
    val finished =
        evidence { snapshot ->
            val agent = snapshot.backgroundAgent(taskId, rawAgent)
            val root = snapshot.records.singleOrNull { it.message.id == agent?.block }
            val children = snapshot.records.filter { it.agentId == agent?.block && it.message.id != agent?.block }
            root?.fact?.active == false &&
                children.any { it.message.role == Role.Tool } &&
                children.filter { it.message.role == Role.Tool }.all { it.fact?.active == false } &&
                children.any { it.message.role == Role.Assistant } &&
                children.filter { it.message.role == Role.Assistant }.all {
                    snapshot.settledReply(
                        snapshot.owner,
                        requireNotNull(it.session),
                        requireNotNull(it.turn),
                        requireNotNull(agent).family,
                    ) !=
                        null
                }
        }
    val agent = requireNotNull(finished.backgroundAgent(taskId, rawAgent))
    assertTrue("session A has no unresolved sends", requireNotNull(finished.sourceSnapshot).pendingSends.isEmpty())
    receipt("finished_session_a", finished)
    val children = finished.records.filter { it.agentId == agent.block && it.message.id != agent.block }
    val root = finished.records.single { it.message.id == agent.block }
    val rootId = requireNotNull(root.fact).id
    val session = requireNotNull(root.session)
    val initialFamily = listOf(root) + children

    fun assertStableFact(
        fact: ThreadItemDto,
        current: ThreadItemDto,
    ) {
        assertEquals("original session", fact.session, current.session)
        assertEquals("original turn", fact.turn, current.turn)
        assertEquals("original parent", fact.parent, current.parent)
        assertEquals("daemon order", fact.order, current.order)
        assertEquals("finished placement order", fact.endedOrder, current.endedOrder)
        assertEquals("finished state", fact.status, current.status)
        assertEquals("finished activity", fact.active, current.active)
        assertTrue("newest revision retained", current.rev >= fact.rev)
    }
    val toolIds =
        children
            .filter { it.message.role == Role.Tool }
            .map { requireNotNull(it.fact).id }
            .toSet()
    assertTrue(
        "daemon must supply parent links for the Agent's own tools",
        children.filter { it.message.role == Role.Tool }.all {
            it.fact?.parent == rootId
        },
    )
    // Retire the destination before reset so its transient metadata collectors cannot race teardown.
    onNodeWithContentDescription(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_back)).performClick()
    waitForIdle()
    runBlocking { source().startNewSession(conversation) }

    fun boundaries(reading: ThreadEvidence) =
        projectAuthoritativeThread(
            host,
            conversation,
            requireNotNull(reading.sourceSnapshot),
        ).items.filterIsInstance<ThreadItem.SessionBoundary>()
    val rotated = evidence { reading -> boundaries(reading).any { it.previousSessionId == session && it.newSessionId != session } }
    val nextSession = boundaries(rotated).single { it.previousSessionId == session }.newSessionId
    openThread()
    awaitDisplayedSessionBoundary(120_000)
    receipt("rotated_session_b", rotated)
    val oldGraph = GlobalContext.get()
    val oldCache = oldGraph.get<ConversationCache>()
    val oldRegistry = oldGraph.get<RelayConnectionRegistry>()
    val saved =
        runBlocking {
            withTimeout(120_000) {
                while (true) {
                    val snapshot = oldCache.readAuthoritativeThread(host, conversation)?.toSnapshot()
                    val coverage = snapshot?.coverage
                    if (snapshot != null &&
                        snapshot.checkpoint > 0 &&
                        coverage != null &&
                        initialFamily.all { row ->
                            val fact = requireNotNull(row.fact)
                            snapshot.items.any { it.id == fact.id && it.rev >= fact.rev }
                        }
                    ) {
                        return@withTimeout snapshot
                    }
                    delay(50)
                }
                error("unreachable")
            }
        }
    // The daemon can append its final task report after marking the Agent inactive. Freeze the
    // complete pre-restart facts actually persisted, preserving every earlier identity and field.
    val persisted = authoritativeThreadEvidence(host, conversation, finished.owner.connection, saved)
    val baseline =
        initialFamily.map { old ->
            val fact = requireNotNull(old.fact)
            val row = persisted.records.single { it.fact?.id == fact.id }
            val current = requireNotNull(row.fact)
            assertStableFact(fact, current)
            if (fact.id == rootId) {
                val originalContent = fact.content as JsonObject
                val persistedContent = current.content as JsonObject
                for ((key, value) in originalContent) {
                    assertEquals("existing Agent content retained before restart: $key", value, persistedContent[key])
                }
            } else {
                assertEquals("complete child content before restart", fact.content, current.content)
            }
            row
        }

    val placementRoot = requireNotNull(baseline.single { it.fact?.id == rootId }.fact)
    val endedOrder = requireNotNull(placementRoot.endedOrder) { "finished Agent needs its canonical placement order" }
    val launchOrder = requireNotNull(placementRoot.order)
    val beforeAnchor =
        requireNotNull(
            persisted.records
                .mapNotNull { row ->
                    row.fact?.takeIf { fact ->
                        row.message.role == Role.User &&
                            row.agentId == null &&
                            fact.kind == "user_message" &&
                            fact.order != null &&
                            fact.order > launchOrder &&
                            fact.order < endedOrder
                    }
                }.maxByOrNull { requireNotNull(it.order) },
        ) { "release message must anchor the Agent between launch and finish" }

    fun assertFamily(restored: ThreadEvidence) {
        for (old in baseline) {
            val fact = requireNotNull(old.fact)
            val current = requireNotNull(restored.records.single { it.fact?.id == fact.id }.fact)
            assertStableFact(fact, current)
            assertEquals("complete content across restart", fact.content, current.content)
        }
        val anchor = requireNotNull(restored.records.single { it.fact?.id == beforeAnchor.id }.fact)
        assertStableFact(beforeAnchor, anchor)
        assertEquals("complete chronological anchor content", beforeAnchor.content, anchor.content)
        assertTrue("canonical finished placement is preserved", restored.retainsFinishedAgentPlacement(placementRoot, beforeAnchor))
    }

    var placementProbe = 0
    var screenObservation: PassiveThreadObservation? = null

    fun assertUiPlacement(
        reading: ThreadEvidence,
        after: ThreadEvidenceRecord? = null,
    ) {
        val observation = requireNotNull(screenObservation)
        val afterFact = after?.fact
        val probe = ++placementProbe
        var previousProbe: List<Any?>? = null
        var indexes = emptyList<Int>()
        waitUntil(120_000) {
            val screenReading = observation.reading(host, conversation)
            val screenEvidence = screenReading?.let(observation::evidence)
            val screen = screenEvidence?.sourceSnapshot
            if (screenEvidence != null) {
                assertEquals("historical family epoch is retained", reading.owner.epoch, screenEvidence.owner.epoch)
                assertFamily(screenEvidence)
                val root = screenEvidence.records.single { it.fact?.id == rootId }
                val before = screenEvidence.records.single { it.fact?.id == beforeAnchor.id }
                val currentAfter = afterFact?.let { fact -> screenEvidence.records.single { it.fact?.id == fact.id } }
                if (currentAfter != null) {
                    assertStableFact(requireNotNull(afterFact), requireNotNull(currentAfter.fact))
                    assertEquals("recovered send body is retained", requireNotNull(after).fullText, currentAfter.fullText)
                    assertTrue(
                        "finished Agent stays before the recovered send after reset",
                        screenEvidence.retainsFinishedAgentPlacement(placementRoot, beforeAnchor, requireNotNull(currentAfter.fact)),
                    )
                }
                val indexForKey = onNode(hasScrollToNodeAction()).fetchSemanticsNode().config[SemanticsProperties.IndexForKey]
                indexes = listOf(indexForKey("msg:${root.message.id}"), indexForKey("msg:${before.message.id}")) +
                    currentAfter?.let { listOf(indexForKey("msg:${it.message.id}")) }.orEmpty()
            } else {
                indexes = List(if (after == null) 2 else 3) { -1 }
            }
            val current = requireNotNull(source().negotiatedThread).store.current(host, conversation)
            val mark =
                indexes +
                    listOf(
                        current.completedVersion,
                        current.checkpoint,
                        current.openingRows?.size,
                        screen?.completedVersion,
                        screen?.checkpoint,
                        screen?.openingRows?.size,
                        screen?.items?.size,
                    current.generation,
                    screen?.generation,
                    )
            if (mark != previousProbe) {
                previousProbe = mark
                val rootPresent = current.items.any { it.id == rootId }
                val beforePresent = current.items.any { it.id == beforeAnchor.id }
                val rootOpening = current.openingRows?.any { it.first == rootId }
                val beforeOpening = current.openingRows?.any { it.first == beforeAnchor.id }
                Log.i(
                    "NegotiatedAgentRestart",
                    "event=placement_probe probe=$probe indexes=$indexes facts=${current.items.size} " +
                        "checkpoint=${current.checkpoint} opening_count=${current.openingRows?.size} " +
                        "root_present=$rootPresent before_present=$beforePresent " +
                        "root_opening=$rootOpening before_opening=$beforeOpening " +
                        "screen_facts=${screen?.items?.size} screen_version=${screen?.completedVersion} " +
                        "screen_checkpoint=${screen?.checkpoint} screen_opening=${screen?.openingRows?.size} " +
                        "screen_root=${screen?.items?.any { it.id == rootId }} " +
                        "screen_before=${screen?.items?.any { it.id == beforeAnchor.id }} screen_current=${screen === current} " +
                        "captured_generation=${reading.owner.generation} current_generation=${current.generation} " +
                        "screen_generation=${screen?.generation}",
                )
            }
            // A reset or connection switch during key lookup cannot certify the retired reading.
            screenReading != null && screenEvidence != null && indexes.all { it >= 0 } &&
                observation.evidence(screenReading) === screenEvidence
        }
        // The lazy list is reversed: a newer row has a smaller index.
        assertTrue("finished Agent stays after the pre-finish phone message", indexes[0] < indexes[1])
        if (after != null) assertTrue("new-session send stays after the finished Agent", indexes[2] < indexes[0])
    }

    Log.i("NegotiatedAgentRestart", "event=saved_family_ready checkpoint=${saved.checkpoint}")
    restart {
        assertTrue("application graph replaced", GlobalContext.get() !== oldGraph)
        assertTrue("registry replaced", GlobalContext.get().get<RelayConnectionRegistry>() !== oldRegistry)
        assertTrue("cache reopened", GlobalContext.get().get<ConversationCache>() !== oldCache)
        val registry = GlobalContext.get().get<RelayConnectionRegistry>()
        runBlocking {
            withTimeout(120_000) { registry.hostConnections.first { registry.connectionFor(host) != null } }
        }
        val route = requireNotNull(source().negotiatedThread)
        assertTrue("saved facts do not negotiate support", route.support.value is ThreadSupport.Unverified)
        val restored = runBlocking { withTimeout(120_000) { route.observe(conversation).first { it.epoch != null } } }
        assertEquals("certified saved checkpoint restored before Activity launch", saved.checkpoint, restored.checkpoint)
        assertEquals("saved coverage restored", saved.coverage, restored.coverage)
        assertFamily(authoritativeThreadEvidence(host, conversation, route, restored))
        receipt("saved_before_launch", authoritativeThreadEvidence(host, conversation, route, restored))
        Log.i("NegotiatedAgentRestart", "event=graph_cache_restart graph=replaced cache=reopened saved_family=restored support=unverified")
    }.use {
        PassiveThreadProbe.open().use { observation ->
            screenObservation = observation
            openThread()

            fun checkFamily(verifyUi: Boolean = true): ThreadEvidence {
                val restored = evidence { snapshot -> snapshot.records.any { it.fact?.id == rootId && it.fact.active == false } }
                assertFamily(restored)
                receipt("reopened", restored)
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
                    assertUiPlacement(restored)
                    verifyAgentRunNavigation(
                        agent.message.id,
                        requireNotNull(owned.toolRunId),
                        children.map { it.message.id },
                        hasTestTag(TOOL_ROW_TAG) and
                            hasAnyAncestor(hasTestTag("background-agent-child:${agent.message.id}")),
                        context.getString(R.string.agent_go_to),
                        context.getString(R.string.tool_run_expand),
                        context.getString(R.string.tool_run_collapse),
                        markerMatcher =
                            hasText(context.getString(R.string.agent_finished)) and
                                hasText(context.getString(R.string.agent_go_to)) and hasClickAction(),
                        ownedChildKey = owned.message.id,
                        evidence = {
                            Log.i("NegotiatedAgentRestart", it)
                        },
                    )
                }
                return restored
            }
            checkFamily()
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
                        assertTrue(
                            "the admitted connection still owns the pending send at retirement",
                            (route.support.value as? ThreadSupport.Supported)?.connection === oldConnection,
                        )
                        assertTrue(
                            "settlement must still be pending when the connection retires",
                            bundle.coordinator.authoritativeThreads.current(host, conversation).pendingSends.any {
                                it.deviceId == pending.deviceId && it.message.id == pending.message.id
                            },
                        )
                        bundle.supervisor.close()
                        bundle.supervisor.retry()
                        pending
                    }
                send()
                val pending = runBlocking { withTimeout(120_000) { reconnect.await() } }
                runBlocking {
                    withTimeout(
                        120_000,
                    ) { route.support.first { it is ThreadSupport.Supported && it.connection !== oldConnection } }
                }
                Log.i(
                    "NegotiatedAgentRestart",
                    "event=connection_replaced capability=thread route=authoritative pending_send=observed owner=replaced",
                )

                fun ThreadEvidenceRecord.matchesSend(): Boolean = fact?.matchesPending(pending) == true
                val settled =
                    evidence { snapshot ->
                        snapshot.records.any {
                            it.fact?.kind == "user_message" &&
                                it.matchesSend() &&
                                it.fact.status == "delivered" &&
                                !it.fact.active
                        }
                    }
                assertEquals("recovered send settles once", 1, settled.records.count { it.matchesSend() })
                receipt("settled_session_b", settled)
                assertNotEquals(
                    "the recovered send belongs to the new session",
                    session,
                    requireNotNull(
                        settled.records
                            .single {
                                it.matchesSend()
                            }.session,
                    ),
                )
                assertEquals("the recovered send belongs to session B", nextSession, settled.records.single { it.matchesSend() }.session)
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
                val reopened = checkFamily(verifyUi = false)
                val settledAnchor = reopened.records.single { it.matchesSend() }
                assertTrue(
                    "finished Agent remains between the original release and recovered send",
                    reopened.retainsFinishedAgentPlacement(placementRoot, beforeAnchor, requireNotNull(settledAnchor.fact)),
                )
                assertUiPlacement(reopened, settledAnchor)
                Log.i("NegotiatedAgentRestart", "event=thread_activation route=authoritative restart=passed settlement_reconnect=passed")
            } finally {
                scope.cancel()
            }
        }
    }
}
