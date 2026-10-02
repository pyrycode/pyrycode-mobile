package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.SessionPromptStatus
import de.pyryco.mobile.ui.conversations.list.ChannelPromptReading
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** #1561: a channel's menu Edit opens Edit channel on the thread, running the list's write chain. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelChannelEditTest {
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
    fun editOpensWithTheChannelsNameStoredPromptAndMuteFlag() =
        runTest {
            val (vm, repo) = rig()

            vm.onOverflowEvent(ThreadEvent.EditChannel)
            runCurrent()

            val editor = vm.state.value.channelEditor
            assertEquals("host-a", editor?.serverId)
            assertEquals(CHANNEL, editor?.conversationId)
            assertEquals("Personal", editor?.savedName)
            assertEquals(true, editor?.savedMuted)
            assertEquals(ChannelPromptReading.Read("stored", SessionPromptStatus.NoSession), editor?.prompt)
            assertEquals(listOf("read $CHANNEL"), repo.calls)
        }

    @Test
    fun okWritesOnlyTheChangedNameAndTheTitleFollows() =
        runTest {
            val (vm, repo) = rig()
            vm.onOverflowEvent(ThreadEvent.EditChannel)
            runCurrent()

            vm.onOverflowEvent(ThreadEvent.ChannelEditSubmit("  Renamed  ", "stored", muted = true))
            runCurrent()

            assertEquals(listOf("read $CHANNEL", "rename $CHANNEL Renamed"), repo.calls)
            assertNull(vm.state.value.channelEditor)
            assertEquals("Renamed", vm.state.value.displayName)
        }

    @Test
    fun okWritesMuteThenPromptWhenOnlyThoseChanged() =
        runTest {
            val (vm, repo) = rig()
            vm.onOverflowEvent(ThreadEvent.EditChannel)
            runCurrent()

            vm.onOverflowEvent(ThreadEvent.ChannelEditSubmit("Personal", "", muted = false))
            runCurrent()

            assertEquals(listOf("read $CHANNEL", "mute $CHANNEL false", "prompt $CHANNEL null"), repo.calls)
            assertNull(vm.state.value.channelEditor)
        }

    @Test
    fun theModalsArchiveArchivesAndLeavesTheThreadOnce() =
        runTest {
            val (vm, repo) = rig()
            val nav = mutableListOf<ThreadNavigation>()
            backgroundScope.launch { vm.navigationEvents.collect { nav += it } }
            vm.onOverflowEvent(ThreadEvent.EditChannel)
            runCurrent()

            vm.onOverflowEvent(ThreadEvent.ChannelEditArchive)
            runCurrent()

            assertEquals(listOf("read $CHANNEL", "archive $CHANNEL"), repo.calls)
            assertNull(vm.state.value.channelEditor)
            // The archived row also reaches the thread's list; the thread still leaves only once.
            assertEquals(listOf<ThreadNavigation>(ThreadNavigation.PopBack), nav)
        }

    @Test
    fun dismissClosesTheModalWithNothingWritten() =
        runTest {
            val (vm, repo) = rig()
            vm.onOverflowEvent(ThreadEvent.EditChannel)
            runCurrent()

            vm.onOverflowEvent(ThreadEvent.ChannelEditDismiss)
            runCurrent()

            assertNull(vm.state.value.channelEditor)
            assertEquals(listOf("read $CHANNEL"), repo.calls)
        }

    @Test
    fun aDiscussionOpensNoChannelEditor() =
        runTest {
            val (vm, repo) = rig(conversationId = DISCUSSION)

            vm.onOverflowEvent(ThreadEvent.EditChannel)
            runCurrent()

            assertNull(vm.state.value.channelEditor)
            assertEquals(emptyList<String>(), repo.calls)
        }

    @Test
    fun aRepositoryWithoutALiveHostDisablesTheModalsActions() =
        runTest {
            val (vm, _) = rig()
            assertEquals(true, vm.state.value.hostAvailable)
            val (offline, _) = rig(available = false)
            assertFalse(offline.state.value.hostAvailable)
        }

    @Test
    fun theSubmitEventNeverPrintsTheNameOrPrompt() {
        assertEquals(
            "ChannelEditSubmit(name=<redacted>, systemPrompt=<redacted>, muted=true)",
            ThreadEvent.ChannelEditSubmit("secret-name", "sk-secret", muted = true).toString(),
        )
    }

    private fun TestScope.rig(
        conversationId: String = CHANNEL,
        available: Boolean = true,
    ): Pair<ThreadViewModel, RecordingRepo> {
        val repo = RecordingRepo()
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("serverId" to "host-a", "conversationId" to conversationId)),
                repo,
                FakeConnectionStateSource(),
                ComposerDraftStore(),
                repositoryAvailable = flowOf(available),
            )
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return vm to repo
    }

    /** Records each write the editor sends and forwards it, so the thread's list sees the result. */
    private class RecordingRepo private constructor(
        private val backing: FakeConversationRepository,
    ) : ConversationRepository by backing {
        constructor() : this(FakeConversationRepository())

        val calls = mutableListOf<String>()

        init {
            runBlocking {
                backing.setMuted(CHANNEL, true)
                backing.setSystemPrompt(CHANNEL, "stored")
            }
        }

        override suspend fun requestSystemPrompt(conversationId: String) =
            backing.requestSystemPrompt(conversationId).also { calls += "read $conversationId" }

        override suspend fun rename(
            conversationId: String,
            name: String,
        ): Conversation = backing.rename(conversationId, name).also { calls += "rename $conversationId $name" }

        override suspend fun setMuted(
            conversationId: String,
            muted: Boolean,
        ) = backing.setMuted(conversationId, muted).also { calls += "mute $conversationId $muted" }

        override suspend fun setSystemPrompt(
            conversationId: String,
            systemPrompt: String?,
        ) = backing.setSystemPrompt(conversationId, systemPrompt).also { calls += "prompt $conversationId $systemPrompt" }

        override suspend fun archive(conversationId: String) = backing.archive(conversationId).also { calls += "archive $conversationId" }
    }

    private companion object {
        const val CHANNEL = "seed-channel-personal"
        const val DISCUSSION = "seed-discussion-a"
    }
}
