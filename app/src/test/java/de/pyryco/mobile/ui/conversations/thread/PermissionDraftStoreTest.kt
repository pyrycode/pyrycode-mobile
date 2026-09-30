package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.ModalOption
import de.pyryco.mobile.data.model.ModalUiState
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
        val modals = MutableStateFlow<ModalUiState>(open())
        val store = PermissionDraftStore(Dispatchers.Unconfined)
        store.bind("host", owner = "coordinator", modals = modals)
        store.set("host", "chat", draft())
        modals.value = open()
        assertEquals(draft(), store.current("host", "chat"))
    }

    @Test
    fun replacement_retires_the_draft_while_the_thread_is_away() {
        val modals = MutableStateFlow<ModalUiState>(open())
        val store = PermissionDraftStore(Dispatchers.Unconfined)
        store.bind("host", owner = "coordinator", modals = modals)
        store.set("host", "chat", draft())
        modals.value = open(modalId = "m2")
        assertNull(store.current("host", "chat"))
    }

    @Test
    fun resolution_retires_the_draft_even_if_the_request_is_shown_again() {
        val modals = MutableStateFlow<ModalUiState>(open())
        val store = PermissionDraftStore(Dispatchers.Unconfined)
        store.bind("host", owner = "coordinator", modals = modals)
        store.set("host", "chat", draft())
        modals.value = ModalUiState.Dismissed("m1", "reject_once", "remote", "chat")
        modals.value = open()
        assertNull(store.current("host", "chat"))
    }

    @Test
    fun a_changed_offer_retires_the_draft() {
        val modals = MutableStateFlow<ModalUiState>(open())
        val store = PermissionDraftStore(Dispatchers.Unconfined)
        store.bind("host", owner = "coordinator", modals = modals)
        store.set("host", "chat", draft())
        modals.value = open(offered = listOf("Bash(rm -rf)"))
        assertNull(store.current("host", "chat"))
    }

    @Test
    fun another_hosts_modal_never_retires_this_hosts_draft() {
        val modals = MutableStateFlow<ModalUiState>(open())
        val peer = MutableStateFlow<ModalUiState>(open())
        val store = PermissionDraftStore(Dispatchers.Unconfined)
        store.bind("host", owner = "coordinator", modals = modals)
        store.bind("peer", owner = "peer-coordinator", modals = peer)
        store.set("host", "chat", draft())
        peer.value = ModalUiState.Hidden
        assertEquals(draft(), store.current("host", "chat"))
    }

    @Test
    fun a_new_owner_clears_the_host_and_an_existing_owner_rebinds_idempotently() {
        val modals = MutableStateFlow<ModalUiState>(open())
        val store = PermissionDraftStore(Dispatchers.Unconfined)
        store.bind("host", owner = "coordinator", modals = modals)
        store.set("host", "chat", draft())
        store.bind("host", owner = "coordinator", modals = modals)
        assertEquals(draft(), store.current("host", "chat"))
        store.bind("host", owner = "replacement", modals = MutableStateFlow(open()))
        assertNull(store.current("host", "chat"))
    }
}
