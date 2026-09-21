package de.pyryco.mobile.ui.conversations.list

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.ui.conversations.components.TreeConversationRow
import de.pyryco.mobile.ui.conversations.components.TreeHostRow
import de.pyryco.mobile.ui.conversations.components.TreeSectionHeader
import de.pyryco.mobile.ui.conversations.components.TreeWorkspaceRow
import de.pyryco.mobile.ui.conversations.components.WorkspacePicker
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The tier a conversation row was drawn in.
 *
 * The two tiers instance the same row component and share every production string, by design, so
 * nothing on the row itself says which section it came from. These tags are the one durable handle the
 * device suites have for reading a conversation's tier off the assembled list (#731); they are
 * app-authored literals, carry no daemon text, and are invisible to accessibility services.
 */
internal const val TREE_CHANNEL_ROW_TEST_TAG: String = "tree-channel-row"

internal const val TREE_CHAT_ROW_TEST_TAG: String = "tree-chat-row"

/**
 * The channel list itself — the device suites' handle for "a paired launch has landed here" (#736).
 *
 * Set once, on the screen's root, so every draw carries it: the loading and error texts, the empty
 * placeholder and the assembled tree. Same shape and same reasoning as the row tags above — an app-authored
 * literal, no daemon text, invisible to accessibility services.
 *
 * It names the destination and nothing drawn on it, deliberately: the suites used to wait for the floating
 * action button's content description, which the chrome work retires (#738), and a marker bound to a visible
 * element or to a [ChannelListUiState] case would need migrating again with it.
 *
 * Weaker than the button wait it replaces, on purpose. The button draws only on [ChannelListUiState.Loaded]
 * and [ChannelListUiState.Empty], so waiting for it implied a loaded list; this matches on all four draws.
 * A caller that needs the loaded list must wait for the control it is about to drive.
 */
internal const val CHANNEL_LIST_TEST_TAG: String = "channel-list"

// The list's own horizontal gutter — the tree rows carry only their own indent (#730). Vertical rhythm
// follows the supplied design: 12dp from a section header to its first host, 16dp between host
// containers, and 28dp of air on each side of the rule between the two sections.
private val TreeGutter = 20.dp
private val TreeFirstHostGap = 8.dp
private val TreeHostGap = 16.dp
private val TreeSectionRuleGap = 28.dp
private val TreeSectionRuleBottomGap = 16.dp

// Keeps the floating action button off the tree's last row.
private val TreeBottomInset = 88.dp

private const val SECTION_RULE_ALPHA = 0.60f

// A daemon-authored identity is clamped before it reaches an item key, for the same reason every render
// path clamps daemon text: `item(key = …)` is evaluated on every recomposition of the list content, so
// an oversized `cwd` or id would be copied on each one instead of truncating once.
private const val MAX_KEY_PART_CHARS = 256

sealed interface ChannelListEvent {
    /** The row's own host, resolved from the row itself — never from the selected-host adapter. */
    data class TreeRowTapped(
        val target: HostConversationTarget,
    ) : ChannelListEvent

    data class TreeFoldToggled(
        val key: TreeFoldKey,
    ) : ChannelListEvent

    data object SettingsTapped : ChannelListEvent

    data object CreateDiscussionTapped : ChannelListEvent

    data object LongPressFab : ChannelListEvent

    data class WorkspacePicked(
        val workspace: String,
    ) : ChannelListEvent

    data object WorkspacePickerDismissed : ChannelListEvent
}

