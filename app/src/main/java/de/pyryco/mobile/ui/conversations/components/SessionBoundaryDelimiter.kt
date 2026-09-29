package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.MemorySearchAvailability
import de.pyryco.mobile.data.repository.MemorySearchReport
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.LocalStaticDarkPalette
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalTime
import kotlinx.datetime.toLocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal const val MEMORY_PLUGIN_DOCS_URL: String = "https://pyryco.de/docs/memory-plugins"

internal fun MemorySearchReport.shouldOfferMemoryInstall(): Boolean =
    availability == MemorySearchAvailability.Absent && providers.none { it.installed }

// Figma 16:8 `Session reset` (119:3843): a 12dp-gap centred row of hairline rule / body-small label /
// hairline rule. Gutter and inter-row spacing come from `MessageBubble.kt` so the three row kinds in the
// thread share one rhythm.
private val RuleLabelSpacing = 12.dp
private val RuleThickness = 1.dp
private val ExplanationTopSpacing = 8.dp

// Fixed dark takes `Schemes/inverse-primary` at 60% directly. Other palettes keep the earlier
// `outlineVariant` adaptation for a legible rule.
private const val RULE_ALPHA = 0.60f

@Composable
fun SessionBoundaryDelimiter(
    boundary: ThreadItem.SessionBoundary,
    modifier: Modifier = Modifier,
    agent: ConversationAgent = ConversationAgent.Claude,
    memorySearch: MemorySearchReport = MemorySearchReport.Unknown,
) {
    SessionBoundaryDelimiterContent(
        boundary = boundary,
        uriHandler = LocalUriHandler.current,
        modifier = modifier,
        agent = agent,
        memorySearch = memorySearch,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionBoundaryDelimiterContent(
    boundary: ThreadItem.SessionBoundary,
    uriHandler: UriHandler,
    modifier: Modifier = Modifier,
    agent: ConversationAgent = ConversationAgent.Claude,
    memorySearch: MemorySearchReport = MemorySearchReport.Unknown,
) {
    val label = boundaryLabel(boundary, TimeZone.currentSystemDefault(), Locale.getDefault())
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    start = MessageContentGutter,
                    end = MessageContentGutter,
                    bottom = MessageAreaRowSpacing,
                ),
    ) {
        RuleLabelRow(label = label)
        Spacer(modifier = Modifier.height(ExplanationTopSpacing))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "${agentDisplayName(agent)} doesn't remember messages above this line.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (memorySearch.shouldOfferMemoryInstall()) {
                Text(
                    text = " Search stored knowledge with a memory plugin. ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                TextButton(
                    onClick = { uriHandler.openUri(MEMORY_PLUGIN_DOCS_URL) },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("Install")
                }
            }
        }
    }
}

/**
 * A finished compaction (#874), drawn as the design's `Session reset` rule / label / rule row with the
 * compaction label in its place. Unlike [SessionBoundaryDelimiter] it carries no explanation line and no
 * Install affordance: a compaction is not a session reset, and the row changes no above-the-line
 * de-emphasis. Stateless and inert.
 *
 * The label is client-owned copy (desktop's `compactionBoundaryTitle`): only validated counts and the
 * recognised `manual` trigger reach it, both narrowed at decode, so no claude-authored string is drawn.
 */
@Composable
fun CompactionBoundaryDivider(
    item: ThreadItem.CompactionBoundary,
    modifier: Modifier = Modifier,
) {
    RuleLabelRow(
        label = compactionBoundaryLabel(item),
        modifier =
            modifier.padding(
                start = MessageContentGutter,
                end = MessageContentGutter,
                bottom = MessageAreaRowSpacing,
            ),
    )
}

/** The design's `Session reset` row (Figma `119:3843`): hairline rule, centred body-small label, rule. */
@Composable
private fun RuleLabelRow(
    label: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(RuleLabelSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoundaryRule(modifier = Modifier.weight(1f))
        // Deliberately unweighted: Row measures a non-weighted child against the full available
        // width before the weighted rules claim any, so a long `Workspace changed to …` label wraps
        // (centred) and squeezes the rules toward zero instead of pushing anything past the viewport
        // edge. That is the degradation the narrow preview below is here to show.
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        BoundaryRule(modifier = Modifier.weight(1f))
    }
}

/** One half of the design's `Session reset` rule pair (Figma nodes `119:3846` / `119:3841`). */
@Composable
private fun BoundaryRule(modifier: Modifier = Modifier) {
    val ruleColor =
        if (LocalStaticDarkPalette.current) MaterialTheme.colorScheme.inversePrimary else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier =
            modifier
                .height(RuleThickness)
                .background(ruleColor.copy(alpha = RULE_ALPHA)),
    )
}

