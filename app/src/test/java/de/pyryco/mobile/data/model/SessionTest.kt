package de.pyryco.mobile.data.model

import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class SessionTest {
    private fun sample() =
        Session(
            id = "sess-1",
            conversationId = "conv-1",
            claudeSessionUuid = "00000000-0000-0000-0000-000000000001",
            startedAt = Instant.fromEpochSeconds(0),
            endedAt = null,
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
        val copy = original.copy(endedAt = Instant.fromEpochSeconds(60))
        assertEquals(Instant.fromEpochSeconds(60), copy.endedAt)
        assertEquals(original.id, copy.id)
        assertNotEquals(original, copy)
    }

    @Test
    fun copy_produces_a_new_instance() {
        val original = sample()
        assertNotSame(original, original.copy())
    }
}
