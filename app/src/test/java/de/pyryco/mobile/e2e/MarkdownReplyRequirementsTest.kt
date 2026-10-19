package de.pyryco.mobile.e2e

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownReplyRequirementsTest {
    private val requirements = MarkdownReplyRequirements("bold-marker", "inline-marker", "code-marker", "cell-marker", "end-marker")
    private val body = "**bold-marker** `inline-marker`\n\n```text\ncode-marker\n```\n\n| Key | Value |\n| --- | --- |\n| cell-marker | present |\n\nend-marker"

    @Test fun acceptsActualProseAndEquivalentMarkdownLayouts() {
        assertTrue(requirements.satisfiedBy(body))
        assertTrue(requirements.satisfiedBy("Here is the requested reply.\n\n" + body.replace("**bold-marker**", "__bold-marker__")
            .replace("```text", "~~~text").replace("```", "~~~")
            .replace("| cell-marker | present |", "|cell-marker|present|") + "\n\nAdditional prose."))
    }

    @Test fun preservesRequiredConstructsAndMarkerValues() {
        for ((old, replacement) in listOf("**bold-marker**" to "bold-marker", "`inline-marker`" to "inline-marker",
            "code-marker" to "other-code", "cell-marker" to "other-cell", "end-marker" to "other-end")) {
            assertFalse(requirements.satisfiedBy(body.replace(old, replacement)))
        }
        assertFalse(requirements.satisfiedBy("````markdown\n$body\n````"))
        assertFalse(requirements.satisfiedBy("````markdown\n$body\n````\n\nend-marker"))
    }
}
