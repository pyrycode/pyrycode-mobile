package de.pyryco.mobile.data.network

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * JVM unit tests for the #721 `workspace_updated` decode boundary. Pins the protocol's three label
 * states at the one place they are decided, because the repository fold that consumes them cannot
 * distinguish "cleared" from "never carried" after the fact.
 */
class WorkspaceUpdatedPayloadDtoTest {
    @Test
    fun decode_carriesPathAndLabelVerbatim() {
        val dto =
            MobileJson.decodeFromString<WorkspaceUpdatedPayloadDto>(
                """{"path":"/Users/j/pyry-workspace/alpha","label":"Tax filing"}""",
            )

        assertEquals("/Users/j/pyry-workspace/alpha", dto.path)
        assertEquals("Tax filing", dto.label)
    }

    // The protocol spells "clear" two ways — an explicit null and an omitted key — and both must mean
    // the same thing, since MobileJson runs with explicitNulls = false.
    @Test
    fun decode_explicitNullLabel_isCleared() {
        val dto = MobileJson.decodeFromString<WorkspaceUpdatedPayloadDto>("""{"path":"/w/alpha","label":null}""")

        assertNull(dto.label)
    }

    @Test
    fun decode_omittedLabelKey_isCleared() {
        val dto = MobileJson.decodeFromString<WorkspaceUpdatedPayloadDto>("""{"path":"/w/alpha"}""")

        assertNull(dto.label)
    }

    // `path` has no default: an absent one is a malformed frame, not an empty path. Without this the
    // fold would silently relabel every row whose cwd is "".
    @Test
    fun decode_missingPath_throws() {
        assertThrows(SerializationException::class.java) {
            MobileJson.decodeFromString<WorkspaceUpdatedPayloadDto>("""{"label":"Tax filing"}""")
        }
    }

    // The 128-byte daemon bound is a size limit, not a safety property, so the client stores what it is
    // given rather than re-enforcing a rule it cannot enforce correctly — truncation would display a
    // different name than the operator typed. Rendering safety belongs to #722 / #641.
    @Test
    fun decode_overlongAndBlankLabels_areStoredVerbatim() {
        val overlong = "x".repeat(200)
        assertEquals(
            overlong,
            MobileJson.decodeFromString<WorkspaceUpdatedPayloadDto>("""{"path":"/w/alpha","label":"$overlong"}""").label,
        )
        // Blank is NOT folded to null: the protocol keeps "labelled blank" and "unlabelled" distinct.
        assertEquals("", MobileJson.decodeFromString<WorkspaceUpdatedPayloadDto>("""{"path":"/w/alpha","label":""}""").label)
    }

    // Unknown keys are ignored (MobileJson.ignoreUnknownKeys), so a daemon adding a field to this frame
    // does not start dropping every workspace notification on an un-upgraded phone.
    @Test
    fun decode_unknownKey_isIgnored() {
        val dto =
            MobileJson.decodeFromString<WorkspaceUpdatedPayloadDto>(
                """{"path":"/w/alpha","label":"Tax filing","renamed_by":"desktop"}""",
            )

        assertEquals("Tax filing", dto.label)
    }
}
