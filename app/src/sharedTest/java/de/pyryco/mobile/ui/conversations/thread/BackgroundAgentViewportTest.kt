package de.pyryco.mobile.ui.conversations.thread

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.BackgroundTaskUpdate
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.model.ToolCall
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.MESSAGE_BUBBLE_TEST_TAG
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Native draws observe relocation from its first rendered update frame, on JVM and Android. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
open class BackgroundAgentViewportTest {
    @get:Rule val compose = createComposeRule()
    private val ts = Instant.parse("2026-10-09T10:00:00Z")
    private val state = mutableStateOf(ThreadUiState("viewport", "Viewport", hasMessages = true))
    private lateinit var listState: LazyListState
    private lateinit var view: View
    private var recording = false
    private var anchor: LayoutCoordinates? = null
    private val positions = mutableListOf<Float>()
    private var stationaryKey: String? = null
    private val stationaryLayouts = mutableListOf<Pair<String, Int?>>()
    private val mainRowBaselines = mutableMapOf<Any, Int>()
    private val mainRowFrames = mutableListOf<Map<Any, Int?>>()
    private val tallVisibleFrames = mutableListOf<Boolean>()
    private var watchVacatedBoundary = false
    private val vacatedBoundaryFrames = mutableListOf<Float>()
    private val ends = mutableListOf<Pair<Int, Int>>()

    @Test open fun followerCompletion_keepsNewestEveryRenderedFrame() {
        mount(collapsed = false)
        completeAndRecord("a")
        assertNewestFrames()
    }

    @Test open fun followerCompletion_withAnotherRunningBlock_keepsNewestEveryFrame() {
        mount(collapsed = true, second = true)
        completeAndRecord("b")
        assertNewestFrames()
        assertTrue(
            "other block remains running",
            state.value.items.none {
                it is ThreadItem.BackgroundTaskLifecycle && it.taskId == "ta" &&
                    it.terminal != null
            },
        )
        completeAndRecord("a")
        assertNewestFrames()
    }

    @Test open fun followerMultipleCompletions_keepNewestEveryFrame() {
        mount(collapsed = true, second = true)
        recording = true
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { finish(setOf("a", "b")) }
        frames(8)
        assertNewestFrames()
    }

    @Test open fun visibleCompletion_preservesStationaryRows_collapsed() = visibleReader(collapsed = true)

    @Test open fun visibleCompletion_preservesStationaryRows_uncollapsed() = visibleReader(collapsed = false)

