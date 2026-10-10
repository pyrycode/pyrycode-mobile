package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.network.Envelope
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** #1925: fixed-size, content-free observations, never a claim that an unseen turn did not start. */
internal fun stopHoldEvidence(
    conversationId: String,
    frames: List<Envelope>,
    phonePermission: Boolean?,
): String {
    val owned = frames.filter { it.stringField("conversation_id") == conversationId }

    fun count(type: String): Int = owned.count { it.type == type }.coerceAtMost(COUNT_CAP)
    val permission = owned.count { it.type == "modal_shown" && it.stringField("class") == "permission" }.coerceAtMost(COUNT_CAP)
    val userEcho = owned.count { it.type == "message" && it.stringField("role") == "user" }.coerceAtMost(COUNT_CAP)
    val assistant = owned.count { it.type == "message" && it.stringField("role") == "assistant" }.coerceAtMost(COUNT_CAP)
    val turnActive = owned.any { it.type == "turn_state" && it.stringField("state") in setOf("thinking", "responding") }
    val lastState = owned.lastOrNull { it.type == "turn_state" }?.let { it.stringField("state").label(STATES) } ?: "none"
    val lastStop = owned.lastOrNull { it.type == "turn_end" }?.let { it.stringField("stop_reason").label(STOP_REASONS) } ?: "none"
    val refusals = (count("model_refusal_fallback") + count("model_refusal_no_fallback")).coerceAtMost(COUNT_CAP)
    val stage =
        when {
            permission > 0 -> "permission_at_peer"
            phonePermission == true -> "permission_at_phone_only"
            count("turn_end") > 0 -> "turn_ended_before_permission"
            count("tool_use") > 0 || count("tool_result") > 0 -> "tool_activity_without_permission"
            turnActive || count("thinking_progress") > 0 || count("assistant_delta") > 0 || assistant > 0 || refusals > 0 ->
                "turn_activity_without_permission"
            userEcho > 0 -> "user_echo_only"
            else -> "no_observed_activity"
        }
    return "stage=$stage user_echo=$userEcho turn_state=${count("turn_state")} last_state=$lastState " +
        "thinking_progress=${count("thinking_progress")} assistant_delta=${count("assistant_delta")} assistant_message=$assistant " +
        "tool_use=${count("tool_use")} tool_result=${count("tool_result")} modal_shown=${count("modal_shown")} " +
        "permission_shown=$permission refusal=$refusals session_error=${count("session_error")} " +
        "turn_end=${count("turn_end")} last_stop=$lastStop phone_permission=${phonePermission ?: "unknown"} " +
        "count_cap=$COUNT_CAP cause=unproven"
}

/** Wrap only the Stop scenario's existing send/wait; retain observations before its finally cleanup. */
internal fun <T> withStopHoldDiagnostics(
    conversationId: String,
    nowMs: () -> Long,
    snapshot: () -> String,
    emit: (String) -> Unit,
    block: () -> T,
): T {
    val correlation = canonicalConversationId(conversationId)

    fun stage(event: String): String = "event=$event at_ms=${nowMs()} conversation=$correlation"

    fun evidence(): String =
        try {
            snapshot()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            "evidence=unavailable cause=unproven"
        } catch (_: AssertionError) {
            "evidence=unavailable cause=unproven"
        }
    emit(stage("stop_hold_submit"))
    val result =
        try {
            block()
        } catch (failure: AssertionError) {
            val message = "${stage("stop_hold_permission_failed")} ${evidence()}"
            emit(message)
            throw AssertionError(message, failure)
        }
    emit("${stage("stop_hold_permission_ready")} ${evidence()}")
    return result
}

private fun canonicalConversationId(value: String): String {
    if (value.length != 36) return "unknown"
    val parsed =
        try {
            UUID.fromString(value).toString()
        } catch (_: IllegalArgumentException) {
            return "unknown"
        }
    return parsed.takeIf { it.equals(value, ignoreCase = true) } ?: "unknown"
}

private fun Envelope.stringField(name: String): String? =
    ((payload as? JsonObject)?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun String?.label(allowed: Set<String>): String = if (this in allowed) this.orEmpty() else "unknown"

private const val COUNT_CAP = 999
private val STATES = setOf("idle", "thinking", "responding")
private val STOP_REASONS = setOf("end_turn", "max_tokens", "max_turn_requests", "refusal", "cancelled")
