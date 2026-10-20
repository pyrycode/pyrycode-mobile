package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContextUsageProjectionTest {
    @Test
    fun malformedCategoriesCannotReplaceHeldReadingOrCertifyHistory() =
        runTest {
            val projection = ContextUsageProjection()
            val readings = mutableListOf<ContextUsage?>()
            backgroundScope.launch { projection.observe("c1").collect { readings += it } }
            runCurrent()
            projection.apply(frame(valid))
            runCurrent()
            val held = readings.last()
            val malformed =
                listOf(
                    "null",
                    "{}",
                    "[null]",
                    "[{}]",
                    "[{\"name\":\"x\"}]",
                    "[{\"tokens\":1}]",
                    "[{\"name\":null,\"tokens\":1}]",
                    "[{\"name\":7,\"tokens\":1}]",
                    "[{\"name\":\"x\",\"tokens\":null}]",
                    "[{\"name\":\"x\",\"tokens\":\"7\"}]",
                    "[{\"name\":\"x\",\"tokens\":1.5}]",
                )
            for (categories in malformed) {
                val payload =
                    JsonObject(
                        valid + ("categories" to MobileJson.parseToJsonElement(categories)) + ("total_tokens" to JsonPrimitive(99)),
                    )
                projection.apply(frame(payload))
                runCurrent()
                assertEquals("invalid category shape", held, readings.last())
                val entry = HistoryEntry(1L, "context_usage", payload, kotlinx.datetime.Instant.parse("2026-10-19T00:00:00Z"))
                assertEquals(null, understoodNonvisualEntry(entry, true))
            }
        }

    @Test
    fun requiredSummaryFieldsCannotBeOmitted() =
        runTest {
            val projection = ContextUsageProjection()
            val readings = mutableListOf<ContextUsage?>()
            backgroundScope.launch { projection.observe("c1").collect { readings += it } }
            runCurrent()
            for (key in listOf("model", "categories", "dropped_categories")) {
                projection.apply(frame(JsonObject(valid - key)))
                runCurrent()
                assertEquals(listOf<ContextUsage?>(null), readings)
            }
        }

    @Test
    fun malformedModelAndDroppedCountCannotBecomeReading() =
        runTest {
            val projection = ContextUsageProjection()
            val readings = mutableListOf<ContextUsage?>()
            backgroundScope.launch { projection.observe("c1").collect { readings += it } }
            runCurrent()
            val malformed =
                listOf(
                    "model" to "null",
                    "model" to "42",
                    "model" to "[]",
                    "dropped_categories" to "null",
                    "dropped_categories" to "\"3\"",
                    "dropped_categories" to "1.2",
                    "dropped_categories" to "false",
                    "dropped_categories" to "[]",
                    "dropped_categories" to "9223372036854775808",
                )
            for ((key, value) in malformed) {
                projection.apply(frame(JsonObject(valid + (key to MobileJson.parseToJsonElement(value)))))
                runCurrent()
                assertEquals(listOf<ContextUsage?>(null), readings)
            }
        }

    @Test
    fun orderedCategoriesEmptyListsDroppedCountsAndRememberedReadingsRemainDistinct() =
        runTest {
            val projection = ContextUsageProjection()
            val readings = mutableListOf<ContextUsage?>()
            backgroundScope.launch { projection.observe("c1").collect { readings += it } }
            runCurrent()
            val categories = """[{"name":"second","tokens":3,"future":true},{"name":"first","tokens":7}]"""
            val payload =
                JsonObject(
                    valid +
                        mapOf(
                            "categories" to MobileJson.parseToJsonElement(categories),
                            "dropped_categories" to JsonPrimitive(Long.MAX_VALUE),
                            "future" to JsonPrimitive(true),
                        ),
                )
            projection.apply(frame(payload))
            runCurrent()
            val fresh = checkNotNull(readings.last())
            assertEquals("claude", fresh.model)
            assertEquals(listOf(ContextCategory("second", 3), ContextCategory("first", 7)), fresh.categories)
            assertEquals(Long.MAX_VALUE, fresh.droppedCategories)
            val entry = HistoryEntry(1L, "context_usage", payload, kotlinx.datetime.Instant.parse("2026-10-19T00:00:00Z"))
            assertEquals(true, understoodNonvisualEntry(entry, true))
            val remembered =
                JsonObject(
                    payload +
                        mapOf(
                            "as_of" to JsonPrimitive("2026-10-18T00:00:00Z"),
                            "categories" to MobileJson.parseToJsonElement("[]"),
                            "dropped_categories" to JsonPrimitive(0),
                        ),
                )
            projection.apply(frame(remembered))
            runCurrent()
            assertTrue(checkNotNull(readings.last()).categories.isEmpty())
            assertEquals(kotlinx.datetime.Instant.parse("2026-10-18T00:00:00Z"), readings.last()?.asOf)
            assertNotEquals(fresh, readings.last())
            projection.apply(frame(JsonObject(valid + ("categories" to MobileJson.parseToJsonElement("[]")))))
            runCurrent()
            assertEquals(emptyList<ContextCategory>(), readings.last()?.categories)
            assertEquals(null, readings.last()?.asOf)
        }

    @Test
    fun hostileDisplayTextIsBoundedRedactedAndNeverAnIdentity() =
        runTest {
            val projection = ContextUsageProjection()
            val readings = mutableListOf<ContextUsage?>()
            backgroundScope.launch { projection.observe("c1").collect { readings += it } }
            runCurrent()
            val hostile = "<a href='file:///secret'>".repeat(100)
            val categories = """[{"name":${JsonPrimitive(hostile)},"tokens":9223372036854775807}]"""
            val payload =
                JsonObject(
                    valid + mapOf("model" to JsonPrimitive(hostile), "categories" to MobileJson.parseToJsonElement(categories)),
                )
            projection.apply(frame(payload))
            runCurrent()
            val reading = checkNotNull(readings.last())
            assertEquals(hostile.take(512), reading.model)
            assertEquals(hostile.take(512), reading.categories.single().name)
            assertFalse(reading.toString().contains("file:"))
            assertFalse(
                reading.categories
                    .single()
                    .toString()
                    .contains("file:"),
            )
        }

    private fun frame(payload: JsonObject) =
        Envelope(
            id = 1L,
            type = "context_usage",
            ts = "2026-10-19T00:00:00Z",
            payload = payload,
        )

    private val valid =
        MobileJson.parseToJsonElement(
            """{"conversation_id":"c1","model":"claude","total_tokens":10,"max_tokens":100,"percentage":99,""" +
                """"categories":[{"name":"Messages","tokens":10}],"dropped_categories":0}""",
        ) as JsonObject
}
