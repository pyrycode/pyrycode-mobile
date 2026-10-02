package de.pyryco.mobile.ui.conversations.list

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class ChannelListColoursTest {
    @get:Rule val rule = createComposeRule()

    private var rootView: View? = null

    @Test
    fun darkPanelAndBarMatchTheReferenceWithOneBlueGreyRule() {
        show(dark = true)
        val bitmap = draw()
        assertPanel(bitmap, Color.rgb(11, 14, 17))
        assertToolbarRule(bitmap, Color.rgb(34, 65, 92))
        assertSelectedRowFill(bitmap, Color.rgb(19, 74, 116))
    }

    @Test
    fun lightPanelAndRuleKeepTheirExistingColours() {
        show(dark = false)
        val bitmap = draw()
        assertPanel(bitmap, Color.rgb(248, 249, 255))
        assertToolbarRule(bitmap, Color.rgb(216, 219, 226))
        assertSelectedRowContrasts(bitmap)
    }

    @Test
    fun emptyDarkPanelHasTheSameFillAsItsBar() {
        show(dark = true, empty = true)
        assertPanel(draw(), Color.rgb(11, 14, 17))
    }

    private fun show(
        dark: Boolean,
        empty: Boolean = false,
    ) {
        val channel =
            Conversation(
                id = "channel",
                name = "Selected channel",
                cwd = "/workspace",
                currentSessionId = "session",
                sessionHistory = emptyList(),
                isPromoted = true,
                lastUsedAt = Instant.fromEpochSeconds(0),
            )
        val host =
            HostChannelListEntry(
                host =
                    HostConversationSnapshot(
                        serverId = "host",
                        displayName = "Host",
                        connectionStatus = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected),
                        channels = listOf(channel),
                        chats = emptyList(),
                    ),
                recentChats = emptyList(),
                chatCount = 0,
                channelGroups = groupConversationsByWorkspace("host", listOf(channel)),
                chatGroups = emptyList(),
            )
        val state =
            if (empty) {
                HostChannelListState()
            } else {
                HostChannelListState(hosts = listOf(host), selected = HostConversationTarget("host", "channel"))
            }
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = dark) {
                rootView = LocalView.current
                ChannelListScreen(hostState = state, onEvent = {})
            }
        }
    }

    private fun draw(): Bitmap =
        rule.runOnIdle {
            val view = checkNotNull(rootView)
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                view.draw(Canvas(it))
            }
        }

    private fun assertPanel(
        bitmap: Bitmap,
        expected: Int,
    ) {
        val bounds = rule.onNodeWithTag(CHANNEL_LIST_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val x = bounds.center.x.roundToInt()
        assertEquals("Top bar background", expected, bitmap.getPixel(x, bounds.top.roundToInt() + 2))
        assertEquals("List background", expected, bitmap.getPixel(x, bounds.bottom.roundToInt() - 2))
    }

    private fun assertToolbarRule(
        bitmap: Bitmap,
        expected: Int,
    ) {
        // The toolbar rule spans the panel; text and selected rows cannot fill this entire horizontal band.
        val left = (bitmap.width * 0.1f).roundToInt()
        val right = (bitmap.width * 0.9f).roundToInt()
        val matchingRows =
            (0 until bitmap.height).filter { y ->
                (left..right).all { x -> closeColour(expected, bitmap.getPixel(x, y)) }
            }
        val bands = matchingRows.filterIndexed { index, y -> index == 0 || y > matchingRows[index - 1] + 1 }
        assertEquals("One full-width toolbar rule", 1, bands.size)
        val density = checkNotNull(rootView).resources.displayMetrics.density
        val panel = rule.onNodeWithTag(CHANNEL_LIST_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val host =
            rule
                .onAllNodesWithText("Host")
                .onFirst()
                .fetchSemanticsNode()
                .boundsInRoot
        assertEquals("Divider top", 68f, (bands.first() - panel.top) / density, 0.5f)
        val firstRuleEnd = matchingRows.first { it + 1 !in matchingRows } + 1
        assertEquals("Divider thickness", 1f, (firstRuleEnd - bands.first()) / density, 0.5f)
        assertEquals("Divider to first row including list padding", 24f, (host.top - firstRuleEnd) / density, 0.5f)
        val rulePixels = (0 until bitmap.width).filter { closeColour(expected, bitmap.getPixel(it, bands.first())) }
        assertEquals("Divider left gutter", 20f, (rulePixels.first() - panel.left) / density, 0.5f)
        assertEquals("Divider right gutter", 20f, (panel.right - rulePixels.last() - 1) / density, 0.5f)
        // The runner's temp directory survives Robolectric's per-test sandbox cleanup for visual review.
        val file = File(System.getProperty("java.io.tmpdir"), "sidebar-1189-$expected.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("Sidebar render: ${file.absolutePath}")
    }

    private fun assertSelectedRowContrasts(bitmap: Bitmap) {
        val bounds =
            rule
                .onNodeWithText("Selected channel")
                .assertIsSelected()
                .fetchSemanticsNode()
                .boundsInRoot
        val x = bounds.center.x.roundToInt()
        val selected = bitmap.getPixel(x, bounds.top.roundToInt() + 2)
        val panel = bitmap.getPixel(x, bitmap.height - 2)
        assertTrue("Selected row keeps a visible fill", !closeColour(panel, selected))
    }

    private fun assertSelectedRowFill(
        bitmap: Bitmap,
        expected: Int,
    ) {
        val bounds =
            rule
                .onNodeWithText("Selected channel")
                .assertIsSelected()
                .fetchSemanticsNode()
                .boundsInRoot
        val x = bounds.left.roundToInt() + 2
        val y = bounds.top.roundToInt() + 2
        assertEquals("Selected row uses Schemes/Primary Container", expected, bitmap.getPixel(x, y))
    }

    private fun closeColour(
        expected: Int,
        actual: Int,
    ): Boolean =
        abs(Color.red(expected) - Color.red(actual)) <= 1 &&
            abs(Color.green(expected) - Color.green(actual)) <= 1 &&
            abs(Color.blue(expected) - Color.blue(actual)) <= 1
}
