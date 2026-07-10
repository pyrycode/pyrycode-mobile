package de.pyryco.mobile.data.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Codec tests for the v2 `create_workspace_folder` / `workspace_folder_created` payloads (#564).
 * JUnit4, mirroring ConversationResponseDtoTest.kt. The request DTO is **encode-only** (phone →
 * binary), so it is exercised through `MobileJson.encodeToJsonElement`; the reply DTO is
 * **decode-only** (binary → phone), exercised through `MobileJson.decodeFromJsonElement`. Wire SSOT:
 * server `internal/protocol/workspace.go` `CreateWorkspaceFolderPayload{Parent, Name}` /
 * `WorkspaceFolderCreatedPayload{Path}` (pyrycode#887).
 */
class CreateWorkspaceFolderPayloadsTest {
    // AC #1 / #5: the request encodes to exactly the two wire keys `{parent, name}` — no extras, no
    // omissions. Both fields are required non-null Strings, so `MobileJson`'s explicitNulls=false
    // never elides them. JsonObject equality is key-set exact, so an added/renamed key would fail.
    @Test
    fun request_encodesToExactlyParentAndName() {
        val dto = CreateWorkspaceFolderPayloadDto(parent = "~/pyry-workspace", name = "foo")

        val encoded = MobileJson.encodeToJsonElement(dto)

        assertEquals(
            MobileJson.parseToJsonElement("""{"parent":"~/pyry-workspace","name":"foo"}"""),
            encoded,
        )
    }

    // AC #1 / #5: the reply decodes its single `path` field — the daemon's canonical $HOME-confined
    // realpath — verbatim into the DTO. Correlation rides Envelope.inReplyTo, not a payload field.
    @Test
    fun reply_decodesPath() {
        val element = MobileJson.parseToJsonElement("""{"path":"/home/op/pyry-workspace/foo"}""")

        val dto = MobileJson.decodeFromJsonElement<WorkspaceFolderCreatedPayloadDto>(element)

        assertEquals("/home/op/pyry-workspace/foo", dto.path)
    }

    // A reply missing the required `path` is rejected at the decode boundary (the #318 posture), so a
    // malformed success reply cannot yield a bogus empty path.
    @Test
    fun reply_missingPath_throwsTypedDecodeFailure() {
        val element = MobileJson.parseToJsonElement("""{}""")

        assertThrows(SerializationException::class.java) {
            MobileJson.decodeFromJsonElement<WorkspaceFolderCreatedPayloadDto>(element)
        }
    }
}
