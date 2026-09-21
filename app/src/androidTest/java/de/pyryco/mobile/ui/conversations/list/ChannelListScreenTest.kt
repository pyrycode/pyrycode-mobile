package de.pyryco.mobile.ui.conversations.list

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
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
    ): HostChannelListEntry =
        HostChannelListEntry(
            host =
                HostConversationSnapshot(
                    serverId = serverId,
                    displayName = displayName,
                    connectionStatus = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected),
                    channels = channels,
                    chats = chats,
                ),
            recentChats = chats.take(3),
            chatCount = chats.size,
            channelGroups = groupConversationsByWorkspace(serverId, channels),
            chatGroups = groupConversationsByWorkspace(serverId, chats),
        )

    private fun loaded(): ChannelListUiState.Loaded =
        ChannelListUiState.Loaded(
            channels = emptyList(),
            recentDiscussions = emptyList(),
            recentDiscussionsCount = 0,
        )

    /**
     * Renders the stateless screen over a fold-state holder that behaves as the ViewModel does, so a
     * tap on a fold control really does hide or restore the subtree under it.
     */
    private fun setTree(
        vararg hosts: HostChannelListEntry,
        selected: HostConversationTarget? = null,
    ) {
        composeTestRule.setContent {
            var hostState by remember { mutableStateOf(HostChannelListState(hosts.toList(), selected = selected)) }
            PyrycodeMobileTheme {
                ChannelListScreen(
                    state = loaded(),
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
                    },
                )
            }
        }
    }

    private fun string(resId: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId)

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

    @Test
    fun topAppBar_rendersTitleAndSettingsAction_whenLoaded() {
        setTree(entry(serverId = "pyrybox", displayName = "Pyrybox"))

        composeTestRule.onNode(hasText(string(R.string.app_name))).assertIsDisplayed()
        composeTestRule
            .onNode(hasContentDescription(string(R.string.cd_open_settings)))
            .assertIsDisplayed()
    }

    @Test
    fun fab_emitsCreateDiscussionTapped() {
        setTree(entry(serverId = "pyrybox", displayName = "Pyrybox"))

        composeTestRule
            .onNode(hasContentDescription(string(R.string.cd_new_discussion)))
            .performClick()

        assertEquals(listOf(ChannelListEvent.CreateDiscussionTapped), events)
    }

    @Test
    fun settingsGear_emitsSettingsTapped() {
        setTree(entry(serverId = "pyrybox", displayName = "Pyrybox"))

        composeTestRule
            .onNode(hasContentDescription(string(R.string.cd_open_settings)))
            .performClick()

        assertEquals(listOf(ChannelListEvent.SettingsTapped), events)
    }

    /**
     * The arrival marker (#736) is set once, on the screen's root, so every draw carries it. This walks the
     * same composition through all four — loading, error, the empty placeholder and the tree — asserting on
     * each that exactly one node carries it, and that the draw really is the one intended: a marker that
     * matched while the screen kept rendering the same thing would prove nothing.
     */
    @Test
    fun arrivalMarker_isCarriedByEveryDrawOfTheList_exactlyOnce() {
        var state: ChannelListUiState by mutableStateOf(ChannelListUiState.Loading)
        var hostState: HostChannelListState by mutableStateOf(HostChannelListState())
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                ChannelListScreen(state = state, hostState = hostState, onEvent = {})
            }
        }

        // 1. Loading.
        composeTestRule.onNode(hasText("Loading…")).assertExists()
        assertMarkedOnce()

        // 2. Error — matched on the carried message, so this is the error draw and not the loading one.
        composeTestRule.runOnIdle { state = ChannelListUiState.Error("relay is down") }
        composeTestRule.onNode(hasText("relay is down", substring = true)).assertExists()
        assertMarkedOnce()

        // 3. The empty placeholder: a loaded state with no host at all.
        composeTestRule.runOnIdle {
            state = ChannelListUiState.Empty(recentDiscussions = emptyList(), recentDiscussionsCount = 0)
        }
        composeTestRule.onNode(hasText(string(R.string.channel_list_empty))).assertExists()
        assertMarkedOnce()

        // 4. The tree — a host arrives, so the placeholder gives way to real rows.
        composeTestRule.runOnIdle {
            state = loaded()
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
                ChannelListScreen(
                    state =
                        ChannelListUiState.Empty(
                            recentDiscussions = emptyList(),
                            recentDiscussionsCount = 0,
                        ),
                    hostState = HostChannelListState(),
                    onEvent = {},
                )
            }
        }

        composeTestRule
            .onNode(hasText(string(R.string.channel_list_empty)))
            .assertIsDisplayed()
    }
}
