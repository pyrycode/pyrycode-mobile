package de.pyryco.mobile.ui.conversations.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
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
import de.pyryco.mobile.di.ConversationAttention
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

// Robolectric measures text with real fonts here, so the one-line truncation checks hold; the device
// ignores this annotation.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
                onEditTapped = {},
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
    fun conversationRow_eachAttentionState_carriesItsOwnDescription_andFollowsAStateChange() {
        val descriptions =
            mapOf(
                ConversationAttention.WaitingForAnswer to string(R.string.cd_conversation_attention_waiting),
                ConversationAttention.Running to string(R.string.cd_conversation_attention_running),
                ConversationAttention.Failed to string(R.string.cd_conversation_attention_failed),
                ConversationAttention.Unread to string(R.string.cd_conversation_attention_unread),
                ConversationAttention.Idle to string(R.string.cd_conversation_attention_idle),
            )
        assertEquals("every state needs its own description", ConversationAttention.entries.size, descriptions.values.toSet().size)
        val attention = mutableStateOf(ConversationAttention.Idle)
        setBoundedContent {
            TreeConversationRow(
                conversationName = "rocd-thinking",
                selected = false,
                onClick = {},
                attention = attention.value,
            )
        }

        ConversationAttention.entries.forEach { state ->
            attention.value = state
            composeTestRule.waitForIdle()
            descriptions.forEach { (other, description) ->
                composeTestRule
                    .onAllNodes(hasContentDescription(description), useUnmergedTree = true)
                    .assertCountEquals(if (other == state) 1 else 0)
            }
        }
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
                    onEditTapped = {},
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
                    onEditTapped = {},
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
                    onEditTapped = {},
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
    fun hostRow_disconnected_drawsAReconnectControlNamingTheHost_andATapReachesOnlyIt() {
        var reconnects = 0
        var toggles = 0
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                TreeHostRow(
                    serverId = "pyrybox",
                    hostName = "Pyrybox",
                    connectionStatus = ConnectionStatus(RelayLinkStatus.Offline, PyrycodeLinkStatus.Down),
                    expanded = true,
                    onToggleExpanded = { toggles++ },
                    onEditTapped = {},
                    onAddTapped = {},
                    onAddLongPressed = {},
                    onReconnectTapped = { reconnects++ },
                )
            }
        }

        val control = composeTestRule.onNodeWithTag(treeHostReconnectTestTag("pyrybox"))
        control.assert(hasContentDescription(string(R.string.cd_tree_host_reconnect, "Pyrybox")))

        control.performClick()

        assertEquals(1, reconnects)
        assertEquals(0, toggles)
    }

    @Test
    fun hostRow_connectedConnectingOrIdle_drawsNoReconnectControl() {
        val status = mutableStateOf(ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected))
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                TreeHostRow(
                    serverId = "pyrybox",
                    hostName = "Pyrybox",
                    connectionStatus = status.value,
                    expanded = true,
                    onToggleExpanded = {},
                    onEditTapped = {},
                    onAddTapped = {},
                    onAddLongPressed = {},
                )
            }
        }

        listOf(RelayLinkStatus.Connected, RelayLinkStatus.Connecting, RelayLinkStatus.Idle).forEach { relay ->
            status.value = ConnectionStatus(relay, PyrycodeLinkStatus.Down)
            composeTestRule.waitForIdle()
            composeTestRule
                .onAllNodes(hasTestTag(treeHostReconnectTestTag("pyrybox")), useUnmergedTree = true)
                .assertCountEquals(0)
            composeTestRule
                .onAllNodes(hasTestTag(treeHostUpdateTestTag("pyrybox")), useUnmergedTree = true)
                .assertCountEquals(0)
        }
    }

    private fun setUpdateRequiredRow(
        minClientVersion: String?,
        expanded: Boolean = true,
        onToggleExpanded: () -> Unit = {},
        onReconnectTapped: () -> Unit = {},
    ) {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                TreeHostRow(
                    serverId = "pyrybox",
                    hostName = "Pyrybox",
                    connectionStatus =
                        ConnectionStatus(RelayLinkStatus.UpdateRequired(minClientVersion), PyrycodeLinkStatus.Down),
                    expanded = expanded,
                    onToggleExpanded = onToggleExpanded,
                    onEditTapped = {},
                    onAddTapped = {},
                    onAddLongPressed = {},
                    onReconnectTapped = onReconnectTapped,
                )
            }
        }
    }

    @Test
    fun hostRow_updateRequired_drawsAnUpdateControlInsteadOfThePlug_andATapReachesOnlyIt() {
        var taps = 0
        var toggles = 0
        setUpdateRequiredRow("1.4.0", onToggleExpanded = { toggles++ }, onReconnectTapped = { taps++ })

        composeTestRule
            .onAllNodes(hasTestTag(treeHostReconnectTestTag("pyrybox")), useUnmergedTree = true)
            .assertCountEquals(0)
        val control = composeTestRule.onNodeWithTag(treeHostUpdateTestTag("pyrybox"))
        control.assert(hasContentDescription(string(R.string.cd_tree_host_update, "Pyrybox")))

        control.performClick()

        assertEquals(1, taps)
        assertEquals(0, toggles)
    }

    @Test
    fun hostRow_updateRequired_captionNamesTheMinimum_andNoDescriptionCarriesIt() {
        setUpdateRequiredRow("1.4.0")

        composeTestRule
            .onNodeWithText(string(R.string.tree_host_update_required_version, "1.4.0"))
            .assertIsDisplayed()
        composeTestRule
            .onAllNodes(hasContentDescription("1.4.0", substring = true), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun hostRow_updateRequired_withoutAMinimum_captionAsksForAnUpdate() {
        setUpdateRequiredRow(null)

        composeTestRule.onNodeWithText(string(R.string.tree_host_update_required)).assertIsDisplayed()
    }

    @Test
    fun hostRow_updateRequired_keepsItsCaptionWhenFolded() {
        setUpdateRequiredRow("1.4.0", expanded = false)

        composeTestRule
            .onNodeWithText(string(R.string.tree_host_update_required_version, "1.4.0"))
            .assertIsDisplayed()
    }

    @Test
    fun hostRow_updateRequired_drawsTheHostDotIdle_whileAnOfflineHostKeepsItsPyrycodeLeg() {
        val status = mutableStateOf(ConnectionStatus(RelayLinkStatus.UpdateRequired("1.4.0"), PyrycodeLinkStatus.Down))
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                TreeHostRow(
                    serverId = "pyrybox",
                    hostName = "Pyrybox",
                    connectionStatus = status.value,
                    expanded = true,
                    onToggleExpanded = {},
                    onEditTapped = {},
                    onAddTapped = {},
                    onAddLongPressed = {},
                )
            }
        }
        val idle = hasContentDescription(string(R.string.cd_tree_host_leg_idle))
        val down = hasContentDescription(PyrycodeLinkStatus.Down.toLegVisual().contentDescription)

        composeTestRule.onAllNodes(idle, useUnmergedTree = true).assertCountEquals(1)
        composeTestRule.onAllNodes(down, useUnmergedTree = true).assertCountEquals(0)
        composeTestRule
            .onAllNodes(
                hasContentDescription(RelayLinkStatus.UpdateRequired(null).toLegVisual().contentDescription),
                useUnmergedTree = true,
            ).assertCountEquals(1)

        status.value = ConnectionStatus(RelayLinkStatus.Offline, PyrycodeLinkStatus.Down)
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodes(idle, useUnmergedTree = true).assertCountEquals(0)
        composeTestRule.onAllNodes(down, useUnmergedTree = true).assertCountEquals(1)
        composeTestRule
            .onAllNodes(hasText(string(R.string.tree_host_update_required)), useUnmergedTree = true)
            .assertCountEquals(0)
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
    fun hostRow_editControl_isNamedForItsHostAndReportsOnlyItsOwnTap() {
        var edits = 0
        var adds = 0
        var toggles = 0
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                TreeHostRow(
                    serverId = "pyrybox",
                    hostName = "Pyrybox",
                    connectionStatus = mixedStatus,
                    expanded = true,
                    onToggleExpanded = { toggles++ },
                    onEditTapped = { edits++ },
                    onAddTapped = { adds++ },
                    onAddLongPressed = {},
                )
            }
        }

        // Named for the host, at its own per-host handle, and distinguishable from the add control
        // beside it — the whole reason both descriptions carry the host.
        composeTestRule
            .onAllNodes(hasContentDescription(string(R.string.cd_tree_host_edit, "Pyrybox")), useUnmergedTree = true)
            .assertCountEquals(1)
        val control = composeTestRule.onNodeWithTag(treeHostEditTestTag("pyrybox"))
        control.assertHeightIsAtLeast(48.dp)
        control.performClick()

        assertEquals(1, edits)
        // The control is its own merging node inside the row's clickable, so a tap on it must not fold
        // the row or reach the add control.
        assertEquals(0, toggles)
        assertEquals(0, adds)
    }

    @Test
    fun hostRow_editControl_clampsAnOversizedIdIntoItsTestHandle() {
        setBoundedContent {
            TreeHostRow(
                serverId = oversizedName,
                hostName = "Pyrybox",
                connectionStatus = mixedStatus,
                expanded = true,
                onToggleExpanded = {},
                onEditTapped = {},
                onAddTapped = {},
                onAddLongPressed = {},
            )
        }

        // Truncated with the original length appended, which is what keeps two ids sharing a prefix on
        // separate handles; the raw id never reaches a tag.
        composeTestRule.onNodeWithTag("tree-host-edit:${"x".repeat(256)}~4000").assertExists()
        composeTestRule.onAllNodesWithTag("tree-host-edit:$oversizedName").assertCountEquals(0)
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
                        onEditTapped = {},
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