/** The name the thread's fixed copy uses for [agent] (#1112): the agent that runs the conversation. */
internal fun agentDisplayName(agent: ConversationAgent): String =
    when (agent) {
        ConversationAgent.Claude -> "Claude"
        ConversationAgent.Codex -> "Codex"
    }

internal fun boundaryLabel(
    boundary: ThreadItem.SessionBoundary,
    timeZone: TimeZone,
    locale: Locale,
): String {
    val time = formatShortTime(boundary.occurredAt, timeZone, locale)
    return when (boundary.reason) {
        BoundaryReason.Clear -> "New session — $time"
        BoundaryReason.WorkspaceChange -> "Workspace changed to ${boundary.workspaceCwd!!} — $time"
        BoundaryReason.IdleEvict -> "Idle session ended — $time"
    }
}

/**
 * Desktop's `compactionBoundaryTitle`, without its failed branch (mobile draws the divider from
 * `compaction_boundary` only): "Conversation compacted", then the sizes only when both counts are known,
 * then " by you" for a manual compaction. A null count claims no size — never "→ 0".
 */
internal fun compactionBoundaryLabel(item: ThreadItem.CompactionBoundary): String {
    val pre = item.preTokens
    val post = item.postTokens
    val sizes = if (pre != null && post != null) ", ${compactionTokenCount(pre)} → ${compactionTokenCount(post)} tokens" else ""
    val byYou = if (item.manual) " by you" else ""
    return "Conversation compacted$sizes$byYou"
}

/**
 * Desktop's `tokenCount` for a validated non-negative [value]: the plain number below 1000, otherwise
 * thousands to one decimal, rounded half-up, with a `.0` dropped — `24000` → `24k`, `1250` → `1.3k`.
 * Integer arithmetic, so the result never depends on the locale.
 */
internal fun compactionTokenCount(value: Long): String {
    if (value < 1000) return value.toString()
    val tenths = (value + 50) / 100
    val whole = tenths / 10
    val fraction = tenths % 10
    return if (fraction == 0L) "${whole}k" else "$whole.${fraction}k"
}

internal fun formatShortTime(
    instant: Instant,
    timeZone: TimeZone,
    locale: Locale,
): String {
    val localTime = instant.toLocalDateTime(timeZone).time.toJavaLocalTime()
    return DateTimeFormatter
        .ofLocalizedTime(FormatStyle.SHORT)
        .withLocale(locale)
        .format(localTime)
}

private val PreviewInstant: Instant = Instant.parse("2026-05-17T14:32:00Z")

private fun previewClearBoundary() =
    ThreadItem.SessionBoundary(
        previousSessionId = "s0",
        newSessionId = "s1",
        reason = BoundaryReason.Clear,
        occurredAt = PreviewInstant,
        workspaceCwd = null,
    )

private fun previewWorkspaceChangeBoundary() =
    ThreadItem.SessionBoundary(
        previousSessionId = "s1",
        newSessionId = "s2",
        reason = BoundaryReason.WorkspaceChange,
        occurredAt = PreviewInstant,
        workspaceCwd = "~/Workspace/Projects/KitchenClaw",
    )

private fun previewIdleEvictBoundary() =
    ThreadItem.SessionBoundary(
        previousSessionId = "s2",
        newSessionId = "s3",
        reason = BoundaryReason.IdleEvict,
        occurredAt = PreviewInstant,
        workspaceCwd = null,
    )

@Composable
private fun SessionBoundaryDelimiterPreviewMatrix() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SessionBoundaryDelimiter(boundary = previewClearBoundary())
        SessionBoundaryDelimiter(boundary = previewWorkspaceChangeBoundary())
        SessionBoundaryDelimiter(boundary = previewIdleEvictBoundary())
        CompactionBoundaryDivider(item = ThreadItem.CompactionBoundary(24000, 3000, manual = true, occurredAt = PreviewInstant))
        CompactionBoundaryDivider(item = ThreadItem.CompactionBoundary(182_450, 21_300, manual = false, occurredAt = PreviewInstant))
        CompactionBoundaryDivider(item = ThreadItem.CompactionBoundary(24000, null, manual = false, occurredAt = PreviewInstant))
    }
}

@Preview(name = "SessionBoundaryDelimiter — Light", showBackground = true, widthDp = 412)
@Composable
private fun SessionBoundaryDelimiterLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface { SessionBoundaryDelimiterPreviewMatrix() }
    }
}

@Preview(
    name = "SessionBoundaryDelimiter — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun SessionBoundaryDelimiterDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface { SessionBoundaryDelimiterPreviewMatrix() }
    }
}

@Preview(
    name = "SessionBoundaryDelimiter — Narrow",
    showBackground = true,
    widthDp = 320,
)
@Composable
private fun SessionBoundaryDelimiterNarrowPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface { SessionBoundaryDelimiterPreviewMatrix() }
    }
}
