package de.pyryco.mobile.data.model

import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class MessageTest {
    private fun sample() =
        Message(
            id = "msg-1",
            sessionId = "sess-1",
            role = Role.User,
            content = "hello",
            timestamp = Instant.fromEpochSeconds(0),
            isStreaming = false,
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
        val copy = original.copy(content = "goodbye")
        assertEquals("goodbye", copy.content)
        assertEquals(original.id, copy.id)
        assertNotEquals(original, copy)
    }

    @Test
    fun copy_produces_a_new_instance() {
        val original = sample()
        assertNotSame(original, original.copy())
    }

    @Test
    fun role_has_exactly_user_assistant_tool() {
        assertEquals(listOf(Role.User, Role.Assistant, Role.Tool), Role.entries.toList())
    }
}
