package de.pyryco.mobile.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import de.pyryco.mobile.ui.conversations.components.MESSAGE_BUBBLE_TEST_TAG

/** The live scenario's exact tool command and reply constraint, shared with its controlled regression. */
internal fun notePrompt(
    marker: String,
    fileName: String,
    reply: String,
): String =
    "Run this exact shell command with your tools: `printf '# $marker\\n' > $fileName`. " +
        "Do not use Write or Edit, and do not create any other file. After the command returns, $reply."

internal fun markdownNoteLinkMatcher(linkText: String): SemanticsMatcher =
    hasText(linkText, substring = true) and hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG)) and
        SemanticsMatcher("has the rendered note link") { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text ->
                text.getLinkAnnotations(0, text.length).any { link -> text.substring(link.start, link.end) == linkText }
            }
        }
