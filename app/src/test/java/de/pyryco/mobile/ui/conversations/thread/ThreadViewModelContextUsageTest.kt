package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.repository.ContextUsage
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.SessionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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

/**
 * #946: the footer and the Status sheet read Claude's reported context percentage from one run-config field.
 * It is shown as sent, never derived, and `null` whenever the repository holds no reading for this conversation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelContextUsageTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun beforeAnyReading_theContextUsageIsUnavailable() =
        runTest {
            val vm = collectedVm(ScriptedRepo())

            assertNull(vm.state.value.runConfig.contextPercent)
        }

    @Test
    fun aReading_isShownAsClaudeReportedIt_notDerivedFromTheTokenTotals() =
        runTest {
            val repo = ScriptedRepo()
            // 84% as reported, although 50_000 / 200_000 would be 25%.
            repo.report(CONV, usage(total = 50_000, max = 200_000, percentage = 84))
            val vm = collectedVm(repo)

            assertEquals(84, vm.state.value.runConfig.contextPercent)
        }

    @Test
    fun aReplacedReading_isShownInPlaceOfTheOldOne() =
        runTest {
            val repo = ScriptedRepo()
            repo.report(CONV, usage(percentage = 12))
            val vm = collectedVm(repo)
            assertEquals(12, vm.state.value.runConfig.contextPercent)

            repo.report(CONV, usage(percentage = 37))

            assertEquals(37, vm.state.value.runConfig.contextPercent)
        }

    @Test
    fun aClearedReading_returnsToUnavailable() =
        runTest {
            val repo = ScriptedRepo()
            repo.report(CONV, usage(percentage = 12))
            val vm = collectedVm(repo)

            // What the repository does on a session transition or a reconnect.
            repo.readings.update { it - CONV }

            assertNull(vm.state.value.runConfig.contextPercent)
        }

    @Test
    fun aReadingForAnotherConversation_doesNotAppear() =
        runTest {
            val repo = ScriptedRepo()
            repo.report(OTHER_CONV, usage(percentage = 64))
            val vm = collectedVm(repo)
            assertNull(vm.state.value.runConfig.contextPercent)

            repo.report(OTHER_CONV, usage(percentage = 65))

            assertNull(vm.state.value.runConfig.contextPercent)
        }

    @Test
    fun theTranscriptDerivedSettingsFigures_neverFillTheUnavailableState() =
        runTest {
            val repo = ScriptedRepo()
            repo.settings.value = settings(usedTokens = 150_000, windowTokens = 200_000)
            val vm = collectedVm(repo)

            assertEquals(true, vm.state.value.runConfig.settingsAvailable)
            assertNull(vm.state.value.runConfig.contextPercent)
        }

    private fun TestScope.collectedVm(repo: ScriptedRepo): ThreadViewModel {
        val vm =
            ThreadViewModel(
                SavedStateHandle(mapOf("serverId" to "host-a", "conversationId" to CONV)),
                repo,
                FakeConnectionStateSource(),
                ComposerDraftStore(),
            )
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        return vm
    }

    private fun usage(
        total: Long = 10_000,
        max: Long = 200_000,
        percentage: Int,
    ) = ContextUsage(totalTokens = total, maxTokens = max, percentage = percentage, asOf = null)

    private fun settings(
        usedTokens: Long,
        windowTokens: Long,
    ) = SessionSettings(
        sessionId = SESSION,
        model = "",
        effort = "",
        effectiveEffort = EffectiveEffort.Unavailable,
        permissionMode = "",
        yolo = false,
        usedTokens = usedTokens,
        windowTokens = windowTokens,
    )

    /** The #945 reading as a per-conversation map, the shape of the repository's own projection. */
    private class ScriptedRepo(
        backing: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by backing {
        val settings = MutableStateFlow<SessionSettings?>(null)
        val readings = MutableStateFlow<Map<String, ContextUsage>>(emptyMap())

        fun report(
            conversationId: String,
            reading: ContextUsage,
        ) = readings.update { it + (conversationId to reading) }

        override fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> = settings

        override fun observeContextUsage(conversationId: String): Flow<ContextUsage?> =
            readings.map { it[conversationId] }.distinctUntilChanged()
    }

    private companion object {
        const val CONV = "seed-channel-personal"
        const val OTHER_CONV = "seed-channel-pyrycode-mobile"
        const val SESSION = "sess-a"
    }
}
