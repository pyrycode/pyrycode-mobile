package de.pyryco.mobile.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeConversationRepositoryTest {

    @Test
    fun observeConversations_emitsEmpty_initially_for_all_filters() = runBlocking {
        val repo = FakeConversationRepository()
        assertEquals(emptyList<Any>(), repo.observeConversations(ConversationFilter.All).first())
        assertEquals(emptyList<Any>(), repo.observeConversations(ConversationFilter.Channels).first())
        assertEquals(emptyList<Any>(), repo.observeConversations(ConversationFilter.Discussions).first())
    }

    @Test
    fun observeMessages_emitsEmpty() = runBlocking {
        val repo = FakeConversationRepository()
        assertEquals(emptyList<Any>(), repo.observeMessages("any-id").first())
    }

    @Test
    fun createDiscussion_appearsIn_observeConversations_All() = runBlocking {
        val repo = FakeConversationRepository()
        val created = repo.createDiscussion()
        val all = repo.observeConversations(ConversationFilter.All).first()
        assertEquals(listOf(created), all)
    }

    @Test
    fun createDiscussion_isUnpromoted_andHasNullName() = runBlocking {
        val repo = FakeConversationRepository()
        val c = repo.createDiscussion()
        assertEquals(false, c.isPromoted)
        assertNull(c.name)
    }

    @Test
    fun createDiscussion_appearsIn_Discussions_filter_butNotIn_Channels() = runBlocking {
        val repo = FakeConversationRepository()
        repo.createDiscussion()
        assertEquals(1, repo.observeConversations(ConversationFilter.Discussions).first().size)
        assertEquals(0, repo.observeConversations(ConversationFilter.Channels).first().size)
    }

    @Test
    fun promote_flipsIsPromoted_andApplies_name_and_workspace() = runBlocking {
        val repo = FakeConversationRepository()
        val created = repo.createDiscussion()
        val promoted = repo.promote(created.id, name = "my-channel", workspace = "/work")
        assertEquals(true, promoted.isPromoted)
        assertEquals("my-channel", promoted.name)
        assertEquals("/work", promoted.cwd)
    }

    @Test
    fun promote_movesConversation_from_Discussions_to_Channels() = runBlocking {
        val repo = FakeConversationRepository()
        val created = repo.createDiscussion()
        repo.promote(created.id, name = "my-channel")
        assertEquals(0, repo.observeConversations(ConversationFilter.Discussions).first().size)
        assertEquals(1, repo.observeConversations(ConversationFilter.Channels).first().size)
    }

    @Test
    fun archive_removes_from_observeConversations() = runBlocking {
        val repo = FakeConversationRepository()
        val created = repo.createDiscussion()
        repo.archive(created.id)
        assertEquals(emptyList<Any>(), repo.observeConversations(ConversationFilter.All).first())
    }

    @Test
    fun rename_updates_name_and_reEmits() = runBlocking {
        val repo = FakeConversationRepository()
        val created = repo.createDiscussion()
        val renamed = repo.rename(created.id, "renamed")
        assertEquals("renamed", renamed.name)
        assertEquals(listOf(renamed), repo.observeConversations(ConversationFilter.All).first())
    }

    @Test
    fun startNewSession_returnsFreshSession_withDifferentId() = runBlocking {
        val repo = FakeConversationRepository()
        val created = repo.createDiscussion()
        val newSession = repo.startNewSession(created.id)
        assertNotEquals(created.currentSessionId, newSession.id)
        assertEquals(created.id, newSession.conversationId)
        assertNull(newSession.endedAt)
    }

    @Test
    fun changeWorkspace_returnsFreshSession_andUpdatesCwd() = runBlocking {
        val repo = FakeConversationRepository()
        val created = repo.createDiscussion(workspace = "/old")
        val newSession = repo.changeWorkspace(created.id, "/new")
        assertEquals(created.id, newSession.conversationId)
        assertNotEquals(created.currentSessionId, newSession.id)
        val current = repo.observeConversations(ConversationFilter.All).first().single()
        assertEquals("/new", current.cwd)
    }

    @Test
    fun promote_onUnknownId_throws() {
        val repo = FakeConversationRepository()
        try {
            runBlocking { repo.promote("nope", name = "x") }
            assertTrue("expected IllegalArgumentException", false)
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
