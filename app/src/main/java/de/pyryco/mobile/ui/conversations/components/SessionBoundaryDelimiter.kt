package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
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

private val DelimiterVerticalPadding = 12.dp
private val DelimiterHorizontalPadding = 16.dp
private val LabelTopSpacing = 8.dp
private val ExplanationTopSpacing = 4.dp

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
                .padding(vertical = DelimiterVerticalPadding),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(LabelTopSpacing))
        Text(
            text = label,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DelimiterHorizontalPadding),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(ExplanationTopSpacing))
        FlowRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DelimiterHorizontalPadding),
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
