package de.pyryco.mobile.ui.conversations.thread

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/** #1578: a row above a session boundary draws at the same full opacity as a row below it. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class ThreadRowOpacityTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val timestamp = Instant.parse("2026-10-03T10:00:00Z")
    private var view: View? = null
    private var density = 1f

    private fun assistant(
        id: String,
        sessionId: String,
        content: String,
    ) = ThreadItem.MessageItem(
        Message(id = id, sessionId = sessionId, role = Role.Assistant, content = content, timestamp = timestamp, isStreaming = false),
    )

    @Test
    fun rowsAboveAndBelowABoundary_drawTheSameBubbleFill() {
        composeRule.setContent {
            view = LocalView.current
            density = LocalDensity.current.density
            PyrycodeMobileTheme {
                ThreadScreen(
                    state =
                        ThreadUiState(
                            conversationId = "c1",
                            displayName = "Opacity",
                            isPromoted = true,
                            hasMessages = true,
                            items =
                                listOf(
                                    assistant("m1", "s0", "Older reply"),
                                    ThreadItem.SessionBoundary("s0", "s1", BoundaryReason.Clear, timestamp, null),
                                    assistant("m2", "s1", "Newer reply"),
                                ),
                        ),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                )
            }
        }

        val older = composeRule.onNodeWithText("Older reply").getUnclippedBoundsInRoot()
        val newer = composeRule.onNodeWithText("Newer reply").getUnclippedBoundsInRoot()
        val (olderFill, newerFill) =
            composeRule.runOnIdle {
                val root = checkNotNull(view)
                val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(image))
                (dominantPixel(image, older) to dominantPixel(image, newer)).also { image.recycle() }
            }
        assertEquals(Integer.toHexString(newerFill), Integer.toHexString(olderFill))
    }

    /** The commonest pixel inside [bounds]: the bubble fill behind the glyphs. */
    private fun dominantPixel(
        image: Bitmap,
        bounds: DpRect,
    ): Int {
        val counts = HashMap<Int, Int>()
        for (x in (bounds.left.value * density).toInt() until (bounds.right.value * density).toInt()) {
            for (y in (bounds.top.value * density).toInt() until (bounds.bottom.value * density).toInt()) {
                if (x !in 0 until image.width || y !in 0 until image.height) continue
                val pixel = image.getPixel(x, y)
                counts[pixel] = (counts[pixel] ?: 0) + 1
            }
        }
        return counts.maxBy { it.value }.key
    }
}
