package de.pyryco.mobile.ui.conversations.components

import kotlin.math.absoluteValue

// The tool row's text rules (#895), mirrored from desktop's `toolHeadline.ts`, `shortenPath.ts` and
// `formatToolElapsed`. Pure and Compose-free so the thread's status components can reuse them.
//
// Every string these functions return is daemon display text: render it as inert `Text` only, never as a
// path to open, a URL, markup or a log line.

/** The subject's probe order; the first field present with a non-empty value wins. */
private val TOOL_SUBJECT_FIELDS: List<String> =
    listOf("file_path", "path", "notebook_path", "command", "pattern", "url", "query", "description")

/** Fields whose value is a path and is shortened for display. Membership is the shortening decision. */
private val TOOL_PATH_FIELDS: Set<String> = setOf("file_path", "path", "notebook_path")

/** The one tool whose input reads better description-first. Compared exactly, so `BashOutput` is not it. */
private const val BASH_TOOL_NAME = "Bash"
private val BASH_SUBJECT_FIELDS: List<String> = listOf("description", "command")

/** How many trailing path segments survive shortening: three folders plus the file name. */
private const val KEPT_PATH_SEGMENTS = 4

private const val SECONDS_PER_MINUTE = 60L

/**
 * claude's `tool_progress` reading as `12s` under a minute and `1m 05s` from one. A negative reading is an
 * upstream value, not an error, and keeps its sign.
 */
internal fun formatToolElapsed(seconds: Int): String {
    val absolute = seconds.toLong().absoluteValue
    val sign = if (seconds < 0) "-" else ""
    return if (absolute < SECONDS_PER_MINUTE) {
        "$sign${absolute}s"
    } else {
        val minutes = absolute / SECONDS_PER_MINUTE
        val remainder = (absolute % SECONDS_PER_MINUTE).toString().padStart(2, '0')
        "$sign${minutes}m ${remainder}s"
    }
}

/**
 * The collapsed row's subject: for [BASH_TOOL_NAME] its description, then its command; otherwise the first
 * of [TOOL_SUBJECT_FIELDS] with a non-empty value, shortened when it is a path; otherwise the server's
 * one-line [input] précis. A row restored from the disk cache has no [inputFields] and takes the précis.
 * "Non-empty" is `!= ""`, as on desktop — a whitespace-only value is left unguarded on purpose.
 */
internal fun toolRowSubject(
    toolName: String,
    inputFields: Map<String, String>,
    input: String,
): String {
    val picked =
        (if (toolName == BASH_TOOL_NAME) firstNonEmpty(inputFields, BASH_SUBJECT_FIELDS) else null)
            ?: firstNonEmpty(inputFields, TOOL_SUBJECT_FIELDS)
            ?: return input
    val (key, value) = picked
    return if (key in TOOL_PATH_FIELDS) shortenToolPath(value) else value
}

/**
 * [path] cut to its last [KEPT_PATH_SEGMENTS] segments behind `.../`, or verbatim when it has no more than
 * that. The shortened form is display text, not a path.
 */
internal fun shortenToolPath(path: String): String {
    val segments = path.split('/').filter { it.isNotEmpty() }
    if (segments.size <= KEPT_PATH_SEGMENTS) return path
    return ".../" + segments.takeLast(KEPT_PATH_SEGMENTS).joinToString("/")
}

private fun firstNonEmpty(
    inputFields: Map<String, String>,
    fields: List<String>,
): Pair<String, String>? =
    fields.firstNotNullOfOrNull { key ->
        inputFields[key]?.takeIf { it.isNotEmpty() }?.let { value -> key to value }
    }
