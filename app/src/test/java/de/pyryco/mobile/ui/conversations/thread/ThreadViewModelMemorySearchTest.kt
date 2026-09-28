package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.toSessionSettings
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.MemorySearchAvailability
import de.pyryco.mobile.data.repository.MemorySearchReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelMemorySearchTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun decodedReportFollowsSelectedConversation_andClearsWhileReplacementReadIsPending() =
        runTest {
            val repo = SelectedSessionsRepo()
            repo.sessions.value = mapOf(CHANNEL to "sess-a", DISCUSSION to "sess-b")
            repo.backing.setSessionSettingsReading(CHANNEL, fixture("session_settings_memory_available.json"))
            val channel = collectedVm(repo, CHANNEL)
            assertEquals(MemorySearchAvailability.Available, channel.state.value.runConfig.memorySearch.availability)

            val discussion = collectedVm(repo, DISCUSSION)
            assertEquals(MemorySearchReport.Unknown, discussion.state.value.runConfig.memorySearch)
            repo.backing.setSessionSettingsReading(
                DISCUSSION,
                fixture("session_settings_memory_disabled.json").copy(sessionId = "sess-b"),
            )
            assertEquals(MemorySearchAvailability.Unavailable, discussion.state.value.runConfig.memorySearch.availability)
            assertEquals(
                "memsearch",
                discussion.state.value.runConfig.memorySearch.providers
                    .single()
                    .id,
            )

            repo.backing.setSessionSettingsReading(CHANNEL, null) // reconnect or host handoff's null reading
            assertEquals(MemorySearchReport.Unknown, channel.state.value.runConfig.memorySearch)
            assertEquals(MemorySearchAvailability.Unavailable, discussion.state.value.runConfig.memorySearch.availability)
        }

    @Test
    fun sessionReplacementMasksOldReport_untilNewSessionReadingArrives() =
        runTest {
            val repo = SelectedSessionsRepo()
            repo.sessions.value = mapOf(CHANNEL to "sess-a")
            repo.backing.setSessionSettingsReading(CHANNEL, fixture("session_settings_memory_available.json"))
            val vm = collectedVm(repo, CHANNEL)
            assertEquals(MemorySearchAvailability.Available, vm.state.value.runConfig.memorySearch.availability)

            repo.sessions.value = mapOf(CHANNEL to "sess-new")
            runCurrent()
            assertEquals(MemorySearchReport.Unknown, vm.state.value.runConfig.memorySearch)

            repo.backing.setSessionSettingsReading(
                CHANNEL,
                fixture("session_settings_memory_absent.json").copy(sessionId = "sess-new"),
            )
            assertEquals(MemorySearchAvailability.Absent, vm.state.value.runConfig.memorySearch.availability)
        }

    private fun TestScope.collectedVm(
        repo: SelectedSessionsRepo,
        conversationId: String,
    ): ThreadViewModel {
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("serverId" to "host-a", "conversationId" to conversationId)),
                repo,
                FakeConnectionStateSource(),
                ComposerDraftStore(),
            )
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return vm
    }

    private fun fixture(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/daemon-contract/$name")) { name }.bufferedReader().use { raw ->
            MobileJson
                .parseToJsonElement(raw.readText())
                .jsonObject
                .getValue("payload")
                .toSessionSettings()
        }

    private class SelectedSessionsRepo(
        val backing: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by backing {
        val sessions = MutableStateFlow<Map<String, String>>(emptyMap())

        override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
            combine(backing.observeConversations(filter), sessions) { list, selected ->
                list.map { conversation ->
                    selected[conversation.id]?.let { conversation.copy(currentSessionId = it) } ?: conversation
                }
            }
    }

    private companion object {
        const val CHANNEL = "seed-channel-personal"
        const val DISCUSSION = "seed-discussion-b"
    }
}
