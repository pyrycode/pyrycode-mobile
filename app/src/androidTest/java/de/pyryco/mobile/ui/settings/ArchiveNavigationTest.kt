package de.pyryco.mobile.ui.settings

import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.PyryNavHost
import de.pyryco.mobile.Routes
import de.pyryco.mobile.data.crypto.DeviceStaticKeyPair
import de.pyryco.mobile.data.crypto.DeviceStaticKeyStore
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.network.NoiseClientInfo
import de.pyryco.mobile.data.network.RelayTransportFactory
import de.pyryco.mobile.data.network.base64StdEncode
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.di.ObservablePairedServerStore
import de.pyryco.mobile.di.RelayConnectionFactory
import de.pyryco.mobile.di.RelayConnectionRegistry
import de.pyryco.mobile.di.appModule
import de.pyryco.mobile.di.conversationRepositoryModule
import de.pyryco.mobile.ui.conversations.thread.NavigationPeer
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.core.KoinApplication
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * The Archive destination on the production graph, bindings and `Routes` (#715).
 *
 * Both hosts hold an archived conversation under the **same** id, because host-local ids are the
 * whole reason this destination needs an owner: a screen that followed compatibility selection would
 * look correct on one host and silently restore the other's row.
 *
 * Mounts `PyryNavHost` directly rather than the Activity, so — like `SettingsNavigationTest`, whose
 * harness this copies — it proves route ownership and cannot prove the startup gate.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: KoinApplication
    private lateinit var registry: RelayConnectionRegistry
    private lateinit var store: ObservablePairedServerStore
    private lateinit var nav: NavHostController

    private val peers = mutableMapOf<String, NavigationPeer>()
    private val stored = MutableStateFlow(emptyPreferences() as Preferences)

    @After fun close() {
        if (::app.isInitialized) app.close()
        if (::registry.isInitialized) registry.dispose()
    }

    /**
     * The owner captured from Settings outlives a compatibility-selection change, a saved-state
     * restoration and a Back, and reopening under the other host captures afresh. Alpha's id carries
     * reserved characters, so the path segment is proven encoded rather than assumed.
     */
    @Test fun archiveKeepsItsCapturedOwnerAcrossSelectionChangeAndRestoration() {
        val restoration = start()
        select(ALPHA_ID)
        openArchive()
        assertOwner(ALPHA_ID)
        assertShowsOnly(ALPHA_ARCHIVED, other = BRAVO_ARCHIVED)

        select(BRAVO_ID)
        assertOwner(ALPHA_ID)
        assertShowsOnly(ALPHA_ARCHIVED, other = BRAVO_ARCHIVED)

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertOwner(ALPHA_ID)
        assertShowsOnly(ALPHA_ARCHIVED, other = BRAVO_ARCHIVED)

        // Back twice: Archive -> Settings, Settings -> list. Reopening under Bravo captures Bravo.
        compose.runOnIdle { nav.popBackStack() }
        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        openArchive()
        assertOwner(BRAVO_ID)
        assertShowsOnly(BRAVO_ARCHIVED, other = ALPHA_ARCHIVED)
    }

    /**
     * Restore reaches the owner's repository and no other: Bravo's peer sees no archive verb at all,
     * and its identically-numbered row is still archived afterwards. The row leaves the list only on
     * the owner's `conversation_updated` confirmation — the send alone never removes it.
     */
    @Test fun restoreReachesOnlyTheOwnerAndLeavesTheOtherHostsMatchingIdArchived() {
        start()
        select(ALPHA_ID)
        openArchive()
        assertOwner(ALPHA_ID)
        // Point compatibility selection at Bravo before restoring, so a restore that followed
        // selection would land on Bravo's identically-numbered row instead of Alpha's.
        select(BRAVO_ID)

        compose.onNodeWithContentDescription("Restore $ALPHA_ARCHIVED").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Restored $ALPHA_ARCHIVED").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(ALPHA_ARCHIVED).fetchSemanticsNodes().isEmpty()
        }
        compose.runOnIdle {
            assertEquals(listOf(SHARED_ID), unarchived(ALPHA_ID))
            assertEquals(emptyList<String>(), unarchived(BRAVO_ID))
        }

        // Bravo's matching id is untouched: open its own archive and find the row still there.
        compose.runOnIdle { nav.popBackStack() }
        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        select(BRAVO_ID)
        openArchive()
        assertOwner(BRAVO_ID)
        assertShowsOnly(BRAVO_ARCHIVED, other = ALPHA_ARCHIVED)
    }

    /**
     * Removing the owner while Archive is open leaves the destination rather than falling through to
     * the other host's rows. Asserted on the departure, not on the absence of Bravo's row: under an
     * unknown owner the host-bound repository emits an empty list, which renders as a plausible
     * "no archived discussions" — a guard that failed to fire would look like a correct empty archive.
     */
    @Test fun removingTheOwnerLeavesTheDestinationRatherThanShowingAnotherHost() {
        start()
        select(ALPHA_ID)
        openArchive()
        assertOwner(ALPHA_ID)

        compose.runOnIdle { runBlocking { store.remove(ALPHA_ID) } }
        compose.waitUntil(5_000) { nav.currentDestination?.route == Routes.CHANNEL_LIST }
        compose.onNodeWithText(BRAVO_ARCHIVED).assertDoesNotExist()
    }

    /**
     * The channel list's own archive entry — the second door onto this destination (#737) — opens on
     * the selected host. It needs its own case because the Settings door cannot stand in for it: the
     * two capture their owner from different sources, and only this one reads selection. Nor can
     * `ChannelListScreenTest` stand in, which asserts that the tap emits `ArchiveTapped` and never
     * where that lands. Before the repair this navigated to the route *pattern*, so the literal text
     * `{serverId}` bound as the owner and `HostDestination` bounced the tap back to the list it came
     * from — an unconditional operator-facing affordance that went nowhere.
     */
    @Test fun theChannelListsArchiveEntryOpensTheSelectedHostsArchive() {
        start()
        select(ALPHA_ID)
        openArchiveFromTheList()
        assertOwner(ALPHA_ID)
        assertShowsOnly(ALPHA_ARCHIVED, other = BRAVO_ARCHIVED)

        // Where Settings' door hands on the owner its own destination already holds, this one reads
        // selection afresh on every tap — so a later selection change opens the other host's archive.
        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        select(BRAVO_ID)
        openArchiveFromTheList()
        assertOwner(BRAVO_ID)
        assertShowsOnly(BRAVO_ARCHIVED, other = ALPHA_ARCHIVED)
    }

    // --- helpers ---

    /** The archive entry on the channel list's own bar, the door beside the settings gear. */
    private fun openArchiveFromTheList() {
        compose.onNodeWithContentDescription("Open archive").performClick()
        compose.waitUntil(5_000) { nav.currentDestination?.route == Routes.ARCHIVED_DISCUSSIONS }
        compose.waitForIdle()
    }

    /** Through the real Settings row, so the capture under test is the production one. */
    private fun openArchive() {
        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.waitUntil(5_000) { nav.currentDestination?.route == Routes.SETTINGS }
        compose.onNodeWithText("Archived discussions").performScrollTo().performClick()
        compose.waitUntil(5_000) { nav.currentDestination?.route == Routes.ARCHIVED_DISCUSSIONS }
        compose.waitForIdle()
    }

    private fun assertOwner(serverId: String) {
        compose.runOnIdle {
            assertEquals(serverId, Routes.archiveOwner(nav.currentBackStackEntry?.arguments))
        }
    }

    private fun assertShowsOnly(
        owned: String,
        other: String,
    ) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(owned).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(other).assertDoesNotExist()
    }

    private fun unarchived(serverId: String) =
        peers
            .getValue(serverId)
            .outbound
            .filter { it.type == "unarchive_conversation" }
            .map {
                it.payload
                    .toString()
                    .substringAfter("\"conversation_id\":\"")
                    .substringBefore('"')
            }

    /** Re-saving a record makes it the latest, which is what compatibility selection follows. */
    private fun select(serverId: String) {
        compose.runOnIdle { runBlocking { store.save(store.loadById(serverId)!!.record) } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(registry.connectionFor(serverId), registry.selected.value) }
    }

    private fun start(): StateRestorationTester {
        val serverKey = NavigationPeer.key()
        val deviceKey = NavigationPeer.key()
        val raw =
            object : PairedServerCollectionStore {
                var entries =
                    listOf(ALPHA_ID to ALPHA_NAME, BRAVO_ID to BRAVO_NAME).map { (id, name) ->
                        PairedServerEntry(
                            PairedServer(id, "unused", relayFor(id), base64StdEncode(serverKey.publicKey)),
                            name,
                        )
                    }

                override suspend fun list() = entries

                override suspend fun load() = entries.lastOrNull()?.record

                override suspend fun loadById(serverId: String) = entries.find { it.record.serverId == serverId }

                override suspend fun save(record: PairedServer) {
                    val held = entries.find { it.record.serverId == record.serverId }?.displayName
                    entries = entries.filterNot { it.record.serverId == record.serverId } + PairedServerEntry(record, held)
                }

                override suspend fun remove(serverId: String) {
                    entries = entries.filterNot { it.record.serverId == serverId }
                }

                override suspend fun setDisplayName(
                    serverId: String,
                    displayName: String?,
                ) {
                    entries = entries.map { if (it.record.serverId == serverId) it.copy(displayName = displayName) else it }
                }
            }
        store = ObservablePairedServerStore(raw)
        val keys =
            object : DeviceStaticKeyStore {
                override suspend fun loadOrCreate(serverId: String) =
                    DeviceStaticKeyPair(deviceKey.publicKey.copyOf(), deviceKey.privateKey.copyOf())

                override suspend fun publicKey(serverId: String) = deviceKey.publicKey.copyOf()
            }
        registry =
            RelayConnectionRegistry(
                store,
                RelayConnectionFactory(
                    keys,
                    RelayTransportFactory { record ->
                        // Both hosts hand out the SAME archived conversation id: ids are host-local,
                        // and a collision is the case an owner-less Archive gets wrong.
                        NavigationPeer(record.serverId, "unused", serverKey, SHARED_ID)
                            .also { peers[record.serverId] = it }
                    },
                    NoiseClientInfo("test", "test"),
                    dispatcher = Dispatchers.Main.immediate,
                    ioDispatcher = Dispatchers.Main.immediate,
                ),
                Dispatchers.Main.immediate,
            )
        registry.connect()
        val preferences =
            AppPreferences(
                object : DataStore<Preferences> {
                    override val data = stored

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                        transform(stored.value).also { stored.value = it }
                },
            )
        app =
            KoinApplication.init().modules(
                appModule,
                conversationRepositoryModule(true),
                module {
                    single { registry }
                    single { store } binds arrayOf(PairedServerStore::class, PairedServerCollectionStore::class)
                    single { preferences }
                },
            )
        return StateRestorationTester(compose).also { tester ->
            tester.setContent {
                KoinIsolatedContext(app) {
                    PyrycodeMobileTheme {
                        nav = rememberNavController()
                        PyryNavHost(Routes.CHANNEL_LIST, navController = nav)
                    }
                }
            }
            compose.waitForIdle()
        }
    }

    private companion object {
        // Reserved characters in the owner's id, as the thread routes' test uses: here the id
        // travels in a required path segment, and nothing else proves that encoding round-trips.
        const val ALPHA_ID = "A /?#%"
        const val BRAVO_ID = "B"
        const val ALPHA_NAME = "Alpha"
        const val BRAVO_NAME = "Bravo"

        /** One id, two hosts — the collision the owner exists to disambiguate. */
        const val SHARED_ID = "shared-archived-id"
        const val ALPHA_ARCHIVED = "$ALPHA_ID archived"
        const val BRAVO_ARCHIVED = "$BRAVO_ID archived"

        fun relayFor(serverId: String) = if (serverId == BRAVO_ID) "wss://bravo.example" else "wss://alpha.example"
    }
}
