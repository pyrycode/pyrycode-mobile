package de.pyryco.mobile.ui.conversations.thread

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.repository.AnnouncedModel
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.EffectiveEffort
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.SessionFacts
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #891: the Status sheet shows the model claude announced and claude's build, kept apart from the selected
 * model. Both are claude-authored, so they arrive inert; the claimed permission posture is never read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThreadViewModelRunningModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun anAnnouncedModel_isShownApartFromADifferentSavedOverride() =
        runTest {
            val repo = ScriptedRepo()
            repo.settings.value = settings(model = "sonnet")
            repo.announce(CONV, AnnouncedModel("claude-opus-4-7", truncated = false))
            repo.report(CONV, facts(version = "2.1.3"))
            val vm = collectedVm(repo)

            val config = vm.state.value.runConfig
            assertEquals(ThreadReportedText("claude-opus-4-7", truncated = false), config.running.model)
            assertEquals(ThreadReportedText("2.1.3", truncated = false), config.running.build)
            assertEquals("sonnet", config.selectedModel)
        }

    @Test
    fun anAnnouncedModel_isShownWhileTheSavedOverrideIsTheInheritedDefault() =
        runTest {
            val repo = ScriptedRepo()
            repo.settings.value = settings(model = "")
            repo.announce(CONV, AnnouncedModel("claude-opus-4-7", truncated = false))
            val vm = collectedVm(repo)

            val config = vm.state.value.runConfig
            assertEquals("claude-opus-4-7", config.running.model?.text)
            assertEquals("", config.selectedModel)
            assertEquals(UNAVAILABLE_MODEL_LABEL, config.modelLabel)
        }

    @Test
    fun beforeAnyAnnouncement_theRunningModelIsUnavailable_notTheSelection() =
        runTest {
            val repo = ScriptedRepo()
            repo.settings.value = settings(model = "sonnet")
            val vm = collectedVm(repo)

            assertEquals(ThreadRunningModel(model = null, build = null), vm.state.value.runConfig.running)
        }

    @Test
    fun aClearedReading_returnsToUnavailable() =
        runTest {
            val repo = ScriptedRepo()
            repo.settings.value = settings(model = "sonnet")
            repo.announce(CONV, AnnouncedModel("claude-opus-4-7", truncated = false))
            repo.report(CONV, facts(version = "2.1.3"))
            val vm = collectedVm(repo)
            assertEquals(
                "claude-opus-4-7",
                vm.state.value.runConfig.running.model
                    ?.text,
            )

            // What the repository does on the conversation's session_transition.
            repo.models.update { it - CONV }
            repo.facts.update { it - CONV }

            assertEquals(ThreadRunningModel(), vm.state.value.runConfig.running)
        }

    @Test
    fun anEmptyBuild_hidesTheBuildLine() =
        runTest {
            val repo = ScriptedRepo()
            repo.announce(CONV, AnnouncedModel("claude-opus-4-7", truncated = false))
            repo.report(CONV, facts(version = ""))
            val vm = collectedVm(repo)

            assertNull(vm.state.value.runConfig.running.build)
        }

    @Test
    fun theDaemonsTruncationFlags_markBothValues() =
        runTest {
            val repo = ScriptedRepo()
            repo.announce(CONV, AnnouncedModel("claude-opus-4", truncated = true))
            repo.report(CONV, facts(version = "2.1", truncatedFields = listOf("claude_code_version")))
            val vm = collectedVm(repo)

            val running = vm.state.value.runConfig.running
            assertEquals(ThreadReportedText("claude-opus-4", truncated = true), running.model)
            assertEquals(ThreadReportedText("2.1", truncated = true), running.build)
        }

    @Test
    fun onlyTheVersionsOwnTruncationFlag_marksTheBuild() =
        runTest {
            val repo = ScriptedRepo()
            repo.report(CONV, facts(version = "2.1.3", truncatedFields = listOf("permission_mode")))
            val vm = collectedVm(repo)

            assertEquals(ThreadReportedText("2.1.3", truncated = false), vm.state.value.runConfig.running.build)
        }

    @Test
    fun anotherConversationsReading_isNeverShown() =
        runTest {
            val repo = ScriptedRepo()
            repo.announce(OTHER_CONV, AnnouncedModel("claude-haiku-4-5", truncated = false))
            repo.report(OTHER_CONV, facts(version = "9.9.9"))
            val first = collectedVm(repo, OTHER_CONV)
            assertEquals(
                "claude-haiku-4-5",
                first.state.value.runConfig.running.model
                    ?.text,
            )

            val switched = collectedVm(repo, CONV)

            assertEquals(ThreadRunningModel(), switched.state.value.runConfig.running)
        }

    @Test
    fun theClaimedPermissionPosture_neverReachesThePermissionReading() =
        runTest {
            val repo = ScriptedRepo()
            repo.settings.value = settings(model = "", permissionMode = "default")
            repo.report(CONV, facts(version = "2.1.3", permissionMode = "bypassPermissions"))
            val vm = collectedVm(repo)

            val config = vm.state.value.runConfig
            assertEquals("default", config.permissionMode)
            assertEquals(PermissionModeOption.fromWire("default")?.label, permissionModeLabel(config))
        }

    @Test
    fun hostileText_isRenderedInert() {
        assertEquals(ThreadReportedText("opus[31m", truncated = false), reportedText("\u001bopus\u001b[31m\n", truncated = false))
        assertNull(reportedText("\u001b\n\t", truncated = false))
    }

    @Test
    fun aValueCutByTheInertBound_isMarkedTruncated() {
        val long = "m".repeat(200)

        val reported = reportedText(long, truncated = false)

        assertEquals(128, reported?.text?.length)
        assertTrue(reported?.truncated == true)
        assertFalse(reportedText("m".repeat(128), truncated = false)?.truncated == true)
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private fun TestScope.collectedVm(
        repo: ScriptedRepo,
        conversationId: String = CONV,
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

    private fun settings(
        model: String,
        permissionMode: String = "",
    ) = SessionSettings(
        sessionId = SESSION,
        model = model,
        effort = "",
        effectiveEffort = EffectiveEffort.Unavailable,
        permissionMode = permissionMode,
        yolo = false,
        usedTokens = 0,
        windowTokens = 0,
    )

    private fun facts(
        version: String,
        permissionMode: String = "",
        truncatedFields: List<String>? = null,
    ) = SessionFacts(claudeCodeVersion = version, permissionMode = permissionMode, truncatedFields = truncatedFields)

    /** Both #890 readings as per-conversation maps, the shape of the repository's own projections. */
    private class ScriptedRepo(
        private val backing: FakeConversationRepository = FakeConversationRepository(),
    ) : ConversationRepository by backing {
        val settings = MutableStateFlow<SessionSettings?>(null)
        val models = MutableStateFlow<Map<String, AnnouncedModel>>(emptyMap())
        val facts = MutableStateFlow<Map<String, SessionFacts>>(emptyMap())

        fun announce(
            conversationId: String,
            model: AnnouncedModel,
        ) = models.update { it + (conversationId to model) }

        fun report(
            conversationId: String,
            reading: SessionFacts,
        ) = facts.update { it + (conversationId to reading) }

        override fun observeSessionSettings(conversationId: String): Flow<SessionSettings?> = settings

        /** Every conversation's live session is the reading's, so `forLiveSession` keeps the permission mode. */
        override fun observeConversations(filter: ConversationFilter): Flow<List<Conversation>> =
            backing.observeConversations(filter).map { list -> list.map { it.copy(currentSessionId = SESSION) } }

        override fun observeAnnouncedModel(conversationId: String): Flow<AnnouncedModel?> =
            models.map { it[conversationId] }.distinctUntilChanged()

        override fun observeSessionFacts(conversationId: String): Flow<SessionFacts?> =
            facts.map { it[conversationId] }.distinctUntilChanged()
    }

    private companion object {
        const val CONV = "seed-channel-personal"
        const val OTHER_CONV = "seed-channel-pyrycode-mobile"
        const val SESSION = "sess-a"
    }
}
