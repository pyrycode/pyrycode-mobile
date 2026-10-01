package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.MessageAttachment
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AttachmentUploadResult
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.SessionCapabilities
import de.pyryco.mobile.data.repository.SessionSettings
import de.pyryco.mobile.data.repository.SlashCommandMenu
import de.pyryco.mobile.data.repository.SlashCommandMenuRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The composer's Actions control (#884) at the ViewModel: a command row sends its client-owned command
 * through the composer's guarded send, leaves the typed draft alone, and refuses a command the published
 * menu (#882) proves absent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelComposerActionsTest {
    private val logs = mutableListOf<String>()
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
    }

    private class RecordingRepo(
        val fake: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by fake {
        val sent = mutableListOf<Pair<String, String>>()
        val sentWithFiles = mutableListOf<Pair<String, List<String>>>() // text to attachment ids
        val uploads = mutableListOf<String>()
        var failure: Throwable? = null
        var beforeUpload: suspend () -> Unit = {}

        override suspend fun uploadAttachment(
            conversationId: String,
            bytes: ByteArray,
            filename: String,
            mimeType: String,
            onProgress: (sentChunks: Int, totalChunks: Int) -> Unit,
        ): AttachmentUploadResult {
            beforeUpload()
            uploads += filename
            return AttachmentUploadResult.Stored("id-$filename")
        }

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
            attachments: List<MessageAttachment>,
        ): Message {
            sentWithFiles += text to attachments.map { it.attachmentId }
            return fake.sendMessage(conversationId, text)
        }

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
        ): Message {
            sent += conversationId to text
            failure?.let { throw it }
            return fake.sendMessage(conversationId, text)
        }
    }

    private val draftStore = ComposerDraftStore()

    private fun TestScope.collectedVm(repo: RecordingRepo): ThreadViewModel {
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("conversationId" to CONV, "serverId" to SERVER)),
                repo,
                FakeConnectionStateSource(),
                draftStore,
                attachmentReader = { AttachmentRead.Bytes("bytes-of-$it".toByteArray()) },
            )
        backgroundScope.launch { vm.state.collect {} }
        return vm
    }

    private fun ThreadViewModel.attach(name: String) = addAttachment("content://docs/$name", name, "text/plain", 5L)

    private fun row(
        name: String,
        aliases: List<String> = emptyList(),
    ) = SlashCommandMenuRow(name = name, argumentHint = "", description = "", aliases = aliases, truncatedFields = null)

    @Test
    fun compact_sendsItsCommandToThisConversation_andLeavesTheDraftAlone() =
        runTest {
            val repo = RecordingRepo()
            val vm = collectedVm(repo)
            vm.onDraftChange("half-typed")

            vm.onComposerCommand(ComposerAction.CompactSession)
            vm.onComposerCommand(ComposerAction.KnowledgeCapture)

            assertEquals(listOf(CONV to "/compact", CONV to "/knowledge-capture"), repo.sent)
            assertEquals("half-typed", draftStore.draftFor(SERVER, CONV))
            assertTrue(logs.contains("event=composer_action action=compact outcome=sent"))
        }

    @Test
    fun aDraftEqualToTheCommand_isNotCleared() =
        runTest {
            val repo = RecordingRepo()
            val vm = collectedVm(repo)
            vm.onDraftChange("/compact")

            vm.onComposerCommand(ComposerAction.CompactSession)

            assertEquals(listOf(CONV to "/compact"), repo.sent)
            assertEquals("/compact", draftStore.draftFor(SERVER, CONV))
        }

    @Test
    fun aFailedSend_isHandledLikeAComposerSend_withoutCrashingOrTouchingTheDraft() =
        runTest {
            val uncaught = mutableListOf<Throwable>()
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught += e }
            try {
                val repo = RecordingRepo()
                val vm = collectedVm(repo)
                vm.onDraftChange("keep me")

                repo.failure = IllegalStateException("not connected")
                vm.onComposerCommand(ComposerAction.CompactSession)
                repo.failure = RelayErrorException("send.failed", retryable = false, message = "no")
                vm.onComposerCommand(ComposerAction.KnowledgeCapture)

                assertEquals(2, repo.sent.size)
                assertEquals("keep me", draftStore.draftFor(SERVER, CONV))
                assertTrue("a failed send must be caught: $uncaught", uncaught.isEmpty())
                assertFalse(logs.any { it.contains("outcome=sent") })
            } finally {
                Thread.setDefaultUncaughtExceptionHandler(previous)
            }
        }

    @Test
    fun aCompleteMenuWithoutCompact_marksItAbsent_andRefusesTheSend() =
        runTest {
            val repo = RecordingRepo()
            val vm = collectedVm(repo)

            repo.fake.setSlashCommandMenu(CONV, SlashCommandMenu(rows = listOf(row("knowledge-capture")), droppedCommands = 0))
            advanceUntilIdle()

            assertEquals(setOf(ComposerAction.CompactSession), vm.state.value.absentActions)
            vm.onComposerCommand(ComposerAction.CompactSession)
            assertEquals(emptyList<Pair<String, String>>(), repo.sent)
            assertTrue(logs.contains("event=composer_action action=compact outcome=absent"))

            vm.onComposerCommand(ComposerAction.KnowledgeCapture)
            assertEquals(listOf(CONV to "/knowledge-capture"), repo.sent)
        }

    @Test
    fun anAliasMatch_keepsCompactAvailable() =
        runTest {
            val repo = RecordingRepo()
            val vm = collectedVm(repo)

            repo.fake.setSlashCommandMenu(
                CONV,
                SlashCommandMenu(rows = listOf(row("summarise", aliases = listOf("compact"))), droppedCommands = 0),
            )
            advanceUntilIdle()
            vm.onComposerCommand(ComposerAction.CompactSession)

            assertFalse(ComposerAction.CompactSession in vm.state.value.absentActions)
            assertEquals(listOf(CONV to "/compact"), repo.sent)
        }

    @Test
    fun noMenu_orADroppedCount_leavesEveryActionAvailable() =
        runTest {
            val repo = RecordingRepo()
            val vm = collectedVm(repo)

            assertEquals(emptySet<ComposerAction>(), vm.state.value.absentActions)
            repo.fake.setSlashCommandMenu(CONV, SlashCommandMenu(rows = emptyList(), droppedCommands = 3))
            advanceUntilIdle()
            assertEquals(emptySet<ComposerAction>(), vm.state.value.absentActions)
        }

    // #885: the published rows reach the composer's type-ahead verbatim; no menu is null, not empty.
    @Test
    fun thePublishedRows_reachTheState_andNoMenuReadsAsNull() =
        runTest {
            val repo = RecordingRepo()
            val vm = collectedVm(repo)
            assertEquals(null, vm.state.value.slashCommands)

            val rows = listOf(row("clear", aliases = listOf("reset")), row("model"))
            repo.fake.setSlashCommandMenu(CONV, SlashCommandMenu(rows = rows, droppedCommands = 0))
            repo.fake.setSlashCommandMenu("other", SlashCommandMenu(rows = listOf(row("usage")), droppedCommands = 0))
            advanceUntilIdle()

            assertEquals(rows, vm.state.value.slashCommands)
        }

    @Test
    fun anotherConversationsMenu_doesNotGreyThisOne() =
        runTest {
            val repo = RecordingRepo()
            val vm = collectedVm(repo)

            repo.fake.setSlashCommandMenu("other", SlashCommandMenu(rows = emptyList(), droppedCommands = 0))
            advanceUntilIdle()

            assertEquals(emptySet<ComposerAction>(), vm.state.value.absentActions)
        }

    @Test
    fun resetSession_isNeverSentAsAMessage() =
        runTest {
            val repo = RecordingRepo()
            val vm = collectedVm(repo)

            vm.onComposerCommand(ComposerAction.ResetSession)

            assertEquals(emptyList<Pair<String, String>>(), repo.sent)
        }

    // #1111: a session whose capability list reports `slash_commands` false greys both commands and refuses
    // the send; true, a list without the flag, and no list at all grey nothing.
    @Test
    fun slashCommandsFalse_marksBothCommandsAbsent_andSendsNothing() =
        runTest {
            val repo = RecordingRepo()
            val vm = collectedVm(repo)

            repo.fake.setSessionSettingsReading(CONV, settings(SessionCapabilities(emptyList(), emptyList(), slashCommands = false)))
            advanceUntilIdle()

            assertEquals(setOf(ComposerAction.CompactSession, ComposerAction.KnowledgeCapture), vm.state.value.absentActions)
            vm.onComposerCommand(ComposerAction.CompactSession)
            vm.onComposerCommand(ComposerAction.KnowledgeCapture)
            assertEquals(emptyList<Pair<String, String>>(), repo.sent)
        }

    @Test
    fun slashCommandsTrue_orNoList_leavesBothCommandsAvailable() =
        runTest {
            val repo = RecordingRepo()
            val vm = collectedVm(repo)

            repo.fake.setSessionSettingsReading(CONV, settings(SessionCapabilities(emptyList(), emptyList())))
            advanceUntilIdle()
            assertEquals(emptySet<ComposerAction>(), vm.state.value.absentActions)

            repo.fake.setSessionSettingsReading(CONV, settings(capabilities = null))
            advanceUntilIdle()
            assertEquals(emptySet<ComposerAction>(), vm.state.value.absentActions)
            vm.onComposerCommand(ComposerAction.CompactSession)
            assertEquals(listOf(CONV to "/compact"), repo.sent)
        }

    // #1348: a command carries the pending files, as desktop's sendText hands takeAttachments to both callers.
    @Test
    fun compactWithPendingFiles_sendsThemWithTheCommand_clearsTheStrip_andLeavesTheDraft() =
        runTest {
            val repo = RecordingRepo()
            val vm = collectedVm(repo)
            vm.onDraftChange("/compact")
            vm.attach("a")
            vm.attach("b")

            vm.onComposerCommand(ComposerAction.CompactSession)
            advanceUntilIdle()

            assertEquals(listOf("a", "b"), repo.uploads)
            assertEquals(listOf("/compact" to listOf("id-a", "id-b")), repo.sentWithFiles)
            assertEquals(emptyList<Pair<String, String>>(), repo.sent)
            assertEquals(emptyList<PendingAttachment>(), draftStore.attachmentsFor(SERVER, CONV))
            assertEquals("/compact", draftStore.draftFor(SERVER, CONV))
            assertFalse(vm.attachmentsSending.value)
            assertTrue(logs.contains("event=composer_action action=compact outcome=sent"))
        }

    @Test
    fun aGreyedCommandWithPendingFiles_sendsNothing_andKeepsTheFiles() =
        runTest {
            val repo = RecordingRepo()
            val vm = collectedVm(repo)
            repo.fake.setSlashCommandMenu(CONV, SlashCommandMenu(rows = listOf(row("knowledge-capture")), droppedCommands = 0))
            advanceUntilIdle()
            vm.attach("a")

            vm.onComposerCommand(ComposerAction.CompactSession)
            advanceUntilIdle()

            assertEquals(emptyList<String>(), repo.uploads)
            assertEquals(emptyList<Pair<String, List<String>>>(), repo.sentWithFiles)
            assertEquals(listOf("a"), draftStore.attachmentsFor(SERVER, CONV).map { it.displayName })
        }

    @Test
    fun aCommandWhileFilesAreSending_isRefused_soTheFilesAreNotSentTwice() =
        runTest {
            val repo = RecordingRepo()
            val gate = CompletableDeferred<Unit>()
            repo.beforeUpload = { gate.await() }
            val vm = collectedVm(repo)
            vm.onDraftChange("look")
            vm.attach("a")
            vm.sendMessage("look")
            assertTrue(vm.attachmentsSending.value)

            vm.onComposerCommand(ComposerAction.CompactSession)
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(listOf("a"), repo.uploads)
            assertEquals(listOf("look" to listOf("id-a")), repo.sentWithFiles)
            assertEquals(emptyList<Pair<String, String>>(), repo.sent)
            assertTrue(logs.contains("event=composer_action action=compact outcome=busy"))
        }

    private fun settings(capabilities: SessionCapabilities?) =
        SessionSettings(
            sessionId = "sess-a",
            model = "",
            effort = "",
            effectiveEffort = EffectiveEffort.Unavailable,
            permissionMode = "default",
            yolo = false,
            usedTokens = 0,
            windowTokens = 0,
            capabilities = capabilities,
        )

    private companion object {
        const val CONV = "seed-channel-personal"
        const val SERVER = "server-1"
    }
}
