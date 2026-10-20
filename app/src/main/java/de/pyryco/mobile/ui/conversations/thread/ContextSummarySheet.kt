package de.pyryco.mobile.ui.conversations.thread

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.repository.ContextCategory
import de.pyryco.mobile.data.repository.ContextUsage
import de.pyryco.mobile.ui.components.MobileDismissModal
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import java.util.Locale
import kotlin.math.floor

/** Read-only context display. Daemon names reach Text only, never actions or identity keys. */
@Composable
internal fun ContextSummarySheet(
    state: ThreadUiState,
    onEvent: (ThreadEvent) -> Unit,
) {
    val config = state.runConfig
    val reading = config.contextUsage
    val claude = state.agent == ConversationAgent.Claude
    val used = reading?.totalTokens ?: config.contextUsedTokens
    val maximum = reading?.maxTokens ?: config.contextMaxTokens
    val pending = if (claude) reading == null else used == null || maximum == null || maximum <= 0
    MobileDismissModal(
        title = stringResource(R.string.thread_context_title),
        actionLabel = stringResource(R.string.thread_context_done),
        onDismissRequest = { onEvent(ThreadEvent.ContextDismiss) },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            if (pending) {
                Text(stringResource(R.string.thread_context_pending), style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (claude && reading != null) {
                        Text(
                            reading.model,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(
                                R.string.thread_context_total,
                                formatContextTokens(used ?: 0),
                                formatContextTokens(maximum ?: 0),
                            ),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            config.contextPercent?.let { stringResource(R.string.thread_context_percent, it) }
                                ?: stringResource(R.string.thread_context_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                // Remembered empty lists are absence of stored detail, never a fresh empty breakdown.
                if (claude &&
                    reading != null &&
                    reading.asOf == null &&
                    (reading.categories.isNotEmpty() || reading.droppedCategories > 0)
                ) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(R.string.thread_context_categories),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                        reading.categories.forEachIndexed { index, category ->
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        category.name,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onBackground,
                                    )
                                    Text(
                                        formatContextTokens(category.tokens),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                val fraction = contextBarPercent(category.tokens, reading.maxTokens) / 100f
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(MaterialTheme.shapes.extraSmall)
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .testTag("context_category_bar_$index")
                                        .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f) },
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxHeight()
                                            .fillMaxWidth(
                                                fraction,
                                            ).background(MaterialTheme.colorScheme.primary)
                                            .testTag("context_category_fill_$index"),
                                    )
                                }
                            }
                        }
                        if (reading.droppedCategories > 0) {
                            Text(
                                stringResource(R.string.thread_context_more, reading.droppedCategories),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun formatContextTokens(tokens: Long): String {
    if (tokens < 1000) return tokens.toString()
    val rounded = floor(tokens.toDouble() / 100 + 0.5) / 10
    return String.format(Locale.ROOT, "%.1f", rounded).removeSuffix(".0") + "k"
}

internal fun contextBarPercent(
    tokens: Long,
    maximum: Long,
): Float = if (maximum <= 0) 0f else (tokens.toDouble() * 100 / maximum.toDouble()).coerceIn(0.0, 100.0).toFloat()

@Preview(name = "Context — Light", widthDp = 412, heightDp = 892)
@Preview(name = "Context — Dark", widthDp = 412, heightDp = 892, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ContextSummaryPreview() {
    PyrycodeMobileTheme {
        val reading = ContextUsage(84_200, 200_000, 42, null, "claude-sonnet-4-5", listOf(ContextCategory("Messages", 53_900)), 2)
        ContextSummarySheet(
            ThreadUiState("preview", "Thread", runConfig = ThreadRunConfig(contextUsage = reading, contextPercent = 42)),
            {},
        )
    }
}
