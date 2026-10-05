package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Power
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.di.ConversationAttention
import de.pyryco.mobile.ui.theme.LocalStaticDarkPalette
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.theme.success
import de.pyryco.mobile.ui.theme.warning
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS

// The tree's four indentation steps, mirroring the supplied design's absolute positions inside its
// `Channels` container. Each row carries only its own tree indent; the screen's horizontal gutter
// belongs to the list that assembles them (#731).
private val HostRowIndent = 0.dp
private val WorkspaceRowIndent = 12.dp
private val ConversationRowIndent = 8.dp
private val HostSectionIndent = 12.dp

// Compact bands follow the sidebar component. Each action keeps a separate named region; the phone
// has no hover, so a visible control shares the same band without expanding its painted height.
private val TreeBandHeight = 28.dp
private val ConversationBandHeight = 24.dp
private val TreeRowEndPadding = 0.dp
private val TreeRowShape = RoundedCornerShape(6.dp)
private val TreeGlyphSize = 12.dp
private val TreeChevronWidth = 8.dp
private val TreeChevronHeight = 4.dp

// Control slots divide the compact row horizontally; their semantics remain distinct.
private val TreeControlWidth = 24.dp
private val TreeGlyphGap = 12.dp
private val TreeNameGap = 6.dp
private val TreeDotSize = 6.dp
private val TreeDotRingWidth = 1.dp

// The update-required caption's gap below its host row (#1009), the frame's own bottom padding.
private val TreeCaptionBottomPadding = 6.dp

// Desktop's working-dot blink (#878): opacity 1 -> 0.3 -> 1 over 2s, eased both ways.
private const val RUNNING_BLINK_MIN_ALPHA = 0.3f
private const val RUNNING_BLINK_HALF_PERIOD_MS = 1000
private const val SECTION_HEADER_ALPHA = 0.85f

// The design fills a selected conversation row with the primary family's next darker step below the
// hover fill. Taken as a literal token that is `onPrimary`, which is white in the light scheme and
// would vanish there, so the fill is `primaryContainer` held back to this alpha over the list's own
// surface: a visible highlight in both schemes that leaves the row's `onSurface` text legible, and
// leaves full-opacity `primaryContainer` free for the brighter tier #665 needs.
private const val SELECTED_FILL_ALPHA = 0.60f

/**
 * Clamps a daemon-authored name before it reaches text layout or a formatted content description.
 *
 * Host names, workspace names and conversation names are authored on the far side of the wire.
 * `workspaceDisplayName` already clamps the workspace label to [MAX_WORKSPACE_LABEL_CHARS], but a
 * host's `displayName` and a conversation's `name` arrive here unbounded, and `maxLines = 1` bounds
 * only what is painted — Compose still measures the whole string, and the fold control's formatted
 * description would hand the whole string to TalkBack. The same bound applied uniformly can never
 * touch a protocol-conformant name and turns an oversized one into a truncation rather than an ANR.
 */
private fun boundedRowText(raw: String): String = raw.take(MAX_WORKSPACE_LABEL_CHARS)

/** The accessible name of a row's fold action, which always names the state a tap would produce. */
@Composable
private fun foldActionLabel(
    expanded: Boolean,
    rowName: String,
): String =
    if (expanded) {
        stringResource(R.string.cd_tree_row_collapse, rowName)
    } else {
        stringResource(R.string.cd_tree_row_expand, rowName)
    }

/**
 * A tree section's header — "Channels", "Chats" — and its add control, which opens the pairing flow for
 * an additional host (#738).
 *
 * [title] is app-authored, so it is not run through [boundedRowText]; it is a resource string the
 * caller resolved, never daemon text. It names the control too: the tree draws one header per section,
 * so two identically-named controls would be ambiguous in the accessibility tree.
 *
 * The band uses the sidebar's 28dp height while the add action owns a separate trailing slot.
 */
