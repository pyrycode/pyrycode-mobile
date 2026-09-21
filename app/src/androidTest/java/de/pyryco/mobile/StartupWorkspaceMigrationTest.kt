package de.pyryco.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.model.DEFAULT_SCRATCH_CWD
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.ui.conversations.list.CHANNEL_LIST_TEST_TAG
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class StartupWorkspaceMigrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val store = SavedHosts()
    private val dataStore = GatedPreferences()
    private val preferences = AppPreferences(dataStore)
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var originalPreferences: AppPreferences
    private lateinit var originalStore: PairedServerStore
    private lateinit var originalCollection: PairedServerCollectionStore

    @Before fun bind() {
        val koin = GlobalContext.get()
        originalPreferences = koin.get()
        originalStore = koin.get()
        originalCollection = koin.get()
        loadKoinModules(
            module {
                single { preferences }
                single<PairedServerStore> { store }
                single<PairedServerCollectionStore> { store }
            },
        )
    }

    @After fun close() {
        scenario?.close()
        loadKoinModules(
            module {
                single { originalPreferences }
                single<PairedServerStore> { originalStore }
                single<PairedServerCollectionStore> { originalCollection }
            },
        )
    }

    @Test fun loadingAndMigrationBothGateNavigationAndRestartKeepsExactOwner() {
        val owner = " Host /A "
        store.ids = listOf(owner)
        store.readGate = CompletableDeferred()
        dataStore.writeGate = CompletableDeferred()
        launch()
        assertPending()
        compose.waitUntil(5_000) { store.reads == 1 }
        assertEquals(0, dataStore.updates)
        store.readGate?.complete(Unit)
        compose.waitUntil(5_000) { dataStore.updates == 1 }
        assertPending()
        assertEquals(DEFAULT_SCRATCH_CWD, workspace(owner))
        dataStore.writeGate?.complete(Unit)
        assertChannelList()
        assertEquals("/legacy-private", workspace(owner))
        assertEquals(DEFAULT_SCRATCH_CWD, workspace("Host /A"))
        assertEquals(0, store.latestReads)
        val committed = dataStore.data.value

        store.ids = listOf("later-host")
        restart()
        assertChannelList()
        compose.waitUntil(5_000) { store.reads == 2 }
        assertEquals(committed, dataStore.data.value)
        assertEquals("/legacy-private", workspace(owner))
        assertEquals(DEFAULT_SCRATCH_CWD, workspace("later-host"))
    }

    @Test fun returnedMigrationFailureKeepsNavigationClosedUntilRestartSucceeds() {
        store.ids = listOf("owner")
        dataStore.failWrite = true
        val before = dataStore.data.value
        launch()
        compose.waitUntil(5_000) { dataStore.updates == 1 }
        assertPending()
        assertEquals(before, dataStore.data.value)
        assertEquals(DEFAULT_SCRATCH_CWD, workspace("owner"))
        compose.waitForIdle()
        assertEquals(1, dataStore.updates)
        dataStore.failWrite = false
        restart()
        assertChannelList()
        assertEquals("/legacy-private", workspace("owner"))
    }

    @Test fun zeroInitialHostsCannotTransferLegacyDefaultToLaterPairing() {
        launch()
        compose.onNodeWithText("I already have pyrycode").assertIsDisplayed()
        assertEquals(1, dataStore.updates)
        assertOwnerlessAfterRestart()
    }

    @Test fun multipleInitialHostsCannotTransferLatestHostsLegacyDefault() {
        store.ids = listOf("Host", "host")
        launch()
        assertChannelList()
        assertEquals(DEFAULT_SCRATCH_CWD, workspace("Host"))
        assertEquals(DEFAULT_SCRATCH_CWD, workspace("host"))
        assertEquals(0, store.latestReads)
        assertOwnerlessAfterRestart()
    }

    private fun assertOwnerlessAfterRestart() {
        val committed = dataStore.data.value
        assertTrue(stringPreferencesKey("default_workspace") !in committed)
        store.ids = listOf("later-host")
        restart()
        assertChannelList()
        compose.waitUntil(5_000) { store.reads == 2 }
        assertEquals(committed, dataStore.data.value)
        assertEquals(DEFAULT_SCRATCH_CWD, workspace("later-host"))
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
    }

    private fun restart() {
        scenario?.close()
        launch()
    }

    /**
     * Neither screen yet: the Welcome copy is absent and so is the list itself.
     *
     * Keyed on the list's arrival marker since #738 retired the floating button this read before — and the
     * marker is the stronger check here, because it is on the screen's root rather than on one control that
     * only some draws carried.
     */
    private fun assertPending() {
        compose.onNodeWithText("I already have pyrycode").assertDoesNotExist()
        compose.onNodeWithTag(CHANNEL_LIST_TEST_TAG).assertDoesNotExist()
    }

    private fun assertChannelList() {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag(CHANNEL_LIST_TEST_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(CHANNEL_LIST_TEST_TAG).assertIsDisplayed()
        compose.onNodeWithText("I already have pyrycode").assertDoesNotExist()
    }

    private fun workspace(id: String): String = runBlocking { preferences.defaultWorkspace(id).first() }

    private class GatedPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(preferencesOf(stringPreferencesKey("default_workspace") to "/legacy-private"))

        @Volatile var updates = 0

        @Volatile var failWrite = false
        var writeGate: CompletableDeferred<Unit>? = null

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            updates++
            writeGate?.await()
            if (failWrite) throw IOException("private-test-failure")
            return transform(data.value).also { data.value = it }
        }
    }

    private class SavedHosts : PairedServerCollectionStore {
        var ids = emptyList<String>()
        var readGate: CompletableDeferred<Unit>? = null

        @Volatile var reads = 0

        @Volatile var latestReads = 0

        override suspend fun list(): List<PairedServerEntry> {
            reads++
            readGate?.await()
            return ids.map { PairedServerEntry(record(it), "same display name") }
        }

        override suspend fun load(): PairedServer? {
            latestReads++
            return ids.lastOrNull()?.let(::record)
        }

        override suspend fun loadById(serverId: String): PairedServerEntry? =
            ids.find { it == serverId }?.let { PairedServerEntry(record(it)) }

        override suspend fun save(record: PairedServer) = error("unexpected pairing before startup completed")

        override suspend fun setDisplayName(
            serverId: String,
            displayName: String?,
        ) = Unit

        override suspend fun remove(serverId: String) = Unit

        private fun record(id: String) = PairedServer(id, "synthetic-token", "wss://same.invalid/relay", "synthetic-key")
    }
}
