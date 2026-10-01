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
 * #946 / #1411: the footer and the Status sheet read the context percentage from one run-config field. A
 * reported reading's `totalTokens` / `maxTokens` give it; with no reading, the session settings' token pair does;
 * with neither it is `null`.
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
    fun beforeAnyReadingOrSettings_theContextUsageIsUnavailable() =
        runTest {
            val vm = collectedVm(ScriptedRepo())

            assertNull(vm.state.value.runConfig.contextPercent)
        }

    @Test
    fun aReading_isComputedFromItsTokenTotals_notItsReportedPercentage() =
        runTest {
            val repo = ScriptedRepo()
            // 50_000 / 200_000 is 25%, although the reading says 84.
            repo.report(CONV, usage(total = 50_000, max = 200_000, percentage = 84))
            val vm = collectedVm(repo)

            assertEquals(25, vm.state.value.runConfig.contextPercent)
        }

    @Test
    fun aReplacedReading_isShownInPlaceOfTheOldOne() =
        runTest {
            val repo = ScriptedRepo()
            repo.report(CONV, usage(total = 24_000))
            val vm = collectedVm(repo)
            assertEquals(12, vm.state.value.runConfig.contextPercent)

            repo.report(CONV, usage(total = 74_000))

            assertEquals(37, vm.state.value.runConfig.contextPercent)
        }

    @Test
    fun withNoReading_theSettingsTokenPairFillsIn() =
        runTest {
            val repo = ScriptedRepo()
            repo.settings.value = settings(usedTokens = 150_000, windowTokens = 200_000)
            val vm = collectedVm(repo)

            assertEquals(75, vm.state.value.runConfig.contextPercent)
        }

    @Test
    fun aReading_outranksTheSettings_andClearingItFallsBackToThem() =
        runTest {
            val repo = ScriptedRepo()
            repo.settings.value = settings(usedTokens = 150_000, windowTokens = 200_000)
            val vm = collectedVm(repo)

            repo.report(CONV, usage(total = 24_000))
            assertEquals(12, vm.state.value.runConfig.contextPercent)

            // What the repository does on a session transition or a reconnect.
            repo.readings.update { it - CONV }

            assertEquals(75, vm.state.value.runConfig.contextPercent)
        }

    @Test
    fun aClearedReadingWithNoSettings_returnsToUnavailable() =
        runTest {
            val repo = ScriptedRepo()
            repo.report(CONV, usage(total = 24_000))
            val vm = collectedVm(repo)

            repo.readings.update { it - CONV }

            assertNull(vm.state.value.runConfig.contextPercent)
        }

    @Test
    fun aSettingsZeroWindow_isUnavailable() =
        runTest {
            val repo = ScriptedRepo()
            repo.settings.value = settings(usedTokens = 0, windowTokens = 0)
            val vm = collectedVm(repo)

            assertEquals(true, vm.state.value.runConfig.settingsAvailable)
            assertNull(vm.state.value.runConfig.contextPercent)
        }

    @Test
    fun aReadingForAnotherConversation_doesNotAppear() =
        runTest {
            val repo = ScriptedRepo()
            repo.report(OTHER_CONV, usage(total = 128_000))
            val vm = collectedVm(repo)
            assertNull(vm.state.value.runConfig.contextPercent)

            repo.report(OTHER_CONV, usage(total = 130_000))

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
        percentage: Int = 0,
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
