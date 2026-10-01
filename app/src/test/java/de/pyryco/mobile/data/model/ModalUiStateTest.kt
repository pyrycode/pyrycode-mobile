package de.pyryco.mobile.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure fold tests for [HostModalState.reduce] (#1337): the host holds every outstanding prompt keyed on
 * `modalId`, and each thread sees its own through [HostModalState.scopedTo]. The fold is a pure function, so
 * these exercise it by direct calls. Its wiring into the coordinator's process-scoped `StateFlow`, including
 * the clear on each new connection, is covered by `RelayRepositoryCoordinatorTest`.
 */
class ModalUiStateTest {
    private val empty = HostModalState()

    @Test
    fun shownFromEmpty_holdsAnOpenCarryingEveryFieldVerbatimInWireOrder() {
        val options =
            listOf(
                ModalOption("allow_once", "Allow once"),
                ModalOption("allow_always", "Allow always"),
                ModalOption("reject_once", "Reject once"),
                ModalOption("reject_always", "Reject always"),
            )
        val next =
            empty.reduce(
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
            listOf(
                ModalUiState.Open(
                    modalId = "m1",
                    modalClass = "permission",
                    title = "Run command?",
                    prompt = "rm -rf /tmp/build",
                    options = options,
                    defaultOptionId = "reject_once",
                    conversationId = "c1",
                ),
            ),
            next.outstanding,
        )
        // Option list carried verbatim, in wire array order (the canonical display order).
        assertEquals(options, next.outstanding.single().options)
    }

    @Test
    fun matchingDismiss_removesThePromptAndRecordsTheVerbatimOutcomeAndSource() {
        // Every source value — the closed set plus a forward-compat value — is carried verbatim.
        for (source in listOf("remote", "local", "timeout", "future_source_v3")) {
            val next = held(open("m1")).reduce(ModalEvent.Dismissed(modalId = "m1", outcome = "allow_once", source = source))

            // The dismissal carries the held prompt's conversation (#816): the wire dismiss has none.
            assertEquals(emptyList<ModalUiState.Open>(), next.outstanding)
            assertEquals(
                listOf(ModalUiState.Dismissed(modalId = "m1", outcome = "allow_once", source = source, conversationId = "c1")),
                next.resolved,
            )
        }
    }

    // #817: the permission context rides the fold, and a re-send of the same id replaces it.
    @Test
    fun shownCarriesItsPermissionContext_andAReSendReplacesIt() {
        val context = ModalContext(reason = "A rule matched", reasonType = "rule", blockedPath = "/etc", description = "d")
        val shown = ModalEvent.Shown("m1", "permission", "t", "p", emptyList(), "d", "c1", context)

        val opened = empty.reduce(shown)
        assertEquals(context, opened.outstanding.single().context)

        val replaced = opened.reduce(shown.copy(context = ModalContext.None))
        assertEquals(ModalContext.None, replaced.outstanding.single().context)
    }

    // #818: the always-allow rules ride the fold, and a re-send without an offer replaces them.
    @Test
    fun shownCarriesItsAlwaysAllowRules_andAReSendReplacesThem() {
        val shown =
            ModalEvent.Shown("m1", "permission", "t", "p", emptyList(), "d", "c1", alwaysAllowRules = listOf("Read", "Bash(ls)"))

        val opened = empty.reduce(shown)
        assertEquals(listOf("Read", "Bash(ls)"), opened.outstanding.single().alwaysAllowRules)

        val replaced = opened.reduce(shown.copy(alwaysAllowRules = emptyList()))
        assertEquals(emptyList<String>(), replaced.outstanding.single().alwaysAllowRules)
    }

    // #818: only a permission ask with an available offer shows it.
    @Test
    fun offersAlwaysAllow_onlyForAPermissionAskWithRules() {
        val withRules = open("m1").copy(alwaysAllowRules = listOf("Read"))
        assertEquals(true, withRules.offersAlwaysAllow)
        assertEquals(false, withRules.copy(modalClass = "trust").offersAlwaysAllow)
        assertEquals(false, withRules.copy(modalClass = "future_class").offersAlwaysAllow)
        assertEquals(false, open("m1").offersAlwaysAllow)
    }

    @Test
    fun unknownDismiss_changesNothing() {
        // A dismiss for an id the host does not hold must not clear anything (spoofed-dismiss safety).
        val state = held(open("m1"))
        assertSame(state, state.reduce(ModalEvent.Dismissed(modalId = "m2", outcome = "reject_once", source = "remote")))
        assertSame(empty, empty.reduce(ModalEvent.Dismissed("m1", "reject_once", "remote")))
    }

    @Test
    fun aSecondChatsPrompt_isHeldBesideTheFirst() {
        val next = held(open("a1", "A")).reduce(shown("b1", "B"))

        assertEquals(listOf("a1", "b1"), next.outstanding.map { it.modalId })
        assertEquals("a1", (next.scopedTo("A") as ModalUiState.Open).modalId)
        assertEquals("b1", (next.scopedTo("B") as ModalUiState.Open).modalId)
    }

    @Test
    fun dismissingOneChatsPrompt_leavesTheOther() {
        val next = held(open("a1", "A"), open("b1", "B")).reduce(ModalEvent.Dismissed("a1", "allow_once", "local"))

        assertEquals(ModalUiState.Dismissed("a1", "allow_once", "local", "A"), next.scopedTo("A"))
        assertEquals("b1", (next.scopedTo("B") as ModalUiState.Open).modalId)
    }

    @Test
    fun repeatShownForAHeldId_replacesThatPromptInPlace() {
        // A re-send keeps the prompt's position, so a chat keeps showing the prompt it showed.
        val resent = shown("a1", "A").copy(prompt = "do it now")
        val next = held(open("a1", "A"), open("b1", "B")).reduce(resent)

        assertEquals(listOf("a1", "b1"), next.outstanding.map { it.modalId })
        assertEquals("do it now", next.outstanding.first().prompt)
    }

    @Test
    fun aDismissedId_isNotShownAgainOnTheSameFold() {
        val dismissed = held(open("a1", "A")).reduce(ModalEvent.Dismissed("a1", "allow_once", "remote"))

        val next = dismissed.reduce(shown("a1", "A"))

        assertSame(dismissed, next)
        assertEquals(ModalUiState.Dismissed("a1", "allow_once", "remote", "A"), next.scopedTo("A"))
    }

    @Test
    fun aChatShowsItsFirstOutstandingPrompt_thenItsLatestDismissal() {
        val state = held(open("a1", "A"), open("a2", "A"))
        assertEquals("a1", (state.scopedTo("A") as ModalUiState.Open).modalId)

        val afterFirst = state.reduce(ModalEvent.Dismissed("a1", "allow_once", "local"))
        assertEquals("a2", (afterFirst.scopedTo("A") as ModalUiState.Open).modalId)

        val afterBoth = afterFirst.reduce(ModalEvent.Dismissed("a2", "reject_once", "timeout"))
        assertEquals(ModalUiState.Dismissed("a2", "reject_once", "timeout", "A"), afterBoth.scopedTo("A"))
        assertEquals(ModalUiState.Hidden, afterBoth.scopedTo("B"))
    }

    @Test
    fun hostScopedTo_blankConversation_rendersInNoThread() {
        val state = held(open("m1", ""))
        assertEquals(ModalUiState.Hidden, state.scopedTo(""))
        assertEquals(ModalUiState.Hidden, state.scopedTo("c1"))
        assertEquals(ModalUiState.Hidden, held(open("m1")).scopedTo(""))
    }

    @Test
    fun scopedTo_keepsOpenAndDismissedOnlyForTheirOwnConversation() {
        val open = open("m1")
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
        val unscoped = open("m1").copy(conversationId = "")
        assertEquals(ModalUiState.Hidden, unscoped.scopedTo(""))
        assertEquals(ModalUiState.Hidden, unscoped.scopedTo("c1"))
        assertEquals(ModalUiState.Hidden, ModalUiState.Dismissed("m1", "o", "remote").scopedTo(""))
        assertEquals(ModalUiState.Hidden, open("m1").scopedTo(""))
    }

    // ---- #1340: this phone's own answers and the daemon's refusals -------------------------------

    @Test
    fun answeredHere_removesTheHeldPrompt_andTheThreadSeesNoDismissal() {
        val next = held(open("m1"), open("m2", conversationId = "c2")).reduce(ModalAction.AnsweredHere("m1"))

        assertEquals(listOf("m2"), next.outstanding.map { it.modalId })
        assertEquals(
            listOf(ModalUiState.Dismissed("m1", outcome = "", source = "", conversationId = "c1", answeredHere = true)),
            next.resolved,
        )
        assertEquals(ModalUiState.Hidden, next.scopedTo("c1"))
    }

    @Test
    fun answeredHere_hidesAnOlderDismissalOfTheSameChat() {
        // Otherwise the chat would go Open(m2) → Dismissed(m1) and replay m1's "resolved elsewhere" message.
        val next =
            held(open("m1"))
                .reduce(ModalEvent.Dismissed("m1", "allow_once", "remote"))
                .reduce(shown("m2", "c1"))
                .reduce(ModalAction.AnsweredHere("m2"))

        assertEquals(ModalUiState.Hidden, next.scopedTo("c1"))
    }

    @Test
    fun answeredHere_aRepeatedShownAndTheDaemonsLaterDismissAreIgnored() {
        val answered = held(open("m1")).reduce(ModalAction.AnsweredHere("m1"))

        assertSame(answered, answered.reduce(shown("m1", "c1")))
        assertSame(answered, answered.reduce(ModalEvent.Dismissed("m1", "allow_once", "remote")))
    }

    @Test
    fun answeredHere_forAnIdTheHostDoesNotHold_changesNothing() {
        val host = held(open("m1"))
        assertSame(host, host.reduce(ModalAction.AnsweredHere("other")))
    }

    @Test
    fun rejected_marksTheOwningChat_andItsDismissalClearsIt() {
        val rejected = empty.reduce(ModalAction.Rejected("c1"))
        assertEquals(setOf("c1"), rejected.rejectedConversations)

        assertEquals(emptySet<String>(), rejected.reduce(ModalAction.RejectionDismissed("c1")).rejectedConversations)
        assertEquals(setOf("c1"), rejected.reduce(ModalAction.RejectionDismissed("c2")).rejectedConversations)
    }

    @Test
    fun rejected_withABlankOwner_marksNothing() {
        assertSame(empty, empty.reduce(ModalAction.Rejected("")))
    }

    @Test
    fun reconnected_keepsRejections_dropsPromptsAndAnswers_soAReSentPromptReturns() {
        val before =
            held(open("m1"), open("m2"))
                .reduce(ModalAction.AnsweredHere("m1"))
                .reduce(ModalAction.Rejected("c1"))

        val after = before.reconnected()

        assertEquals(HostModalState(rejectedConversations = setOf("c1")), after)
        assertEquals(listOf("m1"), after.reduce(shown("m1", "c1")).outstanding.map { it.modalId })
    }

    private fun held(vararg prompts: ModalUiState.Open) = HostModalState(outstanding = prompts.toList())

    private fun shown(
        modalId: String,
        conversationId: String,
    ) = ModalEvent.Shown(
        modalId = modalId,
        modalClass = "permission",
        title = "Run command?",
        prompt = "do the thing",
        options = listOf(ModalOption("allow_once", "Allow once"), ModalOption("reject_once", "Reject once")),
        defaultOptionId = "reject_once",
        conversationId = conversationId,
    )

    private fun open(
        modalId: String,
        conversationId: String = "c1",
    ): ModalUiState.Open =
        ModalUiState.Open(
            modalId = modalId,
            modalClass = "permission",
            title = "Run command?",
            prompt = "do the thing",
            options = listOf(ModalOption("allow_once", "Allow once"), ModalOption("reject_once", "Reject once")),
            defaultOptionId = "reject_once",
            conversationId = conversationId,
        )
}
