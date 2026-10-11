package de.pyryco.mobile.e2e

import de.pyryco.mobile.data.model.ModalEvent
import de.pyryco.mobile.data.network.Envelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopHoldObservationsTest {
    private val conversation = "55cb9ba3-7182-4b87-aee8-a8ddb72999cd"
    private var time = 10L

    private fun recorder() = StopHoldObservations(conversation) { time++ }

    @Test
    fun receiptsRetainTransientPermissionAndCorrelateDismissal() {
        val observations = recorder()
        observations.projection(false)
        observations.phone(shown("private-modal"))
        observations.phone(ModalEvent.Dismissed("private-modal", "private outcome", "private source"))
        observations.projection(false)
        observations.peer(listOf(frame("modal_shown", "private-modal"), frame("modal_dismissed", "private-modal")))
        val summary = observations.summary()
        assertTrue(summary.contains("phone_wire_shown=1 phone_wire_dismissed=1"))
        assertTrue(summary.contains("peer_wire_shown=1 peer_wire_dismissed=1"))
        assertTrue(summary.contains("phone_projection_absent@10"))
        assertTrue(summary.contains("phone_wire_shown@11"))
        assertTrue(summary.contains("phone_wire_dismissed@12"))
        assertFalse(summary.contains("private"))
    }

    @Test
    fun replayedAndForeignModalsDoNotChangeOwnedCounts() {
        val observations = recorder()
        observations.phone(shown("owned"))
        observations.phone(shown("owned"))
        observations.phone(shown("foreign", "foreign conversation"))
        observations.phone(shown("choice").copy(modalClass = "choice"))
        observations.phone(ModalEvent.Dismissed("foreign", "", ""))
        observations.phone(ModalEvent.Dismissed("owned", "", ""))
        observations.phone(ModalEvent.Dismissed("owned", "", ""))
        observations.peer(listOf(frame("modal_shown", "foreign", "foreign conversation"), frame("modal_dismissed", "foreign")))
        assertTrue(observations.summary().contains("phone_wire_shown=1 phone_wire_dismissed=1"))
        assertTrue(observations.summary().contains("peer_wire_shown=0 peer_wire_dismissed=0"))
    }

    @Test
    fun projectionRenderingAndReceiptAreDistinctObservations() {
        val observations = recorder()
        observations.projection(false)
        observations.rendered(false)
        observations.phone(shown("modal"))
        observations.projection(true)
        observations.rendered(true)
        observations.phone(ModalEvent.Dismissed("modal", "", ""))
        observations.projection(false)
        observations.rendered(false)
        observations.phone(null)
        observations.samplingUnavailable()
        val summary = observations.summary()
        for (label in listOf(
            "phone_wire_shown",
            "phone_wire_dismissed",
            "phone_projection_present",
            "phone_projection_absent",
            "phone_ui_present",
            "phone_ui_absent",
            "phone_observer_reset",
            "sampling_unavailable",
        )) {
            assertTrue(summary, summary.contains(label))
        }
        assertTrue(summary.contains("ui_samples=3"))
        assertTrue(summary.contains("cause=unproven"))
    }

    @Test
    fun temporalOutputAndIdentityStorageAreBoundedAndContentFree() {
        val observations = recorder()
        repeat(2000) {
            observations.phone(shown("private material".repeat(1000) + it))
            observations.projection(it % 2 == 0)
            observations.rendered(it % 2 == 0)
        }
        val summary = observations.summary()
        assertTrue(summary.contains("phone_wire_shown=64"))
        assertTrue(summary.contains("identity_cap=64"))
        assertTrue(summary.contains("timeline_cap=64"))
        assertTrue(summary.contains("dropped=999"))
        assertTrue(summary.contains("ui_samples=999"))
        assertFalse(summary.contains("private"))
        assertTrue(summary.length < 4000)
    }

    @Test
    fun temporalInvocationsDoNotShareIdentityOrState() {
        val first = recorder()
        first.phone(shown("reused"))
        first.phone(ModalEvent.Dismissed("reused", "", ""))
        val second = recorder()
        second.phone(shown("reused"))
        assertTrue(second.summary().contains("phone_wire_shown=1 phone_wire_dismissed=0"))
        assertEquals(1, second.summary().split("phone_wire_shown@").size - 1)
    }

    private fun shown(
        id: String,
        owner: String = conversation,
    ) = ModalEvent.Shown(id, "permission", "private title", "private prompt", emptyList(), "", owner)

    private fun frame(
        type: String,
        id: String,
        owner: String = conversation,
    ) = Envelope(
        1,
        type,
        "private timestamp",
        JsonObject(
            mapOf(
                "modal_id" to JsonPrimitive(id),
                "class" to JsonPrimitive("permission"),
                "conversation_id" to JsonPrimitive(owner),
            ),
        ),
    )
}