@Composable
fun TreeSectionHeader(
    title: String,
    onAddTapped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = TreeBandHeight).padding(end = TreeRowEndPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = SECTION_HEADER_ALPHA),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        TreeRowControl(
            painter = painterResource(R.drawable.ic_tree_add),
            contentDescription = stringResource(R.string.cd_tree_section_pair_host, title),
            onClick = onAddTapped,
            height = TreeBandHeight,
            glyphWidth = 16.dp,
            glyphHeight = 16.dp,
        )
    }
}

/**
 * One host in the tree: a server glyph, the host's name, a fold control, and the edit control that
 * opens the Edit host modal for **this** host (#744). It draws no connection dots (#1333); the thread's
 * `ConnectionStatusLine` still shows both legs.
 *
 * Stateless — [hostName] is display text the caller resolved (a nameless host reads as whatever
 * #731 decides), [expanded] is the caller's flag, and the row reports a fold request back through
 * [onToggleExpanded]. The row resolves nothing: [serverId] is reported straight back through
 * [onEditTapped] and is otherwise used only to name the host's test controls.
 *
 * The phone has no hover, so the edit pencil stays visible.
 *
 * A host whose relay leg [isDisconnected] draws the design's disconnected treatment (#840): glyph and
 * name in the error colour, and a plug control inboard of the pencil that reports through
 * [onReconnectTapped]. The frame binds the name to `errorContainer`, which is near-white on the light
 * surface, so both take `error` instead — error-toned and legible in either scheme.
 *
 * A host that refused this app build as too old (#1009) keeps that treatment but swaps the plug for an
 * update control, still reporting through [onReconnectTapped] so the caller decides what it opens, and
 * adds a caption under the row asking for an update. The caption is
 * the only place the host's daemon-authored minimum version appears — plain text, never a description.
 */
