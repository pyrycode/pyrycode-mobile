package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.HistoryEntryDto
import de.pyryco.mobile.data.network.MobileJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProducerReplyPreviewTest {
    @Test fun previewUsesOnlyTheCompletedMainLaneAfterItsToolSeam() {
        val entries =
            listOf(
                entry(1, "assistant_delta", "\"seq\":0,\"text\":\"before\""),
                entry(2, "tool_use"),
                entry(3, "assistant_delta", "\"seq\":1,\"text\":\"after\""),
                entry(4, "assistant_delta", "\"seq\":0,\"text\":\"child\",\"parent_tool_use_id\":\"agent\""),
                entry(5, "turn_end"),
            )
        assertEquals("after", completedProducerReply(entries.reversed(), "turn"))
        assertNull(completedProducerReply(entries, "another-turn"))
    }

    @Test fun missingTerminalCannotAuthorizeAPreview() {
        assertNull(completedProducerReply(listOf(entry(1, "assistant_delta", "\"seq\":0,\"text\":\"prefix\"")), "turn"))
    }

    @Test fun gapsAndDuplicatesCannotAuthorizeAPreview() {
        val first = entry(1, "assistant_delta", "\"seq\":0,\"text\":\"prefix\"")
        val terminal = entry(4, "turn_end")
        assertNull(completedProducerReply(listOf(first, entry(3, "assistant_delta", "\"seq\":2,\"text\":\"tail\""), terminal), "turn"))
        assertNull(completedProducerReply(listOf(first, first, terminal), "turn"))
    }

    private fun entry(
        id: Int,
        type: String,
        fields: String = "",
    ) = HistoryEntryDto(
        id.toULong(),
        type,
        MobileJson.parseToJsonElement("{\"turn_id\":\"turn\"" + fields.takeIf { it.isNotEmpty() }?.let { ",$it" }.orEmpty() + "}"),
        "2026-10-21T00:00:00Z",
    )
}
