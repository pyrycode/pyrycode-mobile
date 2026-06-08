package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Session
import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiteralScreenViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUpMainDispatcher() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDownMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_isLoading() =
        runTest(dispatcher) {
            val vm = LiteralScreenViewModel(handleWith("conv-1"), repo { "ignored" })
            assertEquals(LiteralScreenUiState.Loading, vm.state.value)
        }

    @Test
    fun request_success_yieldsContent_withVerbatimText() =
        runTest(dispatcher) {
            // Leading/trailing whitespace + internal newlines/control chars: any trim/sanitize would
            // change the bytes and fail the equality below (AC#4 — verbatim).
            val raw = "  top line\n\tindented body  \n"
            val vm = LiteralScreenViewModel(handleWith("conv-1"), repo { raw })

            vm.onEvent(LiteralScreenEvent.Request)
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue("expected Content, was $state", state is LiteralScreenUiState.Content)
            assertEquals(raw, (state as LiteralScreenUiState.Content).text)
        }

    @Test
    fun content_toString_redactsText() {
        val rendered = LiteralScreenUiState.Content("super-secret-screen").toString()
        assertFalse("toString must not leak the screen text", rendered.contains("super-secret-screen"))
        assertTrue("toString must mark the text as redacted", rendered.contains("redacted"))
    }

    @Test
    fun unknownConversation_yieldsError_unknownConversation() =
        runTest(dispatcher) {
            val vm =
                LiteralScreenViewModel(
                    handleWith("ghost"),
                    repo { throw IllegalArgumentException("unknown conversation") },
                )

            vm.onEvent(LiteralScreenEvent.Request)
            advanceUntilIdle()

            assertEquals(
                LiteralScreenUiState.Error(LiteralScreenError.UnknownConversation),
                vm.state.value,
            )
        }

    @Test
    fun serverError_yieldsError_serverError() =
        runTest(dispatcher) {
            val vm =
                LiteralScreenViewModel(
                    handleWith("conv-1"),
                    repo { throw RelayErrorException(code = "server.binary_offline", retryable = true, message = "down") },
                )

            vm.onEvent(LiteralScreenEvent.Request)
            advanceUntilIdle()

            assertEquals(
                LiteralScreenUiState.Error(LiteralScreenError.ServerError),
                vm.state.value,
            )
        }

    @Test
    fun notConnected_yieldsError_notConnected() =
        runTest(dispatcher) {
            val vm =
                LiteralScreenViewModel(
                    handleWith("conv-1"),
                    repo { throw IllegalStateException("not connected") },
                )

            vm.onEvent(LiteralScreenEvent.Request)
            advanceUntilIdle()

            assertEquals(
                LiteralScreenUiState.Error(LiteralScreenError.NotConnected),
                vm.state.value,
            )
        }

    @Test
    fun request_invokesRead_withRouteConversationId() =
        runTest(dispatcher) {
            val repository = repo { "screen" }
            val vm = LiteralScreenViewModel(handleWith("conv-xyz"), repository)

            vm.onEvent(LiteralScreenEvent.Request)
            advanceUntilIdle()

            assertEquals(1, repository.calls)
            assertEquals("conv-xyz", repository.lastConversationId)
        }

    @Test
    fun retry_afterError_transitionsLoadingThenContent_andReinvokes() =
        runTest(dispatcher) {
            val deferred = CompletableDeferred<String>()
            var attempt = 0
            val repository =
                repo {
                    attempt++
                    if (attempt == 1) throw IllegalStateException("not connected")
                    deferred.await() // second call suspends until completed below
                }
            val vm = LiteralScreenViewModel(handleWith("conv-1"), repository)

            // First request fails → Error.
            vm.onEvent(LiteralScreenEvent.Request)
            advanceUntilIdle()
            assertEquals(
                LiteralScreenUiState.Error(LiteralScreenError.NotConnected),
                vm.state.value,
            )

            // Retry re-invokes the read; while its outcome is suspended, state is Loading (AC#2).
            vm.onEvent(LiteralScreenEvent.Retry)
            runCurrent()
            assertEquals(LiteralScreenUiState.Loading, vm.state.value)
            assertEquals(2, repository.calls)

            // Completing the read drives Loading → Content (AC#2, AC#5).
            deferred.complete("screen-after-retry")
            advanceUntilIdle()
            val state = vm.state.value
            assertTrue("expected Content, was $state", state is LiteralScreenUiState.Content)
            assertEquals("screen-after-retry", (state as LiteralScreenUiState.Content).text)
        }

    // --- helpers ---

    private fun handleWith(conversationId: String): SavedStateHandle =
        SavedStateHandle(initialState = mapOf("conversationId" to conversationId))

    private fun repo(outcome: suspend (String) -> String): FakeSnapshotRepository = FakeSnapshotRepository(outcome)

    /**
     * Controllable [ConversationRepository] double modelled on `RecordingConversationRepository`
     * (StableConversationRepositoryTest): only [requestScreenSnapshot] is exercised — it records the
     * call count + last id and defers to a configurable outcome. Every other member is an
     * out-of-scope [UnsupportedOperationException] stub.
     */
    private class FakeSnapshotRepository(
        private val outcome: suspend (String) -> String,
    ) : ConversationRepository {
        var calls: Int = 0
            private set
        var lastConversationId: String? = null
            private set

        override suspend fun requestScreenSnapshot(conversationId: String): String {
            calls++
            lastConversationId = conversationId
            return outcome(conversationId)
        }

        override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
            throw UnsupportedOperationException("observeConversations stub")

        override fun observeMessages(conversationId: String): Flow<List<ThreadItem>> =
            throw UnsupportedOperationException("observeMessages stub")

        override fun observeLastMessage(conversationId: String): Flow<Message?> =
            throw UnsupportedOperationException("observeLastMessage stub")

        override suspend fun createDiscussion(workspace: String?): Conversation =
            throw UnsupportedOperationException("createDiscussion stub")

        override suspend fun promote(
            conversationId: String,
            name: String,
            workspace: String?,
        ): Conversation = throw UnsupportedOperationException("promote stub")

        override suspend fun archive(conversationId: String): Unit = throw UnsupportedOperationException("archive stub")

        override suspend fun unarchive(conversationId: String): Unit = throw UnsupportedOperationException("unarchive stub")

        override suspend fun rename(
            conversationId: String,
            name: String,
        ): Conversation = throw UnsupportedOperationException("rename stub")

        override suspend fun startNewSession(
            conversationId: String,
            workspace: String?,
        ): Session = throw UnsupportedOperationException("startNewSession stub")

        override suspend fun changeWorkspace(
            conversationId: String,
            workspace: String,
        ): Session = throw UnsupportedOperationException("changeWorkspace stub")

        override suspend fun sendMessage(
            conversationId: String,
            text: String,
        ): Message = throw UnsupportedOperationException("sendMessage stub")
    }
}
