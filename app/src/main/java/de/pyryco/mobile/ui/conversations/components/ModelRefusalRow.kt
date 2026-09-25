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
import androidx.compose.material.icons.outlined.Info
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant

private val RefusalRowVerticalSpacing = 12.dp
private val RefusalCornerRadius = 12.dp
private val RefusalHorizontalPadding = 12.dp
private val RefusalVerticalPadding = 8.dp
private val RefusalHeaderGap = 8.dp
private val RefusalIconSize = 18.dp
private val RefusalBorderWidth = 1.dp
private val RefusalExpandedTopPadding = 8.dp

/**
 * claude refused a turn on one model and retried on another or did not (#875), drawn as
 * [UnrecognizedMessageRow]'s collapsed pill: "Refused on X, continued on Y" or "Refused by X". When claude
 * sent an explanation, a tap opens it in place, attributed to claude. Figma 16:8 has no refusal component,
 * so the pill takes the drawn `16-28` tool-row tokens [UnrecognizedMessageRow] already uses.
 *
 * Security — every string on [ThreadItem.ModelRefusal] is claude-authored and unsanitized. The render-time
 * obligations:
 * - **Stripped** of control characters and terminal escapes at this one sink: the banner by
 *   [bannerDisplayText], each model identifier by [refusalModelDisplay], which also drops line breaks.
 * - **Inert** — plain [Text] only: never markdown, no link detection, no `SelectionContainer`. The one click
 *   is the expand toggle, and a row with no explanation has none.
 * - **Unforgeable copy** — the title is client-owned spans with each model identifier as its own monospace
 *   span, so an identifier cannot pass itself off as the surrounding words; the banner's "<agent>: " is its
 *   own medium-weight span, as in [BannerNoticeRow], naming the conversation's [agent] (#1113). Keep both
 *   separate spans.
 * - **No logging, no persisting** — only the expand [Boolean] reaches saved state, and the row is never cached.
 */
@Composable
fun ModelRefusalRow(
    item: ThreadItem.ModelRefusal,
    agent: ConversationAgent,
    modifier: Modifier = Modifier,
) {
    // Only the toggle is saved, scoped by the row's LazyColumn key.
    var expanded by rememberSaveable { mutableStateOf(false) }
    ModelRefusalRowContent(
        item = item,
        agent = agent,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        modifier = modifier,
    )
}

