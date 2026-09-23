package de.pyryco.mobile.ui.conversations.list

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.ui.components.AddWorkspaceModal
import de.pyryco.mobile.ui.components.EditChatModal
import de.pyryco.mobile.ui.conversations.components.TreeConversationRow
import de.pyryco.mobile.ui.conversations.components.TreeHostRow
import de.pyryco.mobile.ui.conversations.components.TreeSectionHeader
import de.pyryco.mobile.ui.conversations.components.TreeWorkspaceRow
import de.pyryco.mobile.ui.host.HostEditorModal
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
 * action button's content description, which #738 retired along with the button and the flat state case that
 * gated it. A marker bound to either would have needed migrating again; this one did not.
 *
 * Weaker than the button wait it replaced, on purpose — it matches the blank placeholder as readily as the
 * assembled tree. A caller that needs the loaded list must wait for the control it is about to drive, which
 * is what the device suites' creation helpers do through [de.pyryco.mobile.ui.conversations.components.treeHostAddTestTag].
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

// The 88dp that kept the floating action button off the tree's last row went with the button (#738). What
// remains is the air the last row needs not to sit flush against the screen's bottom edge — the outer
// Scaffold in MainActivity already pads the whole nav host past the system bars.
private val TreeBottomInset = 16.dp

// The list's own bar (#737). The supplied design draws 24dp glyphs with their centres 32dp and 84dp from the
// screen edge, a rule 20dp below them and 28dp of air between that rule and the first section header. Touch
// needs 48dp, the same minimum the tree rows already hold to — and wrapping a 24dp glyph in a 48dp target adds
// `BarTouchSlack` on every side of it. So each of the design's offsets is taken less that slack, which puts
// both glyphs exactly where the design puts them while giving each entry a target a thumb can hit.
private val BarGlyphSize = 24.dp
private val BarTouchSize = 48.dp
private val BarTouchSlack = (BarTouchSize - BarGlyphSize) / 2
private val BarTopGap = 24.dp - BarTouchSlack
private val BarRuleGap = 20.dp - BarTouchSlack
private val BarBottomGap = 28.dp

