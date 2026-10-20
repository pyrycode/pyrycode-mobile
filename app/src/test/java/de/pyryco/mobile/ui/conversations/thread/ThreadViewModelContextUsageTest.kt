package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.repository.ContextCategory
import de.pyryco.mobile.data.repository.ContextMcpTool
import de.pyryco.mobile.data.repository.ContextMemoryFile
import de.pyryco.mobile.data.repository.ContextUsage
import de.pyryco.mobile.data.repository.ConversationFilter
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
import kotlinx.coroutines.flow.first
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #946 / #1411: the footer and the Status sheet read the context percentage from one run-config field. A
 * reported reading's `totalTokens` / `maxTokens` give it; with no reading, the current session settings' token pair does;
 * with neither it is `null`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelContextUsageTest {
    @Test
    fun mcpInventorySurvivesViewModelUpdates() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo)
            vm.onOverflowEvent(ThreadEvent.ContextOpen)
            val first = usage().copy(mcpTools = listOf(ContextMcpTool("one", "s", 19)), droppedMcpTools = 8, droppedCategories = 2)
            repo.report(CONV, first)
            assertEquals(first, vm.state.value.runConfig.contextUsage)
            val replacement = first.copy(mcpTools = listOf(ContextMcpTool("two", "__proto__", 12)), droppedMcpTools = 3)
            repo.report(CONV, replacement)
            assertEquals(replacement, vm.state.value.runConfig.contextUsage)
            repo.report(OTHER_CONV, first)
            assertEquals(replacement, vm.state.value.runConfig.contextUsage)
            repo.report(CONV, replacement.copy(asOf = kotlinx.datetime.Instant.parse("2026-10-19T00:00:00Z")))
            assertEquals(
                replacement.mcpTools,
                vm.state.value.runConfig.contextUsage
                    ?.mcpTools,
            )
            assertTrue(vm.state.value.contextSheetOpen)
        }

    @Test
    fun memoryInventoryReplacesWithoutChangingScalars() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo)
            vm.onOverflowEvent(ThreadEvent.ContextOpen)
            val first = usage().copy(memoryFiles = listOf(ContextMemoryFile("same.md", "first", 1)), droppedMemoryFiles = 7)
            repo.report(CONV, first)
            assertEquals(first, vm.state.value.runConfig.contextUsage)
            val replacement = first.copy(memoryFiles = listOf(ContextMemoryFile("same.md", "other", 2)), droppedMemoryFiles = 3)
            repo.report(CONV, replacement)
            assertEquals(replacement, vm.state.value.runConfig.contextUsage)
            assertTrue(vm.state.value.contextSheetOpen)
            repo.readings.value = emptyMap()
            assertNull(vm.state.value.runConfig.contextUsage)
        }

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
    fun contextSheetVisibilityAndReadingStayLive() =
        runTest {
            val repo = ScriptedRepo()
            val vm = collectedVm(repo)
            assertFalse(vm.state.value.contextSheetOpen)
            vm.onOverflowEvent(ThreadEvent.ContextOpen)
            vm.onOverflowEvent(ThreadEvent.ContextOpen)
            assertTrue(vm.state.value.contextSheetOpen)
            assertNull(vm.state.value.runConfig.contextUsage)
            val reading =
                usage(
                    total = 50_000,
                ).copy(model = "model", categories = listOf(ContextCategory("Messages", 50_000)), droppedCategories = 4)
            repo.report(CONV, reading)
            assertEquals(reading, vm.state.value.runConfig.contextUsage)
            assertEquals(50_000L, vm.state.value.runConfig.contextUsedTokens)
            assertEquals(200_000L, vm.state.value.runConfig.contextMaxTokens)
            repo.report(CONV, reading.copy(categories = listOf(ContextCategory("Tools", 50_000))))
            assertEquals(
                "Tools",
                vm.state.value.runConfig.contextUsage
                    ?.categories
                    ?.single()
                    ?.name,
            )
            repo.report(CONV, reading.copy(asOf = kotlinx.datetime.Instant.parse("2026-10-19T00:00:00Z")))
            assertEquals(
                kotlinx.datetime.Instant.parse("2026-10-19T00:00:00Z"),
                vm.state.value.runConfig.contextUsage
                    ?.asOf,
            )
            repo.readings.value = emptyMap()
            assertNull(vm.state.value.runConfig.contextUsage)
            assertTrue(vm.state.value.contextSheetOpen)
            vm.onOverflowEvent(ThreadEvent.ContextDismiss)
            vm.onOverflowEvent(ThreadEvent.ContextDismiss)
            assertFalse(vm.state.value.contextSheetOpen)
            assertFalse(collectedVm(repo).state.value.contextSheetOpen)
        }

    @Test
    fun codexUsesEligibleScalarSource() =
        runTest {
            val repo = ScriptedRepo()
            repo.settings.value = repo.currentSessionSettings(usedTokens = 84_200, windowTokens = 272_000)
            val vm = collectedVm(repo)
            assertEquals(84_200L, vm.state.value.runConfig.contextUsedTokens)
            assertEquals(272_000L, vm.state.value.runConfig.contextMaxTokens)
            assertEquals(31, vm.state.value.runConfig.contextPercent)
            repo.report(CONV, usage(total = 7, max = 0))
            assertEquals(7L, vm.state.value.runConfig.contextUsedTokens)
            assertEquals(0L, vm.state.value.runConfig.contextMaxTokens)
            assertNull(vm.state.value.runConfig.contextPercent)
            repo.readings.value = emptyMap()
            assertEquals(84_200L, vm.state.value.runConfig.contextUsedTokens)
            repo.settings.value = null
            assertNull(vm.state.value.runConfig.contextUsedTokens)
            assertNull(vm.state.value.runConfig.contextMaxTokens)
        }

    @Test
    fun contextSheetSurvivesRecollectionAndUsesLatestReading() =
        runTest {
            val repo = ScriptedRepo()
            val vm =
                ThreadViewModel(
                    SavedStateHandle(mapOf("serverId" to "host-a", "conversationId" to CONV)),
                    repo,
                    FakeConnectionStateSource(),
                    ComposerDraftStore(),
                )
            val subscription = backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            vm.onOverflowEvent(ThreadEvent.ContextOpen)
            repo.report(CONV, usage(total = 10))
            subscription.cancel()
            runCurrent()
            val latest = usage(total = 20).copy(mcpTools = listOf(ContextMcpTool("latest", "s", 20)), droppedMcpTools = 4)
            repo.report(CONV, latest)
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            assertTrue(vm.state.value.contextSheetOpen)
            assertEquals(20L, vm.state.value.runConfig.contextUsedTokens)
            assertEquals(latest, vm.state.value.runConfig.contextUsage)
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
            repo.settings.value = repo.currentSessionSettings(usedTokens = 150_000, windowTokens = 200_000)
            val vm = collectedVm(repo)

            assertEquals(75, vm.state.value.runConfig.contextPercent)
        }

    @Test
    fun aReading_outranksTheSettings_andClearingItFallsBackToThem() =
        runTest {
            val repo = ScriptedRepo()
            repo.settings.value = repo.currentSessionSettings(usedTokens = 150_000, windowTokens = 200_000)
            val vm = collectedVm(repo)

            repo.report(CONV, usage(total = 24_000))
            assertEquals(12, vm.state.value.runConfig.contextPercent)

            // Clear only the context reading; the settings still belong to the unchanged session.
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
            repo.settings.value = repo.currentSessionSettings(usedTokens = 0, windowTokens = 0)
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

    private suspend fun ScriptedRepo.currentSessionSettings(
        usedTokens: Long,
        windowTokens: Long,
    ) = SessionSettings(
        sessionId = observeConversations(ConversationFilter.All).first().single { it.id == CONV }.currentSessionId,
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
    }
}
