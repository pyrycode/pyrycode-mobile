package de.pyryco.mobile.ui.components

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class MobileModalTest {
    @get:Rule val rule = createComposeRule()

    private var dismissals = 0
    private var submissions = 0
    private val enabled = mutableStateOf(true)
    private val loading = mutableStateOf(false)
    private val error = mutableStateOf<String?>(null)
    private lateinit var dialogView: View

    private fun show(
        overflow: Boolean = false,
        small: Boolean = false,
    ) {
        rule.setContent {
            PyrycodeMobileTheme {
                if (small) {
                    DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(320.dp, 640.dp))) {
                        ModalContent(overflow, Modifier.size(320.dp, 640.dp))
                    }
                } else {
                    ModalContent(overflow)
                }
            }
        }
    }

    @Composable
    private fun ModalContent(
        overflow: Boolean,
        modifier: Modifier = Modifier,
    ) {
        MobileModal(
            title = "Independent title",
            onDismissRequest = { dismissals++ },
            onSubmit = { submissions++ },
            modifier = modifier,
            submissionEnabled = enabled.value,
            loading = loading.value,
            error = error.value,
        ) {
            dialogView = LocalView.current
            var value by remember { mutableStateOf("") }
            if (overflow) repeat(18) { Text("Item $it") }
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Editable value") },
                modifier = Modifier.fillMaxWidth().testTag("field"),
            )
            Text("Final item")
        }
    }

    @Test
    fun short_content_and_accessible_actions_are_visible() {
        show()
        rule.onNodeWithText("Independent title").assertIsDisplayed()
        rule.onNodeWithTag("field").assertIsDisplayed()
        listOf(rule.onNodeWithContentDescription("Close"), rule.onNodeWithText("Cancel"), rule.onNodeWithText("OK")).forEach {
            it
                .assertIsDisplayed()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }
        val title = rule.onNodeWithText("Independent title").fetchSemanticsNode().boundsInRoot
        val field = rule.onNodeWithTag("field").fetchSemanticsNode().boundsInRoot
        val footer = rule.onNodeWithText("OK").fetchSemanticsNode().boundsInRoot
        assertTrue(field.top > title.bottom)
        assertTrue(field.bottom < footer.top)
        assertTrue(field.center.y > title.bottom + (footer.top - title.bottom) / 3)
    }

    @Test
    fun close_cancel_and_back_only_request_dismissal_once_each() {
        show()
        rule.onNodeWithContentDescription("Close").performClick()
        rule.runOnIdle { assertEquals(1, dismissals) }
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertEquals(2, dismissals) }
        Espresso.pressBack()
        rule.runOnIdle {
            assertEquals(3, dismissals)
            assertEquals(0, submissions)
        }
    }

    @Test
    fun disabled_and_loading_block_submission_but_keep_dismissal_available() {
        enabled.value = false
        show()
        rule.onNodeWithText("OK").assertIsNotEnabled().performClick()
        rule.runOnIdle { enabled.value = true }
        rule.onNodeWithText("OK").performClick()
        rule.onNodeWithText("Independent title").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(1, submissions)
            assertEquals(0, dismissals)
            loading.value = true
        }
        rule.onNodeWithText("OK").assertIsNotEnabled().performClick()
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertIsDisplayed()
        rule.onNodeWithContentDescription("Close").performClick()
        rule.onNodeWithText("Cancel").performClick()
        Espresso.pressBack()
        rule.runOnIdle {
            assertEquals(1, submissions)
            assertEquals(3, dismissals)
        }
    }

    @Test
    fun error_and_loading_recomposition_preserve_entered_value_and_focus() {
        show()
        rule.onNodeWithTag("field").performClick().performTextInput("Keep this value")
        rule.runOnIdle {
            error.value = "Try a different value"
            loading.value = true
        }
        rule.onNodeWithTag("field").assertIsFocused().assertTextContains("Keep this value")
        rule
            .onNodeWithText("Try a different value")
            .performScrollTo()
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun small_window_overflow_scrolls_with_header_and_footer_fixed() {
        show(overflow = true, small = true)
        val header = rule.onNodeWithContentDescription("Close").fetchSemanticsNode().boundsInRoot
        val footer = rule.onNodeWithText("OK").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithText("Final item").assertIsNotDisplayed()
        rule.onNodeWithText("Final item").performScrollTo().assertIsDisplayed()
        assertEquals(header, rule.onNodeWithContentDescription("Close").fetchSemanticsNode().boundsInRoot)
        assertEquals(footer, rule.onNodeWithText("OK").fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun ime_keeps_focused_field_final_item_and_actions_reachable() {
        show(overflow = true, small = true)
        rule.onNodeWithTag("field").performScrollTo().performClick()
        rule.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(dialogView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        rule
            .onNodeWithTag("field")
            .assertIsFocused()
            .assertIsDisplayed()
            .performTextInput("Keyboard entry")
        rule.onNodeWithText("Final item").performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Close").assertIsDisplayed()
        rule.onNodeWithText("OK").assertIsDisplayed()
        val location = IntArray(2)
        rule.runOnIdle { dialogView.getLocationOnScreen(location) }
        val footer = rule.onNodeWithText("OK").fetchSemanticsNode().boundsInRoot
        val ime = ViewCompat.getRootWindowInsets(dialogView)?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        assertTrue(ime > 0)
        assertTrue(location[1] + footer.bottom <= dialogView.resources.displayMetrics.heightPixels - ime + 1)
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun keyboard_navigation_stays_in_dialog_and_restores_launcher_focus() {
        rule.setContent {
            PyrycodeMobileTheme {
                var open by remember { mutableStateOf(false) }
                val launcher = remember { FocusRequester() }
                LaunchedEffect(Unit) { launcher.requestFocus() }
                Column {
                    Button(onClick = { open = true }, modifier = Modifier.focusRequester(launcher)) { Text("Launch") }
                    Button(onClick = {}) { Text("Other background action") }
                }
                if (open) {
                    MobileModal("Keyboard modal", onDismissRequest = { open = false }, onSubmit = { submissions++ }) {
                        Text("Body")
                    }
                }
            }
        }
        rule.onNodeWithText("Launch").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        rule.onNodeWithContentDescription("Close").performSemanticsAction(SemanticsActions.RequestFocus)
        repeat(6) {
            rule.onNode(isFocused() and hasAnyAncestor(isDialog())).performKeyInput { pressKey(Key.Tab) }
            rule.onNode(isFocused() and hasAnyAncestor(isDialog())).assertIsDisplayed()
        }
        rule.onNodeWithText("OK").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithText("OK").performKeyInput { pressKey(Key.Enter) }
        rule.runOnIdle { assertEquals(1, submissions) }
        rule.onNodeWithText("Cancel").performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNodeWithText("Cancel").performKeyInput { pressKey(Key.Enter) }
        rule.onNodeWithText("Launch").assertIsFocused()
    }
}
