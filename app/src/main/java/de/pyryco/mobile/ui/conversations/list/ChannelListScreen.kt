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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
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
import de.pyryco.mobile.ui.components.CreateChannelModal
import de.pyryco.mobile.ui.components.EditChannelModal
import de.pyryco.mobile.ui.components.EditChatModal
import de.pyryco.mobile.ui.components.EditWorkspaceModal
import de.pyryco.mobile.ui.conversations.components.TreeConversationRow
import de.pyryco.mobile.ui.conversations.components.TreeHostRow
import de.pyryco.mobile.ui.conversations.components.TreeHostSectionRow
import de.pyryco.mobile.ui.host.HostEditorModal
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.workspace.workspaceDisplayName
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
 * is what the device suites' creation helpers do through [de.pyryco.mobile.ui.conversations.components.treeHostChatAddTestTag].
 */
internal const val CHANNEL_LIST_TEST_TAG: String = "channel-list"

/**
 * Where an update-required host's control sends the operator (#1009): the release listing, as an
 * app-authored literal pinned to the published listing, independent of the build's configuration.
 */
internal const val PLAY_STORE_URL: String = "https://play.google.com/store/apps/details?id=de.pyryco.mobile"

// Host containers share one scrollable list and keep the Figma spacing.
private val TreeGutter = 20.dp
private val TreeHostGap = 16.dp

// The outer Scaffold in MainActivity owns the system-bar insets.
private val TreeBottomInset = 16.dp

// Figma's 24dp top inset + 4dp inside its 28dp wrapper put the glyph at 28dp. Centre
// it in a 48dp target, subtracting the 12dp touch slack from that offset and the 16dp
// wrapper-to-rule gap. The first host has no extra padding below the bar's 24dp gap.
private val BarGlyphSize = 24.dp
private val BarTouchSize = 48.dp
private val BarTouchSlack = (BarTouchSize - BarGlyphSize) / 2
private val BarTopGap = 28.dp - BarTouchSlack
private val BarRuleGap = 16.dp - BarTouchSlack
private val BarBottomGap = 24.dp

// Keep 52dp centre spacing so the left targets never overlap (the reference draws 44dp).
private val BarEntryGap = 4.dp

private const val SECTION_RULE_ALPHA = 0.60f

