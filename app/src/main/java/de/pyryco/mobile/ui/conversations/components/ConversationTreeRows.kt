package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Power
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
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
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import de.pyryco.mobile.ui.theme.success
import de.pyryco.mobile.ui.theme.warning
import de.pyryco.mobile.ui.workspace.MAX_WORKSPACE_LABEL_CHARS

// The tree's four indentation steps, mirroring the supplied design's absolute positions inside its
// `Channels` container. Each row carries only its own tree indent; the screen's horizontal gutter
// belongs to the list that assembles them (#731).
private val HostRowIndent = 0.dp
private val WorkspaceRowIndent = 12.dp
private val ConversationRowIndent = 16.dp

// The design draws 28/28/24dp rows for a pointer. Touch needs 48dp — the same minimum
// `ConversationRowTest` already holds the flat row to. The hierarchy the heights carried on desktop
// is carried here by indent, leading glyph and type scale.
private val TreeRowMinHeight = 48.dp
private val TreeRowEndPadding = 8.dp
private val TreeRowShape = RoundedCornerShape(6.dp)
private val TreeGlyphSize = 16.dp
private val TreeChevronSize = 18.dp

// The add control's target (#738). The same accessibility minimum TreeRowMinHeight holds the rows to,
// applied on both axes because this one is a control rather than a whole row.
private val TreeAddTouchSize = 48.dp
private val TreeGlyphGap = 12.dp
private val TreeNameGap = 6.dp
private val TreeLegDotGap = 6.dp
private val TreeDotSize = 8.dp
private val TreeDotRingWidth = 1.dp

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
 * The band grows from the design's 20dp text line to [TreeRowMinHeight] because it carries a control
 * now rather than being a bare label — the same trade [TreeConversationRow] and [FoldableTreeRow] took
 * when #731 grew the design's 28dp pointer rows to a size touch can hit.
 */
@Composable
fun TreeSectionHeader(
    title: String,
    onAddTapped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = TreeRowMinHeight).padding(end = TreeRowEndPadding),
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
            icon = Icons.Filled.Add,
            contentDescription = stringResource(R.string.cd_tree_section_pair_host, title),
            onClick = onAddTapped,
        )
    }
}

/**
 * One host in the tree: a server glyph, the host's name, a fold control, the two connection legs shown
 * separately, the edit control that opens the Edit host modal for **this** host (#744), and the add
 * control that starts a chat on it (#738).
 *
 * Stateless — [hostName] is display text the caller resolved (a nameless host reads as whatever
 * #731 decides), [expanded] is the caller's flag, and the row reports a fold request back through
 * [onToggleExpanded]. The row resolves nothing: [serverId] is reported straight back through
 * [onAddTapped] / [onAddLongPressed] / [onEditTapped] and is otherwise used only to name the two
 * controls for the device suites, so those handles stay unambiguous once a second host is paired.
 * Keeping the indicator pair accurate as hosts fail live is #668.
 *
 * The design's hover treatment swaps the leg dots for the pencil and the plus, in that order. The phone
 * has no hover, so both are drawn persistently in the same order, outboard of the dots the design would
 * have hidden.
 *
 * A host whose relay leg [isDisconnected] draws the design's disconnected treatment (#840): glyph and
 * name in the error colour, and a plug control inboard of the dots that reports through
 * [onReconnectTapped]. The frame binds the name to `errorContainer`, which is near-white on the light
 * surface, so both take `error` instead — error-toned and legible in either scheme.
 */
@Composable
fun TreeHostRow(
    serverId: String,
    hostName: String,
    connectionStatus: ConnectionStatus,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onEditTapped: () -> Unit,
    onAddTapped: () -> Unit,
    onAddLongPressed: () -> Unit,
    modifier: Modifier = Modifier,
    onReconnectTapped: () -> Unit = {},
) {
    // Clamped once here and reused for the row's own name and for every control label, so no path can
    // format an unbounded daemon-authored name into a content description.
    val bounded = boundedRowText(hostName)
    val disconnected = connectionStatus.relay.isDisconnected()
    FoldableTreeRow(
        glyph = Icons.Filled.Dns,
        name = bounded,
        nameStyle = MaterialTheme.typography.titleSmall,
        startIndent = HostRowIndent,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        modifier = modifier,
        accent = if (disconnected) MaterialTheme.colorScheme.error else null,
    ) {
        Spacer(modifier = Modifier.width(TreeGlyphGap))
        if (disconnected) {
            TreeRowControl(
                // Material's plug, matched to the frame's `plug-solid-full` as `Dns` was to its server.
                icon = Icons.Filled.Power,
                contentDescription = stringResource(R.string.cd_tree_host_reconnect, bounded),
                onClick = onReconnectTapped,
                modifier = Modifier.testTag(treeHostReconnectTestTag(serverId)),
            )
        }
        ConnectionLegPair(status = connectionStatus)
        TreeRowControl(
            // Matched to the Material set the same way this file matched `Dns` and `FolderOpen` to the
            // design's own glyphs, rather than vendoring the frame's drawable.
            icon = Icons.Filled.Edit,
            contentDescription = stringResource(R.string.cd_tree_host_edit, bounded),
            onClick = onEditTapped,
            modifier = Modifier.testTag(treeHostEditTestTag(serverId)),
        )
        TreeRowControl(
            icon = Icons.Filled.Add,
            contentDescription = stringResource(R.string.cd_tree_host_new_chat, bounded),
            onClick = onAddTapped,
            onLongClickLabel = stringResource(R.string.cd_tree_host_pick_workspace, bounded),
            onLongClick = onAddLongPressed,
            modifier = Modifier.testTag(treeHostAddTestTag(serverId)),
        )
    }
}

