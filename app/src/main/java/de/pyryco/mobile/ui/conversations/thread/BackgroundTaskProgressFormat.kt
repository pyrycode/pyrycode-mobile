package de.pyryco.mobile.ui.conversations.thread

import android.content.res.Resources
import de.pyryco.mobile.R

private const val TOKENS_PER_K = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L

/**
 * A running task's three counters as meta-line segments (#1044), in order: tool count, tokens, elapsed.
 * Client-owned: only claude's three integers feed it, never a daemon string. The counters are not
 * guaranteed monotonic, so a negative reading prints as zero rather than a negative number.
 */
internal fun progressCounters(
    resources: Resources,
    toolUses: Long,
    totalTokens: Long,
    durationMs: Long,
): List<String> {
    val tools = toolUses.coerceAtLeast(0)
    val tokens = totalTokens.coerceAtLeast(0)
    return listOf(
        resources.getQuantityString(R.plurals.background_tasks_progress_tools, quantity(tools), tools),
        resources.getQuantityString(R.plurals.background_tasks_progress_tokens, quantity(tokens), tokenFigure(resources, tokens)),
        elapsed(resources, durationMs.coerceAtLeast(0) / 1000),
    )
}

private fun quantity(count: Long): Int = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/** Whole under a thousand; from a thousand up, thousands rounded half-up, without overflowing near the limit. */
private fun tokenFigure(
    resources: Resources,
    tokens: Long,
): String {
    if (tokens < TOKENS_PER_K) return tokens.toString()
    val thousands = tokens / TOKENS_PER_K + if (tokens % TOKENS_PER_K >= TOKENS_PER_K / 2) 1 else 0
    return resources.getString(R.string.background_tasks_progress_thousands, thousands)
}

private fun elapsed(
    resources: Resources,
    seconds: Long,
): String =
    when {
        seconds < SECONDS_PER_MINUTE -> resources.getString(R.string.background_tasks_elapsed_seconds, seconds)
        seconds < SECONDS_PER_HOUR ->
            resources.getString(R.string.background_tasks_elapsed_minutes, seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
        else ->
            resources.getString(
                R.string.background_tasks_elapsed_hours,
                seconds / SECONDS_PER_HOUR,
                seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE,
            )
    }
