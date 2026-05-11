package de.pyryco.mobile.data.model

import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class ConversationTest {

    private fun sample() = Conversation(
        id = "conv-1",
        name = "channel-name",
        cwd = "/tmp/work",
        currentSessionId = "sess-1",
        sessionHistory = listOf("sess-0", "sess-1"),
        isPromoted = true,
        lastUsedAt = Instant.fromEpochSeconds(0),
    )

    @Test
    fun equals_and_hashCode_match_for_identical_instances() {
        val a = sample()
        val b = sample()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun copy_overrides_named_field_and_preserves_rest() {
        val original = sample()
        val copy = original.copy(isPromoted = false)
        assertEquals(false, copy.isPromoted)
        assertEquals(original.id, copy.id)
        assertNotEquals(original, copy)
    }

    @Test
    fun copy_produces_a_new_instance() {
        val original = sample()
        assertNotSame(original, original.copy())
    }
}
