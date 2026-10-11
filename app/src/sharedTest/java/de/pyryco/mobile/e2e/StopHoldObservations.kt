package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.ModalEvent
import de.pyryco.mobile.data.network.Envelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/** Scenario-local observation times, never daemon emission times or proof of unseen rendering. */
internal class StopHoldObservations(
    private val conversationId: String,
    private val nowMs: () -> Long,
) {
    private val shown = mutableSetOf<String>()
    private val dismissed = mutableSetOf<String>()
    private val timeline = mutableListOf<String>()
    private var peerShown = 0
    private var peerDismissed = 0
    private var dropped = 0
    private var uiSamples = 0
    private var projection: Boolean? = null
    private var projectionObserved = false
    private var ui: Boolean? = null
    private var uiObserved = false

    @Synchronized
    fun phone(event: ModalEvent?) {
        when (event) {
            null -> record("phone_observer_reset")
            is ModalEvent.Shown -> {
                if (event.conversationId != conversationId || event.modalClass != "permission") return
                val identity = identity(event.modalId)
                if (identity in shown) return
                if (shown.size == CAP) {
                    drop()
                    return
                }
                shown.add(identity)
                record("phone_wire_shown")
            }
            is ModalEvent.Dismissed -> {
                val identity = identity(event.modalId)
                if (identity in shown && dismissed.add(identity)) record("phone_wire_dismissed")
            }
        }
    }

    @Synchronized
    fun projection(present: Boolean?) {
        if (!projectionObserved || projection != present) {
            projectionObserved = true
            projection = present
            record(
                when (present) {
                    true -> "phone_projection_present"
                    false -> "phone_projection_absent"
                    null -> "phone_projection_unknown"
                },
            )
        }
    }

    @Synchronized
    fun rendered(present: Boolean?) {
        uiSamples = (uiSamples + 1).coerceAtMost(COUNT_CAP)
        if (!uiObserved || ui != present) {
            uiObserved = true
            ui = present
            record(
                when (present) {
                    true -> "phone_ui_present"
                    false -> "phone_ui_absent"
                    null -> "phone_ui_unknown"
                },
            )
        }
    }

    @Synchronized
    fun peer(frames: List<Envelope>) {
        val ids =
            frames
                .filter {
                    it.type == "modal_shown" &&
                        it.field("conversation_id") == conversationId &&
                        it.field("class") == "permission"
                }.mapNotNull { it.field("modal_id") }
                .toSet()
        val shownCount = ids.size.coerceAtMost(COUNT_CAP)
        val dismissedCount =
            frames
                .filter { it.type == "modal_dismissed" && it.field("modal_id") in ids }
                .mapNotNull { it.field("modal_id") }
                .distinct()
                .size
                .coerceAtMost(COUNT_CAP)
        if (shownCount != peerShown) {
            peerShown = shownCount
            record("peer_wire_shown")
        }
        if (dismissedCount != peerDismissed) {
            peerDismissed = dismissedCount
            record("peer_wire_dismissed")
        }
    }

    @Synchronized
    fun samplingUnavailable() = record("sampling_unavailable")

    @Synchronized
    fun phoneUnavailable() = record("phone_observer_unavailable")

    @Synchronized
    fun summary(): String =
        "phone_wire_shown=${shown.size} phone_wire_dismissed=${dismissed.size} " +
            "peer_wire_shown=$peerShown peer_wire_dismissed=$peerDismissed ui_samples=$uiSamples " +
            "timeline=${timeline.joinToString(",")} dropped=$dropped identity_cap=$CAP timeline_cap=$CAP " +
            "observation_time=local projection=sampled ui=sparse cause=unproven"

    private fun record(label: String) {
        if (timeline.size < CAP) timeline.add("$label@${nowMs()}") else drop()
    }

    private fun drop() {
        dropped = (dropped + 1).coerceAtMost(COUNT_CAP)
    }

    // Retain fixed-size identities only; never emit hashes of opaque modal ids.
    private fun identity(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun Envelope.field(name: String): String? =
        ((payload as? JsonObject)?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val CAP = 64
        const val COUNT_CAP = 999
    }
}
