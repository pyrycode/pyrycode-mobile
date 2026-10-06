package de.pyryco.mobile.ui.conversations.list

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.di.ConversationAttention
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.ui.assertDpEquals
import de.pyryco.mobile.ui.components.CHANNEL_NAME_FIELD_TAG
import de.pyryco.mobile.ui.components.CHANNEL_PROMPT_FIELD_TAG
import de.pyryco.mobile.ui.components.EDIT_HOST_NAME_FIELD_TAG
import de.pyryco.mobile.ui.components.EDIT_WORKSPACE_NAME_FIELD_TAG
import de.pyryco.mobile.ui.conversations.components.treeHostChannelAddTestTag
import de.pyryco.mobile.ui.conversations.components.treeHostChatAddTestTag
import de.pyryco.mobile.ui.conversations.components.treeHostEditTestTag
import de.pyryco.mobile.ui.conversations.components.treeHostReconnectTestTag
import de.pyryco.mobile.ui.conversations.components.treeHostUpdateTestTag
import de.pyryco.mobile.ui.host.HostEditorState
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelListScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val events = mutableListOf<ChannelListEvent>()

    private fun conversation(
        id: String,
        name: String?,
        cwd: String,
        promoted: Boolean,
    ): Conversation =
        Conversation(
            id = id,
            name = name,
            cwd = cwd,
            currentSessionId = "$id-s",
            sessionHistory = emptyList(),
            isPromoted = promoted,
            lastUsedAt = Instant.fromEpochSeconds(0),
        )

    private fun entry(
        serverId: String,
        displayName: String?,
        channels: List<Conversation> = emptyList(),
        chats: List<Conversation> = emptyList(),
        relay: RelayLinkStatus = RelayLinkStatus.Connected,
    ): HostChannelListEntry =
        HostChannelListEntry(
            host =
                HostConversationSnapshot(
                    serverId = serverId,
                    displayName = displayName,
                    connectionStatus = ConnectionStatus(relay, PyrycodeLinkStatus.Connected),
                    channels = channels,
                    chats = chats,
                ),
            recentChats = chats.take(3),
            chatCount = chats.size,
            channelGroups = groupConversationsByWorkspace(serverId, channels),
            chatGroups = groupConversationsByWorkspace(serverId, chats),
        )

    /**
     * Renders the stateless screen over a fold-state holder that behaves as the ViewModel does, so a
     * tap on a fold control really does hide or restore the subtree under it.
     */
    private fun setTree(
        vararg hosts: HostChannelListEntry,
        selected: HostConversationTarget? = null,
        hostEditor: HostEditorState? = null,
        createChat: CreateChatState? = null,
        width: Dp? = null,
    ) {
        composeTestRule.setContent {
            if (width == null) {
                TreeContent(hosts, selected, hostEditor, createChat)
            } else {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width, 892.dp))) {
                    TreeContent(hosts, selected, hostEditor, createChat)
                }
            }
        }
    }

    @Composable
    private fun TreeContent(
        hosts: Array<out HostChannelListEntry>,
        selected: HostConversationTarget?,
        hostEditor: HostEditorState?,
        createChat: CreateChatState?,
    ) {
        var hostState by remember {
            mutableStateOf(
                HostChannelListState(
                    hosts.toList(),
                    selected = selected,
                    hostEditor = hostEditor,
                    createChat = createChat,
                ),
            )
        }
        PyrycodeMobileTheme {
            ChannelListScreen(
                hostState = hostState,
                onEvent = { event ->
                    events += event
                    if (event is ChannelListEvent.TreeFoldToggled) {
                        val collapsed = hostState.collapsed
                        hostState =
                            hostState.copy(
                                collapsed =
                                    if (event.key in collapsed) collapsed - event.key else collapsed + event.key,
                            )
                    }
                    // An opened row is the view model's last-opened target, so it is selected on Back (#1523).
                    if (event is ChannelListEvent.TreeRowTapped) hostState = hostState.copy(selected = event.target)
                    // Mirrors the view model's two confirmation transitions (#745), so one composition
                    // can walk request → confirmation → decline the way the screen really does;
                    // `createComposeRule` permits only one `setContent` per test.
                    hostState.hostEditor?.let { editor ->
                        when (event) {
                            ChannelListEvent.HostUnpairRequested ->
                                hostState = hostState.copy(hostEditor = editor.copy(confirmingUnpair = true))

                            ChannelListEvent.HostUnpairDeclined ->
                                hostState = hostState.copy(hostEditor = editor.copy(confirmingUnpair = false))

                            else -> Unit
                        }
                    }
                },
            )
        }
    }

    private fun string(
        resId: Int,
        vararg formatArgs: Any,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId, *formatArgs)

    /**
     * `15:8` stacks host containers 16 dp apart and draws collapsed hosts and sections with an expand
     * affordance and no children (#1521). The first host's Chats section and the whole second host are
     * folded through their own controls, so the asserted state is the one a tap leaves.
     */
    @Test
    fun collapsedRowsDrawNoChildren_andHostContainersKeepTheSixteenDpFigmaGap() {
        setTree(
            entry(
                serverId = "pyry",
                displayName = "Pyry",
                channels = listOf(conversation("c1", "Pyry channel", "/w", true)),
                chats = listOf(conversation("h1", "Pyry chat", "/w", false)),
            ),
            entry(
                serverId = "elli",
                displayName = "Elli",
                channels = listOf(conversation("c2", "Elli channel", "/w", true)),
                chats = listOf(conversation("h2", "Elli chat", "/w", false)),
            ),
        )
        composeTestRule.onNode(hasText("Pyry chat")).assertExists()
        composeTestRule.onNode(hasText("Elli channel")).assertExists()

        composeTestRule.onNode(hasContentDescription(string(R.string.cd_tree_row_collapse, "Chats on Pyry"))).performClick()
        composeTestRule.onNode(hasContentDescription(string(R.string.cd_tree_row_collapse, "Elli"))).performClick()
        composeTestRule.onNode(hasText("Pyry chat")).assertDoesNotExist()

        composeTestRule.onNode(hasContentDescription(string(R.string.cd_tree_row_expand, "Chats on Pyry"))).assertExists()
        composeTestRule.onNode(hasContentDescription(string(R.string.cd_tree_row_expand, "Elli"))).assertExists()
        listOf("Elli channel", "Elli chat").forEach { composeTestRule.onNode(hasText(it)).assertDoesNotExist() }
        composeTestRule.onNode(hasText("Pyry channel")).assertExists()

        val firstHostLastRow = composeTestRule.onNode(hasText("Chats")).getUnclippedBoundsInRoot()
        val secondHost = composeTestRule.onNode(hasText("Elli")).getUnclippedBoundsInRoot()
        assertEquals(16.dp, secondHost.top - firstHostLastRow.bottom)
    }

    @Test
    fun siblingConversationRowsKeepTheFourDpFigmaGap() {
        setTree(
            entry(
                serverId = "pyry",
                displayName = "Pyry",
                channels =
                    listOf(
                        conversation("one", "First channel", "/w", true),
                        conversation("two", "Second channel", "/w", true),
                    ),
            ),
        )

        // The rows' own bands, not their text: a text line is shorter than its 24 dp band, so the gap between two
        // names also counts the band padding around each, which real fonts make visible on the device.
        val first = composeTestRule.onNode(hasText("First channel")).getUnclippedBoundsInRoot()
        val second = composeTestRule.onNode(hasText("Second channel")).getUnclippedBoundsInRoot()
        assertDpEquals(4.dp, second.top - first.bottom)
    }

    @Test
    fun trailingControlsFormOneColumnAtPixel2Width() = assertTrailingControlsFormOneColumn(412.dp)

    @Test
    fun trailingControlsFormOneColumnAtNarrowWidth() = assertTrailingControlsFormOneColumn(320.dp)

    // #1563: 15:8 draws no pen on any conversation row; channels and chats are edited from their thread.
    @Test
    fun noConversationRowDrawsAPen_andRowsStillOpen() {
        val state =
            mutableStateOf(
                HostChannelListState(
                    listOf(
                        entry(
                            serverId = "pyrybox",
                            displayName = "Pyrybox",
                            channels =
                                listOf(
                                    conversation("c1", "alpha channel", "/w", true),
                                    conversation("c2", "bravo channel", "/w", true),
                                ),
                            chats = listOf(conversation("d1", "charlie chat", "/w", false)),
                        ),
                    ),
                    selected = HostConversationTarget("pyrybox", "c1"),
                ),
            )
        composeTestRule.setContent {
            PyrycodeMobileTheme { ChannelListScreen(state.value, onEvent = { events += it }) }
        }

        fun assertNoRowPens() {
            // Keep checking the retired controls independently of their removed string resources.
            // Prefix matching also catches a pen on a newly named conversation.
            val conversationPen =
                SemanticsMatcher("conversation edit control") { node ->
                    node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any {
                        it.startsWith("Edit channel ") || it.startsWith("Edit chat ")
                    }
                }
            composeTestRule.onAllNodes(conversationPen, useUnmergedTree = true).assertCountEquals(0)
            composeTestRule.onAllNodes(hasTestTag(TREE_CHANNEL_ROW_TEST_TAG)).assertCountEquals(2)
            composeTestRule.onAllNodes(hasTestTag(TREE_CHAT_ROW_TEST_TAG)).assertCountEquals(1)
            composeTestRule.onNodeWithTag(treeHostEditTestTag("pyrybox")).assertExists()
        }

        assertNoRowPens()
        composeTestRule.onNode(hasText("alpha channel")).performClick()

        state.value = state.value.copy(selected = HostConversationTarget("pyrybox", "d1"))
        assertNoRowPens()
        composeTestRule.onNode(hasText("charlie chat")).performClick()

        state.value = state.value.copy(selected = null)
        assertNoRowPens()
        composeTestRule.onNode(hasText("bravo channel")).performClick()
        assertEquals(
            listOf(
                ChannelListEvent.TreeRowTapped(HostConversationTarget("pyrybox", "c1")),
                ChannelListEvent.TreeRowTapped(HostConversationTarget("pyrybox", "d1")),
                ChannelListEvent.TreeRowTapped(HostConversationTarget("pyrybox", "c2")),
            ),
            events,
        )
    }

    // Figma (#1334): conversation rows are inset 12dp on the left only and end on the host row's right edge.
    private fun assertTrailingControlsFormOneColumn(width: Dp) {
        setTree(
            entry(
                serverId = "pyry",
                displayName = "Pyry",
                channels = listOf(conversation("c1", "alpha channel", "/w", true)),
                chats = listOf(conversation("d1", "bravo chat", "/w", false)),
            ),
            width = width,
        )

        val root = composeTestRule.onNodeWithTag(CHANNEL_LIST_TEST_TAG).getUnclippedBoundsInRoot()
        // ForcedSize rescales the density to fit, so the measured width rounds to within a pixel.
        assertEquals(width.value, (root.right - root.left).value, 0.5f)

        fun bounds(matcher: SemanticsMatcher) = composeTestRule.onNode(matcher).getUnclippedBoundsInRoot()
        val hostPen = bounds(hasTestTag(treeHostEditTestTag("pyry")))
        val hostFold = bounds(hasContentDescription(string(R.string.cd_tree_row_collapse, "Pyry")))
        listOf(TREE_CHANNEL_ROW_TEST_TAG, TREE_CHAT_ROW_TEST_TAG).forEach { tag ->
            assertEquals("$tag left inset at $width", 12f, (bounds(hasTestTag(tag)).left - hostFold.left).value, 0.5f)
        }
        // Figma 15:8 moved the section plus flush with the host pencil (#1203's 10dp trailing inset is
        // gone): every pen and plus glyph in the current frame shares one right edge, 2px from the row's
        // content edge, so the two controls' right edges now coincide.
        listOf(treeHostChannelAddTestTag("pyry"), treeHostChatAddTestTag("pyry")).forEach { tag ->
            assertEquals("$tag right edge at $width", 0f, (hostPen.right - bounds(hasTestTag(tag)).right).value, 0.5f)
        }
    }

    @Test
    fun emptySecondHostHasItsOwnChatsCreateControlWithoutFolding() {
        setTree(
            entry("first", "First", chats = listOf(conversation("same", "First chat", "/a", false))),
            entry("second", "Second"),
        )

        val create = composeTestRule.onNodeWithTag(treeHostChatAddTestTag("second"))
        create
            .assertExists()
            .assertHeightIsAtLeast(28.dp)
            .performClick()
        assertEquals(listOf(ChannelListEvent.TreeHostChatAddTapped("second")), events)
        composeTestRule.onNode(hasContentDescription(string(R.string.cd_tree_row_collapse, "Chats on Second"))).assertExists()
        composeTestRule.onNode(hasText(string(R.string.create_chat_title))).assertDoesNotExist()
    }

    @Test
    fun aDisconnectedHostDrawsNoSectionPlusAndTheyReturnOnReconnect() {
        val first =
            entry(
                "first",
                "First",
                channels = listOf(conversation("c1", "first channel", "/a", true)),
                chats = listOf(conversation("h1", "first chat", "/a", false)),
            )
        val second =
            entry(
                "second",
                "Second",
                channels = listOf(conversation("c2", "second channel", "/b", true)),
                chats = listOf(conversation("h2", "second chat", "/b", false)),
            )

        fun HostChannelListEntry.withRelay(relay: RelayLinkStatus) =
            copy(host = host.copy(connectionStatus = ConnectionStatus(relay, PyrycodeLinkStatus.Connected)))
        var state by mutableStateOf(HostChannelListState(listOf(first, second)))
        composeTestRule.setContent {
            PyrycodeMobileTheme { ChannelListScreen(hostState = state, onEvent = { events += it }) }
        }
        val firstControls =
            listOf(
                hasTestTag(treeHostChannelAddTestTag("first")),
                hasTestTag(treeHostChatAddTestTag("first")),
            )
        val secondControls =
            listOf(
                hasTestTag(treeHostChannelAddTestTag("second")),
                hasTestTag(treeHostChatAddTestTag("second")),
            )
        val list = composeTestRule.onNode(hasScrollAction())

        fun assertDrawn(
            matchers: List<SemanticsMatcher>,
            count: Int,
        ) = matchers.forEach { matcher ->
            if (count > 0) list.performScrollToNode(matcher)
            composeTestRule.onAllNodes(matcher).assertCountEquals(count)
        }
        assertDrawn(firstControls, 1)
        assertDrawn(secondControls, 1)

        state = state.copy(hosts = listOf(first.withRelay(RelayLinkStatus.Offline), second))
        composeTestRule.waitForIdle()
        assertDrawn(firstControls, 0)
        assertDrawn(secondControls, 1)
        // The host row keeps Edit host and its reconnect control; folding and opening rows still work.
        list.performScrollToNode(hasTestTag(treeHostEditTestTag("first")))
        composeTestRule.onNodeWithTag(treeHostEditTestTag("first")).performClick()
        composeTestRule.onAllNodes(hasTestTag(treeHostReconnectTestTag("first"))).onFirst().performClick()
        list.performScrollToNode(hasText("first chat"))
        composeTestRule.onNode(hasText("first chat")).performClick()
        val firstChats = hasContentDescription(string(R.string.cd_tree_row_collapse, "Chats on First"))
        composeTestRule.onNode(firstChats).performClick()
        assertEquals(
            listOf(
                ChannelListEvent.TreeHostEditTapped("first"),
                ChannelListEvent.TreeHostReconnectTapped("first"),
                ChannelListEvent.TreeRowTapped(HostConversationTarget("first", "h1")),
                ChannelListEvent.TreeFoldToggled(TreeFoldKey(ConversationTreeSection.Chats, "first")),
            ),
            events,
        )

        state = state.copy(hosts = listOf(first, second))
        composeTestRule.waitForIdle()
        assertDrawn(firstControls, 1)
        assertDrawn(secondControls, 1)
    }

    @Test
    fun failedDirectCreateShowsGenericErrorWithoutOpeningDialog() {
        setTree(
            entry("first", "First", relay = RelayLinkStatus.Offline),
            createChat = CreateChatState("first", requestId = 1, saving = false, failed = true),
        )

        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasText(string(R.string.create_chat_failed))).assertExists()
        composeTestRule.onNode(hasText(string(R.string.create_chat_title))).assertDoesNotExist()
    }

    @Test
    fun hostsContainOrderedChannelsAndChatsWithAnEmptyChannelsAddAction() {
        setTree(
            entry(
                serverId = "first",
                displayName = "First",
                channels =
                    listOf(
                        conversation("one", "first channel", "/other", true),
                        conversation("two", "second channel", "/earlier", true),
                    ),
                chats = listOf(conversation("chat", "first chat", "/later", false)),
            ),
            entry(serverId = "second", displayName = "Second"),
        )

        composeTestRule.onAllNodes(hasText("First")).assertCountEquals(1)
        composeTestRule.onAllNodes(hasText("Second")).assertCountEquals(1)
        composeTestRule.onAllNodes(hasText(string(R.string.channels_section_header))).assertCountEquals(2)
        composeTestRule.onAllNodes(hasText(string(R.string.chats_section_header))).assertCountEquals(2)
        composeTestRule.onAllNodes(hasText("other")).assertCountEquals(0)
        composeTestRule.onAllNodes(hasText("earlier")).assertCountEquals(0)
        assertTrue(
            composeTestRule.onNode(hasText("first channel")).getUnclippedBoundsInRoot().top <
                composeTestRule.onNode(hasText("second channel")).getUnclippedBoundsInRoot().top,
        )
        composeTestRule.onNode(hasContentDescription(string(R.string.cd_tree_host_new_channel, "Second"))).performClick()
        assertEquals(ChannelListEvent.TreeHostChannelAddTapped("second"), events.last())
    }

    @Test
    fun multiHostTreeKeepsTargetsThroughFoldsEditingPromotionReconnectAndTheFinalRow() {
        val many = (1..20).map { conversation("a$it", "A channel $it", "/a", true) }
        val state =
            mutableStateOf(
                HostChannelListState(
                    hosts =
                        listOf(
                            entry("first", "First", channels = many + conversation("same", "Selected channel", "/a", true)),
                            entry(
                                "second",
                                "Second",
                                channels = listOf(conversation("same", "Second channel", "/b", true)),
                                chats = listOf(conversation("last", "Final chat", "/b", false)),
                                relay = RelayLinkStatus.Offline,
                            ),
                        ),
                    selected = HostConversationTarget("first", "same"),
                ),
            )
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ChannelListScreen(state.value, onEvent = { event ->
                    events += event
                    if (event is ChannelListEvent.TreeFoldToggled) {
                        val collapsed = state.value.collapsed
                        state.value =
                            state.value.copy(
                                collapsed = if (event.key in collapsed) collapsed - event.key else collapsed + event.key,
                            )
                    }
                })
            }
        }

        val list = composeTestRule.onNode(hasScrollAction())
        list.performScrollToNode(hasText("First"))
        composeTestRule.onNode(hasText("First")).performClick()
        composeTestRule.onAllNodes(hasText("Selected channel")).assertCountEquals(0)
        list.performScrollToNode(hasText("Second"))
        composeTestRule.onNode(hasText("Second")).assertExists()
        assertTrue(
            composeTestRule.onNode(hasText("Second")).getUnclippedBoundsInRoot().right <=
                composeTestRule.onNodeWithTag(treeHostReconnectTestTag("second")).getUnclippedBoundsInRoot().left,
        )
        composeTestRule.onNodeWithTag(treeHostEditTestTag("second")).assertExists()
        list.performScrollToNode(hasText("First"))
        composeTestRule.onNode(hasText("First")).performClick()
        list.performScrollToNode(hasText("Selected channel"))
        composeTestRule.onNode(hasText("Selected channel")).assertIsSelected()

        val firstChannels = hasContentDescription(string(R.string.cd_tree_row_collapse, "Channels on First"))
        list.performScrollToNode(firstChannels)
        composeTestRule.onNode(firstChannels).performClick()
        composeTestRule.onAllNodes(hasText("Selected channel")).assertCountEquals(0)
        val firstChats = hasContentDescription(string(R.string.cd_tree_row_collapse, "Chats on First"))
        composeTestRule.onNode(firstChats).assertExists()
        composeTestRule.onNode(hasContentDescription(string(R.string.cd_tree_row_expand, "Channels on First"))).performClick()
        list.performScrollToNode(hasText("Selected channel"))
        composeTestRule.onNode(hasText("Selected channel")).assertIsSelected()

        val secondChannel = hasText("Second channel")
        list.performScrollToNode(secondChannel)
        composeTestRule.onNode(secondChannel).assertIsNotSelected()

        val finalChat = hasText("Final chat")
        list.performScrollToNode(finalChat)
        composeTestRule.onNode(finalChat).assertIsDisplayed().performClick()
        // The thread destination carries this chat's own host into its promotion action.
        assertEquals(ChannelListEvent.TreeRowTapped(HostConversationTarget("second", "last")), events.last())
        list.performScrollToNode(hasTestTag(treeHostReconnectTestTag("second")))
        composeTestRule.onNodeWithTag(treeHostReconnectTestTag("second")).performClick()
        assertEquals(ChannelListEvent.TreeHostReconnectTapped("second"), events.last())

        // A daemon promotion snapshot moves the same id to Channels on the chat's own host.
        composeTestRule.runOnIdle {
            val second = state.value.hosts[1]
            val promoted =
                second.host.chats
                    .single()
                    .copy(name = "Promoted channel", isPromoted = true)
            state.value =
                state.value.copy(
                    hosts =
                        listOf(
                            state.value.hosts[0],
                            second.copy(host = second.host.copy(channels = second.host.channels + promoted, chats = emptyList())),
                        ),
                )
        }
        val promotedRow = hasTestTag(TREE_CHANNEL_ROW_TEST_TAG) and hasText("Promoted channel")
        list.performScrollToNode(promotedRow)
        composeTestRule.onNode(promotedRow).assertIsDisplayed().performClick()
        assertEquals(ChannelListEvent.TreeRowTapped(HostConversationTarget("second", "last")), events.last())
        composeTestRule.onAllNodes(hasTestTag(TREE_CHAT_ROW_TEST_TAG) and hasText("Promoted channel")).assertCountEquals(0)
    }

    @Test
    fun tree_rendersBothSectionsFromRealHostData_expandedOnFirstShow() {
        setTree(
            entry(
                serverId = "pyrybox",
                displayName = "Pyrybox",
                channels = listOf(conversation("c1", "alpha channel", "/w/one", true)),
                chats = listOf(conversation("d1", "bravo chat", "/w/two", false)),
            ),
        )

        composeTestRule.onNode(hasText(string(R.string.channels_section_header))).assertExists()
        composeTestRule.onNode(hasText(string(R.string.chats_section_header))).assertExists()
        composeTestRule.onAllNodes(hasText("Pyrybox")).assertCountEquals(1)
        composeTestRule.onNode(hasText("one")).assertDoesNotExist()
        composeTestRule.onNode(hasText("two")).assertDoesNotExist()
        composeTestRule.onNode(hasText("alpha channel")).assertIsDisplayed()
        composeTestRule.onNode(hasText("bravo chat")).assertIsDisplayed()
    }

    @Test
    fun foldingAHostHidesItsSections_andFoldingASectionHidesOnlyItsOwnConversations() {
        setTree(
            entry(
                serverId = "pyrybox",
                displayName = "Pyrybox",
                channels = listOf(conversation("c1", "alpha channel", "/w/one", true)),
                chats = listOf(conversation("d1", "bravo chat", "/w/two", false)),
            ),
            entry(
                serverId = "macbook",
                displayName = "Macbook",
                channels = listOf(conversation("c3", "charlie channel", "/w/three", true)),
            ),
        )

        // Fold the first host: its sections and conversations go, the other host's stay.
        composeTestRule.onNode(hasText("Pyrybox")).performClick()
        composeTestRule.onNode(hasContentDescription(string(R.string.cd_tree_row_collapse, "Channels on Pyrybox"))).assertDoesNotExist()
        composeTestRule.onNode(hasText("alpha channel")).assertDoesNotExist()
        composeTestRule.onNode(hasText("bravo chat")).assertDoesNotExist()
        composeTestRule.onNode(hasText("charlie channel")).assertExists()

        // Unfold it and fold Channels: Chats and the other host remain.
        composeTestRule.onNode(hasText("Pyrybox")).performClick()
        composeTestRule.onNode(hasText("alpha channel")).assertExists()
        composeTestRule.onNode(hasContentDescription(string(R.string.cd_tree_row_collapse, "Channels on Pyrybox"))).performClick()
        composeTestRule.onNode(hasText("alpha channel")).assertDoesNotExist()
        composeTestRule.onNode(hasText("bravo chat")).assertExists()
        composeTestRule.onNode(hasText("charlie channel")).assertExists()
    }

    @Test
    fun conversationRow_emitsATargetForItsOwnHost_notTheSelectedOne() {
        setTree(
            entry(
                serverId = "pyrybox",
                displayName = "Pyrybox",
                channels = listOf(conversation("shared-id", "alpha channel", "/w/one", true)),
            ),
            entry(
                serverId = "macbook",
                displayName = "Macbook",
                channels = listOf(conversation("shared-id", "bravo channel", "/w/one", true)),
            ),
        )

        composeTestRule.onNode(hasText("bravo channel")).performClick()

        // Same conversation id on both hosts: only the row's own serverId distinguishes them.
        assertEquals(listOf(ChannelListEvent.TreeRowTapped(HostConversationTarget("macbook", "shared-id"))), events)
    }

    @Test
    fun selection_highlightsOnlyTheLastOpenedRowAcrossBothSections() {
        setTree(
            entry(
                serverId = "pyrybox",
                displayName = "Pyrybox",
                channels = listOf(conversation("c1", "alpha channel", "/w/one", true)),
                chats = listOf(conversation("d1", "bravo chat", "/w/one", false)),
            ),
            entry(
                serverId = "macbook",
                displayName = "Macbook",
                channels = listOf(conversation("c1", "charlie channel", "/w/one", true)),
            ),
            selected = HostConversationTarget("pyrybox", "c1"),
        )

        composeTestRule.onNode(hasText("alpha channel")).assertIsSelected()
        composeTestRule.onNode(hasText("bravo chat")).assertIsNotSelected()
        // Same conversation id, different host — the highlight must not follow the id alone.
        composeTestRule.onNode(hasText("charlie channel")).assertIsNotSelected()
    }

    @Test
    fun conversationRows_carryTheirSectionTier() {
        setTree(
            entry(
                serverId = "pyrybox",
                displayName = "Pyrybox",
                channels = listOf(conversation("c1", "alpha channel", "/w/one", true)),
                chats = listOf(conversation("d1", "bravo chat", "/w/one", false)),
            ),
        )

        composeTestRule.onNode(hasTestTag(TREE_CHANNEL_ROW_TEST_TAG) and hasText("alpha channel")).assertExists()
        composeTestRule.onAllNodes(hasTestTag(TREE_CHAT_ROW_TEST_TAG) and hasText("alpha channel")).assertCountEquals(0)
        composeTestRule.onNode(hasTestTag(TREE_CHAT_ROW_TEST_TAG) and hasText("bravo chat")).assertExists()
    }

    @Test
    fun conversationRows_drawTheirOwnAttentionStateFromTheHostEntry() {
        setTree(
            entry(
                serverId = "pyrybox",
                displayName = "Pyrybox",
                channels =
                    listOf(
                        conversation("c1", "alpha channel", "/w/one", true),
                        conversation("c2", "charlie channel", "/w/one", true),
                    ),
            ).copy(attention = mapOf("c1" to ConversationAttention.Running)),
        )

        val running = string(R.string.cd_conversation_attention_running)
        val idle = string(R.string.cd_conversation_attention_idle)
        composeTestRule
            .onNode(hasText("alpha channel") and hasContentDescription(running))
            .assertExists()
        composeTestRule
            .onNode(hasText("charlie channel") and hasContentDescription(idle))
            .assertExists()
    }

    @Test
    fun tallTree_reachesItsLastRowInOneScrollContainer() {
        val many = (1..60).map { conversation("c$it", "channel $it", "/w/one", true) }
        setTree(
            entry(
                serverId = "pyrybox",
                displayName = "Pyrybox",
                channels = many,
                chats = listOf(conversation("last", "last chat", "/w/two", false)),
            ),
        )
        val before = toolbarBounds()

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("channel 60"))
        composeTestRule.onNode(hasText("channel 60")).assertIsDisplayed()
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("last chat"))
        composeTestRule.onNode(hasText("last chat")).assertIsDisplayed()
        assertBarDrawn()
        assertEquals(before, toolbarBounds())
        clickToolbar()
    }

    @Test
    fun namelessHostAndConversation_renderTheirFallbackLabels() {
        setTree(
            entry(
                serverId = "pyrybox",
                displayName = null,
                channels = listOf(conversation("c1", null, "/w/one", true)),
            ),
        )

        composeTestRule.onAllNodes(hasText(string(R.string.unnamed_host))).assertCountEquals(1)
        composeTestRule.onNode(hasText(string(R.string.untitled_discussion))).assertIsDisplayed()
    }

    /**
     * The list draws its own bar (#737), so both controls have to survive every draw — not just the one a
     * single-state test happens to pick. A bar placed inside the tree's scroll container would pass on the
     * loaded draw and vanish on the centred placeholder, which is exactly the mistake this walks.
     *
     * Two draws now rather than four: the loading and error placeholders went with the flat state (#738),
     * so a tree with no hosts is the only blank left. Same shape as
     * [arrivalMarker_isCarriedByEveryDrawOfTheList_exactlyOnce], and each draw is identified by its own copy
     * first, so a bar that matched while the screen kept rendering the same thing would prove nothing. The
     * retired chrome is asserted absent on each one too: the app name and the logo's description went with
     * the generic top app bar, and the floating button's description with #738.
     */
    @Test
    fun listBar_drawsMenuAndPairingAndNoneOfTheRetiredChrome_onEveryDraw() {
        var hostState: HostChannelListState by mutableStateOf(HostChannelListState())
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ChannelListScreen(hostState = hostState, onEvent = { events += it })
            }
        }

        // 1. The empty placeholder: no host at all.
        composeTestRule.onNode(hasText(string(R.string.channel_list_empty))).assertExists()
        assertBarDrawn()
        assertToolbarGeometry()
        clickToolbar()
        events.clear()

        // 2. The tree — a host arrives, so the placeholder gives way to real rows.
        composeTestRule.runOnIdle {
            hostState =
                HostChannelListState(
                    listOf(
                        entry(
                            serverId = "pyrybox",
                            displayName = "Pyrybox",
                            channels = listOf(conversation("c1", "alpha channel", "/w/one", true)),
                        ),
                    ),
                )
        }
        composeTestRule.onNode(hasText("alpha channel")).assertIsDisplayed()
        assertBarDrawn()
        assertToolbarGeometry()
        val rule = composeTestRule.onNodeWithTag("channel-list-toolbar-rule").getUnclippedBoundsInRoot()
        val firstHost = composeTestRule.onNode(hasText("Pyrybox")).getUnclippedBoundsInRoot()
        assertEquals(24.dp, firstHost.top - rule.bottom)
        clickToolbar()
    }

    private fun assertBarDrawn() {
        composeTestRule.onAllNodes(hasContentDescription("Pair another host")).assertCountEquals(1)
        composeTestRule.onNode(hasContentDescription("Pair another host")).assertIsDisplayed()
        composeTestRule.onNode(hasContentDescription("Pair another host, Channels")).assertDoesNotExist()
        composeTestRule.onNode(hasContentDescription("Pair another host, Chats")).assertDoesNotExist()
        composeTestRule.onAllNodes(hasContentDescription("Open menu")).assertCountEquals(1)
        composeTestRule.onNode(hasContentDescription("Open menu")).assertIsDisplayed()
        composeTestRule.onNode(hasContentDescription("Open settings")).assertDoesNotExist()
        composeTestRule.onNode(hasContentDescription("Open archive")).assertDoesNotExist()
        // The generic top app bar's app name and Pyry logo go with it.
        composeTestRule.onNode(hasText(string(R.string.app_name))).assertDoesNotExist()
        composeTestRule.onNode(hasContentDescription("Pyrycode logo")).assertDoesNotExist()
        // The floating action button's own name is retired with it (#738) — no draw may still carry it.
        composeTestRule.onNode(hasContentDescription("New discussion")).assertDoesNotExist()
    }

    @Test
    fun menuTouchEdgesOpenIt_andThePairingEdgeStillRoutesOnlyToPairing() {
        setTree(entry("pyrybox", "Pyrybox"))
        for (rightEdge in listOf(false, true)) {
            composeTestRule.onNode(hasContentDescription("Open menu")).performTouchInput {
                click(Offset(if (rightEdge) width - 1f else 1f, center.y))
            }
            composeTestRule.onNode(hasText("Settings")).assertIsDisplayed()
            composeTestRule.onNode(hasText("Archive")).performTouchInput { click() }
        }
        composeTestRule.onNode(hasContentDescription("Pair another host")).performTouchInput {
            click(Offset(1f, center.y))
        }
        assertEquals(listOf(ChannelListEvent.ArchiveTapped, ChannelListEvent.ArchiveTapped, ChannelListEvent.PairHostTapped), events)
    }

    @Test
    fun menuOutsideTapAndBack_closeWithoutTapThrough() {
        setTree(entry("pyrybox", "Pyrybox"))
        openMenu()
        val pairing = composeTestRule.onNode(hasContentDescription("Pair another host")).fetchSemanticsNode().boundsInRoot
        composeTestRule.onNodeWithTag(CHANNEL_LIST_TEST_TAG).performTouchInput { click(pairing.center) }
        composeTestRule.onNode(hasText("Settings")).assertDoesNotExist()
        assertTrue(events.isEmpty())
        openMenu()
        composeTestRule.onNode(hasText("Archive")).assertIsDisplayed()
        Espresso.pressBack()
        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasText("Archive")).assertDoesNotExist()
        assertTrue(events.isEmpty())
        composeTestRule.onNode(hasContentDescription("Pair another host")).performTouchInput { click() }
        assertEquals(listOf(ChannelListEvent.PairHostTapped), events)
    }

    @Test
    fun menuSurvivesRecomposition_andUsesLiveBoundsInAnInsetScreen() {
        var state by mutableStateOf(HostChannelListState())
        var inset by mutableStateOf(20.dp)
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ChannelListScreen(state, { events += it }, Modifier.padding(top = inset))
            }
        }
        openMenu()
        composeTestRule.runOnIdle {
            state = HostChannelListState(listOf(entry("pyrybox", "Pyrybox")))
            inset = 40.dp
        }
        val anchor = composeTestRule.onNode(hasContentDescription("Open menu")).getUnclippedBoundsInRoot()
        val settings = composeTestRule.onNode(hasText("Settings")).getUnclippedBoundsInRoot()
        val archive = composeTestRule.onNode(hasText("Archive")).getUnclippedBoundsInRoot()
        assertDpEquals(6.dp, settings.top - anchor.bottom) // 4dp anchor gap + 2dp column padding.
        assertDpEquals(8.dp, settings.left)
        assertEquals(settings.bottom, archive.top)
        composeTestRule.onNode(hasText("Settings")).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        composeTestRule.onNode(hasText("Settings")).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        composeTestRule.onNode(hasText("Archive")).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        composeTestRule.onNode(hasText("Settings")).performTouchInput { click() }
        composeTestRule.onNode(hasText("Archive")).assertDoesNotExist()
        assertEquals(listOf(ChannelListEvent.SettingsTapped), events)
    }

    private val toolbarNames = listOf("Open menu", "Pair another host")

    private fun toolbarBounds() =
        toolbarNames.map {
            composeTestRule.onNode(hasContentDescription(it)).getUnclippedBoundsInRoot()
        }

    private fun openMenu() {
        composeTestRule.onNode(hasContentDescription("Open menu")).performTouchInput { click() }
    }

    private fun clickToolbar() {
        for (label in listOf("Settings", "Archive")) {
            openMenu()
            composeTestRule.onNode(hasText(label)).performTouchInput { click() }
            composeTestRule.onNode(hasText(label)).assertDoesNotExist()
        }
        composeTestRule.onNode(hasContentDescription("Pair another host")).performClick()
        assertEquals(listOf(ChannelListEvent.SettingsTapped, ChannelListEvent.ArchiveTapped, ChannelListEvent.PairHostTapped), events)
    }

    private fun assertToolbarGeometry() {
        val targets = toolbarBounds()
        targets.forEach {
            assertDpEquals(44.dp, it.right - it.left)
            assertDpEquals(44.dp, it.bottom - it.top)
            assertEquals(targets.first().top, it.top)
        }
        assertTrue(targets[0].right <= targets[1].left)
        val glyphs =
            toolbarNames.map {
                composeTestRule.onNode(hasContentDescription(it), useUnmergedTree = true).getUnclippedBoundsInRoot()
            }
        val root = composeTestRule.onNodeWithTag(CHANNEL_LIST_TEST_TAG).getUnclippedBoundsInRoot()
        assertDpEquals(6.dp, glyphs[0].right - glyphs[0].left)
        assertDpEquals(24.dp, glyphs[0].bottom - glyphs[0].top)
        assertDpEquals(24.dp, glyphs[1].right - glyphs[1].left)
        assertDpEquals(24.dp, glyphs[1].bottom - glyphs[1].top)
        assertDpEquals(29.dp, glyphs[0].left - root.left)
        assertDpEquals(20.dp, root.right - glyphs[1].right)
        assertDpEquals(32.dp, glyphs[0].top - root.top)
        assertDpEquals(32.dp, glyphs[1].top - root.top)
        val rule = composeTestRule.onNodeWithTag("channel-list-toolbar-rule").getUnclippedBoundsInRoot()
        assertDpEquals(20.dp, rule.left - root.left)
        assertDpEquals(20.dp, root.right - rule.right)
        assertDpEquals(72.dp, rule.top - root.top)
        assertDpEquals(1.dp, rule.bottom - rule.top)
    }

    /**
     * The host row's add control, on the two gestures the retired floating button owned.
     *
     * Driven on the **second** host of a two-host tree and asserted against that host's own id: a control
     * wired to a globally selected host would pass a single-host test and create the chat on the wrong
     * machine here. The control is addressed by its per-host tag — the same handle the device suites hold —
     * and its name is asserted distinct from the other host's, since both repeat down the screen.
     */
    @Test
    fun chatsSectionAddControl_targetsItsOwnHost() {
        setTree(
            entry(serverId = "pyrybox", displayName = "Pyrybox", chats = listOf(conversation("same", "A chat", "/a", false))),
            entry(serverId = "macbook", displayName = "Macbook", chats = listOf(conversation("same", "B chat", "/b", false))),
        )

        assertNotEquals(
            string(R.string.cd_tree_host_new_chat, "Pyrybox"),
            string(R.string.cd_tree_host_new_chat, "Macbook"),
        )
        val macbook = composeTestRule.onNodeWithTag(treeHostChatAddTestTag("macbook"))
        macbook.assert(hasContentDescription(string(R.string.cd_tree_host_new_chat, "Macbook")))
        composeTestRule.onAllNodes(hasTestTag("tree-host-add:macbook")).assertCountEquals(0)

        macbook.performClick()
        assertEquals(listOf(ChannelListEvent.TreeHostChatAddTapped("macbook")), events)
        composeTestRule.onNode(hasText(string(R.string.create_chat_title))).assertDoesNotExist()
    }

    /**
     * The add control sits inside the Chats header, whose whole surface is the fold control. Compose stops
     * merging semantics at a descendant that merges too, so the control keeps its own click action — but
     * that is an inherited guarantee, and this asserts it rather than assuming it: a tap on the plus must
     * not also fold the row it sits in.
     */
    @Test
    fun chatsSectionAddControl_doesNotFoldTheRowItSitsIn() {
        setTree(
            entry(
                serverId = "pyrybox",
                displayName = "Pyrybox",
                channels = listOf(conversation("c1", "alpha channel", "/w/one", true)),
            ),
        )

        composeTestRule
            .onNodeWithTag(treeHostChatAddTestTag("pyrybox"))
            .performClick()

        composeTestRule.onNode(hasText("alpha channel")).assertExists()
        assertEquals(listOf(ChannelListEvent.TreeHostChatAddTapped("pyrybox")), events)
    }

    @Test
    fun hostRowEditControl_targetsItsOwnHost_andDoesNotFoldTheRowItSitsIn() {
        setTree(
            entry(serverId = "pyrybox", displayName = "Pyrybox"),
            entry(
                serverId = "macbook",
                displayName = "Macbook",
                channels = listOf(conversation("c1", "alpha channel", "/w/one", true)),
            ),
        )

        assertNotEquals(
            string(R.string.cd_tree_host_edit, "Pyrybox"),
            string(R.string.cd_tree_host_edit, "Macbook"),
        )
        // Same shape as the add control's own test: the Chats section draws the host again, so the tag
        // matches twice and either instance is the same control on the same host.
        val macbook = composeTestRule.onAllNodes(hasTestTag(treeHostEditTestTag("macbook"))).onFirst()
        macbook.assert(hasContentDescription(string(R.string.cd_tree_host_edit, "Macbook")))

        macbook.performClick()

        // The row's whole surface is its fold control, so this also asserts the control kept its own
        // click action rather than the row swallowing the tap.
        composeTestRule.onNode(hasText("alpha channel")).assertExists()
        assertEquals(listOf(ChannelListEvent.TreeHostEditTapped("macbook")), events)
    }

    @Test
    fun hostRowReconnectControl_targetsItsOwnHost_andLeavesEveryHostsRowsDrawn() {
        setTree(
            entry(
                serverId = "pyrybox",
                displayName = "Pyrybox",
                channels = listOf(conversation("c1", "alpha channel", "/w/one", true)),
                relay = RelayLinkStatus.Offline,
            ),
            entry(
                serverId = "macbook",
                displayName = "Macbook",
                channels = listOf(conversation("c2", "beta channel", "/w/two", true)),
                relay = RelayLinkStatus.DaemonAbsent,
            ),
        )

        composeTestRule
            .onAllNodes(hasTestTag(treeHostReconnectTestTag("macbook")))
            .onFirst()
            .performClick()

        composeTestRule.onNode(hasText("alpha channel")).assertExists()
        composeTestRule.onNode(hasText("beta channel")).assertExists()
        assertEquals(listOf(ChannelListEvent.TreeHostReconnectTapped("macbook")), events)
    }

    @Test
    fun hostRowPlugControl_onARejectedPairing_opensRePairingForItsOwnHost() {
        setTree(
            entry(
                serverId = "pyrybox",
                displayName = "Pyrybox",
                channels = listOf(conversation("c1", "alpha channel", "/w/one", true)),
                relay = RelayLinkStatus.PairingRejected,
            ),
            entry(
                serverId = "macbook",
                displayName = "Macbook",
                channels = listOf(conversation("c2", "beta channel", "/w/two", true)),
                relay = RelayLinkStatus.Offline,
            ),
        )

        composeTestRule
            .onAllNodes(hasTestTag(treeHostReconnectTestTag("pyrybox")))
            .onFirst()
            .performClick()
        composeTestRule
            .onAllNodes(hasTestTag(treeHostReconnectTestTag("macbook")))
            .onFirst()
            .performClick()

        assertEquals(
            listOf(ChannelListEvent.TreeHostRePairTapped("pyrybox"), ChannelListEvent.TreeHostReconnectTapped("macbook")),
            events,
        )
    }

    @Test
    fun hostRowUpdateControl_opensTheStore_whileTheOtherHostsKeepTheirPlug() {
        setTree(
            entry(
                serverId = "pyrybox",
                displayName = "Pyrybox",
                channels = listOf(conversation("c1", "alpha channel", "/w/one", true)),
                relay = RelayLinkStatus.UpdateRequired("1.4.0"),
            ),
            entry(
                serverId = "macbook",
                displayName = "Macbook",
                relay = RelayLinkStatus.PairingRejected,
            ),
            entry(
                serverId = "laptop",
                displayName = "Laptop",
                relay = RelayLinkStatus.Offline,
            ),
        )

        listOf(
            treeHostUpdateTestTag("pyrybox"),
            treeHostReconnectTestTag("macbook"),
            treeHostReconnectTestTag("laptop"),
        ).forEach { tag ->
            composeTestRule
                .onAllNodes(hasTestTag(tag))
                .onFirst()
                .performClick()
        }

        composeTestRule.onNode(hasText("alpha channel")).assertExists()
        assertEquals(
            listOf(
                ChannelListEvent.TreeHostUpdateTapped,
                ChannelListEvent.TreeHostRePairTapped("macbook"),
                ChannelListEvent.TreeHostReconnectTapped("laptop"),
            ),
            events,
        )
    }

    /** One open editor on `pyrybox`, whose flags each test sets to the state it is asserting. */
    private fun openEditor(
        saving: Boolean = false,
        failed: Boolean = false,
        confirmingUnpair: Boolean = false,
        unpairFailed: Boolean = false,
        initialName: String = "Pyrybox",
    ) = HostEditorState(
        serverId = "pyrybox",
        serverIdentity = "server-777",
        relayAddress = "wss://relay.example:8443",
        initialName = initialName,
        saving = saving,
        failed = failed,
        confirmingUnpair = confirmingUnpair,
        unpairFailed = unpairFailed,
    )

    @Test
    fun editHostModal_drawsTheOpenEditorsOwnValuesAndReportsOkAndDismissal() {
        setTree(entry(serverId = "pyrybox", displayName = "Pyrybox"), hostEditor = openEditor())

        // The identity and the relay address are read from that host's own stored record and drawn as
        // the design's two inert rows; the name arrives as the editable value.
        composeTestRule.onNode(hasText("server-777", substring = true)).assertIsDisplayed()
        composeTestRule.onNode(hasText("wss://relay.example:8443", substring = true)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).assertTextContains("Pyrybox")

        composeTestRule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).performTextReplacement("  Renamed  ")
        composeTestRule.onNode(hasText("OK")).performClick()
        // The component trims before it reports, so the screen forwards a trimmed name.
        assertEquals(listOf(ChannelListEvent.HostEditNameSubmitted("Renamed")), events)

        events.clear()
        composeTestRule.onNode(hasText("Cancel")).performClick()
        assertEquals(listOf(ChannelListEvent.HostEditDismissed), events)
    }

    @Test
    fun editHostModal_whileSaving_cannotStartASecondSave() {
        setTree(entry(serverId = "pyrybox", displayName = "Pyrybox"), hostEditor = openEditor(saving = true))

        // The caller's loading flag disables the shell's OK, so a second tap cannot start a second save.
        composeTestRule.onNode(hasText("OK")).performClick()
        assertEquals(emptyList<ChannelListEvent>(), events)
    }

    @Test
    fun editHostModal_afterAFailure_staysOpenAndActionableAndStatesItGenerically() {
        setTree(entry(serverId = "pyrybox", displayName = "Pyrybox"), hostEditor = openEditor(failed = true))

        // Generic by construction: one static string, naming neither the identity nor the relay address.
        val failure = string(R.string.edit_host_save_failed)
        composeTestRule.onNode(hasText(failure)).assertIsDisplayed()
        assertFalse(failure.contains("server-777"))
        assertFalse(failure.contains("relay.example"))

        // Still open and still actionable with the entered value.
        composeTestRule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).performTextReplacement("Retry")
        composeTestRule.onNode(hasText("OK")).performClick()
        assertEquals(listOf(ChannelListEvent.HostEditNameSubmitted("Retry")), events)
    }

    @Test
    fun editHostModal_unpairAction_asksForConfirmationNamingTheHost_andDecliningReturnsToTheEditor() {
        setTree(entry(serverId = "pyrybox", displayName = "Pyrybox"), hostEditor = openEditor())

        composeTestRule.onNode(hasText(string(R.string.edit_host_unpair))).performClick()

        // Asking, not removing: the action opens a step the operator still has to accept.
        assertEquals(listOf(ChannelListEvent.HostUnpairRequested), events)
        // The host is named the way its row names it, and the editor's own field is gone rather than
        // covered by a second window stacked over this one.
        composeTestRule
            .onNode(hasText(string(R.string.edit_host_unpair_confirm_body, "Pyrybox")))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).assertDoesNotExist()

        composeTestRule.onNode(hasText("Cancel")).performClick()

        // A decline, not a dismissal — the modal is still open and back on its editor step.
        assertEquals(listOf(ChannelListEvent.HostUnpairRequested, ChannelListEvent.HostUnpairDeclined), events)
        composeTestRule.onNodeWithTag(EDIT_HOST_NAME_FIELD_TAG).assertIsDisplayed()
    }

    @Test
    fun editHostModal_unpairConfirmation_namesAnUnnamedHostByItsRowsOwnFallback() {
        setTree(
            entry(serverId = "pyrybox", displayName = null),
            hostEditor = openEditor(confirmingUnpair = true, initialName = ""),
        )

        // The same fallback the row draws, so the prompt can never name a host something the list does not.
        composeTestRule
            .onNode(hasText(string(R.string.edit_host_unpair_confirm_body, string(R.string.unnamed_host))))
            .assertIsDisplayed()
    }

    @Test
    fun editHostModal_afterAFailedUnpair_namesTheUnpairRatherThanTheSaveAndStaysActionable() {
        setTree(
            entry(serverId = "pyrybox", displayName = "Pyrybox"),
            hostEditor = openEditor(confirmingUnpair = true, unpairFailed = true),
        )

        // Each failure reads from its own flag, so the slot never reports the wrong operation — and the
        // string names neither the server identity nor the relay address.
        composeTestRule.onNode(hasText(string(R.string.edit_host_unpair_failed))).assertIsDisplayed()
        composeTestRule.onNode(hasText(string(R.string.edit_host_save_failed))).assertDoesNotExist()

        composeTestRule.onNode(hasText("OK")).performClick()

        // Still actionable: OK retries the removal rather than saving a name.
        assertEquals(listOf(ChannelListEvent.HostUnpairConfirmed), events)
    }

    /** Every composed row under [tag], top to bottom, read by the text it draws. */
    private fun drawnRows(tag: String): List<String> =
        composeTestRule
            .onAllNodes(hasTestTag(tag))
            .fetchSemanticsNodes()
            .sortedBy { it.boundsInRoot.top }
            .map { node -> node.config[SemanticsProperties.Text].first().text }

    /**
     * #1331: each host's sections sort alphabetically on every emission, so a rename, an auto-name and a
     * new chat each land at their sorted place with no refresh. The hosts' names interleave, so a sort
     * across hosts instead of within each would draw a different order.
     */
    @Test
    fun treeSections_sortAlphabeticallyWithinEachHost_andResortOnEveryEmission() {
        val state =
            mutableStateOf(
                HostChannelListState(
                    listOf(
                        entry(
                            "pyrybox",
                            "Pyrybox",
                            channels = listOf(conversation("p-c1", "delta ops", "/w", true), conversation("p-c2", "Bravo ops", "/w", true)),
                            chats = listOf(conversation("p-d1", "echo", "/w", false), conversation("p-d2", null, "/w", false)),
                        ),
                        entry(
                            "macbook",
                            "Macbook",
                            chats = listOf(conversation("m-d1", "Charlie", "/w", false), conversation("m-d2", "alpha", "/w", false)),
                        ),
                    ),
                ),
            )
        composeTestRule.setContent {
            PyrycodeMobileTheme { ChannelListScreen(state.value, onEvent = { events += it }) }
        }
        val untitled = string(R.string.untitled_discussion)

        assertEquals(listOf("Bravo ops", "delta ops"), drawnRows(TREE_CHANNEL_ROW_TEST_TAG))
        assertEquals(listOf("echo", untitled, "alpha", "Charlie"), drawnRows(TREE_CHAT_ROW_TEST_TAG))

        // A rename moves a channel, the daemon names the unnamed chat, and a new unnamed chat arrives.
        composeTestRule.runOnIdle {
            state.value =
                HostChannelListState(
                    listOf(
                        entry(
                            "pyrybox",
                            "Pyrybox",
                            channels = listOf(conversation("p-c1", "Alpha ops", "/w", true), conversation("p-c2", "Bravo ops", "/w", true)),
                            chats = listOf(conversation("p-d1", "echo", "/w", false), conversation("p-d2", "Bug triage", "/w", false)),
                        ),
                        entry(
                            "macbook",
                            "Macbook",
                            chats =
                                listOf(
                                    conversation("m-d3", null, "/w", false),
                                    conversation("m-d1", "Charlie", "/w", false),
                                    conversation("m-d2", "alpha", "/w", false),
                                ),
                        ),
                    ),
                )
        }

        assertEquals(listOf("Alpha ops", "Bravo ops"), drawnRows(TREE_CHANNEL_ROW_TEST_TAG))
        assertEquals(listOf("Bug triage", "echo", "alpha", "Charlie", untitled), drawnRows(TREE_CHAT_ROW_TEST_TAG))
    }

    /** #1331: a re-sort moves rows, never identities — the highlight and the tap target follow the moved row. */
    @Test
    fun resortedRows_keepTheirSelectionAndTapTargets() {
        fun host(
            channelName: String,
            chatName: String,
        ) = entry(
            "pyrybox",
            "Pyrybox",
            channels = listOf(conversation("c1", channelName, "/w", true), conversation("c2", "middle channel", "/w", true)),
            chats = listOf(conversation("d1", chatName, "/w", false), conversation("d2", "middle chat", "/w", false)),
        )
        val state =
            mutableStateOf(
                HostChannelListState(listOf(host("alpha channel", "alpha chat")), selected = HostConversationTarget("pyrybox", "d1")),
            )
        composeTestRule.setContent {
            PyrycodeMobileTheme { ChannelListScreen(state.value, onEvent = { events += it }) }
        }
        assertEquals(listOf("alpha chat", "middle chat"), drawnRows(TREE_CHAT_ROW_TEST_TAG))

        composeTestRule.runOnIdle { state.value = state.value.copy(hosts = listOf(host("zulu channel", "zulu chat"))) }

        assertEquals(listOf("middle channel", "zulu channel"), drawnRows(TREE_CHANNEL_ROW_TEST_TAG))
        assertEquals(listOf("middle chat", "zulu chat"), drawnRows(TREE_CHAT_ROW_TEST_TAG))
        composeTestRule.onNode(hasTestTag(TREE_CHAT_ROW_TEST_TAG) and hasText("zulu chat")).assertIsSelected()
        composeTestRule.onNode(hasTestTag(TREE_CHAT_ROW_TEST_TAG) and hasText("middle chat")).assertIsNotSelected()

        composeTestRule.onNode(hasTestTag(TREE_CHAT_ROW_TEST_TAG) and hasText("zulu chat")).performClick()
        composeTestRule.onNode(hasTestTag(TREE_CHANNEL_ROW_TEST_TAG) and hasText("zulu channel")).performClick()
        assertEquals(
            listOf(
                ChannelListEvent.TreeRowTapped(HostConversationTarget("pyrybox", "d1")),
                ChannelListEvent.TreeRowTapped(HostConversationTarget("pyrybox", "c1")),
            ),
            events,
        )
    }

    @Test
    fun sectionRowsNameTheirHostAndLeaveFolderSettingsOutOfTheTree() {
        setTree(
            entry(
                serverId = "pyrybox",
                displayName = "Pyrybox",
                channels = listOf(conversation("c1", "alpha channel", "/w/one", true)),
                chats = listOf(conversation("d1", "bravo chat", "/w/two", false)),
            ),
            entry(
                serverId = "macbook",
                displayName = "Macbook",
                chats = listOf(conversation("d2", "charlie chat", "/w/two", false)),
            ),
        )

        val channels = hasContentDescription(string(R.string.cd_tree_row_collapse, "Channels on Pyrybox"))
        composeTestRule
            .onNode(channels)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(28.dp)
            .performClick()
        composeTestRule.onNode(hasText("alpha channel")).assertDoesNotExist()
        composeTestRule.onNode(hasText("bravo chat")).assertExists()
        composeTestRule.onNode(hasText("charlie chat")).assertExists()
        composeTestRule.onAllNodes(hasContentDescription(string(R.string.cd_tree_workspace_edit, "one"))).assertCountEquals(0)
        assertEquals(ChannelListEvent.TreeFoldToggled(TreeFoldKey(ConversationTreeSection.Channels, "pyrybox")), events.last())
    }

    @Test
    fun channelsSectionPlus_namesItsHostAndWorksWhenItsSectionIsEmpty() {
        setTree(
            entry(
                serverId = "pyrybox",
                displayName = "Pyrybox",
                channels = listOf(conversation("c1", "alpha channel", "/w/one", true)),
                chats = listOf(conversation("d1", "bravo chat", "/w/chats", false)),
            ),
            entry(
                serverId = "macbook",
                displayName = "Macbook",
            ),
            selected = HostConversationTarget("pyrybox", "c1"),
        )

        val first = hasTestTag(treeHostChannelAddTestTag("pyrybox"))
        val second = hasTestTag(treeHostChannelAddTestTag("macbook"))
        val fold = composeTestRule.onNode(hasContentDescription(string(R.string.cd_tree_row_collapse, "Channels on Pyrybox")))
        assertTrue(fold.getUnclippedBoundsInRoot().right <= composeTestRule.onNode(first).getUnclippedBoundsInRoot().left)
        composeTestRule
            .onNode(first)
            .assertHeightIsAtLeast(28.dp)
            .performClick()
        // The plus's own node took the tap: the channel is still drawn, nothing folded.
        composeTestRule.onNode(hasText("alpha channel")).assertExists()
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(second)
        composeTestRule.onNode(second and hasContentDescription(string(R.string.cd_tree_host_new_channel, "Macbook"))).performClick()

        assertEquals(
            listOf(
                ChannelListEvent.TreeHostChannelAddTapped("pyrybox"),
                ChannelListEvent.TreeHostChannelAddTapped("macbook"),
            ),
            events,
        )
    }

    private fun channelHost(pyrycode: PyrycodeLinkStatus = PyrycodeLinkStatus.Connected) =
        entry(serverId = "pyrybox", displayName = "Pyrybox", channels = listOf(conversation("c1", "alpha channel", "/w/one", true)))
            .let { it.copy(host = it.host.copy(connectionStatus = ConnectionStatus(RelayLinkStatus.Connected, pyrycode))) }

    @Test
    fun createChannelModal_reportsTheTypedValues_failuresAreStatic_andOkFollowsItsOwnHost() {
        val state =
            mutableStateOf(HostChannelListState(listOf(channelHost()), createChannel = CreateChannelState("pyrybox")))
        composeTestRule.setContent {
            PyrycodeMobileTheme { ChannelListScreen(hostState = state.value, onEvent = { events += it }) }
        }

        composeTestRule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).performTextInput("  Ops  ")
        composeTestRule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).performTextInput("Be brief.")
        composeTestRule.onNode(hasText("OK")).performClick()

        state.value = state.value.copy(createChannel = CreateChannelState("pyrybox", createFailed = true))
        composeTestRule.onNode(hasText(string(R.string.create_channel_failed))).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).assertTextContains("  Ops  ")

        state.value =
            state.value.copy(
                createChannel = CreateChannelState("pyrybox", createdConversationId = "c9", promptFailed = true),
            )
        composeTestRule.onNode(hasText(string(R.string.create_channel_prompt_failed))).performScrollTo().assertIsDisplayed()
        composeTestRule.onAllNodes(hasText(string(R.string.create_channel_failed))).assertCountEquals(0)
        composeTestRule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).assertTextContains("Be brief.")

        state.value = state.value.copy(hosts = listOf(channelHost(PyrycodeLinkStatus.Down)))
        composeTestRule.onNode(hasText("OK")).assertIsNotEnabled()
        composeTestRule.onNode(hasText("Cancel")).performClick()

        assertEquals(
            listOf(
                ChannelListEvent.CreateChannelSubmitted("Ops", "Be brief."),
                ChannelListEvent.CreateChannelDismissed,
            ),
            events,
        )
        assertFalse(events.first().toString().contains("Be brief."))
    }

    private fun workspaceHost(pyrycode: PyrycodeLinkStatus = PyrycodeLinkStatus.Connected) =
        entry(serverId = "pyrybox", displayName = "Pyrybox", chats = listOf(conversation("d1", "bravo chat", "/w/two", false)))
            .let { it.copy(host = it.host.copy(connectionStatus = ConnectionStatus(RelayLinkStatus.Connected, pyrycode))) }

    private fun openWorkspace(
        confirmingArchive: Boolean = false,
        failed: Boolean = false,
        archiveFailed: Boolean = false,
    ) = WorkspaceEditorState(
        serverId = "pyrybox",
        cwd = "/w/two",
        initialName = "two",
        confirmingArchive = confirmingArchive,
        failed = failed,
        archiveFailed = archiveFailed,
    )

    @Test
    fun editWorkspaceModal_isSeeded_reportsTheNameAndTheArchiveSteps() {
        val state = mutableStateOf(HostChannelListState(listOf(workspaceHost()), workspaceEditor = openWorkspace()))
        composeTestRule.setContent {
            PyrycodeMobileTheme { ChannelListScreen(hostState = state.value, onEvent = { events += it }) }
        }

        composeTestRule.onNodeWithTag(EDIT_WORKSPACE_NAME_FIELD_TAG).assertTextContains("two")
        composeTestRule.onNodeWithTag(EDIT_WORKSPACE_NAME_FIELD_TAG).performTextReplacement("  Renamed  ")
        composeTestRule.onNode(hasText("OK")).performClick()
        composeTestRule.onNode(hasText(string(R.string.edit_workspace_archive))).performScrollTo().performClick()
        composeTestRule.onNode(hasText("Cancel")).performClick()

        state.value = state.value.copy(workspaceEditor = openWorkspace(confirmingArchive = true))
        composeTestRule.onNode(hasText(string(R.string.edit_workspace_archive_confirm_body, "two"))).assertIsDisplayed()
        composeTestRule.onNode(hasText("OK")).performClick()
        composeTestRule.onNode(hasText("Cancel")).performClick()

        assertEquals(
            listOf(
                ChannelListEvent.WorkspaceEditNameSubmitted("Renamed"),
                ChannelListEvent.WorkspaceArchiveRequested,
                ChannelListEvent.WorkspaceEditDismissed,
                ChannelListEvent.WorkspaceArchiveConfirmed,
                ChannelListEvent.WorkspaceArchiveDeclined,
            ),
            events,
        )
    }

    @Test
    fun editWorkspaceModal_failuresAreStaticAndTheTypedNameSurvives_andOkFollowsItsOwnHost() {
        val state = mutableStateOf(HostChannelListState(listOf(workspaceHost()), workspaceEditor = openWorkspace()))
        composeTestRule.setContent {
            PyrycodeMobileTheme { ChannelListScreen(hostState = state.value, onEvent = { events += it }) }
        }
        composeTestRule.onNodeWithTag(EDIT_WORKSPACE_NAME_FIELD_TAG).performTextReplacement("Typed")

        state.value = state.value.copy(workspaceEditor = openWorkspace(failed = true))
        composeTestRule.onNode(hasText(string(R.string.edit_workspace_save_failed))).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag(EDIT_WORKSPACE_NAME_FIELD_TAG).assertTextContains("Typed")

        state.value = state.value.copy(hosts = listOf(workspaceHost(PyrycodeLinkStatus.Down)))
        composeTestRule.onNode(hasText("OK")).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(EDIT_WORKSPACE_NAME_FIELD_TAG).assertTextContains("Typed")

        state.value =
            HostChannelListState(listOf(workspaceHost()), workspaceEditor = openWorkspace(confirmingArchive = true, archiveFailed = true))
        composeTestRule.onNode(hasText(string(R.string.edit_workspace_archive_failed))).performScrollTo().assertIsDisplayed()
        composeTestRule.onAllNodes(hasText(string(R.string.edit_workspace_save_failed))).assertCountEquals(0)
        assertTrue(events.isEmpty())
    }

    @Test
    fun settingsMenuRow_emitsSettingsTapped() {
        setTree(entry(serverId = "pyrybox", displayName = "Pyrybox"))

        openMenu()
        composeTestRule
            .onNode(hasText("Settings"))
            .performClick()

        assertEquals(listOf(ChannelListEvent.SettingsTapped), events)
    }

    @Test
    fun archiveMenuRow_emitsArchiveTapped() {
        setTree(entry(serverId = "pyrybox", displayName = "Pyrybox"))

        openMenu()
        composeTestRule
            .onNode(hasText("Archive"))
            .performClick()

        assertEquals(listOf(ChannelListEvent.ArchiveTapped), events)
    }

    /**
     * The arrival marker (#736) is set once, on the screen's root, so every draw carries it. This walks the
     * same composition through both draws that remain after #738 retired the loading and error placeholders
     * — the empty placeholder and the tree — asserting on each that exactly one node carries it, and that
     * the draw really is the one intended: a marker that matched while the screen kept rendering the same
     * thing would prove nothing.
     */
    @Test
    fun arrivalMarker_isCarriedByEveryDrawOfTheList_exactlyOnce() {
        var hostState: HostChannelListState by mutableStateOf(HostChannelListState())
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ChannelListScreen(hostState = hostState, onEvent = {})
            }
        }

        // 1. The empty placeholder: no host at all.
        composeTestRule.onNode(hasText(string(R.string.channel_list_empty))).assertExists()
        assertMarkedOnce()

        // 2. The tree — a host arrives, so the placeholder gives way to real rows.
        composeTestRule.runOnIdle {
            hostState =
                HostChannelListState(
                    listOf(
                        entry(
                            serverId = "pyrybox",
                            displayName = "Pyrybox",
                            channels = listOf(conversation("c1", "alpha channel", "/w/one", true)),
                        ),
                    ),
                )
        }
        composeTestRule.onNode(hasText(string(R.string.channel_list_empty))).assertDoesNotExist()
        composeTestRule.onNode(hasText("alpha channel")).assertIsDisplayed()
        assertMarkedOnce()
    }

    private fun assertMarkedOnce() {
        composeTestRule.onAllNodes(hasTestTag(CHANNEL_LIST_TEST_TAG)).assertCountEquals(1)
    }

    @Test
    fun emptyState_rendersPlaceholder_whenThereAreNoHosts() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ChannelListScreen(hostState = HostChannelListState(), onEvent = { events += it })
            }
        }

        composeTestRule
            .onNode(hasText("To pair a host, tap Pair another host at the top right."))
            .assertIsDisplayed()
        composeTestRule.onNode(hasText("No host is paired")).assertDoesNotExist()
        composeTestRule.onNode(hasContentDescription("Pair another host")).assertIsDisplayed().performClick()
        assertEquals(listOf(ChannelListEvent.PairHostTapped), events)
        events.clear()
        composeTestRule.onNode(hasText("Tap + to start a conversation")).assertDoesNotExist()
        openMenu()
        composeTestRule
            .onNode(hasText("Settings"))
            .assertIsDisplayed()
            .performClick()
        assertEquals(listOf(ChannelListEvent.SettingsTapped), events)
    }

    /**
     * Add workspace (#904) draws exactly while its state is set; OK waits for a selection and for the
     * modal's own host to be connected; a row reports its raw path, not the clamped text it displays.
     */
    @Test
    fun addWorkspaceModal_drawsOnItsStateAndGatesOkOnSelectionAndItsHost() {
        val longPath = "/w/" + "x".repeat(600)
        var state: HostChannelListState by mutableStateOf(
            HostChannelListState(hosts = listOf(entry(serverId = "pyrybox", displayName = "Pyrybox"))),
        )
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ChannelListScreen(hostState = state, onEvent = { events += it })
            }
        }
        composeTestRule.onNode(hasText(CREATE_FOLDER_ENTRY, substring = true)).assertDoesNotExist()

        composeTestRule.runOnIdle {
            state = state.copy(addWorkspace = AddWorkspaceState("pyrybox"), addWorkspaceRecent = listOf("/w/one", longPath))
        }
        composeTestRule.onNode(hasText(string(R.string.add_workspace_title))).assertIsDisplayed()
        composeTestRule.onNode(hasText(string(R.string.add_workspace_recent))).assertIsDisplayed()
        composeTestRule.onNode(hasText(CREATE_FOLDER_ENTRY, substring = true)).assertIsDisplayed()
        composeTestRule.onNode(hasText("OK")).assertIsNotEnabled()

        composeTestRule.onNode(hasText("/w/" + "x".repeat(509))).performScrollTo().performClick()
        assertEquals(listOf<ChannelListEvent>(ChannelListEvent.AddWorkspaceSelected(longPath)), events)

        // Selected, but its host is down: OK stays disabled; up again, OK submits and carries no ids.
        composeTestRule.runOnIdle {
            state =
                state.copy(
                    hosts = listOf(entry(serverId = "pyrybox", displayName = "Pyrybox", relay = RelayLinkStatus.Offline)),
                    addWorkspace = AddWorkspaceState("pyrybox", selected = "/w/one"),
                )
        }
        composeTestRule.onNode(hasText("/w/one")).assertIsSelected()
        composeTestRule.onNode(hasText("OK")).assertIsNotEnabled()
        composeTestRule.runOnIdle { state = state.copy(hosts = listOf(entry(serverId = "pyrybox", displayName = "Pyrybox"))) }
        events.clear()
        composeTestRule.onNode(hasText("OK")).assertIsEnabled().performClick()
        composeTestRule.onNode(hasText("Cancel")).performClick()
        assertEquals(listOf(ChannelListEvent.AddWorkspaceSubmitted, ChannelListEvent.AddWorkspaceDismissed), events)

        composeTestRule.runOnIdle { state = state.copy(addWorkspace = null) }
        composeTestRule.onNode(hasText(string(R.string.add_workspace_title))).assertDoesNotExist()
    }

    /**
     * A created folder absent from the recents is still drawn as the selection. The new-folder entry
     * reports the trimmed name and leaves the selection alone, and a failure shows a static sentence.
     */
    @Test
    fun addWorkspaceModal_showsACreatedSelectionAndReportsANewFolderAndStaticFailures() {
        var state: HostChannelListState by mutableStateOf(
            HostChannelListState(
                hosts = listOf(entry(serverId = "pyrybox", displayName = "Pyrybox")),
                addWorkspace = AddWorkspaceState("pyrybox", selected = "/w/created"),
                addWorkspaceRecent = listOf("/w/one"),
            ),
        )
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ChannelListScreen(hostState = state, onEvent = { events += it })
            }
        }
        composeTestRule.onNode(hasText(string(R.string.add_workspace_new_folder))).assertIsDisplayed()
        composeTestRule.onNode(hasText("/w/created")).assertIsSelected()
        composeTestRule.onNode(hasText("/w/one")).assertIsNotSelected()

        composeTestRule.onNode(hasText(CREATE_FOLDER_ENTRY, substring = true)).performScrollTo().performClick()
        composeTestRule.onNode(hasText("What should this workspace be called?")).performTextInput("  fresh  ")
        composeTestRule.onNode(hasText("Create")).performClick()
        assertEquals(listOf<ChannelListEvent>(ChannelListEvent.AddWorkspaceFolderCreateRequested("fresh")), events)
        composeTestRule.onNode(hasText("/w/created")).assertIsSelected()

        composeTestRule.runOnIdle { state = state.copy(addWorkspace = state.addWorkspace?.copy(createFailed = true)) }
        composeTestRule.onNode(hasText(string(R.string.add_workspace_create_failed))).assertIsDisplayed()
        composeTestRule.runOnIdle { state = state.copy(addWorkspace = state.addWorkspace?.copy(createFailed = false, startFailed = true)) }
        composeTestRule.onNode(hasText(string(R.string.add_workspace_start_failed))).assertIsDisplayed()
        composeTestRule.onNode(hasText("/w/created")).assertIsSelected()
    }

    private companion object {
        // Add workspace's new-folder entry, matched as a substring so its trailing ellipsis need not be
        // reproduced — the same handle the rung-3 create-workspace scenario holds it by.
        const val CREATE_FOLDER_ENTRY = "Create new folder under pyry-workspace"
    }
}
