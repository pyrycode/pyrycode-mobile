package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.MemorySearchAvailability
import de.pyryco.mobile.data.repository.MemorySearchReport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The five shared daemon contract frames, copied verbatim into test resources. */
class SessionSettingsMemorySearchTest {
    @Test
    fun availableFixture_preservesUsableProvider() {
        val reading = fixture("session_settings_memory_available.json")

        assertEquals(MemorySearchAvailability.Available, reading.memorySearch.availability)
        val provider = reading.memorySearch.providers.single()
        assertEquals("qmd", provider.id)
        assertEquals("QMD", provider.displayName)
        assertTrue(provider.installed)
        assertTrue(provider.enabled)
        assertEquals(MemorySearchAvailability.Available, provider.availability)
    }

    @Test
    fun disabledFixture_keepsInstalledAndEnabledDistinct() {
        val reading = fixture("session_settings_memory_disabled.json")

        assertEquals(MemorySearchAvailability.Unavailable, reading.memorySearch.availability)
        val provider = reading.memorySearch.providers.single()
        assertEquals("memsearch", provider.id)
        assertEquals("Memsearch", provider.displayName)
        assertTrue(provider.installed)
        assertFalse(provider.enabled)
        assertEquals(MemorySearchAvailability.Unavailable, provider.availability)
    }

    @Test
    fun absentAndUnknownFixtures_haveDifferentMeaningDespiteEmptyProviders() {
        val absent = fixture("session_settings_memory_absent.json").memorySearch
        val unknown = fixture("session_settings_memory_unknown.json").memorySearch

        assertEquals(MemorySearchAvailability.Absent, absent.availability)
        assertEquals(MemorySearchAvailability.Unknown, unknown.availability)
        assertTrue(absent.providers.isEmpty())
        assertTrue(unknown.providers.isEmpty())
    }

    @Test
    fun olderFixture_hasUnknownSearchWithoutLosingSettings() {
        val reading = fixture("session_settings.json")

        assertEquals(MemorySearchReport.Unknown, reading.memorySearch)
        assertEquals("sess-a", reading.sessionId)
        assertEquals("opus", reading.model)
        assertEquals("high", reading.effort)
        assertEquals(12480L, reading.usedTokens)
    }

    @Test
    fun malformedIncompleteAndFutureReports_becomeUnknownWithoutLosingOtherSettings() {
        val payload =
            MobileJson
                .parseToJsonElement(
                    fixtureText("session_settings_memory_available.json"),
                ).jsonObject
                .getValue("payload")
                .jsonObject +
                ("effective_effort" to MobileJson.parseToJsonElement("\"low\"")) +
                ("capabilities" to MobileJson.parseToJsonElement("""{"effort_levels":["low"],"permission_modes":["default"]}"""))
        val reports =
            listOf(
                "null",
                "[]",
                """{"availability":"future","providers":[]}""",
                """{"availability":"available"}""",
                """{"availability":"available","providers":[{"id":"qmd"}]}""",
                """{"availability":"available","providers":[{"id":"qmd","display_name":"QMD","installed":true,"enabled":true,"availability":"future"}]}""",
            )
        for (report in reports) {
            val reading = JsonObject(payload + ("memory_search" to MobileJson.parseToJsonElement(report))).toSessionSettings()
            assertEquals(report, MemorySearchReport.Unknown, reading.memorySearch)
            assertEquals(report, "opus", reading.model)
            assertEquals(report, 12480L, reading.usedTokens)
            assertEquals(report, EffectiveEffort.Applied("low"), reading.effectiveEffort)
            assertEquals(report, listOf("low"), reading.capabilities?.effortLevels)
        }
    }

    private fun fixture(name: String) = decodeFrame(fixtureText(name))

    private fun fixtureText(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/daemon-contract/$name")) { name }.bufferedReader().use { it.readText() }

    private fun decodeFrame(raw: String) =
        MobileJson
            .parseToJsonElement(raw)
            .jsonObject
            .getValue("payload")
            .toSessionSettings()
}
