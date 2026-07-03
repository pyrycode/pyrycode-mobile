package de.pyryco.mobile.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure fold tests for [ModalUiState.reduce] (#492): the "which modal is open" accumulation moved out of
 * [de.pyryco.mobile.ui.conversations.thread.ThreadViewModel] to the process-scoped coordinator, and the
 * type + fold relocated here to `data/model`. The fold is a pure function with no flow/coroutine/VM
 * scaffolding, so these exercise it by direct calls (simpler than the old `MutableSharedFlow`-driven
 * ThreadViewModel versions). The wiring of this fold into the coordinator's process-scoped `StateFlow`
 * is covered by `RelayRepositoryCoordinatorTest.currentModal_*`.
 */
class ModalUiStateTest {
    @Test
    fun shownFromHidden_becomesOpenCarryingEveryFieldVerbatimInWireOrder() {
        val options =
            listOf(
                ModalOption("allow_once", "Allow once"),
                ModalOption("allow_always", "Allow always"),
                ModalOption("reject_once", "Reject once"),
                ModalOption("reject_always", "Reject always"),
            )
        val next =
            ModalUiState.Hidden.reduce(
                ModalEvent.Shown(
                    modalId = "m1",
                    modalClass = "permission",
                    title = "Run command?",
                    prompt = "rm -rf /tmp/build",
                    options = options,
                    defaultOptionId = "reject_once",
                ),
            )

        assertEquals(
            ModalUiState.Open(
                modalId = "m1",
                modalClass = "permission",
                title = "Run command?",
                prompt = "rm -rf /tmp/build",
                options = options,
                defaultOptionId = "reject_once",
            ),
            next,
        )
        // Option list carried verbatim, in wire array order (the canonical display order).
        assertEquals(options, (next as ModalUiState.Open).options)
    }

    @Test
    fun matchingDismiss_clearsOpenWithVerbatimOutcomeAndSource() {
        // Every source value — the closed set plus a forward-compat value — is carried verbatim.
        for (source in listOf("remote", "local", "timeout", "future_source_v3")) {
            val open = open(modalId = "m1")
            val next = open.reduce(ModalEvent.Dismissed(modalId = "m1", outcome = "allow_once", source = source))

            assertEquals(
                ModalUiState.Dismissed(modalId = "m1", outcome = "allow_once", source = source),
                next,
            )
        }
    }

    @Test
    fun nonMatchingDismiss_leavesOpenUnchanged() {
        val open = open(modalId = "m1")
        // A dismiss for a different modalId must not clear the open modal (spoofed-dismiss safety).
        val next = open.reduce(ModalEvent.Dismissed(modalId = "m2", outcome = "reject_once", source = "remote"))

        assertEquals(open, next)
    }

    @Test
    fun dismissFromHiddenOrDismissed_isNoOp() {
        val fromHidden = ModalUiState.Hidden.reduce(ModalEvent.Dismissed("m1", "reject_once", "remote"))
        assertEquals(ModalUiState.Hidden, fromHidden)

        val alreadyDismissed = ModalUiState.Dismissed("m1", "reject_once", "remote")
        val next = alreadyDismissed.reduce(ModalEvent.Dismissed("m2", "allow_once", "local"))
        assertEquals(alreadyDismissed, next)
    }

    @Test
    fun shownSupersedesOpen_unconditionallyLastShownWins() {
        val next = open(modalId = "m1").reduce(ModalEvent.Shown("m2", "permission", "t", "p", emptyList(), "d"))
        assertEquals("m2", (next as ModalUiState.Open).modalId)
    }

    private fun open(modalId: String): ModalUiState.Open =
        ModalUiState.Open(
            modalId = modalId,
            modalClass = "permission",
            title = "Run command?",
            prompt = "do the thing",
            options = listOf(ModalOption("allow_once", "Allow once"), ModalOption("reject_once", "Reject once")),
            defaultOptionId = "reject_once",
        )
}