// 52dp between the glyph centres, less the two 48dp targets they sit in.
private val BarEntryGap = 4.dp

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

    /** The list's own archive entry — the same destination Settings' archived-discussions row opens (#737). */
    data object ArchiveTapped : ChannelListEvent

    /**
     * A section header's add control: pair an additional host (#738).
     *
     * Carries no section, deliberately. Both headers open the same existing pairing flow, so a section
     * identifier would establish no capability the route could act on; the section is what each control's
     * *name* disambiguates, for the operator and for TalkBack.
     */
    data object PairHostTapped : ChannelListEvent

    /** A host row's add control: a chat on **that** row's host, in its default workspace. */
    data class TreeHostAddTapped(
        val serverId: String,
    ) : ChannelListEvent

    /** The same control held: open Add workspace on **that** row's host (#904), a folder before the chat. */
    data class TreeHostAddLongPressed(
        val serverId: String,
    ) : ChannelListEvent

    /** A host row's edit control: open the Edit host modal on **that** row's host (#744). */
    data class TreeHostEditTapped(
        val serverId: String,
    ) : ChannelListEvent

    /**
     * A disconnected host row's plug control: redial **that** row's host and no other (#840). Carries the
     * `serverId` only — never the displayed name, which the daemon authors.
     */
    data class TreeHostReconnectTapped(
        val serverId: String,
    ) : ChannelListEvent

    /**
     * The same plug control on a host whose saved pairing was rejected (#842): a retry cannot succeed, so
     * it opens re-pairing for **that** host. Carries the `serverId` only, like its retry sibling.
     */
    data class TreeHostRePairTapped(
        val serverId: String,
    ) : ChannelListEvent

    /**
     * The open modal's OK, carrying the entered name already trimmed by the component.
     *
     * No `serverId`, deliberately: the target is the open editor's, held in the view model, and a second
     * id on the event would be a second source of truth for which host is being renamed.
     */
    data class HostEditNameSubmitted(
        val name: String,
    ) : ChannelListEvent

    /** The modal's Cancel, Close and Back, which the shell routes through one dismissal callback. */
    data object HostEditDismissed : ChannelListEvent

    /**
     * The open modal's `Unpair host` action (#745), which asks for a confirmation rather than removing.
     *
     * None of these three carries a `serverId`, for the reason [HostEditNameSubmitted] carries none: the
     * target is the open editor's, held in the view model, and it is the *exact* id the modal was opened
     * for. Re-resolving it here would be a second source of truth for which host is being removed.
     */
    data object HostUnpairRequested : ChannelListEvent

    /** The confirmation accepted, through the shell's own OK. */
    data object HostUnpairConfirmed : ChannelListEvent

    /**
     * The confirmation backed out of, through any of the shell's dismissal routes while it is up.
     *
     * Distinct from [HostEditDismissed] because the outcomes differ: a dismissal closes the modal, a
     * decline returns to the editor with the typed name still in its field.
     */
    data object HostUnpairDeclined : ChannelListEvent

    /**
     * A Chats row's pencil: open the Edit chat modal on **that** row's own host and conversation (#827).
     * Ids only — the view model reads the name from that host's own snapshot.
     */
    data class TreeChatEditTapped(
        val target: HostConversationTarget,
    ) : ChannelListEvent

    /**
     * The Edit chat modal's OK, carrying the entered name already trimmed by the component. No ids, for
     * the reason [HostEditNameSubmitted] carries none: the target is the open editor's.
     */
    data class ChatEditNameSubmitted(
        val name: String,
    ) : ChannelListEvent

    /** The Edit chat modal's Cancel, Close and Back. */
    data object ChatEditDismissed : ChannelListEvent

    /**
     * The Edit chat modal's Archive chat (#828). No ids and no name: the target is the open editor's, and
     * archiving never depends on what the name field holds.
     */
    data object ChatArchiveRequested : ChannelListEvent

    /**
     * An Add workspace row selected (#904): the row's raw path, never its displayed text. No `serverId`,
     * for the reason [HostEditNameSubmitted] carries none: the target is the open modal's.
     */
    data class AddWorkspaceSelected(
        val path: String,
    ) : ChannelListEvent

    /** The new-folder dialog's name, already trimmed; the folder is created on the open modal's host. */
    data class AddWorkspaceFolderCreateRequested(
        val name: String,
    ) : ChannelListEvent

    /** The Add workspace modal's OK: start a chat in the selected folder. */
    data object AddWorkspaceSubmitted : ChannelListEvent

    /** The Add workspace modal's Cancel, Close and Back. */
    data object AddWorkspaceDismissed : ChannelListEvent
}

/**
 * The conversation tree: a Channels section and a Chats section, each holding host rows, their
 * workspace rows and those workspaces' conversation rows.
 *
 * Stateless, and fed by exactly one model: [hostState] carries the host-qualified rows (#729), the
 * collapsed nodes, the last-opened target and the open modals' targets. The flat `ChannelListUiState`
 * went with the floating action button that was its last consumer (#738), taking the loading and error
 * placeholders with it — a tree with no hosts is the only blank the list draws. The generic top app bar
 * is gone too; the list draws its own bar instead (#737, [ChannelListTopBar]).
 */
@Composable
fun ChannelListScreen(
    hostState: HostChannelListState,
    onEvent: (ChannelListEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        // The arrival marker goes on the root, above the branch below, so both draws carry it (#736).
        modifier = modifier.testTag(CHANNEL_LIST_TEST_TAG),
        topBar = { ChannelListTopBar(onEvent) },
    ) { inner ->
        val bodyModifier = Modifier.padding(inner)
        if (hostState.hosts.isEmpty()) {
            // No host at all is the only blank tree: a paired host with no conversations still draws its
            // own rows, which is content rather than an empty screen.
            CenteredText(stringResource(R.string.channel_list_empty), bodyModifier)
        } else {
            ConversationTree(hostState = hostState, onEvent = onEvent, modifier = bodyModifier)
        }
    }
    AddWorkspaceModalBinding(hostState = hostState, onEvent = onEvent)
    // The shared binding (#751), which owns the presence rule, the loading flag and the failure-string
    // resolution this screen used to spell out — Settings draws the same editor through the same call.
    HostEditorModal(
        state = hostState.hostEditor,
        onSubmit = { name -> onEvent(ChannelListEvent.HostEditNameSubmitted(name)) },
        onUnpairRequested = { onEvent(ChannelListEvent.HostUnpairRequested) },
        onUnpairConfirmed = { onEvent(ChannelListEvent.HostUnpairConfirmed) },
        onUnpairDeclined = { onEvent(ChannelListEvent.HostUnpairDeclined) },
        onDismissRequest = { onEvent(ChannelListEvent.HostEditDismissed) },
    )
    ChatEditorModal(hostState = hostState, onEvent = onEvent)
}

