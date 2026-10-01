package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.HostModalState
import de.pyryco.mobile.data.model.ModalEvent
import de.pyryco.mobile.data.model.ModalOption
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.reduce
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class PermissionDraftStoreTest {
    private val enabled = RelayLog.enabled

    @Before fun silenceLogs() {
        RelayLog.enabled = false
    }

    @After fun restoreLogs() {
        RelayLog.enabled = enabled
    }

    private val rules = listOf("Bash(npm test)")

    private fun open(
        modalId: String = "m1",
        conversationId: String = "chat",
        offered: List<String> = rules,
    ) = ModalUiState.Open(
        modalId = modalId,
        modalClass = "permission",
        title = "Permission required",
        prompt = "Run npm test?",
        options = listOf(ModalOption("allow_once", "Allow once"), ModalOption("reject_once", "Reject once")),
        defaultOptionId = "reject_once",
        conversationId = conversationId,
        alwaysAllowRules = offered,
    )

    private fun draft(modalId: String = "m1") = PermissionGrantDraft(modalId, rules)

    private fun held(vararg prompts: ModalUiState.Open) = HostModalState(outstanding = prompts.toList())

    @Test
    fun drafts_are_isolated_by_server_and_conversation() {
        val store = PermissionDraftStore(Dispatchers.Unconfined)
        store.set("host", "chat", draft())
        assertEquals(draft(), store.current("host", "chat"))
        assertNull(store.current("host", "other"))
        assertNull(store.current("peer", "chat"))
        store.set("host", "chat", null)
        assertNull(store.current("host", "chat"))
    }

    @Test
    fun the_same_outstanding_request_keeps_its_draft() {
        val modals = MutableStateFlow(held(open()))
        val store = PermissionDraftStore(Dispatchers.Unconfined)
        store.bind("host", owner = "coordinator", modals = modals)
        store.set("host", "chat", draft())
        modals.value = held(open())
        assertEquals(draft(), store.current("host", "chat"))
    }

    @Test
    fun replacement_retires_the_draft_while_the_thread_is_away() {
        val modals = MutableStateFlow(held(open()))
        val store = PermissionDraftStore(Dispatchers.Unconfined)
        store.bind("host", owner = "coordinator", modals = modals)
        store.set("host", "chat", draft())
        modals.value = held(open(modalId = "m2"))
        assertNull(store.current("host", "chat"))
    }

    @Test
    fun resolution_retires_the_draft_even_if_the_request_is_shown_again() {
        val modals = MutableStateFlow(held(open()))
        val store = PermissionDraftStore(Dispatchers.Unconfined)
        store.bind("host", owner = "coordinator", modals = modals)
        store.set("host", "chat", draft())
        modals.value = modals.value.reduce(ModalEvent.Dismissed("m1", "reject_once", "remote"))
        modals.value = held(open())
        assertNull(store.current("host", "chat"))
    }

    @Test
    fun a_changed_offer_retires_the_draft() {
        val modals = MutableStateFlow(held(open()))
        val store = PermissionDraftStore(Dispatchers.Unconfined)
        store.bind("host", owner = "coordinator", modals = modals)
        store.set("host", "chat", draft())
        modals.value = held(open(offered = listOf("Bash(rm -rf)")))
        assertNull(store.current("host", "chat"))
    }

    @Test
    fun another_hosts_modal_never_retires_this_hosts_draft() {
        val modals = MutableStateFlow(held(open()))
        val peer = MutableStateFlow(held(open()))
        val store = PermissionDraftStore(Dispatchers.Unconfined)
        store.bind("host", owner = "coordinator", modals = modals)
        store.bind("peer", owner = "peer-coordinator", modals = peer)
        store.set("host", "chat", draft())
        peer.value = HostModalState()
        assertEquals(draft(), store.current("host", "chat"))
    }

    @Test
    fun a_new_owner_clears_the_host_and_an_existing_owner_rebinds_idempotently() {
        val modals = MutableStateFlow(held(open()))
        val store = PermissionDraftStore(Dispatchers.Unconfined)
        store.bind("host", owner = "coordinator", modals = modals)
        store.set("host", "chat", draft())
        store.bind("host", owner = "coordinator", modals = modals)
        assertEquals(draft(), store.current("host", "chat"))
        store.bind("host", owner = "replacement", modals = MutableStateFlow(held(open())))
        assertNull(store.current("host", "chat"))
    }

    // #1337: the host holds every chat's prompt, so another chat's prompt arriving keeps this chat's draft.
    @Test
    fun another_chats_prompt_arriving_keeps_this_chats_draft() {
        val modals = MutableStateFlow(held(open()))
        val store = PermissionDraftStore(Dispatchers.Unconfined)
        store.bind("host", owner = "coordinator", modals = modals)
        store.set("host", "chat", draft())
        store.set("host", "other", draft(modalId = "m2"))

        modals.value = held(open(), open(modalId = "m2", conversationId = "other"))
        assertEquals(draft(), store.current("host", "chat"))
        assertEquals(draft(modalId = "m2"), store.current("host", "other"))

        modals.value = modals.value.reduce(ModalEvent.Dismissed("m2", "allow_once", "local"))
        assertEquals(draft(), store.current("host", "chat"))
        assertNull(store.current("host", "other"))
    }

    // #1337: a reconnect empties the host's prompts; the daemon's unchanged re-send keeps the tick.
    @Test
    fun a_reconnect_clear_keeps_the_draft_for_an_unchanged_resend() {
        val modals = MutableStateFlow(held(open()))
        val store = PermissionDraftStore(Dispatchers.Unconfined)
        store.bind("host", owner = "coordinator", modals = modals)
        store.set("host", "chat", draft())
        modals.value = HostModalState()
        assertEquals(draft(), store.current("host", "chat"))
        modals.value = HostModalState().reduce(shown(open()))
        assertEquals(draft(), store.current("host", "chat"))
    }

    // #1337: after a reconnect clear, a different request for the chat retires the old draft.
    @Test
    fun a_different_request_after_a_reconnect_clear_retires_the_draft() {
        val modals = MutableStateFlow(held(open()))
        val store = PermissionDraftStore(Dispatchers.Unconfined)
        store.bind("host", owner = "coordinator", modals = modals)
        store.set("host", "chat", draft())
        store.set("host", "other", draft(modalId = "m2"))
        modals.value = HostModalState()
        modals.value = HostModalState().reduce(shown(open(modalId = "m3")))
        assertNull(store.current("host", "chat"))
        assertEquals("a chat holding no prompt keeps its draft", draft(modalId = "m2"), store.current("host", "other"))
    }

    private fun shown(modal: ModalUiState.Open) =
        ModalEvent.Shown(
            modal.modalId,
            modal.modalClass,
            modal.title,
            modal.prompt,
            modal.options,
            modal.defaultOptionId,
            modal.conversationId,
            alwaysAllowRules = modal.alwaysAllowRules,
        )
}
