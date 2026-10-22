package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContextUsageProjectionTest {
    @Test
    fun orderedMemoryTypesIndependentDropsReplacementAndRedaction() =
        runTest {
            val projection = ContextUsageProjection()
            val readings = mutableListOf<ContextUsage?>()
            backgroundScope.launch { projection.observe("c1").collect { readings += it } }
            runCurrent()
            val files = """[{"path":"same.md","type":"second","tokens":3,"future":true},{"path":"same.md","type":"first","tokens":7}]"""
            val payload =
                JsonObject(
                    valid +
                        mapOf(
                            "memory_files" to MobileJson.parseToJsonElement(files),
                            "dropped_memory_files" to JsonPrimitive(8),
                            "dropped_categories" to JsonPrimitive(2),
                            "dropped_mcp_tools" to JsonPrimitive(5),
                        ),
                )
            projection.apply(frame(payload))
            runCurrent()
            val held = checkNotNull(readings.last())
            assertEquals(listOf(ContextMemoryFile("same.md", "second", 3), ContextMemoryFile("same.md", "first", 7)), held.memoryFiles)
            assertEquals(8L, held.droppedMemoryFiles)
            assertEquals(2L, held.droppedCategories)
            projection.apply(frame(valid))
            runCurrent()
            assertEquals(emptyList<ContextMemoryFile>(), readings.last()?.memoryFiles)
            assertNotEquals(held, readings.last())
            val hostile = "<a href='file:secret'>".repeat(200)
            val hostileFiles = """[{"path":${JsonPrimitive(hostile)},"type":${JsonPrimitive(hostile)},"tokens":-1}]"""
            projection.apply(frame(JsonObject(valid + ("memory_files" to MobileJson.parseToJsonElement(hostileFiles)))))
            runCurrent()
            val file = checkNotNull(readings.last()).memoryFiles.single()
            assertEquals(hostile.take(2048), file.path)
            assertEquals(hostile.take(512), file.type)
            assertEquals(-1L, file.tokens)
            assertFalse(file.toString().contains("file:"))
        }

    @Test
    fun malformedMemoryCannotReplaceHeldReadingOrCertifyHistory() =
        runTest {
            val projection = ContextUsageProjection()
            val readings = mutableListOf<ContextUsage?>()
            backgroundScope.launch { projection.observe("c1").collect { readings += it } }
            runCurrent()
            projection.apply(frame(valid))
            runCurrent()
            val held = checkNotNull(readings.last())
            val badLists =
                listOf(
                    "null",
                    "{}",
                    "42",
                    "[null]",
                    "[{}]",
                    "[{\"path\":\"x\",\"tokens\":1}]",
                    "[{\"path\":7,\"type\":\"memory\",\"tokens\":1}]",
                    "[{\"path\":\"x\",\"type\":false,\"tokens\":1}]",
                    "[{\"path\":\"x\",\"type\":\"memory\",\"tokens\":1.5}]",
                    "[{\"path\":\"x\",\"type\":\"memory\",\"tokens\":9223372036854775808}]",
                    "[{\"path\":\"x\",\"type\":\"memory\",\"tokens\":\"1\"}]",
                )
            val goodFile = MobileJson.parseToJsonElement("""{"path":"x","type":"memory","tokens":1}""") as JsonObject
            val entryFailures =
                listOf("path", "type", "tokens")
                    .flatMap { key ->
                        listOf(JsonObject(goodFile - key)) +
                            listOf("null", "[]", "{}", "true").map { value ->
                                JsonObject(goodFile + (key to MobileJson.parseToJsonElement(value)))
                            }
                    }.map { JsonObject(valid + ("memory_files" to kotlinx.serialization.json.JsonArray(listOf(it)))) }
            val dropFailures =
                listOf("null", "\"1\"", "1.2", "false", "[]", "9223372036854775808").map {
                    JsonObject(valid + ("dropped_memory_files" to MobileJson.parseToJsonElement(it)))
                }
            val invalid =
                badLists.map { JsonObject(valid + ("memory_files" to MobileJson.parseToJsonElement(it))) } + entryFailures + dropFailures +
                    listOf(JsonObject(valid - "memory_files"), JsonObject(valid - "dropped_memory_files"))
            for (bad in invalid) {
                val payload = JsonObject(bad + ("total_tokens" to JsonPrimitive(99)))
                projection.apply(frame(payload))
                runCurrent()
                assertEquals("invalid memory shape", held, readings.last())
            }
        }

    @Test
    fun mcpInventoryPreservesOrderCountsAndReplacement() =
        runTest {
            val projection = ContextUsageProjection()
            val readings = mutableListOf<ContextUsage?>()
            backgroundScope.launch { projection.observe("c1").collect { readings += it } }
            runCurrent()
            val tools = """[{"name":"duplicate","server_name":"__proto__","tokens":1900,"future":true},
            {"name":"other","server_name":"github","tokens":1400},
            {"name":"duplicate","server_name":"__proto__","tokens":1200}]"""
            val payload =
                JsonObject(
                    valid +
                        mapOf(
                            "mcp_tools" to MobileJson.parseToJsonElement(tools),
                            "dropped_mcp_tools" to JsonPrimitive(Long.MAX_VALUE),
                            "dropped_categories" to JsonPrimitive(2),
                            "dropped_memory_files" to JsonPrimitive(17),
                        ),
                )
            projection.apply(frame(payload))
            runCurrent()
            val fresh = checkNotNull(readings.last())
            assertEquals(
                listOf(
                    ContextMcpTool("duplicate", "__proto__", 1900),
                    ContextMcpTool("other", "github", 1400),
                    ContextMcpTool("duplicate", "__proto__", 1200),
                ),
                fresh.mcpTools,
            )
            assertEquals(Long.MAX_VALUE, fresh.droppedMcpTools)
            assertEquals(2L, fresh.droppedCategories)
            projection.apply(frame(JsonObject(valid + ("dropped_mcp_tools" to JsonPrimitive(3)))))
            runCurrent()
            assertEquals(emptyList<ContextMcpTool>(), readings.last()?.mcpTools)
            assertEquals(3L, readings.last()?.droppedMcpTools)
            assertNotEquals(fresh, readings.last())
            projection.apply(frame(valid))
            runCurrent()
            assertEquals(0L, readings.last()?.droppedMcpTools)
        }

    @Test
    fun mcpNamesAreBoundedAndRedacted() =
        runTest {
            val text = "<a href='file:///secret'>".repeat(100)
            val payload =
                JsonObject(
                    valid + (
                        "mcp_tools" to
                            MobileJson.parseToJsonElement(
                                """[{"name":${JsonPrimitive(text)},"server_name":${JsonPrimitive(text)},"tokens":-7}]""",
                            )
                    ),
                )
            val dto = MobileJson.decodeFromJsonElement<de.pyryco.mobile.data.network.ContextUsagePayloadDto>(payload)
            val projection = ContextUsageProjection()
            var reading: ContextUsage? = null
            backgroundScope.launch { projection.observe("c1").collect { reading = it } }
            projection.apply(frame(payload))
            runCurrent()
            val tool = checkNotNull(reading).mcpTools.single()
            assertEquals(text.take(512), tool.name)
            assertEquals(text.take(512), tool.serverName)
            assertEquals(-7L, tool.tokens)
            for (value in listOf(dto, dto.mcpTools.single(), reading, tool)) assertFalse(value.toString().contains("file:"))
        }

    @Test
    fun malformedMcpInventoryCannotReplaceOrCertify() =
        runTest {
            val projection = ContextUsageProjection()
            var held: ContextUsage? = null
            backgroundScope.launch { projection.observe("c1").collect { held = it } }
            projection.apply(frame(valid))
            runCurrent()
            val previous = checkNotNull(held)
            val invalid =
                listOf(
                    "null",
                    "{}",
                    "false",
                    "7",
                    "\"tools\"",
                    "[null]",
                    "[{}]",
                    """[{"name":"x","tokens":1}]""",
                    """[{"server_name":"s","tokens":1}]""",
                    """[{"name":"x","server_name":"s"}]""",
                ) +
                    listOf("name", "server_name", "tokens").flatMap { field ->
                        val entry = MobileJson.parseToJsonElement("""{"name":"x","server_name":"s","tokens":1}""") as JsonObject
                        val bad =
                            if (field == "tokens") {
                                listOf("null", "\"1\"", "1.5", "true", "[]", "9223372036854775808")
                            } else {
                                listOf("null", "42", "false", "[]", "{}")
                            }
                        bad.map { "[${JsonObject(entry + (field to MobileJson.parseToJsonElement(it)))}]" }
                    }
            val payloads =
                invalid.map { JsonObject(valid + ("mcp_tools" to MobileJson.parseToJsonElement(it))) } +
                    listOf(JsonObject(valid - "mcp_tools"), JsonObject(valid - "dropped_mcp_tools")) +
                    listOf("null", "\"3\"", "1.2", "false", "[]", "9223372036854775808").map {
                        JsonObject(valid + ("dropped_mcp_tools" to MobileJson.parseToJsonElement(it)))
                    }
            for (payload in payloads) {
                val changed = JsonObject(payload + ("total_tokens" to JsonPrimitive(99)))
                projection.apply(frame(changed))
                runCurrent()
                assertEquals("invalid MCP inventory must preserve held reading", previous, held)
            }
        }

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
                """"categories":[{"name":"Messages","tokens":10}],"dropped_categories":0,"mcp_tools":[],"dropped_mcp_tools":0,"memory_files":[],"dropped_memory_files":0}""",
        ) as JsonObject
}
