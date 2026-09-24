package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AttachmentContent
import de.pyryco.mobile.data.repository.AttachmentFetchResult
import de.pyryco.mobile.data.repository.AttachmentRetrievalResult
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #1050: a markdown link in an assistant reply is read live from the workspace, once per open. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelMarkdownLinkTest {
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

    /** Answers each workspace read with the next queued outcome, after [gate] opens, recording each request. */
    private class WorkspaceRepository(
        vararg outcomes: AttachmentFetchResult,
        private val gate: CompletableDeferred<Unit>? = null,
        private val delegate: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by delegate {
        private val queue = ArrayDeque(outcomes.toList())
        val reads = mutableListOf<Pair<String, String>>()

        override suspend fun readWorkspaceFile(
            conversationId: String,
            path: String,
        ): AttachmentFetchResult {
            reads += conversationId to path
            gate?.await()
            return queue.removeFirst()
        }
    }

    private fun note(text: String): AttachmentFetchResult =
        AttachmentFetchResult.Fetched(AttachmentContent(listOf(text.toByteArray())), "ignored.md", "text/markdown")

    private fun vm(repository: ConversationRepository) =
        ThreadViewModel(
            SavedStateHandle(mapOf("serverId" to HOST, "conversationId" to CONV)),
            repository,
            FakeConnectionStateSource(),
            ComposerDraftStore(),
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    @Test
    fun open_readsThePathOnce_holdsTheNote_andNavigates() =
        runTest {
            val repository = WorkspaceRepository(note("# Plan v1"))
            val vm = vm(repository)
            val navigation = mutableListOf<ThreadNavigation>()
            val failures = mutableListOf<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigationEvents.collect { navigation += it } }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.markdownOpenFailures.collect { failures += it } }

            vm.onOpenMarkdownLink(PATH)
            advanceUntilIdle()

            assertEquals(listOf(CONV to PATH), repository.reads)
            assertEquals(listOf<ThreadNavigation>(ThreadNavigation.OpenLinkedMarkdown), navigation)
            assertEquals("Plan.md", vm.linkedMarkdown()?.name)
            assertEquals("# Plan v1", vm.linkedMarkdown()?.text)
            assertTrue(failures.isEmpty())
        }

    @Test
    fun aTapWhileAnOpenIsInFlight_sendsNothing() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repository = WorkspaceRepository(note("text"), gate = gate)
            val vm = vm(repository)
            val navigation = mutableListOf<ThreadNavigation>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigationEvents.collect { navigation += it } }

            vm.onOpenMarkdownLink(PATH)
            vm.onOpenMarkdownLink(PATH)
            vm.onOpenMarkdownAttachment(ATTACHMENT)
            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(1, repository.reads.size)
            assertEquals(1, navigation.size)
        }

    @Test
    fun aReopen_readsAgain_andShowsTheNewContent() =
        runTest {
            val repository = WorkspaceRepository(note("# Plan v1"), note("# Plan v2"))
            val vm = vm(repository)
            val navigation = mutableListOf<ThreadNavigation>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigationEvents.collect { navigation += it } }

            vm.onOpenMarkdownLink(PATH)
            advanceUntilIdle()
            vm.releaseLinkedMarkdown()
            assertNull(vm.linkedMarkdown())
            vm.onOpenMarkdownLink(PATH)
            advanceUntilIdle()

            assertEquals(2, repository.reads.size)
            assertEquals(2, navigation.size)
            assertEquals("# Plan v2", vm.linkedMarkdown()?.text)
        }

    @Test
    fun aFailedRead_staysOnTheThread_saysOpenFailed_andHoldsNothing() =
        runTest {
            val repository =
                WorkspaceRepository(
                    note("# Plan v1"),
                    AttachmentRetrievalResult.Unavailable,
                    AttachmentRetrievalResult.NotFound,
                    AttachmentFetchResult.Fetched(AttachmentContent(listOf(byteArrayOf(0x41, 0x80.toByte()))), "bad.md", "text/markdown"),
                )
            val vm = vm(repository)
            val navigation = mutableListOf<ThreadNavigation>()
            val failures = mutableListOf<Unit>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.navigationEvents.collect { navigation += it } }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.markdownOpenFailures.collect { failures += it } }
            vm.onOpenMarkdownLink(PATH)
            advanceUntilIdle()
            navigation.clear()

            repeat(3) {
                vm.onOpenMarkdownLink(PATH)
                advanceUntilIdle()
            }

            assertTrue(navigation.isEmpty())
            assertEquals(3, failures.size)
            assertNull(vm.linkedMarkdown())
        }

    @Test
    fun logs_carryAStaticOutcome_neverThePathNameOrText() =
        runTest {
            val repository = WorkspaceRepository(note("secret body"), AttachmentRetrievalResult.Unavailable)
            val vm = vm(repository)

            vm.onOpenMarkdownLink("secret/dir/secret-name.md")
            advanceUntilIdle()
            vm.onOpenMarkdownLink("secret/dir/secret-name.md")
            advanceUntilIdle()

            assertEquals(
                listOf("event=thread_markdown_link_open outcome=reader", "event=thread_markdown_link_open outcome=failed"),
                logs.filter { it.startsWith("event=thread_markdown_link_open") },
            )
            assertTrue(logs.none { "secret" in it })
        }

    private companion object {
        const val HOST = "pyrybox"
        const val CONV = "c1"
        const val PATH = "notes/Plan.md"
        const val ATTACHMENT = "0f8fad5b-d9cb-469f-a165-70867728950e"
    }
}
