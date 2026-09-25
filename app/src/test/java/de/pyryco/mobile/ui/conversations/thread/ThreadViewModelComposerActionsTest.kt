package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.SlashCommandMenu
import de.pyryco.mobile.data.repository.SlashCommandMenuRow
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
        var failure: Throwable? = null

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
            )
        backgroundScope.launch { vm.state.collect {} }
        return vm
    }

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

    private companion object {
        const val CONV = "seed-channel-personal"
        const val SERVER = "server-1"
    }
}
