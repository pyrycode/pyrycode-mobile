package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.runtime.Immutable
import de.pyryco.mobile.data.repository.ReplySuggestion

/** One destination's current explicit-send affordance. Identity, rather than text equality, authorizes it. */
@Immutable
class SuggestedReply internal constructor(
    val text: String,
    internal val reading: ReplySuggestion,
) {
    override fun toString(): String = "SuggestedReply(redacted)"
}
