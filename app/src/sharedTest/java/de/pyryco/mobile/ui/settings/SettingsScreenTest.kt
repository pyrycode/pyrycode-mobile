package de.pyryco.mobile.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h892dp")
class SettingsScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun settingsShowsOnlyNotificationsInSharedModal() {
        show()

        listOf("Settings", "Notifications", "Push notifications when claude responds", "Notification sound", "Default", "Done")
            .forEach { rule.onNodeWithText(it).assertIsDisplayed() }
        rule.onNodeWithContentDescription("Close").assertIsDisplayed()
        listOf("Connection", "Appearance", "Defaults for new conversations", "Memory", "Storage", "About")
            .forEach { rule.onAllNodesWithText(it).assertCountEquals(0) }
        rule.onNodeWithText("Notification sound").assertHasNoClickAction()
    }

    @Test fun pushSwitchUpdatesAndSoundDoesNothing() {
        var enabled by mutableStateOf(false)
        var toggles = 0
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                SettingsScreen(enabled, {
                    enabled = it
                    toggles++
                }, {})
            }
        }

        val toggle = rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
        toggle.assertIsOff().performClick().assertIsOn()
        rule.onNodeWithText("Notification sound").assertHasNoClickAction()
        rule.runOnIdle { assertEquals(1, toggles) }
    }

    @Test fun closeDoneAndBackRequestDismissal() {
        var dismissals = 0
        show(onDismiss = { dismissals++ })

        rule.onNodeWithContentDescription("Close").performClick()
        rule.onNodeWithText("Done").performClick()
        Espresso.pressBack()
        rule.runOnIdle { assertEquals(3, dismissals) }
    }

    private fun show(onDismiss: () -> Unit = {}) {
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                SettingsScreen(true, {}, onDismiss)
            }
        }
    }
}
