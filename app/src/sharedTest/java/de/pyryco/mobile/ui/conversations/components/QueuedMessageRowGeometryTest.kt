package de.pyryco.mobile.ui.conversations.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.repository.QueuedMessage
import de.pyryco.mobile.data.repository.SessionCapabilities
import de.pyryco.mobile.ui.conversations.thread.ThreadRunConfig
import de.pyryco.mobile.ui.conversations.thread.ThreadScreen
import de.pyryco.mobile.ui.conversations.thread.ThreadUiState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.theme.userBubbleContainer
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class QueuedMessageRowGeometryTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun referenceRows_matchBubblePaddingSpacingAndLeftActionColumn() {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 892.dp))) {
                PyrycodeMobileTheme {
                    Column {
                        QueuedMessageRow(WRAPPING, {}, onSendNow = {})
                        QueuedMessageRow(SHORT, {}, onSendNow = {})
                    }
                }
            }
        }
        val text = rule.onNodeWithText(WRAPPING, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val bubbles = rule.onAllNodesWithTag("queued-bubble", useUnmergedTree = true)
        val bubble = bubbles[0].getUnclippedBoundsInRoot()
        val next = bubbles[1].getUnclippedBoundsInRoot()
        assertEquals(200f, bubble.width.value, 2f)
        assertEquals(392f, bubble.right.value, 2f)
        assertEquals(20f, (text.left - bubble.left).value, 2f)
        assertEquals(20f, (bubble.right - text.right).value, 2f)
        val verticalInset = maxOf(16f, (96f - text.height.value) / 2)
        assertEquals(verticalInset, (text.top - bubble.top).value, 2f)
        assertEquals(verticalInset, (bubble.bottom - text.bottom).value, 2f)
        assertEquals(16f, (next.top - bubble.bottom).value, 2f)
        assertEquals(96f, next.height.value, 1f)
        val send = rule.onAllNodesWithTag("queued-send-glyph", useUnmergedTree = true)[0].getUnclippedBoundsInRoot()
        val cancel = rule.onAllNodesWithTag("queued-cancel-glyph", useUnmergedTree = true)[0].getUnclippedBoundsInRoot()
        listOf(send, cancel).forEach {
            assertEquals(12f, it.width.value, 1f)
            assertEquals(12f, it.height.value, 1f)
        }
        assertEquals(25f, (((cancel.top + cancel.bottom) / 2) - ((send.top + send.bottom) / 2)).value, 1f)
        assertEquals(
            ((bubble.top + bubble.bottom) / 2).value,
            ((((send.top + send.bottom) / 2) + ((cancel.top + cancel.bottom) / 2)) / 2).value,
            1f,
        )
        val column = rule.onAllNodesWithTag("queued-actions", useUnmergedTree = true)[0].getUnclippedBoundsInRoot()
        assertEquals(13f, column.width.value, 1f)
        assertEquals(12f, (bubble.left - column.right).value, 1f)
        val waiting = rule.onAllNodesWithTag("queued-waiting-glyph", useUnmergedTree = true)[0].getUnclippedBoundsInRoot()
        assertEquals(12f, (column.left - waiting.right).value, 1f)
    }

    @Test
    fun wideViewport_stillCapsWrappingBubbleAt200Dp() {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(600.dp, 892.dp))) {
                PyrycodeMobileTheme { QueuedMessageRow(WRAPPING.repeat(3), {}, onSendNow = {}) }
            }
        }
        val bubble = rule.onNodeWithTag("queued-bubble", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(200f, bubble.width.value, 2f)
        val text = rule.onNodeWithText(WRAPPING.repeat(3), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals(16f, (text.top - bubble.top).value, 2f)
        assertEquals(16f, (bubble.bottom - text.bottom).value, 2f)
    }

    @Test fun pairedTargets_at320Dp_routeEdgesAndMidpointToOnlySelectedRow() = pairedTargets(320)

    @Test fun pairedTargets_at412Dp_routeEdgesAndMidpointToOnlySelectedRow() = pairedTargets(412)

    private fun pairedTargets(widthDp: Int) {
        var text by mutableStateOf(SHORT)
        val sends = IntArray(2)
        val drops = IntArray(2)
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, 1600.dp))) {
                PyrycodeMobileTheme {
                    Column {
                        repeat(2) { row ->
                            QueuedMessageRow(text, { drops[row]++ }, onSendNow = { sends[row]++ })
                        }
                    }
                }
            }
        }
        listOf(SHORT, WRAPPING, "unbroken".repeat(30)).forEach { value ->
            rule.runOnIdle { text = value }
            repeat(2) { row ->
                val send = rule.onAllNodesWithContentDescription(SEND)[row]
                val drop = rule.onAllNodesWithContentDescription(DROP)[row]
                listOf(send, drop).forEach { action ->
                    action.assertIsDisplayed().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                    val bounds = action.getUnclippedBoundsInRoot()
                    assertEquals(48f, bounds.width.value, 1f)
                    assertEquals(48f, bounds.height.value, 1f)
                    assertTrue(bounds.left >= 0.dp && bounds.right <= widthDp.dp)
                }
                val sendBounds = send.getUnclippedBoundsInRoot()
                val dropBounds = drop.getUnclippedBoundsInRoot()
                assertEquals(sendBounds.bottom.value, dropBounds.top.value, 1f)
                val bubble = rule.onAllNodesWithTag("queued-bubble", useUnmergedTree = true)[row].getUnclippedBoundsInRoot()
                assertEquals(((bubble.top + bubble.bottom) / 2).value, sendBounds.bottom.value, 1f)
                assertTrue(bubble.width <= 201.dp)
                // Centre, both horizontal edges, outer vertical edge and either side of the shared edge.
                repeat(5) { position ->
                    val beforeSends = sends.toList()
                    val beforeDrops = drops.toList()
                    send.performTouchInput {
                        click(
                            when (position) {
                                0 -> center
                                1 -> Offset(1f, center.y)
                                2 -> Offset(width - 1f, center.y)
                                3 -> Offset(center.x, 1f)
                                else -> Offset(center.x, height - 1f)
                            },
                        )
                    }
                    assertEquals(beforeSends.mapIndexed { index, count -> count + if (index == row) 1 else 0 }, sends.toList())
                    assertEquals(beforeDrops, drops.toList())
                    drop.performTouchInput {
                        click(
                            when (position) {
                                0 -> center
                                1 -> Offset(1f, center.y)
                                2 -> Offset(width - 1f, center.y)
                                3 -> Offset(center.x, height - 1f)
                                else -> Offset(center.x, 1f)
                            },
                        )
                    }
                    assertEquals(beforeSends.mapIndexed { index, count -> count + if (index == row) 1 else 0 }, sends.toList())
                    assertEquals(beforeDrops.mapIndexed { index, count -> count + if (index == row) 1 else 0 }, drops.toList())
                }
            }
        }
    }

    @Test fun cancelOnly_at320Dp_keepsIndependentTarget() = cancelOnly(320)

    @Test fun cancelOnly_at412Dp_keepsIndependentTarget() = cancelOnly(412)

    private fun cancelOnly(widthDp: Int) {
        var text by mutableStateOf(SHORT)
        val drops = IntArray(2)
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(widthDp.dp, 1600.dp))) {
                PyrycodeMobileTheme {
                    Column { repeat(2) { row -> QueuedMessageRow(text, { drops[row]++ }) } }
                }
            }
        }
        listOf(SHORT, WRAPPING, "unbroken".repeat(30)).forEach { value ->
            rule.runOnIdle { text = value }
            rule.onNodeWithContentDescription(SEND).assertDoesNotExist()
            repeat(2) { row ->
                val drop = rule.onAllNodesWithContentDescription(DROP)[row].assertIsDisplayed()
                val bounds = drop.getUnclippedBoundsInRoot()
                val bubble = rule.onAllNodesWithTag("queued-bubble", useUnmergedTree = true)[row].getUnclippedBoundsInRoot()
                assertEquals(48f, bounds.width.value, 1f)
                assertEquals(48f, bounds.height.value, 1f)
                assertEquals(((bubble.top + bubble.bottom) / 2).value, ((bounds.top + bounds.bottom) / 2).value, 1f)
                repeat(5) { position ->
                    val before = drops.toList()
                    drop.performTouchInput {
                        click(
                            when (position) {
                                0 -> center
                                1 -> Offset(1f, center.y)
                                2 -> Offset(width - 1f, center.y)
                                3 -> Offset(center.x, 1f)
                                else -> Offset(center.x, height - 1f)
                            },
                        )
                    }
                    assertEquals(before.mapIndexed { index, count -> count + if (index == row) 1 else 0 }, drops.toList())
                }
            }
        }
    }

    @Test
    fun palettes_keepActionsOpaqueAndOnlyBubbleAndWaitingDimmed() {
        var dark by mutableStateOf(false)
        var paired by mutableStateOf(true)
        var productionCanvas by mutableStateOf(false)
        var showRows by mutableStateOf(true)
        var view: View? = null
        var tint = Color.Unspecified
        var fill = Color.Unspecified
        var waitingTint = Color.Unspecified
        val rows = listOf(SHORT, WRAPPING, "unbroken".repeat(20))
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 892.dp))) {
                PyrycodeMobileTheme(darkTheme = dark, dynamicColor = false) {
                    view = LocalView.current
                    tint = MaterialTheme.colorScheme.primary
                    fill = MaterialTheme.colorScheme.userBubbleContainer
                    waitingTint = MaterialTheme.colorScheme.onSurfaceVariant
                    if (productionCanvas) {
                        // Exercise ThreadScreen's actual radial gradient, scrim and queued-row placement.
                        ThreadScreen(
                            state =
                                ThreadUiState(
                                    conversationId = "contrast-fixture",
                                    displayName = "Queued contrast",
                                    isPromoted = true,
                                    hasMessages = true,
                                    queuedMessages =
                                        if (showRows) {
                                            rows.mapIndexed { index, text ->
                                                QueuedMessage(index.toLong(), text, Instant.parse("2026-10-07T12:00:00Z"))
                                            }
                                        } else {
                                            emptyList()
                                        },
                                    runConfig =
                                        ThreadRunConfig(
                                            sessionId = "contrast-session",
                                            settingsAvailable = true,
                                            capabilities = SessionCapabilities(emptyList(), emptyList(), midTurnInput = paired),
                                        ),
                                ),
                            onBack = {},
                            onSendMessage = {},
                            connectionState = ConnectionState.Connected,
                            onRetry = {},
                        )
                    } else {
                        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column {
                                if (showRows) rows.forEach { QueuedMessageRow(it, {}, onSendNow = if (paired) ({}) else null) }
                            }
                        }
                    }
                }
            }
        }

        fun matches(
            actual: Color,
            expected: Color,
        ): Boolean =
            kotlin.math.abs(actual.red - expected.red) <= 1.1f / 255f &&
                kotlin.math.abs(actual.green - expected.green) <= 1.1f / 255f &&
                kotlin.math.abs(actual.blue - expected.blue) <= 1.1f / 255f

        fun contrast(
            first: Color,
            second: Color,
        ): Float = (maxOf(first.luminance(), second.luminance()) + 0.05f) / (minOf(first.luminance(), second.luminance()) + 0.05f)

        fun snapshot(): Bitmap =
            rule.runOnIdle {
                val root = checkNotNull(view)
                Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
            }

        for (isProduction in listOf(false, true)) {
            for (isDark in listOf(false, true)) {
                for (hasSend in listOf(true, false)) {
                    rule.runOnIdle {
                        productionCanvas = isProduction
                        dark = isDark
                        paired = hasSend
                        showRows = true
                    }
                    val tags = listOf("queued-cancel-glyph") + if (hasSend) listOf("queued-send-glyph") else emptyList()
                    val glyphs =
                        tags.flatMap { tag ->
                            rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().map { tag to it.boundsInRoot }
                        }
                    assertEquals(if (hasSend) 6 else 3, glyphs.size)
                    val bubbles =
                        rule
                            .onAllNodesWithTag(
                                "queued-bubble",
                                useUnmergedTree = true,
                            ).fetchSemanticsNodes()
                            .map { it.boundsInRoot }
                    val waiting =
                        rule
                            .onAllNodesWithTag(
                                "queued-waiting-glyph",
                                useUnmergedTree = true,
                            ).fetchSemanticsNodes()
                            .map { it.boundsInRoot }
                    val painted = snapshot()
                    // A second render without rows measures the canvas at exactly the same pixels,
                    // including the gradient under each glyph rather than a nominal flat surface.
                    rule.runOnIdle { showRows = false }
                    val canvas = snapshot()

                    fun background(
                        x: Int,
                        y: Int,
                    ) = Color(canvas.getPixel(x, y))

                    fun hasPaint(
                        bounds: androidx.compose.ui.geometry.Rect,
                        expected: (Int, Int) -> Color,
                    ): Boolean =
                        (bounds.top.toInt() until bounds.bottom.toInt()).any { y ->
                            (bounds.left.toInt() until bounds.right.toInt()).any { x ->
                                matches(Color(painted.getPixel(x, y)), expected(x, y))
                            }
                        }
                    try {
                        val mode = "production=$isProduction dark=$isDark sendNow=$hasSend"
                        glyphs.forEach { (tag, bounds) ->
                            val pixels =
                                (bounds.top.toInt() until bounds.bottom.toInt()).flatMap { y ->
                                    (bounds.left.toInt() until bounds.right.toInt()).map { x -> x to y }
                                }
                            // The highest-contrast rendered pixel finds solid glyph paint, excluding AA edges.
                            val foreground =
                                pixels
                                    .maxBy { (x, y) -> contrast(Color(painted.getPixel(x, y)), background(x, y)) }
                                    .let { (x, y) -> Color(painted.getPixel(x, y)) }
                            val minimumContrast = pixels.minOf { (x, y) -> contrast(foreground, background(x, y)) }
                            assertTrue("$tag $mode contrast must be at least 3:1; got $minimumContrast", minimumContrast >= 3f)
                            assertTrue("$tag $mode full-opacity primary glyph", hasPaint(bounds) { _, _ -> tint })
                        }
                        bubbles.forEach { bounds ->
                            val x = (bounds.left + 12).toInt()
                            val y = (bounds.top + 8).toInt()
                            val dimFill = fill.copy(alpha = 0.6f).compositeOver(background(x, y))
                            assertTrue("dimmed bubble $mode", matches(Color(painted.getPixel(x, y)), dimFill))
                        }
                        waiting.forEach { bounds ->
                            assertTrue(
                                "dimmed waiting glyph $mode",
                                hasPaint(bounds) { x, y ->
                                    waitingTint.copy(alpha = 0.6f).compositeOver(background(x, y))
                                },
                            )
                        }
                    } finally {
                        painted.recycle()
                        canvas.recycle()
                    }
                }
            }
        }
    }

    private companion object {
        const val SEND = "Send now"
        const val DROP = "Drop this queued message"
        const val SHORT = "Then push a draft PR."
        const val WRAPPING = "Can you also update the migration tests once you're done?"
    }
}