@Composable
private fun ModelRefusalRowContent(
    item: ThreadItem.ModelRefusal,
    agent: ConversationAgent,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val banner = bannerDisplayText(item.banner)
    val expandable = banner.isNotBlank()
    val name = agentName(agent)
    val clickLabel =
        if (expanded) {
            stringResource(R.string.cd_thread_refusal_collapse, name)
        } else {
            stringResource(R.string.cd_thread_refusal_expand, name)
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = MessageContentGutter, end = MessageContentGutter, bottom = RefusalRowVerticalSpacing),
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (expandable) Modifier.clickable(onClickLabel = clickLabel, onClick = onToggle) else Modifier),
            shape = RoundedCornerShape(RefusalCornerRadius),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(RefusalBorderWidth, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.padding(horizontal = RefusalHorizontalPadding, vertical = RefusalVerticalPadding)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(RefusalHeaderGap),
                ) {
                    // The adjacent title carries the meaning, so the glyph is decorative.
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(RefusalIconSize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = refusalTitle(item),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (expandable && expanded) {
                    Text(
                        text = attributedBanner(name, banner, item.bannerTruncated),
                        modifier = Modifier.padding(top = RefusalExpandedTopPadding),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** The title: client-owned copy in the muted style, each claude-named model its own monospace span. */
@Composable
private fun refusalTitle(item: ThreadItem.ModelRefusal): AnnotatedString {
    val clientStyle = SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val modelStyle = SpanStyle(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace)
    val unknownModel = stringResource(R.string.thread_refusal_unknown_model)
    val refusedOn = stringResource(R.string.thread_refusal_refused_on)
    val continuedOn = stringResource(R.string.thread_refusal_continued_on)
    val refusedBy = stringResource(R.string.thread_refusal_refused_by)
    return buildAnnotatedString {
        fun model(value: String) {
            val display = refusalModelDisplay(value)
            if (display == null) {
                withStyle(clientStyle) { append(unknownModel) }
            } else {
                withStyle(modelStyle) { append(display) }
            }
        }
        val fallbackModel = item.fallbackModel
        if (fallbackModel != null) {
            withStyle(clientStyle) { append(refusedOn) }
            model(item.originalModel)
            withStyle(clientStyle) { append(continuedOn) }
            model(fallbackModel)
        } else {
            withStyle(clientStyle) { append(refusedBy) }
            model(item.originalModel)
        }
    }
}

/** The opened explanation: the client-owned attribution span, the stripped prose, then the cut mark if cut. */
@Composable
private fun attributedBanner(
    agentName: String,
    banner: String,
    truncated: Boolean,
): AnnotatedString {
    val attribution = stringResource(R.string.thread_banner_attribution, agentName)
    val truncatedMark = stringResource(R.string.thread_banner_truncated)
    return buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(attribution) }
        append(banner)
        if (truncated) {
            withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(truncatedMark) }
        }
    }
}

private val LineBreakOrTab = Regex("""[\t\n\r]""")

/**
 * A refusal's claude-authored model identifier as the title draws it (#875), or **null** when nothing
 * readable is left — the caller then shows "unknown model". [bannerDisplayText]'s stripping plus tabs and
 * line breaks: an identifier has no business spanning lines, and one that did could fake a second row.
 */
internal fun refusalModelDisplay(model: String): String? = bannerDisplayText(model).replace(LineBreakOrTab, "").takeIf { it.isNotBlank() }

// Preview fixtures are hand-written literals, never captured live payloads.
private val PreviewInstant = Instant.parse("2026-09-23T12:00:00Z")

private val PreviewFallback =
    ThreadItem.ModelRefusal(
        originalModel = "claude-opus-5-5",
        fallbackModel = "claude-sonnet-5",
        banner = "This request was declined on Opus, so it was retried on Sonnet for the rest of this session.",
        bannerTruncated = false,
        occurredAt = PreviewInstant,
    )

private val PreviewTruncated =
    ThreadItem.ModelRefusal(
        originalModel = "claude-opus-5-5",
        fallbackModel = null,
        banner = "This request was declined and was not retried on another model. You can rephrase it and",
        bannerTruncated = true,
        occurredAt = PreviewInstant,
    )

private val PreviewUnknownNoBanner =
    ThreadItem.ModelRefusal(
        originalModel = "",
        fallbackModel = null,
        banner = "",
        bannerTruncated = false,
        occurredAt = PreviewInstant,
    )

@Composable
private fun ModelRefusalRowPreviewMatrix() {
    Column {
        ModelRefusalRowContent(item = PreviewFallback, agent = ConversationAgent.Claude, expanded = false, onToggle = {})
        ModelRefusalRowContent(item = PreviewFallback, agent = ConversationAgent.Claude, expanded = true, onToggle = {})
        ModelRefusalRowContent(item = PreviewTruncated, agent = ConversationAgent.Codex, expanded = true, onToggle = {})
        ModelRefusalRowContent(item = PreviewUnknownNoBanner, agent = ConversationAgent.Claude, expanded = false, onToggle = {})
    }
}

@Preview(name = "ModelRefusalRow — Light", showBackground = true, widthDp = 412)
@Composable
private fun ModelRefusalRowLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            ModelRefusalRowPreviewMatrix()
        }
    }
}

// The dark preview is the fidelity reference against Figma 16:8.
@Preview(
    name = "ModelRefusalRow — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun ModelRefusalRowDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface {
            ModelRefusalRowPreviewMatrix()
        }
    }
}
