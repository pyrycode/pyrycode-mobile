package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.AuthoritativeThreadSnapshot
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.NegotiatedThread
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.SessionPump
import de.pyryco.mobile.data.repository.ThreadSupport
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class AgentChildrenScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mountedAgentExpansionPagesNestedSummariesRetriesAndReopensOffline() = mounted()

    @Test fun stoppedConsumerWithdrawsBeforeLoadingFrame() = mounted(stop = true)

    @Test fun collapsedConsumerWithdrawsBeforeLoadingFrame() = mounted(collapse = true)

    @Test fun capacityRetryStaysInsideExpandedAgentAndRecoversAfterOtherDemandCloses() = mounted(capacity = true)

    @Test fun invalidatedRowsRemainVisibleThroughFailureRetryAndCursorRestart() = mounted(refresh = true)

    @Test fun lifecycleConnectionReplacementRetiresOldPresentationWithCollidingIds() = mounted(replacement = true)

    private fun mounted(
        stop: Boolean = false,
        collapse: Boolean = false,
        capacity: Boolean = false,
        refresh: Boolean = false,
        replacement: Boolean = false,
    ) {
        val frames = Channel<Long>(Channel.UNLIMITED).apply { trySend(1) }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val replies = Channel<Envelope>(Channel.UNLIMITED)
        var currentReplies = replies
        var capture = 41
        var replacementScope: CoroutineScope? = null
        val sent = CopyOnWriteArrayList<Envelope>()
        val pump =
            object : SessionPump {
                override val inbound = replies.receiveAsFlow()

                override fun send(envelope: Envelope): Boolean {
                    sent += envelope
                    if (envelope.type == "thread_read_tool_body") {
                        val r = envelope.payload as JsonObject
                        val identity = """
                        "conversation_id":"c","epoch":"e","version":$capture,
                        "item_id":${r["item_id"]},"rev":${r["expected_rev"]},"kind":"agent"
                    """
                        listOf(""", "content":{},"final":false""", """, "final":true""").forEach {
                            replies.trySend(
                                Envelope(
                                    93,
                                    "thread_tool_body",
                                    "now",
                                    MobileJson.parseToJsonElement("{$identity$it}"),
                                    inReplyTo = envelope.id,
                                ),
                            )
                        }
                    }
                    return true
                }
            }
        val repo =
            RemoteConversationRepository(pump, scope, negotiatedCapabilities = {
                setOf("thread", "interactive", "thread_expansion")
            }, authoritativeHostId = "host")
        val supported = MutableStateFlow<ThreadSupport>(ThreadSupport.Supported(repo))
        var currentRepo = repo
        val route =
            object : NegotiatedThread() {
                override val host = "host"
                override val support = supported
                override val store = repo.authoritativeThreads

                override fun observe(conversationId: String) = repo.observeAuthoritativeThread(conversationId)

                override suspend fun open(conversationId: String) = true

                override suspend fun acquire(
                    conversationId: String,
                    connection: Any,
                ) = Closeable {}

                override fun older(conversationId: String) = false

                override fun cancel(conversationId: String) = Unit

                override fun children(
                    conversationId: String,
                    connection: Any,
                    consumer: Any,
                    snapshot: AuthoritativeThreadSnapshot,
                    ids: Set<Long>,
                    retry: Long?,
                    more: Long?,
                ) = if (connection ===
                    currentRepo
                ) {
                    currentRepo.demandAgentChildren(conversationId, consumer, snapshot, ids, retry, more)
                } else {
                    null
                }

                override fun bodies(
                    conversationId: String,
                    connection: Any,
                    consumer: Any,
                    snapshot: AuthoritativeThreadSnapshot,
                    ids: Set<Long>,
                    retry: Long?,
                ) = if (connection === currentRepo) currentRepo.demandToolBodies(conversationId, consumer, snapshot, ids, retry) else null
            }
        val source =
            object : ConversationRepository by FakeConversationRepository() {
                override val negotiatedThread = route
            }
        val connection = FakeConnectionStateSource()
        val vmStore = ViewModelStore()
        val vm =
            ViewModelProvider(
                vmStore,
                viewModelFactory {
                    initializer {
                        ThreadViewModel(
                            SavedStateHandle(mapOf("serverId" to "host", "conversationId" to "c")),
                            source,
                            connection,
                            ComposerDraftStore(),
                            contentScheduling =
                                if (stop ||
                                    collapse
                                ) {
                                    ThreadContentScheduling(Dispatchers.IO) { frames.receive() }
                                } else {
                                    null
                                },
                        )
                    }
                },
            )[ThreadViewModel::class.java]
        val owner =
            object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            }
        var seen = emptyMap<String, Long>()
        var lastDemand: ThreadEvent.AgentChildrenDemand? = null

        fun json(text: String) = MobileJson.parseToJsonElement(text) as JsonObject

        fun agent(
            id: Long,
            freshness: Long = 0,
        ) = ThreadItemDto.decode(
            json(
                """{"id":$id,"rev":23,"order":$id,"parent":0,"kind":"agent","status":"done","shown":true,"active":false,"summary":"Agent $id","subtree_version":$freshness,"detail_omitted":true,"content":{"name":"Agent","input":{"description":"Agent $id"}}}""",
            ),
        )

        fun reads() = sent.filter { it.type == "thread_read_children" }

        fun toggle(text: String) {
            compose.onNode(hasClickAction() and hasText(text)).performTouchInput {
                // The offline Retry pill overlaps the header's right half.
                click(Offset(20f, centerY))
            }
            compose.waitForIdle()
        }

        fun reply(
            parent: Int,
            children: String = "",
            more: Boolean = false,
            last: Int = 50,
        ) {
            val request = reads().last()
            val extra =
                if (more) {
                    """
                , "cursor":{"conversation_id":"c","epoch":"e","version":$capture,
                "item_id":$parent,"rev":23,"order":0,"id":$last}
            """
                } else {
                    ""
                }
            if (children.isNotEmpty()) {
                currentReplies.trySend(
                    Envelope(
                        90,
                        "thread_children",
                        "now",
                        json(
                            """{"conversation_id":"c","epoch":"e","version":$capture,"item_id":$parent,"rev":23,"children":[$children],"final":false}""",
                        ),
                        inReplyTo = request.id,
                    ),
                )
            }
            currentReplies.trySend(
                Envelope(
                    91,
                    "thread_children",
                    "now",
                    json(
                        """{"conversation_id":"c","epoch":"e","version":$capture,"item_id":$parent,"rev":23,"children":[],"final":true,"more":$more$extra}""",
                    ),
                    inReplyTo = request.id,
                ),
            )
        }

        fun child(
            id: Int,
            parent: Int = 17,
        ) =
            """{"id":$id,"rev":23,"order":0,"parent":$parent,"kind":"agent","status":"done","shown":true,"active":false,"headline":"Nested $id","detail_omitted":true}"""
        val pressure = if (capacity) occupyExpansionCapacity(repo.authoritativeThreads, "host") else null
        try {
            repo.authoritativeThreads.establishComplete("host", "c", "e", 23, listOf(agent(17), agent(18)), 0)
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalThreadReadSightObserver provides { seen = it }) {
                    PyrycodeMobileTheme {
                        ThreadScreen(
                            vm.state.collectAsState().value,
                            {},
                            {},
                            vm.connectionState.collectAsState().value,
                            {},
                            onOverflowEvent = { event ->
                                if (event is ThreadEvent.AgentChildrenDemand) lastDemand = event
                                vm.onOverflowEvent(event)
                            },
                            collapseToolUses = true,
                        )
                    }
                }
            }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Agent 17").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(reads().isEmpty())
            assertTrue(sent.none { it.type == "thread_read_tool_body" })
            val held = vm.state.value.authoritativeThread
            toggle("Agent 17")
            compose.waitUntil(5000) { reads().size == 1 }
            if (stop || collapse) {
                assertSame(held, vm.state.value.authoritativeThread)
                if (stop) {
                    compose.runOnIdle { (owner.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.CREATED }
                } else {
                    toggle("Agent 17")
                }
                compose.waitUntil(5000) {
                    repo.authoritativeThreads
                        .current("host", "c")
                        .childLoads
                        .isEmpty()
                }
                reply(17, child(50))
                repo.openAuthoritativeThread("c")
                compose.waitUntil(5000) { sent.any { it.type == "thread_catch_up" } }
                assertTrue(
                    repo.authoritativeThreads
                        .current("host", "c")
                        .childPages
                        .isEmpty(),
                )
                assertTrue(seen.isEmpty())
                return
            }
            compose.onNodeWithText("Loading agent children…").assertExists()
            if (refresh || replacement) {
                reply(17, child(50), more = true)
                compose.waitUntil(5000) { compose.onAllNodesWithText("Nested 50").fetchSemanticsNodes().isNotEmpty() }
                val oldPage = repo.authoritativeThreads.current("host", "c").childPages[17L to 23L]
                capture = 45
                compose.runOnIdle {
                    repo.authoritativeThreads.applyBatch("host", "c", "e", 44, listOf(agent(17, 44)), 0)
                }
                compose.waitUntil(5000) { reads().size == 2 }
                compose.onNodeWithText("Nested 50").assertExists()
                compose.onNodeWithText("Loading agent children…").assertExists()
                compose.onNodeWithText("Load more").assertDoesNotExist()
                assertSame(oldPage, repo.authoritativeThreads.current("host", "c").childPages[17L to 23L])
                if (replacement) {
                    val retired = requireNotNull(lastDemand)
                    val heldRequest = reads().last()
                    compose.runOnIdle { (owner.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.CREATED }
                    scope.cancel()
                    val freshReplies = Channel<Envelope>(Channel.UNLIMITED)
                    currentReplies = freshReplies
                    val freshScope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { replacementScope = it }
                    val freshPump =
                        object : SessionPump {
                            override val inbound = freshReplies.receiveAsFlow()

                            override fun send(envelope: Envelope): Boolean {
                                sent += envelope
                                return true
                            }
                        }
                    val fresh =
                        RemoteConversationRepository(
                            freshPump,
                            freshScope,
                            negotiatedCapabilities = { setOf("thread", "interactive", "thread_expansion") },
                            authoritativeHostId = "host",
                            authoritativeThreads = repo.authoritativeThreads,
                        )
                    compose.runOnIdle {
                        currentRepo = fresh
                        supported.value = ThreadSupport.Supported(fresh)
                        (owner.lifecycle as LifecycleRegistry).currentState = Lifecycle.State.RESUMED
                        // This fixture's acquire is inert; supply the new connection's source observation.
                        repo.authoritativeThreads.applyBatch("host", "c", "e", 46, listOf(agent(17, 44)), 0)
                    }
                    try {
                        compose.waitUntil(5000) {
                            vm.state.value.authoritativeThread
                                ?.sourceConnection === fresh
                        }
                    } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                        val p = vm.state.value.authoritativeThread
                        throw AssertionError(
                            "replacement=${p?.sourceConnection === fresh} old=${p?.sourceConnection === repo} support=${(supported.value as? ThreadSupport.Supported)?.connection === fresh} reads=${reads().size} demand=${lastDemand?.keys?.size}\n${compose.onRoot().printToString()}",
                            failure,
                        )
                    }
                    compose.waitForIdle()
                    // The verdict switch clears destination-local expansion; the new owner can open again.
                    if (reads().size == 2) toggle("Agent 17")
                    compose.waitUntil(5000) { reads().size == 3 }
                    compose.runOnIdle {
                        vm.onOverflowEvent(
                            retired.copy(retry = retired.keys.firstOrNull(), more = retired.keys.firstOrNull()),
                        )
                    }
                    replies.trySend(
                        Envelope(
                            95,
                            "thread_children",
                            "now",
                            json(
                                """{"conversation_id":"c","epoch":"e","version":45,"item_id":17,"rev":23,"children":[],"final":true,"more":false}""",
                            ),
                            inReplyTo = heldRequest.id,
                        ),
                    )
                    compose.waitForIdle()
                    assertEquals(3, reads().size)
                    assertSame(oldPage, repo.authoritativeThreads.current("host", "c").childPages[17L to 23L])
                    reply(17, child(51))
                    compose.waitUntil(5000) { compose.onAllNodesWithText("Nested 51").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText("Nested 50").assertDoesNotExist()
                    assertTrue(sent.none { it.type == "mark_conversation_read" })
                    return
                }

                fun refuse() {
                    currentReplies.trySend(
                        Envelope(
                            92,
                            "error",
                            "now",
                            json("""{"code":"thread.query_failed","message":"private","retryable":true}"""),
                            inReplyTo = reads().last().id,
                        ),
                    )
                    compose.waitUntil(
                        5000,
                    ) { compose.onAllNodesWithText("Couldn’t load agent children").fetchSemanticsNodes().isNotEmpty() }
                }
                refuse()
                compose.onNodeWithText("Nested 50").assertExists()
                compose.mainClock.advanceTimeBy(500)
                assertEquals(2, reads().size)
                compose.onNodeWithText("Retry").performClick()
                compose.waitUntil(5000) { reads().size == 3 }
                assertTrue("cursor" !in (reads().last().payload as JsonObject))
                reply(17, child(51), more = true, last = 51)
                compose.waitUntil(5000) { compose.onAllNodesWithText("Nested 51").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Nested 50").assertDoesNotExist()
                compose.onNodeWithText("Load more").performClick()
                compose.waitUntil(5000) { reads().size == 4 }
                refuse()
                compose.onNodeWithText("Retry").performClick()
                compose.waitUntil(5000) { reads().size == 5 }
                assertTrue("cursor" !in (reads().last().payload as JsonObject))
                reply(17)
                compose.waitUntil(5000) { compose.onAllNodesWithText("No agent children").fetchSemanticsNodes().isNotEmpty() }
                assertTrue(sent.none { it.type == "mark_conversation_read" })
                return
            }
            if (capacity) {
                reply(17, child(50))
                compose.waitUntil(5000) { compose.onAllNodesWithText("Couldn’t load agent children").fetchSemanticsNodes().isNotEmpty() }
                val childRetry = hasText("Retry") and hasAnySibling(hasText("Couldn’t load agent children"))
                compose.onNode(childRetry).assertExists()
                compose.mainClock.advanceTimeBy(500)
                assertEquals(1, reads().size)
                pressure?.close()
                compose.onNode(childRetry).performClick()
                compose.waitUntil(5000) { reads().size == 2 }
                reply(17, child(50))
                compose.waitUntil(5000) { compose.onAllNodesWithText("Nested 50").fetchSemanticsNodes().isNotEmpty() }
                assertTrue(sent.none { it.type == "mark_conversation_read" })
                return
            }
            val toolSummaries = (51..52).joinToString(",") { child(it).replace("\"kind\":\"agent\"", "\"kind\":\"tool_call\"") }
            reply(17, child(50) + "," + toolSummaries, true, last = 52)
            compose.waitUntil(5000) { compose.onAllNodesWithText("Load more").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(1, reads().size)
            compose.onNodeWithText("Using tools: 2").performClick()
            compose.waitForIdle()
            assertTrue(sent.filter { it.type == "thread_read_tool_body" }.all { (it.payload as JsonObject)["item_id"].toString() == "17" })
            assertEquals(1, reads().size)
            compose.onNodeWithText("Load more").performClick()
            compose.waitUntil(5000) { reads().size == 2 }
            reply(17, child(53))
            compose.waitUntil(5000) { compose.onAllNodesWithText("Nested 53").fetchSemanticsNodes().isNotEmpty() }
            toggle("Nested 50")
            compose.waitUntil(5000) { reads().size == 3 }
            assertEquals("50", (reads().last().payload as JsonObject)["item_id"].toString())
            replies.trySend(
                Envelope(
                    92,
                    "error",
                    "now",
                    json("""{"code":"thread.query_failed","message":"private","retryable":true}"""),
                    inReplyTo = reads().last().id,
                ),
            )
            compose.waitUntil(5000) { compose.onAllNodesWithText("Couldn’t load agent children").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Retry").performClick()
            compose.waitUntil(5000) { reads().size == 4 }
            reply(50)
            compose.waitUntil(5000) { compose.onAllNodesWithText("No agent children").fetchSemanticsNodes().isNotEmpty() }
            toggle("Nested 50")
            toggle("Agent 17")
            compose.runOnIdle {
                supported.value = ThreadSupport.Unverified
                connection.emit(ConnectionState.Offline)
            }
            compose.waitUntil(5000) {
                compose.waitForIdle()
                vm.state.value.authoritativeThread
                    ?.let { it.sourceConnection == null } == true
            }
            assertEquals(
                4,
                vm.state.value.authoritativeThread
                    ?.sourceSnapshot
                    ?.childPages
                    ?.get(17L to 23L)
                    ?.items
                    ?.size,
            )
            toggle("Agent 17")
            try {
                compose.waitUntil(5000) { compose.onAllNodesWithText("Nested 53").fetchSemanticsNodes().isNotEmpty() }
            } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError(compose.onRoot().printToString(), failure)
            }
            compose.onNodeWithText("Nested 53").assertExists()
            assertEquals(4, reads().size)
            toggle("Agent 18")
            compose.waitUntil(
                5000,
            ) { compose.onAllNodesWithText("Agent children aren’t available offline").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(sent.none { it.type == "mark_conversation_read" })
        } finally {
            pressure?.close()
            compose.runOnIdle { vmStore.clear() }
            scope.cancel()
            replacementScope?.cancel()
        }
    }
}
