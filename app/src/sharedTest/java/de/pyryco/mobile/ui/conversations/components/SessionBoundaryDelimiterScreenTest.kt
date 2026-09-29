package de.pyryco.mobile.ui.conversations.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.MemorySearchAvailability
import de.pyryco.mobile.data.repository.MemorySearchProvider
import de.pyryco.mobile.data.repository.MemorySearchReport
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
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

    private fun setContentWithCapturingUriHandler(
        boundary: ThreadItem.SessionBoundary,
        opened: MutableList<String>,
    ) {
        val capturing =
            object : UriHandler {
                override fun openUri(uri: String) {
                    opened += uri
                }
            }
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                CompositionLocalProvider(LocalUriHandler provides capturing) {
                    SessionBoundaryDelimiter(boundary = boundary, memorySearch = absent)
                }
            }
        }
    }

    private val absent = MemorySearchReport(MemorySearchAvailability.Absent, emptyList())

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
                Color(image.getPixel((24 * density).toInt(), ((label.top.value + label.height.value / 2) * density).toInt())).also {
                    image.recycle()
                }
            }
        assertTrue("rule red differs: $actual vs $expected", kotlin.math.abs(actual.red - expected.red) < 2f / 255f)
        assertTrue("rule green differs: $actual vs $expected", kotlin.math.abs(actual.green - expected.green) < 2f / 255f)
        assertTrue("rule blue differs: $actual vs $expected", kotlin.math.abs(actual.blue - expected.blue) < 2f / 255f)
    }

    @Test
    fun installed_or_unknown_report_keeps_reset_explanation_without_install() {
        val installed =
            MemorySearchReport(
                MemorySearchAvailability.Unavailable,
                listOf(MemorySearchProvider("p", "Knowledge search", true, false, MemorySearchAvailability.Unavailable)),
            )
        val report = androidx.compose.runtime.mutableStateOf(installed)
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SessionBoundaryDelimiter(boundary = clearBoundary(), memorySearch = report.value)
            }
        }

        composeTestRule.onNodeWithText("New session — ", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Claude doesn't remember messages above this line.", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Install").assertDoesNotExist()
        composeTestRule.runOnIdle { report.value = MemorySearchReport.Unknown }
        composeTestRule.onNodeWithText("Install").assertDoesNotExist()
        composeTestRule.runOnIdle { report.value = absent }
        composeTestRule.onNodeWithText("Install").assertIsDisplayed()
    }

    @Test
    fun renders_explanatory_sentence_and_install_button_for_Clear() {
        setContentWithCapturingUriHandler(clearBoundary(), mutableListOf())

        composeTestRule
            .onNode(
                hasText(
                    "Claude doesn't remember messages above this line.",
                    substring = true,
                ),
            ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Search stored knowledge with a memory plugin.", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Install").assertIsDisplayed()
    }

    @Test
    fun a_Codex_conversation_names_Codex_and_keeps_the_install_button() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                SessionBoundaryDelimiter(boundary = clearBoundary(), agent = ConversationAgent.Codex, memorySearch = absent)
            }
        }

        composeTestRule
            .onNode(
                hasText(
                    "Codex doesn't remember messages above this line.",
                    substring = true,
                ),
            ).assertIsDisplayed()
        composeTestRule.onNode(hasText("Claude doesn't remember", substring = true)).assertDoesNotExist()
        composeTestRule.onNodeWithText("Install").assertIsDisplayed()
    }

    @Test
    fun renders_Clear_label_prefix() {
        setContentWithCapturingUriHandler(clearBoundary(), mutableListOf())

        composeTestRule
            .onNode(hasText("New session — ", substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun renders_WorkspaceChange_label_with_cwd_prefix() {
        setContentWithCapturingUriHandler(workspaceChangeBoundary(), mutableListOf())

        composeTestRule
            .onNode(
                hasText(
                    "Workspace changed to ~/Workspace/Projects/KitchenClaw — ",
                    substring = true,
                ),
            ).assertIsDisplayed()
    }

    @Test
    fun renders_IdleEvict_label_prefix() {
        setContentWithCapturingUriHandler(idleEvictBoundary(), mutableListOf())

        composeTestRule
            .onNode(hasText("Idle session ended — ", substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun tapping_Install_opens_memory_plugin_docs_url_exactly_once() {
        val opened = mutableListOf<String>()
        setContentWithCapturingUriHandler(clearBoundary(), opened)

        composeTestRule.onNodeWithText("Install").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(MEMORY_PLUGIN_DOCS_URL), opened)
    }
}
