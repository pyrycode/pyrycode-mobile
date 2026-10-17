package de.pyryco.mobile.ui.conversations.components

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources
import de.pyryco.mobile.R
import de.pyryco.mobile.data.repository.UsageLimitReading
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt

/**
 * The furthest ahead a `resets_at` may lie and still be formatted. The observed windows are five hours and
 * seven days, so 31 days is generous headroom while keeping claude's unvalidated number (year-40000 values
 * are representable) away from any date constructor.
 */
private const val MAX_RESET_HORIZON_SECONDS = 31L * 24 * 60 * 60

/** The one status besides upstream's benign `allowed` that the variant names: a warning the operator may hide. */
private const val WARNING_STATUS = "allowed_warning"

/** The one status that earns "Usage limit reached". Any other, known or not, reads as a warning. */
private const val EXHAUSTED_STATUS = "rejected"

/**
 * The share of the window that must be spent before the pill earns a spot, for any status besides
 * [EXHAUSTED_STATUS]. At or below this fraction the window still has room, so the pill stays hidden.
 */
private const val SHOW_THRESHOLD = 0.75

/**
 * The Top overlay's label for claude's usage-limit report (#802's `rate_limited` projection, hoisted as
 * [de.pyryco.mobile.ui.conversations.thread.ThreadViewModel.usageLimit]); see [usageLimitText] for the rule.
 */
@Composable
internal fun usageLimitLabel(reading: UsageLimitReading): String {
    val now = remember(reading) { Clock.System.now() }
    return usageLimitText(reading, now, TimeZone.currentSystemDefault(), LocalResources.current)
}

/**
 * The usage pill's one line, desktop's `usageLimitNotice` rule (#1519): a lead, the window, the percent
 * spent, then the reset.
 *
 * **Every character is client-owned copy.** claude's `status` and `limitType` are lookup keys only, compared
 * by exact equality — no trim, case fold, prefix or substring test, so `rejected_something_new` cannot pull
 * the pill into "Usage limit reached" — and neither is ever appended, so a hostile value cannot reach the
 * text. "Usage limit reached" is reserved for `rejected`; every other status reads "Nearly at usage limit",
 * because over-claiming tells an operator they are blocked while their turns keep running. Only `five_hour`
 * and `seven_day` name a window. The percent spent renders only when [usageLimitSpentPercent] has an honest
 * figure to give; the agent's name and `truncatedFields` are not read.
 */
internal fun usageLimitText(
    reading: UsageLimitReading,
    now: Instant,
    timeZone: TimeZone,
    resources: Resources,
): String {
    val window =
        when (reading.limitType) {
            "five_hour" -> R.string.thread_usage_limit_five_hour
            "seven_day" -> R.string.thread_usage_limit_seven_day
            else -> null
        }
    val percent = usageLimitSpentPercent(reading.utilization)
    val reset = formatUsageLimitReset(reading.resetsAt, now, timeZone)
    return buildString {
        append(
            resources.getString(
                if (reading.status == EXHAUSTED_STATUS) R.string.thread_usage_limit_reached else R.string.thread_usage_limit_nearly,
            ),
        )
        if (window != null) append(resources.getString(window))
        if (percent != null) append(resources.getString(R.string.thread_usage_limit_percent, percent))
        when {
            reset == null -> Unit
            reset.date == null -> append(resources.getString(R.string.thread_usage_limit_resets, reset.time))
            else -> append(resources.getString(R.string.thread_usage_limit_resets_on, reset.date, reset.time))
        }
    }
}

/**
 * The percent of the window claude says is spent, or `null` when there is nothing honest to show.
 * [UsageLimitReading.utilization] is claude's own number and **not a bounded fraction** — absence is the
 * common case and must never read as `0`, and a value outside `0.0..1.0` (or non-finite) is declined rather
 * than clamped, matching the rule on the field itself. Rounds to the nearest whole percent.
 */
internal fun usageLimitSpentPercent(utilization: Double?): Int? {
    if (utilization == null || !utilization.isFinite() || utilization < 0.0 || utilization > 1.0) return null
    return (utilization * 100).roundToInt()
}

/**
 * Whether [reading] is the warning the Top overlay lets the operator hide (#1002). Exact equality and
 * nothing else: any other status, recognised or not, is an Error pill with no X, so nothing unknown can
 * be hidden. This picks the pill's variant only.
 */
internal fun usageLimitIsWarning(reading: UsageLimitReading): Boolean = reading.status == WARNING_STATUS

/**
 * Whether [reading] earns a spot in the Top overlay at all, independent of [usageLimitIsWarning]'s pill
 * variant and of the operator's own dismissal. `rejected` always shows — the operator is blocked, and that
 * holds regardless of what [UsageLimitReading.utilization] says. For every other status, a valid finite
 * fraction in `0.0..1.0` must clear [SHOW_THRESHOLD] (75%) before the pill appears; at or below it, the
 * window still has room and nothing is shown. A missing or out-of-range utilization cannot prove the window
 * is comfortable, so it falls back to showing the pill, matching the rule before this threshold existed.
 */
internal fun usageLimitShouldShow(reading: UsageLimitReading): Boolean {
    if (reading.status == EXHAUSTED_STATUS) return true
    val utilization = reading.utilization
    if (utilization == null || !utilization.isFinite() || utilization < 0.0 || utilization > 1.0) return true
    return utilization > SHOW_THRESHOLD
}

/** A reset instant in the pill's fixed shape: [time] as `HH:MM`, and [date] as `DD.MM.YYYY` unless it is today. */
internal data class UsageLimitReset(
    val date: String?,
    val time: String,
)

/**
 * When claude says the limit lifts, or `null` when there is nothing honest to show. The range checks come
 * before any date exists: `0` means claude reported no reset (not the epoch), a past instant has nothing
 * to name, and anything beyond [MAX_RESET_HORIZON_SECONDS] is out of range. A reset on today's local date
 * carries no date; any other day does, so a seven-day reset never reads as "later today". The digits are
 * zero-padded from the local fields, never a locale's formatter, matching desktop's `formatMessageTime`.
 */
internal fun formatUsageLimitReset(
    resetsAt: Long,
    now: Instant,
    timeZone: TimeZone,
): UsageLimitReset? {
    if (resetsAt <= 0L) return null
    val ahead = resetsAt - now.epochSeconds
    if (ahead <= 0L || ahead > MAX_RESET_HORIZON_SECONDS) return null
    val at = Instant.fromEpochSeconds(resetsAt).toLocalDateTime(timeZone)
    val time = "${pad(at.hour)}:${pad(at.minute)}"
    if (at.date == now.toLocalDateTime(timeZone).date) return UsageLimitReset(date = null, time = time)
    return UsageLimitReset(date = "${pad(at.dayOfMonth)}.${pad(at.monthNumber)}.${at.year.toString().padStart(4, '0')}", time = time)
}

private fun pad(value: Int): String = value.toString().padStart(2, '0')
