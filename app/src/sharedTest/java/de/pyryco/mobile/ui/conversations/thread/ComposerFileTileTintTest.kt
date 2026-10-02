package de.pyryco.mobile.ui.conversations.thread

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * #1532: the staged file tile is drawn in primary while connected and in inverse primary otherwise, as 627:1740.
 * Robolectric draws real pixels here, so the tile's tint can be sampled; the device ignores the annotation.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class ComposerFileTileTintTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var primary = Color.Unspecified
    private var inversePrimary = Color.Unspecified
    private var view: View? = null

    @Test
    fun connected_drawsTheTileInPrimary() {
        assertTint(ConnectionState.Connected) { primary }
    }

    @Test
    fun connecting_drawsTheTileInInversePrimary() {
        assertTint(ConnectionState.Connecting) { inversePrimary }
    }

    @Test
    fun reconnecting_drawsTheTileInInversePrimary() {
        assertTint(ConnectionState.Reconnecting(secondsRemaining = 5)) { inversePrimary }
    }

    @Test
    fun offline_drawsTheTileInInversePrimary() {
        assertTint(ConnectionState.Offline) { inversePrimary }
    }

    private fun assertTint(
        connectionState: ConnectionState,
        expected: () -> Color,
    ) {
        composeRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                primary = MaterialTheme.colorScheme.primary
                inversePrimary = MaterialTheme.colorScheme.inversePrimary
                view = LocalView.current
                ThreadScreen(
                    state = ThreadUiState(conversationId = "chat", displayName = "Chat"),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = connectionState,
                    onRetry = {},
                    attachments = listOf(PDF),
                )
            }
        }
        val brightest = brightestBelowRemoveControl()
        val want = expected()
        assertTrue("brightest tile pixel $brightest, expected about $want", brightest.isNear(want))
    }

    /**
     * The tile's brightest pixel below the remove control, which overlaps its top-trailing corner.
     * `captureToImage` never finishes a redraw under the thread's animations, so the view is drawn by hand.
     */
    private fun brightestBelowRemoveControl(): Color {
        val tile = composeRule.onNodeWithContentDescription(PDF.displayName).fetchSemanticsNode().boundsInRoot
        val top = with(composeRule.density) { 20.dp.toPx() }
        return composeRule.runOnIdle {
            val root = checkNotNull(view)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            var brightest = Color.Black
            for (y in (tile.top + top).toInt() until tile.bottom.toInt()) {
                for (x in tile.left.toInt() until tile.right.toInt()) {
                    val pixel = Color(bitmap.getPixel(x, y))
                    if (pixel.sum() > brightest.sum()) brightest = pixel
                }
            }
            brightest
        }
    }

    private fun Color.sum(): Float = red + green + blue

    private fun Color.isNear(other: Color): Boolean =
        abs(red - other.red) < TOLERANCE && abs(green - other.green) < TOLERANCE && abs(blue - other.blue) < TOLERANCE

    private companion object {
        const val TOLERANCE = 0.06f
        val PDF =
            PendingAttachment(
                key = 1,
                uri = "content://com.example.docs/document/report.pdf",
                displayName = "report.pdf",
                mimeType = "application/pdf",
                size = 1L,
            )
    }
}
