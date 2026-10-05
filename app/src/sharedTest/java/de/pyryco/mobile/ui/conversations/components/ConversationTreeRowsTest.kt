package de.pyryco.mobile.ui.conversations.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
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
import de.pyryco.mobile.ui.assertDpEquals
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.theme.success
import de.pyryco.mobile.ui.theme.warning
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
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

    @Test
    fun treeRows_matchFigmaBandHeightsAndKeepActionRegionsSeparate() {
        var folds = 0
        var edits = 0
        var adds = 0
        var opens = 0
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                Column(modifier = Modifier.width(320.dp)) {
                    TreeHostRow(
                        serverId = "pyry",
                        hostName = "Pyry",
                        connectionStatus = mixedStatus,
                        expanded = true,
                        onToggleExpanded = { folds++ },
                        onEditTapped = { edits++ },
                        modifier = Modifier.testTag("geometry-host"),
                    )
                    TreeHostSectionRow(
                        serverId = "pyry",
                        hostName = "Pyry",
                        sectionName = "Channels",
                        expanded = true,
                        onToggleExpanded = { folds++ },
                        onAddTapped = { adds++ },
                        isChat = false,
                        modifier = Modifier.testTag("geometry-section"),
                    )
                    TreeConversationRow(
                        conversationName = "kitchenclaw refactor",
                        selected = false,
                        onClick = { opens++ },
                        modifier = Modifier.testTag("geometry-conversation"),
                    )
                }
            }
        }

        assertDpEquals(28.dp, composeTestRule.onNodeWithTag("geometry-host").getUnclippedBoundsInRoot().height)
        assertDpEquals(28.dp, composeTestRule.onNodeWithTag("geometry-section").getUnclippedBoundsInRoot().height)
        assertDpEquals(24.dp, composeTestRule.onNodeWithTag("geometry-conversation").getUnclippedBoundsInRoot().height)

        composeTestRule.onNodeWithTag(treeHostEditTestTag("pyry")).performClick()
        composeTestRule.onNodeWithTag(treeHostChannelAddTestTag("pyry")).performClick()
        composeTestRule.onNodeWithText("kitchenclaw refactor").performClick()
        assertEquals(0, folds)
        assertEquals(1, edits)
        assertEquals(1, adds)
        assertEquals(1, opens)
    }

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
    fun hostRow_longName_staysOnOneLineAndLeavesTheEditControlInsideTheRow() {
        setBoundedContent {
            TreeHostRow(
                serverId = "pyrybox",
                hostName = longName,
                connectionStatus = mixedStatus,
                expanded = true,
                onToggleExpanded = {},
                onEditTapped = {},
            )
        }

        val edit = composeTestRule.onNodeWithTag(treeHostEditTestTag("pyrybox"))
        edit.assertIsDisplayed()
        assertRightEdgeWithinRow(edit.getUnclippedBoundsInRoot().right, "edit control")

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

    /** How many of the composition's pixels are exactly [color]; drawn by hand, as `captureToImage` never redraws here. */
    private fun countPixels(
        view: View?,
        color: Color,
    ): Int =
        composeTestRule.runOnIdle {
            val root = checkNotNull(view)
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            pixels.count { it == color.toArgb() }
        }

    // #1523: under the static dark palette 15:8's Hover row (selected) is `primary-container`, its darker row
    // (pressed) `on-primary`.
    @Test
    fun conversationRow_staticDark_selectedDrawsPrimaryContainer_andPressedDrawsOnPrimary() {
        val selected = mutableStateOf(true)
        var primaryContainer = Color.Unspecified
        var onPrimary = Color.Unspecified
        var view: View? = null
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                primaryContainer = MaterialTheme.colorScheme.primaryContainer
                onPrimary = MaterialTheme.colorScheme.onPrimary
                view = LocalView.current
                Box(modifier = Modifier.width(rowWidth)) {
                    TreeConversationRow(
                        conversationName = "kitchenclaw refactor",
                        selected = selected.value,
                        onClick = {},
                        modifier = Modifier.testTag("fill-row"),
                    )
                }
            }
        }

        assertTrue("selected draws primary-container", countPixels(view, primaryContainer) > 0)
        assertEquals("selected draws no on-primary", 0, countPixels(view, onPrimary))

        selected.value = false
        assertEquals("unselected draws no primary-container", 0, countPixels(view, primaryContainer))
        assertEquals("unselected draws no on-primary", 0, countPixels(view, onPrimary))

        // Held down, not released: the row stays pressed while it is drawn.
        composeTestRule.onNodeWithTag("fill-row").performTouchInput { down(center) }
        assertTrue("pressed draws on-primary", countPixels(view, onPrimary) > 0)
        assertEquals("pressed draws no primary-container", 0, countPixels(view, primaryContainer))
        composeTestRule.onNodeWithTag("fill-row").performTouchInput { up() }
    }

    // #1451: desktop has no failed state, so no dot state may paint the `error` fill.
    @Test
    fun conversationRow_noAttentionState_drawsTheErrorFill() {
        assertEquals(
            setOf(
                ConversationAttention.WaitingForAnswer,
                ConversationAttention.Running,
                ConversationAttention.Unread,
                ConversationAttention.Idle,
            ),
            ConversationAttention.entries.toSet(),
        )
        val attention = mutableStateOf(ConversationAttention.Idle)
        var error = Color.Unspecified
        var view: View? = null
        setBoundedContent {
            error = MaterialTheme.colorScheme.error
            view = LocalView.current
            TreeConversationRow(
                conversationName = "rocd-thinking",
                selected = false,
                onClick = {},
                attention = attention.value,
            )
        }

        // `captureToImage` never finishes a redraw here, so the composition's view is drawn by hand.
        ConversationAttention.entries.forEach { state ->
            attention.value = state
            assertEquals("$state draws the error fill", 0, countPixels(view, error))
        }
    }

    // #1679: only Idle has a ring; selection does not fill it, and only Running blinks.
    @Config(qualifiers = "xxxhdpi")
    @Test
    fun conversationRow_statusDots_matchRedrawnPaintAndBlink() {
        composeTestRule.mainClock.autoAdvance = false
        val cases = ConversationAttention.entries.map { it to false } + (ConversationAttention.Idle to true)
        val descriptions =
            mapOf(
                ConversationAttention.Idle to string(R.string.cd_conversation_attention_idle),
                ConversationAttention.Running to string(R.string.cd_conversation_attention_running),
                ConversationAttention.Unread to string(R.string.cd_conversation_attention_unread),
                ConversationAttention.WaitingForAnswer to string(R.string.cd_conversation_attention_waiting),
            )
        var primary = Color.Unspecified
        var success = Color.Unspecified
        var warning = Color.Unspecified
        var surface = Color.Unspecified
        var selectedFill = Color.Unspecified
        var view: View? = null
        composeTestRule.setContent {
            PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                primary = MaterialTheme.colorScheme.primary
                success = MaterialTheme.colorScheme.success
                warning = MaterialTheme.colorScheme.warning
                surface = MaterialTheme.colorScheme.surface
                selectedFill = MaterialTheme.colorScheme.primaryContainer
                view = LocalView.current
                // Pin physical pixels on devices too; Robolectric qualifiers are ignored there.
                CompositionLocalProvider(LocalDensity provides Density(4f)) {
                    Column(modifier = Modifier.width(rowWidth).background(surface)) {
                        cases.forEachIndexed { index, (attention, selected) ->
                            TreeConversationRow(
                                conversationName = "row-$index",
                                selected = selected,
                                onClick = {},
                                attention = attention,
                                modifier = Modifier.testTag("dot-row-$index"),
                            )
                        }
                    }
                }
            }
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.mainClock.advanceTimeByFrame()

        fun pixels(index: Int): IntArray {
            val dot =
                composeTestRule.onNode(
                    hasContentDescription(descriptions.getValue(cases[index].first)) and
                        hasAnyAncestor(hasTestTag("dot-row-$index")),
                    useUnmergedTree = true,
                )
            val bounds = dot.getUnclippedBoundsInRoot()
            assertEquals(6.dp, bounds.right - bounds.left)
            assertEquals(6.dp, bounds.bottom - bounds.top)
            val rect = dot.fetchSemanticsNode().boundsInRoot
            return composeTestRule.runOnIdle {
                val root = checkNotNull(view)
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bitmap))
                val width = rect.width.toInt()
                val height = rect.height.toInt()
                IntArray(width * height).also {
                    bitmap.getPixels(it, 0, width, rect.left.toInt(), rect.top.toInt(), width, height)
                    bitmap.recycle()
                }
            }
        }

        fun assertPaint(
            index: Int,
            alpha: Float = 1f,
        ) {
            val (attention, selected) = cases[index]
            val background = if (selected) selectedFill else surface
            val fill =
                when (attention) {
                    ConversationAttention.Idle -> background
                    ConversationAttention.Running -> primary.copy(alpha = alpha).compositeOver(background)
                    ConversationAttention.Unread -> success
                    ConversationAttention.WaitingForAnswer -> warning
                }
            val ring = if (attention == ConversationAttention.Idle) primary.copy(alpha = 0.5f).compositeOver(background) else fill
            val image = pixels(index)
            val width = kotlin.math.sqrt(image.size.toDouble()).toInt()

            fun assertColor(
                label: String,
                expected: Color,
                actual: Int,
            ) {
                listOf(0, 8, 16, 24).forEach { shift ->
                    val delta = kotlin.math.abs(((expected.toArgb() ushr shift) and 255) - ((actual ushr shift) and 255))
                    assertTrue("$attention selected=$selected $label channel $shift differs by $delta", delta <= 3)
                }
            }
            assertColor("centre", fill, image[(width / 2) * width + width / 2])
            assertColor("ring region", ring, image[2 * width + width / 2])
        }

        cases.indices.forEach { assertPaint(it) }
        val first = cases.indices.map { pixels(it) }
        composeTestRule.mainClock.advanceTimeBy(1_000)
        cases.indices.forEach { index ->
            if (cases[index].first == ConversationAttention.Running) {
                assertPaint(index, alpha = 0.3f)
                assertTrue("Running must blink", !first[index].contentEquals(pixels(index)))
            } else {
                assertTrue("${cases[index]} must not blink", first[index].contentEquals(pixels(index)))
            }
        }
        composeTestRule.mainClock.advanceTimeBy(1_000)
        cases.indices.forEach { assertPaint(it) }
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
    fun hostRow_drawsNoConnectionDotInAnyState_andKeepsItsNameChevronAndControls() {
        val relays =
            listOf(
                RelayLinkStatus.Connected,
                RelayLinkStatus.Connecting,
                RelayLinkStatus.Offline,
                RelayLinkStatus.PairingRejected,
                RelayLinkStatus.UpdateRequired("1.4.0"),
            )
        val pyrycodes = listOf(PyrycodeLinkStatus.Connected, PyrycodeLinkStatus.Handshaking, PyrycodeLinkStatus.Down)
        // Every description a leg dot ever carried, including the retired update-required idle ring.
        val legDescriptions =
            relays.map { it.toLegVisual().contentDescription } +
                pyrycodes.map { it.toLegVisual().contentDescription } +
                "Pyrycode: idle"
        val status = mutableStateOf(ConnectionStatus(relays.first(), pyrycodes.first()))
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                TreeHostRow(
                    serverId = "pyrybox",
                    hostName = "Pyrybox",
                    connectionStatus = status.value,
                    expanded = true,
                    onToggleExpanded = {},
                    onEditTapped = {},
                )
            }
        }

        relays.forEach { relay ->
            pyrycodes.forEach { pyrycode ->
                status.value = ConnectionStatus(relay, pyrycode)
                composeTestRule.waitForIdle()
                legDescriptions.forEach { description ->
                    composeTestRule
                        .onAllNodes(hasContentDescription(description, substring = true), useUnmergedTree = true)
                        .assertCountEquals(0)
                }
                composeTestRule.onNodeWithText("Pyrybox").assertIsDisplayed()
                composeTestRule
                    .onAllNodes(
                        hasContentDescription(string(R.string.cd_tree_row_collapse, "Pyrybox")),
                        useUnmergedTree = true,
                    ).assertCountEquals(1)
                composeTestRule
                    .onNodeWithTag(treeHostEditTestTag("pyrybox"))
                    .assert(hasContentDescription(string(R.string.cd_tree_host_edit, "Pyrybox")))
                val plug = relay == RelayLinkStatus.Offline || relay == RelayLinkStatus.PairingRejected
                composeTestRule
                    .onAllNodes(
                        hasTestTag(treeHostReconnectTestTag("pyrybox")) and
                            hasContentDescription(string(R.string.cd_tree_host_reconnect, "Pyrybox")),
                        useUnmergedTree = true,
                    ).assertCountEquals(if (plug) 1 else 0)
                composeTestRule
                    .onAllNodes(
                        hasTestTag(treeHostUpdateTestTag("pyrybox")) and
                            hasContentDescription(string(R.string.cd_tree_host_update, "Pyrybox")),
                        useUnmergedTree = true,
                    ).assertCountEquals(if (relay is RelayLinkStatus.UpdateRequired) 1 else 0)
            }
        }
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
                )
            }
        }

        // Named for the host at its own per-host handle.
        composeTestRule
            .onAllNodes(hasContentDescription(string(R.string.cd_tree_host_edit, "Pyrybox")), useUnmergedTree = true)
            .assertCountEquals(1)
        val control = composeTestRule.onNodeWithTag(treeHostEditTestTag("pyrybox"))
        assertDpEquals(28.dp, control.getUnclippedBoundsInRoot().height)
        control.performClick()

        assertEquals(1, edits)
        // The control is its own merging node inside the row's clickable, so a tap on it must not fold
        // the row.
        assertEquals(0, toggles)
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
            )
        }

        // Truncated with the original length appended, which is what keeps two ids sharing a prefix on
        // separate handles; the raw id never reaches a tag.
        composeTestRule.onNodeWithTag("tree-host-edit:${"x".repeat(256)}~4000").assertExists()
        composeTestRule.onAllNodesWithTag("tree-host-edit:$oversizedName").assertCountEquals(0)
    }

    @Test
    fun everyTappableRow_keepsTheCompactBandWithoutClippingItsText() {
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
                    )
                    TreeWorkspaceRow(workspaceName = "Second Brain", expanded = true, onToggleExpanded = {})
                    TreeConversationRow(conversationName = "rocd-thinking", selected = false, onClick = {})
                }
            }
        }

        assertDpEquals(28.dp, composeTestRule.onNode(hasText("Pyrybox")).getUnclippedBoundsInRoot().height)
        assertDpEquals(28.dp, composeTestRule.onNode(hasText("Second Brain")).getUnclippedBoundsInRoot().height)
        assertDpEquals(24.dp, composeTestRule.onNode(hasText("rocd-thinking")).getUnclippedBoundsInRoot().height)
    }
}