/**
 * [AddWorkspaceModal] bound to the open [AddWorkspaceState] (#904), present exactly while there is one.
 *
 * OK follows the modal's **own** host's connection, read from that host's snapshot on every draw, so a
 * disconnect disables it without closing the modal or losing the selection. Both failure strings are
 * static: the shell announces them aloud, and the daemon's message never reaches this screen.
 */
@Composable
private fun AddWorkspaceModalBinding(
    hostState: HostChannelListState,
    onEvent: (ChannelListEvent) -> Unit,
) {
    val state = hostState.addWorkspace ?: return
    AddWorkspaceModal(
        recent = hostState.addWorkspaceRecent,
        selected = state.selected,
        onSelect = { path -> onEvent(ChannelListEvent.AddWorkspaceSelected(path)) },
        onCreateFolder = { name -> onEvent(ChannelListEvent.AddWorkspaceFolderCreateRequested(name)) },
        onDismissRequest = { onEvent(ChannelListEvent.AddWorkspaceDismissed) },
        onSubmit = { onEvent(ChannelListEvent.AddWorkspaceSubmitted) },
        hostAvailable = hostState.isHostConnected(state.serverId),
        loading = state.busy,
        error =
            when {
                state.createFailed -> stringResource(R.string.add_workspace_create_failed)
                state.startFailed -> stringResource(R.string.add_workspace_start_failed)
                else -> null
            },
    )
}

/**
 * [EditChatModal] bound to the open [ChatEditorState] (#827), present exactly while there is one.
 *
 * OK follows the chat's **own** host: availability is read from that host's snapshot on every draw, so a
 * disconnect disables OK and a reconnect re-enables it without the modal leaving composition — the typed
 * name lives in the component's buffer and survives both. The failure string is resolved here and is
 * generic by design: the shell announces it aloud, and the daemon's message never reaches this screen.
 * Archive (#828) reads the same availability and in-flight flag; its failure resolves the thread's own
 * generic archive string rather than the rename's.
 */
@Composable
private fun ChatEditorModal(
    hostState: HostChannelListState,
    onEvent: (ChannelListEvent) -> Unit,
) {
    val editor = hostState.chatEditor ?: return
    EditChatModal(
        conversationId = editor.conversationId,
        initialName = editor.initialName,
        onDismissRequest = { onEvent(ChannelListEvent.ChatEditDismissed) },
        onSubmit = { name -> onEvent(ChannelListEvent.ChatEditNameSubmitted(name)) },
        onArchiveRequested = { onEvent(ChannelListEvent.ChatArchiveRequested) },
        hostAvailable = hostState.isHostConnected(editor.serverId),
        loading = editor.saving,
        error =
            when {
                editor.archiveFailed -> stringResource(R.string.archive_failed)
                editor.failed -> stringResource(R.string.edit_chat_save_failed)
                else -> null
            },
    )
}

/**
 * The list's own bar: a settings entry at the leading content edge, an archive entry beside it, and the rule
 * that closes the bar (#737). The generic top app bar the supplied design marks hidden took the app name and
 * the Pyry logo with it — the bar holds these two entries and the rule, nothing else.
 *
 * It lives in the `Scaffold`'s `topBar` slot rather than in the tree's scroll container, so it draws above the
 * branch on `hostState.hosts` and is therefore carried by all four of the screen's draws: the loading and
 * error texts, the empty placeholder and the assembled tree.
 *
 * No window insets of its own, unlike the `TopAppBar` it replaces: the outer `Scaffold` in `MainActivity`
 * already pads the whole `PyryNavHost` past the system bars, and the old bar applied its own on top of that.
 */
