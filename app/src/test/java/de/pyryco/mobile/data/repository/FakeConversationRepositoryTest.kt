package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.SetSessionSettingsPayloadDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

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
    fun seededPyrycodeMobileChannel_boundary_isClear_withNullWorkspaceCwd() =
        runBlocking {
            val repo = FakeConversationRepository()
            val items = repo.observeMessages("seed-channel-pyrycode-mobile").first()
            val boundary =
                items.filterIsInstance<ThreadItem.SessionBoundary>().single()
            assertEquals(BoundaryReason.Clear, boundary.reason)
            assertNull(boundary.workspaceCwd)
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
    fun seededChannels_exerciseClearAndIdleEvict_withoutWorkspaceChange() =
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
    fun createChannel_isPromoted_withVerbatimName_andWorkspace() =
        runBlocking {
            val repo = FakeConversationRepository()
            val c = repo.createChannel(name = "  Weekly planning ", workspace = "/work/wp")
            assertEquals(true, c.isPromoted)
            assertEquals("  Weekly planning ", c.name)
            assertEquals("/work/wp", c.cwd)
        }

    @Test
    fun createChannel_appearsIn_Channels_filter_butNotIn_Discussions() =
        runBlocking {
            val repo = FakeConversationRepository()
            val created = repo.createChannel(name = "Weekly planning", workspace = "/work/wp")
            val channels = repo.observeConversations(ConversationFilter.Channels).first()
            assertEquals(created, channels.single { it.id == created.id })
            val discussions = repo.observeConversations(ConversationFilter.Discussions).first()
            assertTrue(discussions.none { it.id == created.id })
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
    fun setMuted_setsAndClearsTheFlagOnTheRow() =
        runBlocking {
            val repo = FakeConversationRepository()
            val created = repo.createDiscussion()

            repo.setMuted(created.id, true)
            assertTrue(
                repo
                    .observeConversations(ConversationFilter.All)
                    .first()
                    .single { it.id == created.id }
                    .muted,
            )

            repo.setMuted(created.id, false)
            assertFalse(
                repo
                    .observeConversations(ConversationFilter.All)
                    .first()
                    .single { it.id == created.id }
                    .muted,
            )
        }

    @Test
    fun setMuted_onUnknownId_throwsAndChangesNothing() =
        runBlocking {
            val repo = FakeConversationRepository()
            val before = repo.observeConversations(ConversationFilter.All).first()

            val thrown = runCatching { repo.setMuted("nope", true) }.exceptionOrNull()

            assertTrue(thrown is IllegalArgumentException)
            assertEquals(before, repo.observeConversations(ConversationFilter.All).first())
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
    fun delete_removesConversation_from_observeConversations_All() =
        runBlocking {
            val repo = FakeConversationRepository()
            val created = repo.createDiscussion()

            assertTrue(
                "newly created discussion must appear in All before delete",
                repo.observeConversations(ConversationFilter.All).first().any { it.id == created.id },
            )

            repo.delete(created.id)

            assertTrue(
                "deleted conversation must not appear in All",
                repo.observeConversations(ConversationFilter.All).first().none { it.id == created.id },
            )
        }

    @Test
    fun delete_onUnknownId_doesNotThrow() =
        runBlocking {
            val repo = FakeConversationRepository()
            repo.delete("nope")
        }

    @Test
    fun delete_isIdempotent() =
        runBlocking {
            val repo = FakeConversationRepository()
            val created = repo.createDiscussion()
            repo.delete(created.id)
            repo.delete(created.id)
            assertTrue(
                "twice-deleted conversation must be absent from All",
                repo.observeConversations(ConversationFilter.All).first().none { it.id == created.id },
            )
        }

    @Test
    fun delete_causes_observeMessages_toReEmitEmpty() =
        runBlocking {
            val repo = FakeConversationRepository()
            val seedId = "seed-channel-pyrycode-mobile"
            assertTrue(
                "seed channel must have messages before delete",
                repo.observeMessages(seedId).first().isNotEmpty(),
            )

            repo.delete(seedId)

            assertEquals(
                emptyList<ThreadItem>(),
                repo.observeMessages(seedId).first(),
            )
        }

    @Test
    fun delete_causes_observeLastMessage_toReEmitNull() =
        runBlocking {
            val repo = FakeConversationRepository()
            val seedId = "seed-channel-pyrycode-mobile"
            assertNotNull(
                "seed channel must have a last message before delete",
                repo.observeLastMessage(seedId).first(),
            )

            repo.delete(seedId)

            assertNull(repo.observeLastMessage(seedId).first())
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

    // #823: demo mode holds the three stored states per conversation and reports no running session.
    @Test
    fun systemPrompt_roundTripsTheThreeStoredStates() =
        runBlocking {
            val repo = FakeConversationRepository()
            val other = repo.createDiscussion()

            assertEquals(SystemPromptReading(null, SessionPromptStatus.NoSession), repo.requestSystemPrompt(SEED_ID))
            repo.setSystemPrompt(SEED_ID, "")
            assertEquals(SystemPromptReading("", SessionPromptStatus.NoSession), repo.requestSystemPrompt(SEED_ID))
            repo.setSystemPrompt(SEED_ID, " text\n")
            assertEquals(" text\n", repo.requestSystemPrompt(SEED_ID).systemPrompt)
            assertNull(repo.requestSystemPrompt(other.id).systemPrompt)
            repo.setSystemPrompt(SEED_ID, null)
            assertNull(repo.requestSystemPrompt(SEED_ID).systemPrompt)
        }

    // Unknown ids read like a hosted conversation holding nothing (the daemon's posture), but a write to
    // one is refused, as is a value over the byte limit — and neither refusal stores anything.
    @Test
    fun systemPrompt_refusesUnknownConversationAndOverLimitValue() =
        runBlocking {
            val repo = FakeConversationRepository()

            assertEquals(SystemPromptReading(null, SessionPromptStatus.NoSession), repo.requestSystemPrompt("nope"))
            assertTrue(runCatching { repo.setSystemPrompt("nope", "x") }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { repo.setSystemPrompt(SEED_ID, "€".repeat(2731)) }.exceptionOrNull() is IllegalArgumentException)
            assertNull(repo.requestSystemPrompt(SEED_ID).systemPrompt)
            repo.setSystemPrompt(SEED_ID, "€".repeat(2730) + "ab")
            assertEquals(8192, SystemPromptLimit.utf8Bytes(repo.requestSystemPrompt(SEED_ID).systemPrompt.orEmpty()))
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
    fun requestScreenSnapshot_knownId_returnsCannedTextVerbatim() =
        runBlocking {
            val repo = FakeConversationRepository()
            assertEquals(FAKE_SCREEN_SNAPSHOT_TEXT, repo.requestScreenSnapshot("seed-discussion-a"))
        }

    @Test
    fun requestScreenSnapshot_onUnknownId_throws() {
        val repo = FakeConversationRepository()
        try {
            runBlocking { repo.requestScreenSnapshot("nope") }
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

    // --- Auto-archive idle discussions (#267) ---

    @Test
    fun shouldArchive_boundaryMatrix_respectsInclusive30DayThreshold() {
        val now = Instant.parse("2026-06-01T12:00:00Z")

        assertFalse(
            "promoted channel idle 365 days must never auto-archive",
            shouldArchive(conv(promoted = true, lastUsedAt = now - 365.days), now),
        )
        assertTrue(
            "discussion idle exactly the threshold must archive (inclusive >=)",
            shouldArchive(conv(promoted = false, lastUsedAt = now - ARCHIVE_IDLE_THRESHOLD), now),
        )
        assertFalse(
            "discussion idle 29d23h (just under) must stay",
            shouldArchive(conv(promoted = false, lastUsedAt = now - (29.days + 23.hours)), now),
        )
        assertTrue(
            "discussion idle just over the threshold must archive (locks the >= direction)",
            shouldArchive(conv(promoted = false, lastUsedAt = now - (ARCHIVE_IDLE_THRESHOLD + 1.minutes)), now),
        )
    }

    @Test
    fun sweep_autoArchivesIdleDiscussions_convergingOnArchivedFilter() =
        runBlocking {
            val repo = FakeConversationRepository()
            assertEquals(2, repo.observeConversations(ConversationFilter.Discussions).first().size)
            assertEquals(1, repo.observeConversations(ConversationFilter.Archived).first().size)
            assertEquals(3, repo.observeConversations(ConversationFilter.Channels).first().size)
            assertEquals(6, repo.observeConversations(ConversationFilter.All).first().size)

            val n = repo.sweep(Instant.parse("2026-07-01T00:00:00Z"))
            assertEquals("only the two live discussions are newly archived", 2, n)

            val discussions = repo.observeConversations(ConversationFilter.Discussions).first()
            assertEquals("all idle discussions left Discussions", 0, discussions.size)

            val archived = repo.observeConversations(ConversationFilter.Archived).first()
            assertEquals(3, archived.size)
            assertTrue("seed-discussion-a is now archived", archived.any { it.id == "seed-discussion-a" })
            assertTrue("seed-discussion-b is now archived", archived.any { it.id == "seed-discussion-b" })

            assertEquals(
                "promoted channels untouched despite being >30 days idle",
                3,
                repo.observeConversations(ConversationFilter.Channels).first().size,
            )

            val all = repo.observeConversations(ConversationFilter.All).first()
            assertEquals("retention — nothing deleted", 6, all.size)
            assertTrue(all.any { it.id == "seed-discussion-a" })
            assertTrue(all.any { it.id == "seed-discussion-b" })
        }

    @Test
    fun sweep_isIdempotent_secondSweepArchivesNothing() =
        runBlocking {
            val repo = FakeConversationRepository()
            val referenceTime = Instant.parse("2026-07-01T00:00:00Z")

            assertEquals(2, repo.sweep(referenceTime))
            assertEquals("a second sweep at the same reference time archives nothing", 0, repo.sweep(referenceTime))
            assertEquals(
                "Archived stays at 3 after a redundant sweep (no duplicate membership)",
                3,
                repo.observeConversations(ConversationFilter.Archived).first().size,
            )
        }

    @Test
    fun sweep_autoArchivedDiscussion_isRestorableViaUnarchive() =
        runBlocking {
            val repo = FakeConversationRepository()
            repo.sweep(Instant.parse("2026-07-01T00:00:00Z"))

            repo.unarchive("seed-discussion-a")

            assertTrue(
                "auto-archived discussion must be restorable to Discussions",
                repo.observeConversations(ConversationFilter.Discussions).first().any { it.id == "seed-discussion-a" },
            )
            assertTrue(
                "restored discussion must leave Archived",
                repo.observeConversations(ConversationFilter.Archived).first().none { it.id == "seed-discussion-a" },
            )
        }

    // AC #4: the fake records each setSessionSettings request verbatim (four fields, incl. an omitted
    // effort as null) so #544's ViewModel tests can assert send-on-change; the call returns normally.
    @Test
    fun setSessionSettings_recordsRequestVerbatim() =
        runBlocking {
            val repo = FakeConversationRepository()

            repo.setSessionSettings("s1", model = "opus", effort = null, yolo = true)
            repo.setSessionSettings("s1", permissionMode = "acceptEdits")

            assertEquals(
                listOf(
                    SetSessionSettingsPayloadDto("s1", model = "opus", effort = null, yolo = true),
                    SetSessionSettingsPayloadDto("s1", permissionMode = "acceptEdits"),
                ),
                repo.setSessionSettingsCalls,
            )
        }

    // ---- requestHistory (#623): an honest backward walk over the fake's own seeded messages -------

    // AC #1/#2: the first ask (empty cursor) returns the NEWEST entries first, with durable ids that
    // are the entries' positions in the log — the fake pages over real data rather than throwing.
    @Test
    fun requestHistory_firstPage_returnsNewestFirstWithDurableIds() =
        runBlocking {
            val repo = seededHistoryRepo(count = 30)

            val page = repo.requestHistory(SEED_ID, limit = 3)

            assertEquals(listOf(30L, 29L, 28L), page.entries.map { it.id })
            assertEquals(listOf("m30", "m29", "m28"), page.entries.map { entryMessageId(it) })
            assertFalse(page.atStart)
        }

    // AC #3: the full walk visits every entry exactly once, in descending id order, and terminates on
    // `atStart` — never on an empty page. 30 entries over a page size of 7 means the last page is
    // short (2 entries) and carries the terminal flag with it.
    @Test
    fun requestHistory_walk_visitsEveryEntryOnceAndTerminatesOnAtStart() =
        runBlocking {
            val repo = seededHistoryRepo(count = 30)
            val visited = mutableListOf<Long>()
            var cursor = ""
            var pages = 0

            while (true) {
                val page = repo.requestHistory(SEED_ID, cursor = cursor, limit = 7)
                visited += page.entries.map { it.id }
                pages++
                if (page.atStart) {
                    assertEquals("a terminal page carries no cursor", "", page.cursor)
                    break
                }
                cursor = page.cursor
                assertTrue("a non-terminal page must hand back a usable cursor", cursor.isNotEmpty())
            }

            assertEquals((30L downTo 1L).toList(), visited)
            assertEquals(5, pages)
        }

    // AC #3, the boundary a client that stopped on an empty page would get wrong: a page that fills
    // EXACTLY at the log's first entry reports `atStart` false with a usable cursor, and only the call
    // after it returns no entries with `atStart` true. A short page is never the end-of-log signal.
    @Test
    fun requestHistory_exactFillAtFirstEntry_defersAtStartToTheNextCall() =
        runBlocking {
            val repo = seededHistoryRepo(count = 10)

            val filled = repo.requestHistory(SEED_ID, limit = 10)
            assertEquals(10, filled.entries.size)
            assertFalse("an exact fill has not yet learned the log ended", filled.atStart)
            assertTrue(filled.cursor.isNotEmpty())

            val terminal = repo.requestHistory(SEED_ID, cursor = filled.cursor, limit = 10)
            assertEquals(emptyList<HistoryEntry>(), terminal.entries)
            assertTrue(terminal.atStart)
            assertEquals("", terminal.cursor)
        }

    // A non-positive limit asks the fake to choose, exactly as `0` asks the daemon to choose — it
    // never means "zero entries".
    @Test
    fun requestHistory_nonPositiveLimit_usesTheFakesOwnPageSize() =
        runBlocking {
            val repo = seededHistoryRepo(count = 40)

            assertEquals(FakeConversationRepository.FAKE_HISTORY_PAGE_SIZE, repo.requestHistory(SEED_ID).entries.size)
            assertEquals(
                FakeConversationRepository.FAKE_HISTORY_PAGE_SIZE,
                repo.requestHistory(SEED_ID, limit = -1).entries.size,
            )
        }

    // A conversation with no messages is the third page shape: no entries, empty cursor, at_start.
    @Test
    fun requestHistory_emptyLog_isTheTerminalShape() =
        runBlocking {
            val repo = seededHistoryRepo(count = 0)

            val page = repo.requestHistory(SEED_ID)

            assertEquals(emptyList<HistoryEntry>(), page.entries)
            assertEquals("", page.cursor)
            assertTrue(page.atStart)
        }

    // An unknown conversation throws IllegalArgumentException — the same type the remote surfaces for
    // the daemon's `conversation.not_found`, so a consumer handles one type either way.
    @Test
    fun requestHistory_unknownConversation_throws() =
        runBlocking {
            val repo = FakeConversationRepository()

            assertTrue(
                runCatching { repo.requestHistory("does-not-exist") }.exceptionOrNull() is IllegalArgumentException,
            )
        }

    /** A repo whose [SEED_ID] conversation holds exactly [count] messages, one minute apart. */
    private fun seededHistoryRepo(count: Int): FakeConversationRepository =
        FakeConversationRepository(
            initialMessages =
                mapOf(
                    SEED_ID to
                        (1..count).map { n ->
                            Message(
                                id = "m$n",
                                sessionId = "s1",
                                role = if (n % 2 == 0) Role.Assistant else Role.User,
                                content = "line $n",
                                timestamp = Instant.parse("2026-05-10T10:00:00Z") + n.minutes,
                                isStreaming = false,
                            )
                        },
                ),
        )

    /** The `message_id` inside a fake history entry's `message`-shaped payload. */
    private fun entryMessageId(entry: HistoryEntry): String =
        entry.payload.jsonObject
            .getValue("message_id")
            .jsonPrimitive
            .content

    private fun conv(
        promoted: Boolean,
        lastUsedAt: Instant,
    ): Conversation =
        Conversation(
            id = "test-conv",
            name = null,
            cwd = DEFAULT_SCRATCH_CWD,
            currentSessionId = "test-session",
            sessionHistory = listOf("test-session"),
            isPromoted = promoted,
            lastUsedAt = lastUsedAt,
        )

    // ---- #590: settings readings are seeded, never manufactured ---------------------------------

    // AC #3/#4: an unseeded conversation reads UNAVAILABLE. The Fake invents no posture and no effort,
    // because a default here would be exactly the lie the read exists to prevent.
    @Test
    fun observeSessionSettings_unseeded_isUnavailable() =
        runBlocking {
            val repo = FakeConversationRepository()

            assertNull(repo.observeSessionSettings(SEED_ID).first())
        }

    @Test
    fun observeSessionSettings_seeded_emitsTheReadingForThatConversationOnly() =
        runBlocking {
            val repo = FakeConversationRepository()
            val reading =
                SessionSettings(
                    sessionId = "sess-a",
                    model = "opus",
                    effort = "high",
                    effectiveEffort = EffectiveEffort.NotReported,
                    permissionMode = "",
                    yolo = false,
                    usedTokens = 0L,
                    windowTokens = 0L,
                )

            repo.setSessionSettingsReading(SEED_ID, reading)

            assertEquals(reading, repo.observeSessionSettings(SEED_ID).first())
            assertNull(repo.observeSessionSettings("another-conversation").first())
        }

    // The seam #649's ViewModel test asserts a settled write against.
    @Test
    fun refreshSessionSettings_recordsTheAsk() {
        val repo = FakeConversationRepository()

        repo.refreshSessionSettings(SEED_ID)
        repo.refreshSessionSettings(SEED_ID)

        assertEquals(listOf(SEED_ID, SEED_ID), repo.sessionSettingsRefreshes)
    }

    // ---- #791: model menus are seeded, never manufactured ---------------------------------------

    // AC #3: an unseeded conversation reads UNAVAILABLE. The Fake substitutes no `Model` entries and
    // no `Effort` levels — the device enum is exactly what this reading exists to replace.
    @Test
    fun observeModelMenu_unseeded_isUnavailable() =
        runBlocking {
            val repo = FakeConversationRepository()

            assertNull(repo.observeModelMenu(SEED_ID).first())
        }

    // The seam #649's UI work drives: a seeded menu reads back verbatim for that conversation alone.
    @Test
    fun observeModelMenu_seeded_emitsTheMenuForThatConversationOnly() =
        runBlocking {
            val repo = FakeConversationRepository()
            val menu =
                ModelMenu(
                    rows =
                        listOf(
                            ModelMenuRow("claude-sonnet-5", "sonnet", "Sonnet 5", listOf("low", "high"), true, null),
                            ModelMenuRow("claude-sonnet-5", "default", "Default", emptyList(), false, listOf("value")),
                        ),
                    droppedModels = 37,
                )

            repo.setModelMenu(SEED_ID, menu)

            assertEquals(menu, repo.observeModelMenu(SEED_ID).first())
            assertNull(repo.observeModelMenu("another-conversation").first())
        }

    // Clearing returns the conversation to unavailable, so a preview can exercise the resting state.
    @Test
    fun setModelMenu_null_clearsBackToUnavailable() =
        runBlocking {
            val repo = FakeConversationRepository()
            repo.setModelMenu(SEED_ID, ModelMenu(rows = emptyList(), droppedModels = 0))
            assertEquals(ModelMenu(emptyList(), 0), repo.observeModelMenu(SEED_ID).first())

            repo.setModelMenu(SEED_ID, null)

            assertNull(repo.observeModelMenu(SEED_ID).first())
        }

    // ---- #882: slash-command menus are seeded, never manufactured -------------------------------

    // An unseeded conversation reads `null`: the Fake has no wire and invents no commands.
    @Test
    fun observeSlashCommandMenu_unseeded_isNull() =
        runBlocking {
            assertNull(FakeConversationRepository().observeSlashCommandMenu(SEED_ID).first())
        }

    // A seeded menu reads back verbatim for that conversation alone.
    @Test
    fun observeSlashCommandMenu_seeded_emitsTheMenuForThatConversationOnly() =
        runBlocking {
            val repo = FakeConversationRepository()
            val menu =
                SlashCommandMenu(
                    rows = listOf(SlashCommandMenuRow("clear", "[name]", "Start a new session", listOf("reset", "new"), null)),
                    droppedCommands = 3,
                )

            repo.setSlashCommandMenu(SEED_ID, menu)

            assertEquals(menu, repo.observeSlashCommandMenu(SEED_ID).first())
            assertNull(repo.observeSlashCommandMenu("another-conversation").first())
        }

    // Clearing returns the conversation to `null`, distinct from a seeded empty menu.
    @Test
    fun setSlashCommandMenu_null_clearsBackToNull() =
        runBlocking {
            val repo = FakeConversationRepository()
            repo.setSlashCommandMenu(SEED_ID, SlashCommandMenu(rows = emptyList(), droppedCommands = 0))
            assertEquals(SlashCommandMenu(emptyList(), 0), repo.observeSlashCommandMenu(SEED_ID).first())

            repo.setSlashCommandMenu(SEED_ID, null)

            assertNull(repo.observeSlashCommandMenu(SEED_ID).first())
        }

    private companion object {
        /** The seeded channel whose messages the history-walk tests replace wholesale. */
        const val SEED_ID = "seed-channel-personal"
    }
}
