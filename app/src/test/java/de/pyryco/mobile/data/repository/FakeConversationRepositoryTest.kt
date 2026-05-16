package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeConversationRepositoryTest {
    @Test
    fun observeConversations_emitsExpectedSeeds_initially_for_all_filters() =
        runBlocking {
            val repo = FakeConversationRepository()
            assertEquals(6, repo.observeConversations(ConversationFilter.All).first().size)
            assertEquals(3, repo.observeConversations(ConversationFilter.Channels).first().size)
            assertEquals(2, repo.observeConversations(ConversationFilter.Discussions).first().size)
            assertEquals(1, repo.observeConversations(ConversationFilter.Archived).first().size)
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
                repo.observeMessages("seed-discussion-b").first(),
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
    fun seededDiscussions_remainEmpty_exceptDiscussionA() =
        runBlocking {
            val repo = FakeConversationRepository()
            for (id in listOf("seed-discussion-b", "seed-discussion-archived")) {
                assertEquals(
                    "discussion $id must be empty",
                    emptyList<ThreadItem>(),
                    repo.observeMessages(id).first(),
                )
            }
        }

    @Test
    fun observeLastMessage_returnsNull_whenConversationHasNoMessages() =
        runBlocking {
            val repo = FakeConversationRepository()
            assertNull(repo.observeLastMessage("seed-discussion-b").first())
        }

    @Test
    fun observeLastMessage_returnsNull_whenConversationUnknown() =
        runBlocking {
            val repo = FakeConversationRepository()
            assertNull(repo.observeLastMessage("does-not-exist").first())
        }

    @Test
    fun observeLastMessage_returnsMostRecentByTimestamp_whenMessagesExist() =
        runBlocking {
            val repo = FakeConversationRepository()
            val last = repo.observeLastMessage("seed-discussion-a").first()
            assertNotNull(last)
            assertEquals(
                Instant.parse("2026-05-11T14:00:00Z"),
                last!!.timestamp,
            )
        }

    @Test
    fun seededPersonalChannel_boundary_isClear_withNullWorkspaceCwd() =
        runBlocking {
            val repo = FakeConversationRepository()
            val items = repo.observeMessages("seed-channel-personal").first()
            val boundary =
                items.filterIsInstance<ThreadItem.SessionBoundary>().single()
            assertEquals(BoundaryReason.Clear, boundary.reason)
            assertNull(boundary.workspaceCwd)
        }

    @Test
    fun seededPyrycodeMobileChannel_boundary_isWorkspaceChange_withSeededPath() =
        runBlocking {
            val repo = FakeConversationRepository()
            val items = repo.observeMessages("seed-channel-pyrycode-mobile").first()
            val boundary =
                items.filterIsInstance<ThreadItem.SessionBoundary>().single()
            assertEquals(BoundaryReason.WorkspaceChange, boundary.reason)
            assertEquals("~/Workspace/pyrycode-mobile", boundary.workspaceCwd)
        }

    @Test
    fun seededJoiPilatesChannel_boundary_isIdleEvict_withNullWorkspaceCwd() =
        runBlocking {
            val repo = FakeConversationRepository()
            val items = repo.observeMessages("seed-channel-joi-pilates").first()
            val boundary =
                items.filterIsInstance<ThreadItem.SessionBoundary>().single()
            assertEquals(BoundaryReason.IdleEvict, boundary.reason)
            assertNull(boundary.workspaceCwd)
        }

    @Test
    fun seededChannels_collectivelyExerciseAllBoundaryReasons() =
        runBlocking {
            val repo = FakeConversationRepository()
            val channelIds =
                listOf(
                    "seed-channel-personal",
                    "seed-channel-pyrycode-mobile",
                    "seed-channel-joi-pilates",
                )
            val reasons =
                channelIds
                    .map { repo.observeMessages(it).first() }
                    .flatMap { it.filterIsInstance<ThreadItem.SessionBoundary>() }
                    .map { it.reason }
                    .toSet()
            assertEquals(
                setOf(
                    BoundaryReason.Clear,
                    BoundaryReason.IdleEvict,
                    BoundaryReason.WorkspaceChange,
                ),
                reasons,
            )
        }

    @Test
    fun observeMessages_emitsSeededToolMessage_withStructuredPayload() =
        runBlocking {
            val repo = FakeConversationRepository()
            val items = repo.observeMessages("seed-channel-pyrycode-mobile").first()
            val toolMessages =
                items
                    .filterIsInstance<ThreadItem.MessageItem>()
                    .map { it.message }
                    .filter { it.role == Role.Tool }
            assertEquals("expected exactly one tool message in seed channel", 1, toolMessages.size)
            val tool = toolMessages.single()
            val payload = tool.toolCall
            assertNotNull("tool message must carry a non-null toolCall", payload)
            assertEquals("Read", payload!!.toolName)
            assertEquals(
                "app/src/main/java/de/pyryco/mobile/ui/conversations/thread/ThreadScreen.kt",
                payload.input,
            )
            assertEquals(
                """
                @Composable
                fun ThreadScreen(
                    state: ThreadUiState,
                    onEvent: (ThreadEvent) -> Unit,
                ) {
                    Scaffold(topBar = { ThreadTopBar(state, onEvent) }) { padding ->
                        MessageList(state.items, modifier = Modifier.padding(padding))
                    }
                }
                """.trimIndent(),
                payload.output,
            )
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
            assertNull(b.workspaceCwd)

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
            assertEquals(7, all.size)
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
    fun archive_movesConversation_from_Discussions_to_Archived_andRetainsInStore() =
        runBlocking {
            val repo = FakeConversationRepository()
            val created = repo.createDiscussion()

            assertTrue(
                "newly created discussion must appear in Discussions",
                repo.observeConversations(ConversationFilter.Discussions).first().any { it.id == created.id },
            )
            assertTrue(
                "newly created discussion must not appear in Archived",
                repo.observeConversations(ConversationFilter.Archived).first().none { it.id == created.id },
            )

            repo.archive(created.id)

            assertTrue(
                "archived conversation must leave Discussions",
                repo.observeConversations(ConversationFilter.Discussions).first().none { it.id == created.id },
            )
            assertTrue(
                "archived conversation must not appear in Channels",
                repo.observeConversations(ConversationFilter.Channels).first().none { it.id == created.id },
            )
            assertTrue(
                "archived conversation must appear in Archived",
                repo.observeConversations(ConversationFilter.Archived).first().any { it.id == created.id },
            )
            assertTrue(
                "archived conversation must be retained in All",
                repo.observeConversations(ConversationFilter.All).first().any { it.id == created.id },
            )
        }

    @Test
    fun seededArchivedDiscussion_appearsIn_Archived_butNotIn_Discussions() =
        runBlocking {
            val repo = FakeConversationRepository()
            val archivedId = "seed-discussion-archived"
            assertTrue(
                "seeded archived discussion must appear in Archived",
                repo.observeConversations(ConversationFilter.Archived).first().any { it.id == archivedId },
            )
            assertTrue(
                "seeded archived discussion must not appear in Discussions",
                repo.observeConversations(ConversationFilter.Discussions).first().none { it.id == archivedId },
            )
            assertTrue(
                "seeded archived discussion must be retained in All",
                repo.observeConversations(ConversationFilter.All).first().any { it.id == archivedId },
            )
        }

    @Test
    fun archive_onUnknownId_throws() {
        val repo = FakeConversationRepository()
        try {
            runBlocking { repo.archive("nope") }
            assertTrue("expected IllegalArgumentException", false)
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun archive_isIdempotent() =
        runBlocking {
            val repo = FakeConversationRepository()
            val created = repo.createDiscussion()
            repo.archive(created.id)
            repo.archive(created.id)
            val archived = repo.observeConversations(ConversationFilter.Archived).first()
            assertEquals(
                "archived conversation must appear exactly once after two archive() calls",
                1,
                archived.count { it.id == created.id },
            )
        }

    @Test
    fun unarchive_movesConversation_from_Archived_to_Discussions_andRetainsInStore() =
        runBlocking {
            val repo = FakeConversationRepository()
            val archivedId = "seed-discussion-archived"

            assertTrue(
                "seeded archived discussion must appear in Archived before unarchive",
                repo.observeConversations(ConversationFilter.Archived).first().any { it.id == archivedId },
            )
            assertTrue(
                "seeded archived discussion must not appear in Discussions before unarchive",
                repo.observeConversations(ConversationFilter.Discussions).first().none { it.id == archivedId },
            )

            repo.unarchive(archivedId)

            assertTrue(
                "unarchived conversation must leave Archived",
                repo.observeConversations(ConversationFilter.Archived).first().none { it.id == archivedId },
            )
            assertTrue(
                "unarchived discussion must appear in Discussions",
                repo.observeConversations(ConversationFilter.Discussions).first().any { it.id == archivedId },
            )
            assertTrue(
                "unarchived discussion must not appear in Channels",
                repo.observeConversations(ConversationFilter.Channels).first().none { it.id == archivedId },
            )
            assertTrue(
                "unarchived conversation must be retained in All",
                repo.observeConversations(ConversationFilter.All).first().any { it.id == archivedId },
            )
        }

    @Test
    fun unarchive_onUnknownId_throws() {
        val repo = FakeConversationRepository()
        try {
            runBlocking { repo.unarchive("nope") }
            assertTrue("expected IllegalArgumentException", false)
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun unarchive_isIdempotent() =
        runBlocking {
            val repo = FakeConversationRepository()
            val archivedId = "seed-discussion-archived"
            repo.unarchive(archivedId)
            repo.unarchive(archivedId)
            val discussions = repo.observeConversations(ConversationFilter.Discussions).first()
            assertEquals(
                "unarchived conversation must appear exactly once after two unarchive() calls",
                1,
                discussions.count { it.id == archivedId },
            )
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
            assertEquals(setOf(DEFAULT_SCRATCH_CWD), discussions.map { it.cwd }.toSet())
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

    @Test
    fun sendMessage_appendsMessageAtTailOfObserveMessages() =
        runBlocking {
            val repo = FakeConversationRepository()
            val id = "seed-discussion-a"
            val before = repo.observeMessages(id).first()
            val currentSessionId =
                repo
                    .observeConversations(ConversationFilter.Discussions)
                    .first()
                    .first { it.id == id }
                    .currentSessionId

            val sent = repo.sendMessage(id, "hello")

            val after = repo.observeMessages(id).first()
            assertEquals(before.size + 1, after.size)
            val tail = after.last()
            assertTrue("tail must be a MessageItem, was $tail", tail is ThreadItem.MessageItem)
            val message = (tail as ThreadItem.MessageItem).message
            assertEquals(sent, message)
            assertEquals("hello", message.content)
            assertEquals(Role.User, message.role)
            assertEquals(false, message.isStreaming)
            assertEquals(currentSessionId, message.sessionId)
        }

    @Test
    fun sendMessage_observeLastMessage_reEmitsTheNewMessage() =
        runBlocking {
            val repo = FakeConversationRepository()
            val id = "seed-discussion-a"
            val sent = repo.sendMessage(id, "hello")
            assertEquals(sent, repo.observeLastMessage(id).first())
        }

    @Test
    fun sendMessage_updatesLastUsedAt_andReEmitsViaObserveConversations() =
        runBlocking {
            val repo = FakeConversationRepository()
            val id = "seed-discussion-b"
            val before =
                repo
                    .observeConversations(ConversationFilter.Discussions)
                    .first()
                    .first { it.id == id }
                    .lastUsedAt

            val sent = repo.sendMessage(id, "hello")

            val discussions = repo.observeConversations(ConversationFilter.Discussions).first()
            val updated = discussions.first { it.id == id }
            assertTrue(
                "lastUsedAt must strictly increase ($before -> ${updated.lastUsedAt})",
                updated.lastUsedAt > before,
            )
            assertEquals(sent.timestamp, updated.lastUsedAt)
            assertEquals(
                "conversation must now sort first by lastUsedAt descending",
                id,
                discussions.first().id,
            )
        }

    @Test
    fun sendMessage_onUnknownId_throws() {
        val repo = FakeConversationRepository()
        try {
            runBlocking { repo.sendMessage("nope", "hi") }
            assertTrue("expected IllegalArgumentException", false)
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun recentWorkspaces_dedupes_repeatedCwds() =
        runBlocking {
            val repo = FakeConversationRepository()
            val initialSize = repo.recentWorkspaces().first().size

            repo.promote("seed-discussion-a", name = "a", workspace = "~/Workspace/foo")
            repo.changeWorkspace("seed-channel-personal", "~/Workspace/foo")

            val recents = repo.recentWorkspaces().first()
            assertEquals(
                "expected ~/Workspace/foo exactly once",
                1,
                recents.count { it == "~/Workspace/foo" },
            )
            assertEquals("~/Workspace/foo", recents.first())
            assertEquals(
                "list size grew by exactly one (foo added, not duplicated)",
                initialSize + 1,
                recents.size,
            )
        }

    @Test
    fun recentWorkspaces_ordersByMostRecentWrite() =
        runBlocking {
            val repo = FakeConversationRepository()

            assertEquals(
                listOf(
                    "~/Workspace/pyrycode-mobile",
                    "~/Workspace/joi-pilates",
                    "~/Workspace/personal",
                ),
                repo.recentWorkspaces().first(),
            )

            repo.changeWorkspace("seed-channel-personal", "~/Workspace/personal")
            assertEquals(
                "~/Workspace/personal",
                repo.recentWorkspaces().first().first(),
            )

            repo.createDiscussion(workspace = "~/Workspace/new")
            val afterCreate = repo.recentWorkspaces().first()
            assertEquals("~/Workspace/new", afterCreate[0])
            assertEquals("~/Workspace/personal", afterCreate[1])
        }

    @Test
    fun recentWorkspaces_excludesEmptyStringAndDefaultScratch() =
        runBlocking {
            val repo = FakeConversationRepository()

            assertTrue(
                "initial recents must not contain empty string",
                repo.recentWorkspaces().first().none { it.isEmpty() },
            )
            assertTrue(
                "initial recents must not contain DEFAULT_SCRATCH_CWD",
                repo.recentWorkspaces().first().none { it == DEFAULT_SCRATCH_CWD },
            )

            repo.createDiscussion(workspace = null)
            repo.createDiscussion(workspace = DEFAULT_SCRATCH_CWD)

            val recents = repo.recentWorkspaces().first()
            assertTrue(
                "recents must not contain empty string after null-workspace createDiscussion",
                recents.none { it.isEmpty() },
            )
            assertTrue(
                "recents must not contain DEFAULT_SCRATCH_CWD after scratch-workspace createDiscussion",
                recents.none { it == DEFAULT_SCRATCH_CWD },
            )
        }

    @Test
    fun createWorkspaceFolder_appearsAtPositionZeroOfRecents() =
        runBlocking {
            val repo = FakeConversationRepository()
            val initial = repo.recentWorkspaces().first()

            val path = repo.createWorkspaceFolder("scratch-1")

            assertEquals("pyry-workspace/scratch-1", path)
            val updated = repo.recentWorkspaces().first()
            assertEquals("pyry-workspace/scratch-1", updated.first())
            assertEquals(
                "list size grew by exactly one (path is new — no dedup collapse)",
                initial.size + 1,
                updated.size,
            )
        }

    @Test
    fun createWorkspaceFolder_blankName_throwsIllegalArgumentException() {
        val repo = FakeConversationRepository()
        try {
            runBlocking { repo.createWorkspaceFolder("  ") }
            assertTrue("expected IllegalArgumentException", false)
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