    @Test open fun visibleCompletion_withStationaryGrowth_preservesTopEveryFrame() {
        mount(collapsed = true, second = true)
        list().performScrollToIndex(2)
        compose.runOnIdle { listState.dispatchRawDelta(12f) }
        compose.waitForIdle()
        anchor =
            bubble("main-35")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .layoutInfo.coordinates
        stationaryKey = "msg:main-35"
        val top = requireNotNull(anchor).positionInRoot().y
        recording = true
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            finish(setOf("a"))
            state.value =
                state.value.copy(
                    items =
                        state.value.items.map { item ->
                            if (item is ThreadItem.MessageItem &&
                                item.message.id == "main-35"
                            ) {
                                item.copy(
                                    message =
                                        item.message.copy(
                                            content =
                                                "main-35\n" + "Stationary content grows. ".repeat(30),
                                        ),
                                )
                            } else {
                                item
                            }
                        },
                )
        }
        frames(8)
        assertStationaryFrames(top)
    }

    @Test open fun offscreenCompletion_preservesStationaryRows() {
        mount(collapsed = false)
        list().performScrollToIndex(16)
        compose.waitForIdle()
        val visible = listState.layoutInfo.visibleItemsInfo.first { it.key.toString().startsWith("msg:main-") }
        val text = visible.key.toString().removePrefix("msg:")
        anchor =
            bubble(text)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .layoutInfo.coordinates
        stationaryKey = "msg:$text"
        val top = requireNotNull(anchor).positionInRoot().y
        completeAndRecord("a")
        assertStationaryFrames(top)
        assertTrue("reader must not start following", listState.firstVisibleItemIndex > 0)
    }

    @Test open fun fullViewportCompletion_fillsVacancyAndClamps() {
        mount(collapsed = false, tall = true)
        compose.runOnIdle { listState.dispatchRawDelta(120f) }
        compose.waitForIdle()
        assertTrue("fixture is away from newest", listState.firstVisibleItemScrollOffset > 4)
        assertTrue("all visible rows belong to the moving block", listState.layoutInfo.visibleItemsInfo.all { it.key == "msg:tall-a" })
        completeAndRecord("a")
        val settledFrames = ends.zip(tallVisibleFrames).filterNot { it.second }.map { it.first }
        assertTrue("must draw remaining thread after vacancy fills", settledFrames.isNotEmpty())
        settledFrames.forEach { assertEquals(0 to 0, it) }
        anchor =
            bubble("main-35")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .layoutInfo.coordinates
        val top = requireNotNull(anchor).positionInRoot().y
        positions.clear()
        compose.runOnUiThread { state.value = state.value.copy(items = state.value.items + user("appended")) }
        frames(5)
        assertTrue("newest-end clamp must not resume following", listState.firstVisibleItemIndex > 0)
        positions.forEach { assertEquals(top, it, 1f) }
    }

    @Test open fun fullViewportCompletion_retainsOlderBoundaryAgainstRemainingBlock() {
        mount(collapsed = false, second = true, tall = true, secondTall = true)
        val rows = foldBackgroundAgentBlocks(foldQueuedRows(state.value.items, emptyList()), state.value.items, null).asReversed()

        fun index(key: String) = rows.indexOfFirst { it.listKey(0) == key }
        var olderBlockHeight = 0
        listOf("msg:a", "msg:a-1", "msg:a-2").forEach { key ->
            list().performScrollToIndex(index(key))
            compose.waitForIdle()
            olderBlockHeight +=
                listState.layoutInfo.visibleItemsInfo
                    .first { it.key == key }
                    .size
        }
        list().performScrollToIndex(index("msg:tall-a"))
        compose.waitForIdle()
        val info = listState.layoutInfo
        val height = info.visibleItemsInfo.first { it.key == "msg:tall-a" }.size
        // Keep the block's actual older boundary just outside the viewport, with its root still measured.
        val offset = height + olderBlockHeight - info.viewportSize.height + info.beforeContentPadding - 20
        compose.runOnIdle { listState.dispatchRawDelta(offset.toFloat()) }
        compose.waitForIdle()
        val old = listState.layoutInfo
        assertTrue(
            "viewport must contain only the completing block",
            old.visibleItemsInfo.all {
                it.key in
                    setOf("msg:a", "msg:a-1", "msg:a-2", "msg:tall-a")
            },
        )
        val root = old.visibleItemsInfo.first { it.key == "msg:a" }
        val boundary = (old.viewportSize.height - old.beforeContentPadding - root.offset - root.size).toFloat()
        watchVacatedBoundary = true
        completeAndRecord("a")
        assertTrue("must draw remaining block against the vacated older boundary", vacatedBoundaryFrames.isNotEmpty())
        vacatedBoundaryFrames.forEach { assertEquals(boundary, it, 1f) }
        assertTrue("remaining content fills vacancy without a clamp", listState.firstVisibleItemScrollOffset > 4)
    }

    @Test open fun multipleCompletions_keepStationaryReader() {
        mount(collapsed = true, second = true)
        // Multiple offscreen blocks settle while the reader is anchored in stationary history.
        list().performScrollToIndex(16)
        compose.waitForIdle()
        val visible = listState.layoutInfo.visibleItemsInfo.first { it.key.toString().startsWith("msg:main-") }
        val text = visible.key.toString().removePrefix("msg:")
        anchor =
            bubble(text)
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .layoutInfo.coordinates
        stationaryKey = visible.key.toString()
        val top = requireNotNull(anchor).positionInRoot().y
        recording = true
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { finish(setOf("a", "b")) }
        frames(8)
        assertStationaryFrames(top)
    }

    private fun visibleReader(collapsed: Boolean) {
        mount(collapsed, second = true)
        val movingIndex = if (collapsed) 2 else 3
        list().performScrollToIndex(movingIndex)
        compose.runOnIdle { listState.dispatchRawDelta(12f) }
        compose.waitForIdle()
        assertEquals("moving child is the bottom-most anchor", movingIndex, listState.firstVisibleItemIndex)
        assertTrue("reader is outside follow tolerance", listState.firstVisibleItemScrollOffset > 4)
        anchor =
            bubble("main-35")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .layoutInfo.coordinates
        stationaryKey = "msg:main-35"
        val top = requireNotNull(anchor).positionInRoot().y
        completeAndRecord("a")
        assertStationaryFrames(top)
        // Repeated terminal data and unrelated newer content must not resume following.
        compose.runOnUiThread {
            state.value =
                state.value.copy(
                    items =
                        state.value.items.map { item ->
                            if (item is ThreadItem.MessageItem &&
                                item.message.id == "b-2"
                            ) {
                                item.copy(message = item.message.copy(toolCall = item.message.toolCall?.copy(output = "updated output")))
                            } else {
                                item
                            }
                        },
                )
        }
        frames(5)
        assertStationaryFrames(top)
        assertTrue("reader remains away from newest", listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 4)
    }

    private fun mount(
        collapsed: Boolean,
        second: Boolean = false,
        tall: Boolean = false,
        secondTall: Boolean = false,
    ) {
        val roots = listOf(tool("a", "Agent"), launch("a")) + if (second) listOf(tool("b", "Agent"), launch("b")) else emptyList()
        val children =
            listOf(tool("a-1", "Read", "a"), tool("a-2", "Glob", "a")) +
                if (tall) {
                    listOf(
                        ThreadItem.MessageItem(
                            Message(
                                "tall-a",
                                "s",
                                Role.Assistant,
                                (1..90).joinToString("\n\n") {
                                    "Agent paragraph $it."
                                },
                                ts,
                                false,
                                parentToolUseId = "a",
                            ),
                        ),
                    )
                } else {
                    emptyList()
                }
        val secondChildren = if (second) listOf(tool("b-1", "Read", "b"), tool("b-2", "Glob", "b")) else emptyList()
        val remainingProse =
            if (secondTall) {
                listOf(
                    ThreadItem.MessageItem(
                        Message(
                            "tall-b",
                            "s",
                            Role.Assistant,
                            (1..160).joinToString("\n\n") { "Remaining agent paragraph $it." },
                            ts,
                            false,
                            parentToolUseId = "b",
                        ),
                    ),
                )
            } else {
                emptyList()
            }
        state.value =
            state.value.copy(
                items =
                    (1..35).map { user("old-$it") } + roots + (1..35).map { user("main-$it") } +
                        children + secondChildren + remainingProse,
            )
        compose.setContent {
            view = LocalView.current
            CompositionLocalProvider(LocalOverscrollFactory provides null, LocalThreadListCompositionObserver provides { listState = it }) {
                PyrycodeMobileTheme {
                    ThreadScreen(
                        state.value,
                        {},
                        {},
                        ConnectionState.Connected,
                        {},
                        collapseToolUses = collapsed,
                        modifier =
                            Modifier.drawWithContent {
                                drawContent()
                                if (recording) {
                                    ends += listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                                    val info = listState.layoutInfo
                                    tallVisibleFrames += info.visibleItemsInfo.any { it.key == "msg:tall-a" }
                                    if (watchVacatedBoundary && info.visibleItemsInfo.none { it.key == "msg:tall-a" }) {
                                        info.visibleItemsInfo.firstOrNull { it.key == "msg:b" }?.let {
                                            vacatedBoundaryFrames +=
                                                (info.viewportSize.height - info.beforeContentPadding - it.offset - it.size).toFloat()
                                        }
                                    }
                                    if (mainRowBaselines.isNotEmpty()) {
                                        mainRowFrames +=
                                            mainRowBaselines.keys.associateWith { key ->
                                                info.visibleItemsInfo.firstOrNull { it.key == key }?.let { item ->
                                                    info.viewportSize.height - info.beforeContentPadding - item.offset - item.size
                                                }
                                            }
                                    }
                                    anchor?.takeIf { it.isAttached }?.let { positions += it.positionInRoot().y }
                                    stationaryKey?.let { key ->
                                        stationaryLayouts +=
                                            key to
                                            listState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.key == key }
                                                ?.offset
                                    }
                                }
                            },
                    )
                }
            }
        }
        compose.waitForIdle()
        assertEquals(0, listState.firstVisibleItemIndex)
        assertEquals(0, listState.firstVisibleItemScrollOffset)
    }

    private fun completeAndRecord(id: String) {
        if (stationaryKey != null) {
            val info = listState.layoutInfo
            info.visibleItemsInfo.filter { it.key.toString().startsWith("msg:main-") && it.offset >= 0 }.forEach {
                mainRowBaselines[it.key] = info.viewportSize.height - info.beforeContentPadding - it.offset - it.size
            }
        }
        recording = true
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { finish(setOf(id)) }
        frames(8)
        compose.runOnUiThread { finish(setOf(id)) }
        frames(3)
    }

    private fun finish(ids: Set<String>) {
        val terminal =
            ids.map {
                ThreadItem.BackgroundTaskLifecycle(
                    "t$it",
                    ts,
                    terminal = BackgroundTaskUpdate("", "completed", "", null),
                )
            }
        // Terminal receipts precede the later main rows: completion must really move into history.
        val items = state.value.items
        val insertion = items.indexOfFirst { it is ThreadItem.MessageItem && it.message.id == "main-6" }
        state.value = state.value.copy(items = items.take(insertion) + terminal + items.drop(insertion))
    }

    private fun frames(count: Int) {
        repeat(count) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.runOnUiThread {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                bitmap.recycle()
            }
        }
    }

    private fun assertNewestFrames() {
        assertTrue("must sample rendered frames", ends.isNotEmpty())
        ends.forEachIndexed { frame, (index, offset) ->
            assertEquals("newest index in rendered frame $frame", 0, index)
            assertEquals("newest offset in rendered frame $frame", 0, offset)
        }
    }

    private fun assertStationaryFrames(top: Float) {
        assertTrue("must sample stationary row in rendered frames", positions.isNotEmpty())
        stationaryLayouts.forEachIndexed { frame, (key, offset) ->
            assertTrue(
                "stationary $key must remain in rendered layout $frame",
                offset != null,
            )
        }
        mainRowFrames.forEachIndexed { frame, rows ->
            mainRowBaselines.forEach { (key, baseline) ->
                assertTrue("stationary $key must remain visible in frame $frame", rows[key] != null)
                assertEquals("stationary $key position in frame $frame", baseline.toFloat(), requireNotNull(rows[key]).toFloat(), 1f)
            }
        }
        positions.forEachIndexed { frame, actual -> assertEquals("stationary row in rendered frame $frame", top, actual, 1f) }
    }

    private fun list() = compose.onNode(hasScrollToIndexAction())

    private fun bubble(text: String) =
        compose.onNode(hasTestTag(MESSAGE_BUBBLE_TEST_TAG) and hasAnyDescendant(hasText(text)), useUnmergedTree = true)

    private fun user(id: String) = ThreadItem.MessageItem(Message(id, "s", Role.User, id, ts, false))

    private fun launch(id: String) = ThreadItem.BackgroundTaskLifecycle("t$id", ts, id, "Background agent", "local_agent")

    private fun tool(
        id: String,
        name: String,
        parent: String = "",
    ) = ThreadItem.MessageItem(
        Message(
            id,
            "s",
            Role.Tool,
            "",
            ts,
            false,
            ToolCall(
                name,
                "input",
                "output",
                inputFields =
                    if (name ==
                        "Agent"
                    ) {
                        mapOf("run_in_background" to "true")
                    } else {
                        emptyMap()
                    },
                parentToolUseId = parent,
            ),
        ),
    )
}
