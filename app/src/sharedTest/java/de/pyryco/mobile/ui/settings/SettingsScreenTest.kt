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
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                SettingsScreen(
                    pushNotifications = enabled,
                    onTogglePushNotifications = {
                        enabled = it
                        toggles++
                    },
                    collapseToolUses = true,
                    onToggleCollapseToolUses = {},
                    onDismissRequest = {},
                )
            }
        }

        val toggle = rule.onAllNodes(switch)[0]
        toggle.assertIsOff().performClick().assertIsOn()
        rule.onNodeWithText("Notification sound").assertHasNoClickAction()
        rule.runOnIdle { assertEquals(1, toggles) }
    }

    @Test fun threadSectionShowsCollapseSwitchBelowNotificationSound() {
        show()

        rule.onNodeWithText("Thread").assertIsDisplayed()
        rule.onNodeWithText("Collapse assistant tool uses").assertIsDisplayed()
        rule.onAllNodes(switch).assertCountEquals(2)
        rule.onAllNodes(switch)[1].assertIsDisplayed().assertIsOn()
        val sound = rule.onNodeWithText("Notification sound").getUnclippedBoundsInRoot()
        val heading = rule.onNodeWithText("Thread").getUnclippedBoundsInRoot()
        val label = rule.onNodeWithText("Collapse assistant tool uses").getUnclippedBoundsInRoot()
        assertTrue(heading.top > sound.bottom)
        assertTrue(label.top > heading.bottom)
    }

    @Test fun collapseSwitchTogglesOnlyItsOwnCallback() {
        var collapse by mutableStateOf(true)
        val collapseToggles = mutableListOf<Boolean>()
        var pushToggles = 0
        rule.setContent {
            PyrycodeMobileTheme(darkTheme = true) {
                SettingsScreen(
                    pushNotifications = true,
                    onTogglePushNotifications = { pushToggles++ },
                    collapseToolUses = collapse,
                    onToggleCollapseToolUses = {
                        collapse = it
                        collapseToggles += it
                    },
                    onDismissRequest = {},
                )
            }
        }

        rule
            .onAllNodes(switch)[1]
            .assertIsOn()
            .performClick()
            .assertIsOff()
        rule.runOnIdle {
            assertEquals(listOf(false), collapseToggles)
            assertEquals(0, pushToggles)
        }
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
                SettingsScreen(
                    pushNotifications = true,
                    onTogglePushNotifications = {},
                    collapseToolUses = true,
                    onToggleCollapseToolUses = {},
                    onDismissRequest = onDismiss,
                )
            }
        }
    }

    private val switch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
}
