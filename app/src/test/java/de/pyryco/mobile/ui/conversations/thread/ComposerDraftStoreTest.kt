package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.network.MessageAttachmentIds
import de.pyryco.mobile.data.repository.AttachmentUploadLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ---- #932: pending attachments beside the text ------------------------------------------------

    private fun ComposerDraftStore.add(
        serverId: String,
        conversationId: String,
        name: String,
        size: Long? = 10L,
    ): AttachmentAddOutcome = addAttachment(serverId, conversationId, "content://docs/$name", name, "text/plain", size)

    @Test
    fun attachments_areKeptPerPair_includingTheSameIdUnderAnotherHost() {
        val store = ComposerDraftStore()
        assertEquals(AttachmentAddOutcome.ADDED, store.add("pyrybox", "c1", "a.txt"))
        store.add("laptop", "c1", "b.txt")
        store.add("pyrybox", "c2", "c.txt")

        assertEquals(listOf("a.txt"), store.attachmentsFor("pyrybox", "c1").map { it.displayName })
        assertEquals(listOf("b.txt"), store.attachmentsFor("laptop", "c1").map { it.displayName })
        assertEquals(listOf("c.txt"), store.attachmentsFor("pyrybox", "c2").map { it.displayName })
        assertEquals(emptyList<PendingAttachment>(), store.attachmentsFor("laptop", "c2"))
    }

    @Test
    fun addAttachment_holdsTheGivenMetadata_withNoIdYet() {
        val store = ComposerDraftStore()
        store.addAttachment("pyrybox", "c1", "content://docs/1", "report.pdf", "application/pdf", 1234L)

        val entry = store.attachmentsFor("pyrybox", "c1").single()
        assertEquals("content://docs/1", entry.uri)
        assertEquals("report.pdf", entry.displayName)
        assertEquals("application/pdf", entry.mimeType)
        assertEquals(1234L, entry.size)
        assertNull(entry.attachmentId)
    }

    @Test
    fun addAttachment_refusesAKnownSizeOverTheUploadLimit_butNotAtItOrUnknown() {
        val store = ComposerDraftStore()
        val limit = AttachmentUploadLimit.MAX_BYTES.toLong()

        assertEquals(AttachmentAddOutcome.TOO_LARGE, store.add("pyrybox", "c1", "big", size = limit + 1))
        assertEquals(AttachmentAddOutcome.ADDED, store.add("pyrybox", "c1", "edge", size = limit))
        // An unknown size is only a hint missing, not a refusal: the read at send time bounds it.
        assertEquals(AttachmentAddOutcome.ADDED, store.add("pyrybox", "c1", "unknown", size = null))

        assertEquals(listOf("edge", "unknown"), store.attachmentsFor("pyrybox", "c1").map { it.displayName })
    }

    @Test
    fun addAttachment_refusesTheEntryPastTheMessageIdLimit() {
        val store = ComposerDraftStore()
        repeat(MessageAttachmentIds.MAX) { assertEquals(AttachmentAddOutcome.ADDED, store.add("pyrybox", "c1", "f$it")) }

        assertEquals(AttachmentAddOutcome.TOO_MANY, store.add("pyrybox", "c1", "one-too-many"))
        assertEquals(MessageAttachmentIds.MAX, store.attachmentsFor("pyrybox", "c1").size)
        // The limit is per draft: another chat still has room.
        assertEquals(AttachmentAddOutcome.ADDED, store.add("pyrybox", "c2", "elsewhere"))
    }

    @Test
    fun removeAttachment_leavesTheRestInOrder() {
        val store = ComposerDraftStore()
        listOf("a", "b", "c").forEach { store.add("pyrybox", "c1", it) }
        val middle = store.attachmentsFor("pyrybox", "c1")[1]

        store.removeAttachment("pyrybox", "c1", middle.key)

        assertEquals(listOf("a", "c"), store.attachmentsFor("pyrybox", "c1").map { it.displayName })
    }

    @Test
    fun removingTheLastAttachment_dropsTheHostBucket() {
        val store = ComposerDraftStore()
        store.add("pyrybox", "c1", "a")
        store.removeAttachment("pyrybox", "c1", store.attachmentsFor("pyrybox", "c1").single().key)
        assertTrue(store.attachments.value.isEmpty())
    }

    @Test
    fun keys_areDistinctEvenForTheSameFile() {
        val store = ComposerDraftStore()
        store.add("pyrybox", "c1", "same")
        store.add("pyrybox", "c1", "same")
        val keys = store.attachmentsFor("pyrybox", "c1").map { it.key }
        assertEquals(2, keys.toSet().size)
    }

    @Test
    fun markUploaded_recordsTheIdOnThatEntryOnly() {
        val store = ComposerDraftStore()
        store.add("pyrybox", "c1", "a")
        store.add("pyrybox", "c1", "b")
        val (a, b) = store.attachmentsFor("pyrybox", "c1")

        store.markUploaded("pyrybox", "c1", a.key, "id-a")
        // An entry that is gone is a no-op, never a resurrection.
        store.markUploaded("pyrybox", "c1", 999_999L, "id-ghost")

        val after = store.attachmentsFor("pyrybox", "c1")
        assertEquals(listOf(a.key, b.key), after.map { it.key })
        assertEquals("id-a", after[0].attachmentId)
        assertNull(after[1].attachmentId)
    }

    @Test
    fun removeAttachments_removesOnlyTheNamedKeys() {
        val store = ComposerDraftStore()
        listOf("a", "b", "c").forEach { store.add("pyrybox", "c1", it) }
        val (a, _, c) = store.attachmentsFor("pyrybox", "c1")

        store.removeAttachments("pyrybox", "c1", setOf(a.key, c.key))

        assertEquals(listOf("b"), store.attachmentsFor("pyrybox", "c1").map { it.displayName })
    }

    @Test
    fun clearHost_dropsThatHostsAttachmentsWithItsText() {
        val store = ComposerDraftStore()
        store.setDraft("pyrybox", "c1", "text")
        store.add("pyrybox", "c1", "a")
        store.add("laptop", "c1", "b")

        store.clearHost("pyrybox")

        assertEquals("", store.draftFor("pyrybox", "c1"))
        assertTrue(store.attachmentsFor("pyrybox", "c1").isEmpty())
        assertEquals(listOf("b"), store.attachmentsFor("laptop", "c1").map { it.displayName })
    }

    @Test
    fun clearConversation_dropsThatPairsAttachmentsWithItsText() {
        val store = ComposerDraftStore()
        store.setDraft("pyrybox", "c1", "text")
        store.add("pyrybox", "c1", "a")
        store.add("pyrybox", "c2", "b")

        store.clearConversation("pyrybox", "c1")

        assertEquals("", store.draftFor("pyrybox", "c1"))
        assertTrue(store.attachmentsFor("pyrybox", "c1").isEmpty())
        assertEquals(listOf("b"), store.attachmentsFor("pyrybox", "c2").map { it.displayName })
    }

    @Test
    fun emptyingTheText_leavesTheAttachments() {
        val store = ComposerDraftStore()
        store.setDraft("pyrybox", "c1", "text")
        store.add("pyrybox", "c1", "a")

        store.setDraft("pyrybox", "c1", "")

        assertEquals(listOf("a"), store.attachmentsFor("pyrybox", "c1").map { it.displayName })
    }

    @Test
    fun providerText_isClampedWithoutSplittingASurrogatePair() {
        val store = ComposerDraftStore()
        // 1023 ASCII chars then an emoji: a plain cut at 1024 would leave a lone high surrogate.
        val name = "a".repeat(PENDING_ATTACHMENT_TEXT_MAX_CHARS - 1) + "😀" + "tail"
        store.addAttachment("pyrybox", "c1", "content://docs/1", name, "x".repeat(5000), 1L)

        val entry = store.attachmentsFor("pyrybox", "c1").single()
        assertEquals("a".repeat(PENDING_ATTACHMENT_TEXT_MAX_CHARS - 1), entry.displayName)
        assertEquals(PENDING_ATTACHMENT_TEXT_MAX_CHARS, entry.mimeType.length)
    }

    @Test
    fun pendingAttachment_toStringRedactsUriNameTypeAndId() {
        val entry = PendingAttachment(7L, "content://secret/doc", "private.pdf", "application/pdf", 99L, "att-id")
        val printed = entry.toString()
        listOf("secret", "private", "application/pdf", "att-id").forEach {
            assertFalse("toString must not print $it: $printed", printed.contains(it))
        }
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