/**
 * The device suites' handles for one host's two controls — an app-authored prefix, the host's own id, and
 * nothing drawn on the row (#736's convention, per-host because the controls repeat).
 *
 * The id comes from the saved `PairedServer` record the operator scanned, not from a daemon frame, but a
 * hostile QR could still make it enormous and a `testTag` is re-evaluated on every recomposition of the
 * row. So it is clamped exactly as `treeItemKey` clamps its parts — truncated with the original length
 * appended, which keeps two ids sharing a prefix from collapsing onto one tag. `testTag` is invisible to
 * accessibility services, so this carries the id no further than the test tree. Both tags share one
 * clamp so the two cannot drift apart.
 */
fun treeHostAddTestTag(serverId: String): String = "tree-host-add:${boundedTagId(serverId)}"

fun treeHostEditTestTag(serverId: String): String = "tree-host-edit:${boundedTagId(serverId)}"

fun treeHostReconnectTestTag(serverId: String): String = "tree-host-reconnect:${boundedTagId(serverId)}"

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
 * `cwd`. The pencil is drawn permanently, as the host and chat rows' are, since the phone has no hover;
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
        glyph = Icons.Filled.FolderOpen,
        name = bounded,
        nameStyle = MaterialTheme.typography.titleSmall,
        startIndent = WorkspaceRowIndent,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        modifier = modifier,
    ) {
        if (onEditTapped != null) {
            TreeRowControl(
                icon = Icons.Filled.Edit,
                contentDescription = stringResource(R.string.cd_tree_workspace_edit, bounded),
                onClick = onEditTapped,
            )
        }
        if (onAddTapped != null) {
            TreeRowControl(
                icon = Icons.Filled.Add,
                contentDescription = stringResource(R.string.cd_tree_workspace_new_channel, bounded),
                onClick = onAddTapped,
            )
        }
    }
}

/**
 * One conversation under a workspace — the same row for a channel and for a chat, as the design
 * instances one component in both sections.
 *
 * The leading status dot draws [attention], the row's one state that #877 resolves by precedence (#878).
 * [selected] draws the design's plain highlighted treatment.
 *
 * A non-null [onEditTapped] draws the design's hover pencil at the trailing edge, permanently, since the
 * phone has no hover (#827) — the host row's pencil made the same trade (#744). The caller decides which
 * rows get it and names what it edits through [editDescription]: Edit chat on Chats rows, Edit channel on
 * Channels rows (#667). It is a [TreeRowControl], so a tap on it edits the row without opening it or
 * moving the highlight.
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
    val fill =
        if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = SELECTED_FILL_ALPHA)
        } else {
            Color.Transparent
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = TreeRowMinHeight)
                .clip(TreeRowShape)
                .background(fill)
                .selectable(selected = selected, role = Role.Button, onClick = onClick)
                .padding(start = ConversationRowIndent, end = TreeRowEndPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConversationStatusDot(attention = attention)
        Spacer(modifier = Modifier.width(TreeGlyphGap))
        Text(
            text = bounded,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Filling the width pushes the pencil to the trailing edge and ellipsizes a long name before it.
            modifier = Modifier.weight(1f, fill = onEditTapped != null),
        )
        if (onEditTapped != null) {
            TreeRowControl(
                icon = Icons.Filled.Edit,
                contentDescription = stringResource(editDescription, bounded),
                onClick = onEditTapped,
            )
        }
    }
}

/**
 * The shared host/workspace row: leading glyph, name, fold chevron, then the caller's [trailing]
 * content.
 *
 * The whole row is the fold control, so folding works by touch alone with nothing riding on a
 * pointer hovering. A non-null [accent] recolours the leading glyph and the name, and nothing else. The row deliberately sets no `contentDescription` of its own: `clickable` merges
 * descendants, and an overriding description would replace the chevron's and the leg dots' own
 * names. The action is named through `onClickLabel`, and the chevron repeats that name so the
 * control is identifiable in the unmerged tree too.
 */