@Composable
fun TreeHostRow(
    serverId: String,
    hostName: String,
    connectionStatus: ConnectionStatus,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onEditTapped: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onReconnectTapped: () -> Unit = {},
) {
    // Clamped once here and reused for the row's own name and for every control label, so no path can
    // format an unbounded daemon-authored name into a content description.
    val bounded = boundedRowText(hostName)
    val disconnected = connectionStatus.relay.isDisconnected()
    val update = connectionStatus.relay as? RelayLinkStatus.UpdateRequired
    Column(modifier = modifier) {
        FoldableTreeRow(
            glyph = painterResource(R.drawable.ic_tree_server),
            glyphWidth = 12.dp,
            glyphHeight = 12.dp,
            name = bounded,
            nameStyle = MaterialTheme.typography.titleSmall,
            startIndent = HostRowIndent,
            expanded = expanded,
            onToggleExpanded = onToggleExpanded,
            accent = if (disconnected) MaterialTheme.colorScheme.error else null,
        ) {
            Spacer(modifier = Modifier.width(4.dp))
            if (update != null) {
                TreeRowControl(
                    // Material's download, matched to the frame's `download-solid` arrow into a tray.
                    painter = rememberVectorPainter(Icons.Filled.Download),
                    contentDescription = stringResource(R.string.cd_tree_host_update, bounded),
                    onClick = onReconnectTapped,
                    modifier = Modifier.testTag(treeHostUpdateTestTag(serverId)),
                    height = TreeBandHeight,
                )
            } else if (disconnected) {
                TreeRowControl(
                    // Retained mobile repair action; the reference has no disconnected-host variant.
                    painter = rememberVectorPainter(Icons.Filled.Power),
                    contentDescription = stringResource(R.string.cd_tree_host_reconnect, bounded),
                    onClick = onReconnectTapped,
                    modifier = Modifier.testTag(treeHostReconnectTestTag(serverId)),
                    height = TreeBandHeight,
                )
            }
            if (onEditTapped != null) {
                TreeRowControl(
                    // The supplied pen path, visible on mobile without hover.
                    painter = painterResource(R.drawable.ic_tree_edit),
                    contentDescription = stringResource(R.string.cd_tree_host_edit, bounded),
                    onClick = onEditTapped,
                    modifier = Modifier.testTag(treeHostEditTestTag(serverId)),
                    height = TreeBandHeight,
                )
            }
        }
        if (update != null) {
            // Inside the host's own item, so folding the host, which drops only the rows below it, keeps it.
            Text(
                text =
                    update.minClientVersion?.let { stringResource(R.string.tree_host_update_required_version, it) }
                        ?: stringResource(R.string.tree_host_update_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.padding(
                        start = HostRowIndent + TreeGlyphSize + TreeGlyphGap,
                        end = TreeRowEndPadding,
                        bottom = TreeCaptionBottomPadding,
                    ),
            )
        }
    }
}

/**
 * The device suites' handles for host controls — an app-authored prefix, the host's own id, and
 * nothing drawn on the row (#736's convention, per-host because the controls repeat).
 *
 * The id comes from the saved `PairedServer` record the operator scanned, not from a daemon frame, but a
 * hostile QR could still make it enormous and a `testTag` is re-evaluated on every recomposition of the
 * row. So it is clamped exactly as `treeItemKey` clamps its parts — truncated with the original length
 * appended, which keeps two ids sharing a prefix from collapsing onto one tag. `testTag` is invisible to
 * accessibility services.
 */
fun treeHostEditTestTag(serverId: String): String = "tree-host-edit:${boundedTagId(serverId)}"

fun treeHostReconnectTestTag(serverId: String): String = "tree-host-reconnect:${boundedTagId(serverId)}"

fun treeHostUpdateTestTag(serverId: String): String = "tree-host-update:${boundedTagId(serverId)}"

fun treeHostChannelAddTestTag(serverId: String): String = "tree-host-channel-add:${boundedTagId(serverId)}"

fun treeHostChatAddTestTag(serverId: String): String = "tree-host-chat-add:${boundedTagId(serverId)}"

/**
 * Fixed Channels or Chats section under one host, each with its own create control. The supplied sidebar
 * frame shows only Channels' plus; #1190's later product decision gives Chats the matching control. A null
 * [onAddTapped] draws no plus: the caller passes null while the host is not connected (#1336).
 */
@Composable
fun TreeHostSectionRow(
    serverId: String,
    hostName: String,
    sectionName: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onAddTapped: (() -> Unit)?,
    isChat: Boolean,
    modifier: Modifier = Modifier,
) {
    val boundedHost = boundedRowText(hostName)
    FoldableTreeRow(
        glyph = painterResource(if (expanded) R.drawable.ic_tree_folder_open else R.drawable.ic_tree_folder),
        glyphWidth = if (expanded) 13.dp else 12.dp,
        glyphHeight = if (expanded) 11.dp else 10.dp,
        glyphGap = if (expanded) 9.dp else 10.dp,
        name = sectionName,
        foldLabelName = stringResource(R.string.tree_host_section_label, sectionName, boundedHost),
        nameStyle = MaterialTheme.typography.titleSmall,
        startIndent = HostSectionIndent,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        modifier = modifier.padding(end = 10.dp),
    ) {
        if (onAddTapped != null) {
            TreeRowControl(
                painter = painterResource(R.drawable.ic_tree_add),
                contentDescription =
                    stringResource(if (isChat) R.string.cd_tree_host_new_chat else R.string.cd_tree_host_new_channel, boundedHost),
                onClick = onAddTapped,
                modifier = Modifier.testTag(if (isChat) treeHostChatAddTestTag(serverId) else treeHostChannelAddTestTag(serverId)),
                height = TreeBandHeight,
                glyphWidth = 16.dp,
                glyphHeight = 16.dp,
            )
        }
    }
}

/**
 * Whether a host row draws the disconnected treatment and its reconnect control (#840).
 *
 * Exhaustive with no `else`, so a relay state added later has to be classified here. [RelayLinkStatus.Idle]
 * is a deliberate background close rather than an error, and a dial in progress is not one either.
 */
internal fun RelayLinkStatus.isDisconnected(): Boolean =
    when (this) {
        is RelayLinkStatus.Reconnecting,
        RelayLinkStatus.Offline,
        RelayLinkStatus.DaemonAbsent,
        RelayLinkStatus.PairingRejected,
        is RelayLinkStatus.UpdateRequired,
        -> true
        RelayLinkStatus.Idle, RelayLinkStatus.Connecting, RelayLinkStatus.Connected -> false
    }

private fun boundedTagId(serverId: String): String =
    if (serverId.length <= MAX_TEST_TAG_ID_CHARS) {
        serverId
    } else {
        serverId.take(MAX_TEST_TAG_ID_CHARS) + "~" + serverId.length
    }

private const val MAX_TEST_TAG_ID_CHARS = 256

/**
 * One workspace under a host: an open-folder glyph, the workspace's name, a fold control and, when
 * [onEditTapped] is supplied, the pencil that opens Edit workspace for it (#905).
 *
 * [workspaceName] is #729's already-resolved `HostWorkspaceGroup.displayName` — display text only,
 * never the `cwd`. The row resolves nothing: the caller binds [onEditTapped] to the group's own host and
 * `cwd`. The pencil is drawn permanently, as the host row's is, since the phone has no hover;
 * it is a [TreeRowControl], so a tap on it edits the workspace without folding the row. The row adds no
 * workspace: that is the host row's plus, held (#904).
 *
 * A non-null [onAddTapped] draws the design's plus after the pencil (#958), the control that creates a
 * channel in this workspace; the caller supplies it on Channels-section rows only.
 */
@Composable
fun TreeWorkspaceRow(
    workspaceName: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
    onEditTapped: (() -> Unit)? = null,
    onAddTapped: (() -> Unit)? = null,
) {
    // Clamped once and reused for the name and the pencil's label, as the host row does.
    val bounded = boundedRowText(workspaceName)
    FoldableTreeRow(
        glyph = painterResource(R.drawable.ic_tree_folder_open),
        glyphWidth = 13.dp,
        glyphHeight = 11.dp,
        name = bounded,
        nameStyle = MaterialTheme.typography.titleSmall,
        startIndent = WorkspaceRowIndent,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        modifier = modifier,
    ) {
        if (onEditTapped != null) {
            TreeRowControl(
                painter = painterResource(R.drawable.ic_tree_edit),
                contentDescription = stringResource(R.string.cd_tree_workspace_edit, bounded),
                onClick = onEditTapped,
                height = TreeBandHeight,
            )
        }
        if (onAddTapped != null) {
            TreeRowControl(
                painter = painterResource(R.drawable.ic_tree_add),
                contentDescription = stringResource(R.string.cd_tree_workspace_new_channel, bounded),
                onClick = onAddTapped,
                height = TreeBandHeight,
                glyphWidth = 16.dp,
                glyphHeight = 16.dp,
            )
        }
    }
}

/**
 * One conversation under its host's Channels or Chats section — the same row for both, as the design
 * instances one component in both sections.
 *
 * The leading status dot draws [attention], the row's one state that #877 resolves by precedence (#878).
 * Under the static dark palette, [selected] draws 15:8's `Hover` fill (`primary-container`) and a pressed
 * row its darker `on-primary` fill (#1523).
 *
 * A non-null [onEditTapped] draws the design's hover pencil at the trailing edge, named through
 * [editDescription]: Edit chat on Chats rows, Edit channel on Channels rows (#667). It is a
 * [TreeRowControl], so a tap on it edits the row without opening it or moving the highlight. No caller
 * passes it since #1563, which matches 15:8's pen-free rows; #1582 removes it.
 */
@Composable
fun TreeConversationRow(
    conversationName: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onEditTapped: (() -> Unit)? = null,
    @StringRes editDescription: Int = R.string.cd_tree_chat_edit,
    attention: ConversationAttention = ConversationAttention.Idle,
) {
    // Clamped once and reused for the name and the pencil's label, as the host row does.
    val bounded = boundedRowText(conversationName)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed = interactionSource.collectIsPressedAsState().value
    val staticDark = LocalStaticDarkPalette.current
    val fill =
        if (selected) {
            if (staticDark) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = SELECTED_FILL_ALPHA)
            }
        } else if (pressed) {
            if (staticDark) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = ConversationBandHeight)
                .clip(TreeRowShape)
                .background(fill)
                .padding(end = TreeRowEndPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
                modifier
                    .weight(1f)
                    .heightIn(min = ConversationBandHeight)
                    .selectable(
                        selected = selected,
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClick = onClick,
                    ).padding(start = ConversationRowIndent),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConversationStatusDot(attention = attention)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = bounded,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (onEditTapped != null) {
            TreeRowControl(
                painter = painterResource(R.drawable.ic_tree_edit),
                contentDescription = stringResource(editDescription, bounded),
                onClick = onEditTapped,
                height = ConversationBandHeight,
            )
        }
    }
}

