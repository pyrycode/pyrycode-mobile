package de.pyryco.mobile.ui.settings

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
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
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

/** The real Settings route and channel-list entry, with in-memory preference storage. */
@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: KoinApplication
    private lateinit var registry: RelayConnectionRegistry
    private lateinit var nav: NavHostController
    private lateinit var preferences: AppPreferences
    private val stored = MutableStateFlow(preferencesOf(booleanPreferencesKey("notification_permission_asked") to true))

    @After fun close() {
        if (::app.isInitialized) app.close()
        if (::registry.isInitialized) registry.dispose()
    }

    @Test fun entryAndActionsReturnToThePreviousView() {
        start()
        openSettings()
        compose.onNodeWithText("Done").performClick()
        assertOnList()

        openSettings()
        compose.onNodeWithContentDescription("Close").performClick()
        assertOnList()

        compose.onNodeWithContentDescription("Open archive").performClick()
        compose.runOnIdle { assertEquals(Routes.ARCHIVED_DISCUSSIONS, nav.currentDestination?.route) }
    }

    @Test fun pushPreferenceSurvivesReopeningTheModal() {
        start()
        openSettings()
        compose
            .onAllNodes(switch)[0]
            .assertIsOn()
            .performClick()
            .assertIsOff()
        compose.waitUntil(5_000) { runBlocking { !preferences.notificationsEnabled.first() } }
        compose.onNodeWithText("Done").performClick()
        openSettings()
        compose.onAllNodes(switch)[0].assertIsOff()
    }

    @Test fun collapseToolUsesPreferenceSurvivesReopeningTheModal() {
        start()
        openSettings()
        compose
            .onAllNodes(switch)[1]
            .assertIsOn()
            .performClick()
            .assertIsOff()
        compose.waitUntil(5_000) { runBlocking { !preferences.collapseToolUses.first() } }
        compose.onNodeWithText("Done").performClick()
        openSettings()
        compose.onAllNodes(switch)[1].assertIsOff()
        compose.onAllNodes(switch)[0].assertIsOn()
    }

    @Test fun unpairedPhoneCanStillDismissSettings() {
        start(withHost = false)
        openSettings()
        compose.onNodeWithText("Notifications").assertExists()
        compose.onNodeWithText("Done").performClick()
        assertOnList()
    }

    private fun openSettings() {
        compose.onNodeWithContentDescription("Open settings").performClick()
        compose.waitUntil(5_000) { nav.currentDestination?.route == Routes.SETTINGS }
    }

    private fun assertOnList() {
        compose.waitForIdle()
        assertEquals(Routes.CHANNEL_LIST, nav.currentDestination?.route)
    }

    private fun start(withHost: Boolean = true) {
        val serverKey = NavigationPeer.key()
        val deviceKey = NavigationPeer.key()
        val raw =
            object : PairedServerCollectionStore {
                var entries =
                    if (withHost) {
                        listOf(
                            PairedServerEntry(
                                PairedServer("host", "unused", "wss://host.example", base64StdEncode(serverKey.publicKey)),
                                "Host",
                            ),
                        )
                    } else {
                        emptyList()
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
                    RelayTransportFactory { error("Settings must not dial") },
                    NoiseClientInfo("test", "test"),
                    dispatcher = Dispatchers.Main.immediate,
                    ioDispatcher = Dispatchers.Main.immediate,
                ),
                Dispatchers.Main.immediate,
            )
        preferences =
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

    private val switch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
}
