package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.BackgroundTask
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Dialog windows do not inherit ForcedSize's parent constraints. No editable field is hosted here.
@Config(qualifiers = "w412dp-h892dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(AndroidJUnit4::class)
class BackgroundTaskPanelLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun populatedContent_usesFigmaTextSizes() {
        val task = BackgroundTask("t1", "toolu_1", "local_bash", "go test ./...", null, null, null, false)
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                BackgroundTaskPanel(BackgroundTaskRoster(listOf(task), 0), onDismiss = {})
            }
        }

        assertTextSize("Running · 1", 13)
        assertTextSize("local_bash", 12)
        assertTextSize("Running", 11)
        assertTextSize("go test ./...", 13)
    }

    private fun assertTextSize(
        text: String,
        sizeSp: Int,
    ) {
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule
            .onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(
            "$text font size",
            sizeSp.sp,
            layouts
                .single()
                .layoutInput.style.fontSize,
        )
    }

    @Test
    fun emptyAndNeverReported_useTheReferenceContentOffset() {
        val roster = mutableStateOf<BackgroundTaskRoster?>(BackgroundTaskRoster(emptyList(), 0))
        val size = mutableStateOf(DpSize(412.dp, 892.dp))
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size.value)) {
                PyrycodeMobileTheme {
                    BackgroundTaskPanel(roster = roster.value, onDismiss = {}, modifier = Modifier.requiredSize(size.value))
                }
            }
        }
        assertReferenceOffset("No background tasks")
        rule.runOnIdle { roster.value = null }
        assertReferenceOffset("No background-task report yet")
        rule.runOnIdle { size.value = DpSize(412.dp, 640.dp) }
        assertReferenceOffset("No background-task report yet")
        rule.runOnIdle { roster.value = BackgroundTaskRoster(emptyList(), 0) }
        assertReferenceOffset("No background tasks")
    }

    @Test
    fun emptyReading_remainsReachableAtShortPinnedHeight() {
        assertReachable(height = 360, roster = BackgroundTaskRoster(emptyList(), 0))
    }

    @Test
    fun neverReported_remainsReachableAtShortPinnedHeight() {
        assertReachable(height = 360, roster = null)
    }

    @Test
    fun emptyReading_remainsReachableAtCompactHeight() {
        assertReachable(height = 240, roster = BackgroundTaskRoster(emptyList(), 0))
    }

    @Test
    fun neverReported_remainsReachableAtCompactHeight() {
        assertReachable(height = 240, roster = null)
    }

    private fun assertReachable(
        height: Int,
        roster: BackgroundTaskRoster?,
    ) {
        val open = mutableStateOf(true)
        val size = DpSize(412.dp, height.dp)
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size)) {
                PyrycodeMobileTheme {
                    if (open.value) {
                        BackgroundTaskPanel(roster, onDismiss = { open.value = false }, modifier = Modifier.requiredSize(size))
                    }
                }
            }
        }
        rule.onNodeWithText("Background tasks").assertIsDisplayed()
        rule.onNodeWithContentDescription("Close").assertIsDisplayed()
        val support =
            if (roster == null) {
                "The daemon has not reported on this conversation since the app connected."
            } else {
                "Claude has nothing running in the background for this conversation."
            }
        rule.onNodeWithText(support).performScrollTo().assertIsDisplayed()
        rule
            .onNode(hasText("Close") and hasClickAction())
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        rule.onNodeWithText("Background tasks").assertDoesNotExist()
        rule.runOnIdle { open.value = true }
        rule.onNodeWithContentDescription("Close").assertIsDisplayed().performClick()
        rule.onNodeWithText("Background tasks").assertDoesNotExist()
    }

    private fun assertReferenceOffset(title: String) {
        val reading = rule.onNodeWithText(title).fetchSemanticsNode()
        val viewport =
            generateSequence(reading.parent) { it.parent }
                .first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
        val expected = with(rule.density) { (160.dp + 32.dp + 12.dp).toPx() }
        assertEquals("Reference viewport width", with(rule.density) { (412.dp - 56.dp).toPx() }, viewport.boundsInRoot.width, 1f)
        assertEquals(
            "Title follows the 160dp inset, 32dp ring and 12dp gap",
            expected,
            reading.boundsInRoot.top - viewport.boundsInRoot.top,
            1f,
        )
    }
}
