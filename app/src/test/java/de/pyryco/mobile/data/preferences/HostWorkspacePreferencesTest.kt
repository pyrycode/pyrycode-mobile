package de.pyryco.mobile.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HostWorkspacePreferencesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var file: File
    private lateinit var job: Job
    private lateinit var store: DataStore<Preferences>
    private lateinit var prefs: AppPreferences
    private val originalSink = RelayLog.sink
    private val originalEnabled = RelayLog.enabled
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        RelayLog.enabled = true
        RelayLog.sink = { _, _, message ->
            synchronized(logs) { logs.add(message) }
            Unit
        }
        file = tmp.newFile("workspace.preferences_pb")
        openStore()
    }

    @After
    fun tearDown() {
        runBlocking { job.cancelAndJoin() }
        RelayLog.sink = originalSink
        RelayLog.enabled = originalEnabled
    }

    private fun openStore() {
        job = Job()
        store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
        prefs = AppPreferences(store)
    }

    private suspend fun reopen() {
        job.cancelAndJoin()
        openStore()
    }

    private fun TestScope.observe(flow: Flow<String>): Channel<String> {
        val values = Channel<String>(Channel.UNLIMITED)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.distinctUntilChanged().collect { values.send(it) }
        }
        return values
    }

    @Test
    fun exactIdsRemainIsolatedAndAppWidePreferencesSurviveReopening() =
        runTest {
            prefs.setThemeMode(ThemeMode.DARK)
            prefs.setUseWallpaperColors(true)
            prefs.setDefaultModel(Model.HAIKU_4_5)
            prefs.setDefaultEffort(Effort.LOW)
            prefs.setDefaultYolo(true)
            prefs.setNotificationsEnabled(false)
            prefs.setPushToken("test-push-token")
            val globalValues = store.data.first().asMap()
            prefs.setDefaultWorkspace("/legacy")
            val ids = listOf("server-A", "server-a", " server-A", "server-A ", "server-A/path", "server-A:path")
            for ((index, id) in ids.withIndex()) {
                assertEquals(DEFAULT_SCRATCH_CWD, prefs.defaultWorkspace(id).first())
                prefs.setDefaultWorkspace(id, "/host/$index").getOrThrow()
            }
            prefs.removeDefaultWorkspace(ids.first()).getOrThrow()
            assertEquals("/legacy", prefs.defaultWorkspace.first())
            reopen()
            for ((index, id) in ids.withIndex()) {
                assertEquals(if (index == 0) DEFAULT_SCRATCH_CWD else "/host/$index", prefs.defaultWorkspace(id).first())
            }
            prefs.migrateDefaultWorkspace(ids.toSet()).getOrThrow()
            prefs.setDefaultWorkspace(ids.first(), "/changed").getOrThrow()
            prefs.removeDefaultWorkspace(ids.first()).getOrThrow()
            reopen()
            for ((index, id) in ids.withIndex()) {
                assertEquals(if (index == 0) DEFAULT_SCRATCH_CWD else "/host/$index", prefs.defaultWorkspace(id).first())
            }
            val persisted = store.data.first().asMap()
            globalValues.forEach { (key, value) -> assertEquals(value, persisted[key]) }
        }

    @Test
    fun hostAndLegacyCollectorsFollowOwnerUpdatesAndRemoval() =
        runTest {
            val legacy = observe(prefs.defaultWorkspace)
            val host = observe(prefs.defaultWorkspace("owner"))
            assertEquals(DEFAULT_SCRATCH_CWD, legacy.receive())
            assertEquals(DEFAULT_SCRATCH_CWD, host.receive())
            prefs.setDefaultWorkspace("/legacy")
            assertEquals("/legacy", legacy.receive())
            prefs.setDefaultWorkspace("owner", "/before").getOrThrow()
            assertEquals("/before", host.receive())
            assertEquals("/legacy", prefs.defaultWorkspace.first())
            prefs.removeDefaultWorkspace("owner").getOrThrow()
            assertEquals(DEFAULT_SCRATCH_CWD, host.receive())
            prefs.migrateDefaultWorkspace(setOf("owner")).getOrThrow()
            assertEquals("/legacy", host.receive())
            prefs.setDefaultWorkspace("owner", "/host-update").getOrThrow()
            assertEquals("/host-update", host.receive())
            assertEquals("/host-update", legacy.receive())
            prefs.setDefaultWorkspace("/compatibility-update")
            assertEquals("/compatibility-update", host.receive())
            assertEquals("/compatibility-update", legacy.receive())
            prefs.removeDefaultWorkspace("owner").getOrThrow()
            assertEquals(DEFAULT_SCRATCH_CWD, host.receive())
            assertEquals(DEFAULT_SCRATCH_CWD, legacy.receive())
            prefs.migrateDefaultWorkspace(setOf("later-host")).getOrThrow()
            assertEquals(DEFAULT_SCRATCH_CWD, prefs.defaultWorkspace("later-host").first())
        }

    @Test
    fun migrationMatrixPersistsOwnerWithAbsentOrPresentLegacyAndExistingHostValues() =
        runTest {
            for (legacy in listOf(null, "/legacy")) {
                for (existing in listOf(null, "/existing", DEFAULT_SCRATCH_CWD)) {
                    job.cancelAndJoin()
                    file = tmp.newFile("matrix-${legacy != null}-${existing?.length}.preferences_pb")
                    openStore()
                    if (legacy != null) prefs.setDefaultWorkspace(legacy)
                    if (existing != null) prefs.setDefaultWorkspace("owner", existing).getOrThrow()
                    reopen()
                    prefs.migrateDefaultWorkspace(setOf("owner")).getOrThrow()
                    val expected = existing ?: legacy ?: DEFAULT_SCRATCH_CWD
                    assertEquals(expected, prefs.defaultWorkspace("owner").first())
                    assertEquals(expected, prefs.defaultWorkspace.first())
                    reopen()
                    prefs.migrateDefaultWorkspace(setOf("later-host")).getOrThrow()
                    assertEquals(expected, prefs.defaultWorkspace.first())
                    assertEquals(expected, prefs.defaultWorkspace("owner").first())
                    assertEquals(DEFAULT_SCRATCH_CWD, prefs.defaultWorkspace("later-host").first())
                    prefs.setDefaultWorkspace("/alias")
                    assertEquals("/alias", prefs.defaultWorkspace("owner").first())
                    prefs.removeDefaultWorkspace("owner").getOrThrow()
                    reopen()
                    prefs.migrateDefaultWorkspace(setOf("owner")).getOrThrow()
                    assertEquals(DEFAULT_SCRATCH_CWD, prefs.defaultWorkspace.first())
                    assertEquals(DEFAULT_SCRATCH_CWD, prefs.defaultWorkspace("owner").first())
                    prefs.setDefaultWorkspace("/still-owned")
                    reopen()
                    assertEquals("/still-owned", prefs.defaultWorkspace("owner").first())
                }
            }
        }

    @Test
    fun zeroOrMultipleInitialHostsPermanentlyDisableLegacyWrites() =
        runTest {
            for (hosts in listOf(emptySet(), setOf("A", "B"))) {
                for (legacy in listOf(null, "/legacy")) {
                    job.cancelAndJoin()
                    file = tmp.newFile("ownerless-${hosts.size}-${legacy != null}.preferences_pb")
                    openStore()
                    if (legacy != null) prefs.setDefaultWorkspace(legacy)
                    prefs.migrateDefaultWorkspace(hosts).getOrThrow()
                    assertEquals(DEFAULT_SCRATCH_CWD, prefs.defaultWorkspace.first())
                    val migrated = store.data.first()
                    prefs.setDefaultWorkspace("/ignored")
                    assertEquals(migrated, store.data.first())
                    reopen()
                    prefs.migrateDefaultWorkspace(setOf("A")).getOrThrow()
                    assertEquals(DEFAULT_SCRATCH_CWD, prefs.defaultWorkspace("A").first())
                    prefs.setDefaultWorkspace("A", "/explicit").getOrThrow()
                    prefs.setDefaultWorkspace("/ignored-again")
                    reopen()
                    assertEquals("/explicit", prefs.defaultWorkspace("A").first())
                    assertEquals(DEFAULT_SCRATCH_CWD, prefs.defaultWorkspace.first())
                }
            }
        }

    @Test
    fun migrationPublishesOwnerAndTransferInOneTransaction() =
        runTest {
            prefs.setDefaultWorkspace("/legacy")
            var updates = 0
            val tracking =
                object : DataStore<Preferences> {
                    override val data: Flow<Preferences> = store.data

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                        updates++
                        return store.updateData(transform)
                    }
                }
            AppPreferences(tracking).migrateDefaultWorkspace(setOf("owner")).getOrThrow()
            assertEquals(1, updates)
            reopen()
            assertEquals("/legacy", prefs.defaultWorkspace("owner").first())
            prefs.setDefaultWorkspace("/alias")
            assertEquals("/alias", prefs.defaultWorkspace("owner").first())
        }

    @Test
    fun concurrentMigrationCallsChooseOnePermanentOwner() =
        runTest {
            prefs.setDefaultWorkspace("/legacy")
            listOf("A", "B").map { id -> async { prefs.migrateDefaultWorkspace(setOf(id)).getOrThrow() } }.awaitAll()
            val values = listOf(prefs.defaultWorkspace("A").first(), prefs.defaultWorkspace("B").first())
            assertEquals(1, values.count { it == "/legacy" })
            assertEquals(1, values.count { it == DEFAULT_SCRATCH_CWD })
            val owner = if (values.first() == "/legacy") "A" else "B"
            reopen()
            prefs.migrateDefaultWorkspace(setOf("C")).getOrThrow()
            prefs.setDefaultWorkspace("/alias")
            assertEquals("/alias", prefs.defaultWorkspace(owner).first())
            assertEquals(DEFAULT_SCRATCH_CWD, prefs.defaultWorkspace("C").first())
        }

    @Test
    fun failedMigrationCommitsNeitherDecisionNorTransferAndCanRetry() =
        runTest {
            prefs.setDefaultWorkspace("/legacy-private")
            val before = store.data.first()
            val failing = AppPreferences(rejectingStore(IOException("private failure")))
            assertTrue(failing.migrateDefaultWorkspace(setOf("private-owner")).exceptionOrNull() is IOException)
            assertEquals(before, store.data.first())
            assertEquals("/legacy-private", prefs.defaultWorkspace.first())
            assertEquals(DEFAULT_SCRATCH_CWD, prefs.defaultWorkspace("private-owner").first())
            reopen()
            prefs.migrateDefaultWorkspace(setOf("retry-owner")).getOrThrow()
            assertEquals("/legacy-private", prefs.defaultWorkspace("retry-owner").first())
            assertEquals(DEFAULT_SCRATCH_CWD, prefs.defaultWorkspace("private-owner").first())
            assertTrue(logs.any { "io_failure" in it })
            assertTrue(logs.none { "private" in it || "retry-owner" in it })
        }

    @Test
    fun writesAndRemovalReturnIoFailuresWithoutChangingValues() =
        runTest {
            prefs.setDefaultWorkspace("A", "/original").getOrThrow()
            val failing = AppPreferences(rejectingStore(IOException("private failure")))
            assertTrue(failing.setDefaultWorkspace("A", "/new").isFailure)
            assertTrue(failing.removeDefaultWorkspace("A").isFailure)
            reopen()
            assertEquals("/original", prefs.defaultWorkspace("A").first())
        }

    @Test
    fun cancellationPropagatesWithoutRecordingMigration() =
        runTest {
            val cancelled = AppPreferences(rejectingStore(CancellationException("cancelled")))
            try {
                cancelled.migrateDefaultWorkspace(setOf("A"))
                throw AssertionError("Cancellation must propagate")
            } catch (_: CancellationException) {
                prefs.setDefaultWorkspace("/legacy")
                prefs.migrateDefaultWorkspace(setOf("B")).getOrThrow()
                assertEquals("/legacy", prefs.defaultWorkspace("B").first())
            }
        }

    private fun rejectingStore(failure: Exception): DataStore<Preferences> =
        object : DataStore<Preferences> {
            override val data: Flow<Preferences> = store.data

            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                store.updateData { current ->
                    transform(current)
                    throw failure
                }
        }
}
