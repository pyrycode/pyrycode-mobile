package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.network.ThreadItemDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Captured acceptance identity; a queue counter alone must never select a replacement item. */
@ConsistentCopyVisibility
data class AuthoritativeQueueTarget internal constructor(
    val host: String,
    val conversation: String,
    val epoch: String,
    val generation: Long,
    val acceptedItemId: Long,
    val queuedMessageId: ULong,
) {
    override fun toString(): String = "AuthoritativeQueueTarget(<redacted>)"
}

internal data class AuthoritativePendingSend(
    val deviceId: String?,
    val message: Message,
) {
    override fun toString(): String = "AuthoritativePendingSend(<redacted>)"
}

internal fun authoritativeQueueTarget(
    host: String,
    conversation: String,
    epoch: String,
    generation: Long,
    item: ThreadItemDto,
): AuthoritativeQueueTarget? {
    if (host.isEmpty() || conversation.isEmpty() || epoch.isEmpty() || item.id !in 1..9007199254740991L) return null
    if (item.kind != "user_message" || item.status != "queued" || !item.active || !item.shown) return null
    val value = ((item.content as? JsonObject)?.get("queued_msg_id") as? JsonPrimitive)?.takeUnless { it.isString }?.content ?: return null
    if (value.isEmpty() || value.length > 20 || value.first() !in '1'..'9' || value.any { it !in '0'..'9' }) return null
    val handle = value.toULongOrNull()?.takeIf { it > 0uL } ?: return null
    return AuthoritativeQueueTarget(host, conversation, epoch, generation, item.id, handle)
}

internal fun ThreadItemDto.matchesPending(send: AuthoritativePendingSend): Boolean {
    if (kind != "user_message" || send.deviceId.isNullOrEmpty()) return false
    val fields = content as? JsonObject ?: return false

    fun text(name: String) = (fields[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return text("device_id") == send.deviceId && text("message_id") == send.message.id
}
