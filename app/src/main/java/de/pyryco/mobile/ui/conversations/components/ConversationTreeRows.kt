package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
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
private val TreeGlyphGap = 12.dp
private val TreeNameGap = 6.dp
private val TreeLegDotGap = 6.dp
private val TreeDotSize = 8.dp
private val TreeDotRingWidth = 1.dp
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
 * A tree section's header — "Channels", "Chats".
 *
 * [title] is app-authored, so it is not run through [boundedRowText]; it is a resource string the
 * caller resolved, never daemon text. The trailing add control is #732's.
 */
@Composable
fun TreeSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = SECTION_HEADER_ALPHA),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp, end = TreeRowEndPadding)
                .semantics { heading() },
    )
}

/**
 * One host in the tree: a server glyph, the host's name, a fold control, and the two connection legs
 * shown separately.
 *
 * Stateless — [hostName] is display text the caller resolved (a nameless host reads as whatever
 * #731 decides), [expanded] is the caller's flag, and the row reports a fold request back through
 * [onToggleExpanded]. The row knows no `serverId` and resolves nothing. Keeping the indicator pair
 * accurate as hosts fail live is #668; the edit control is #642 and the add control #732.
 */
@Composable
fun TreeHostRow(
    hostName: String,
    connectionStatus: ConnectionStatus,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FoldableTreeRow(
        glyph = Icons.Filled.Dns,
        name = boundedRowText(hostName),
        nameStyle = MaterialTheme.typography.titleSmall,
        startIndent = HostRowIndent,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        modifier = modifier,
    ) {
        Spacer(modifier = Modifier.width(TreeGlyphGap))
        ConnectionLegPair(status = connectionStatus)
    }
}

/**
 * One workspace under a host: an open-folder glyph, the workspace's name and a fold control.
 *
 * [workspaceName] is #729's already-resolved `HostWorkspaceGroup.displayName` — display text only,
 * never the `cwd`. The add-workspace control is #732, with its content behind #664.
 */
@Composable
fun TreeWorkspaceRow(
    workspaceName: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FoldableTreeRow(
        glyph = Icons.Filled.FolderOpen,
        name = boundedRowText(workspaceName),
        nameStyle = MaterialTheme.typography.titleSmall,
        startIndent = WorkspaceRowIndent,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
        modifier = modifier,
    )
}

/**
 * One conversation under a workspace — the same row for a channel and for a chat, as the design
 * instances one component in both sections.
 *
 * The leading status slot is drawn only in the idle treatment and takes no state input: #668
 * introduces unread/activity state and the precedence behind it. [selected] draws the design's plain
 * highlighted treatment; the phone has no hover, and the edit content is #665.
 */
@Composable
fun TreeConversationRow(
    conversationName: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        IdleStatusDot()
        Spacer(modifier = Modifier.width(TreeGlyphGap))
        Text(
            text = boundedRowText(conversationName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * The shared host/workspace row: leading glyph, name, fold chevron, then the caller's [trailing]
 * content.
 *
 * The whole row is the fold control, so folding works by touch alone with nothing riding on a
 * pointer hovering. The row deliberately sets no `contentDescription` of its own: `clickable` merges
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
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(TreeGlyphSize),
        )
        Spacer(modifier = Modifier.width(TreeGlyphGap))
        // The name and its chevron share the row's leftover width, so a long name ellipsizes rather
        // than pushing the chevron or any trailing content past the row's edge.
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = nameStyle,
                color = MaterialTheme.colorScheme.onSurface,
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

/** The conversation row's leading slot, drawn only in the design's idle ring treatment (#668). */
@Composable
private fun IdleStatusDot() {
    Box(
        modifier =
            Modifier
                .size(TreeDotSize)
                .border(TreeDotRingWidth, MaterialTheme.colorScheme.primary, CircleShape),
    )
}

@Composable
private fun TreeRowsPreviewMatrix() {
    Surface {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            TreeSectionHeader(title = "Channels")
            TreeHostRow(
                hostName = "Pyrybox",
                connectionStatus =
                    ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected),
                expanded = true,
                onToggleExpanded = {},
            )
            TreeWorkspaceRow(workspaceName = "Second Brain", expanded = true, onToggleExpanded = {})
            TreeConversationRow(conversationName = "kitchenclaw refactor", selected = false, onClick = {})
            TreeConversationRow(
                conversationName = "pyrycode discord integration",
                selected = true,
                onClick = {},
            )
            TreeConversationRow(conversationName = "rocd-thinking", selected = false, onClick = {})
            TreeHostRow(
                hostName = "Macbook",
                connectionStatus =
                    ConnectionStatus(
                        RelayLinkStatus.Reconnecting(secondsRemaining = 12),
                        PyrycodeLinkStatus.Down,
                    ),
                expanded = false,
                onToggleExpanded = {},
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
