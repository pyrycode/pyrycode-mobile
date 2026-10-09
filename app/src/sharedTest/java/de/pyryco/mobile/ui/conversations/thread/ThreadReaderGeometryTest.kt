package de.pyryco.mobile.ui.conversations.thread

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.conversations.components.MESSAGE_BUBBLE_TEST_TAG
import de.pyryco.mobile.ui.conversations.components.MarkdownText
import de.pyryco.mobile.ui.conversations.components.StreamingMarkdownText
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** Observe actual rendered bubble coordinates, including intermediate reveal frames. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
open class ThreadReaderGeometryTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var listState: LazyListState
    private lateinit var view: View
    private lateinit var replyCoordinates: LayoutCoordinates
    private var olderCoordinates: LayoutCoordinates? = null
    private var olderTop = 0f
    private val renderedOlderTops = mutableListOf<Float>()
    private var recording = false
    private val renderedTops = mutableListOf<Float>()
    private var consumedMovement = 0f
    private val motion =
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                consumedMovement += consumed.y
                return Offset.Zero
            }
        }
    private val body = (1..95).joinToString("\n\n") { "Reader line $it." }
    private val growthTail = "[Settlement growth](https://example.invalid/" + "segment/".repeat(40)
    private val shrinkTail = "```text\nSettlement shrink\n```"
    private val state =
        mutableStateOf(
            ThreadUiState(
                conversationId = "reader",
                displayName = "Reader",
                isPromoted = true,
                hasMessages = true,
                items = (1..25).map { message("old-$it", "Older row $it.", false) } + message("reply", body, true),
            ),
        )

    @Test open fun streamingReader_holdsTopAndOlderRowsEveryFrame() {
        openReader()
        val top = replyCoordinates.positionInRoot().y
        recording = true
        appendAndReveal()
        finishReply()
        frames(5)
        assertFrames(top)
    }

    @Test open fun restingTouch_holdsReaderEveryFrame() {
        openReader()
        val top = replyCoordinates.positionInRoot().y
        list().performTouchInput { down(Offset(centerX, height / 2f)) }
        recording = true
        appendAndReveal()
        settlementCases().forEach { (tail, grows) ->
            updateReply(body + "\n\n" + tail, true)
            fullyReveal()
            settleAndAssertHeight(grows)
        }
        assertFrames(top)
        list().performTouchInput { up() }
    }

    @Test open fun settledMarkdown_holdsTopForGrowthAndShrink() {
        openReader()
        val top = replyCoordinates.positionInRoot().y
        recording = true
        settlementCases().forEach { (tail, grows) ->
            updateReply(body + "\n\n" + tail, true)
            fullyReveal()
            settleAndAssertHeight(grows)
            assertFrames(top)
        }
    }

    @Test open fun settlementFixtures_changeHeightWithoutProgressiveReveal() {
        val caret = mutableStateOf(true)
        compose.setContent {
            PyrycodeMobileTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    listOf("growth" to growthTail, "shrink" to shrinkTail).forEach { (name, text) ->
                        Box(Modifier.width(180.dp).testTag("$name-streaming")) {
                            // Direct renderer input is already complete: no reveal producer or text update.
                            StreamingMarkdownText(text, caretVisible = caret.value)
                        }
                        Box(Modifier.width(180.dp).testTag("$name-settled")) {
                            MarkdownText(text)
                        }
                    }
                }
            }
        }
        listOf(true, false).forEach { visible ->
            compose.runOnIdle { caret.value = visible }
            val growthStreaming =
                compose
                    .onNodeWithTag("growth-streaming")
                    .fetchSemanticsNode()
                    .size.height
            val growthSettled =
                compose
                    .onNodeWithTag("growth-settled")
                    .fetchSemanticsNode()
                    .size.height
            val shrinkStreaming =
                compose
                    .onNodeWithTag("shrink-streaming")
                    .fetchSemanticsNode()
                    .size.height
            val shrinkSettled =
                compose
                    .onNodeWithTag("shrink-settled")
                    .fetchSemanticsNode()
                    .size.height
            assertTrue("unfinished link grows with caret=$visible", growthSettled > growthStreaming + 1)
            assertTrue("fence caret line shrinks with caret=$visible", shrinkSettled < shrinkStreaming - 1)
        }
    }

    @Test open fun endSpacing_preservesReaderInBothDirections() {
        openReader()
        val top = replyCoordinates.positionInRoot().y
        recording = true
        repeat(2) {
            attachments(true)
            frames(5)
            attachments(false)
            frames(5)
        }
        assertFrames(top)
        recording = false
        compose.mainClock.autoAdvance = true
        list().performScrollToIndex(12)
        compose.waitForIdle()
        replyCoordinates =
            bubble("Older row 14.")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .layoutInfo.coordinates
        olderCoordinates = null
        renderedTops.clear()
        val historyTop = replyCoordinates.positionInRoot().y
        compose.mainClock.autoAdvance = false
        recording = true
        attachments(true)
        frames(5)
        attachments(false)
        frames(5)
        assertFrames(historyTop)
    }

    @Test open fun movingReader_preservesConsumedMovement() {
        openReader()
        settlementCases().forEach { (tail, grows) ->
            updateReply(body, true)
            fullyReveal()
            val top = resetMotionBaseline()
            val beforeDrag = consumedMovement
            list().performTouchInput { down(Offset(centerX, height / 2f)) }
            repeat(6) { step ->
                list().performTouchInput { moveBy(Offset(0f, -15f), delayMillis = 32) }
                if (step == 2) updateReply(body + "\n\n" + tail, true)
                if (step == 3) {
                    assertTrue("spacing changes during the active drag", listState.isScrollInProgress)
                    attachments(true)
                }
                if (step == 4) attachments(false)
                frames(3)
            }
            assertTrue("the drag consumed real movement", consumedMovement < beforeDrag - 10f)
            fullyReveal()
            assertTrue("settlement arrives during an active drag", listState.isScrollInProgress)
            settleAndAssertHeight(grows)
            assertTrue("settlement preserves the active drag", listState.isScrollInProgress)

            // Re-enter streaming with the same complete source before starting the fling.
            val text = replyText()
            updateReply(text, true)
            fullyReveal()
            list().performTouchInput {
                // Frame sampling leaves preceding velocity samples far apart. Finish with a real burst.
                repeat(4) { moveBy(Offset(0f, -30f), delayMillis = 10) }
                up()
            }
            val beforeFling = consumedMovement
            attachments(true)
            frames(2)
            assertTrue("the fling remains active after spacing growth", listState.isScrollInProgress)
            attachments(false)
            frames(2)
            assertTrue("settlement arrives during the fling", listState.isScrollInProgress)
            settleAndAssertHeight(grows)
            assertTrue("settlement preserves the active fling", listState.isScrollInProgress)
            frames(120)
            assertTrue("the fling consumes movement after the update", consumedMovement < beforeFling - 1f)
            assertTrue("the fling finishes naturally", !listState.isScrollInProgress)
            assertFrames(top)
        }
    }

    private fun resetMotionBaseline(): Float {
        // Each independent gesture case starts near the reply's top, with room for an unclamped fling.
        recording = false
        compose.runOnUiThread {
            listState.dispatchRawDelta(with(compose.density) { 180.dp.toPx() } - replyCoordinates.positionInRoot().y)
        }
        frames(3)
        val top = replyCoordinates.positionInRoot().y
        captureOlderRow()
        consumedMovement = 0f
        renderedTops.clear()
        renderedOlderTops.clear()
        recording = true
        return top
    }

    private fun settlementCases() = listOf(growthTail to true, shrinkTail to false)

    private fun fullyReveal() {
        // 720ms exceeds the 495ms catch-up budget plus presentation. Sample every frame, including catch-up.
        frames(45)
    }

    private fun settleAndAssertHeight(grows: Boolean) {
        val text = replyText()
        val streamingHeight = replyCoordinates.size.height
        finishReply()
        frames(5)
        assertEquals("settlement must hold the complete source constant", text, replyText())
        val settledHeight = replyCoordinates.size.height
        if (grows) {
            assertTrue("fully revealed unfinished link must grow on settlement", settledHeight > streamingHeight + 1)
        } else {
            assertTrue("fully revealed fence must shrink on settlement", settledHeight < streamingHeight - 1)
        }
    }

    private fun replyText() = (state.value.items.last() as ThreadItem.MessageItem).message.content

    private fun finishReply() {
        val text = (state.value.items.last() as ThreadItem.MessageItem).message.content
        updateReply(text, false)
    }

    private fun attachments(present: Boolean) {
        compose.runOnUiThread {
            val reply = (state.value.items.last() as ThreadItem.MessageItem).message
            val updated =
                reply.copy(
                    attachments = if (present) listOf(MessageAttachment("file", "Notes.pdf", "application/pdf")) else emptyList(),
                )
            state.value = state.value.copy(items = state.value.items.dropLast(1) + ThreadItem.MessageItem(updated))
        }
    }

    private fun updateReply(
        text: String,
        streaming: Boolean,
    ) {
        compose.runOnUiThread {
            state.value = state.value.copy(items = state.value.items.dropLast(1) + message("reply", text, streaming))
        }
    }

    private fun frames(count: Int) {
        repeat(count) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            drawFrame()
        }
    }

    private fun drawFrame() {
        // The native JVM renderer needs an explicit View draw; use the same real composition on Android.
        compose.runOnUiThread {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            bitmap.recycle()
        }
    }

    private fun openReader() {
        compose.setContent {
            view = LocalView.current
            CompositionLocalProvider(
                LocalOverscrollFactory provides null,
                LocalThreadListCompositionObserver provides { listState = it },
            ) {
                PyrycodeMobileTheme {
                    ThreadScreen(
                        state = state.value,
                        onBack = {},
                        onSendMessage = {},
                        connectionState = ConnectionState.Connected,
                        onRetry = {},
                        modifier =
                            Modifier.nestedScroll(motion).drawWithContent {
                                val hasRows = listState.layoutInfo.visibleItemsInfo.isNotEmpty()
                                drawContent()
                                if (recording && hasRows && replyCoordinates.isAttached) {
                                    renderedTops += replyCoordinates.positionInRoot().y - consumedMovement
                                    olderCoordinates?.takeIf { it.isAttached && olderTop + consumedMovement >= 0f }?.let {
                                        renderedOlderTops +=
                                            it.positionInRoot().y - consumedMovement
                                    }
                                }
                            },
                    )
                }
            }
        }
        compose.waitForIdle()
        // The newest reply is the first lazy row. Capture the actual un-clipped layout coordinates.
        replyCoordinates = bubble("Reader line 1.").fetchSemanticsNode().layoutInfo.coordinates
        compose.runOnIdle {
            assertTrue("fixture reply must overflow", replyCoordinates.size.height > listState.layoutInfo.viewportSize.height)
            listState.dispatchRawDelta(with(compose.density) { 180.dp.toPx() } - replyCoordinates.positionInRoot().y)
        }
        compose.waitForIdle()
        assertEquals(0, listState.firstVisibleItemIndex)
        assertTrue(listState.firstVisibleItemScrollOffset > 4)
        captureOlderRow()
        compose.mainClock.autoAdvance = false
    }

    private fun appendAndReveal() {
        val tail = (1..20).joinToString("\n") { "Appended line $it." }
        updateReply(body + "\n" + tail, true)
        frames(45)
    }

    private fun captureOlderRow() {
        // Prefetched lazy items retain coordinates; select the displayed keyed fixture row, not a y maximum.
        olderCoordinates =
            bubble("Older row 25.")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .layoutInfo.coordinates
        olderTop = olderCoordinates?.positionInRoot()?.y ?: 0f
        assertTrue("an older row must be above the reply", olderTop < replyCoordinates.positionInRoot().y)
    }

    private fun bubble(text: String) =
        compose.onNode(hasTestTag(MESSAGE_BUBBLE_TEST_TAG) and hasAnyDescendant(hasText(text)), useUnmergedTree = true)

    private fun assertFrames(top: Float) {
        assertTrue("must observe rendered reveal frames", renderedTops.isNotEmpty())
        if (olderCoordinates != null) assertTrue("must observe the displayed older row", renderedOlderTops.isNotEmpty())
        renderedTops.forEachIndexed { frame, actual -> assertEquals("rendered frame $frame", top, actual, 1f) }
        renderedOlderTops.forEachIndexed { frame, actual ->
            assertEquals("older row rendered frame $frame", olderTop, actual, 1f)
        }
    }

    private fun list() = compose.onNode(hasScrollToIndexAction())

    private fun message(
        id: String,
        text: String,
        streaming: Boolean,
    ): ThreadItem =
        ThreadItem.MessageItem(Message(id, "s1", Role.Assistant, text, Instant.parse("2026-10-01T00:00:00Z"), isStreaming = streaming))
}
