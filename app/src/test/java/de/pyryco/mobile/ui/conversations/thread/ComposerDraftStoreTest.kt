package de.pyryco.mobile.ui.conversations.thread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-chat draft store (#789), proven without a ViewModel and without a device — the reason drafts
 * are a process-scoped value rather than composition state inside [ThreadInputBar].
 */
class ComposerDraftStoreTest {
    @Test
    fun unknownPair_readsAsEmpty() {
        val store = ComposerDraftStore()
        assertEquals("", store.draftFor("pyrybox", "c1"))
    }

    @Test
    fun draft_roundTripsExactText() {
        val store = ComposerDraftStore()
        store.setDraft("pyrybox", "c1", "  ship it\n")
        // Exact text, surrounding whitespace included (AC #1) — the composer restores what was typed,
        // not a trimmed approximation of it.
        assertEquals("  ship it\n", store.draftFor("pyrybox", "c1"))
    }

    @Test
    fun whitespaceOnlyDraft_isRetained() {
        val store = ComposerDraftStore()
        store.setDraft("pyrybox", "c1", "   ")
        // Only the empty string clears. A blank-but-non-empty draft is still the user's text, and
        // ThreadViewModel.sendMessage refuses to send it, so nothing else would ever clear it.
        assertEquals("   ", store.draftFor("pyrybox", "c1"))
    }

    @Test
    fun emptyText_removesTheEntry() {
        val store = ComposerDraftStore()
        store.setDraft("pyrybox", "c1", "draft")
        store.setDraft("pyrybox", "c1", "")
        assertEquals("", store.draftFor("pyrybox", "c1"))
        assertTrue(store.drafts.value.isEmpty())
    }

    @Test
    fun emptyingTheLastConversation_dropsTheHostBucket() {
        val store = ComposerDraftStore()
        store.setDraft("pyrybox", "c1", "one")
        store.setDraft("pyrybox", "c2", "two")
        store.setDraft("pyrybox", "c1", "")

        // The surviving sibling keeps the host bucket alive...
        assertEquals(setOf("pyrybox"), store.drafts.value.keys)
        assertEquals(mapOf("c2" to "two"), store.drafts.value.getValue("pyrybox"))

        // ...and the bucket goes when its last entry does, so the map never accumulates empty branches.
        store.setDraft("pyrybox", "c2", "")
        assertTrue(store.drafts.value.isEmpty())
    }

    @Test
    fun sameConversationIdOnTwoHosts_holdsTwoIndependentDrafts() {
        val store = ComposerDraftStore()
        // Conversation ids are host-local (see dependency-injection.md § Host identity and snapshots),
        // so the key has to be the pair. An id-only key would show host A's unsent text on host B.
        store.setDraft("pyrybox", "c1", "for pyrybox")
        store.setDraft("laptop", "c1", "for laptop")

        assertEquals("for pyrybox", store.draftFor("pyrybox", "c1"))
        assertEquals("for laptop", store.draftFor("laptop", "c1"))

        // Clearing one leaves the other untouched, entry and host bucket alike.
        store.setDraft("pyrybox", "c1", "")
        assertEquals("", store.draftFor("pyrybox", "c1"))
        assertEquals("for laptop", store.draftFor("laptop", "c1"))
    }

    @Test
    fun drafts_emitsTheCurrentMap() {
        val store = ComposerDraftStore()
        store.setDraft("pyrybox", "c1", "one")
        store.setDraft("laptop", "c2", "two")

        assertEquals(
            mapOf("pyrybox" to mapOf("c1" to "one"), "laptop" to mapOf("c2" to "two")),
            store.drafts.value,
        )
    }
}
