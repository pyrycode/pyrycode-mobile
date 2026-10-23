package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    private fun mounted(
        stop: Boolean = false,
        collapse: Boolean = false,
    ) {
        val frames = Channel<Long>(Channel.UNLIMITED).apply { trySend(1) }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val replies = Channel<Envelope>(Channel.UNLIMITED)
        val sent = CopyOnWriteArrayList<Envelope>()
        val pump =
            object : SessionPump {
                override val inbound = replies.receiveAsFlow()

                override fun send(envelope: Envelope): Boolean {
                    sent += envelope
                    if (envelope.type == "thread_read_tool_body") {
                        val r = envelope.payload as JsonObject
                        val identity = """
                        "conversation_id":"c","epoch":"e","version":41,
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
                ) = repo.demandAgentChildren(conversationId, consumer, snapshot, ids, retry, more)

                override fun bodies(
                    conversationId: String,
                    connection: Any,
                    consumer: Any,
                    snapshot: AuthoritativeThreadSnapshot,
                    ids: Set<Long>,
                    retry: Long?,
                ) = repo.demandToolBodies(conversationId, consumer, snapshot, ids, retry)
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

        fun json(text: String) = MobileJson.parseToJsonElement(text) as JsonObject

        fun agent(id: Long) =
            ThreadItemDto.decode(
                json(
                    """{"id":$id,"rev":23,"order":$id,"parent":0,"kind":"agent","status":"done","shown":true,"active":false,"summary":"Agent $id","detail_omitted":true,"content":{"name":"Agent","input":{"description":"Agent $id"}}}""",
                ),
            )

        fun reads() = sent.filter { it.type == "thread_read_children" }

        fun toggle(text: String) {
            compose.onNode(hasClickAction() and hasText(text)).performClick()
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
                , "cursor":{"conversation_id":"c","epoch":"e","version":41,
                "item_id":$parent,"rev":23,"order":0,"id":$last}
            """
                } else {
                    ""
                }
            if (children.isNotEmpty()) {
                replies.trySend(
                    Envelope(
                        90,
                        "thread_children",
                        "now",
                        json(
                            """{"conversation_id":"c","epoch":"e","version":41,"item_id":$parent,"rev":23,"children":[$children],"final":false}""",
                        ),
                        inReplyTo = request.id,
                    ),
                )
            }
            replies.trySend(
                Envelope(
                    91,
                    "thread_children",
                    "now",
                    json(
                        """{"conversation_id":"c","epoch":"e","version":41,"item_id":$parent,"rev":23,"children":[],"final":true,"more":$more$extra}""",
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
                            onOverflowEvent = vm::onOverflowEvent,
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
            toggle("Agent 17")
            compose.waitUntil(5000) { compose.onAllNodesWithText("Nested 53").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Nested 53").assertExists()
            assertEquals(4, reads().size)
            toggle("Agent 18")
            compose.waitUntil(
                5000,
            ) { compose.onAllNodesWithText("Agent children aren’t available offline").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(sent.none { it.type == "mark_conversation_read" })
        } finally {
            compose.runOnIdle { vmStore.clear() }
            scope.cancel()
        }
    }
}
