package de.pyryco.mobile.data.cache

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/** Historical bytes are data for the discard oracle, independent of any production content codec. */
internal fun writeObsoleteThreadFixture(
    root: File,
    host: String,
    conversation: String,
): File {
    fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).toHexString()
    return File(root, "${hash(host)}/threads/${hash(conversation)}.json").apply {
        parentFile?.mkdirs()
        writeText(
            """{"version":1,"rows":[{"message":{"id":"legacy","sessionId":"s","role":"User","content":"obsolete","timestamp":"1970-01-01T00:00:00Z"}}],"history":{"cursor":"old-cursor","atStart":true}}""",
        )
    }
}

/** Supported daemon facts for mixed ownership/removal fixtures. */
internal fun savedTextFixture(text: String): SavedAuthoritativeThread =
    SavedAuthoritativeThread(
        "epoch",
        listOf(
            buildJsonObject {
                put("id", 1)
                put("rev", 1)
                put("kind", "assistant_message")
                put("status", "done")
                put("active", false)
                put("shown", true)
                put("order", 1)
                put("session", "s")
                put("summary", "")
                put("content", buildJsonObject { put("text", text) })
            },
        ),
        1,
        0,
    )
