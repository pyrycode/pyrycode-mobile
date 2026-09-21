package de.pyryco.mobile.ui.workspace

import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD

/**
 * Upper bound on the rendered label, in UTF-16 units.
 *
 * The daemon stores labels under a 128-byte bound, but the protocol is explicit that this is a size
 * limit and **not** a safety property — bounding what reaches text layout is the client's job. 128
 * UTF-16 units is an upper bound on 128 UTF-8 bytes, so this clamp can never truncate a
 * protocol-conformant label; it fires only on a non-conformant one.
 */
internal const val MAX_WORKSPACE_LABEL_CHARS: Int = 128

/**
 * The one workspace display rule: prefer the operator's chosen label, else derive text from [cwd].
 *
 * - A non-blank [label] wins, verbatim except for the [MAX_WORKSPACE_LABEL_CHARS] clamp — it is
 *   opaque daemon-authored text and is not trimmed, escaped or otherwise rewritten. It applies
 *   unconditionally, so a named scratch workspace shows its name.
 * - Otherwise an empty [cwd] or [DEFAULT_SCRATCH_CWD] yields `"scratch"`.
 * - Otherwise the last path segment of [cwd], or the whole [cwd] when that segment is empty.
 *
 * The result is **display text only**: never an identity, never a path, and never a log line. Callers
 * that need the real working directory must read `Conversation.cwd` instead — a label never replaces
 * it. Render the result as plain text; it must not reach markup, a URL, a filename or a cache key.
 */
fun workspaceDisplayName(
    cwd: String,
    label: String?,
): String {
    val chosen = label?.takeIf { it.isNotBlank() }
    if (chosen != null) return chosen.take(MAX_WORKSPACE_LABEL_CHARS)
    return if (cwd.isEmpty() || cwd == DEFAULT_SCRATCH_CWD) {
        "scratch"
    } else {
        cwd.substringAfterLast('/').ifEmpty { cwd }
    }
}
