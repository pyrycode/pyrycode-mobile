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
import de.pyryco.mobile.data.repository.BoundaryReason
import de.pyryco.mobile.data.repository.ThreadItem
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalTime
import kotlinx.datetime.toLocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal const val MEMORY_PLUGIN_DOCS_URL: String = "https://pyryco.de/docs/memory-plugins"

// Figma 16:8 `Session reset` (119:3843): a 12dp-gap centred row of hairline rule / body-small label /
// hairline rule. Gutter and inter-row spacing come from `MessageBubble.kt` so the three row kinds in the
// thread share one rhythm.
private val RuleLabelSpacing = 12.dp
private val RuleThickness = 1.dp
private val ExplanationTopSpacing = 8.dp

// The design names `Schemes/inverse-primary` at 60% for the rules. #643 mapped that same token at that
// same alpha onto `outlineVariant` for the header rule — M3's divider role, and the only mapping that
// reads as a rule under both colour schemes rather than only against the dark reference frame.
private const val RULE_ALPHA = 0.60f

@Composable
fun SessionBoundaryDelimiter(
    boundary: ThreadItem.SessionBoundary,
    modifier: Modifier = Modifier,
) {
    SessionBoundaryDelimiterContent(
        boundary = boundary,
        uriHandler = LocalUriHandler.current,
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionBoundaryDelimiterContent(
    boundary: ThreadItem.SessionBoundary,
    uriHandler: UriHandler,
    modifier: Modifier = Modifier,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
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
        Spacer(modifier = Modifier.height(ExplanationTopSpacing))
        // The design draws neither the explanation nor the Install affordance, and both stay: CLAUDE.md
        // requires them under every delimiter variant, and `ScriptedSessionBoundaryTest` asserts the
        // explanation string. The restyle is of the rule-and-label arrangement above them only.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Claude doesn't remember messages above this line. Install a memory plugin to preserve context. ",
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

/** One half of the design's `Session reset` rule pair (Figma nodes `119:3846` / `119:3841`). */
@Composable
private fun BoundaryRule(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .height(RuleThickness)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = RULE_ALPHA)),
    )
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
