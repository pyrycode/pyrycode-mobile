package de.pyryco.mobile.ui.conversations.thread

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.lifecycle.ViewModel
import androidx.navigation.NavBackStackEntry
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
import de.pyryco.mobile.di.HostConversationSource
import de.pyryco.mobile.di.InertAttachmentStore
import de.pyryco.mobile.di.InertConversationCache
import de.pyryco.mobile.di.ObservablePairedServerStore
import de.pyryco.mobile.di.RelayConnectionFactory
import de.pyryco.mobile.di.RelayConnectionRegistry
import de.pyryco.mobile.di.appModule
import de.pyryco.mobile.di.conversationRepositoryModule
import de.pyryco.mobile.ui.conversations.list.ChannelListViewModel
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.core.KoinApplication
import org.koin.dsl.binds
import org.koin.dsl.module

@RunWith(AndroidJUnit4::class)
class LiteralScreenNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var app: KoinApplication
    private lateinit var registry: RelayConnectionRegistry
    private lateinit var store: ObservablePairedServerStore
    private lateinit var nav: NavHostController
    private val a = HostConversationTarget("A /?#%", "same /?#%")
    private val b = a.copy(serverId = "B")
    private val peers = mutableMapOf<String, NavigationPeer>()

    @After fun close() {
        if (::app.isInitialized) app.close()
        if (::registry.isInitialized) registry.dispose()
    }

    @Test fun hostStreamsBackReopenAndRestorationKeepDestinationIdentity() {
        val restoration = start()
        lateinit var first: ThreadViewModel
        compose.runOnIdle {
            val list = model<ChannelListViewModel>()
            list.onHostRowTapped(a)
            list.onHostRowTapped(a)
            list.onHostRowTapped(b)
        }
        awaitTarget(b, Routes.CONVERSATION_THREAD)
        lateinit var other: ThreadViewModel
        compose.runOnIdle {
            other = model()
            nav.popBackStack()
        }
        awaitTarget(a, Routes.CONVERSATION_THREAD)
        compose.runOnIdle { assertNotSame(other, model<ThreadViewModel>()) }
        compose.runOnIdle { first = model() }
        restoration.emulateSavedInstanceStateRestore()
        awaitTarget(a, Routes.CONVERSATION_THREAD)
        compose.runOnIdle { nav.popBackStack() }
        compose.runOnIdle {
            assertEquals(Routes.CHANNEL_LIST, nav.currentDestination?.route)
            model<ChannelListViewModel>().onHostRowTapped(b)
        }
        awaitTarget(b, Routes.CONVERSATION_THREAD)
        compose.runOnIdle { assertNotSame(first, model<ThreadViewModel>()) }
        restoration.emulateSavedInstanceStateRestore()
        awaitTarget(b, Routes.CONVERSATION_THREAD)
        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(Routes.CHANNEL_LIST, nav.currentDestination?.route)
            model<ChannelListViewModel>().onHostRowTapped(a)
        }
        awaitTarget(a, Routes.CONVERSATION_THREAD)
        compose.runOnIdle { assertNotSame(first, model<ThreadViewModel>()) }
    }

    @Test fun recentDiscussionsDestinationIsNotRegistered() {
        start()
        compose.runOnIdle { assertNull(nav.graph.findNode("discussions")) }
    }

    @Test fun unknownAndRemovedHostsReturnToListWithoutResolvingAnotherHost() {
        start()
        compose.runOnIdle { nav.navigate(Routes.thread(a)) }
        awaitTarget(a, Routes.CONVERSATION_THREAD)
        compose.runOnIdle { runBlocking { store.remove(a.serverId) } }
        compose.waitUntil(5_000) { nav.currentDestination?.route == Routes.CHANNEL_LIST }
        compose.runOnIdle { nav.navigate(Routes.thread(b)) }
        awaitTarget(b, Routes.CONVERSATION_THREAD)
    }

    @Test fun threadOverflowOmitsChangeWorkspace() {
        start(live = true)
        compose.runOnIdle { nav.navigate(Routes.thread(a)) }
        awaitTarget(a, Routes.CONVERSATION_THREAD)
        compose.onNodeWithContentDescription("More actions").performClick()
        compose.onNodeWithText("Change workspace…").assertDoesNotExist()
    }

    @Test fun flatListWorkspacePickerKeepsCapturedOwnerAcrossSelectionChanges() {
        start(live = true)
        select(a.serverId)
        // The host source reconciles on Dispatchers.Default, which Compose idling does not track; until
        // A is in its snapshots, openAddWorkspace rejects A as an unknown host and nothing is requested.
        val hosts = app.koin.get<HostConversationSource>()
        compose.waitUntil(5_000) { hosts.snapshots.value.any { it.serverId == a.serverId } }
        compose.runOnIdle { model<ChannelListViewModel>().openAddWorkspace(a.serverId) }
        assertOwnerPicker()
        select(b.serverId)
        assertOwnerPicker()
        // Add workspace (#904): a created folder is only selected; OK starts the chat in it.
        createFolder()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("OK") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertTrue(peers.getValue(a.serverId).outbound.none { it.type == "create_conversation" }) }
        compose.onNode(hasText("OK")).performClick()
        awaitTarget(a, Routes.CONVERSATION_THREAD)
        compose.runOnIdle {
            assertEquals(1, peers.getValue(a.serverId).outbound.count { it.type == "create_conversation" })
            assertNoOtherPickerCalls()
        }
    }

    private fun assertOwnerPicker() {
        compose.waitUntil(5_000) { peers.getValue(a.serverId).outbound.any { it.type == "recent_workspaces" } }
        compose.runOnIdle { assertNoOtherPickerCalls() }
        compose.onNodeWithText("/${a.serverId}/recent").assertIsDisplayed()
        compose.onNodeWithText("/${b.serverId}/recent").assertDoesNotExist()
        compose.runOnIdle { assertNoOtherPickerCalls() }
    }

    private fun assertNoOtherPickerCalls() {
        assertFalse(
            peers.getValue(b.serverId).outbound.any {
                it.type in setOf("recent_workspaces", "create_workspace_folder", "change_workspace", "create_conversation")
            },
        )
    }

    private fun select(serverId: String) {
        compose.runOnIdle { runBlocking { store.save(store.loadById(serverId)!!.record) } }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(registry.selected.value === registry.connectionFor(serverId)) }
    }

    private fun createFolder() {
        compose.onNodeWithText("Create new folder under pyry-workspace…").performClick()
        compose.onNodeWithText("What should this workspace be called?").performTextInput("owned-folder")
        compose.onNodeWithText("Create", ignoreCase = false).performClick()
    }

    private fun start(live: Boolean = false): StateRestorationTester {
        val serverKey = NavigationPeer.key()
        val deviceKey = NavigationPeer.key()
        val raw =
            object : PairedServerCollectionStore {
                var entries =
                    listOf(
                        a.serverId,
                        b.serverId,
                    ).map { PairedServerEntry(PairedServer(it, "unused", "wss://unused.example", base64StdEncode(serverKey.publicKey))) }

                override suspend fun list() = entries

                override suspend fun load() = entries.lastOrNull()?.record

                override suspend fun loadById(serverId: String) = entries.find { it.record.serverId == serverId }

                override suspend fun save(record: PairedServer) {
                    entries =
                        entries.filterNot { it.record.serverId == record.serverId } + PairedServerEntry(record)
                }

                override suspend fun remove(serverId: String) {
                    entries = entries.filterNot { it.record.serverId == serverId }
                }

                override suspend fun setDisplayName(
                    serverId: String,
                    displayName: String?,
                ) = Unit
            }
        store = ObservablePairedServerStore(raw) { }
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
                        NavigationPeer(record.serverId, a.conversationId, serverKey).also { peers[record.serverId] = it }
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
                    // Already asked: on a device the channel list would otherwise raise the one-time
                    // notification prompt (#685) over the test activity.
                    override val data = flowOf(preferencesOf(booleanPreferencesKey("notification_permission_asked") to true))

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
                    // This container has no androidContext(), and the relay host source resolves a
                    // ConversationCache (#796) whose real binding needs one. See InertConversationCache
                    // for why these containers override it rather than supply the Context.
                    single<ConversationCache> { InertConversationCache }
                    single { InertAttachmentStore }
                    // The real reader needs androidContext() for its ContentResolver (#932).
                    single<AttachmentReader> { AttachmentReader { AttachmentRead.Unreadable } }
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

    private fun awaitTarget(
        target: HostConversationTarget,
        route: String,
    ) {
        compose.waitUntil(5_000) { nav.currentDestination?.route == route }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(target, Routes.target(nav.currentBackStackEntry?.arguments)) }
    }

    private inline fun <reified T : ViewModel> model(entry: NavBackStackEntry = nav.currentBackStackEntry!!): T =
        entry.viewModelStore
            .keys()
            .mapNotNull { entry.viewModelStore[it] as? T }
            .single()
}
