package de.pyryco.mobile.ui.onboarding

import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScannerDeniedScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun heading_rendersCameraPermissionRequired() {
        showScreen()
        rule.onNodeWithText("Camera permission required").assertExists()
    }

    @Test
    fun openSettings_dispatchesOnlySettings() {
        val actions = showScreen()
        rule.onNodeWithText("Open settings").assertHeightIsEqualTo(48.dp).performClick()
        rule.runOnIdle { assertEquals(listOf("settings"), actions) }
    }

    @Test
    fun pasteCode_hasAccessibleTargetAndDispatchesOnlyPaste() {
        val actions = showScreen()
        rule
            .onNodeWithText("Paste code instead")
            .assertHeightIsEqualTo(40.dp)
            .assertTouchHeightIsEqualTo(48.dp)
            .performClick()
        rule.runOnIdle { assertEquals(listOf("paste"), actions) }
    }

    private fun showScreen(): List<String> {
        val actions = mutableListOf<String>()
        rule.setContent {
            PyrycodeMobileTheme {
                ScannerDeniedScreen(
                    onNavigateBack = { actions.add("back") },
                    onOpenSettings = { actions.add("settings") },
                    onPasteCode = { actions.add("paste") },
                )
            }
        }
        return actions
    }
}
