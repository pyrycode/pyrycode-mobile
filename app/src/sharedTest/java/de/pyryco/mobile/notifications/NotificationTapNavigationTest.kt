package de.pyryco.mobile.notifications

import androidx.compose.ui.test.junit4.createComposeRule
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
import de.pyryco.mobile.di.InertConversationCache
import de.pyryco.mobile.di.ObservablePairedServerStore
import de.pyryco.mobile.di.RelayConnectionFactory
import de.pyryco.mobile.di.RelayConnectionRegistry
import de.pyryco.mobile.di.appModule
import de.pyryco.mobile.di.conversationRepositoryModule
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import de.pyryco.mobile.ui.conversations.thread.NavigationPeer
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
 * A notification tap's target on the production graph and `Routes` (#685): a saved host's conversation
 * opens above the channel list, and anything else stays on the list. Mounts `PyryNavHost` directly, as
 * `LiteralScreenNavigationTest` does, so the intent parse is covered by `AttentionNotifierTest` instead.
 *
 * The permission prompt is marked as already asked, so the channel list never raises the system dialog
 * on a device run.
 */
@RunWith(AndroidJUnit4::class)
class NotificationTapNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: KoinApplication
    private lateinit var registry: RelayConnectionRegistry
    private lateinit var nav: NavHostController

    @After fun close() {
        if (::app.isInitialized) app.close()
        if (::registry.isInitialized) registry.dispose()
    }

    @Test fun aSavedHostsTapOpensItsThreadAboveTheChannelList() {
        val target = HostConversationTarget(SAVED, "conv /?#%")
        start(target)

        compose.waitUntil(5_000) { nav.currentDestination?.route == Routes.CONVERSATION_THREAD }
        compose.runOnIdle {
            assertEquals(target, Routes.target(nav.currentBackStackEntry?.arguments))
            // A conversation deleted since the alert still leaves the list one Back away.
            nav.popBackStack()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(Routes.CHANNEL_LIST, nav.currentDestination?.route) }
    }

    @Test fun anUnsavedHostsTapStaysOnTheChannelList() {
        start(HostConversationTarget("forged", "conv"))

        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(Routes.CHANNEL_LIST, nav.currentDestination?.route)
            assertEquals(null, nav.previousBackStackEntry)
        }
    }

    private fun start(target: HostConversationTarget) {
        val serverKey = NavigationPeer.key()
        val deviceKey = NavigationPeer.key()
        val entries = listOf(PairedServerEntry(PairedServer(SAVED, "unused", "wss://unused.example", base64StdEncode(serverKey.publicKey))))
        val raw =
            object : PairedServerCollectionStore {
                override suspend fun list() = entries

                override suspend fun load() = entries.lastOrNull()?.record

                override suspend fun loadById(serverId: String) = entries.find { it.record.serverId == serverId }

                override suspend fun save(record: PairedServer) = Unit

                override suspend fun remove(serverId: String) = Unit

                override suspend fun setDisplayName(
                    serverId: String,
                    displayName: String?,
                ) = Unit
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
                    RelayTransportFactory { error("must not dial") },
                    NoiseClientInfo("test", "test"),
                    dispatcher = Dispatchers.Main.immediate,
                    ioDispatcher = Dispatchers.Main.immediate,
                ),
                Dispatchers.Main.immediate,
            )
        val stored = MutableStateFlow(preferencesOf(booleanPreferencesKey("notification_permission_asked") to true))
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
                },
            )
        compose.setContent {
            KoinIsolatedContext(app) {
                PyrycodeMobileTheme {
                    nav = rememberNavController()
                    PyryNavHost(Routes.CHANNEL_LIST, navController = nav, openTarget = target)
                }
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        const val SAVED = "A /?#%"
    }
}
