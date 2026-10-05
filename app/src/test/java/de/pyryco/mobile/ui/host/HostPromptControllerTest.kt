package de.pyryco.mobile.ui.host

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.HostSystemPromptReading
import de.pyryco.mobile.data.repository.SystemPromptLimit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HostPromptControllerTest {
    private val previousEnabled = RelayLog.enabled

    @Before fun silenceAndroidLog() {
        RelayLog.enabled = false
    }

    @After fun restoreLog() {
        RelayLog.enabled = previousEnabled
    }

    @Test fun unreadValuesCannotBeEditedResetOrWritten() =
        runTest {
            val repo = Repo().apply { readGate = CompletableDeferred() }
            val controller = controller(repo)
            controller.open("A")
            runCurrent()
            assertEquals(HostPromptState.Loading, controller.state.value?.prompt)
            controller.onPromptEvent(HostPromptEvent.Open)
            controller.onPromptEvent(HostPromptEvent.Edit("overwrite"))
            controller.onPromptEvent(HostPromptEvent.Reset)
            controller.onPromptEvent(HostPromptEvent.Save)
            assertTrue(repo.writes.isEmpty())
            repo.readGate?.complete(Unit)
            runCurrent()
            assertEquals("saved", loaded(controller).confirmed)
        }

    @Test fun readFailuresAndReopenRetryKeepEmptyDistinct() =
        runTest {
            val repo = Repo().apply { failRead = true }
            var current: ConversationRepository? = repo
            val controller = controller { current }
            controller.open("A")
            runCurrent()
            assertEquals(HostPromptState.Unavailable, controller.state.value?.prompt)
            controller.onPromptEvent(HostPromptEvent.Open)
            controller.onPromptEvent(HostPromptEvent.Save)
            assertTrue(repo.writes.isEmpty())
            current = null
            controller.open("A")
            runCurrent()
            assertEquals(HostPromptState.Unavailable, controller.state.value?.prompt)
            current = repo
            repo.failRead = false
            val oversizedReadings =
                listOf(
                    HostSystemPromptReading("x".repeat(8193), ""),
                    HostSystemPromptReading("", "x".repeat(8193)),
                )
            for (reading in oversizedReadings) {
                repo.reading = reading
                controller.open("A")
                runCurrent()
                assertEquals(HostPromptState.Unavailable, controller.state.value?.prompt)
            }
            repo.reading = HostSystemPromptReading("", "daemon default")
            controller.open("A")
            runCurrent()
            assertEquals("", loaded(controller).confirmed)
            assertEquals("daemon default", loaded(controller).defaultPrompt)
        }

    @Test fun resetDiscardsWithoutWritingAndVerbatimSaveUsesAcknowledgedPreview() =
        runTest {
            val repo = Repo()
            val controller = controller(repo)
            controller.open("A")
            runCurrent()
            controller.onPromptEvent(HostPromptEvent.Open)
            assertTrue(loaded(controller).showReset)
            controller.onPromptEvent(HostPromptEvent.Reset)
            assertEquals("default", loaded(controller).draft)
            assertFalse(loaded(controller).showReset)
            controller.onPromptEvent(HostPromptEvent.Discard)
            assertFalse(requireNotNull(controller.state.value).editingPrompt)
            assertTrue(repo.writes.isEmpty())
            controller.onPromptEvent(HostPromptEvent.Open)
            assertEquals("saved", loaded(controller).draft)
            val verbatim = "  line one\nline two  \n"
            controller.onPromptEvent(HostPromptEvent.Edit(verbatim))
            controller.onPromptEvent(HostPromptEvent.Save)
            runCurrent()
            assertEquals(listOf(verbatim), repo.writes)
            assertEquals(verbatim, loaded(controller).confirmed)
            assertFalse(requireNotNull(controller.state.value).editingPrompt)
            controller.dismiss()
            assertEquals(verbatim, repo.reading.systemPrompt)
        }

    @Test fun inclusiveUtf8LimitKeepsOversizedDraftEditableAndEmptyIsAValue() =
        runTest {
            val repo = Repo()
            val controller = controller(repo)
            controller.open("A")
            runCurrent()
            controller.onPromptEvent(HostPromptEvent.Open)
            val atLimit = "é".repeat(SystemPromptLimit.MAX_BYTES / 2)
            controller.onPromptEvent(HostPromptEvent.Edit(atLimit + "x"))
            assertTrue(loaded(controller).overLimit)
            controller.onPromptEvent(HostPromptEvent.Save)
            assertTrue(repo.writes.isEmpty())
            controller.onPromptEvent(HostPromptEvent.Edit(atLimit))
            assertTrue(loaded(controller).canSave)
            controller.onPromptEvent(HostPromptEvent.Save)
            runCurrent()
            assertEquals(atLimit, repo.writes.single())
            controller.onPromptEvent(HostPromptEvent.Open)
            controller.onPromptEvent(HostPromptEvent.Edit(""))
            controller.onPromptEvent(HostPromptEvent.Save)
            runCurrent()
            assertEquals("", repo.writes.last())
            assertEquals("", loaded(controller).confirmed)
        }

    @Test fun failureAndMissingConnectionRetainDraftThenRetryOnReplacementRepository() =
        runTest {
            val original = Repo().apply { failWrite = true }
            var live: ConversationRepository? = original
            val lookups = mutableListOf<String>()
            val controller =
                controller { id ->
                    lookups += id
                    live
                }
            controller.open("A")
            runCurrent()
            controller.onPromptEvent(HostPromptEvent.Open)
            controller.onPromptEvent(HostPromptEvent.Edit("draft"))
            controller.onPromptEvent(HostPromptEvent.Save)
            runCurrent()
            assertTrue(loaded(controller).failed)
            assertEquals("draft", loaded(controller).draft)
            live = null
            controller.onPromptEvent(HostPromptEvent.Save)
            runCurrent()
            assertTrue(loaded(controller).failed)
            assertTrue(requireNotNull(controller.state.value).editingPrompt)
            val replacement = Repo().apply { ackDefault = "new default" }
            live = replacement
            controller.onPromptEvent(HostPromptEvent.Save)
            runCurrent()
            assertEquals(listOf("draft"), replacement.writes)
            assertEquals("new default", loaded(controller).defaultPrompt)
            assertTrue(lookups.all { it == "A" })
        }

    @Test fun lateNonCooperativeReadCannotOverwriteNewHost() =
        runTest {
            val a =
                Repo().apply {
                    readGate = CompletableDeferred()
                    ignoreCancellation = true
                }
            val b = Repo().apply { reading = HostSystemPromptReading("B saved", "B default") }
            val controller = controller { id -> if (id == "A") a else b }
            controller.open("A")
            runCurrent()
            controller.dismiss()
            controller.open("B")
            runCurrent()
            a.readGate?.complete(Unit)
            runCurrent()
            assertEquals("B", controller.state.value?.serverId)
            assertEquals("B saved", loaded(controller).draft)
            controller.dismiss()
            assertNull(controller.state.value)
        }

    @Test fun duplicateAndLateWritesCannotReopenEditors() =
        runTest {
            val a = Repo().apply { writeGate = CompletableDeferred() }
            val b = Repo()
            val controller = controller { id -> if (id == "A") a else b }
            controller.open("A")
            runCurrent()
            controller.onPromptEvent(HostPromptEvent.Open)
            controller.onPromptEvent(HostPromptEvent.Edit("first"))
            controller.onPromptEvent(HostPromptEvent.Save)
            controller.onPromptEvent(HostPromptEvent.Save)
            runCurrent()
            assertEquals(listOf("first"), a.writes)
            assertTrue(loaded(controller).saving)
            controller.onPromptEvent(HostPromptEvent.Discard)
            controller.onPromptEvent(HostPromptEvent.Open)
            controller.onPromptEvent(HostPromptEvent.Edit("new draft"))
            a.writeGate?.complete(Unit)
            runCurrent()
            assertEquals("new draft", loaded(controller).draft)
            assertTrue(requireNotNull(controller.state.value).editingPrompt)
            a.writeGate = CompletableDeferred()
            controller.onPromptEvent(HostPromptEvent.Save)
            runCurrent()
            controller.dismiss()
            controller.open("B")
            runCurrent()
            a.writeGate?.complete(Unit)
            runCurrent()
            assertEquals("B", controller.state.value?.serverId)
            assertEquals("saved", loaded(controller).confirmed)
            assertTrue(b.writes.isEmpty())
        }

    @Test fun stateEventsAndLogsRedactEveryPromptAndRawFailure() =
        runTest {
            val logs = mutableListOf<String>()
            val oldSink = RelayLog.sink
            val oldEnabled = RelayLog.enabled
            RelayLog.enabled = true
            RelayLog.sink = { _, _, message -> logs += message }
            try {
                val repo =
                    Repo().apply {
                        reading = HostSystemPromptReading("secret-current", "secret-default")
                        failWrite = true
                    }
                val controller = controller(repo)
                controller.open("A")
                runCurrent()
                controller.onPromptEvent(HostPromptEvent.Open)
                val event = HostPromptEvent.Edit("secret-draft")
                controller.onPromptEvent(event)
                controller.onPromptEvent(HostPromptEvent.Save)
                runCurrent()
                assertFalse(
                    controller.state.value
                        .toString()
                        .contains("secret"),
                )
                assertFalse(event.toString().contains("secret"))
                assertTrue(logs.isNotEmpty())
                assertTrue(logs.none { "secret" in it })
            } finally {
                RelayLog.sink = oldSink
                RelayLog.enabled = oldEnabled
            }
        }

    @Test fun failedRenameRetainsSuccessfulPromptRead() = outerWriteFailureRetainsPromptRead(unpair = false, readFails = false)

    @Test fun failedRenameRetainsUnavailablePromptRead() = outerWriteFailureRetainsPromptRead(unpair = false, readFails = true)

    @Test fun failedUnpairRetainsSuccessfulPromptRead() = outerWriteFailureRetainsPromptRead(unpair = true, readFails = false)

    @Test fun failedUnpairRetainsUnavailablePromptRead() = outerWriteFailureRetainsPromptRead(unpair = true, readFails = true)

    private fun outerWriteFailureRetainsPromptRead(
        unpair: Boolean,
        readFails: Boolean,
    ) = runTest {
        val repo =
            Repo().apply {
                readGate = CompletableDeferred()
                failRead = readFails
            }
        val store =
            Store().apply {
                writeGate = CompletableDeferred()
                failWrite = true
            }
        val controller = HostEditorController(this, store, preferences()) { repo }
        controller.open("A")
        runCurrent()
        assertEquals(HostPromptState.Loading, controller.state.value?.prompt)
        if (unpair) {
            controller.requestUnpair()
            controller.confirmUnpair()
        } else {
            controller.submitName("New name")
        }
        runCurrent()
        assertTrue(requireNotNull(controller.state.value).saving)
        repo.readGate?.complete(Unit)
        runCurrent()
        assertTrue(requireNotNull(controller.state.value).saving)
        store.writeGate?.complete(Unit)
        runCurrent()
        val failed = requireNotNull(controller.state.value)
        assertFalse(failed.saving)
        assertEquals(!unpair, failed.failed)
        assertEquals(unpair, failed.unpairFailed)
        if (unpair) controller.declineUnpair()
        controller.onPromptEvent(HostPromptEvent.Open)
        assertTrue(requireNotNull(controller.state.value).editingPrompt)
        if (readFails) {
            assertEquals(HostPromptState.Unavailable, controller.state.value?.prompt)
            controller.onPromptEvent(HostPromptEvent.Edit("overwrite"))
            controller.onPromptEvent(HostPromptEvent.Reset)
            controller.onPromptEvent(HostPromptEvent.Save)
            runCurrent()
            assertTrue(repo.writes.isEmpty())
        } else {
            assertEquals("saved", loaded(controller).confirmed)
            assertEquals("default", loaded(controller).defaultPrompt)
            controller.onPromptEvent(HostPromptEvent.Edit("after failure"))
            controller.onPromptEvent(HostPromptEvent.Save)
            runCurrent()
            assertEquals(listOf("after failure"), repo.writes)
            assertEquals("after failure", loaded(controller).confirmed)
            controller.submitName("Retry name")
            runCurrent()
            assertEquals("after failure", loaded(controller).confirmed)
        }
    }

    @Test fun completedPromptReadDoesNotPreventSuccessfulRenameOrUnpair() =
        runTest {
            for (unpair in listOf(false, true)) {
                val repo = Repo().apply { readGate = CompletableDeferred() }
                val store = Store().apply { writeGate = CompletableDeferred() }
                val controller = HostEditorController(this, store, preferences()) { repo }
                controller.open("A")
                runCurrent()
                if (unpair) {
                    controller.requestUnpair()
                    controller.confirmUnpair()
                } else {
                    controller.submitName("New name")
                }
                runCurrent()
                repo.readGate?.complete(Unit)
                runCurrent()
                store.writeGate?.complete(Unit)
                runCurrent()
                assertNull(controller.state.value)
            }
        }

    @Test fun lateOuterWritesCannotCloseOrFailReopenedSameHost() =
        runTest {
            for (unpair in listOf(false, true)) {
                for (fails in listOf(false, true)) {
                    val repo = Repo().apply { readGate = CompletableDeferred() }
                    val oldWrite = CompletableDeferred<Unit>()
                    val newWrite = CompletableDeferred<Unit>()
                    val store =
                        Store().apply {
                            writeGate = oldWrite
                            failWrite = fails
                        }
                    val controller = HostEditorController(this, store, preferences()) { repo }

                    fun startWrite() {
                        if (unpair) {
                            controller.requestUnpair()
                            controller.confirmUnpair()
                        } else {
                            controller.submitName("New name")
                        }
                    }
                    controller.open("A")
                    runCurrent()
                    startWrite()
                    runCurrent()
                    repo.readGate?.complete(Unit)
                    runCurrent()
                    controller.dismiss()
                    repo.readGate = CompletableDeferred()
                    store.writeGate = newWrite
                    controller.open("A")
                    runCurrent()
                    startWrite()
                    runCurrent()
                    oldWrite.complete(Unit)
                    runCurrent()
                    val newer = requireNotNull(controller.state.value)
                    assertTrue(newer.saving)
                    assertFalse(newer.failed || newer.unpairFailed)
                    assertEquals(HostPromptState.Loading, newer.prompt)
                    repo.readGate?.complete(Unit)
                    newWrite.complete(Unit)
                    runCurrent()
                    if (fails) {
                        assertEquals("saved", loaded(controller).confirmed)
                    } else {
                        assertNull(controller.state.value)
                    }
                }
            }
        }

    private fun loaded(controller: HostEditorController) = controller.state.value?.prompt as HostPromptState.Loaded

    private fun kotlinx.coroutines.test.TestScope.controller(repo: Repo) = controller { repo }

    private fun kotlinx.coroutines.test.TestScope.controller(resolve: (String) -> ConversationRepository?) =
        HostEditorController(this, Store(), preferences(), resolve)

    private class Repo : ConversationRepository by FakeConversationRepository() {
        var reading = HostSystemPromptReading("saved", "default")
        var ackDefault: String? = null
        var failRead = false
        var failWrite = false
        var ignoreCancellation = false
        var readGate: CompletableDeferred<Unit>? = null
        var writeGate: CompletableDeferred<Unit>? = null
        val writes = mutableListOf<String>()

        override suspend fun requestHostSystemPrompt(): Result<HostSystemPromptReading> {
            if (ignoreCancellation) withContext(NonCancellable) { readGate?.await() } else readGate?.await()
            return if (failRead) Result.failure(IllegalStateException("secret-read")) else Result.success(reading)
        }

        override suspend fun setHostSystemPrompt(systemPrompt: String): Result<HostSystemPromptReading> {
            writes += systemPrompt
            writeGate?.await()
            if (failWrite) return Result.failure(IllegalStateException("secret-write"))
            reading = HostSystemPromptReading(systemPrompt, ackDefault ?: reading.defaultSystemPrompt)
            return Result.success(reading)
        }
    }

    private class Store : PairedServerCollectionStore {
        var writeGate: CompletableDeferred<Unit>? = null
        var failWrite = false

        override suspend fun loadById(serverId: String): PairedServerEntry {
            val record = PairedServer(serverId, "token", "wss://relay", "key")
            return PairedServerEntry(record, "Name")
        }

        override suspend fun load() = null

        override suspend fun list() = emptyList<PairedServerEntry>()

        override suspend fun save(record: PairedServer) = Unit

        override suspend fun setDisplayName(
            serverId: String,
            displayName: String?,
        ) = write()

        override suspend fun remove(serverId: String) = write()

        private suspend fun write() {
            writeGate?.await()
            if (failWrite) throw IllegalStateException("secret-store-failure")
        }
    }

    private fun preferences() =
        AppPreferences(
            object : DataStore<Preferences> {
                override val data = MutableStateFlow(emptyPreferences())

                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                    val updated = transform(data.value)
                    data.value = updated
                    return updated
                }
            },
        )
}
