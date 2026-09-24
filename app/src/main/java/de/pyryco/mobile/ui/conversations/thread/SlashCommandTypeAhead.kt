package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import de.pyryco.mobile.data.repository.SlashCommandMenuRow
import de.pyryco.mobile.ui.conversations.components.OptionsOverlay
import de.pyryco.mobile.ui.conversations.components.OptionsOverlayOption

// #885 — the composer's slash-command type-ahead, after desktop's `slashCommandTypeAhead.ts` and
// `ComposerSlashCommandTypeAhead.tsx`. The rules are total functions of the composer text and the
// conversation's published menu (#882); the composable at the foot holds the one piece of state the text
// cannot answer — whether the suggestions were closed while their rows still match.
//
// SECURITY. Every string on a SlashCommandMenuRow is workspace-authored and bounded but not sanitized by
// the daemon. They reach the screen only through [slashCommandOptions], as inert `Text`, and nothing here
// logs, keys or tags on them. A row's identity is its index, never its name.

/**
 * The published rows the suggestions show for [text], in display order — empty when they stay closed.
 *
 * They open only while the whole text is `/` followed by characters that are not whitespace: a slash
 * anywhere else, a space, a tab or a newline closes them, since claude only reads a message that begins
 * with the command. Matching is over the name and the aliases, never the description, ignoring case with
 * the locale-independent [lowercase]. Rows whose name or an alias starts with the fragment come first,
 * then those that contain it, then rows whose cut aliases might have matched; each group keeps the
 * daemon's order. A lone `/` is a prefix of everything, so it returns the whole list. `null` (no menu
 * received) and an empty menu return nothing.
 */
internal fun slashCommandTypeAheadRows(
    text: String,
    commands: List<SlashCommandMenuRow>?,
): List<SlashCommandMenuRow> {
    if (commands.isNullOrEmpty() || !text.startsWith('/')) return emptyList()
    val fragment = text.substring(1)
    if (fragment.any { it.isWhitespace() }) return emptyList()

    val needle = fragment.lowercase()
    val prefix = mutableListOf<SlashCommandMenuRow>()
    val contained = mutableListOf<SlashCommandMenuRow>()
    val unknown = mutableListOf<SlashCommandMenuRow>()
    for (row in commands) {
        when (rank(row, needle)) {
            Rank.Prefix -> prefix += row
            Rank.Contained -> contained += row
            // Nothing visible matched. Only a row whose alias list was cut can still be the command the
            // user means, and an empty alias list on its own says nothing.
            Rank.None -> if (row.truncatedFields.orEmpty().contains("aliases")) unknown += row
        }
    }
    return prefix + contained + unknown
}

private enum class Rank { Prefix, Contained, None }

/** A prefix hit on any candidate wins, so the scan does not stop at the first contained hit. */
private fun rank(
    row: SlashCommandMenuRow,
    needle: String,
): Rank {
    var rank = Rank.None
    for (candidate in listOf(row.name) + row.aliases) {
        val folded = candidate.lowercase()
        if (folded.startsWith(needle)) return Rank.Prefix
        if (folded.contains(needle)) rank = Rank.Contained
    }
    return rank
}

/**
 * What picking [row] puts in the composer: the canonical `/name`, even for a row matched by an alias, and
 * one trailing space exactly when the argument hint is non-empty. The test is literal, not trimmed. The
 * name is copied verbatim — it has to be the command claude recognises, and its destination is the
 * plain-text composer, where the user sees it before anything is sent.
 */
internal fun completeSlashCommand(row: SlashCommandMenuRow): String = if (row.argumentHint.isEmpty()) "/${row.name}" else "/${row.name} "

/**
 * [rows] as overlay options. The value is the row's index: a published name is not unique and not an
 * identifier. The label is `/name`, followed by the argument hint when it is non-empty, both through
 * [inert]. The detail is the description through [slashCommandDetail].
 */
internal fun slashCommandOptions(rows: List<SlashCommandMenuRow>): List<OptionsOverlayOption> =
    rows.mapIndexed { index, row ->
        val name = "/" + row.name.inert()
        OptionsOverlayOption(
            value = index.toString(),
            label = if (row.argumentHint.isEmpty()) name else name + " " + row.argumentHint.inert(),
            detail = slashCommandDetail(row.description),
        )
    }

/**
 * A published description reduced to bounded, inert display text, or `null` when nothing printable is
 * left. Line breaks and tabs become spaces, so a multi-line description still reads as prose inside the
 * row's two-line limit, and every other control character is dropped.
 */
internal fun slashCommandDetail(raw: String): String? {
    val text =
        raw
            .map { if (it == '\n' || it == '\r' || it == '\t') ' ' else it }
            .filterNot { it.isISOControl() }
            .joinToString("")
            .take(MAX_SLASH_DETAIL_CHARS)
    return text.takeIf { it.isNotBlank() }
}

private const val MAX_SLASH_DETAIL_CHARS = 240

/**
 * The most suggestions laid out at once. The overlay's column is not lazy and nothing on the client
 * bounds a menu's row count; the rest is reported through the overlay's "not listed" caption.
 */
private const val MAX_SLASH_TYPEAHEAD_ROWS = 100

/**
 * The suggestions over the composer (#885), drawn in [ThreadScreen]'s overlay layer above [anchor] (in
 * that layer's coordinates; `null` draws nothing).
 *
 * The only state is the text the suggestions were closed for. It is set by a pick, by the overlay's
 * outside tap or Back, and by the keyboard hiding, and any edit clears it — the rule desktop keys its
 * `dismissed` flag on. A pick closes against the completed text, because an un-hinted command leaves a
 * fragment that still matches. Plain `remember` keyed on [resetKey]: another conversation opens closed.
 *
 * A pick hands the completion to [onComplete] and sends nothing.
 */
@Composable
internal fun SlashCommandTypeAhead(
    text: String,
    commands: List<SlashCommandMenuRow>?,
    anchor: Rect?,
    imeVisible: Boolean,
    onComplete: (String) -> Unit,
    resetKey: Any?,
) {
    var dismissedFor by remember(resetKey) { mutableStateOf<String?>(null) }
    val currentText by rememberUpdatedState(text)
    LaunchedEffect(text) {
        if (dismissedFor != text) dismissedFor = null
    }
    LaunchedEffect(imeVisible) {
        if (!imeVisible) dismissedFor = currentText
    }

    val rows = slashCommandTypeAheadRows(text, commands)
    if (anchor == null || rows.isEmpty() || dismissedFor == text) return
    val shown = rows.take(MAX_SLASH_TYPEAHEAD_ROWS)
    OptionsOverlay(
        options = slashCommandOptions(shown),
        selectedValue = "",
        notListed = rows.size - shown.size,
        anchor = anchor,
        onSelect = { value ->
            val row = value.toIntOrNull()?.let(shown::getOrNull) ?: return@OptionsOverlay
            val completed = completeSlashCommand(row)
            dismissedFor = completed
            onComplete(completed)
        },
        onDismiss = { dismissedFor = currentText },
        actions = true,
    )
}
