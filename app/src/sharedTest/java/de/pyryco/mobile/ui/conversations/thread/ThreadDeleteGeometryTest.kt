package de.pyryco.mobile.ui.conversations.thread

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h892dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreadDeleteGeometryTest {
    @get:Rule val rule = createComposeRule()

    private val events = mutableListOf<ThreadEvent>()
    private val name = "kitchenclaw refactor"

    private fun body(name: String) = "This permanently deletes \"$name\" and all its sessions and messages. This can’t be undone."

    private fun show(displayName: String = name) {
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                ThreadScreen(
                    state = ThreadUiState("test", displayName, deleteConfirmVisible = true),
                    onBack = {},
                    onSendMessage = {},
                    connectionState = ConnectionState.Connected,
                    onRetry = {},
                    onOverflowEvent = { events += it },
                )
            }
        }
    }

    private fun Rect.inDp(): Rect {
        val density = rule.density.density
        return Rect(left / density, top / density, right / density, bottom / density)
    }

    private fun textBounds(text: String): Rect =
        rule
            .onNodeWithText(text, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .inDp()

    private fun layout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.runOnIdle {
            val node = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode()
            checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(results)
        }
        return results.single()
    }

    @Test fun default_matches_frame_geometry_and_type_roles() {
        show()
        // Check the real content width before relying on implementation-owned tags.
        assertEquals(268f, textBounds(body(name)).width, 1f)
        val surface =
            rule
                .onNodeWithTag("delete-dialog-surface")
                .fetchSemanticsNode()
                .boundsInRoot
                .inDp()
        val title = textBounds("Delete conversation?")
        val body = textBounds(body(name))
        val cancelLabel = textBounds("Cancel")
        val deleteLabel = textBounds("Delete")
        assertEquals(316f, surface.width, 1f)
        assertEquals(
            "title=$title body=$body cancel=$cancelLabel delete=$deleteLabel bodyLayout=${layout(body(name)).size}",
            220f,
            surface.height,
            1f,
        )
        assertEquals(24f, title.left - surface.left, 1f)
        assertEquals(24f, title.top - surface.top, 1f)
        assertEquals(16f, body.top - title.bottom, 1f)
        assertEquals(24f, cancelLabel.top - 10f - body.bottom, 1f)
        assertEquals(8f, deleteLabel.left - 12f - (cancelLabel.right + 12f), 1f)
        assertEquals(24f, surface.bottom - (deleteLabel.bottom + 10f), 1f)
        assertEquals(3, layout(body(name)).lineCount)
        assertEquals(32f, title.height, 1f)
        assertEquals(60f, body.height, 1f)
        assertEquals(
            24f,
            layout("Delete conversation?")
                .layoutInput.style.fontSize.value,
            0f,
        )
        assertEquals(
            14f,
            layout(body(name))
                .layoutInput.style.fontSize.value,
            0f,
        )
        assertEquals(
            20f,
            layout("Delete")
                .layoutInput.style.lineHeight.value,
            0f,
        )
        rule.onNodeWithText("About").assertDoesNotExist()
        for (label in listOf("Cancel", "Delete")) {
            val result = layout(label)
            assertTrue("$label lineRight=${result.getLineRight(0)} size=${result.size}", result.getLineRight(0) <= result.size.width + 1f)
            assertTrue(result.getLineLeft(0) >= -1f)
            assertTrue(!result.isLineEllipsized(0))
            assertTrue(!result.didOverflowHeight)
            assertEquals(1, result.lineCount)
            val target =
                rule
                    .onNodeWithText(label)
                    .fetchSemanticsNode()
                    .touchBoundsInRoot
                    .inDp()
            assertTrue("$label target must be 48 dp high: $target", target.height >= 48f)
            assertTrue("$label target must be 48 dp wide: $target", target.width >= 48f)
        }
    }

    @Test fun pointer_at_delete_target_extension_confirms_once() {
        show()
        val delete = rule.onNodeWithText("Delete")
        val node = delete.fetchSemanticsNode()
        delete.performTouchInput {
            click(
                androidx.compose.ui.geometry.Offset(
                    center.x,
                    node.touchBoundsInRoot.top - node.boundsInRoot.top + 1f,
                ),
            )
        }
        rule.runOnIdle { assertEquals(listOf(ThreadEvent.DeleteConfirm), events) }
    }

    @Test fun pointer_at_cancel_target_extension_dismisses_once() {
        show()
        val cancel = rule.onNodeWithText("Cancel")
        val node = cancel.fetchSemanticsNode()
        cancel.performTouchInput {
            click(
                androidx.compose.ui.geometry.Offset(
                    center.x,
                    node.touchBoundsInRoot.bottom - node.boundsInRoot.top - 1f,
                ),
            )
        }
        rule.runOnIdle { assertEquals(listOf(ThreadEvent.DeleteDismiss), events) }
    }

    @Test fun pointer_beside_left_surface_edge_dismisses_once() = tapBesideSurface(left = true)

    @Test fun pointer_beside_right_surface_edge_dismisses_once() = tapBesideSurface(left = false)

    private fun tapBesideSurface(left: Boolean) {
        show()
        val node = rule.onNodeWithTag("delete-dialog-surface").fetchSemanticsNode()
        val view = (checkNotNull(node.root) as ViewRootForTest).view
        val location = IntArray(2)
        val decorLocation = IntArray(2)
        rule.runOnUiThread {
            view.getLocationOnScreen(location)
            view.rootView.getLocationOnScreen(decorLocation)
            val surface = node.boundsInRoot.translate(Offset(location[0].toFloat(), location[1].toFloat()))
            val margin = 12f * rule.density.density
            val x = (if (left) surface.left - margin else surface.right + margin) - decorLocation[0]
            val y = surface.center.y - decorLocation[1]
            val time = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, time, action, x, y, 0)
                try {
                    view.rootView.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
        }
        rule.runOnIdle { assertEquals(listOf(ThreadEvent.DeleteDismiss), events) }
        rule.onNodeWithText("About").assertDoesNotExist()
    }

    @Test fun long_name_grows_surface_without_clipping_body() {
        val longName = "A long channel name that wraps over many lines while retaining every word"
        show(longName)
        val surface =
            rule
                .onNodeWithTag("delete-dialog-surface")
                .fetchSemanticsNode()
                .boundsInRoot
                .inDp()
        assertTrue(surface.height > 220f)
        assertTrue(!layout(body(longName)).hasVisualOverflow)
        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onNodeWithText("Delete").assertIsDisplayed()
    }
}
