package de.pyryco.mobile.ui.conversations.components

import de.pyryco.mobile.data.network.RelayErrorException
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.SessionPromptStatus
import de.pyryco.mobile.data.repository.SystemPromptLimit
import de.pyryco.mobile.data.repository.SystemPromptReading
import de.pyryco.mobile.ui.conversations.components.SystemPromptEditorState.Loaded
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * The system-prompt editing state (#824): what a reading loads as, what a save sends, and what a failed
 * save or a failed refresh leaves behind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemPromptEditorTest {
    private val oldSink = RelayLog.sink
    private val oldEnabled = RelayLog.enabled
    private val logs = mutableListOf<String>()

    @Before
    fun captureLogs() {
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message -> logs += message }
    }

    @After
    fun restoreLogs() {
        RelayLog.sink = oldSink
        RelayLog.enabled = oldEnabled
    }

    @Test
    fun loadingUntilTheReadingArrivesAndEditingAndSavingAreInert() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val repo = PromptRepo(reads = mutableListOf(Read.Value(SystemPromptReading("stored", SessionPromptStatus.Matches))))
            repo.readGate = gate
            val editor = editor(repo)
            runCurrent()

            assertEquals(SystemPromptEditorState.Loading, editor.state.value)
            editor.edit("typed")
            editor.save()
            editor.clear()
            runCurrent()
            assertEquals(SystemPromptEditorState.Loading, editor.state.value)
            assertTrue(repo.writes.isEmpty())

            gate.complete(Unit)
            runCurrent()
            assertEquals(Loaded("stored", SessionPromptStatus.Matches, draft = "stored"), editor.state.value)
            assertEquals(listOf(CONVERSATION_ID), repo.readIds)
        }

    @Test
    fun absentEmptyAndTextReadingsLoadDistinctlyWithTheirStatus() =
        runTest {
            val cases =
                listOf(
                    SystemPromptReading(null, SessionPromptStatus.NoSession),
                    SystemPromptReading("", SessionPromptStatus.Differs),
                    SystemPromptReading("be terse", SessionPromptStatus.Matches),
                )
            val loaded =
                cases.map { reading ->
                    val editor = editor(PromptRepo(reads = mutableListOf(Read.Value(reading))))
                    runCurrent()
                    editor.state.value as Loaded
                }

            assertNull(loaded[0].confirmed)
            assertEquals("", loaded[1].confirmed)
            assertEquals("be terse", loaded[2].confirmed)
            assertEquals(listOf("", "", "be terse"), loaded.map { it.draft })
            assertEquals(
                listOf(SessionPromptStatus.NoSession, SessionPromptStatus.Differs, SessionPromptStatus.Matches),
                loaded.map { it.appliedStatus },
            )
            assertTrue(loaded.none { it.saving || it.saveFailed })
        }

    @Test
    fun aFailedReadIsUnavailableAndNothingCanBeWritten() =
        runTest {
            val repo = PromptRepo(reads = mutableListOf(Read.Fail(IOException("down"))))
            val editor = editor(repo)
            runCurrent()

            assertEquals(SystemPromptEditorState.Unavailable, editor.state.value)
            editor.edit("typed")
            editor.save()
            editor.clear()
            runCurrent()
            assertEquals(SystemPromptEditorState.Unavailable, editor.state.value)
            assertTrue(repo.writes.isEmpty())
            assertEquals(1, repo.readIds.size)
        }

    @Test
    fun theDraftCountsUtf8BytesAgainstTheSharedLimit() =
        runTest {
            val editor = loadedEditor(PromptRepo(reads = readings(SystemPromptReading(null, SessionPromptStatus.NoSession))))

            editor.edit("äö€")
            val loaded = editor.state.value as Loaded
            assertEquals(SystemPromptLimit.utf8Bytes("äö€"), loaded.draftBytes)
            assertEquals(7, loaded.draftBytes)
            assertFalse(loaded.overLimit)
        }

    @Test
    fun aDraftAtTheLimitSavesAndOneByteOverSendsNothing() =
        runTest {
            val repo =
                PromptRepo(
                    reads =
                        readings(
                            SystemPromptReading(null, SessionPromptStatus.NoSession),
                            SystemPromptReading(null, SessionPromptStatus.NoSession),
                        ),
                )
            val editor = loadedEditor(repo)

            val over = "€".repeat(2730) + "ab" + "c" // 8190 + 2 + 1 = 8193 bytes
            editor.edit(over)
            val overState = editor.state.value as Loaded
            assertTrue(overState.overLimit)
            assertFalse(overState.canSave)
            editor.save()
            runCurrent()
            assertTrue(repo.writes.isEmpty())

            val atLimit = over.dropLast(1)
            editor.edit(atLimit)
            assertEquals(SystemPromptLimit.MAX_BYTES, (editor.state.value as Loaded).draftBytes)
            editor.save()
            runCurrent()
            assertEquals(listOf<String?>(atLimit), repo.writes)
        }

    @Test
    fun savingSendsTheDraftVerbatimIncludingAnEmptyString() =
        runTest {
            val repo =
                PromptRepo(
                    reads =
                        readings(
                            SystemPromptReading("old", SessionPromptStatus.Matches),
                            SystemPromptReading("", SessionPromptStatus.Differs),
                            SystemPromptReading("", SessionPromptStatus.Differs),
                            SystemPromptReading("  spaced\n", SessionPromptStatus.Differs),
                        ),
                )
            val editor = loadedEditor(repo)

            editor.edit("")
            editor.save()
            runCurrent()
            editor.edit("  spaced\n")
            editor.save()
            runCurrent()

            assertEquals(listOf<String?>("", "  spaced\n"), repo.writes)
            assertEquals("  spaced\n", (editor.state.value as Loaded).confirmed)
        }

    @Test
    fun clearingSendsNullAndEmptiesTheDraft() =
        runTest {
            val repo =
                PromptRepo(
                    reads =
                        readings(
                            SystemPromptReading("", SessionPromptStatus.Matches),
                            SystemPromptReading(null, SessionPromptStatus.Differs),
                        ),
                )
            val editor = loadedEditor(repo)
            assertTrue((editor.state.value as Loaded).canClear)

            editor.clear()
            runCurrent()

            assertEquals(listOf<String?>(null), repo.writes)
            assertEquals(Loaded(null, SessionPromptStatus.Differs, draft = "", saved = true), editor.state.value)
            // Desktop's rule: Clear needs only a settled write, so it stays available with nothing stored.
            assertTrue((editor.state.value as Loaded).canClear)
        }

    @Test
    fun saveAndClearFollowDesktopRulesWhateverTheBoxHolds() =
        runTest {
            // deriveSystemPromptSection: Save needs a settled write and a fitting box; Clear needs a settled write.
            for (stored in listOf(null, "", "kept")) {
                val repo =
                    PromptRepo(
                        reads =
                            readings(
                                SystemPromptReading(stored, SessionPromptStatus.NoSession),
                                SystemPromptReading(stored, SessionPromptStatus.NoSession),
                                SystemPromptReading(null, SessionPromptStatus.NoSession),
                            ),
                    )
                val editor = loadedEditor(repo)
                val loaded = editor.state.value as Loaded
                assertTrue("stored=$stored", loaded.canSave && loaded.canClear)

                // An untouched box is sent verbatim, "" included; Clear then sends null.
                editor.save()
                runCurrent()
                editor.clear()
                runCurrent()

                assertEquals("stored=$stored", listOf(stored.orEmpty(), null), repo.writes)
            }
        }

    @Test
    fun noControlIsAvailableWhileAWriteIsInFlight() =
        runTest {
            val write = CompletableDeferred<Unit>()
            val repo =
                PromptRepo(
                    reads =
                        readings(
                            SystemPromptReading("kept", SessionPromptStatus.Matches),
                            SystemPromptReading("kept", SessionPromptStatus.Matches),
                        ),
                )
            val editor = loadedEditor(repo)
            repo.writeGate = write

            editor.save()
            runCurrent()
            val saving = editor.state.value as Loaded
            assertTrue(saving.saving)
            assertFalse(saving.canSave || saving.canClear)

            write.complete(Unit)
            runCurrent()
            val settled = editor.state.value as Loaded
            assertTrue(settled.saved && settled.canSave && settled.canClear)
        }

    @Test
    fun aRefusedWriteIsClassifiedByItsCodeAndTheNextWriteForgetsIt() =
        runTest {
            val cases =
                listOf(
                    RelayErrorException("protocol.malformed", retryable = false, message = "x") to SystemPromptRefusal.Malformed,
                    // RelayRequests.mapError turns conversation.not_found into an IllegalArgumentException.
                    IllegalArgumentException("Unknown conversation") to SystemPromptRefusal.NotFound,
                    RelayErrorException("conversation.not_found", retryable = false, message = "x") to SystemPromptRefusal.NotFound,
                    RelayErrorException("server.busy", retryable = true, message = "x") to SystemPromptRefusal.Unclassified,
                    IllegalStateException("not connected") to SystemPromptRefusal.Unclassified,
                )
            for ((error, refusal) in cases) {
                val repo =
                    PromptRepo(
                        reads =
                            readings(
                                SystemPromptReading("old", SessionPromptStatus.Matches),
                                SystemPromptReading("new", SessionPromptStatus.Differs),
                            ),
                    )
                val editor = loadedEditor(repo)
                repo.writeFailures += error

                editor.edit("new")
                editor.save()
                runCurrent()
                val failed = editor.state.value as Loaded
                assertTrue(failed.saveFailed)
                assertFalse(failed.saved)
                assertEquals(error.toString(), refusal, failed.refusal)

                editor.save()
                runCurrent()
                val saved = editor.state.value as Loaded
                assertNull(saved.refusal)
                assertFalse(saved.saveFailed)
                assertTrue(saved.saved)
            }
        }

    @Test
    fun aSuccessfulSaveConfirmsTheValueThenRefreshesTheStatus() =
        runTest {
            val refresh = CompletableDeferred<Unit>()
            val repo =
                PromptRepo(
                    reads =
                        readings(
                            SystemPromptReading("old", SessionPromptStatus.Matches),
                            // A concurrent writer's value: only the status is adopted.
                            SystemPromptReading("someone else", SessionPromptStatus.Differs),
                        ),
                )
            val editor = loadedEditor(repo)
            repo.readGate = refresh

            editor.edit("new")
            editor.save()
            runCurrent()
            assertEquals(Loaded("new", null, draft = "new", saving = true, saved = true), editor.state.value)

            refresh.complete(Unit)
            runCurrent()
            assertEquals(Loaded("new", SessionPromptStatus.Differs, draft = "new", saved = true), editor.state.value)
            assertEquals(listOf(CONVERSATION_ID, CONVERSATION_ID), repo.readIds)
            assertEquals(listOf(CONVERSATION_ID), repo.writeIds)
        }

    @Test
    fun aFailedRefreshKeepsTheSavedValueAndLeavesTheStatusUnknown() =
        runTest {
            val repo =
                PromptRepo(
                    reads =
                        mutableListOf(
                            Read.Value(SystemPromptReading("old", SessionPromptStatus.Matches)),
                            Read.Fail(IllegalStateException("disconnected")),
                        ),
                )
            val editor = loadedEditor(repo)

            editor.edit("new")
            editor.save()
            runCurrent()

            assertEquals(Loaded("new", null, draft = "new", saved = true), editor.state.value)
        }

    @Test
    fun aFailedSaveKeepsTheDraftAndTheConfirmedValueAndARetrySendsTheSameDraft() =
        runTest {
            val repo =
                PromptRepo(
                    reads =
                        readings(
                            SystemPromptReading("old", SessionPromptStatus.Matches),
                            SystemPromptReading("new", SessionPromptStatus.Differs),
                        ),
                )
            val editor = loadedEditor(repo)
            repo.writeFailures += IOException("dropped")

            editor.edit("new")
            editor.save()
            runCurrent()
            assertEquals(
                Loaded(
                    "old",
                    SessionPromptStatus.Matches,
                    draft = "new",
                    saveFailed = true,
                    refusal = SystemPromptRefusal.Unclassified,
                ),
                editor.state.value,
            )

            editor.save()
            runCurrent()
            assertEquals(listOf<String?>("new", "new"), repo.writes)
            assertEquals(Loaded("new", SessionPromptStatus.Differs, draft = "new", saved = true), editor.state.value)
        }

    @Test
    fun aSecondTapWhileSavingSendsNothingAndTypingMeanwhileSurvivesTheCompletion() =
        runTest {
            val write = CompletableDeferred<Unit>()
            val repo =
                PromptRepo(
                    reads =
                        readings(
                            SystemPromptReading(null, SessionPromptStatus.NoSession),
                            SystemPromptReading("first", SessionPromptStatus.NoSession),
                        ),
                )
            val editor = loadedEditor(repo)
            repo.writeGate = write

            editor.edit("first")
            editor.save()
            runCurrent()
            editor.save()
            editor.clear()
            editor.edit("second")
            write.complete(Unit)
            runCurrent()

            assertEquals(listOf<String?>("first"), repo.writes)
            assertEquals(Loaded("first", SessionPromptStatus.NoSession, draft = "second", saved = true), editor.state.value)
        }

    @Test
    fun twoEditorsForOneConversationOnTwoHostsUseOnlyTheirOwnRepository() =
        runTest {
            val hostA =
                PromptRepo(
                    reads =
                        readings(
                            SystemPromptReading("a", SessionPromptStatus.Matches),
                            SystemPromptReading("a2", SessionPromptStatus.Differs),
                        ),
                )
            val hostB = PromptRepo(reads = readings(SystemPromptReading(null, SessionPromptStatus.NoSession)))
            val editorA = editor(hostA)
            val editorB = editor(hostB)
            runCurrent()

            assertEquals("a", (editorA.state.value as Loaded).confirmed)
            assertNull((editorB.state.value as Loaded).confirmed)

            editorA.edit("a2")
            editorA.save()
            runCurrent()

            assertEquals(listOf<String?>("a2"), hostA.writes)
            assertTrue(hostB.writes.isEmpty())
            assertEquals(2, hostA.readIds.size)
            assertEquals(1, hostB.readIds.size)
            assertEquals(Loaded(null, SessionPromptStatus.NoSession, draft = ""), editorB.state.value)
        }

    @Test
    fun neitherLogsNorToStringCarryThePromptItsLengthOrTheConversationId() =
        runTest {
            val secret = "sk-secret-prompt-text"
            val repo =
                PromptRepo(
                    reads =
                        mutableListOf(
                            Read.Value(SystemPromptReading(secret, SessionPromptStatus.Matches)),
                            Read.Fail(IllegalStateException(secret)),
                        ),
                )
            val editor = loadedEditor(repo)
            repo.writeFailures += IllegalArgumentException(secret)
            editor.edit("$secret-2")
            editor.save()
            runCurrent()
            editor.save()
            runCurrent()
            val failedRead = editor(PromptRepo(reads = mutableListOf(Read.Fail(IOException(secret)))))
            runCurrent()

            assertEquals(SystemPromptEditorState.Unavailable, failedRead.state.value)
            assertTrue(logs.isNotEmpty())
            val lengths = listOf(secret.length, secret.length + 2).map { it.toString() }
            for (line in logs) {
                assertFalse(line, line.contains(secret))
                assertFalse(line, line.contains(CONVERSATION_ID))
                assertTrue(line, lengths.none { line.contains(it) })
            }
            val rendered = editor.state.value.toString()
            assertFalse(rendered, rendered.contains(secret))
        }

    private fun TestScope.editor(repo: ConversationRepository) = SystemPromptEditor(backgroundScope, repo, CONVERSATION_ID)

    private fun TestScope.loadedEditor(repo: ConversationRepository): SystemPromptEditor {
        val editor = editor(repo)
        runCurrent()
        check(editor.state.value is Loaded)
        return editor
    }

    private fun readings(vararg values: SystemPromptReading) = values.mapTo(mutableListOf<Read>()) { Read.Value(it) }

    private sealed interface Read {
        data class Value(
            val reading: SystemPromptReading,
        ) : Read

        data class Fail(
            val error: Exception,
        ) : Read
    }

    /** Scripts the two prompt members; every other member is the in-memory fake's. */
    private class PromptRepo(
        val reads: MutableList<Read>,
    ) : ConversationRepository by FakeConversationRepository() {
        val readIds = mutableListOf<String>()
        val writeIds = mutableListOf<String>()
        val writes = mutableListOf<String?>()
        val writeFailures = mutableListOf<Exception>()
        var readGate: CompletableDeferred<Unit>? = null
        var writeGate: CompletableDeferred<Unit>? = null

        override suspend fun requestSystemPrompt(conversationId: String): SystemPromptReading {
            readIds += conversationId
            readGate?.await()
            return when (val next = reads.removeAt(0)) {
                is Read.Value -> next.reading
                is Read.Fail -> throw next.error
            }
        }

        override suspend fun setSystemPrompt(
            conversationId: String,
            systemPrompt: String?,
        ) {
            writeIds += conversationId
            writes += systemPrompt
            writeGate?.await()
            if (writeFailures.isNotEmpty()) throw writeFailures.removeAt(0)
        }
    }

    private companion object {
        const val CONVERSATION_ID = "conv-7f3a"
    }
}
