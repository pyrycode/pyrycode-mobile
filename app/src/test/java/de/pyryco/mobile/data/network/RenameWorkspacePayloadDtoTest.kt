package de.pyryco.mobile.data.network

import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for the #663 `rename_workspace` request payload. Pins the encoded bytes, because the
 * protocol reads an omitted `label` as the clear and [MobileJson] omits nulls — a codec change that
 * started sending `"label":""` instead would be refused as a blank label.
 */
class RenameWorkspacePayloadDtoTest {
    @Test
    fun encode_carriesPathAndLabelVerbatim() {
        val encoded = MobileJson.encodeToJsonElement(RenameWorkspacePayloadDto(path = "/w/alpha ", label = " Tax filing "))

        assertEquals(MobileJson.parseToJsonElement("""{"path":"/w/alpha ","label":" Tax filing "}"""), encoded)
    }

    @Test
    fun encode_nullLabel_omitsTheKey() {
        val encoded = MobileJson.encodeToJsonElement(RenameWorkspacePayloadDto(path = "/w/alpha", label = null))

        assertEquals(MobileJson.parseToJsonElement("""{"path":"/w/alpha"}"""), encoded)
    }
}
