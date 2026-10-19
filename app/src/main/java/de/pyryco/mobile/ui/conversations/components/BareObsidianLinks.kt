package de.pyryco.mobile.ui.conversations.components

private val BareObsidianStart = Regex("obsidian://open", RegexOption.IGNORE_CASE)
private const val BARE_LINK_OPENERS = "(\"'“‘"
private const val BARE_LINK_TRAILING_PUNCTUATION = ".,;)!?\"'”’"

/** Exact source ranges only: shared admission decides safety, without decoding or rewriting a target. */
internal fun bareObsidianRanges(
    source: String,
    content: IntRange = source.indices,
    arrivingSourceEnd: Int? = null,
    synthetic: IntRange = IntRange.EMPTY,
): List<IntRange> {
    val result = mutableListOf<IntRange>()
    val region = source.substring(content)
    val limit = content.last + 1
    var searchAt = content.first
    while (searchAt < limit) {
        val match = BareObsidianStart.find(region, searchAt - content.first) ?: break
        val start = content.first + match.range.first
        searchAt = content.first + match.range.last + 1
        if (start != content.first && !source[start - 1].isWhitespace() && source[start - 1] !in BARE_LINK_OPENERS) continue
        var end = searchAt
        while (end < limit && !source[end].isWhitespace()) end++
        // Never discover another target inside a refused token.
        searchAt = end
        if (!synthetic.isEmpty() && start <= synthetic.last && end > synthetic.first) continue
        val realEnd = end - if (!synthetic.isEmpty() && end > synthetic.last) synthetic.count() else 0
        // Sentence punctuation and synthetic Markdown closers cannot complete an arriving candidate.
        if (realEnd == arrivingSourceEnd) continue
        val candidate = source.substring(start, end)
        if (isSafeLinkScheme(candidate)) {
            result += start until end
            continue
        }
        val target = candidate.trimEnd { it in BARE_LINK_TRAILING_PUNCTUATION }
        if (target.isNotEmpty() && isSafeLinkScheme(target)) result += start until start + target.length
    }
    return result
}