// A daemon-authored identity is clamped before it reaches an item key, for the same reason every render
// path clamps daemon text: `item(key = …)` is evaluated on every recomposition of the list content, so
// an oversized id would be copied on each one instead of truncating once.
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

    /** The fixed toolbar's add control opens the existing scanner/code pairing flow. */
    data object PairHostTapped : ChannelListEvent

    /** A host's Chats-section plus creates a chat on that host. */
    data class TreeHostChatAddTapped(
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
     * The same control on a host that refused this app build as too old (#1009): only an update recovers,
     * so it opens [PLAY_STORE_URL] and never redials. Carries nothing — the store listing needs no host, and
     * the host's daemon-authored minimum version must not reach a link.
     */
    data object TreeHostUpdateTapped : ChannelListEvent

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

    /**
     * The Edit workspace modal's OK, carrying the entered name already trimmed by the component. No ids, for
     * the reason [HostEditNameSubmitted] carries none; the view model applies the label rule.
     */
    data class WorkspaceEditNameSubmitted(
        val name: String,
    ) : ChannelListEvent

    /** The Edit workspace modal's Cancel, Close and Back from the editor. */
    data object WorkspaceEditDismissed : ChannelListEvent

    /** The modal's Archive workspace, which asks for a confirmation in place rather than archiving. */
    data object WorkspaceArchiveRequested : ChannelListEvent

    /** The archive confirmation accepted, through the shell's own OK. */
    data object WorkspaceArchiveConfirmed : ChannelListEvent

    /**
     * The archive confirmation backed out of, through any of the shell's dismissal routes while it is up —
     * distinct from [WorkspaceEditDismissed] because it returns to the editor instead of closing.
     */
    data object WorkspaceArchiveDeclined : ChannelListEvent

    /** A host's Channels-section plus opens Create channel in that daemon workspace's default folder. */
    data class TreeHostChannelAddTapped(
        val serverId: String,
    ) : ChannelListEvent

    /**
     * The Create channel modal's OK: the name already trimmed by the component, the prompt verbatim. No ids,
     * for the reason [HostEditNameSubmitted] carries none: the target is the open modal's.
     */
    data class CreateChannelSubmitted(
        val name: String,
        val systemPrompt: String,
    ) : ChannelListEvent {
        // The prompt may hold a pasted credential; a logged or crash-traced event must not carry it.
        override fun toString(): String = "CreateChannelSubmitted(name=$name, systemPrompt=<redacted>)"
    }

    /** The Create channel modal's Cancel, Close and Back. */
    data object CreateChannelDismissed : ChannelListEvent

    /**
     * A Channels row's pen: open Edit channel on **that** row's own host and conversation (#667). The name
     * is read from the host's own snapshot, never from the row's text.
     */
    data class TreeChannelEditTapped(
        val target: HostConversationTarget,
    ) : ChannelListEvent

    /**
     * The Edit channel modal's OK: the name already trimmed by the component, the prompt verbatim — or `null`
     * when the modal never showed a stored prompt — and the Mute notifications checkbox as it stands (#1021).
     * No ids: the target is the open modal's.
     */
    data class ChannelEditSubmitted(
        val name: String,
        val systemPrompt: String?,
        val muted: Boolean,
    ) : ChannelListEvent {
        // The prompt may hold a pasted credential; a logged or crash-traced event must not carry it.
        override fun toString(): String =
            "ChannelEditSubmitted(name=$name, systemPrompt=${if (systemPrompt == null) "absent" else "<redacted>"}, muted=$muted)"
    }

    /** The Edit channel modal's Archive channel; the target is the open modal's. */
    data object ChannelArchiveRequested : ChannelListEvent

    /** The Edit channel modal's Cancel, Close and Back. */
    data object ChannelEditDismissed : ChannelListEvent
}

/**
 * The conversation tree: each host holds Channels and Chats sections with direct conversation rows.
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
    val colors = MaterialTheme.colorScheme
    val snackbarHostState = remember { SnackbarHostState() }
    val createChat = hostState.createChat
    val createChatFailure = stringResource(R.string.create_chat_failed)
    LaunchedEffect(createChat?.requestId, createChat?.failed) {
        if (createChat?.failed == true) snackbarHostState.showSnackbar(createChatFailure)
    }
    Scaffold(
        // The arrival marker goes on the root, above the branch below, so both draws carry it (#736).
        modifier = modifier.testTag(CHANNEL_LIST_TEST_TAG),
        containerColor =
            if (colors.surface.luminance() < 0.5f) {
                colors.scrim.copy(alpha = 0.30f).compositeOver(colors.surface)
            } else {
                colors.surface
            },
        topBar = { ChannelListTopBar(onEvent) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
    WorkspaceEditorModal(hostState = hostState, onEvent = onEvent)
    CreateChannelModalBinding(hostState = hostState, onEvent = onEvent)
    ChannelEditorModal(hostState = hostState, onEvent = onEvent)
}

/**
 * [EditChannelModal] bound to the open [ChannelEditorState] (#667), present exactly while there is one.
 *
 * OK and Archive follow the channel's **own** host's connection, read on every draw as the chat editor does.
 * Both failure strings are static: the shell announces them aloud, and the daemon's message never reaches
 * this screen.
 */
@Composable
private fun ChannelEditorModal(
    hostState: HostChannelListState,
    onEvent: (ChannelListEvent) -> Unit,
) {
    val editor = hostState.channelEditor ?: return
    EditChannelModal(
        conversationId = editor.conversationId,
        initialName = editor.savedName,
        prompt = editor.prompt,
        initialMuted = editor.savedMuted,
        onSubmit = { name, systemPrompt, muted -> onEvent(ChannelListEvent.ChannelEditSubmitted(name, systemPrompt, muted)) },
        onArchiveRequested = { onEvent(ChannelListEvent.ChannelArchiveRequested) },
        onDismissRequest = { onEvent(ChannelListEvent.ChannelEditDismissed) },
        hostAvailable = hostState.isHostConnected(editor.serverId),
        loading = editor.saving,
        error =
            when {
                editor.archiveFailed -> stringResource(R.string.archive_failed)
                editor.failed -> stringResource(R.string.edit_channel_save_failed)
                else -> null
            },
    )
}

/**
 * [CreateChannelModal] bound to the open [CreateChannelState] (#958), present exactly while there is one.
 *
 * OK follows the modal's **own** host's connection, read on every draw as the other bindings do. The name
 * locks once the create is confirmed, since a retry then writes only the prompt. Both failure strings are
 * static: the shell announces them aloud, and the daemon's message never reaches this screen.
 */
@Composable
private fun CreateChannelModalBinding(
    hostState: HostChannelListState,
    onEvent: (ChannelListEvent) -> Unit,
) {
    val state = hostState.createChannel ?: return
    CreateChannelModal(
        serverId = state.serverId,
        cwd = state.cwd,
        onSubmit = { name, systemPrompt -> onEvent(ChannelListEvent.CreateChannelSubmitted(name, systemPrompt)) },
        onDismissRequest = { onEvent(ChannelListEvent.CreateChannelDismissed) },
        hostAvailable = hostState.isHostConnected(state.serverId),
        nameEditable = state.createdConversationId == null,
        loading = state.saving,
        error =
            when {
                state.createFailed -> stringResource(R.string.create_channel_failed)
                state.promptFailed -> stringResource(R.string.create_channel_prompt_failed)
                else -> null
            },
    )
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
 * [EditWorkspaceModal] bound to the open [WorkspaceEditorState] (#905), present exactly while there is one.
 *
 * OK and Archive follow the workspace's **own** host's connection, read on every draw as the chat editor
 * does. The folder's own name comes from the same display rule the row uses, with no label. Both failure
 * strings are static: the shell announces them aloud, and the daemon's message never reaches this screen.
 */
@Composable
private fun WorkspaceEditorModal(
    hostState: HostChannelListState,
    onEvent: (ChannelListEvent) -> Unit,
) {
    val editor = hostState.workspaceEditor ?: return
    EditWorkspaceModal(
        serverId = editor.serverId,
        cwd = editor.cwd,
        initialName = editor.initialName,
        folderName = workspaceDisplayName(editor.cwd, label = null),
        onDismissRequest = { onEvent(ChannelListEvent.WorkspaceEditDismissed) },
        onSubmit = { name -> onEvent(ChannelListEvent.WorkspaceEditNameSubmitted(name)) },
        onArchiveRequested = { onEvent(ChannelListEvent.WorkspaceArchiveRequested) },
        onArchiveConfirmed = { onEvent(ChannelListEvent.WorkspaceArchiveConfirmed) },
        onArchiveDeclined = { onEvent(ChannelListEvent.WorkspaceArchiveDeclined) },
        hostAvailable = hostState.isHostConnected(editor.serverId),
        loading = editor.saving,
        error =
            when {
                editor.archiveFailed -> stringResource(R.string.edit_workspace_archive_failed)
                editor.failed -> stringResource(R.string.edit_workspace_save_failed)
                else -> null
            },
        confirmingArchive = editor.confirmingArchive,
    )
}

/**
 * The list's fixed, titleless bar: Settings and Archive at the left, host pairing at the right,
 * and the rule that closes the bar.
 *
 * It lives in the `Scaffold`'s `topBar` slot rather than in the tree's scroll container, so it draws above the
 * branch on `hostState.hosts` and is therefore carried by both the empty placeholder and the assembled tree.
 *
 * No window insets of its own, unlike the `TopAppBar` it replaces: the outer `Scaffold` in `MainActivity`
 * already pads the whole `PyryNavHost` past the system bars, and the old bar applied its own on top of that.
 */
@Composable
private fun ChannelListTopBar(onEvent: (ChannelListEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier.fillMaxWidth().padding(
                    start = TreeGutter - BarTouchSlack,
                    end = TreeGutter - BarTouchSlack,
                    top = BarTopGap,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(BarEntryGap)) {
                ChannelListBarEntry(
                    icon = Icons.Default.Settings,
                    label = stringResource(R.string.cd_open_settings),
                    onClick = { onEvent(ChannelListEvent.SettingsTapped) },
                )
                ChannelListBarEntry(
                    // Keep the existing Material approximation of Figma's FontAwesome archive glyph.
                    icon = Icons.Default.Archive,
                    label = stringResource(R.string.cd_open_archive),
                    onClick = { onEvent(ChannelListEvent.ArchiveTapped) },
                )
            }
            ChannelListBarEntry(
                // The toolbar uses the same Material plus as the existing tree add controls.
                icon = Icons.Default.Add,
                label = stringResource(R.string.cd_pair_another_host),
                onClick = { onEvent(ChannelListEvent.PairHostTapped) },
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
            thickness = 1.dp,
            color = sidebarRuleColor(),
        )
    }
}

@Composable
private fun sidebarRuleColor(): Color {
    val colors = MaterialTheme.colorScheme
    return (if (colors.surface.luminance() < 0.5f) colors.inversePrimary else colors.outlineVariant)
        .copy(alpha = SECTION_RULE_ALPHA)
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
        hostState.hosts.forEachIndexed { index, entry ->
            treeHost(index, entry, hostState, onEvent)
        }
    }
}

/**
 * Emits a host once, followed by its two independent sections and direct conversation rows.
 *
 * A conversation row's tap target is built from the row's **own** `serverId`, so a tree drawing rows
 * from several hosts opens each on the host that owns it.
 */
private fun LazyListScope.treeHost(
    index: Int,
    entry: HostChannelListEntry,
    hostState: HostChannelListState,
    onEvent: (ChannelListEvent) -> Unit,
) {
    val host = entry.host
    val hostKey = TreeFoldKey(ConversationTreeSection.Host, host.serverId)
    item(key = treeItemKey("host", host.serverId)) {
        TreeHostRow(
            serverId = host.serverId,
            hostName = host.displayName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.unnamed_host),
            connectionStatus = host.connectionStatus,
            expanded = hostKey !in hostState.collapsed,
            onToggleExpanded = { onEvent(ChannelListEvent.TreeFoldToggled(hostKey)) },
            onEditTapped = { onEvent(ChannelListEvent.TreeHostEditTapped(host.serverId)) },
            modifier = Modifier.padding(top = if (index == 0) 0.dp else TreeHostGap),
            onReconnectTapped = {
                onEvent(
                    when (host.connectionStatus.relay) {
                        RelayLinkStatus.PairingRejected -> ChannelListEvent.TreeHostRePairTapped(host.serverId)
                        is RelayLinkStatus.UpdateRequired -> ChannelListEvent.TreeHostUpdateTapped
                        else -> ChannelListEvent.TreeHostReconnectTapped(host.serverId)
                    },
                )
            },
        )
    }
    if (hostKey in hostState.collapsed) return
    for (section in listOf(ConversationTreeSection.Channels, ConversationTreeSection.Chats)) {
        val sectionKey = TreeFoldKey(section, host.serverId)
        item(key = treeItemKey("section", section.name, host.serverId)) {
            TreeHostSectionRow(
                serverId = host.serverId,
                hostName = host.displayName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.unnamed_host),
                sectionName = stringResource(section.titleRes),
                expanded = sectionKey !in hostState.collapsed,
                onToggleExpanded = { onEvent(ChannelListEvent.TreeFoldToggled(sectionKey)) },
                isChat = section == ConversationTreeSection.Chats,
                onAddTapped =
                    if (section == ConversationTreeSection.Channels) {
                        { onEvent(ChannelListEvent.TreeHostChannelAddTapped(host.serverId)) }
                    } else {
                        { onEvent(ChannelListEvent.TreeHostChatAddTapped(host.serverId)) }
                    },
            )
        }
        if (sectionKey in hostState.collapsed) continue
        val conversations = if (section == ConversationTreeSection.Channels) host.channels else host.chats
        items(
            items = conversations,
            key = { conversation -> treeItemKey("conversation", section.name, host.serverId, conversation.id) },
        ) { conversation ->
            val target = HostConversationTarget(host.serverId, conversation.id)
            TreeConversationRow(
                conversationName =
                    conversation.name?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.untitled_discussion),
                selected = target == hostState.selected,
                onClick = { onEvent(ChannelListEvent.TreeRowTapped(target)) },
                modifier = Modifier.testTag(section.rowTestTag),
                attention = entry.attentionFor(conversation.id),
                onEditTapped =
                    when (section) {
                        ConversationTreeSection.Host -> error("Host is not a conversation section")
                        ConversationTreeSection.Channels -> {
                            { onEvent(ChannelListEvent.TreeChannelEditTapped(target)) }
                        }
                        ConversationTreeSection.Chats -> {
                            { onEvent(ChannelListEvent.TreeChatEditTapped(target)) }
                        }
                    },
                editDescription =
                    when (section) {
                        ConversationTreeSection.Host -> error("Host is not a conversation section")
                        ConversationTreeSection.Channels -> R.string.cd_tree_channel_edit
                        ConversationTreeSection.Chats -> R.string.cd_tree_chat_edit
                    },
            )
        }
    }
}

private val ConversationTreeSection.titleRes: Int
    get() =
        when (this) {
            ConversationTreeSection.Host -> error("Host has no section title")
            ConversationTreeSection.Channels -> R.string.channels_section_header
            ConversationTreeSection.Chats -> R.string.chats_section_header
        }

private val ConversationTreeSection.rowTestTag: String
    get() =
        when (this) {
            ConversationTreeSection.Host -> error("Host has no conversation rows")
            ConversationTreeSection.Channels -> TREE_CHANNEL_ROW_TEST_TAG
            ConversationTreeSection.Chats -> TREE_CHAT_ROW_TEST_TAG
        }

/**
 * Builds a `LazyColumn` item key out of daemon-authored identities.
 *
 * Each part is length-prefixed, so no `serverId` or conversation id can forge another row's key
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
        collapsed = setOf(TreeFoldKey(ConversationTreeSection.Host, "macbook")),
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
