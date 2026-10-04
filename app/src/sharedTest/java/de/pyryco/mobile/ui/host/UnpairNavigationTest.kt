package de.pyryco.mobile.ui.host

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.PyryNavHost
import de.pyryco.mobile.Routes
import de.pyryco.mobile.data.cache.ConversationCache
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
import de.pyryco.mobile.di.InertAttachmentStore
import de.pyryco.mobile.di.InertConversationCache
import de.pyryco.mobile.di.ObservablePairedServerStore
import de.pyryco.mobile.di.RelayConnectionFactory
import de.pyryco.mobile.di.RelayConnectionRegistry
import de.pyryco.mobile.di.appModule
import de.pyryco.mobile.di.conversationRepositoryModule
import de.pyryco.mobile.ui.conversations.thread.NavigationPeer
import de.pyryco.mobile.ui.settings.SettingsViewModel
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.core.KoinApplication
import org.koin.dsl.binds
import org.koin.dsl.module

/** #1323: a successful unpair that leaves no saved host returns to Welcome, from either editor owner. */
@RunWith(AndroidJUnit4::class)
class UnpairNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: KoinApplication
    private lateinit var registry: RelayConnectionRegistry
    private lateinit var nav: NavHostController
    private val stored = MutableStateFlow(preferencesOf(booleanPreferencesKey("notification_permission_asked") to true))

    @After fun close() {
        if (::app.isInitialized) app.close()
        if (::registry.isInitialized) registry.dispose()
    }

    @Test fun unpairingTheLastHostFromTheChannelListShowsWelcomeWithNothingBehindIt() {
        start("alpha")
        unpairFromList("alpha")
        assertWelcomeAlone()
    }

    @Test fun unpairingTheLastHostFromSettingsShowsWelcomeWithNothingBehindIt() {
        start("alpha")
        compose.onNodeWithContentDescription("Open menu").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.waitUntil(5_000) { nav.currentDestination?.route == Routes.SETTINGS }
        // Settings has drawn no host editor since #1239, so its own view model is driven directly.
        val vm = compose.runOnIdle { ViewModelProvider(nav.getBackStackEntry(Routes.SETTINGS))[SettingsViewModel::class.java] }
        compose.runOnIdle { vm.openOwnerHostEditor() }
        compose.waitUntil(5_000) { vm.hostEditor.value != null }
        compose.runOnIdle {
            vm.requestHostUnpair()
            vm.confirmHostUnpair()
        }
        assertWelcomeAlone()
    }

    @Test fun unpairingOneOfTwoHostsStaysOnTheList() {
        start("alpha", "beta")
        unpairFromList("alpha")
        compose.waitUntil(5_000) { count("Edit host alpha") == 0 }
        compose.waitForIdle()
        assertEquals(Routes.CHANNEL_LIST, nav.currentDestination?.route)
        compose.onNodeWithContentDescription("Edit host beta").assertExists()
    }

    private fun unpairFromList(name: String) {
        compose.waitUntil(5_000) { count("Edit host $name") == 1 }
        compose.onNodeWithContentDescription("Edit host $name").performClick()
        compose.onNodeWithText("Unpair host").performClick()
        compose.onNodeWithText("Unpair host?").assertExists()
        compose.onNodeWithText("OK").performClick()
    }

    private fun assertWelcomeAlone() {
        compose.waitUntil(5_000) { nav.currentDestination?.route == Routes.WELCOME }
        compose.runOnIdle { assertNull(nav.previousBackStackEntry) }
    }

    private fun count(description: String) = compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().size

    private fun start(vararg names: String) {
        val deviceKey = NavigationPeer.key()
        val raw =
            object : PairedServerCollectionStore {
                var entries =
                    names.map { name ->
                        PairedServerEntry(
                            PairedServer(name, "unused", "wss://$name.example", base64StdEncode(NavigationPeer.key().publicKey)),
                            name,
                        )
                    }

                override suspend fun list() = entries

                override suspend fun load() = entries.lastOrNull()?.record

                override suspend fun loadById(serverId: String) = entries.find { it.record.serverId == serverId }

                override suspend fun save(record: PairedServer) {
                    entries = entries.filterNot { it.record.serverId == record.serverId } + PairedServerEntry(record, null)
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
        val store = ObservablePairedServerStore(raw) { }
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
                    RelayTransportFactory { error("Unpair must not dial") },
                    NoiseClientInfo("test", "test"),
                    dispatcher = Dispatchers.Main.immediate,
                    ioDispatcher = Dispatchers.Main.immediate,
                ),
                Dispatchers.Main.immediate,
            )
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
                    single<ConversationCache> { InertConversationCache }
                    single { InertAttachmentStore }
                },
            )
        compose.setContent {
            KoinIsolatedContext(app) {
                PyrycodeMobileTheme(darkTheme = true) {
                    nav = rememberNavController()
                    PyryNavHost(Routes.CHANNEL_LIST, navController = nav)
                }
            }
        }
        compose.waitForIdle()
    }
}
