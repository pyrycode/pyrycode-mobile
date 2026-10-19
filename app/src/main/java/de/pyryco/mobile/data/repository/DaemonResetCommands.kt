package de.pyryco.mobile.data.repository

private val DAEMON_RESET_COMMANDS = setOf("/clear", "/reset")

/** Fixed daemon interceptions, independent of the session's published slash-command menu. */
internal fun isDaemonResetCommand(text: String): Boolean =
    text.trim { it.isDaemonWhitespace() }.takeWhile { !it.isDaemonWhitespace() } in DAEMON_RESET_COMMANDS

/** Go unicode.IsSpace includes NEL but excludes the JVM's U+001C–U+001F separators. */
private fun Char.isDaemonWhitespace(): Boolean =
    when (this) {
        in '\u0009'..'\u000D', ' ', '\u0085', '\u00A0', '\u1680',
        in '\u2000'..'\u200A', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000',
        -> true
        else -> false
    }
