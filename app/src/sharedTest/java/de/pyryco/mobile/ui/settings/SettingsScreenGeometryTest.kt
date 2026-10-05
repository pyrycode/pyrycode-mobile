package de.pyryco.mobile.ui.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.pixelDp
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Frame 17:2 text-box offsets from the sheet's top, and Done's 24 dp bottom gap (#1503). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w412dp-h892dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenGeometryTest {
    @get:Rule val rule = createComposeRule()

    @Test fun notificationsRowsAndDoneSitAtFrameOffsets() {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1f)) {
                PyrycodeMobileTheme(darkTheme = true) {
                    SettingsScreen(
                        pushNotifications = true,
                        onTogglePushNotifications = {},
                        collapseToolUses = true,
                        onToggleCollapseToolUses = {},
                        onDismissRequest = {},
                    )
                }
            }
        }
        val sheet = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.PaneTitle)).getUnclippedBoundsInRoot()

        // Each row is checked against the row above it, so the 1 dp slack is per row. At the emulator's density
        // every 12 dp gap and 20 sp line rounds up half a pixel, which adds up down the sheet; a gap between two
        // neighbours carries at most a couple of those. Robolectric's density 1 rounds nothing.
        val step = 1f + 2 * pixelDp()
        val frame =
            listOf(
                "Notifications" to 85f,
                "Push notifications when claude responds" to 129f,
                "Notification sound" to 213f,
                "Default" to 239f,
                // Frame 726:8150: the sound row ends at 267 and a 12 dp gap follows each block; the label is
                // centred against the 32 px switch inside the row's 12 px inset.
                "Thread" to 279f,
                "Collapse assistant tool uses" to 327f,
            )
        val tops = frame.map { (text, _) -> (rule.onNodeWithText(text).getUnclippedBoundsInRoot().top - sheet.top).value }
        assertEquals(frame.first().first, frame.first().second, tops.first(), 1f + 4 * pixelDp())
        for (index in 1 until frame.size) {
            val (text, top) = frame[index]
            assertEquals("$text below ${frame[index - 1].first}", top - frame[index - 1].second, tops[index] - tops[index - 1], step)
        }
        val collapse = rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))[1]
        assertEquals(
            "Collapse switch above its label",
            327f - 323f,
            tops.last() - (collapse.getUnclippedBoundsInRoot().top - sheet.top).value,
            step,
        )
        val done = rule.onNodeWithText("Done").getUnclippedBoundsInRoot()
        assertEquals("Done height", 40f, (done.bottom - done.top).value, 1f)
        assertEquals("Done bottom gap", 24f, (sheet.bottom - done.bottom).value, 1f)
    }
}
