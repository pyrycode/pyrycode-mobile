package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AttachmentUploadResult
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelMessageReplyTest {
    private val models = ViewModelStore()
    private val oldSink = RelayLog.sink

    @Before fun setup() {
        RelayLog.sink = { _, _, _ -> }
    }

    @After fun close() {
        models.clear()
        RelayLog.sink = oldSink
        Dispatchers.resetMain()
    }

    private class Repository : ConversationRepository by FakeConversationRepository() {
        val sends = mutableListOf<String>()
        val sentAttachments = mutableListOf<List<MessageAttachment>>()

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
        ): Message {
            sends += text
            return message(Role.User, text)
        }

        override suspend fun uploadAttachment(
            conversationId: String,
            bytes: ByteArray,
            filename: String,
            mimeType: String,
            onProgress: (
                Int,
                Int,
            ) -> Unit,
        ) = AttachmentUploadResult.Stored("file-id")

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
            attachments: List<MessageAttachment>,
        ): Message {
            sentAttachments += attachments
            return sendMessage(conversationId, text)
        }
    }

    private fun vm(
        store: ComposerDraftStore,
        repo: Repository = Repository(),
        host: String = "host",
        chat: String = "seed-channel-personal",
    ) = ThreadViewModel(
        SavedStateHandle(mapOf("serverId" to host, "conversationId" to chat)),
        repo,
        FakeConnectionStateSource(),
        store,
        attachmentReader = AttachmentReader { AttachmentRead.Bytes(byteArrayOf(1)) },
        ioDispatcher = StandardTestDispatcher(),
    ).also { models.put("${models.keys().size}", it) }

    @Test fun exactRoleQuotesPreserveSourceAndDraftWhitespace() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val store = ComposerDraftStore()
            val vm = vm(store)
            for (role in listOf(Role.User, Role.Assistant)) {
                for (source in listOf("Hello", "first\n\n**second**", "  \"quoted\"\n\t", "")) {
                    for (draft in listOf("", "draft", "draft\n", " \n\t")) {
                        vm.onDraftChange(draft)
                        val prefix = draft + if (draft.isNotEmpty() && !draft.endsWith('\n')) "\n" else ""
                        val expected = prefix + "${role.name}:\n\"$source\"\n"
                        assertEquals(expected, vm.replyToMessage(message(role, source)))
                        assertEquals(expected, store.draftFor("host", "seed-channel-personal"))
                    }
                }
            }
        }

    @Test fun latestStoreDraftAndRepeatedRepliesAreIsolatedWithoutYielding() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val store = ComposerDraftStore()
            val repo = Repository()
            val vm = vm(store, repo)
            val otherServer = vm(store, host = "other")
            val otherChat = vm(store, chat = "other")
            otherServer.onDraftChange("other server")
            otherChat.onDraftChange("other chat")
            store.addAttachment("host", "seed-channel-personal", "content://foreign/file", "file.txt", "text/plain", 1)
            val files = store.attachmentsFor("host", "seed-channel-personal")
            vm.onDraftChange("latest")
            assertEquals("", vm.draft.value) // Deliberately lag the derived StateFlow.
            val first = "latest\nUser:\n\"one\"\n"
            val attachedSource =
                message(Role.User, "one").copy(
                    attachments = listOf(MessageAttachment("source-file", "ignored.txt", "text/plain")),
                )
            assertEquals(first, vm.replyToMessage(attachedSource))
            assertEquals(first + "Assistant:\n\"two\"\n", vm.replyToMessage(message(Role.Assistant, "two")))
            assertEquals("other server", store.draftFor("other", "seed-channel-personal"))
            assertEquals("other chat", store.draftFor("host", "other"))
            assertEquals(files, store.attachmentsFor("host", "seed-channel-personal"))
            runCurrent()
            assertTrue(repo.sends.isEmpty())
        }

    @Test fun fullStreamingSnapshotExceedsClipboardBoundAndDoesNotFollowLaterChunks() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val store = ComposerDraftStore()
            val vm = vm(store)
            val source = "x".repeat(100_001) + "\n\"end\"  "
            val streaming = message(Role.Assistant, source).copy(isStreaming = true)
            val expected = "Assistant:\n\"$source\"\n"
            assertEquals(expected, vm.replyToMessage(streaming))
            streaming.copy(content = source + "later")
            runCurrent()
            assertEquals(expected, vm.draft.value)
        }

    @Test fun explicitSendUsesExistingTrimAndAttachmentPath() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val store = ComposerDraftStore()
            val repo = Repository()
            val vm = vm(store, repo)
            runCurrent()
            store.addAttachment("host", "seed-channel-personal", "content://foreign/file", "file.txt", "text/plain", 1)
            vm.onDraftChange(" \n")
            val quote = requireNotNull(vm.replyToMessage(message(Role.Assistant, "  **text**\n\"more\"  ")))
            assertTrue(repo.sends.isEmpty())
            vm.sendMessage(quote)
            runCurrent()
            assertEquals(listOf(quote.trim()), repo.sends)
            assertEquals(
                "file-id",
                repo.sentAttachments
                    .single()
                    .single()
                    .attachmentId,
            )
            assertEquals("", store.draftFor("host", "seed-channel-personal"))
            assertTrue(store.attachmentsFor("host", "seed-channel-personal").isEmpty())
        }

    @Test fun toolReplyLeavesDraftUntouched() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val store = ComposerDraftStore()
            val vm = vm(store)
            vm.onDraftChange("draft")
            assertNull(vm.replyToMessage(message(Role.Tool, "tool")))
            assertEquals("draft", store.draftFor("host", "seed-channel-personal"))
        }

    companion object {
        private fun message(
            role: Role,
            text: String,
        ) = Message(
            "m",
            "seed-session-personal",
            role,
            text,
            Instant.parse("2026-10-07T00:00:00Z"),
            false,
        )
    }
}