/**
 * The conversation tree: a Channels section and a Chats section, each holding host rows, their
 * workspace rows and those workspaces' conversation rows.
 *
 * Stateless. [hostState] carries the host-qualified rows (#729), the collapsed nodes and the
 * last-opened target; [state] still carries the compatibility loading, empty and error placeholders and
 * the workspace picker's visibility, both of which #732 retires along with the top bar and the button.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChannelListScreen(
    state: ChannelListUiState,
    hostState: HostChannelListState,
    onEvent: (ChannelListEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val newDiscussionLabel = stringResource(R.string.cd_new_discussion)
    val longPressLabel = stringResource(R.string.cd_long_press_fab_pick_workspace)
    Scaffold(
        // The arrival marker goes on the root, above the branch below, so all four draws carry it (#736).
        modifier = modifier.testTag(CHANNEL_LIST_TEST_TAG),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_pyry_logo),
                            contentDescription = stringResource(R.string.cd_pyrycode_logo),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                },
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { onEvent(ChannelListEvent.SettingsTapped) }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.cd_open_settings),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (state is ChannelListUiState.Loaded || state is ChannelListUiState.Empty) {
                ChannelListFab(
                    onTap = { onEvent(ChannelListEvent.CreateDiscussionTapped) },
                    onLongPress = { onEvent(ChannelListEvent.LongPressFab) },
                    onTapLabel = newDiscussionLabel,
                    onLongPressLabel = longPressLabel,
                )
            }
        },
    ) { inner ->
        val bodyModifier = Modifier.padding(inner)
        if (hostState.hosts.isEmpty()) {
            // No host at all is the only blank tree: a paired host with no conversations still draws its
            // own rows, which is content rather than an empty screen.
            when (state) {
                ChannelListUiState.Loading -> CenteredText("Loading…", bodyModifier)
                is ChannelListUiState.Error -> CenteredText("Couldn't load channels: ${state.message}", bodyModifier)
                is ChannelListUiState.Loaded, is ChannelListUiState.Empty ->
                    CenteredText(stringResource(R.string.channel_list_empty), bodyModifier)
            }
        } else {
            ConversationTree(hostState = hostState, onEvent = onEvent, modifier = bodyModifier)
        }
    }
    val pickerVisible =
        when (state) {
            is ChannelListUiState.Loaded -> state.workspacePickerVisible
            is ChannelListUiState.Empty -> state.workspacePickerVisible
            ChannelListUiState.Loading, is ChannelListUiState.Error -> false
        }
    WorkspacePicker(
        visible = pickerVisible,
        onPicked = { path -> onEvent(ChannelListEvent.WorkspacePicked(path)) },
        onDismiss = { onEvent(ChannelListEvent.WorkspacePickerDismissed) },
    )
}

/** Both sections in one scroll container — no nested scroll region, so the last row is reachable. */
@Composable
private fun ConversationTree(
    hostState: HostChannelListState,
    onEvent: (ChannelListEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = TreeGutter, end = TreeGutter, bottom = TreeBottomInset),
    ) {
        treeSection(ConversationTreeSection.Channels, hostState, onEvent)
        item(key = "tree-section-rule") {
            HorizontalDivider(
                modifier = Modifier.padding(top = TreeSectionRuleGap, bottom = TreeSectionRuleBottomGap),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = SECTION_RULE_ALPHA),
            )
        }
        treeSection(ConversationTreeSection.Chats, hostState, onEvent)
    }
}

/**
 * Emits one section's rows: its header, then each host, each of that host's workspaces, and each
 * workspace's conversations — stopping at whichever node the operator folded.
 *
 * A conversation row's tap target is built from the row's **own** `serverId`, so a tree drawing rows
 * from several hosts opens each on the host that owns it.
 */
private fun LazyListScope.treeSection(
    section: ConversationTreeSection,
    hostState: HostChannelListState,
    onEvent: (ChannelListEvent) -> Unit,
) {
    item(key = treeItemKey("header", section.name)) {
        TreeSectionHeader(title = stringResource(section.titleRes))
    }
    hostState.hosts.forEachIndexed { index, entry ->
        val host = entry.host
        val hostKey = TreeFoldKey(section, host.serverId)
        item(key = treeItemKey("host", section.name, host.serverId)) {
            TreeHostRow(
                hostName = host.displayName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.unnamed_host),
                connectionStatus = host.connectionStatus,
                expanded = hostKey !in hostState.collapsed,
                onToggleExpanded = { onEvent(ChannelListEvent.TreeFoldToggled(hostKey)) },
                modifier = Modifier.padding(top = if (index == 0) TreeFirstHostGap else TreeHostGap),
            )
        }
        if (hostKey in hostState.collapsed) return@forEachIndexed
        val groups =
            when (section) {
                ConversationTreeSection.Channels -> entry.channelGroups
                ConversationTreeSection.Chats -> entry.chatGroups
            }
        groups.forEach { group ->
            val workspaceKey = TreeFoldKey(section, group.serverId, group.cwd)
            item(key = treeItemKey("workspace", section.name, group.serverId, group.cwd)) {
                TreeWorkspaceRow(
                    workspaceName = group.displayName,
                    expanded = workspaceKey !in hostState.collapsed,
                    onToggleExpanded = { onEvent(ChannelListEvent.TreeFoldToggled(workspaceKey)) },
                )
            }
            if (workspaceKey in hostState.collapsed) return@forEach
            items(
                items = group.conversations,
                key = { row -> treeItemKey("conversation", section.name, row.serverId, row.conversation.id) },
            ) { row ->
                val target = HostConversationTarget(row.serverId, row.conversation.id)
                TreeConversationRow(
                    conversationName =
                        row.conversation.name?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.untitled_discussion),
                    selected = target == hostState.selected,
                    onClick = { onEvent(ChannelListEvent.TreeRowTapped(target)) },
                    modifier = Modifier.testTag(section.rowTestTag),
                )
            }
        }
    }
}

private val ConversationTreeSection.titleRes: Int
    get() =
        when (this) {
            ConversationTreeSection.Channels -> R.string.channels_section_header
            ConversationTreeSection.Chats -> R.string.chats_section_header
        }

private val ConversationTreeSection.rowTestTag: String
    get() =
        when (this) {
            ConversationTreeSection.Channels -> TREE_CHANNEL_ROW_TEST_TAG
            ConversationTreeSection.Chats -> TREE_CHAT_ROW_TEST_TAG
        }

