package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.data.repository.UnrecognizedSite
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant

private val UnrecognizedRowVerticalSpacing = 12.dp
private val UnrecognizedCornerRadius = 12.dp
private val UnrecognizedHorizontalPadding = 12.dp
private val UnrecognizedVerticalPadding = 8.dp
private val UnrecognizedHeaderGap = 8.dp
private val UnrecognizedIconSize = 18.dp
private val UnrecognizedBorderWidth = 1.dp
private val UnrecognizedExpandedTopPadding = 8.dp
private val UnrecognizedExpandedGap = 8.dp

/** Client-owned separator between the summary's spans. Never daemon-supplied. */
private const val SUMMARY_SEPARATOR = " · "

/**
 * A claude message the daemon's stream-json parser could not understand (#608), drawn as a collapsed
 * one-line pill that expands in place on tap to reveal the offending JSON. Structure mirrors
 * [ToolCallRow]; the palette comes from Figma `16-28` (`ToolCallRow` diverges from that frame).
 *
 * Security — [ThreadItem.UnrecognizedMessage.raw] and
 * [messageType][ThreadItem.UnrecognizedMessage.messageType] are the most untrusted strings the thread
 * holds (unbounded, model-adjacent JSON the daemon could not interpret, arriving over the network). The
 * render-time obligations follow the permission modal's precedent:
 * - **Inert output-encoding** — every daemon string reaches a plain [Text] as a literal. Never
 *   [MarkdownText] (which [MessageBubble] routes through), and no `SelectionContainer`, which would open
 *   a clipboard exfiltration path.
 * - **No payload in persisted state** — only the [Boolean] toggle reaches `rememberSaveable`.
 * - **No logging** — nothing on this path logs any payload field.
 * - **Client-owned [site][ThreadItem.UnrecognizedMessage.site] copy** — see [siteLabel]. This narrows
 *   the daemon-supplied render surface to exactly two strings.
 *
 * Deliberately **not** borrowed from [LiteralScreenSurface], whose four-rule bundle does not transfer:
 * no `FLAG_SECURE` (a per-window flag; a thread row has no window of its own, so the only way to apply
 * it would be the host Activity — hardening the whole thread screen as a side effect of one diagnostic
 * row), and no `softWrap = false` / dual-axis scroll (a thread row wraps like its neighbours).
 */
@Composable
fun UnrecognizedMessageRow(
    item: ThreadItem.UnrecognizedMessage,
    modifier: Modifier = Modifier,
) {
    // Only the toggle is saved. Inside a LazyColumn item this is scoped by the row's key, so each row
    // keeps its own state and it survives configuration change.
    var expanded by rememberSaveable { mutableStateOf(false) }
    UnrecognizedMessageRowContent(
        item = item,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier,
    )
}

@Composable
private fun UnrecognizedMessageRowContent(
    item: ThreadItem.UnrecognizedMessage,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Announced by TalkBack as the action, rather than re-reading the row's text.
    val clickLabel =
        if (expanded) {
            stringResource(R.string.cd_thread_unrecognized_collapse)
        } else {
            stringResource(R.string.cd_thread_unrecognized_expand)
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    // The thread's shared content gutter (#644). Carried here because this row is the
                    // only list kind with no other owner, and without it the row bleeds to the screen
                    // edge while every bubble beside it sits inset.
                    start = MessageContentGutter,
                    end = MessageContentGutter,
                    bottom = UnrecognizedRowVerticalSpacing,
                ),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = clickLabel, onClick = onToggle),
            shape = RoundedCornerShape(UnrecognizedCornerRadius),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(UnrecognizedBorderWidth, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier =
                    Modifier.padding(
                        horizontal = UnrecognizedHorizontalPadding,
                        vertical = UnrecognizedVerticalPadding,
                    ),
            ) {
                CollapsedHeaderRow(item)
                if (expanded) {
                    ExpandedBody(item)
                }
            }
        }
    }
}

