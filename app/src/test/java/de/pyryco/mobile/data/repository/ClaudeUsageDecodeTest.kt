package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.MobileJson
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClaudeUsageDecodeTest {
    @Test
    fun optionalWindowsAndFieldsPreserveAbsenceAndRawNumbers() {
        assertEquals(ClaudeUsageReading.Observed(Instant.parse(TS)), decode(observed()))
        assertEquals(
            ClaudeUsageReading.Observed(
                Instant.parse(TS),
                ClaudeUsageReading.Window(0.0, Long.MAX_VALUE),
                ClaudeUsageReading.Window(-2.5, Long.MIN_VALUE),
            ),
            decode(
                observed(
                    ""","five_hour":{"utilization":0,"resets_at":9223372036854775807},"seven_day":{"utilization":-2.5,"resets_at":-9223372036854775808}""",
                ),
            ),
        )
        assertEquals(
            ClaudeUsageReading.Observed(
                Instant.parse(TS),
                ClaudeUsageReading.Window(),
                ClaudeUsageReading.Window(1.75),
            ),
            decode(observed(""","five_hour":{},"seven_day":{"utilization":1.75}""")),
        )
        assertEquals(
            ClaudeUsageReading.Observed(Instant.parse(TS), sevenDay = ClaudeUsageReading.Window(resetsAt = 1000)),
            decode(observed(""","seven_day":{"resets_at":1000,"future":true},"extra":[]""")),
        )
    }

    @Test
    fun noneAndUnknownAreSafeTypedStates() {
        assertEquals(ClaudeUsageReading.None, decode("""{"state":"none","future":1}"""))
        assertEquals(ClaudeUsageReading.Unavailable, decode("""{"state":"<untrusted>","observed_at":false,"five_hour":false}"""))
    }

    @Test
    fun malformedKnownFieldsRejectWithoutCoercion() {
        val invalid = listOf("null", "[]", "0", "{}", """{"state":1}""", """{"state":null}""", """{"state":true}""")
        invalid.forEach { assertNull(it, decode(it)) }
        listOf("null", "1", "false", "\"bad\"", "\"2026-99-17T12:00:00Z\"", "{}", "[]").forEach { value ->
            assertNull(value, decode("""{"state":"observed","observed_at":$value}"""))
        }
        assertNull(decode("""{"state":"observed"}"""))
        for (state in listOf("none", "observed")) {
            for (window in listOf("five_hour", "seven_day")) {
                for (bad in listOf("null", "[]", "true", "1", "\"text\"")) {
                    assertNull("$state $window $bad", decode("""{"state":"$state","observed_at":"$TS","$window":$bad}"""))
                }
                for (field in listOf("utilization", "resets_at")) {
                    for (bad in listOf("null", "true", "\"0\"", "{}", "[]")) {
                        assertNull("$state $field $bad", decode("""{"state":"$state","observed_at":"$TS","$window":{"$field":$bad}}"""))
                    }
                }
            }
        }
        listOf("0.5", "1e3", "9223372036854775808").forEach { bad ->
            assertNull(bad, decode(observed(""","five_hour":{"resets_at":$bad}""")))
        }
        assertNull(decode(observed(""","five_hour":{"utilization":1e999}""")))
        assertNull(decode("""{"state":"none","observed_at":"bad"}"""))
    }

    private fun observed(extra: String = ""): String = """{"state":"observed","observed_at":"$TS"$extra}"""

    private fun decode(raw: String): ClaudeUsageReading? = decodeClaudeUsage(MobileJson.parseToJsonElement(raw))

    private companion object {
        const val TS = "2026-10-17T12:00:00Z"
    }
}
