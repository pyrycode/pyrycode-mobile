package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption

internal class ExchangeCapture(
    private val maxBytes: Int = 2_000_000,
) {
    private val wire = mutableListOf<JsonElement>()
    private var bytes = 0
    private var failed = false

    @Synchronized fun observe(
        direction: String,
        frame: Envelope,
    ) {
        if (failed) return
        try {
            require(direction in setOf("client", "daemon"))
            require(frame.type !in setOf("hello", "hello_ack", "rekey_request", "multipart", "fragment"))
            val entry =
                buildJsonObject {
                    put("direction", direction)
                    put("message", MobileJson.encodeToJsonElement(Envelope.serializer(), frame))
                }
            requireSafe(entry)
            bytes += entry.toString().toByteArray().size
            require(bytes <= maxBytes && wire.size < 5000)
            wire += entry
        } catch (_: Exception) {
            failed = true
            wire.clear()
        }
    }

    @Synchronized fun take(): JsonArray {
        check(!failed) { "Application exchange capture rejected" }
        return JsonArray(wire.toList())
    }

    fun writeWire(
        file: File,
        completed: Boolean,
    ) {
        check(completed) { "Application exchange flow incomplete" }
        require(!file.exists()) { "Candidate already exists" }
        val output = take().toString().toByteArray()
        require(wire.isNotEmpty())
        val permissions =
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions
                    .fromString("rw-------"),
            )
        Files.newByteChannel(file.toPath(), setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), permissions).use {
            val buffer = java.nio.ByteBuffer.wrap(output)
            while (buffer.hasRemaining()) it.write(buffer)
        }
    }
}
