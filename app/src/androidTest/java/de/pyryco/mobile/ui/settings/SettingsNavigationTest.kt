package de.pyryco.mobile.ui.settings

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import kotlinx.coroutines.flow.flowOf
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
 * The Settings destination on the production graph, bindings and `Routes` (#749).
 *
 * Mounts `PyryNavHost` directly rather than the Activity, so — like `LiteralScreenNavigationTest`,
 * whose harness this copies — it proves route ownership and cannot prove the startup gate.
 */
@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: KoinApplication
    private lateinit var registry: RelayConnectionRegistry
    private lateinit var store: ObservablePairedServerStore
    private lateinit var nav: NavHostController

    private val peers = mutableMapOf<String, NavigationPeer>()

    @After fun close() {
        if (::app.isInitialized) app.close()
        if (::registry.isInitialized) registry.dispose()
    }

    /**
     * The captured owner outlives a compatibility-selection change, a saved-state restoration and a
     * Back; reopening captures afresh. Bravo's supervisor is closed first, so a screen that followed
     * selection could not keep showing a connected relay after the flip.
     */
    @Test fun settingsKeepsItsCapturedOwnerAcrossSelectionChangeAndRestoration() {
        val restoration = start(live = true)
        select(ALPHA_ID)
        openSettings()
        assertOwner(ALPHA_ID)
        assertShowsAlphaConnected()

        compose.runOnIdle { registry.connectionFor(BRAVO_ID)!!.supervisor.close() }
        compose.waitForIdle()
        select(BRAVO_ID)
        assertOwner(ALPHA_ID)
        assertShowsAlphaConnected()

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertOwner(ALPHA_ID)
        assertShowsAlphaConnected()

        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        openSettings()
        assertOwner(BRAVO_ID)
        compose.onNodeWithText(BRAVO_NAME).assertIsDisplayed()
        compose.onNodeWithText(ALPHA_NAME).assertDoesNotExist()
    }

    /**
     * Neither non-owned state borrows another host's identity, and neither closes the screen — the
     * `HostDestination` guard the thread routes use would have returned both of these to the list.
     */
    @Test fun unknownAndAbsentOwnersKeepSettingsOpenWithoutNamingAnotherHost() {
        start()
        compose.runOnIdle { nav.navigate(Routes.settings("ghost")) }
        awaitSettings()
        assertOwner("ghost")
        compose.onNodeWithText("This host is no longer paired").assertIsDisplayed()
        assertNoHostIdentityOnScreen()
        // Still Settings, and the app-wide sections below it still draw.
        compose.runOnIdle { assertEquals(Routes.SETTINGS, nav.currentDestination?.route) }
        compose.onNodeWithText("Use Material You dynamic color").performScrollTo().assertIsDisplayed()

        compose.runOnIdle {
            nav.popBackStack()
            runBlocking {
                store.remove(ALPHA_ID)
                store.remove(BRAVO_ID)
            }
        }
        compose.waitForIdle()
        openSettings()
        assertOwner("")
        compose.onNodeWithText("No host is paired").assertIsDisplayed()
        assertNoHostIdentityOnScreen()
        // Usability of the pairing entry is asserted here and its target in the diff: clicking it
        // would compose the camera destination and raise a system permission dialog, and
        // `SettingsScreenTest` already pins that the click reaches the callback.
        compose.onNodeWithText("Pair another server").assertIsDisplayed().assertHasClickAction()
    }

    private fun openSettings() {
        compose.onNodeWithContentDescription("Open settings").performClick()
        awaitSettings()
    }

    private fun awaitSettings() {
        compose.waitUntil(5_000) { nav.currentDestination?.route == Routes.SETTINGS }
        compose.waitForIdle()
    }

    private fun assertOwner(serverId: String) {
        compose.runOnIdle {
            assertEquals(serverId, Routes.settingsOwner(nav.currentBackStackEntry?.arguments))
        }
    }

    /** Alpha's four facts, and none of Bravo's — including a status only Alpha's host can have. */
    private fun assertShowsAlphaConnected() {
        compose.waitUntil(5_000) {
            compose
                .onAllNodesWithContentDescription("Relay: connected")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(ALPHA_NAME).assertIsDisplayed()
        compose.onNodeWithText(ALPHA_ID).assertIsDisplayed()
        compose.onNodeWithText(ALPHA_RELAY).assertIsDisplayed()
        compose.onNodeWithContentDescription("Pyrycode: connected").assertIsDisplayed()
        compose.onNodeWithText(BRAVO_NAME).assertDoesNotExist()
        compose.onNodeWithText(BRAVO_RELAY).assertDoesNotExist()
    }

    private fun assertNoHostIdentityOnScreen() {
        compose.onNodeWithText(ALPHA_NAME).assertDoesNotExist()
        compose.onNodeWithText(BRAVO_NAME).assertDoesNotExist()
        compose.onNodeWithText(ALPHA_RELAY).assertDoesNotExist()
        compose.onNodeWithText(BRAVO_RELAY).assertDoesNotExist()
        compose.onNodeWithText(ALPHA_ID).assertDoesNotExist()
    }

    /** Re-saving a record makes it the latest, which is what compatibility selection follows. */
    private fun select(serverId: String) {
        compose.runOnIdle { runBlocking { store.save(store.loadById(serverId)!!.record) } }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(registry.connectionFor(serverId), registry.selected.value) }
    }

    private fun start(live: Boolean = false): StateRestorationTester {
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

                // Preserves the local name, as the real store's contract requires — otherwise a
                // selection flip would silently blank the very identity under assertion.
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
                        check(live) { "must not dial" }
                        NavigationPeer(record.serverId, "unused", serverKey).also { peers[record.serverId] = it }
                    },
                    NoiseClientInfo("test", "test"),
                    dispatcher = Dispatchers.Main.immediate,
                    ioDispatcher = Dispatchers.Main.immediate,
                ),
                Dispatchers.Main.immediate,
            )
        if (live) registry.connect()
        val preferences =
            AppPreferences(
                object : DataStore<Preferences> {
                    override val data = flowOf(emptyPreferences())

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(emptyPreferences())
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
        // travels in a query parameter rather than a path segment, and nothing else proves that
        // encoding round-trips.
        const val ALPHA_ID = "A /?#%"
        const val BRAVO_ID = "B"
        const val ALPHA_NAME = "Alpha"
        const val BRAVO_NAME = "Bravo"
        const val ALPHA_RELAY = "wss://alpha.example"
        const val BRAVO_RELAY = "wss://bravo.example"

        fun relayFor(serverId: String) = if (serverId == BRAVO_ID) BRAVO_RELAY else ALPHA_RELAY
    }
}
