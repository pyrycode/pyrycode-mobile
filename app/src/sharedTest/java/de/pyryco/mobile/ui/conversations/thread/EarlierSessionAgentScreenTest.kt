package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.cache.FileConversationCache
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ToolCallStatus
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.ThreadItemDto
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.ThreadSupport
import de.pyryco.mobile.di.SavedAuthoritativeDestination
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Production destination, serialized cache, correlated queries and actual screen; no projector substitution. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class EarlierSessionAgentScreenTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    @get:Rule(order = 0)
    val mainDispatcher =
        object : TestWatcher() {
            override fun starting(description: Description) = Dispatchers.setMain(dispatcher)

            override fun finished(description: Description) = Dispatchers.resetMain()
        }

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule val temporary = TemporaryFolder()
    private val owners = ViewModelStore()
    private lateinit var destination: SavedAuthoritativeDestination
    private lateinit var cache: FileConversationCache
    private lateinit var vm: ThreadViewModel
    private lateinit var listState: LazyListState
    private val originalSink = RelayLog.sink
    private val originalLogging = RelayLog.enabled

    @Before fun logs() {
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> println("relay=$message") }
    }

    @After fun close() {
        try {
            compose.runOnIdle {
                owners.clear()
                if (::destination.isInitialized) destination.close()
            }
            scheduler.runCurrent()
            if (::vm.isInitialized) await { vm.viewModelScope.coroutineContext[Job]?.isCompleted == true }
        } finally {
            RelayLog.sink = originalSink
            RelayLog.enabled = originalLogging
        }
    }

    private fun open() {
        cache = FileConversationCache(temporary.root)
        compose.runOnIdle {
            destination =
                SavedAuthoritativeDestination(
                    cache,
                    dispatcher = dispatcher,
                    worker = dispatcher,
                    awaitFrame = { 1L },
                )
        }
        await { destination.hostReady }
        compose.runOnIdle {
            vm = destination.open()
            owners.put("thread", vm)
        }
    }

    private fun presentation() = requireNotNull(vm.state.value.authoritativeThread)

    private fun support() = requireNotNull(destination.coordinator.negotiatedThread).support.value

    private fun await(condition: () -> Boolean) {
        compose.waitUntil(15_000) {
            scheduler.runCurrent()
            scheduler.advanceTimeBy(100)
            scheduler.runCurrent()
            // Repository jobs post to Android's main looper outside Compose's frame clock.
            compose.waitForIdle()
            condition()
        }
    }

    private fun mountScreen() {
        compose.setContent {
            val state by vm.state.collectAsState()
            PyrycodeMobileTheme(darkTheme = true) {
                CompositionLocalProvider(LocalThreadListCompositionObserver provides { listState = it }) {
                    ThreadScreen(
                        state,
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                    )
                }
            }
        }
    }

    private fun awaitFact(
        id: Long,
        rev: Long,
    ) {
        await {
            vm.state.value.authoritativeThread
                ?.sourceSnapshot
                ?.items
                ?.any { it.id == id && it.rev == rev } == true
        }
    }

    private fun request(
        type: String,
        after: Int = 0,
    ): Envelope {
        try {
            await { destination.requests.drop(after).any { it.type == type } }
        } catch (failure: AssertionError) {
            throw AssertionError(
                "Missing $type: support=${support()} hostReady=${destination.hostReady} " +
                    "requests=${destination.requests.map { it.type }} presentation=${vm.state.value.authoritativeThread}",
                failure,
            )
        }
        return destination.requests.drop(after).first { it.type == type }.also {
            assertEquals(JsonPrimitive("c"), it.payload.jsonObject["conversation_id"])
            println("receipt=request type=${it.type} id=${it.id} payload=${it.payload}")
        }
    }

    private fun batch(
        request: Envelope,
        version: Long,
        items: List<ThreadItemDto>,
        coverage: String,
    ) {
        val head = "\"conversation_id\":\"c\",\"epoch\":\"e\",\"version\":$version"
        val payload = "{$head,\"items\":[${items.joinToString { it.raw.toString() }}],\"final\":false}"
        val finalPayload = "{$head,\"items\":[],\"final\":true,$coverage}"
        compose.runOnIdle {
            destination.reply(request, payload)
            destination.reply(request, finalPayload)
        }
        // Only synthetic fixture content: keep both wire boundaries with the counted XML.
        println("receipt=reply request=${request.id} type=${request.type} payload=$payload")
        println("receipt=final request=${request.id} payload=$finalPayload")
    }

    private fun live(item: ThreadItemDto) {
        destination.push(
            MobileJson.decodeFromString<Envelope>(
                """{"id":${item.rev},"type":"thread_item_added","ts":"2026-10-21T00:00:00Z",
                    "payload":{"conversation_id":"c","epoch":"e","version":${item.rev},"item":${item.raw}}}""",
            ),
        )
    }

    private fun agent(
        active: Boolean,
        rev: Long = 140,
    ): ThreadItemDto {
        val original = authoritativeAgent(20, active = active, endedOrder = if (active) null else 30, session = "session-a")
        return authoritativeItem(
            20,
            "agent",
            active = active,
            status = if (active) "running" else "finished",
            rev = rev,
            endedOrder = if (active) null else 30,
            session = "session-a",
            content = original.content.toString(),
        )
    }

    private fun tool(
        id: Long,
        rev: Long = id,
        active: Boolean = false,
        parent: Long = 20,
        session: String = "session-a",
    ) = authoritativeItem(
        id,
        "tool_call",
        parent = parent,
        rev = rev,
        active = active,
        status = if (active) "running" else "done",
        session = session,
        content = """{"name":"Read","input_summary":"file-$id","input":{"file_path":"file-$id"},
                "result":{"result_summary":"tool-output-$id"}}""",
    )

    private fun persisted(
        revisions: Map<Long, Long>,
        continuation: ULong? = null,
    ) {
        await {
            val saved = runBlocking { cache.readAuthoritativeThread("host", "c") }?.toSnapshot()
            saved != null &&
                revisions.all { (id, rev) -> saved.items.any { it.id == id && it.rev == rev } } &&
                (continuation == null || saved.coverage?.continuation == continuation)
        }
        val saved = requireNotNull(runBlocking { cache.readAuthoritativeThread("host", "c") }).toSnapshot()
        assertFalse(saved.items.single { it.id == 20L }.active)
        assertEquals("session-a", saved.items.single { it.id == 20L }.session)
        assertEquals(30L, saved.items.single { it.id == 20L }.endedOrder)
        for (id in revisions.keys.filter { it in setOf(21L, 22L, 23L) }) {
            assertEquals(20L, saved.items.single { it.id == id }.parent)
            assertEquals("session-a", saved.items.single { it.id == id }.session)
        }
    }

    private fun assertFamily(revisions: Map<Long, Long>) {
        compose.runOnIdle {
            val projected = presentation()
            assertEquals("host", projected.host)
            assertEquals("c", projected.conversation)
            assertEquals("e", projected.sourceSnapshot.epoch)
            val facts = projected.sourceSnapshot.items
            assertEquals(facts.size, facts.map { it.id }.distinct().size)
            for ((id, rev) in revisions) assertEquals(rev, facts.single { it.id == id }.rev)
            val old = facts.single { it.id == 20L }
            assertFalse("earlier-session Agent must stay terminal", old.active)
            assertEquals("finished", old.status)
            assertEquals("session-a", old.session)
            assertEquals(30L, old.endedOrder)
            val content = projected.rows.filterIsInstance<ThreadRow.Delivered>()
            val ids = content.mapNotNull { (it.item as? ThreadItem.MessageItem)?.message?.id }
            assertEquals(ids.size, ids.distinct().size)

            fun key(id: Long) = content.single { (it.item as? ThreadItem.MessageItem)?.message?.id == projectedKey(id) }
            val a = key(20)
            assertEquals(ToolCallStatus.Done, (a.item as ThreadItem.MessageItem).message.toolCall?.status)
            val later = content.indexOf(key(60))
            assertTrue(content.indexOf(key(25)) < content.indexOf(a))
            assertTrue(content.indexOf(a) < later)
            for (id in revisions.keys.filter { it in setOf(21L, 22L, 23L) }) {
                val child = key(id)
                assertEquals(a.agentBlockId, child.agentBlockId)
                assertEquals(20L, facts.single { it.id == id }.parent)
                assertEquals("session-a", facts.single { it.id == id }.session)
                assertTrue(content.indexOf(child) in (content.indexOf(a) + 1) until later)
            }
            val b = key(70)
            assertEquals(ToolCallStatus.Running, (b.item as ThreadItem.MessageItem).message.toolCall?.status)
            assertEquals("session-b", facts.single { it.id == 70L }.session)
            assertEquals(b.agentBlockId, key(71).agentBlockId)
            assertTrue(content.indexOf(b) > content.indexOf(key(80)))
            assertEquals(1, projected.tasks.liveCount)
            assertEquals(
                listOf("task-70"),
                projected.tasks.tasks
                    .filterNot { it.isFinished }
                    .map { it.taskId },
            )
            val launch = projected.rows.filterIsInstance<ThreadRow.AgentStartMarker>().single { it.agentId == projectedKey(20) }
            assertTrue(launch.finished)
        }
    }

    private fun projectedKey(id: Long): String =
        presentation()
            .items
            .filterIsInstance<ThreadItem.MessageItem>()
            .single { it.message.id.endsWith(":$id") }
            .message.id

    private fun assertRendered() {
        compose.onNodeWithText("1 task running").assertIsDisplayed()
        val list = compose.onNode(hasScrollToIndexAction())
        val marker = hasText("agent-20") and hasText("Agent finished")
        list.performScrollToNode(marker)
        compose.onNode(marker).assertIsDisplayed()
        val a = "background-agent:${projectedKey(20)}"
        list.performScrollToNode(hasTestTag(a))
        val aIndex =
            listState.layoutInfo.visibleItemsInfo
                .single { it.key == "msg:${projectedKey(20)}" }
                .index
        compose
            .onAllNodes(
                hasContentDescription("Tool call running") and hasAnyAncestor(hasTestTag(a)),
                useUnmergedTree = true,
            ).assertCountEquals(0)
        compose
            .onAllNodes(
                hasContentDescription("Tool call done") and hasAnyAncestor(hasTestTag(a)),
                useUnmergedTree = true,
            ).assertCountEquals(1)
        for (id in listOf(21L, 22L, 23L).filter { id -> presentation().sourceSnapshot.items.any { it.id == id } }) {
            val text = if (id == 23L) "text-23" else "file-$id"
            list.performScrollToNode(hasText(text))
            compose
                .onNode(
                    hasText(text) and hasAnyAncestor(hasTestTag("background-agent-child:${projectedKey(20)}")),
                    useUnmergedTree = true,
                ).assertIsDisplayed()
        }
        list.performScrollToNode(hasText("text-25"))
        val previousIndex =
            listState.layoutInfo.visibleItemsInfo
                .single { it.key == "msg:${projectedKey(25)}" }
                .index
        assertTrue("finished family follows its ended-order anchor in the reversed UI list", previousIndex > aIndex)
        list.performScrollToNode(hasText("text-60"))
        val nextSessionIndex =
            listState.layoutInfo.visibleItemsInfo
                .single { it.key == "msg:${projectedKey(60)}" }
                .index
        assertTrue("finished family precedes the new session in the reversed UI list", aIndex > nextSessionIndex)
        val b = "background-agent:${projectedKey(70)}"
        list.performScrollToNode(hasTestTag(b))
        compose
            .onAllNodes(
                hasContentDescription("Tool call running") and hasAnyAncestor(hasTestTag(b)),
                useUnmergedTree = true,
            ).assertCountEquals(1)
    }

    @Test fun terminalFamilySurvivesRestartOlderPageAndReplacementBesideRunningAgent() {
        open()
        compose.runOnIdle { destination.connect() }
        // Subscribe before opening queries, as the production screen does.
        val initialCollector = vm.viewModelScope.launch { vm.state.collect {} }
        val initial = request("thread_catch_up")
        batch(
            initial,
            100,
            listOf(agent(true, 20), tool(21, active = true), authoritativeItem(25, session = "session-a")),
            "\"lower_order\":25,\"upper_order\":101,\"continuation\":25,\"older_exists\":true",
        )
        awaitFact(20, 20)
        compose.runOnIdle {
            assertTrue(
                presentation()
                    .sourceSnapshot.items
                    .single { it.id == 20L }
                    .active,
            )
            assertEquals(1, presentation().tasks.liveCount)
        }
        compose.runOnIdle {
            live(agent(false))
            live(tool(21, rev = 141))
            live(authoritativeItem(23, order = 35, parent = 20, rev = 142, session = "session-a"))
        }
        awaitFact(23, 142)
        persisted(mapOf(20L to 140L, 21L to 141L, 23L to 142L))
        val firstCache = cache
        val firstGraph = destination
        compose.runOnIdle {
            initialCollector.cancel()
            owners.clear()
            destination.close()
        }
        open()
        assertNotSame(firstCache, cache)
        assertNotSame(firstGraph, destination)
        assertNotSame(firstGraph.coordinator, destination.coordinator)
        assertEquals(ThreadSupport.Unverified, support())
        mountScreen()
        awaitFact(20, 140)
        compose.runOnIdle { destination.connect() }
        val reopen = request("thread_catch_up")
        assertEquals(JsonPrimitive(100), reopen.payload.jsonObject["version"])
        val sessionB =
            listOf(
                authoritativeItem(
                    50,
                    "session_divider",
                    session = "session-b",
                    content = """{"cause":"operator_reset","previous_session_id":"session-a","new_session_id":"session-b"}""",
                ),
                authoritativeItem(60, "user_message", session = "session-b"),
                authoritativeAgent(70, session = "session-b"),
                tool(71, active = true, parent = 70, session = "session-b"),
                authoritativeItem(80, session = "session-b"),
            )
        val heldReply = authoritativeItem(23, order = 35, parent = 20, rev = 142, session = "session-a")
        batch(reopen, 200, listOf(agent(false), tool(21, rev = 141), heldReply) + sessionB, "\"from_version\":100")
        awaitFact(80, 80)
        assertFamily(mapOf(20L to 140L, 21L to 141L, 23L to 142L))
        assertRendered()
        val beforePage = destination.requests.size
        // Control this boundary independently of the scrolls used to inspect status and ownership.
        compose.runOnIdle { vm.onDemandOlderHistory() }
        val page = request("thread_page", beforePage)
        assertEquals(JsonPrimitive(25), page.payload.jsonObject["before_order"])
        compose.runOnIdle {
            live(agent(false, 210))
            live(tool(21, rev = 211))
        }
        awaitFact(21, 211)
        val pageItems = listOf(authoritativeItem(19, "user_message", session = "session-a"), agent(false), tool(21, rev = 141), tool(22))
        batch(page, 200, pageItems, "\"lower_order\":19,\"upper_order\":25,\"continuation\":19,\"older_exists\":false")
        awaitFact(22, 22)
        await { presentation().sourceSnapshot.coverage?.continuation == 19uL }
        compose.runOnIdle {
            val coverage = requireNotNull(presentation().sourceSnapshot.coverage)
            assertEquals(19uL, coverage.lowerOrder)
            assertEquals(101uL, coverage.upperOrder)
            assertFalse(coverage.olderExists)
            println("receipt=page-admitted lower=19 upper=101 continuation=19 older=false")
        }
        val expected = mapOf(20L to 210L, 21L to 211L, 22L to 22L, 23L to 142L)
        assertFamily(expected)
        assertRendered()
        persisted(expected, 19uL)
        val firstOwner = (support() as ThreadSupport.Supported).connection
        val previousKeys = presentation().rows.map { it.listKey(0) }
        val beforeReconnect = destination.requests.size
        compose.runOnIdle { destination.disconnect() }
        await { support() == ThreadSupport.Unverified }
        compose.runOnIdle { destination.connect() }
        val reconnect = request("thread_catch_up", beforeReconnect)
        val secondOwner = (support() as ThreadSupport.Supported).connection
        assertNotSame(firstOwner, secondOwner)
        println("receipt=connection-replaced distinct=true restored-checkpoint=${reconnect.payload.jsonObject["version"]}")
        val recovered = listOf(agent(false, 210), tool(21, rev = 211), tool(22), heldReply) + sessionB
        batch(
            reconnect,
            220,
            recovered.reversed() + recovered,
            "\"from_version\":${reconnect.payload.jsonObject["version"]}",
        )
        await { presentation().sourceSnapshot.checkpoint == 220L }
        compose.runOnIdle {
            repeat(2) {
                live(agent(true, 20))
                live(tool(21, active = true))
            }
        }
        compose.waitForIdle()
        assertFamily(expected)
        assertEquals(previousKeys, presentation().rows.map { it.listKey(0) })
        assertRendered()
        persisted(expected, 19uL)
        assertTrue(destination.requests.none { it.type in setOf("request_history", "backfill", "backfill_since") })
    }

    @Test fun missingTerminalNeverInfersCompletionFromLaterSessionOrAbsentRoster() {
        open()
        mountScreen()
        compose.runOnIdle { destination.connect() }
        batch(
            request("thread_catch_up"),
            100,
            listOf(agent(true, 20), tool(21), authoritativeAgent(70, session = "session-b")),
            "\"lower_order\":20,\"upper_order\":101,\"continuation\":20,\"older_exists\":false",
        )
        awaitFact(70, 70)
        compose.runOnIdle {
            assertEquals(2, presentation().tasks.liveCount)
            assertTrue(
                presentation()
                    .sourceSnapshot.items
                    .single { it.id == 20L }
                    .active,
            )
            assertFalse(
                presentation()
                    .rows
                    .filterIsInstance<ThreadRow.AgentStartMarker>()
                    .single { it.agentId == projectedKey(20) }
                    .finished,
            )
        }
        compose.onNodeWithText("2 tasks running").assertIsDisplayed()
        for (id in listOf(20L, 70L)) {
            val tag = "background-agent:${projectedKey(id)}"
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
            compose
                .onAllNodes(
                    hasContentDescription("Tool call running") and hasAnyAncestor(hasTestTag(tag)),
                    useUnmergedTree = true,
                ).assertCountEquals(1)
        }
    }
}
