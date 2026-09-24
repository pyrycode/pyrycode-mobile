package de.pyryco.mobile.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #1008: the optional `min_client_version` on `error`, and the shape a minimum must have to be kept. */
class ErrorPayloadTest {
    @Test
    fun decodesMinClientVersion_whenPresent() {
        val payload =
            MobileJson.decodeFromString(
                ErrorPayload.serializer(),
                """{"code":"client.update_required","message":"m","retryable":false,"min_client_version":"1.4.0"}""",
            )
        assertEquals(ERROR_CLIENT_UPDATE_REQUIRED, payload.code)
        assertEquals("1.4.0", payload.minClientVersion)
    }

    @Test
    fun decodesMinClientVersion_asNull_whenOmitted() {
        val payload = MobileJson.decodeFromString(ErrorPayload.serializer(), """{"code":"x","message":"m","retryable":true}""")
        assertNull(payload.minClientVersion)
    }

    @Test
    fun validMinClientVersion_keepsThreeBoundedDecimalParts() {
        for (valid in listOf("1.4.0", "0.0.0", "10.20.30", "999999.999999.999999", "01.4.0")) {
            assertEquals(valid, validMinClientVersion(valid))
        }
    }

    @Test
    fun validMinClientVersion_dropsEverythingElse() {
        val invalid =
            listOf(
                null,
                "",
                "1.4",
                "1.4.0.1",
                "v1.4.0",
                "1.4.0-beta",
                "1..0",
                ".1.4",
                "1.4.",
                "1234567.0.0",
                "1.4.0\n",
                " 1.4.0",
                "1.4.0 ",
                "1.٤.0", // Arabic-Indic digit: not ASCII
                "1,4,0",
                "pyrycode-mobile/1.4.0",
            )
        for (value in invalid) {
            assertNull("'$value' must be dropped", validMinClientVersion(value))
        }
    }
}
