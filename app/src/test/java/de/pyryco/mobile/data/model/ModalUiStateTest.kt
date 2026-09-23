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
                    conversationId = "c1",
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
                conversationId = "c1",
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

            // The dismissed state carries the open modal's conversation (#816): the wire dismiss has none.
            assertEquals(
                ModalUiState.Dismissed(modalId = "m1", outcome = "allow_once", source = source, conversationId = "c1"),
                next,
            )
        }
    }

    // #817: the permission context rides the fold, and a later Shown replaces it wholesale.
    @Test
    fun shownCarriesItsPermissionContext_andALaterShownReplacesIt() {
        val context = ModalContext(reason = "A rule matched", reasonType = "rule", blockedPath = "/etc", description = "d")
        val shown = ModalEvent.Shown("m1", "permission", "t", "p", emptyList(), "d", "c1", context)

        val opened = ModalUiState.Hidden.reduce(shown)
        assertEquals(context, (opened as ModalUiState.Open).context)

        val replaced = opened.reduce(shown.copy(modalId = "m2", context = ModalContext.None))
        assertEquals(ModalContext.None, (replaced as ModalUiState.Open).context)
    }

    // #818: the always-allow rules ride the fold, and a later Shown without an offer replaces them.
    @Test
    fun shownCarriesItsAlwaysAllowRules_andALaterShownReplacesThem() {
        val shown =
            ModalEvent.Shown("m1", "permission", "t", "p", emptyList(), "d", "c1", alwaysAllowRules = listOf("Read", "Bash(ls)"))

        val opened = ModalUiState.Hidden.reduce(shown)
        assertEquals(listOf("Read", "Bash(ls)"), (opened as ModalUiState.Open).alwaysAllowRules)

        val replaced = opened.reduce(shown.copy(modalId = "m2", alwaysAllowRules = emptyList()))
        assertEquals(emptyList<String>(), (replaced as ModalUiState.Open).alwaysAllowRules)
    }

    // #818: only a permission ask with an available offer shows it.
    @Test
    fun offersAlwaysAllow_onlyForAPermissionAskWithRules() {
        val withRules = open(modalId = "m1").copy(alwaysAllowRules = listOf("Read"))
        assertEquals(true, withRules.offersAlwaysAllow)
        assertEquals(false, withRules.copy(modalClass = "trust").offersAlwaysAllow)
        assertEquals(false, withRules.copy(modalClass = "future_class").offersAlwaysAllow)
        assertEquals(false, open(modalId = "m1").offersAlwaysAllow)
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

    @Test
    fun repeatShownWithSameModalId_replacesOpenInPlace() {
        // A reconnect re-sends the outstanding modal_shown with the same modal_id and conversation (#816).
        val resent = ModalEvent.Shown("m1", "permission", "Run command?", "do it now", emptyList(), "d", "c1")
        val next = open(modalId = "m1").reduce(resent)

        assertEquals(
            ModalUiState.Open("m1", "permission", "Run command?", "do it now", emptyList(), "d", "c1"),
            next,
        )
    }

    @Test
    fun scopedTo_keepsOpenAndDismissedOnlyForTheirOwnConversation() {
        val open = open(modalId = "m1")
        val dismissed = ModalUiState.Dismissed("m1", "reject_once", "remote", conversationId = "c1")

        assertEquals(open, open.scopedTo("c1"))
        assertEquals(dismissed, dismissed.scopedTo("c1"))
        assertEquals(ModalUiState.Hidden, open.scopedTo("c2"))
        assertEquals(ModalUiState.Hidden, dismissed.scopedTo("c2"))
        assertEquals(ModalUiState.Hidden, ModalUiState.Hidden.scopedTo("c1"))
    }

    @Test
    fun scopedTo_blankConversationOnEitherSide_rendersInNoThread() {
        // An unscoped prompt (no conversation_id on the wire) belongs to no thread, never to every thread.
        val unscoped = open(modalId = "m1").copy(conversationId = "")
        assertEquals(ModalUiState.Hidden, unscoped.scopedTo(""))
        assertEquals(ModalUiState.Hidden, unscoped.scopedTo("c1"))
        assertEquals(ModalUiState.Hidden, ModalUiState.Dismissed("m1", "o", "remote").scopedTo(""))
        assertEquals(ModalUiState.Hidden, open(modalId = "m1").scopedTo(""))
    }

    private fun open(modalId: String): ModalUiState.Open =
        ModalUiState.Open(
            modalId = modalId,
            modalClass = "permission",
            title = "Run command?",
            prompt = "do the thing",
            options = listOf(ModalOption("allow_once", "Allow once"), ModalOption("reject_once", "Reject once")),
            defaultOptionId = "reject_once",
            conversationId = "c1",
        )
}
