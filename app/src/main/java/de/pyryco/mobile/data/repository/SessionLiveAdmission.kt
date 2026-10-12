package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.SessionProvenance
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One authenticated connection; never derives ownership from correlation, session IDs or UI. */
internal class SessionLiveAdmission(
    private val bindings: MutableMap<String, SessionProvenance>,
    private val retired: MutableMap<String, Set<SessionProvenance>>,
    private val clear: (String, String?) -> Unit,
) {
    private class Owner(
        var session: SessionProvenance,
    ) {
        val cleared = mutableSetOf<String>()
        val fresh = mutableSetOf<String>()
    }

    private val owners = mutableMapOf<String, Owner>()

    fun summary(
        conversation: String,
        session: String,
    ) {
        val provenance = SessionProvenance(if (session.isEmpty()) JsonNull else JsonPrimitive(session))
        if (conversation.isNotEmpty()) {
            replace(conversation, provenance)
            retired[conversation] = retired[conversation].orEmpty() - provenance
        }
    }

    fun currentBinding(conversation: String): String? =
        owners[conversation]?.session?.raw?.let {
            if (it === JsonNull) "" else (it as JsonPrimitive).content
        }

    private fun replace(
        conversation: String,
        session: SessionProvenance,
    ): Owner {
        val owner =
            owners[conversation] ?: Owner(bindings[conversation] ?: session).also {
                owners[conversation] = it
                if (conversation !in bindings && session.raw != null) {
                    bindings[conversation] = session
                    clear(conversation, null)
                }
            }
        if (owner.session != session) {
            if (owner.session.raw is JsonPrimitive &&
                owner.session.raw !== JsonNull
            ) {
                retired[conversation] = retired[conversation].orEmpty() + owner.session
            }
            owner.session = session
            bindings[conversation] = session
            owner.cleared.clear()
            owner.fresh.clear()
            clear(conversation, null)
            RelayLog.i { "event=session_live code=replaced" }
        }
        return owner
    }

    /** Returns a payload-routed ordinary frame only after all ownership checks have passed. */
    fun admit(envelope: Envelope): Envelope? {
        val family = family(envelope.type) ?: return null
        val flag = envelope.sessionStateCleared
        if (flag !is JsonPrimitive || flag.isString || flag.booleanOrNull == null) return rejected("clear")
        val cleared = (flag as? JsonPrimitive)?.booleanOrNull == true
        val conversation =
            (envelope.conversationId as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
                ?: return rejected("conversation")
        val payload = envelope.payload as? JsonObject ?: return rejected("payload")
        if ("conversation_id" in payload && payload["conversation_id"] != JsonPrimitive(conversation)) return rejected("conflict")
        val session = envelope.sessionProvenance
        if (!session.usable) return rejected("session")
        if (envelope.eventId != null || envelope.historyEntryId != null) return rejected("durable_identity")
        if (cleared && (payload.isNotEmpty() || envelope.inReplyTo != null)) return rejected("clear")
        val existing =
            owners[conversation] ?: bindings[conversation]?.let { known ->
                Owner(known).also { owners[conversation] = it }
            }
        if (session.raw != null && session in retired[conversation].orEmpty()) return rejected("retired")
        if (session.raw == null && existing?.session?.raw != null) return rejected("missing")
        if (!cleared && existing?.session != session) return rejected("unreconciled")
        val owner = if (cleared) replace(conversation, session) else existing ?: return rejected("unreconciled")
        if (cleared) {
            if (family !in owner.fresh && owner.cleared.add(family)) {
                clear(conversation, family)
                RelayLog.i { "event=session_live code=cleared" }
            }
            return null
        }
        owner.fresh += family
        RelayLog.d { "event=session_live code=accepted" }
        return envelope.copy(payload = JsonObject(payload + ("conversation_id" to JsonPrimitive(conversation))))
    }

    private fun rejected(code: String): Envelope? {
        RelayLog.d { "event=session_live code=$code" }
        return null
    }

    companion object {
        fun family(type: String): String? =
            when (type) {
                "modal_dismissed" -> "modal_shown"
                "question_dismissed" -> "question_shown"
                "session_settings_updated" -> "session_settings"
                in families -> type
                else -> null
            }

        val families =
            setOf(
                "modal_shown",
                "question_shown",
                "turn_state",
                "stall",
                "api_retry",
                "compacting",
                "thinking_progress",
                "tool_progress",
                "background_task_progress",
                "resetting",
                "rate_limited",
                "context_usage",
                "model_announced",
                "session_facts",
                "session_settings",
                "mcp_status",
                "slash_command_list",
                "model_list",
                "reply_suggestion",
                "session_error",
            )
    }
}
