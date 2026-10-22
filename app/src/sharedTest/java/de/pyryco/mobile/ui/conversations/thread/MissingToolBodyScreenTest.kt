package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class MissingToolBodyScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mountedProductionConsumerLoadsRetriesAndReusesRetainedBodyWithoutFalseSight() {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val inbound = Channel<Envelope>(Channel.UNLIMITED)
        val sent = CopyOnWriteArrayList<Envelope>()
        val pump =
            object : SessionPump {
                override val inbound = inbound.receiveAsFlow()

                override fun send(envelope: Envelope): Boolean {
                    sent += envelope
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
                ): Closeable = Closeable {}

                override fun older(conversationId: String) = false

                override fun cancel(conversationId: String) = Unit

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
                        )
                    }
                },
            )[ThreadViewModel::class.java]
        val owner =
            object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            }
        var seen = emptyMap<String, Long>()

        fun tool(
            rev: Long,
            id: Long = 17,
            order: Long = 5,
            name: String = "Read",
        ) = ThreadItemDto.decode(
            MobileJson.parseToJsonElement(
                """{"id":$id,"rev":$rev,"order":$order,"parent":0,"kind":"tool_call","status":"done","shown":true,"active":false,"summary":"Read file","detail_omitted":true,"content":{"name":"$name"}}""",
            ) as JsonObject,
        )

        fun reads() = sent.filter { it.type == "thread_read_tool_body" }

        fun toggle() {
            compose.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("tool-row")) and hasText("Read")).performClick()
            compose.waitForIdle()
        }

        fun reply(final: Boolean) {
            val request = reads().last()
            val raw = request.payload as JsonObject
            inbound.trySend(
                Envelope(
                    90,
                    "thread_tool_body",
                    "now",
                    MobileJson.parseToJsonElement(
                        """{"conversation_id":"c","epoch":"e","version":41,"item_id":17,"rev":${raw["expected_rev"]},"kind":"tool_call","final":$final${if (final) "" else ",\"content\":{\"input\":\"\",\"output\":\"loaded contents\"}"}}""",
                    ),
                    inReplyTo = request.id,
                ),
            )
        }
        try {
            repo.authoritativeThreads.establishComplete("host", "c", "e", 23, listOf(tool(23), tool(23, 18, 6, "Bash")), 0)
            compose.setContent {
                CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalThreadReadSightObserver provides { seen = it }) {
                    PyrycodeMobileTheme {
                        ThreadScreen(
                            state = vm.state.collectAsState().value,
                            onBack = {},
                            onSendMessage = {},
                            onRetry = {},
                            connectionState = vm.connectionState.collectAsState().value,
                            onOverflowEvent = vm::onOverflowEvent,
                            collapseToolUses = true,
                        )
                    }
                }
            }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Using tools: 2").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(reads().isEmpty())
            compose.onNodeWithText("Using tools: 2").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("tool-row").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(reads().isEmpty())
            toggle()
            compose.waitUntil(5000) { reads().size == 1 }
            assertEquals("17", (reads().single().payload as JsonObject)["item_id"].toString())
            compose.onNodeWithText("Loading tool details…").assertExists()
            assertTrue(seen.isEmpty())
            reply(false)
            compose.waitForIdle()
            compose.onAllNodesWithText("loaded contents").assertCountEquals(0)
            assertTrue(seen.isEmpty())
            reply(true)
            compose.waitUntil(5000) { compose.onAllNodesWithText("loaded contents").fetchSemanticsNodes().isNotEmpty() }
            toggle()
            toggle()
            compose.onNodeWithText("loaded contents").assertExists()
            assertEquals(1, reads().size)
            assertTrue(sent.none { it.type == "mark_conversation_read" })
            repo.authoritativeThreads.applyBatch("host", "c", "e", 24, listOf(tool(24)), 0)
            compose.waitUntil(5000) { reads().size == 2 }
            inbound.trySend(
                Envelope(
                    91,
                    "error",
                    "now",
                    MobileJson.parseToJsonElement("""{"code":"thread.query_failed","message":"private","retryable":true}"""),
                    inReplyTo = reads().last().id,
                ),
            )
            compose.waitUntil(5000) { compose.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Couldn’t load tool details").assertExists()
            compose.mainClock.advanceTimeBy(500)
            assertEquals(2, reads().size)
            compose.onNodeWithText("Retry").performClick()
            compose.waitUntil(5000) { reads().size == 3 }
            compose.runOnIdle {
                supported.value = ThreadSupport.Unverified
                connection.emit(ConnectionState.Offline)
            }
            compose.waitUntil(
                5000,
            ) { compose.onAllNodesWithText("Tool details aren’t available offline").fetchSemanticsNodes().isNotEmpty() }
            reply(false)
            reply(true)
            compose.waitForIdle()
            compose.onAllNodesWithText("loaded contents").assertCountEquals(0)
        } finally {
            compose.runOnIdle { vmStore.clear() }
            scope.cancel()
        }
    }
}
