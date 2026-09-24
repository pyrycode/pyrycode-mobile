package de.pyryco.mobile.ui.conversations.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import de.pyryco.mobile.R
import de.pyryco.mobile.data.repository.UsageLimitReading
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

/** The most characters of claude's `status` the label shows; the daemon's own cap is not a layout bound. */
private const val MAX_STATUS_CHARS = 40

/**
 * The furthest ahead a `resets_at` may lie and still be formatted. The observed windows are five hours and
 * seven days, so 31 days is generous headroom while keeping claude's unvalidated number (year-40000 values
 * are representable) away from any date constructor.
 */
private const val MAX_RESET_HORIZON_SECONDS = 31L * 24 * 60 * 60

/** The one status besides upstream's benign `allowed` that anything names: a warning the operator may hide. */
private const val WARNING_STATUS = "allowed_warning"

/** The daemon's `truncated_fields` name for the status field. */
private const val STATUS_FIELD = "status"

private const val ELLIPSIS = "…"

/**
 * The Top overlay's label for claude's usage-limit report (#802's `rate_limited` projection, hoisted as
 * [de.pyryco.mobile.ui.conversations.thread.ThreadViewModel.usageLimit]). #804 drew it as a status-area
 * arm; #1002 moved it to a pill in the thread's Top overlay so it never hides live turn status.
 *
 * **The copy is attributed reportage and never a verdict.** A `rate_limited` frame is not proof that a
 * turn was blocked — the one measured non-benign status rode an account whose turns all ran normally — so
 * the lead says what claude reported and nothing here says "limited", "reached" or "lifted". claude's
 * `status` is rendered as an opaque label inside that lead and no branch of the label reads its value;
 * `limitType` is not rendered at all. Every untrusted field goes through a render-or-decline helper below,
 * so the worst a hostile reading costs is one pill of inert text with no date and no percent.
 */
@Composable
internal fun usageLimitLabel(reading: UsageLimitReading): String {
    val now = remember(reading) { Clock.System.now() }
    val status = usageLimitStatusLabel(reading.status, reading.truncatedFields)
    val spent = usageLimitSpentPercent(reading.utilization)
    val resets = formatUsageLimitReset(reading.resetsAt, now, TimeZone.currentSystemDefault(), Locale.getDefault())
    return buildString {
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
}

/**
 * Whether [reading] is the warning the Top overlay lets the operator hide (#1002). Exact equality and
 * nothing else: any other status, recognised or not, is an Error pill with no X, so nothing unknown can
 * be hidden. This picks the pill's variant only; the label never branches on the status.
 */
internal fun usageLimitIsWarning(reading: UsageLimitReading): Boolean = reading.status == WARNING_STATUS

/**
 * claude's `status` as an inert display label, or `null` when nothing printable is left.
 *
 * ISO-control and Unicode format characters become spaces — the format class holds the bidi overrides,
 * which could otherwise visually reorder the client-owned clauses that follow the label. The result is
 * cut to [MAX_STATUS_CHARS], and an ellipsis marks a cut made here **or** one the daemon reported in
 * [truncatedFields], so claude's cut text is never presented as complete. No comparison against any
 * status value happens here or in the label; only [usageLimitIsWarning] names one, to pick the pill.
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
