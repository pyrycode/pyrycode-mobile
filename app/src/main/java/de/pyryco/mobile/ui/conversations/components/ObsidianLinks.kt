package de.pyryco.mobile.ui.conversations.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.net.toUri
import de.pyryco.mobile.R
import de.pyryco.mobile.data.network.RelayLog
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

private val ObsidianOpenKeys = setOf("vault", "file", "path", "paneType")
private val EncodedObsidianSeparator = Regex("%(26|23)", RegexOption.IGNORE_CASE)

/** Desktop's read-only Obsidian rule. Validation never rewrites the target handed to Android. */
internal fun isSafeObsidianLink(target: String): Boolean {
    // String ignoreCase also equates Unicode lookalikes (for example dotless i) with ASCII letters.
    if (target.substringBefore(':').lowercase() != "obsidian") return false
    if (target.substringBefore('?').substringAfter(':') != "//open" || '#' in target) return false
    val query = target.substringAfter('?', "")
    if (EncodedObsidianSeparator.containsMatchIn(query)) return false
    return query.split('&').all { pair ->
        if (pair.isEmpty()) return@all true
        val key = pair.substringBefore('=')
        if (key !in ObsidianOpenKeys) return@all false
        val value = decodeObsidianValue(pair.substringAfter('=', "")) ?: return@all false
        key != "file" && key != "path" || opensAsObsidianNote(value)
    }
}

private fun opensAsObsidianNote(value: String): Boolean {
    if (':' in value) return false
    val name = value.replace('\\', '/').trimEnd('/').substringAfterLast('/')
    return '.' !in name || name.endsWith(".md", ignoreCase = true)
}

/** decodeURIComponent semantics: strict percent escapes/UTF-8, and a literal '+' stays a plus. */
private fun decodeObsidianValue(raw: String): String? {
    val decoded = StringBuilder(raw.length)
    var index = 0
    while (index < raw.length) {
        if (raw[index] != '%') {
            decoded.append(raw[index++])
            continue
        }
        val bytes = ByteArrayOutputStream()
        while (index < raw.length && raw[index] == '%') {
            if (index + 2 >= raw.length) return null
            val hex = raw.substring(index + 1, index + 3)
            if (!hex.all { it in "0123456789abcdefABCDEF" }) return null
            bytes.write(hex.toInt(16))
            index += 3
        }
        val text =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
            } catch (_: CharacterCodingException) {
                return null
            }
        decoded.append(text)
    }
    return decoded.toString()
}

internal enum class MarkdownLinkNotice(
    @param:StringRes val message: Int,
) {
    OBSIDIAN_NOT_INSTALLED(R.string.markdown_link_obsidian_not_installed),
    OPEN_FAILED(R.string.markdown_link_open_failed),
}

/** Screen-owned sink; only static notices cross it, never the URL or platform exception. */
internal val LocalMarkdownLinkFailure = staticCompositionLocalOf<(MarkdownLinkNotice) -> Unit> { {} }

internal fun openObsidianLink(
    context: Context,
    target: String,
): MarkdownLinkNotice? {
    if (!isSafeObsidianLink(target)) return null
    val intent = Intent(Intent.ACTION_VIEW, target.toUri())
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val notice =
        try {
            context.startActivity(intent)
            null
        } catch (_: ActivityNotFoundException) {
            MarkdownLinkNotice.OBSIDIAN_NOT_INSTALLED
        } catch (_: Exception) {
            MarkdownLinkNotice.OPEN_FAILED
        }
    RelayLog.d { "event=markdown_link_open outcome=${notice?.name ?: "opened"}" }
    return notice
}