@Composable
private fun ChannelListTopBar(onEvent: (ChannelListEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = TreeGutter - BarTouchSlack, top = BarTopGap),
            horizontalArrangement = Arrangement.spacedBy(BarEntryGap),
        ) {
            ChannelListBarEntry(
                icon = Icons.Default.Settings,
                // Carried forward verbatim: the live archive/restore scenario reaches Settings by this
                // description, mirrored in its own CD_OPEN_SETTINGS constant.
                label = stringResource(R.string.cd_open_settings),
                onClick = { onEvent(ChannelListEvent.SettingsTapped) },
            )
            ChannelListBarEntry(
                // The design draws FontAwesome's `box-archive-solid`. Matched to the Material icon for the
                // same affordance, as the tree rows matched theirs (`Dns` for a host, `FolderOpen` for a
                // workspace), rather than vendoring a drawable: same lidded-box silhouette, and the name says
                // what the entry does. `Inventory2` is the closer silhouette — a slot where this has an
                // arrow — but it reads as inventory, not archive, at the call site.
                icon = Icons.Default.Archive,
                label = stringResource(R.string.cd_open_archive),
                onClick = { onEvent(ChannelListEvent.ArchiveTapped) },
            )
        }
        HorizontalDivider(
            modifier =
                Modifier.padding(
                    start = TreeGutter,
                    end = TreeGutter,
                    top = BarRuleGap,
                    bottom = BarBottomGap,
                ),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = SECTION_RULE_ALPHA),
        )
    }
}

/** One bar entry: the design's 24dp glyph centred in a target touch can actually hit. */
@Composable
private fun ChannelListBarEntry(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(BarTouchSize)) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(BarGlyphSize),
        )
    }
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
        TreeSectionHeader(
            title = stringResource(section.titleRes),
            onAddTapped = { onEvent(ChannelListEvent.PairHostTapped) },
        )
    }
    hostState.hosts.forEachIndexed { index, entry ->
        val host = entry.host
        val hostKey = TreeFoldKey(section, host.serverId)
        item(key = treeItemKey("host", section.name, host.serverId)) {
            TreeHostRow(
                serverId = host.serverId,
                hostName = host.displayName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.unnamed_host),
                connectionStatus = host.connectionStatus,
                expanded = hostKey !in hostState.collapsed,
                onToggleExpanded = { onEvent(ChannelListEvent.TreeFoldToggled(hostKey)) },
                // The row's own host, as with a conversation row's target: the tree draws rows from every
                // host, so a globally selected one would edit or create the chat on the wrong machine.
                onEditTapped = { onEvent(ChannelListEvent.TreeHostEditTapped(host.serverId)) },
                onAddTapped = { onEvent(ChannelListEvent.TreeHostAddTapped(host.serverId)) },
                onAddLongPressed = { onEvent(ChannelListEvent.TreeHostAddLongPressed(host.serverId)) },
                modifier = Modifier.padding(top = if (index == 0) TreeFirstHostGap else TreeHostGap),
                onReconnectTapped = {
                    onEvent(
                        if (host.connectionStatus.relay == RelayLinkStatus.PairingRejected) {
                            ChannelListEvent.TreeHostRePairTapped(host.serverId)
                        } else {
                            ChannelListEvent.TreeHostReconnectTapped(host.serverId)
                        },
                    )
                },
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
                    // The row's own target, as for its tap. Only chats: editing a channel is #667.
                    onEditTapped =
                        when (section) {
                            ConversationTreeSection.Channels -> null
                            ConversationTreeSection.Chats -> {
                                { onEvent(ChannelListEvent.TreeChatEditTapped(target)) }
                            }
                        },
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

@Preview(name = "Tree — Light", showBackground = true, widthDp = 412, heightDp = 900)
@Composable
private fun ChannelListScreenTreePreview() {
    val now: Instant = Clock.System.now()
    PyrycodeMobileTheme(darkTheme = false) {
        ChannelListScreen(hostState = previewHostState(now), onEvent = {})
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
        ChannelListScreen(hostState = previewHostState(now), onEvent = {})
    }
}

@Preview(name = "No hosts — Light", showBackground = true, widthDp = 412)
@Composable
private fun ChannelListScreenEmptyPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        ChannelListScreen(hostState = HostChannelListState(), onEvent = {})
    }
}