@Composable
private fun CollapsedHeaderRow(item: ThreadItem.UnrecognizedMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(UnrecognizedHeaderGap),
    ) {
        // `tertiary`, not `error`: this reports a gap in *our own* parser, not a claude failure or a tool
        // error. `error` overstates it and `onSurfaceVariant` erases it as a signal; `tertiary` is the
        // accent the drawn row family already gives the notable slot. Design-owed — the row has no Figma
        // frame of its own. contentDescription is null because the adjacent text carries the meaning.
        Icon(
            imageVector = Icons.Outlined.WarningAmber,
            contentDescription = null,
            modifier = Modifier.size(UnrecognizedIconSize),
            tint = MaterialTheme.colorScheme.tertiary,
        )
        Text(
            text = buildSummaryAnnotated(item),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The collapsed line: a fixed client-owned label, the offending type, then a human label for the site.
 *
 * The type's `Monospace` + `tertiary` styling is a **security property, not decoration** — do not
 * simplify this to one uniform style. `messageType` is daemon-supplied and renders adjacent to
 * client-owned copy, so a crafted value could try to impersonate the label or the site text. Three
 * things contain that: the type is the only span in mono + `tertiary` (the client's spans are
 * proportional + `onSurfaceVariant`), it always renders *between* the fixed label and the site label
 * rather than replacing either, and the caller's `maxLines = 1` + ellipsis clips any embedded newline so
 * a payload cannot manufacture extra visual rows.
 */
@Composable
private fun buildSummaryAnnotated(item: ThreadItem.UnrecognizedMessage): AnnotatedString {
    val clientColor = MaterialTheme.colorScheme.onSurfaceVariant
    val typeColor = MaterialTheme.colorScheme.tertiary
    val label = stringResource(R.string.thread_unrecognized_label)
    val site = siteLabel(item.site)
    return buildAnnotatedString {
        withStyle(SpanStyle(color = clientColor)) { append(label) }
        // Empty for the `undecodable` site — nothing decoded, so no type was ever read — in which case
        // the slot is omitted entirely rather than leaving an empty literal or a dangling separator.
        if (item.messageType.isNotEmpty()) {
            withStyle(SpanStyle(color = clientColor)) { append(SUMMARY_SEPARATOR) }
            withStyle(SpanStyle(color = typeColor, fontFamily = FontFamily.Monospace)) {
                append(item.messageType)
            }
        }
        withStyle(SpanStyle(color = clientColor)) {
            append(SUMMARY_SEPARATOR)
            append(site)
        }
    }
}

/**
 * A human label for each drop site. A total function over the closed enum — **client-owned** copy, so
 * the daemon's `site` value selects a string but never becomes one, which is what keeps that value out
 * of the rendered text. Used as an expression with no `else`, so a future fifth site is a compile error
 * rather than a blank slot.
 */
@Composable
private fun siteLabel(site: UnrecognizedSite): String =
    when (site) {
        UnrecognizedSite.LineType -> stringResource(R.string.thread_unrecognized_site_line_type)
        UnrecognizedSite.AssistantBlock -> stringResource(R.string.thread_unrecognized_site_assistant_block)
        UnrecognizedSite.UserBlock -> stringResource(R.string.thread_unrecognized_site_user_block)
        UnrecognizedSite.Undecodable -> stringResource(R.string.thread_unrecognized_site_undecodable)
    }

@Composable
private fun ExpandedBody(item: ThreadItem.UnrecognizedMessage) {
    Column(
        modifier = Modifier.padding(top = UnrecognizedExpandedTopPadding),
        verticalArrangement = Arrangement.spacedBy(UnrecognizedExpandedGap),
    ) {
        // Verbatim: never parsed, trimmed, or reformatted. A malformed or truncated payload is the
        // expected input here, not an error.
        Text(
            text = item.raw,
            style =
                MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        // Only when the daemon actually cut the payload, so the reader knows the JSON is incomplete by
        // design rather than malformed at the source. A render of the daemon's flag, not a client-side
        // detection.
        if (item.truncated) {
            Text(
                text = stringResource(R.string.thread_unrecognized_truncated),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Preview fixtures are hand-written literals, never captured live payloads.
private val PreviewInstant = Instant.parse("2026-07-30T12:00:00Z")

private val PreviewAssistantBlock =
    ThreadItem.UnrecognizedMessage(
        id = "preview-1",
        site = UnrecognizedSite.AssistantBlock,
        messageType = "thinking_delta",
        raw = """{"type":"thinking_delta","delta":{"thinking":"weighing the options"}}""",
        truncated = false,
        occurredAt = PreviewInstant,
    )

private val PreviewTruncated =
    ThreadItem.UnrecognizedMessage(
        id = "preview-2",
        site = UnrecognizedSite.LineType,
        messageType = "server_tool_use",
        raw = """{"type":"server_tool_use","id":"srvtoolu_01","name":"web_search","input":{"query":"how""",
        truncated = true,
        occurredAt = PreviewInstant,
    )

// site = Undecodable: nothing decoded, so messageType is empty and the type slot is omitted.
private val PreviewUndecodable =
    ThreadItem.UnrecognizedMessage(
        id = "preview-3",
        site = UnrecognizedSite.Undecodable,
        messageType = "",
        raw = "{not json at all",
        truncated = false,
        occurredAt = PreviewInstant,
    )

// No horizontal padding of its own: the row carries the thread's 20dp gutter now, so the preview shows
// the real geometry rather than stacking a second inset on top of it.
@Composable
private fun UnrecognizedMessageRowPreviewMatrix() {
    Column {
        UnrecognizedMessageRowContent(
            item = PreviewAssistantBlock,
            expanded = false,
            onToggle = {},
        )
        UnrecognizedMessageRowContent(
            item = PreviewAssistantBlock,
            expanded = true,
            onToggle = {},
        )
        UnrecognizedMessageRowContent(
            item = PreviewTruncated,
            expanded = true,
            onToggle = {},
        )
        UnrecognizedMessageRowContent(
            item = PreviewUndecodable,
            expanded = false,
            onToggle = {},
        )
        UnrecognizedMessageRowContent(
            item = PreviewUndecodable,
            expanded = true,
            onToggle = {},
        )
    }
}

@Preview(name = "UnrecognizedMessageRow — Light", showBackground = true, widthDp = 412)
@Composable
private fun UnrecognizedMessageRowLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            UnrecognizedMessageRowPreviewMatrix()
        }
    }
}

// The dark preview is the fidelity reference against Figma `16-28`.
@Preview(
    name = "UnrecognizedMessageRow — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun UnrecognizedMessageRowDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface {
            UnrecognizedMessageRowPreviewMatrix()
        }
    }
}
