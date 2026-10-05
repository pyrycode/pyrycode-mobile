package de.pyryco.mobile.ui.conversations.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class SessionBoundaryDelimiterScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val occurredAt = Instant.parse("2026-05-17T14:32:00Z")

    private fun clearBoundary() =
        ThreadItem.SessionBoundary(
            previousSessionId = "s0",
            newSessionId = "s1",
            reason = BoundaryReason.Clear,
            occurredAt = occurredAt,
            workspaceCwd = null,
        )

    private fun workspaceChangeBoundary() =
        ThreadItem.SessionBoundary(
            previousSessionId = "s0",
            newSessionId = "s1",
            reason = BoundaryReason.WorkspaceChange,
            occurredAt = occurredAt,
            workspaceCwd = "~/Workspace/Projects/KitchenClaw",
        )

    private fun idleEvictBoundary() =
        ThreadItem.SessionBoundary(
            previousSessionId = "s0",
            newSessionId = "s1",
            reason = BoundaryReason.IdleEvict,
            occurredAt = occurredAt,
            workspaceCwd = null,
        )

    private fun setContent(boundary: ThreadItem.SessionBoundary) {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SessionBoundaryDelimiter(boundary = boundary)
            }
        }
    }

    @Test
    fun dark_reset_rule_uses_the_reference_inverse_primary_at_sixty_percent() {
        var density = 1f
        var expected = Color.Unspecified
        var view: View? = null
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                density = LocalDensity.current.density
                expected =
                    MaterialTheme.colorScheme.inversePrimary
                        .copy(alpha = 0.6f)
                        .compositeOver(MaterialTheme.colorScheme.surface)
                view = LocalView.current
                Surface {
                    SessionBoundaryDelimiter(boundary = clearBoundary())
                }
            }
        }

        val label = composeTestRule.onNodeWithText("New session — ", substring = true).getUnclippedBoundsInRoot()
        val actual =
            composeTestRule.runOnIdle {
                val root = checkNotNull(view)
                val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(image))
                Color(image.getPixel((44 * density).toInt(), ((label.top.value + label.height.value / 2) * density).toInt())).also {
                    image.recycle()
                }
            }
        assertTrue("rule red differs: $actual vs $expected", kotlin.math.abs(actual.red - expected.red) < 2f / 255f)
        assertTrue("rule green differs: $actual vs $expected", kotlin.math.abs(actual.green - expected.green) < 2f / 255f)
        assertTrue("rule blue differs: $actual vs $expected", kotlin.math.abs(actual.blue - expected.blue) < 2f / 255f)
    }

    // Figma 675:3797 insets the frame 20 px inside the 20 px message gutter: at 412 px the rules span x 40–372.
    // The qualifier gives Robolectric a 412 dp window at density 1. The device ignores it, so the forced size
    // lays the row out 412 dp wide there too, at whatever density fits, and samples scale by that density.
    @OptIn(ExperimentalTestApi::class)
    @Test
    @Config(qualifiers = "w412dp-h892dp-mdpi")
    fun reset_rules_span_the_design_inset_at_412_wide() {
        var rule = Color.Unspecified
        var surface = Color.Unspecified
        var view: View? = null
        var scale = 0f
        composeTestRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(412.dp, 892.dp))) {
                PyrycodeMobileTheme(darkTheme = true) {
                    rule =
                        MaterialTheme.colorScheme.inversePrimary
                            .copy(alpha = 0.6f)
                            .compositeOver(MaterialTheme.colorScheme.surface)
                    surface = MaterialTheme.colorScheme.surface
                    view = LocalView.current
                    scale = LocalDensity.current.density
                    Surface {
                        SessionBoundaryDelimiter(boundary = clearBoundary())
                    }
                }
            }
        }

        val row = composeTestRule.onNodeWithTag(SESSION_BOUNDARY_TEST_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(412f, row.width / scale, 0.5f)
        val label = composeTestRule.onNodeWithText("New session — ", substring = true).fetchSemanticsNode().boundsInRoot
        val y = label.center.y.toInt()
        val samples =
            composeTestRule.runOnIdle {
                val root = checkNotNull(view)
                val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(image))
                listOf(36, 44, 368, 376)
                    .associateWith { Color(image.getPixel((row.left + it * scale).toInt(), y)) }
                    .also { image.recycle() }
            }

        fun close(
            a: Color,
            b: Color,
        ) = kotlin.math.abs(a.red - b.red) < 2f / 255f &&
            kotlin.math.abs(a.green - b.green) < 2f / 255f &&
            kotlin.math.abs(a.blue - b.blue) < 2f / 255f
        assertTrue("x 36 should be surface: ${samples[36]}", close(samples.getValue(36), surface))
        assertTrue("x 44 should be rule: ${samples[44]}", close(samples.getValue(44), rule))
        assertTrue("x 368 should be rule: ${samples[368]}", close(samples.getValue(368), rule))
        assertTrue("x 376 should be surface: ${samples[376]}", close(samples.getValue(376), surface))
    }

    // #1578: every reason draws the rule row alone — no explanation, no memory-search copy, no Install.
    @Test
    fun every_reason_draws_only_the_rule_row() {
        val reason = mutableStateOf(clearBoundary())
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SessionBoundaryDelimiter(boundary = reason.value)
            }
        }

        for (boundary in listOf(clearBoundary(), workspaceChangeBoundary(), idleEvictBoundary())) {
            composeTestRule.runOnIdle { reason.value = boundary }
            composeTestRule.onNodeWithTag(SESSION_BOUNDARY_TEST_TAG).assertIsDisplayed()
            composeTestRule.onAllNodes(hasText("doesn't remember", substring = true)).assertCountEquals(0)
            composeTestRule.onAllNodes(hasText("memory plugin", substring = true)).assertCountEquals(0)
            composeTestRule.onAllNodes(hasText("Install")).assertCountEquals(0)
            composeTestRule.onAllNodes(hasClickAction()).assertCountEquals(0)
        }
    }

    @Test
    fun renders_Clear_label_prefix() {
        setContent(clearBoundary())

        composeTestRule
            .onNode(hasText("New session — ", substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun renders_WorkspaceChange_as_new_session_without_workspace_text() {
        setContent(workspaceChangeBoundary())

        composeTestRule
            .onNode(hasText("New session — ", substring = true))
            .assertIsDisplayed()
        composeTestRule
            .onAllNodes(hasText("Workspace", substring = true))
            .assertCountEquals(0)
    }

    @Test
    fun renders_IdleEvict_label_prefix() {
        setContent(idleEvictBoundary())

        composeTestRule
            .onNode(hasText("Idle session ended — ", substring = true))
            .assertIsDisplayed()
    }
}
