package de.pyryco.mobile.data.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Wire (de)serialization tests for the screen-snapshot exchange (#374). JUnit4, mirroring
 * MessagePayloadTest.kt. The request encodes through [MobileJson]
 * (`MobileJson.encodeToJsonElement(...)`); the event decodes via `parseToJsonElement` →
 * `decodeFromJsonElement`, exactly as the #375 consumer will over `Envelope.payload`.
 */
class SnapshotPayloadTest {
    // ---- request_snapshot (#374): the encode-only request -----------------------------------------

    @Test
    fun requestSnapshot_encodesWireFieldName() {
        // Encode-only: the phone sends this. The snake_case field name must match the SSOT.
        val element =
            MobileJson
                .encodeToJsonElement(RequestSnapshotPayloadDto(conversationId = "c1"))
                .jsonObject

        assertEquals("c1", element.getValue("conversation_id").jsonPrimitive.content)
    }

    // ---- screen_snapshot (#374): the decode-only event --------------------------------------------

    @Test
    fun screenSnapshot_decodesAllThreeFields() {
        val element =
            MobileJson.parseToJsonElement(
                """{"conversation_id":"c1","text":"<rendered screen>","ts":"2026-06-08T10:00:00Z"}""",
            )

        val dto = MobileJson.decodeFromJsonElement<ScreenSnapshotPayloadDto>(element)

        assertEquals("c1", dto.conversationId)
        assertEquals("<rendered screen>", dto.text)
        assertEquals("2026-06-08T10:00:00Z", dto.ts)
    }

    @Test
    fun screenSnapshot_toleratesUnknownFields() {
        // Forward-compat: an unmodeled key (e.g. `cols`) is dropped, not an error
        // (MobileJson.ignoreUnknownKeys). The three modeled fields are unchanged.
        val element =
            MobileJson.parseToJsonElement(
                """{"conversation_id":"c1","text":"hi","ts":"2026-06-08T10:00:00Z","cols":80}""",
            )

        val dto = MobileJson.decodeFromJsonElement<ScreenSnapshotPayloadDto>(element)

        assertEquals("c1", dto.conversationId)
        assertEquals("hi", dto.text)
        assertEquals("2026-06-08T10:00:00Z", dto.ts)
    }

    @Test
    fun screenSnapshot_preservesTextVerbatim() {
        // A realistic rendered screen carries newlines and leading whitespace; the DTO must not
        // trim/normalize it — decode fidelity is the whole point of the parser-independent floor.
        val rendered = "  line one\n    line two\n\n  line four  "
        val element =
            MobileJson.parseToJsonElement(
                MobileJson
                    .encodeToJsonElement(
                        ScreenSnapshotPayloadDto(
                            conversationId = "c1",
                            text = rendered,
                            ts = "2026-06-08T10:00:00Z",
                        ),
                    ).toString(),
            )

        val dto = MobileJson.decodeFromJsonElement<ScreenSnapshotPayloadDto>(element)

        assertEquals(rendered, dto.text)
    }

    @Test
    fun screenSnapshot_missingRequiredField_throwsTypedDecodeFailure() {
        // Drops the required `text`. All three fields are required, so any omission is a valid
        // trigger; no partial / null-punned value is produced.
        val element =
            MobileJson.parseToJsonElement(
                """{"conversation_id":"c1","ts":"2026-06-08T10:00:00Z"}""",
            )

        assertThrows(SerializationException::class.java) {
            MobileJson.decodeFromJsonElement<ScreenSnapshotPayloadDto>(element)
        }
    }
}
