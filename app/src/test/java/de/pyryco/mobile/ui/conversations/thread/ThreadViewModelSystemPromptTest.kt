package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.SessionPromptStatus
import de.pyryco.mobile.data.repository.SystemPromptReading
import de.pyryco.mobile.ui.conversations.components.SystemPromptEditorState
import de.pyryco.mobile.ui.conversations.components.SystemPromptEditorState.Loaded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** #1342: Channel info's System prompt section reads on every open, and its controls reach the host. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelSystemPromptTest {
    private val previousSink = RelayLog.sink

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        RelayLog.sink = { _, _, _ -> }
    }

    @After
    fun tearDown() {
        RelayLog.sink = previousSink
        Dispatchers.resetMain()
    }

    @Test
    fun theSectionExistsOnlyWhileTheSheetIsOpenAndEachOpenReadsAgain() =
        runTest {
            val (vm, repo) = rig()
            assertNull(vm.systemPrompt.value)
            assertEquals(emptyList<String>(), repo.reads)

            vm.onOverflowEvent(ThreadEvent.ChannelInfo)
            runCurrent()
            assertEquals(Loaded("stored", SessionPromptStatus.Differs, draft = "stored"), vm.systemPrompt.value)
            // A second open signal while open is not a second read.
            vm.onOverflowEvent(ThreadEvent.ChannelInfo)
            runCurrent()
            assertEquals(listOf(CONV), repo.reads)

            vm.onOverflowEvent(ThreadEvent.SystemPromptEdit("typed"))
            vm.onOverflowEvent(ThreadEvent.ChannelInfoDismiss)
            runCurrent()
            assertNull(vm.systemPrompt.value)

            // Reopening starts from the stored prompt again, not the abandoned draft.
            vm.onOverflowEvent(ThreadEvent.ChannelInfo)
            runCurrent()
            assertEquals(listOf(CONV, CONV), repo.reads)
            assertEquals("stored", (vm.systemPrompt.value as Loaded).draft)
        }

    @Test
    fun aFailedReadIsUnavailable() =
        runTest {
            val (vm, repo) = rig()
            repo.readFailure = IOException("down")

            vm.onOverflowEvent(ThreadEvent.ChannelInfo)
            runCurrent()

            assertEquals(SystemPromptEditorState.Unavailable, vm.systemPrompt.value)
        }

    @Test
    fun saveSendsTheBoxAndClearSendsNullToThisConversation() =
        runTest {
            val (vm, repo) = rig()
            vm.onOverflowEvent(ThreadEvent.ChannelInfo)
            runCurrent()

            vm.onOverflowEvent(ThreadEvent.SystemPromptEdit("  new prompt\n"))
            vm.onOverflowEvent(ThreadEvent.SystemPromptSave)
            runCurrent()
            vm.onOverflowEvent(ThreadEvent.SystemPromptClear)
            runCurrent()

            assertEquals(listOf<Pair<String, String?>>(CONV to "  new prompt\n", CONV to null), repo.writes)
            assertEquals("", (vm.systemPrompt.value as Loaded).draft)
        }

    @Test
    fun archivingOrDeletingFromTheSheetDropsTheSection() =
        runTest {
            for (close in listOf(listOf(ThreadEvent.Archive), listOf(ThreadEvent.Delete, ThreadEvent.DeleteConfirm))) {
                val (vm, _) = rig()
                vm.onOverflowEvent(ThreadEvent.ChannelInfo)
                runCurrent()
                close.forEach(vm::onOverflowEvent)
                runCurrent()
                assertNull(close.toString(), vm.systemPrompt.value)
            }
        }

    @Test
    fun editsWithTheSheetClosedSendNothing() =
        runTest {
            val (vm, repo) = rig()
            vm.onOverflowEvent(ThreadEvent.SystemPromptEdit("x"))
            vm.onOverflowEvent(ThreadEvent.SystemPromptSave)
            vm.onOverflowEvent(ThreadEvent.SystemPromptClear)
            runCurrent()

            assertEquals(emptyList<Pair<String, String?>>(), repo.writes)
        }

    @Test
    fun theEditEventNeverPrintsThePrompt() {
        assertEquals("SystemPromptEdit(text=<redacted>)", ThreadEvent.SystemPromptEdit("sk-secret").toString())
    }

    private fun TestScope.rig(): Pair<ThreadViewModel, PromptRepo> {
        val repo = PromptRepo()
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("serverId" to "host-a", "conversationId" to CONV)),
                repo,
                FakeConnectionStateSource(),
                ComposerDraftStore(),
            )
        backgroundScope.launch { vm.systemPrompt.collect {} }
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return vm to repo
    }

    private class PromptRepo(
        backing: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by backing {
        val reads = mutableListOf<String>()
        val writes = mutableListOf<Pair<String, String?>>()
        var readFailure: Exception? = null

        override suspend fun requestSystemPrompt(conversationId: String): SystemPromptReading {
            reads += conversationId
            readFailure?.let { throw it }
            return SystemPromptReading("stored", SessionPromptStatus.Differs)
        }

        override suspend fun setSystemPrompt(
            conversationId: String,
            systemPrompt: String?,
        ) {
            writes += conversationId to systemPrompt
        }
    }

    private companion object {
        const val CONV = "seed-channel-personal"
    }
}
