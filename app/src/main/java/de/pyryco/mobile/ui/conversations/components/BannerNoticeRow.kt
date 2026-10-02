package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.BannerLevel
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant

/**
 * Text the agent printed about the session (#873), drawn without a bubble or icon in the message stream.
 * Figma's `Thread notification` component uses muted body text for both levels. A [BannerLevel.Warning]
 * starts with a client-owned "Warning · " prefix so its meaning does not depend on colour.
 *
 * Security — [ThreadItem.Banner.text] is claude-authored and unsanitized, and a notice styled like the
 * app's own chrome is the realistic abuse. The render-time obligations:
 * - **Stripped** of control characters and terminal escapes by [bannerDisplayText] at this one sink.
 * - **Inert** — a plain [Text]: never markdown, no link detection, no click, no `SelectionContainer`.
 * - **Attributed** — the client-owned "<agent>: " ([agentName] of the conversation's [agent], #1113) is its
 *   own medium-weight span. The agent can type those characters but cannot style a span, so the attribution
 *   cannot be forged from inside the text. Keep it a separate styled span.
 * - **No second length cap** — the daemon bounds the text at 4 KiB and reports its own cut in
 *   [ThreadItem.Banner.truncated], shown as a client-owned italic mark.
 * - **No logging** — nothing on this path logs the text. The thread cache stores the row as held (#1353), so
 *   a restored row renders through this same boundary.
 */
@Composable
fun BannerNoticeRow(
    item: ThreadItem.Banner,
    agent: ConversationAgent,
    modifier: Modifier = Modifier,
) {
    val warning = item.level == BannerLevel.Warning
    val name = agentName(agent)
    val warningPrefix = stringResource(R.string.thread_banner_warning_prefix)
    val attribution = stringResource(R.string.thread_banner_attribution, name)
    val truncatedMark = stringResource(R.string.thread_banner_truncated)
    val text =
        buildAnnotatedString {
            if (warning) withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(warningPrefix) }
            withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(attribution) }
            append(bannerDisplayText(item.text))
            if (item.truncated) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(truncatedMark) }
            }
        }
    Text(
        text = text,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = MessageContentGutter, end = MessageContentGutter, bottom = MessageAreaRowSpacing),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// desktop's `bannerDisplayText` set, in its order: OSC strings, DCS/SOS/PM/APC strings, CSI sequences,
// other ESC sequences, then the remaining C0/C1 controls and DEL. Tab, LF and CR survive. A string or
// CSI the text never terminates runs to the end of the text, so a cut sequence cannot leak its tail.
private val OscSequence = Regex("""(?:\x1b\]|\x9d)[\s\S]*?(?:\x07|\x1b\\|\x9c|\z)""")
private val ControlString = Regex("""(?:\x1b[PX^_]|[\x90\x98\x9e\x9f])[\s\S]*?(?:\x1b\\|\x9c|\z)""")
private val CsiSequence = Regex("""(?:\x1b\[|\x9b)[0-?]*[ -/]*(?:[@-~]|\z)""")
private val EscSequence = Regex("""\x1b[ -/]*[0-~]""")
private val ControlCharacter = Regex("""[\x00-\x08\x0b\x0c\x0e-\x1f\x7f-\x9f]""")

/**
 * A banner's claude-authored [text] with control characters and terminal escape sequences removed (#873).
 * The protocol makes this stripping the client's: the daemon bounds the text and does not sanitize it.
 * Presentation only — the row keeps [ThreadItem.Banner.text] verbatim.
 */
internal fun bannerDisplayText(text: String): String =
    text
        .replace(OscSequence, "")
        .replace(ControlString, "")
        .replace(CsiSequence, "")
        .replace(EscSequence, "")
        .replace(ControlCharacter, "")

// Preview fixtures are hand-written literals, never captured live payloads.
private val PreviewInstant = Instant.parse("2026-09-23T12:00:00Z")

@Composable
private fun BannerNoticeRowPreviewMatrix() {
    Column {
        BannerNoticeRow(
            ThreadItem.Banner(
                level = BannerLevel.Warning,
                text = "UserPromptSubmit operation blocked by hook: prompts naming the prod database are not allowed",
                truncated = false,
                occurredAt = PreviewInstant,
            ),
            agent = ConversationAgent.Claude,
        )
        BannerNoticeRow(
            ThreadItem.Banner(
                level = BannerLevel.Notice,
                text = "Loop iteration 3 of 10 finished.",
                truncated = false,
                occurredAt = PreviewInstant,
            ),
            agent = ConversationAgent.Claude,
        )
        BannerNoticeRow(
            ThreadItem.Banner(
                level = BannerLevel.Notice,
                text = "Total cost: \$0.42\nTotal duration (API): 1m 12s\nTotal code changes: 48 lines added",
                truncated = true,
                occurredAt = PreviewInstant,
            ),
            agent = ConversationAgent.Codex,
        )
    }
}

@Preview(name = "BannerNoticeRow — Light", showBackground = true, widthDp = 412)
@Composable
private fun BannerNoticeRowLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            BannerNoticeRowPreviewMatrix()
        }
    }
}

// The dark preview is the fidelity reference against Figma 16:8.
@Preview(
    name = "BannerNoticeRow — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun BannerNoticeRowDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface {
            BannerNoticeRowPreviewMatrix()
        }
    }
}
