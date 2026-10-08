package de.pyryco.mobile.ui.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.assertDpEquals
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

        // Each row is checked against the row above it. A size that is not a whole pixel at the emulator's density
        // 2.625 rounds on its own: 20 sp lines, and 1, 2, 4, 12, 20 and 28 dp. The 24 and 16 sp lines and the
        // 48 dp switch target are whole pixels. So each gap allows one device pixel per rounded piece it is built
        // from, counted from SettingsScreen's layout. Robolectric's density 1 rounds nothing.
        val frame =
            listOf(
                // From the 28 dp header row, 24 dp below the sheet top: the header row, the 12 dp gap, the 1 dp
                // divider, the Header area's 4 dp bottom padding (#1588) and the 20 dp gap.
                FrameRow("Notifications", 89f, roundings = 5),
                // The 20 sp Notifications line, the 12 dp column gap and the row's 12 dp inset. The label wraps to
                // two 24 sp lines, as tall as the switch target, so centring it adds nothing.
                FrameRow("Push notifications when claude responds", 133f, roundings = 3),
                // The row's 12 dp bottom inset, the 12 dp column gap and the next row's 12 dp inset.
                FrameRow("Notification sound", 217f, roundings = 3),
                // The 2 dp gap below the 24 sp line.
                FrameRow("Default", 243f, roundings = 1),
                // Frame 726:8150: the sound row ends at 267 and a 12 dp gap follows each block. Here: the row's
                // 12 dp bottom inset and the 12 dp column gap.
                FrameRow("Thread", 283f, roundings = 2),
                // The 20 sp Thread line, the 12 dp column gap, the row's 4 dp inset, and the 24 sp label centred
                // against the 48 dp switch target, which splits 24 dp in half.
                FrameRow("Collapse assistant tool uses", 331f, roundings = 4),
            )
        val tops = frame.map { (rule.onNodeWithText(it.text).getUnclippedBoundsInRoot().top - sheet.top).value }
        // The close glyph fills the header row, so its top is the row's top.
        val header =
            (rule.onNodeWithContentDescription("Close", useUnmergedTree = true).getUnclippedBoundsInRoot().top - sheet.top).value
        val aboveNames = listOf("the header row") + frame.dropLast(1).map { it.text }
        val aboveFrame = listOf(24f) + frame.dropLast(1).map { it.top }
        val aboveMeasured = listOf(header) + tops.dropLast(1)
        frame.forEachIndexed { index, row ->
            assertDpEquals(
                (row.top - aboveFrame[index]).dp,
                (tops[index] - aboveMeasured[index]).dp,
                "${row.text} below ${aboveNames[index]}",
                pixels = row.roundings,
            )
        }
        // The label's centring in the row rounds; the switch's 32 dp track centred in its 48 dp target does not.
        val collapse = rule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))[1]
        assertDpEquals(
            (331f - 327f).dp,
            (tops.last() - (collapse.getUnclippedBoundsInRoot().top - sheet.top).value).dp,
            "Collapse switch above its label",
        )
        val done = rule.onNodeWithText("Done").getUnclippedBoundsInRoot()
        assertEquals("Done height", 40f, (done.bottom - done.top).value, 1f)
        assertEquals("Done bottom gap", 24f, (sheet.bottom - done.bottom).value, 1f)
    }

    /** A text box's top in the frame, and how many separately rounded sizes lie between it and the box above. */
    private data class FrameRow(
        val text: String,
        val top: Float,
        val roundings: Int,
    )
}
