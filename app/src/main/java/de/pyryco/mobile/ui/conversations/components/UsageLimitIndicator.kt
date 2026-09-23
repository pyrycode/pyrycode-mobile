package de.pyryco.mobile.ui.conversations.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.pyryco.mobile.R
import de.pyryco.mobile.data.repository.UsageLimitReading
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

private val IndicatorHorizontalPadding = 16.dp
private val IndicatorVerticalPadding = 8.dp
private val IconSize = 16.dp
private val IconLabelGap = 8.dp

/** The most characters of claude's `status` this row shows; the daemon's own cap is not a layout bound. */
private const val MAX_STATUS_CHARS = 40

/**
 * The furthest ahead a `resets_at` may lie and still be formatted. The observed windows are five hours and
 * seven days, so 31 days is generous headroom while keeping claude's unvalidated number (year-40000 values
 * are representable) away from any date constructor.
 */
private const val MAX_RESET_HORIZON_SECONDS = 31L * 24 * 60 * 60

/** The daemon's `truncated_fields` name for the status field. */
private const val STATUS_FIELD = "status"

private const val ELLIPSIS = "…"

/**
 * Status-area arm for claude's usage-limit report (#802's `rate_limited` projection, hoisted as
 * [de.pyryco.mobile.ui.conversations.thread.ThreadViewModel.usageLimit]), so a turn waiting on a usage
 * limit says why rather than leaving the user watching a spinner (#804).
 *
 * **The copy is attributed reportage and never a verdict.** A `rate_limited` frame is not proof that a
 * turn was blocked — the one measured non-benign status rode an account whose turns all ran normally — so
 * the lead says what claude reported and nothing here says "limited", "reached" or "lifted". claude's
 * `status` is rendered as an opaque label inside that lead and no branch reads its value; `limitType` is
 * not rendered at all. Every untrusted field goes through a render-or-decline helper below, so the worst
 * a hostile reading costs is one row of inert text with no date and no percent.
 *
 * Stateless and total: it emits nothing for `null`, the sibling early-return idiom. An info glyph rather
 * than the siblings' spinner, because a usage-limit report is not progress. The merged content
 * description is the visible label itself, so the wording has one source.
 */
@Composable
fun UsageLimitIndicator(
    reading: UsageLimitReading?,
    modifier: Modifier = Modifier,
) {
    if (reading == null) return
    val now = remember(reading) { Clock.System.now() }
    val status = usageLimitStatusLabel(reading.status, reading.truncatedFields)
    val spent = usageLimitSpentPercent(reading.utilization)
    val resets = formatUsageLimitReset(reading.resetsAt, now, TimeZone.currentSystemDefault(), Locale.getDefault())
    val label =
        buildString {
            append(
                if (status != null) {
                    stringResource(R.string.thread_usage_limit_label, status)
                } else {
                    stringResource(R.string.thread_usage_limit_label_no_status)
                },
            )
            if (spent != null) append(stringResource(R.string.thread_usage_limit_spent, spent))
            if (resets != null) append(stringResource(R.string.thread_usage_limit_resets, resets))
        }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(
                    horizontal = IndicatorHorizontalPadding,
                    vertical = IndicatorVerticalPadding,
                ).semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IconLabelGap),
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            modifier = Modifier.size(IconSize),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * claude's `status` as an inert display label, or `null` when nothing printable is left.
 *
 * ISO-control and Unicode format characters become spaces — the format class holds the bidi overrides,
 * which could otherwise visually reorder the client-owned clauses that follow the label. The result is
 * cut to [MAX_STATUS_CHARS], and an ellipsis marks a cut made here **or** one the daemon reported in
 * [truncatedFields], so claude's cut text is never presented as complete. No comparison against any
 * status value happens here or anywhere on the render path.
 */
internal fun usageLimitStatusLabel(
    status: String,
    truncatedFields: List<String>?,
): String? {
    val printable =
        status
            .map { if (it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt()) ' ' else it }
            .joinToString("")
            .trim()
    if (printable.isEmpty()) return null
    val cutHere = printable.length > MAX_STATUS_CHARS
    val shown = printable.take(MAX_STATUS_CHARS)
    return if (cutHere || truncatedFields?.contains(STATUS_FIELD) == true) shown + ELLIPSIS else shown
}

/**
 * How much of the window claude says is spent, as a whole percent — or `null` when claude reported none,
 * or reported something outside `0.0..1.0` or non-finite. Absence is never rendered as zero (that would
 * show a fresh window as an exhausted one), and an out-of-range value is declined rather than clamped.
 */
internal fun usageLimitSpentPercent(utilization: Double?): Int? =
    utilization
        ?.takeIf { it.isFinite() && it in 0.0..1.0 }
        ?.let { (it * 100).roundToInt() }

/**
 * When claude says the limit lifts, or `null` when there is nothing honest to show. The range checks come
 * before any date exists: `0` means claude reported no reset (not the epoch), a past instant has nothing
 * to name, and anything beyond [MAX_RESET_HORIZON_SECONDS] is out of range. A reset later today renders
 * as a localized short time; any other day carries its date too, so a seven-day reset never reads as
 * "later today".
 */
internal fun formatUsageLimitReset(
    resetsAt: Long,
    now: Instant,
    timeZone: TimeZone,
    locale: Locale,
): String? {
    if (resetsAt <= 0L) return null
    val ahead = resetsAt - now.epochSeconds
    if (ahead <= 0L || ahead > MAX_RESET_HORIZON_SECONDS) return null
    val at = Instant.fromEpochSeconds(resetsAt).toLocalDateTime(timeZone)
    val sameDay = at.date == now.toLocalDateTime(timeZone).date
    val formatter =
        if (sameDay) {
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        } else {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        }
    return formatter.withLocale(locale).format(at.toJavaLocalDateTime())
}

@Preview(name = "UsageLimitIndicator — Light", showBackground = true, widthDp = 412)
@Composable
private fun UsageLimitIndicatorLightPreview() {
    PyrycodeMobileTheme(darkTheme = false) {
        Surface {
            UsageLimitIndicator(
                reading =
                    UsageLimitReading(
                        status = "allowed_warning",
                        limitType = "seven_day",
                        resetsAt = 0L,
                        utilization = 0.94,
                        truncatedFields = null,
                    ),
            )
        }
    }
}

@Preview(
    name = "UsageLimitIndicator — Dark",
    showBackground = true,
    widthDp = 412,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun UsageLimitIndicatorDarkPreview() {
    PyrycodeMobileTheme(darkTheme = true) {
        Surface {
            UsageLimitIndicator(
                reading =
                    UsageLimitReading(
                        status = "allowed_warning",
                        limitType = "seven_day",
                        resetsAt = 0L,
                        utilization = null,
                        truncatedFields = null,
                    ),
            )
        }
    }
}
