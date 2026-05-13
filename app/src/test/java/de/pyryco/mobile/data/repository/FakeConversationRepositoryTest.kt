package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.DefaultScratchCwd
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeConversationRepositoryTest {
    @Test
    fun observeConversations_emitsExpectedSeeds_initially_for_all_filters() =
        runBlocking {
            val repo = FakeConversationRepository()
            assertEquals(5, repo.observeConversations(ConversationFilter.All).first().size)
            assertEquals(3, repo.observeConversations(ConversationFilter.Channels).first().size)
            assertEquals(2, repo.observeConversations(ConversationFilter.Discussions).first().size)
        }

    @Test
    fun observeMessages_unknownConversation_emitsEmpty() =
        runBlocking {
            val repo = FakeConversationRepository()
            assertEquals(emptyList<ThreadItem>(), repo.observeMessages("any-id").first())
        }

    @Test
    fun observeMessages_knownConversationWithNoMessages_emitsEmpty() =
        runBlocking {
            val repo = FakeConversationRepository()
            assertEquals(
                emptyList<ThreadItem>(),
                repo.observeMessages("seed-discussion-a").first(),
            )
        }

    @Test
    fun seededChannels_emitExactlyOneBoundary_betweenTwoSessions() =
        runBlocking {
            val repo = FakeConversationRepository()
            val channelIds =
                listOf(
                    "seed-channel-personal",
                    "seed-channel-pyrycode-mobile",
                    "seed-channel-joi-pilates",
                )
            for (id in channelIds) {
                val items = repo.observeMessages(id).first()
                val boundaries = items.filterIsInstance<ThreadItem.SessionBoundary>()
                assertEquals("expected 1 boundary in $id", 1, boundaries.size)
            }
        }

    @Test
    fun seededChannels_messagesAreChronologicallyOrdered() =
        runBlocking {
            val repo = FakeConversationRepository()
            val channelIds =
                listOf(
                    "seed-channel-personal",
                    "seed-channel-pyrycode-mobile",
                    "seed-channel-joi-pilates",
                )
            for (id in channelIds) {
                val items = repo.observeMessages(id).first()
                val timestamps =
                    items
                        .filterIsInstance<ThreadItem.MessageItem>()
                        .map { it.message.timestamp }
                assertEquals("messages in $id must be chronological", timestamps.sorted(), timestamps)
                assertTrue("expected messages in $id", timestamps.isNotEmpty())
            }
        }

    @Test
    fun seededChannels_haveTwoSessionsInHistory_endingWithCurrentSessionId() =
        runBlocking {
            val repo = FakeConversationRepository()
            val channels = repo.observeConversations(ConversationFilter.Channels).first()
            for (channel in channels) {
                assertEquals(
                    "sessionHistory size for ${channel.id}",
                    2,
                    channel.sessionHistory.size,
                )
                assertEquals(
                    "sessionHistory has duplicates in ${channel.id}",
                    2,
                    channel.sessionHistory.toSet().size,
                )
                assertEquals(
                    "currentSessionId must be last in sessionHistory for ${channel.id}",
                    channel.currentSessionId,
                    channel.sessionHistory.last(),
                )
            }
        }

    @Test
    fun seededDiscussions_remainEmpty() =
        runBlocking {
            val repo = FakeConversationRepository()
            for (id in listOf("seed-discussion-a", "seed-discussion-b")) {
                assertEquals(
                    "discussion $id must be empty",
                    emptyList<ThreadItem>(),
                    repo.observeMessages(id).first(),
                )
            }
        }

    @Test
    fun observeMessages_messagesAcrossTwoSessions_emitsExactlyOneBoundary() =
        runBlocking {
            val sessionA = "session-a"
            val sessionB = "session-b"
            val messages =
                listOf(
                    Message("m1", sessionA, Role.User, "hi", Instant.parse("2026-05-10T10:00:00Z"), false),
                    Message("m2", sessionA, Role.Assistant, "hello", Instant.parse("2026-05-10T10:01:00Z"), false),
                    Message("m3", sessionB, Role.User, "again", Instant.parse("2026-05-10T11:00:00Z"), false),
                    Message("m4", sessionB, Role.Assistant, "back", Instant.parse("2026-05-10T11:01:00Z"), false),
                )
            val repo =
                FakeConversationRepository(
                    initialMessages = mapOf("seed-channel-personal" to messages),
                )

            val items = repo.observeMessages("seed-channel-personal").first()

            assertEquals(5, items.size)

            val boundaries = items.filterIsInstance<ThreadItem.SessionBoundary>()
            assertEquals(1, boundaries.size)
            val b = boundaries.single()
            assertEquals(sessionA, b.previousSessionId)
            assertEquals(sessionB, b.newSessionId)
            assertEquals(Instant.parse("2026-05-10T11:00:00Z"), b.occurredAt)
            assertEquals(BoundaryReason.Clear, b.reason)

            val boundaryIndex = items.indexOfFirst { it is ThreadItem.SessionBoundary }
            assertEquals(ThreadItem.MessageItem(messages[1]), items[boundaryIndex - 1])
            assertEquals(ThreadItem.MessageItem(messages[2]), items[boundaryIndex + 1])

            val messageItems = items.filterIsInstance<ThreadItem.MessageItem>()
            assertEquals(messages, messageItems.map { it.message })
        }

    @Test
    fun createDiscussion_appearsIn_observeConversations_All() =
        runBlocking {
            val repo = FakeConversationRepository()
            val created = repo.createDiscussion()
            val all = repo.observeConversations(ConversationFilter.All).first()
            assertEquals(6, all.size)
            assertTrue(all.any { it.id == created.id })
        }

    @Test
    fun createDiscussion_isUnpromoted_andHasNullName() =
        runBlocking {
            val repo = FakeConversationRepository()
            val c = repo.createDiscussion()
            assertEquals(false, c.isPromoted)
            assertNull(c.name)
        }

    @Test
    fun createDiscussion_appearsIn_Discussions_filter_butNotIn_Channels() =
        runBlocking {
            val repo = FakeConversationRepository()
            val created = repo.createDiscussion()
            assertEquals(3, repo.observeConversations(ConversationFilter.Discussions).first().size)
            val channels = repo.observeConversations(ConversationFilter.Channels).first()
            assertEquals(3, channels.size)
            assertTrue(channels.none { it.id == created.id })
        }

    @Test
    fun promote_flipsIsPromoted_andApplies_name_and_workspace() =
        runBlocking {
            val repo = FakeConversationRepository()
            val created = repo.createDiscussion()
            val promoted = repo.promote(created.id, name = "my-channel", workspace = "/work")
            assertEquals(true, promoted.isPromoted)
            assertEquals("my-channel", promoted.name)
            assertEquals("/work", promoted.cwd)
        }

    @Test
    fun promote_movesConversation_from_Discussions_to_Channels() =
        runBlocking {
            val repo = FakeConversationRepository()
            val created = repo.createDiscussion()
            repo.promote(created.id, name = "my-channel")
            assertEquals(2, repo.observeConversations(ConversationFilter.Discussions).first().size)
            val channels = repo.observeConversations(ConversationFilter.Channels).first()
            assertEquals(4, channels.size)
            assertTrue(channels.any { it.id == created.id })
        }

    @Test
    fun archive_removes_from_observeConversations() =
        runBlocking {
            val repo = FakeConversationRepository()
            val created = repo.createDiscussion()
            repo.archive(created.id)
            assertTrue(repo.observeConversations(ConversationFilter.All).first().none { it.id == created.id })
        }

    @Test
    fun rename_updates_name_and_reEmits() =
        runBlocking {
            val repo = FakeConversationRepository()
            val created = repo.createDiscussion()
            val renamed = repo.rename(created.id, "renamed")
            assertEquals("renamed", renamed.name)
            val found = repo.observeConversations(ConversationFilter.All).first().first { it.id == created.id }
            assertEquals("renamed", found.name)
        }

    @Test
    fun startNewSession_returnsFreshSession_withDifferentId() =
        runBlocking {
            val repo = FakeConversationRepository()
            val created = repo.createDiscussion()
            val newSession = repo.startNewSession(created.id)
            assertNotEquals(created.currentSessionId, newSession.id)
            assertEquals(created.id, newSession.conversationId)
            assertNull(newSession.endedAt)
        }

    @Test
    fun changeWorkspace_returnsFreshSession_andUpdatesCwd() =
        runBlocking {
            val repo = FakeConversationRepository()
            val created = repo.createDiscussion(workspace = "/old")
            val newSession = repo.changeWorkspace(created.id, "/new")
            assertEquals(created.id, newSession.conversationId)
            assertNotEquals(created.currentSessionId, newSession.id)
            val current = repo.observeConversations(ConversationFilter.All).first().first { it.id == created.id }
            assertEquals("/new", current.cwd)
        }

    @Test
    fun observeConversations_Channels_emitsThreeSeededChannels_orderedByLastUsedAtDescending() =
        runBlocking {
            val repo = FakeConversationRepository()
            val channels = repo.observeConversations(ConversationFilter.Channels).first()

            assertEquals(3, channels.size)
            assertEquals(
                listOf("Pyrycode Mobile", "Joi Pilates", "Personal"),
                channels.map { it.name },
            )
            assertEquals(3, channels.map { it.cwd }.toSet().size)
            val timestamps = channels.map { it.lastUsedAt }
            assertEquals(timestamps.sortedDescending(), timestamps)
            assertEquals(3, timestamps.toSet().size)
            assertTrue(channels.all { it.currentSessionId.isNotBlank() })
            assertTrue(channels.all { it.isPromoted })
        }

    @Test
    fun observeConversations_Discussions_emitsTwoSeededDiscussions_orderedByLastUsedAtDescending() =
        runBlocking {
            val repo = FakeConversationRepository()
            val discussions = repo.observeConversations(ConversationFilter.Discussions).first()

            assertEquals(2, discussions.size)
            assertTrue(discussions.all { it.name == null })
            assertTrue(discussions.all { !it.isPromoted })
            assertEquals(setOf(DefaultScratchCwd), discussions.map { it.cwd }.toSet())
            assertTrue(discussions.all { it.currentSessionId.isNotBlank() })
            assertEquals(2, discussions.map { it.currentSessionId }.toSet().size)
            val timestamps = discussions.map { it.lastUsedAt }
            assertEquals(timestamps.sortedDescending(), timestamps)
            assertEquals(2, timestamps.toSet().size)
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