/**
 * The shared host/workspace row: leading glyph, name, fold chevron, then the caller's [trailing]
 * content.
 *
 * The leading row area is the fold control, so folding works by touch alone with nothing riding on a
 * pointer hovering. The trailing controls have separate, non-overlapping targets. A non-null [accent]
 * recolours the leading glyph and the name, and nothing else. The row deliberately sets no
 * `contentDescription` of its own: `clickable` merges the leading descendants, and an overriding description would replace the chevron's own
 * name. The action is named through `onClickLabel`, and the chevron repeats that name so the
 * control is identifiable in the unmerged tree too.
 */
@Composable
private fun FoldableTreeRow(
    glyph: Painter,
    glyphWidth: Dp,
    glyphHeight: Dp,
    name: String,
    nameStyle: TextStyle,
    startIndent: Dp,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    foldLabelName: String = name,
    glyphGap: Dp = TreeGlyphGap,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val foldLabel = foldActionLabel(expanded = expanded, rowName = foldLabelName)

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = TreeBandHeight)
                .clip(TreeRowShape)
                .padding(end = TreeRowEndPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = TreeBandHeight)
                    .clickable(onClickLabel = foldLabel, role = Role.Button, onClick = onToggleExpanded)
                    .padding(start = startIndent),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = glyph,
                contentDescription = null,
                tint = accent ?: MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(glyphWidth, glyphHeight),
            )
            Spacer(modifier = Modifier.width(glyphGap))
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = nameStyle,
                    color = accent ?: MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(TreeNameGap))
                Icon(
                    painter = painterResource(if (expanded) R.drawable.ic_tree_chevron_down else R.drawable.ic_tree_chevron_right),
                    contentDescription = foldLabel,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier =
                        Modifier.size(
                            if (expanded) TreeChevronWidth else TreeChevronHeight,
                            if (expanded) TreeChevronHeight else TreeChevronWidth,
                        ),
                )
            }
        }
        trailing()
    }
}

