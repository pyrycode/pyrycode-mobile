package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.MemorySearchAvailability
import de.pyryco.mobile.data.repository.MemorySearchReport
import de.pyryco.mobile.data.repository.SessionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #1320: after a reconnect the repository's settings head is the host's held reading, invalidated. The footer
 * keeps its model and effort and stays writable, while the permission mode and memory search read as not yet
 * known until the new connection's reply replaces the whole reading.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelHeldSettingsTest {
    private val settings = MutableStateFlow<SessionSettings?>(null)
    private val repo =
        object : ConversationRepository by FakeConversationRepository() {
            override fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> = settings
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
    fun heldReading_keepsModelAndEffortWritable_withPermissionAndMemoryUnknown_untilTheReply() =
        runTest {
            val vm = collectedVm()

            settings.value = CONFIRMED.copy(permissionMode = "", memorySearch = MemorySearchReport.Unknown, held = true)
            runCurrent()
            val held = vm.state.value.runConfig
            assertTrue(held.settingsAvailable)
            assertTrue("marked held, so nothing automatic acts on it", held.settingsHeld)
            assertTrue("a held reading keeps the footer writable", held.writable)
            assertEquals("opus", held.savedModel)
            assertEquals("high", held.savedEffort)
            assertEquals("the permission mode is not yet known", "", held.permissionMode)
            assertEquals(MemorySearchReport.Unknown, held.memorySearch)

            settings.value = CONFIRMED
            runCurrent()
            val confirmed = vm.state.value.runConfig
            assertFalse(confirmed.settingsHeld)
            assertEquals("plan", confirmed.permissionMode)
            assertEquals(MemorySearchAvailability.Available, confirmed.memorySearch.availability)
            assertEquals("opus", confirmed.savedModel)
        }

    @Test
    fun noHeldReading_leavesTheRunConfigurationUnavailable() =
        runTest {
            val vm = collectedVm()

            val config = vm.state.value.runConfig
            assertFalse(config.settingsAvailable)
            assertFalse(config.writable)
            assertEquals("", config.savedModel)
        }

    private fun TestScope.collectedVm(): ThreadViewModel {
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

    private companion object {
        const val CONV = "seed-channel-personal"

        /** The seed conversation's current session, so the reading is not hidden as a replaced session's. */
        val CONFIRMED =
            SessionSettings(
                sessionId = "seed-session-personal",
                model = "opus",
                effort = "high",
                effectiveEffort = EffectiveEffort.Unavailable,
                permissionMode = "plan",
                yolo = false,
                usedTokens = 0,
                windowTokens = 0,
                memorySearch = MemorySearchReport(MemorySearchAvailability.Available, emptyList()),
            )
    }
}
