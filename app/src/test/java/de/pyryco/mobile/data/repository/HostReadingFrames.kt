package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

/**
 * The five frames whose readings a host holds across reconnects (#1317), shared by the coordinator, facade and
 * registry tests. [all] carries one of each for a conversation; [assertHeld] and [assertNone] read them back
 * through any of the five `observe*` surfaces.
 */
internal object HostReadingFrames {
    private const val TS = "2026-10-01T00:00:00Z"

    fun modelAnnounced(
        conversationId: String,
        model: String,
    ): Envelope = frame("model_announced", """{"conversation_id":"$conversationId","model":"$model","truncated":false}""")

    fun sessionFacts(conversationId: String): Envelope =
        frame(
            "session_facts",
            """{"conversation_id":"$conversationId","claude_code_version":"2.1.0","permission_mode":"default","truncated_fields":null}""",
        )

    fun contextUsage(
        conversationId: String,
        total: Long = 50_000,
    ): Envelope =
        frame(
            "context_usage",
            """{"conversation_id":"$conversationId","model":"claude-opus-5-5","total_tokens":$total,"max_tokens":200000,""" +
                """"percentage":25,"categories":[],"dropped_categories":0,"mcp_tools":[],"dropped_mcp_tools":0,""" +
                """"memory_files":[],"dropped_memory_files":0}""",
        )

    /** `resets_at` 0 means no reset time, so the reading never expires against the wall clock. */
    fun rateLimited(
        conversationId: String,
        status: String = "allowed_warning",
        resetsAt: Long = 0,
    ): Envelope =
        frame(
            "rate_limited",
            """{"conversation_id":"$conversationId","status":"$status","limit_type":"seven_day","resets_at":$resetsAt,""" +
                """"utilization":null,"truncated_fields":null}""",
        )

    fun slashCommandList(
        conversationId: String,
        name: String = "compact",
    ): Envelope =
        frame(
            "slash_command_list",
            """{"conversation_id":"$conversationId","commands":[{"name":"$name","argument_hint":"","description":"","aliases":[]}],""" +
                """"dropped_commands":0}""",
        )

    fun sessionTransition(conversationId: String): Envelope =
        frame(
            "session_transition",
            """{"conversation_id":"$conversationId","previous_session_id":"s1","new_session_id":"s2","reason":"clear",""" +
                """"occurred_at":"$TS","workspace_cwd":null}""",
        )

    /** A one-row `model_list` for [conversationId] (#1320), the row's identifier [value]. */
    fun modelList(
        conversationId: String,
        value: String,
        inReplyTo: Long? = null,
    ): Envelope =
        frame(
            "model_list",
            """{"conversation_id":"$conversationId","models":[{"resolved_model":"$value","value":"$value",""" +
                """"display_name":"$value","effort_levels":["high"],"supports_auto_mode":false}],"dropped_models":0}""",
        ).copy(inReplyTo = inReplyTo)

    /**
     * The correlated `session_settings` reply to [inReplyTo] (#1320). [memorySearch] is the report's
     * availability, always carried so an invalidation has something to reset.
     */
    fun sessionSettings(
        inReplyTo: Long,
        model: String,
        permissionMode: String = "plan",
        memorySearch: String = "available",
    ): Envelope =
        frame(
            "session_settings",
            """{"session_id":"sess-a","model":"$model","effort":"high","yolo":false,"permission_mode":"$permissionMode",""" +
                """"used_tokens":0,"window_tokens":200000,"memory_search":{"availability":"$memorySearch","providers":[]}}""",
        ).copy(inReplyTo = inReplyTo)

    /** One frame of each held reading for [conversationId], the announced model naming [model]. */
    fun all(
        conversationId: String,
        model: String,
    ): List<Envelope> =
        listOf(
            modelAnnounced(conversationId, model),
            sessionFacts(conversationId),
            contextUsage(conversationId),
            rateLimited(conversationId),
            slashCommandList(conversationId),
        )

    /** Applies [all] straight to [readings], as a connection's inbound arms would. */
    fun applyAll(
        readings: HostReadings,
        conversationId: String,
        model: String,
    ) {
        val (announced, facts, context, limit, menu) = all(conversationId, model)
        readings.announcedModel.apply(announced)
        readings.sessionFacts.apply(facts)
        readings.contextUsage.apply(context)
        readings.usageLimit.apply(limit)
        readings.slashCommandMenu.apply(menu)
    }

    suspend fun assertHeld(
        source: ConversationRepository,
        conversationId: String,
        model: String,
    ) = assertHeld(Reads.of(source), conversationId, model)

    suspend fun assertHeld(
        source: HostReadings,
        conversationId: String,
        model: String,
    ) = assertHeld(Reads.of(source), conversationId, model)

    suspend fun assertNone(
        source: ConversationRepository,
        conversationId: String,
    ) = assertNone(Reads.of(source), conversationId)

    suspend fun assertNone(
        source: HostReadings,
        conversationId: String,
    ) = assertNone(Reads.of(source), conversationId)

    private suspend fun assertHeld(
        reads: Reads,
        conversationId: String,
        model: String,
    ) {
        assertEquals(AnnouncedModel(model, truncated = false), reads.announcedModel(conversationId))
        assertEquals(SessionFacts("2.1.0", "default", null), reads.sessionFacts(conversationId))
        assertEquals(ContextUsage(50_000, 200_000, 25, null), reads.contextUsage(conversationId))
        assertEquals("allowed_warning", reads.usageLimit(conversationId)?.status)
        assertEquals(listOf("compact"), reads.slashCommandMenu(conversationId)?.rows?.map { it.name })
    }

    private suspend fun assertNone(
        reads: Reads,
        conversationId: String,
    ) {
        assertNull(reads.announcedModel(conversationId))
        assertNull(reads.sessionFacts(conversationId))
        assertNull(reads.contextUsage(conversationId))
        assertNull(reads.usageLimit(conversationId))
        assertNull(reads.slashCommandMenu(conversationId))
    }

    /** The five reads as one shape, so a facade and a held source assert alike. */
    private class Reads(
        val announcedModel: suspend (String) -> AnnouncedModel?,
        val sessionFacts: suspend (String) -> SessionFacts?,
        val contextUsage: suspend (String) -> ContextUsage?,
        val usageLimit: suspend (String) -> UsageLimitReading?,
        val slashCommandMenu: suspend (String) -> SlashCommandMenu?,
    ) {
        companion object {
            fun of(source: ConversationRepository) =
                Reads(
                    { source.observeAnnouncedModel(it).first() },
                    { source.observeSessionFacts(it).first() },
                    { source.observeContextUsage(it).first() },
                    { source.observeUsageLimit(it).first() },
                    { source.observeSlashCommandMenu(it).first() },
                )

            fun of(source: HostReadings) =
                Reads(
                    { source.observeAnnouncedModel(it).first() },
                    { source.observeSessionFacts(it).first() },
                    { source.observeContextUsage(it).first() },
                    { source.observeUsageLimit(it).first() },
                    { source.observeSlashCommandMenu(it).first() },
                )
        }
    }

    private fun frame(
        type: String,
        payload: String,
    ): Envelope = Envelope(id = 1L, type = type, ts = TS, payload = MobileJson.parseToJsonElement(payload))
}