/**
 * Builds a `LazyColumn` item key out of daemon-authored identities.
 *
 * Each part is length-prefixed, so no `serverId`, `cwd` or conversation id can forge another row's key
 * by embedding the separator — a duplicate key is a crash, not a rendering glitch. An oversized part is
 * clamped with its own length appended, which keeps distinct identities distinct without copying a
 * multi-megabyte string on every recomposition.
 */
private fun treeItemKey(vararg parts: String): String =
    parts.joinToString("|") { part ->
        val bounded =
            if (part.length <= MAX_KEY_PART_CHARS) part else part.take(MAX_KEY_PART_CHARS) + "~" + part.length
        "${bounded.length}|$bounded"
    }

@Composable
private fun CenteredText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChannelListFab(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onTapLabel: String,
    onLongPressLabel: String,
) {
    Surface(
        modifier =
            Modifier
                .size(56.dp)
                .combinedClickable(
                    onClick = onTap,
                    onLongClick = onLongPress,
                    onClickLabel = onTapLabel,
                    onLongClickLabel = onLongPressLabel,
                    role = Role.Button,
                ).semantics { contentDescription = onTapLabel },
        shape = FloatingActionButtonDefaults.shape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null)
        }
    }
}

private fun previewConversation(
    id: String,
    name: String?,
    cwd: String,
    lastUsedAt: Instant,
    promoted: Boolean,
) = Conversation(
    id = id,
    name = name,
    cwd = cwd,
    currentSessionId = "$id-session",
    sessionHistory = emptyList(),
    isPromoted = promoted,
    lastUsedAt = lastUsedAt,
)

private fun previewEntry(
    serverId: String,
    displayName: String?,
    status: ConnectionStatus,
    channels: List<Conversation>,
    chats: List<Conversation>,
) = HostChannelListEntry(
    host = HostConversationSnapshot(serverId, displayName, status, channels, chats),
    recentChats = chats.take(3),
    chatCount = chats.size,
    channelGroups = groupConversationsByWorkspace(serverId, channels),
    chatGroups = groupConversationsByWorkspace(serverId, chats),
)

private fun previewHostState(now: Instant): HostChannelListState {
    val brain = "~/Workspace/Second Brain"
    val mobile = "~/Workspace/Projects/pyrycode-mobile"
    val pyrybox =
        previewEntry(
            serverId = "pyrybox",
            displayName = "Pyrybox",
            status = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected),
            channels =
                listOf(
                    previewConversation("c1", "kitchenclaw refactor", brain, now - 12.minutes, true),
                    previewConversation("c2", "pyrycode discord integration", brain, now - 4.hours, true),
                    previewConversation("c3", "rocd-thinking", mobile, now - 3.days, true),
                ),
            chats =
                listOf(
                    previewConversation("d1", "What's the safest way…", DEFAULT_SCRATCH_CWD, now - 26.minutes, false),
                    previewConversation("d2", null, DEFAULT_SCRATCH_CWD, now - 2.hours, false),
                ),
        )
    val macbook =
        previewEntry(
            serverId = "macbook",
            displayName = null,
            status = ConnectionStatus(RelayLinkStatus.Reconnecting(secondsRemaining = 12), PyrycodeLinkStatus.Down),
            channels = listOf(previewConversation("c4", "Culinary Corner", brain, now - 9.days, true)),
            chats = listOf(previewConversation("d3", "Quick regex for log parsing", DEFAULT_SCRATCH_CWD, now - 9.days, false)),
        )
    return HostChannelListState(
        hosts = listOf(pyrybox, macbook),
        collapsed = setOf(TreeFoldKey(ConversationTreeSection.Channels, "macbook")),
        selected = HostConversationTarget("pyrybox", "c2"),
    )
}

// The tree is fed by hostState; the compatibility state only has to be Loaded for the button to draw.
private fun previewLoaded() =
    ChannelListUiState.Loaded(
        channels = emptyList(),
        recentDiscussions = emptyList(),
        recentDiscussionsCount = 0,
    )

@Preview(name = "Tree — Light", showBackground = true, widthDp = 412, heightDp = 900)
@Composable
private fun ChannelListScreenTreePreview() {
    val now: Instant = Clock.System.now()
    PyrycodeMobileTheme(darkTheme = false) {
        ChannelListScreen(state = previewLoaded(), hostState = previewHostState(now), onEvent = {})
    }
}

@Preview(
    name = "Tree — Dark",
    showBackground = true,
    widthDp = 412,
    heightDp = 900,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ChannelListScreenTreeDarkPreview() {
    val now: Instant = Clock.System.now()
    PyrycodeMobileTheme(darkTheme = true) {
        ChannelListScreen(state = previewLoaded(), hostState = previewHostState(now), onEvent = {})
    }
}

@Preview(name = "No hosts — Light", showBackground = true, widthDp = 412)
@Composable
private fun ChannelListScreenEmptyPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ChannelListScreen(
            state = ChannelListUiState.Empty(recentDiscussions = emptyList(), recentDiscussionsCount = 0),
            hostState = HostChannelListState(),
            onEvent = {},
        )
    }
}
