package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationTreeRowsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // Narrower than any phone, so a long name has to truncate rather than merely fit.
    private val rowWidth = 240.dp

    // Two legs in different states, so a single shared description can never satisfy both assertions.
    private val mixedStatus =
        ConnectionStatus(relay = RelayLinkStatus.Connected, pyrycode = PyrycodeLinkStatus.Handshaking)

    // Far wider than the row, but inside the clamp, so this test measures truncation and not the
    // security clamp that `oversizedName` covers.
    private val longName = "kitchenclaw-refactor".repeat(6)

    // A protocol-non-conformant name, well past MAX_WORKSPACE_LABEL_CHARS.
    private val oversizedName = "x".repeat(4000)

    // One line of titleSmall is 20sp and one of bodySmall 16sp, so two lines of either clear this.
    private val singleLineCeiling = 28.dp

    private fun string(
        resId: Int,
        vararg args: Any,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId, *args)

    private fun setBoundedContent(content: @Composable () -> Unit) {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                Box(modifier = Modifier.width(rowWidth)) { content() }
            }
        }
    }

    private fun assertRightEdgeWithinRow(
        right: Dp,
        what: String,
    ) = assertTrue("$what right edge $right exceeds the row's $rowWidth", right <= rowWidth)

    private fun assertSingleLine(
        height: Dp,
        what: String,
    ) = assertTrue("$what is $height tall, so it wrapped instead of truncating", height <= singleLineCeiling)

    @Test
    fun sectionHeader_rendersTitleAsHeading() {
        composeTestRule.setContent {
            PyrycodeMobileTheme { TreeSectionHeader(title = "Channels", onAddTapped = {}) }
        }

        composeTestRule.onNodeWithText("Channels").assert(isHeading())
    }

    @Test
    fun hostRow_longName_staysOnOneLineAndLeavesTheIndicatorPairInsideTheRow() {
        setBoundedContent {
            TreeHostRow(
                serverId = "pyrybox",
                hostName = longName,
                connectionStatus = mixedStatus,
                expanded = true,
                onToggleExpanded = {},
                onAddTapped = {},
                onAddLongPressed = {},
            )
        }

        val relayDot =
            composeTestRule.onNode(hasContentDescription("Relay: connected"), useUnmergedTree = true)
        relayDot.assertIsDisplayed()
        assertRightEdgeWithinRow(relayDot.getUnclippedBoundsInRoot().right, "relay dot")

        val bounds =
            composeTestRule.onNode(hasText(longName), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertRightEdgeWithinRow(bounds.right, "host name")
        assertSingleLine(bounds.height, "host name")
    }

    @Test
    fun conversationRow_longName_truncatesInsteadOfWrappingOrOverflowing() {
        setBoundedContent {
            TreeConversationRow(conversationName = longName, selected = false, onClick = {})
        }

        val bounds =
            composeTestRule.onNode(hasText(longName), useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertRightEdgeWithinRow(bounds.right, "conversation name")
        assertSingleLine(bounds.height, "conversation name")
    }

    @Test
    fun conversationRow_clampsAnOversizedDaemonAuthoredName() {
        setBoundedContent {
            TreeConversationRow(conversationName = oversizedName, selected = false, onClick = {})
        }

        composeTestRule
            .onNode(hasText(oversizedName.take(MAX_WORKSPACE_LABEL_CHARS)), useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule
            .onAllNodes(hasText(oversizedName), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun hostRow_foldControl_namesTheOppositeStateAndTogglesOnTap() {
        var toggles = 0
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                TreeHostRow(
                    serverId = "pyrybox",
                    hostName = "Pyrybox",
                    connectionStatus = mixedStatus,
                    expanded = true,
                    onToggleExpanded = { toggles++ },
                    onAddTapped = {},
                    onAddLongPressed = {},
                )
            }
        }

        composeTestRule
            .onAllNodes(
                hasContentDescription(string(R.string.cd_tree_row_collapse, "Pyrybox")),
                useUnmergedTree = true,
            ).assertCountEquals(1)
        composeTestRule
            .onAllNodes(
                hasContentDescription(string(R.string.cd_tree_row_expand, "Pyrybox")),
                useUnmergedTree = true,
            ).assertCountEquals(0)

        composeTestRule.onNodeWithText("Pyrybox").performClick()
        assertEquals(1, toggles)
    }

    @Test
    fun hostRow_collapsed_namesTheExpandAction() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                TreeHostRow(
                    serverId = "pyrybox",
                    hostName = "Pyrybox",
                    connectionStatus = mixedStatus,
                    expanded = false,
                    onToggleExpanded = {},
                    onAddTapped = {},
                    onAddLongPressed = {},
                )
            }
        }

        composeTestRule
            .onAllNodes(
                hasContentDescription(string(R.string.cd_tree_row_expand, "Pyrybox")),
                useUnmergedTree = true,
            ).assertCountEquals(1)
    }

    @Test
    fun hostRow_showsEachConnectionLegWithItsOwnDescription() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                TreeHostRow(
                    serverId = "pyrybox",
                    hostName = "Pyrybox",
                    connectionStatus = mixedStatus,
                    expanded = true,
                    onToggleExpanded = {},
                    onAddTapped = {},
                    onAddLongPressed = {},
                )
            }
        }

        composeTestRule
            .onAllNodes(hasContentDescription("Relay: connected"), useUnmergedTree = true)
            .assertCountEquals(1)
        composeTestRule
            .onAllNodes(hasContentDescription("Pyrycode: handshaking"), useUnmergedTree = true)
            .assertCountEquals(1)
    }

    @Test
    fun workspaceRow_foldControl_namesTheOppositeStateAndTogglesOnTap() {
        var toggles = 0
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                TreeWorkspaceRow(
                    workspaceName = "Second Brain",
                    expanded = false,
                    onToggleExpanded = { toggles++ },
                )
            }
        }

        composeTestRule
            .onAllNodes(
                hasContentDescription(string(R.string.cd_tree_row_expand, "Second Brain")),
                useUnmergedTree = true,
            ).assertCountEquals(1)

        composeTestRule.onNodeWithText("Second Brain").performClick()
        assertEquals(1, toggles)
    }

    @Test
    fun conversationRow_announcesSelectionAndOpensOnTap() {
        var opened = 0
        // Stacked in a Column: siblings placed straight into setContent overlap, and the topmost
        // full-width row would swallow the tap aimed at the one beneath it.
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                Column {
                    TreeConversationRow(
                        conversationName = "rocd-thinking",
                        selected = true,
                        onClick = { opened++ },
                    )
                    TreeConversationRow(conversationName = "Gourmet Hub", selected = false, onClick = {})
                }
            }
        }

        composeTestRule.onNodeWithText("rocd-thinking").assertIsSelected()
        composeTestRule.onNodeWithText("Gourmet Hub").assertIsNotSelected()

        composeTestRule.onNodeWithText("rocd-thinking").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun everyTappableRow_meetsTheMinimumTouchTargetHeight() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                Column {
                    TreeHostRow(
                        serverId = "pyrybox",
                        hostName = "Pyrybox",
                        connectionStatus = mixedStatus,
                        expanded = true,
                        onToggleExpanded = {},
                        onAddTapped = {},
                        onAddLongPressed = {},
                    )
                    TreeWorkspaceRow(workspaceName = "Second Brain", expanded = true, onToggleExpanded = {})
                    TreeConversationRow(conversationName = "rocd-thinking", selected = false, onClick = {})
                }
            }
        }

        listOf("Pyrybox", "Second Brain", "rocd-thinking").forEach { label ->
            composeTestRule.onNode(hasText(label)).assertHeightIsAtLeast(48.dp)
        }
    }
}
