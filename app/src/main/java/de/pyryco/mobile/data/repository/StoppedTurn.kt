package de.pyryco.mobile.data.repository

/** The largest report token, in UTF-8 bytes, that is shown; a longer one is treated as absent (desktop's bound). */
private const val MAX_REPORT_BYTES = 256

/** The character types desktop's `stoppedReportText` removes: `\p{Cc}`, `\p{Cf}`, `\p{Zl}` and `\p{Zp}`. */
private val REMOVED_TYPES =
    setOf(
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
    )

/**
 * One agent-authored report token as inert display text (#1356), copied from desktop's `stoppedReportText`:
 * empty when [value] is over [MAX_REPORT_BYTES] UTF-8 bytes, else [value] with every control, format (the
 * bidi overrides and isolates among them), line-separator and paragraph-separator code point removed —
 * removed, not replaced, and never cut. Idempotent.
 */
internal fun stoppedReportText(value: String): String {
    if (utf8Length(value) > MAX_REPORT_BYTES) return ""
    val kept = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        if (Character.getType(codePoint) !in REMOVED_TYPES) kept.appendCodePoint(codePoint)
        index += Character.charCount(codePoint)
    }
    return kept.toString()
}

/** [value]'s UTF-8 length, counting a lone surrogate as the three bytes of the U+FFFD an encoder writes for it. */
private fun utf8Length(value: String): Int {
    var bytes = 0
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        bytes +=
            when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
        index += Character.charCount(codePoint)
    }
    return bytes
}