/**
 * The trailing row control: each section header's plus, the host row's pencil (#744), a disconnected
 * host's plug (#840), an update-required host's update control (#1009), a chat row's pencil (#827) and a workspace row's pencil
 * (#905) and plus (#958). The body remains glyph-agnostic: the caller supplies the painter and nothing else
 * differs between them.
 *
 * [contentDescription] is the whole accessible name, because the controls repeat down the screen and two
 * of them sit on one row, so each has to say which section or host it acts on and what it does there.
 *
 * The slot is 24dp wide and uses the parent row's 28dp or 24dp height. This matches the visual band and
 * keeps adjacent actions separate, at the cost of a smaller touch region than the usual 48dp guidance.
 *
 * Adjacent to the row's fold or open target, so this stays its own node with its own name, tag and
 * click action and a tap on it never reaches the row's main action.
 */
@Composable
private fun TreeRowControl(
    painter: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    height: Dp,
    modifier: Modifier = Modifier,
    glyphWidth: Dp = 12.dp,
    glyphHeight: Dp = 12.dp,
) {
    Box(
        modifier =
            modifier
                .width(TreeControlWidth)
                .height(height)
                .clickable(
                    onClick = onClick,
                    onClickLabel = contentDescription,
                    role = Role.Button,
                ).semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painter,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(glyphWidth, glyphHeight),
        )
    }
}

