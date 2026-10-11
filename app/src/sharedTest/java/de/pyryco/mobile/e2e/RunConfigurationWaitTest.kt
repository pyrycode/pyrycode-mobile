package de.pyryco.mobile.e2e

import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.ModelMenu
import de.pyryco.mobile.data.repository.ModelMenuRow
import de.pyryco.mobile.data.repository.SessionSettings
import de.pyryco.mobile.ui.conversations.components.StatusSheetContent
import de.pyryco.mobile.ui.conversations.thread.ComposerDraftStore
import de.pyryco.mobile.ui.conversations.thread.RememberedEffortStore
import de.pyryco.mobile.ui.conversations.thread.ThreadEffortChoice
import de.pyryco.mobile.ui.conversations.thread.ThreadModelChoice
import de.pyryco.mobile.ui.conversations.thread.ThreadViewModel
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RunConfigurationWaitTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun optimisticEffortWaitsForAcknowledgementAndFreshReading() =
        runTest {
            val repo = DelayedSettingsRepository()
            val store = RecordingEffortStore()
            lateinit var vm: ThreadViewModel
            rule.runOnIdle {
                vm =
                    ThreadViewModel(
                        SavedStateHandle(mapOf("serverId" to "host-a", "conversationId" to CONVERSATION)),
                        repo,
                        FakeConnectionStateSource(),
                        ComposerDraftStore(),
                        rememberedEffort = store,
                    )
            }
            try {
                rule.setContent {
                    val state by vm.state.collectAsState()
                    val config = state.runConfig
                    PyrycodeMobileTheme {
                        StatusSheetContent(
                            choices = config.choices,
                            menuAvailable = config.menuAvailable,
                            notListedModels = 0,
                            selectedModel = config.selectedModel,
                            onModelSelected = {},
                            effortChoices = config.effortChoices,
                            selectedEffort = config.selectedEffort,
                            onEffortSelected = vm::onEffortSelected,
                            pending = config.pending,
                            enabled = true,
                        )
                    }
                }
                rule.waitUntil(5_000) { vm.state.value.runConfig.writable && vm.state.value.runConfig.menuAvailable }
                rule.runOnIdle { vm.onEffortSelected("high") }
                rule.waitForIdle()
                assertEquals("high", vm.state.value.runConfig.selectedEffort)
                assertTrue(vm.state.value.runConfig.pending)
                assertEquals(1, repo.writes)
                assertNull(store.level)
                assertBlocked("Effort", "High")

                rule.runOnIdle { repo.ack.complete(Unit) }
                rule.waitUntil(5_000) { store.level == "high" && repo.refreshes > 0 }
                assertTrue("acknowledgement alone must not clear pending", vm.state.value.runConfig.pending)
                assertBlocked("Effort", "High")

                rule.runOnIdle { repo.readings.value = settings("high") }
                rule.awaitRunConfiguration("Effort", "High", 5_000)
                assertFalse(vm.state.value.runConfig.pending)
                assertEquals("high", store.level)
                assertEquals(1, repo.writes)
            } finally {
                rule.runOnIdle { vm.viewModelScope.cancel() }
            }
        }

    @Test
    fun selectionInAnotherSectionCannotSatisfyEffort() {
        sheet(modelLabel = "High", selectedEffort = "low")
        assertBlocked("Effort", "High")
        rule.awaitRunConfiguration("Model", "High", 5_000)
    }

    @Test
    fun optionalNoteMustMatchInsideEffortAndDisappearBeforeAbsentNotePasses() {
        var note by mutableStateOf<String?>("queued")
        rule.setContent {
            PyrycodeMobileTheme { content(effortNote = note, modelNote = "expected") }
        }
        assertBlocked("Effort", "High") { it == "expected" }
        assertBlocked("Effort", "High") { it == null }
        rule.runOnIdle { note = "expected" }
        rule.awaitRunConfiguration("Effort", "High", 5_000) { it == "expected" }
        rule.runOnIdle { note = null }
        rule.awaitRunConfiguration("Effort", "High", 5_000) { it == null }
    }

    @Test
    fun permissiveNotePredicateCannotAcceptPendingPermission() {
        var pending by mutableStateOf(true)
        rule.setContent { PyrycodeMobileTheme { content(permissionPending = pending) } }
        assertBlocked("Permission", "Plan") { true }
        rule.runOnIdle { pending = false }
        rule.awaitRunConfiguration("Permission", "Plan", 5_000)
    }

    @Test
    fun absentSectionCannotSatisfyEffortPlaceholder() {
        rule.setContent { Text("unrelated") }
        assertBlocked("Effort", "Effort")
    }

    private fun assertBlocked(
        section: String,
        label: String,
        state: ((String?) -> Boolean)? = null,
    ) {
        val failure =
            runCatching {
                if (state == null) {
                    rule.awaitRunConfiguration(section, label, 50)
                } else {
                    rule.awaitRunConfiguration(section, label, 50, state)
                }
            }.exceptionOrNull()
        assertTrue("wait completed before settlement: section=$section", failure is ComposeTimeoutException)
    }

    private fun sheet(
        modelLabel: String,
        selectedEffort: String,
    ) = rule.setContent {
        PyrycodeMobileTheme { content(modelLabel = modelLabel, selectedEffort = selectedEffort) }
    }

    @androidx.compose.runtime.Composable
    private fun content(
        modelLabel: String = "Model",
        selectedEffort: String = "high",
        effortNote: String? = null,
        modelNote: String? = null,
        permissionPending: Boolean = false,
    ) {
        StatusSheetContent(
            choices = listOf(ThreadModelChoice("model", modelLabel, "", emptyList())),
            menuAvailable = true,
            notListedModels = 0,
            selectedModel = "model",
            onModelSelected = {},
            effortChoices = listOf(ThreadEffortChoice("low", "Low"), ThreadEffortChoice("high", "High")),
            selectedEffort = selectedEffort,
            onEffortSelected = {},
            pending = false,
            enabled = true,
            effortNote = effortNote,
            modelSelectionNote = modelNote,
            permissionMode = "plan",
            permissionChoices = listOf("plan" to "Plan"),
            permissionPending = permissionPending,
        )
    }

    private class RecordingEffortStore : RememberedEffortStore {
        var level: String? = null

        override suspend fun read(): String? = level

        override suspend fun remember(level: String) {
            this.level = level
        }
    }

    private class DelayedSettingsRepository(
        val backing: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by backing {
        val readings = MutableStateFlow<SessionSettings?>(settings(""))
        val ack = CompletableDeferred<Unit>()
        var writes = 0
        var refreshes = 0

        init {
            backing.setModelMenu(
                CONVERSATION,
                ModelMenu(listOf(ModelMenuRow("", "default", "default", listOf("low", "high"), false, null)), 0),
            )
        }

        override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
            backing.observeConversations(filter).map { list ->
                list.map { if (it.id == CONVERSATION) it.copy(currentSessionId = SESSION) else it }
            }

        override fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> = readings

        override fun refreshSessionSettings(conversationId: String) {
            refreshes++
        }

        override suspend fun setSessionSettings(
            sessionId: String,
            model: String?,
            effort: String?,
            yolo: Boolean?,
            permissionMode: String?,
        ) {
            writes++
            ack.await()
        }
    }

    private companion object {
        const val CONVERSATION = "seed-channel-personal"
        const val SESSION = "sess-a"

        fun settings(effort: String) =
            SessionSettings(
                sessionId = SESSION,
                model = "",
                effort = effort,
                effectiveEffort = EffectiveEffort.Unavailable,
                permissionMode = "",
                yolo = false,
                usedTokens = 0,
                windowTokens = 0,
            )
    }
}