@Composable
private fun FoldableTreeRow(
    glyph: ImageVector,
    name: String,
    nameStyle: TextStyle,
    startIndent: Dp,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val foldLabel = foldActionLabel(expanded = expanded, rowName = name)

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = TreeRowMinHeight)
                .clip(TreeRowShape)
                .clickable(onClickLabel = foldLabel, role = Role.Button, onClick = onToggleExpanded)
                .padding(start = startIndent, end = TreeRowEndPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = glyph,
            contentDescription = null,
            tint = accent ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(TreeGlyphSize),
        )
        Spacer(modifier = Modifier.width(TreeGlyphGap))
        // The name and its chevron share the row's leftover width, so a long name ellipsizes rather
        // than pushing the chevron or any trailing content past the row's edge.
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
                imageVector =
                    if (expanded) {
                        Icons.Filled.KeyboardArrowDown
                    } else {
                        Icons.AutoMirrored.Filled.KeyboardArrowRight
                    },
                contentDescription = foldLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(TreeChevronSize),
            )
        }
        trailing()
    }
}

/**
 * The trailing row control: the section header's and the host row's plus (#738), the host row's
 * pencil (#744), a disconnected host's plug (#840), a chat row's pencil (#827) and a workspace row's pencil
 * (#905) and plus (#958). The body was already glyph-agnostic, so the caller supplies the [icon] and nothing else
 * differs between them.
 *
 * [contentDescription] is the whole accessible name, because the controls repeat down the screen and two
 * of them sit on one row, so each has to say which section or host it acts on and what it does there.
 * Callers that want a long-press path supply both [onLongClickLabel] and [onLongClick]; the pair is what
 * keeps the retired button's second path — pick a workspace first — reachable, and it is built the same
 * way the button built it. The pencil passes neither: it has one action.
 *
 * **The 48dp trade.** The design pins a [TreeGlyphSize] glyph with its centre 10dp from the content edge.
 * Touch needs [TreeAddTouchSize] and the glyph centres in that box, so behind the rows' own
 * [TreeRowEndPadding] the glyph's centre lands about 22dp further inboard than the design draws it. Taken
 * deliberately, and the same trade #731 took growing the design's 28dp pointer rows to a size a thumb can
 * hit; the alternative is a target below the accessibility minimum every other row here holds to.
 *
 * Nested inside the host row's own `clickable`, which merges descendants — but `combinedClickable` merges
 * too, and merging stops at a merging descendant, so this stays its own node with its own name, tag and
 * click action and a tap on it never reaches the row's fold.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TreeRowControl(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        modifier =
            modifier
                .size(TreeAddTouchSize)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = onClick,
                    onClickLabel = contentDescription,
                    onLongClick = onLongClick,
                    onLongClickLabel = onLongClickLabel,
                    role = Role.Button,
                ).semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(TreeGlyphSize),
        )
    }
}

/**
 * The host row's trailing indicator pair: the relay-to-server leg inboard and the phone-to-relay leg
 * outboard, as the design places them.
 *
 * Both legs resolve through the package's existing [ConnectionLegVisual] mapping — no second
 * mapping. Each dot carries that mapping's own description, naming the leg and its state, so the
 * pair is identifiable beyond colour alone.
 */
@Composable
private fun ConnectionLegPair(status: ConnectionStatus) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(TreeLegDotGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegDot(visual = status.pyrycode.toLegVisual())
        LegDot(visual = status.relay.toLegVisual())
    }
}

@Composable
private fun LegDot(visual: ConnectionLegVisual) {
    Box(
        modifier =
            Modifier
                .size(TreeDotSize)
                .background(visual.category.color(), CircleShape)
                .clearAndSetSemantics { contentDescription = visual.contentDescription },
    )
}

/**
 * The conversation row's leading dot (#878), after desktop's `ConversationStatusDot`: the design's idle
 * ring on every state, with the state's fill inside it. Failed has no desktop counterpart and takes `error`.
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
            ConversationAttention.Running -> MaterialTheme.colorScheme.tertiary
            ConversationAttention.Failed -> MaterialTheme.colorScheme.error
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
                .border(TreeDotRingWidth, MaterialTheme.colorScheme.primary, CircleShape)
                .clearAndSetSemantics { contentDescription = description },
    )
}

private fun ConversationAttention.descriptionRes(): Int =
    when (this) {
        ConversationAttention.WaitingForAnswer -> R.string.cd_conversation_attention_waiting
        ConversationAttention.Running -> R.string.cd_conversation_attention_running
        ConversationAttention.Failed -> R.string.cd_conversation_attention_failed
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
                onAddTapped = {},
                onAddLongPressed = {},
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
            )
            TreeConversationRow(conversationName = "rocd-thinking", selected = false, onClick = {}, onEditTapped = {})
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
                onAddTapped = {},
                onAddLongPressed = {},
            )
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
