package de.pyryco.mobile.ui.conversations.list

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.repository.SessionPromptStatus
import de.pyryco.mobile.data.repository.SystemPromptLimit
import de.pyryco.mobile.di.ConversationAttention
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.ui.components.CHANNEL_NAME_FIELD_TAG
import de.pyryco.mobile.ui.components.CHANNEL_PROMPT_FIELD_TAG
import de.pyryco.mobile.ui.components.EDIT_CHAT_NAME_FIELD_TAG
import de.pyryco.mobile.ui.components.EDIT_HOST_NAME_FIELD_TAG
import de.pyryco.mobile.ui.components.EDIT_WORKSPACE_NAME_FIELD_TAG
import de.pyryco.mobile.ui.conversations.components.treeHostAddTestTag
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
        chatEditor: ChatEditorState? = null,
    ) {
        composeTestRule.setContent {
            var hostState by remember {
                mutableStateOf(HostChannelListState(hosts.toList(), selected = selected, hostEditor = hostEditor, chatEditor = chatEditor))
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
    }

    private fun string(
        resId: Int,
        vararg formatArgs: Any,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId, *formatArgs)

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
        // The host draws a row in each section; its workspaces and conversations start expanded, which is
        // what every scripted device scenario depends on.
        composeTestRule.onAllNodes(hasText("Pyrybox")).assertCountEquals(2)
        composeTestRule.onNode(hasText("one")).assertExists()
        composeTestRule.onNode(hasText("two")).assertExists()
        composeTestRule.onNode(hasText("alpha channel")).assertIsDisplayed()
        composeTestRule.onNode(hasText("bravo chat")).assertIsDisplayed()
    }

    @Test
    fun foldingAHostHidesItsWorkspaces_andFoldingAWorkspaceHidesOnlyItsOwnConversations() {
        setTree(
            entry(
                serverId = "pyrybox",
                displayName = "Pyrybox",
                channels =
                    listOf(
                        conversation("c1", "alpha channel", "/w/one", true),
                        conversation("c2", "bravo channel", "/w/two", true),
                    ),
            ),
            entry(
                serverId = "macbook",
                displayName = "Macbook",
                channels = listOf(conversation("c3", "charlie channel", "/w/three", true)),
            ),
        )

        // Fold the first host: its workspaces and their conversations go, the other host's stay.
        composeTestRule.onAllNodes(hasText("Pyrybox")).onFirst().performClick()
        composeTestRule.onNode(hasText("one")).assertDoesNotExist()
        composeTestRule.onNode(hasText("alpha channel")).assertDoesNotExist()
        composeTestRule.onNode(hasText("bravo channel")).assertDoesNotExist()
        composeTestRule.onNode(hasText("charlie channel")).assertExists()

        // Unfold it and fold one workspace instead: only that workspace's conversations go.
        composeTestRule.onAllNodes(hasText("Pyrybox")).onFirst().performClick()
        composeTestRule.onNode(hasText("alpha channel")).assertExists()
        composeTestRule.onNode(hasText("one")).performClick()
        composeTestRule.onNode(hasText("one")).assertExists()
        composeTestRule.onNode(hasText("alpha channel")).assertDoesNotExist()
        composeTestRule.onNode(hasText("bravo channel")).assertExists()
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
        setTree(entry(serverId = "pyrybox", displayName = "Pyrybox", channels = many))

        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("channel 60"))
        composeTestRule.onNode(hasText("channel 60")).assertIsDisplayed()
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

        composeTestRule.onAllNodes(hasText(string(R.string.unnamed_host))).assertCountEquals(2)
        composeTestRule.onNode(hasText(string(R.string.untitled_discussion))).assertIsDisplayed()
    }

    /**
     * The list draws its own bar (#737), so both entries have to survive every draw — not just the one a
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
    fun listBar_drawsBothEntriesAndNoneOfTheRetiredChrome_onEveryDraw() {
        var hostState: HostChannelListState by mutableStateOf(HostChannelListState())
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ChannelListScreen(hostState = hostState, onEvent = {})
            }
        }

        // 1. The empty placeholder: no host at all.
        composeTestRule.onNode(hasText(string(R.string.channel_list_empty))).assertExists()
        assertBarDrawn()

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
    }

    private fun assertBarDrawn() {
        composeTestRule.onNode(hasContentDescription(string(R.string.cd_open_settings))).assertIsDisplayed()
        composeTestRule.onNode(hasContentDescription(string(R.string.cd_open_archive))).assertIsDisplayed()
        // The generic top app bar's app name and Pyry logo go with it.
        composeTestRule.onNode(hasText(string(R.string.app_name))).assertDoesNotExist()
        composeTestRule.onNode(hasContentDescription("Pyrycode logo")).assertDoesNotExist()
        // The floating action button's own name is retired with it (#738) — no draw may still carry it.
        composeTestRule.onNode(hasContentDescription("New discussion")).assertDoesNotExist()
    }

    /**
     * The tree draws a header per section, so the pairing control repeats — and the two must be separately
     * addressable rather than sharing one name. Both are driven here, so neither header can ship a control
     * that is drawn but inert.
     */
    @Test
    fun sectionHeaders_eachCarryTheirOwnPairingControl() {
        setTree(entry(serverId = "pyrybox", displayName = "Pyrybox"))

        val channels = string(R.string.cd_tree_section_pair_host, string(R.string.channels_section_header))
        val chats = string(R.string.cd_tree_section_pair_host, string(R.string.chats_section_header))
        assertNotEquals(channels, chats)

        composeTestRule.onNode(hasContentDescription(channels)).performClick()
        composeTestRule.onNode(hasContentDescription(chats)).performClick()

        assertEquals(listOf(ChannelListEvent.PairHostTapped, ChannelListEvent.PairHostTapped), events)
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
    fun hostRowAddControl_targetsItsOwnHost_onTapAndOnLongPress() {
        setTree(
            entry(serverId = "pyrybox", displayName = "Pyrybox"),
            entry(serverId = "macbook", displayName = "Macbook"),
        )

        assertNotEquals(
            string(R.string.cd_tree_host_new_chat, "Pyrybox"),
            string(R.string.cd_tree_host_new_chat, "Macbook"),
        )
        // The Chats section draws the same host again, so the tag matches twice; either instance is the
        // same control on the same host, which is the point — tap the one the suites would reach first.
        val macbook = composeTestRule.onAllNodes(hasTestTag(treeHostAddTestTag("macbook"))).onFirst()
        macbook.assert(hasContentDescription(string(R.string.cd_tree_host_new_chat, "Macbook")))

        macbook.performClick()
        macbook.performTouchInput { longClick() }

        assertEquals(
            listOf(
                ChannelListEvent.TreeHostAddTapped("macbook"),
                ChannelListEvent.TreeHostAddLongPressed("macbook"),
            ),
            events,
        )
    }

    /**
     * The add control sits inside the host row, whose whole surface is the fold control. Compose stops
     * merging semantics at a descendant that merges too, so the control keeps its own click action — but
     * that is an inherited guarantee, and this asserts it rather than assuming it: a tap on the plus must
     * not also fold the row it sits in.
     */
    @Test
    fun hostRowAddControl_doesNotFoldTheRowItSitsIn() {
        setTree(
            entry(
                serverId = "pyrybox",
                displayName = "Pyrybox",
                channels = listOf(conversation("c1", "alpha channel", "/w/one", true)),
            ),
        )

        composeTestRule
            .onAllNodes(hasTestTag(treeHostAddTestTag("pyrybox")))
            .onFirst()
            .performClick()

        composeTestRule.onNode(hasText("alpha channel")).assertExists()
        assertEquals(listOf(ChannelListEvent.TreeHostAddTapped("pyrybox")), events)
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

    @Test
    fun chatRowPencil_namesItsChat_opensItsOwnTarget_andChannelRowsNameThemselvesAsChannels() {
        setTree(
            entry(
                serverId = "pyrybox",
                displayName = "Pyrybox",
                channels = listOf(conversation("same", "alpha channel", "/w/one", true)),
                chats = listOf(conversation("same", "bravo chat", "/w/two", false)),
            ),
            entry(
                serverId = "macbook",
                displayName = "Macbook",
                chats = listOf(conversation("same", "charlie chat", "/w/three", false), conversation("d2", null, "/w/three", false)),
            ),
        )

        // One pencil per chat, each naming its own chat; the channel's pen is an Edit channel pen (#667).
        composeTestRule.onAllNodes(hasContentDescription(string(R.string.cd_tree_chat_edit, "bravo chat"))).assertCountEquals(1)
        composeTestRule.onAllNodes(hasContentDescription(string(R.string.cd_tree_chat_edit, "alpha channel"))).assertCountEquals(0)
        composeTestRule.onAllNodes(hasContentDescription(string(R.string.cd_tree_channel_edit, "alpha channel"))).assertCountEquals(1)
        // Lower rows compose lazily, so the nameless chat's pencil is reached by scrolling to it.
        val untitled = hasContentDescription(string(R.string.cd_tree_chat_edit, string(R.string.untitled_discussion)))
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(untitled)
        composeTestRule.onAllNodes(untitled).assertCountEquals(1)

        composeTestRule
            .onNode(hasScrollAction())
            .performScrollToNode(hasContentDescription(string(R.string.cd_tree_chat_edit, "charlie chat")))
        composeTestRule.onNode(hasContentDescription(string(R.string.cd_tree_chat_edit, "charlie chat"))).performClick()

        // The pencil's own node took the tap: it opens neither the thread nor the highlight.
        assertEquals(listOf(ChannelListEvent.TreeChatEditTapped(HostConversationTarget("macbook", "same"))), events)
        composeTestRule.onAllNodes(hasTestTag(TREE_CHAT_ROW_TEST_TAG)).onFirst().assertIsNotSelected()
    }

    private fun openChat(
        saving: Boolean = false,
        failed: Boolean = false,
        archiveFailed: Boolean = false,
    ) = ChatEditorState(
        serverId = "pyrybox",
        conversationId = "d1",
        initialName = "bravo chat",
        saving = saving,
        failed = failed,
        archiveFailed = archiveFailed,
    )

    private fun chatHost(pyrycode: PyrycodeLinkStatus = PyrycodeLinkStatus.Connected) =
        entry(serverId = "pyrybox", displayName = "Pyrybox", chats = listOf(conversation("d1", "bravo chat", "/w/two", false)))
            .let { it.copy(host = it.host.copy(connectionStatus = ConnectionStatus(RelayLinkStatus.Connected, pyrycode))) }

    @Test
    fun editChatModal_isPrefilled_reportsTheTrimmedNameAndDismissal() {
        setTree(chatHost(), chatEditor = openChat())

        composeTestRule.onNodeWithTag(EDIT_CHAT_NAME_FIELD_TAG).assertTextContains("bravo chat")
        composeTestRule.onNodeWithTag(EDIT_CHAT_NAME_FIELD_TAG).performTextReplacement("  Renamed  ")
        composeTestRule.onNode(hasText("OK")).performClick()
        assertEquals(listOf(ChannelListEvent.ChatEditNameSubmitted("Renamed")), events)

        events.clear()
        composeTestRule.onNode(hasText("Cancel")).performClick()
        assertEquals(listOf(ChannelListEvent.ChatEditDismissed), events)
    }

    @Test
    fun editChatModal_afterAFailure_statesItGenericallyAndKeepsTheTypedName() {
        setTree(chatHost(), chatEditor = openChat(failed = true))

        val failure = string(R.string.edit_chat_save_failed)
        composeTestRule.onNode(hasText(failure)).assertIsDisplayed()
        assertFalse(failure.contains("bravo"))

        composeTestRule.onNodeWithTag(EDIT_CHAT_NAME_FIELD_TAG).performTextReplacement("Retry")
        composeTestRule.onNode(hasText("OK")).performClick()
        assertEquals(listOf(ChannelListEvent.ChatEditNameSubmitted("Retry")), events)
    }

    @Test
    fun editChatModal_okFollowsItsOwnHostsConnection_andKeepsTheTypedNameAcrossAReconnect() {
        val state = mutableStateOf(HostChannelListState(listOf(chatHost()), chatEditor = openChat()))
        composeTestRule.setContent {
            PyrycodeMobileTheme { ChannelListScreen(hostState = state.value, onEvent = { events += it }) }
        }
        composeTestRule.onNodeWithTag(EDIT_CHAT_NAME_FIELD_TAG).performTextReplacement("Typed")

        state.value = state.value.copy(hosts = listOf(chatHost(PyrycodeLinkStatus.Down)))
        composeTestRule.onNode(hasText("OK")).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(EDIT_CHAT_NAME_FIELD_TAG).assertTextContains("Typed")

        state.value = state.value.copy(hosts = listOf(chatHost()))
        composeTestRule.onNode(hasText("OK")).assertIsEnabled()
        composeTestRule.onNodeWithTag(EDIT_CHAT_NAME_FIELD_TAG).assertTextContains("Typed")
        composeTestRule.onNode(hasText("OK")).performClick()
        assertEquals(listOf(ChannelListEvent.ChatEditNameSubmitted("Typed")), events)
    }

    @Test
    fun editChatModal_archiveReportsOnlyTheArchive_whateverTheNameFieldHolds() {
        setTree(chatHost(), chatEditor = openChat())

        composeTestRule.onNodeWithTag(EDIT_CHAT_NAME_FIELD_TAG).performTextReplacement("   ")
        composeTestRule.onNode(hasText(string(R.string.edit_chat_archive))).performClick()

        assertEquals(listOf(ChannelListEvent.ChatArchiveRequested), events)
    }

    @Test
    fun editChatModal_afterAFailedArchive_statesItGenericallyAndKeepsTheTypedName() {
        val state = mutableStateOf(HostChannelListState(listOf(chatHost()), chatEditor = openChat()))
        composeTestRule.setContent {
            PyrycodeMobileTheme { ChannelListScreen(hostState = state.value, onEvent = { events += it }) }
        }
        composeTestRule.onNodeWithTag(EDIT_CHAT_NAME_FIELD_TAG).performTextReplacement("Typed")

        state.value = state.value.copy(chatEditor = openChat(archiveFailed = true))
        val failure = string(R.string.archive_failed)
        composeTestRule.onNode(hasText(failure)).assertIsDisplayed()
        composeTestRule.onAllNodes(hasText(string(R.string.edit_chat_save_failed))).assertCountEquals(0)
        assertFalse(failure.contains("bravo"))
        composeTestRule.onNodeWithTag(EDIT_CHAT_NAME_FIELD_TAG).assertTextContains("Typed")
        composeTestRule.onNode(hasText(string(R.string.edit_chat_archive))).assertIsEnabled()
    }

    @Test
    fun editChatModal_archiveIsDisabledWhileItsHostIsDownOrAWriteIsInFlight() {
        val state = mutableStateOf(HostChannelListState(listOf(chatHost(PyrycodeLinkStatus.Down)), chatEditor = openChat()))
        composeTestRule.setContent {
            PyrycodeMobileTheme { ChannelListScreen(hostState = state.value, onEvent = { events += it }) }
        }
        val archive = hasText(string(R.string.edit_chat_archive))
        composeTestRule.onNode(archive).assertIsNotEnabled()

        state.value = HostChannelListState(listOf(chatHost()), chatEditor = openChat())
        composeTestRule.onNode(archive).assertIsEnabled()

        state.value = state.value.copy(chatEditor = openChat(saving = true))
        composeTestRule.onNode(archive).assertIsNotEnabled()
        assertTrue(events.isEmpty())
    }

    @Test
    fun workspaceRowPencil_inBothSections_namesItsWorkspace_andOpensItsOwnHostAndCwdWithoutFolding() {
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

        val one = hasContentDescription(string(R.string.cd_tree_workspace_edit, "one"))
        composeTestRule.onAllNodes(one).assertCountEquals(1)
        composeTestRule
            .onNode(one)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        // The pencil's own node took the tap: the workspace's conversation is still drawn, nothing folded.
        composeTestRule.onNode(hasText("alpha channel")).assertExists()

        // Two hosts show a workspace called "two"; each pencil addresses its own host.
        val two = hasContentDescription(string(R.string.cd_tree_workspace_edit, "two"))
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("charlie chat"))
        composeTestRule.onAllNodes(two).assertCountEquals(2)
        composeTestRule.onAllNodes(two)[1].performClick()
        composeTestRule.onNode(hasText("charlie chat")).assertExists()

        assertEquals(
            listOf(
                ChannelListEvent.TreeWorkspaceEditTapped("pyrybox", "/w/one"),
                ChannelListEvent.TreeWorkspaceEditTapped("macbook", "/w/two"),
            ),
            events,
        )
    }

    @Test
    fun workspaceRowPlus_onChannelsRowsOnly_namesItsWorkspace_andOpensItsOwnHostAndCwdWithoutFolding() {
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
                channels = listOf(conversation("c2", "charlie channel", "/w/one", true)),
            ),
        )

        // Two hosts show a Channels workspace called "one"; the Chats workspace has no plus.
        val one = hasContentDescription(string(R.string.cd_tree_workspace_new_channel, "one"))
        composeTestRule.onAllNodes(one).assertCountEquals(2)
        composeTestRule.onAllNodes(hasContentDescription(string(R.string.cd_tree_workspace_new_channel, "chats"))).assertCountEquals(0)
        composeTestRule
            .onAllNodes(one)[0]
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        // The plus's own node took the tap: the workspace's channel is still drawn, nothing folded.
        composeTestRule.onNode(hasText("alpha channel")).assertExists()
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText("charlie channel"))
        composeTestRule.onAllNodes(one)[1].performClick()
        composeTestRule.onNode(hasText("charlie channel")).assertExists()

        assertEquals(
            listOf(
                ChannelListEvent.TreeWorkspaceAddTapped("pyrybox", "/w/one"),
                ChannelListEvent.TreeWorkspaceAddTapped("macbook", "/w/one"),
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
            mutableStateOf(HostChannelListState(listOf(channelHost()), createChannel = CreateChannelState("pyrybox", "/w/one")))
        composeTestRule.setContent {
            PyrycodeMobileTheme { ChannelListScreen(hostState = state.value, onEvent = { events += it }) }
        }

        composeTestRule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).performTextInput("  Ops  ")
        composeTestRule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).performTextInput("Be brief.")
        composeTestRule.onNode(hasText("OK")).performClick()

        state.value = state.value.copy(createChannel = CreateChannelState("pyrybox", "/w/one", createFailed = true))
        composeTestRule.onNode(hasText(string(R.string.create_channel_failed))).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).assertTextContains("  Ops  ")

        state.value =
            state.value.copy(
                createChannel = CreateChannelState("pyrybox", "/w/one", createdConversationId = "c9", promptFailed = true),
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

    @Test
    fun channelRowPen_namesItsChannel_andOpensItsOwnTargetWithoutSelecting() {
        setTree(
            entry(serverId = "pyrybox", displayName = "Pyrybox", channels = listOf(conversation("same", "alpha channel", "/w/one", true))),
            entry(serverId = "macbook", displayName = "Macbook", channels = listOf(conversation("same", "bravo channel", "/w/one", true))),
        )

        val bravo = hasContentDescription(string(R.string.cd_tree_channel_edit, "bravo channel"))
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(bravo)
        composeTestRule
            .onNode(bravo)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        // The pen's own node took the tap: it opens neither the thread nor the highlight.
        assertEquals(listOf(ChannelListEvent.TreeChannelEditTapped(HostConversationTarget("macbook", "same"))), events)
        composeTestRule.onAllNodes(hasTestTag(TREE_CHANNEL_ROW_TEST_TAG)).onFirst().assertIsNotSelected()
    }

    private fun openChannel(
        prompt: ChannelPromptReading = ChannelPromptReading.Reading,
        saving: Boolean = false,
        failed: Boolean = false,
        archiveFailed: Boolean = false,
        muted: Boolean = false,
    ) = ChannelEditorState("pyrybox", "c1", "alpha channel", prompt, saving, failed, archiveFailed, muted)

    @Test
    fun editChannelModal_muteRowOpensAtTheHostsFlag_andOkReportsTheToggledValue() {
        val read = ChannelPromptReading.Read("Be brief.", SessionPromptStatus.Matches)
        val state = mutableStateOf(HostChannelListState(listOf(channelHost()), channelEditor = openChannel(read, muted = true)))
        composeTestRule.setContent {
            PyrycodeMobileTheme { ChannelListScreen(hostState = state.value, onEvent = { events += it }) }
        }
        val mute = hasText(string(R.string.edit_channel_mute))

        // The whole row is one checkbox at the touch floor, opening at the host's flag.
        composeTestRule
            .onNode(mute)
            .performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assertHeightIsAtLeast(48.dp)
            .assertIsOn()
        composeTestRule.onNode(hasText("OK")).performClick()
        composeTestRule.onNode(mute).performClick().assertIsOff()
        // A failure keeps the operator's value where they put it.
        state.value = state.value.copy(channelEditor = openChannel(read, failed = true, muted = true))
        composeTestRule.onNode(mute).assertIsOff()
        composeTestRule.onNode(hasText("OK")).performClick()
        composeTestRule.onNode(hasText("Cancel")).performClick()

        assertEquals(
            listOf(
                ChannelListEvent.ChannelEditSubmitted("alpha channel", "Be brief.", muted = true),
                ChannelListEvent.ChannelEditSubmitted("alpha channel", "Be brief.", muted = false),
                ChannelListEvent.ChannelEditDismissed,
            ),
            events,
        )
        assertFalse(events.first().toString().contains("Be brief."))
    }

    @Test
    fun editChannelModal_muteRowOpensUncheckedForAnUnmutedChannel() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ChannelListScreen(hostState = HostChannelListState(listOf(channelHost()), channelEditor = openChannel()), onEvent = {})
            }
        }
        composeTestRule.onNode(hasText(string(R.string.edit_channel_mute))).performScrollTo().assertIsOff()
    }

    @Test
    fun editChannelModal_readsThenShowsThePromptVerbatim_andReportsTheTrimmedNameAndPrompt() {
        val state = mutableStateOf(HostChannelListState(listOf(channelHost()), channelEditor = openChannel()))
        composeTestRule.setContent {
            PyrycodeMobileTheme { ChannelListScreen(hostState = state.value, onEvent = { events += it }) }
        }

        // Prefilled name; the prompt is locked behind a static reading line until it arrives.
        composeTestRule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).assertTextContains("alpha channel")
        composeTestRule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).assertIsNotEnabled()
        composeTestRule.onNode(hasText(string(R.string.edit_channel_prompt_reading))).assertExists()

        composeTestRule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).performTextReplacement("  Ops  ")
        state.value =
            state.value.copy(channelEditor = openChannel(ChannelPromptReading.Read("  Be brief.\n", SessionPromptStatus.Differs)))
        composeTestRule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).assertIsEnabled().assertTextContains("  Be brief.\n")
        composeTestRule.onAllNodes(hasText(string(R.string.edit_channel_prompt_reading))).assertCountEquals(0)
        composeTestRule.onNode(hasText(string(R.string.edit_channel_prompt_next_session))).assertExists()
        // The typed name survived the reading's arrival.
        composeTestRule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).assertTextContains("  Ops  ")

        composeTestRule.onNode(hasText("OK")).performClick()
        composeTestRule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).performTextReplacement("Shorter.")
        composeTestRule.onNode(hasText("OK")).performClick()
        composeTestRule.onNode(hasText("Cancel")).performClick()

        assertEquals(
            listOf(
                ChannelListEvent.ChannelEditSubmitted("Ops", "  Be brief.\n", muted = false),
                ChannelListEvent.ChannelEditSubmitted("Ops", "Shorter.", muted = false),
                ChannelListEvent.ChannelEditDismissed,
            ),
            events,
        )
        assertFalse(events.first().toString().contains("Be brief."))
    }

    @Test
    fun editChannelModal_anUnreadPromptIsNeverReported_andArchiveIgnoresTheFields() {
        val state =
            mutableStateOf(HostChannelListState(listOf(channelHost()), channelEditor = openChannel(ChannelPromptReading.Unavailable)))
        composeTestRule.setContent {
            PyrycodeMobileTheme { ChannelListScreen(hostState = state.value, onEvent = { events += it }) }
        }

        composeTestRule.onNode(hasText(string(R.string.edit_channel_prompt_unavailable))).assertExists()
        composeTestRule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).assertIsNotEnabled()
        composeTestRule.onNode(hasText("OK")).performClick()

        // A blank name disables OK but not Archive.
        composeTestRule.onNodeWithTag(CHANNEL_NAME_FIELD_TAG).performTextReplacement("   ")
        composeTestRule.onNode(hasText("OK")).assertIsNotEnabled()
        composeTestRule.onNode(hasText(string(R.string.edit_channel_archive))).performScrollTo().performClick()

        assertEquals(
            listOf(ChannelListEvent.ChannelEditSubmitted("alpha channel", null, muted = false), ChannelListEvent.ChannelArchiveRequested),
            events,
        )
    }

    @Test
    fun editChannelModal_failuresAreStatic_andOkAndArchiveFollowItsOwnHostAndTheByteLimit() {
        val read = ChannelPromptReading.Read(null, SessionPromptStatus.Matches)
        val state = mutableStateOf(HostChannelListState(listOf(channelHost()), channelEditor = openChannel(read)))
        composeTestRule.setContent {
            PyrycodeMobileTheme { ChannelListScreen(hostState = state.value, onEvent = { events += it }) }
        }
        composeTestRule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).performTextInput("Typed")

        state.value = state.value.copy(channelEditor = openChannel(read, failed = true))
        composeTestRule.onNode(hasText(string(R.string.edit_channel_save_failed))).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).assertTextContains("Typed")

        state.value = state.value.copy(channelEditor = openChannel(read, archiveFailed = true))
        composeTestRule.onNode(hasText(string(R.string.archive_failed))).performScrollTo().assertIsDisplayed()
        composeTestRule.onAllNodes(hasText(string(R.string.edit_channel_save_failed))).assertCountEquals(0)

        val archive = hasText(string(R.string.edit_channel_archive))
        state.value = state.value.copy(channelEditor = openChannel(read, saving = true))
        composeTestRule.onNode(archive).assertIsNotEnabled()

        state.value = state.value.copy(hosts = listOf(channelHost(PyrycodeLinkStatus.Down)), channelEditor = openChannel(read))
        composeTestRule.onNode(hasText("OK")).assertIsNotEnabled()
        composeTestRule.onNode(archive).assertIsNotEnabled()

        state.value = state.value.copy(hosts = listOf(channelHost()))
        composeTestRule.onNode(hasText("OK")).assertIsEnabled()
        composeTestRule.onNodeWithTag(CHANNEL_PROMPT_FIELD_TAG).performTextReplacement("é".repeat(SystemPromptLimit.MAX_BYTES / 2 + 1))
        composeTestRule.onNode(hasText("OK")).assertIsNotEnabled()
        assertTrue(events.isEmpty())
    }

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
        val state = mutableStateOf(HostChannelListState(listOf(chatHost()), workspaceEditor = openWorkspace()))
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
        val state = mutableStateOf(HostChannelListState(listOf(chatHost()), workspaceEditor = openWorkspace()))
        composeTestRule.setContent {
            PyrycodeMobileTheme { ChannelListScreen(hostState = state.value, onEvent = { events += it }) }
        }
        composeTestRule.onNodeWithTag(EDIT_WORKSPACE_NAME_FIELD_TAG).performTextReplacement("Typed")

        state.value = state.value.copy(workspaceEditor = openWorkspace(failed = true))
        composeTestRule.onNode(hasText(string(R.string.edit_workspace_save_failed))).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag(EDIT_WORKSPACE_NAME_FIELD_TAG).assertTextContains("Typed")

        state.value = state.value.copy(hosts = listOf(chatHost(PyrycodeLinkStatus.Down)))
        composeTestRule.onNode(hasText("OK")).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(EDIT_WORKSPACE_NAME_FIELD_TAG).assertTextContains("Typed")

        state.value =
            HostChannelListState(listOf(chatHost()), workspaceEditor = openWorkspace(confirmingArchive = true, archiveFailed = true))
        composeTestRule.onNode(hasText(string(R.string.edit_workspace_archive_failed))).performScrollTo().assertIsDisplayed()
        composeTestRule.onAllNodes(hasText(string(R.string.edit_workspace_save_failed))).assertCountEquals(0)
        assertTrue(events.isEmpty())
    }

    @Test
    fun settingsGear_emitsSettingsTapped() {
        setTree(entry(serverId = "pyrybox", displayName = "Pyrybox"))

        composeTestRule
            .onNode(hasContentDescription(string(R.string.cd_open_settings)))
            .performClick()

        assertEquals(listOf(ChannelListEvent.SettingsTapped), events)
    }

    @Test
    fun archiveEntry_emitsArchiveTapped() {
        setTree(entry(serverId = "pyrybox", displayName = "Pyrybox"))

        composeTestRule
            .onNode(hasContentDescription(string(R.string.cd_open_archive)))
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
            .onNode(hasText("To pair a host, open Settings and choose Pair another server."))
            .assertIsDisplayed()
        composeTestRule.onNode(hasText("Tap + to start a conversation")).assertDoesNotExist()
        composeTestRule
            .onNode(hasContentDescription(string(R.string.cd_open_settings)))
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