/**
 * The conversation row's leading dot: Figma's redrawn states (#1679) keep a half-alpha primary ring
 * only for Idle, regardless of selection. Running, Unread and WaitingForAnswer are solid discs.
 *
 * The dot names its state, so the meaning never rests on colour; the row's `selectable` merges that name
 * with the conversation's. Only Running blinks, and the alpha is read in the layer, so the blink redraws
 * the dot without recomposing the row.
 */
@Composable
private fun ConversationStatusDot(attention: ConversationAttention) {
    val fill =
        when (attention) {
            ConversationAttention.WaitingForAnswer -> MaterialTheme.colorScheme.warning
            ConversationAttention.Running -> MaterialTheme.colorScheme.primary
            ConversationAttention.Unread -> MaterialTheme.colorScheme.success
            ConversationAttention.Idle -> Color.Transparent
        }
    val description = stringResource(attention.descriptionRes())
    val blink =
        if (attention == ConversationAttention.Running) {
            rememberInfiniteTransition(label = "running-dot").animateFloat(
                initialValue = 1f,
                targetValue = RUNNING_BLINK_MIN_ALPHA,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(RUNNING_BLINK_HALF_PERIOD_MS, easing = EaseInOut),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "running-dot-alpha",
            )
        } else {
            null
        }
    Box(
        modifier =
            Modifier
                .size(TreeDotSize)
                .graphicsLayer { alpha = blink?.value ?: 1f }
                .background(fill, CircleShape)
                .then(
                    if (attention == ConversationAttention.Idle) {
                        Modifier.border(TreeDotRingWidth, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape)
                    } else {
                        Modifier
                    },
                ).clearAndSetSemantics { contentDescription = description },
    )
}

private fun ConversationAttention.descriptionRes(): Int =
    when (this) {
        ConversationAttention.WaitingForAnswer -> R.string.cd_conversation_attention_waiting
        ConversationAttention.Running -> R.string.cd_conversation_attention_running
        ConversationAttention.Unread -> R.string.cd_conversation_attention_unread
        ConversationAttention.Idle -> R.string.cd_conversation_attention_idle
    }

@Composable
private fun TreeRowsPreviewMatrix() {
    Surface {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            TreeSectionHeader(title = "Channels", onAddTapped = {})
            TreeHostRow(
                serverId = "pyrybox",
                hostName = "Pyrybox",
                connectionStatus =
                    ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected),
                expanded = true,
                onToggleExpanded = {},
                onEditTapped = {},
            )
            TreeWorkspaceRow(
                workspaceName = "Second Brain",
                expanded = true,
                onToggleExpanded = {},
                onEditTapped = {},
                onAddTapped = {},
            )
            TreeConversationRow(conversationName = "kitchenclaw refactor", selected = false, onClick = {})
            TreeConversationRow(
                conversationName = "pyrycode discord integration",
                selected = true,
                onClick = {},
                onEditTapped = {},
            )
            TreeConversationRow(conversationName = "rocd-thinking", selected = false, onClick = {})
            ConversationAttention.entries.forEach { state ->
                TreeConversationRow(conversationName = state.name, selected = false, onClick = {}, attention = state)
            }
            TreeHostRow(
                serverId = "macbook",
                hostName = "Macbook",
                connectionStatus =
                    ConnectionStatus(
                        RelayLinkStatus.Reconnecting(secondsRemaining = 12),
                        PyrycodeLinkStatus.Down,
                    ),
                expanded = false,
                onToggleExpanded = {},
                onEditTapped = {},
            )
            listOf("1.4.0", null).forEach { minimum ->
                TreeHostRow(
                    serverId = "old-$minimum",
                    hostName = "Pyrybox",
                    connectionStatus =
                        ConnectionStatus(RelayLinkStatus.UpdateRequired(minimum), PyrycodeLinkStatus.Down),
                    expanded = false,
                    onToggleExpanded = {},
                    onEditTapped = {},
                )
            }
        }
    }
}

@Preview(name = "Tree rows — Light", showBackground = true, widthDp = 412)
@Composable
private fun TreeRowsLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) { TreeRowsPreviewMatrix() }
}

@Preview(
    name = "Tree rows — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun TreeRowsDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) { TreeRowsPreviewMatrix() }
}
