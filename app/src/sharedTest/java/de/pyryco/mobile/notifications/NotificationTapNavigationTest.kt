package de.pyryco.mobile.notifications

import android.content.Context
import android.os.Looper
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.NOTIFICATION_TAP_ROW_WAIT
import de.pyryco.mobile.PyryNavHost
import de.pyryco.mobile.Routes
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.crypto.DeviceStaticKeyPair
import de.pyryco.mobile.data.crypto.DeviceStaticKeyStore
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.NoiseClientInfo
import de.pyryco.mobile.data.network.RelayTransportFactory
import de.pyryco.mobile.data.network.base64StdEncode
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.di.HostConversationConnection
import de.pyryco.mobile.di.HostConversationSource
import de.pyryco.mobile.di.InertAttachmentStore
import de.pyryco.mobile.di.InertConversationCache
import de.pyryco.mobile.di.ObservablePairedServerStore
import de.pyryco.mobile.di.RelayConnectionFactory
import de.pyryco.mobile.di.RelayConnectionRegistry
import de.pyryco.mobile.di.appModule
import de.pyryco.mobile.di.conversationRepositoryModule
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import de.pyryco.mobile.ui.conversations.share.RecentShareTargets
import de.pyryco.mobile.ui.conversations.share.ShareIntakeViewModel
import de.pyryco.mobile.ui.conversations.share.SharePayload
import de.pyryco.mobile.ui.conversations.share.SharingShortcuts
import de.pyryco.mobile.ui.conversations.thread.AttachmentRead
import de.pyryco.mobile.ui.conversations.thread.AttachmentReader
import de.pyryco.mobile.ui.conversations.thread.ComposerDraftStore
import de.pyryco.mobile.ui.conversations.thread.NavigationPeer
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.compose.KoinIsolatedContext
import org.koin.core.KoinApplication
import org.koin.dsl.binds
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A notification tap's target on the production graph and `Routes` (#685): a saved host's conversation
 * opens above the channel list, and anything else stays on the list. Since #1400 the conversation must
 * also be active in the host's snapshot; rows land through the real cache-restore path, released by
 * [rows], and the tap waits [NOTIFICATION_TAP_ROW_WAIT_MS] for them. Mounts `PyryNavHost` directly, as
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
    private val rows = CompletableDeferred<List<Conversation>>()
    private val threadOpenedOnMain = AtomicBoolean(false)

    @After fun close() {
        if (::app.isInitialized) app.close()
        if (::registry.isInitialized) registry.dispose()
    }

    @Test fun aSavedHostsTapOpensItsThreadAboveTheChannelList() {
        val target = HostConversationTarget(SAVED, "conv /?#%")
        rows.complete(listOf(conversation(target.conversationId, promoted = true)))
        start(target)

        compose.waitUntil(5_000) { nav.currentDestination?.route == Routes.CONVERSATION_THREAD }
        compose.runOnIdle {
            assertEquals(target, Routes.target(nav.currentBackStackEntry?.arguments))
            // The thread opens above the list, so the list is one Back away.
            nav.popBackStack()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(Routes.CHANNEL_LIST, nav.currentDestination?.route) }
    }

    @Test fun anActiveChatsTapOpensItsThread() {
        val target = HostConversationTarget(SAVED, "chat")
        rows.complete(listOf(conversation("chat", promoted = false)))
        start(target)

        compose.waitUntil(5_000) { nav.currentDestination?.route == Routes.CONVERSATION_THREAD }
        compose.runOnIdle { assertEquals(target, Routes.target(nav.currentBackStackEntry?.arguments)) }
    }

    @Test fun aSavedHostsTapReturnsToMainAfterAnIoLookup() {
        val target = HostConversationTarget(SAVED, "conv")
        rows.complete(listOf(conversation("conv", promoted = true)))
        start(target, ioLookup = true)

        compose.waitUntil(5_000) {
            compose.runOnIdle { nav.currentDestination?.route == Routes.CONVERSATION_THREAD }
        }
        compose.runOnIdle {
            assertTrue("Thread navigation must run on Main", threadOpenedOnMain.get())
            assertEquals(target, Routes.target(nav.currentBackStackEntry?.arguments))
            nav.popBackStack()
            assertEquals(Routes.CHANNEL_LIST, nav.currentDestination?.route)
        }
    }

    @Test fun directShareReturnsToMainAfterAnIoLookupAndTransfersOnce() {
        val target = HostConversationTarget(SAVED, "conv")
        rows.complete(listOf(conversation("conv", promoted = true)))
        start(target, direct = true, ioLookup = true)

        compose.waitUntil(5_000) {
            compose.runOnIdle { nav.currentDestination?.route == Routes.CONVERSATION_THREAD }
        }
        compose.runOnIdle {
            assertTrue("Direct Share navigation must run on Main", threadOpenedOnMain.get())
            assertEquals(target, Routes.target(nav.currentBackStackEntry?.arguments))
            assertEquals("direct", app.koin.get<ComposerDraftStore>().draftFor(SAVED, "conv"))
            assertEquals(null, intake?.state?.value)
        }
    }

    @Test fun anArchivedConversationsTapStaysOnTheChannelList() {
        rows.complete(listOf(conversation("conv", promoted = true, archived = true)))
        start(HostConversationTarget(SAVED, "conv"))

        assertStaysOnTheListPastTheWait()
    }

    @Test fun aDeletedRowDuringTheFinalHostLookupStaysOnTheListWithoutChangingDrafts() {
        assertUnavailableDuringFinalHostLookup(archived = false)
    }

    @Test fun anArchivedRowDuringTheFinalHostLookupStaysOnTheListWithoutChangingDrafts() {
        assertUnavailableDuringFinalHostLookup(archived = true)
    }

    private fun assertUnavailableDuringFinalHostLookup(archived: Boolean) {
        val target = HostConversationTarget(SAVED, "conv")
        val activeRow = conversation(target.conversationId, promoted = true)
        val liveRows = MutableStateFlow(listOf(activeRow))
        val lookups = AtomicInteger()
        val finalLookupStarted = CompletableDeferred<Unit>()
        val releaseFinalLookup = CompletableDeferred<Unit>()
        start(target, liveRows = liveRows, beforeHostLookup = {
            if (lookups.incrementAndGet() == 2) {
                finalLookupStarted.complete(Unit)
                releaseFinalLookup.await()
            }
        })
        compose.waitUntil(5_000) { finalLookupStarted.isCompleted }
        val drafts = app.koin.get<ComposerDraftStore>()
        compose.runOnIdle {
            val snapshot =
                app.koin
                    .get<HostConversationSource>()
                    .snapshots.value
                    .single()
            assertTrue(snapshot.rowsLoaded)
            assertEquals(listOf(activeRow), snapshot.channels)
            assertEquals(Routes.CHANNEL_LIST, nav.currentDestination?.route)
            drafts.setDraft(SAVED, target.conversationId, "keep target draft")
            drafts.setDraft("other", target.conversationId, "keep other host draft")
            drafts.addAttachment(SAVED, target.conversationId, "content://test/draft", "draft.txt", "text/plain", 5)
        }
        val originalDrafts = drafts.drafts.value
        val originalAttachments = drafts.attachments.value
        compose.runOnIdle { liveRows.value = if (archived) listOf(activeRow.copy(archived = true)) else emptyList() }
        compose.waitUntil(5_000) {
            val snapshot =
                app.koin
                    .get<HostConversationSource>()
                    .snapshots.value
                    .single()
            snapshot.rowsLoaded && snapshot.channels.isEmpty()
        }
        compose.runOnIdle { releaseFinalLookup.complete(Unit) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(Routes.CHANNEL_LIST, nav.currentDestination?.route)
            assertEquals(null, nav.previousBackStackEntry)
            assertEquals(originalDrafts, drafts.drafts.value)
            assertEquals(originalAttachments, drafts.attachments.value)
        }
    }

    @Test fun anUnknownConversationsTapStaysOnTheChannelList() {
        rows.complete(listOf(conversation("other", promoted = true)))
        start(HostConversationTarget(SAVED, "deleted"))

        assertStaysOnTheListPastTheWait()
    }

    @Test fun aTapOpensTheThreadWhenItsRowArrivesWithinTheWait() {
        val target = HostConversationTarget(SAVED, "conv")
        start(target)
        compose.mainClock.advanceTimeBy(NOTIFICATION_TAP_ROW_WAIT_MS / 2)
        compose.runOnIdle { assertEquals(Routes.CHANNEL_LIST, nav.currentDestination?.route) }

        rows.complete(listOf(conversation("conv", promoted = true)))

        compose.waitUntil(5_000) { nav.currentDestination?.route == Routes.CONVERSATION_THREAD }
        compose.runOnIdle { assertEquals(target, Routes.target(nav.currentBackStackEntry?.arguments)) }
    }

    @Test fun aTapWhoseRowsNeverArriveStaysOnTheChannelList() {
        start(HostConversationTarget(SAVED, "conv"))

        assertStaysOnTheListPastTheWait()
        // Released only after the wait: had it not elapsed, this would still open the thread.
        rows.complete(listOf(conversation("conv", promoted = true)))
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(Routes.CHANNEL_LIST, nav.currentDestination?.route)
            assertEquals(null, nav.previousBackStackEntry)
        }
    }

    @Test fun aRowArrivingAfterTheUserLeftTheListOpensNothing() {
        start(HostConversationTarget(SAVED, "conv"))
        compose.runOnIdle { nav.navigate(Routes.ABOUT) }
        compose.waitForIdle()

        rows.complete(listOf(conversation("conv", promoted = true)))
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(Routes.ABOUT, nav.currentDestination?.route)
            assertEquals(Routes.CHANNEL_LIST, nav.previousBackStackEntry?.destination?.route)
        }
    }

    @Test fun anUnsavedHostsTapStaysOnTheChannelList() {
        rows.complete(listOf(conversation("conv", promoted = true)))
        start(HostConversationTarget("forged", "conv"))

        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(Routes.CHANNEL_LIST, nav.currentDestination?.route)
            assertEquals(null, nav.previousBackStackEntry)
        }
    }

    @Test fun directShareWaitsForTheExactHostsRowAndTransfersOnce() {
        val target = HostConversationTarget(SAVED, "conv")
        start(target, direct = true)
        compose.mainClock.advanceTimeBy(NOTIFICATION_TAP_ROW_WAIT_MS / 2)
        compose.runOnIdle { assertEquals(Routes.CHANNEL_LIST, nav.currentDestination?.route) }
        compose.onAllNodes(hasText("Share to…")).assertCountEquals(0)
        rows.complete(listOf(conversation("conv", promoted = true)))
        compose.waitUntil(5_000) { nav.currentDestination?.route == Routes.CONVERSATION_THREAD }
        compose.runOnIdle {
            assertEquals(target, Routes.target(nav.currentBackStackEntry?.arguments))
            assertEquals("direct", app.koin.get<ComposerDraftStore>().draftFor(SAVED, "conv"))
            assertEquals("", app.koin.get<ComposerDraftStore>().draftFor("other", "conv"))
        }
    }

    @Test fun staleDirectShareFallsBackWithoutMutatingAnyDraftAndLateRowsCannotStage() {
        val target = HostConversationTarget(SAVED, "deleted")
        start(target, direct = true)
        assertStaysOnTheListPastTheWait()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Share to…")).fetchSemanticsNodes().isNotEmpty() }
        rows.complete(listOf(conversation("deleted", promoted = true)))
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle {
            assertEquals(Routes.CHANNEL_LIST, nav.currentDestination?.route)
            assertEquals("", app.koin.get<ComposerDraftStore>().draftFor(SAVED, "deleted"))
        }
    }

    @Test fun cancelledDirectShareCannotNavigateWhenTheRowArrives() {
        start(HostConversationTarget(SAVED, "conv"), direct = true)
        compose.runOnIdle { intake?.cancel() }
        rows.complete(listOf(conversation("conv", promoted = true)))
        compose.mainClock.advanceTimeBy(NOTIFICATION_TAP_ROW_WAIT_MS + 1_000)
        compose.runOnIdle {
            assertEquals(Routes.CHANNEL_LIST, nav.currentDestination?.route)
            assertEquals("", app.koin.get<ComposerDraftStore>().draftFor(SAVED, "conv"))
        }
    }

    private fun assertStaysOnTheListPastTheWait() {
        compose.mainClock.advanceTimeBy(NOTIFICATION_TAP_ROW_WAIT_MS + 1_000)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(Routes.CHANNEL_LIST, nav.currentDestination?.route)
            assertEquals(null, nav.previousBackStackEntry)
        }
    }

    private fun conversation(
        id: String,
        promoted: Boolean,
        archived: Boolean = false,
    ) = Conversation(id, null, "/w", "s", emptyList(), promoted, Instant.fromEpochMilliseconds(0), archived = archived)

    private var intake: ShareIntakeViewModel? = null

    private fun start(
        target: HostConversationTarget,
        direct: Boolean = false,
        ioLookup: Boolean = false,
        liveRows: MutableStateFlow<List<Conversation>>? = null,
        beforeHostLookup: suspend () -> Unit = {},
    ) {
        val serverKey = NavigationPeer.key()
        val deviceKey = NavigationPeer.key()
        val entries = listOf(PairedServerEntry(PairedServer(SAVED, "unused", "wss://unused.example", base64StdEncode(serverKey.publicKey))))
        val raw =
            object : PairedServerCollectionStore {
                override suspend fun list() = entries

                override suspend fun load() = entries.lastOrNull()?.record

                override suspend fun loadById(serverId: String): PairedServerEntry? {
                    beforeHostLookup()
                    return if (ioLookup) {
                        withContext(Dispatchers.IO) {
                            // Force suspension as the real Keystore/DataStore lookup does.
                            delay(10)
                            entries.find { it.record.serverId == serverId }
                        }
                    } else {
                        entries.find { it.record.serverId == serverId }
                    }
                }

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
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ledgerFile = File(context.cacheDir, "direct-share-test")
        val ledger = RecentShareTargets(4).apply { open(target, "target") }
        ledgerFile.writeText(ledger.encode())
        app =
            KoinApplication.init().modules(
                appModule,
                conversationRepositoryModule(true),
                module {
                    single {
                        SharingShortcuts(context, get<HostConversationSource>().snapshots, flowOf(setOf(SAVED)), ledgerFile)
                    } onClose { it?.dispose() }
                    single { registry }
                    single { store } binds arrayOf(PairedServerStore::class, PairedServerCollectionStore::class)
                    single { preferences }
                    single<ConversationCache> {
                        object : ConversationCache by InertConversationCache {
                            override suspend fun readConversations(serverId: String) = if (serverId == SAVED) rows.await() else emptyList()
                        }
                    }
                    single { InertAttachmentStore }
                    // The real reader needs androidContext() for its ContentResolver (#932).
                    single<AttachmentReader> { AttachmentReader { AttachmentRead.Unreadable } }
                    // On the main thread like the registry above: the test's effect dispatcher does not
                    // redispatch, so a snapshot published from Dispatchers.Default would navigate off it.
                    single {
                        if (liveRows == null) {
                            HostConversationSource.relay(get(), Dispatchers.Main.immediate, cache = get(), viewing = get())
                        } else {
                            val repository =
                                object : ConversationRepository by FakeConversationRepository() {
                                    override fun observeConversations(filter: ConversationFilter) = liveRows
                                }
                            HostConversationSource(
                                MutableStateFlow(
                                    listOf(
                                        HostConversationConnection(
                                            SAVED,
                                            null,
                                            MutableStateFlow(repository),
                                            MutableStateFlow(ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)),
                                        ),
                                    ),
                                ),
                                { if (it == SAVED) repository else null },
                                Dispatchers.Main.immediate,
                                viewing = get(),
                            )
                        }
                    } onClose {
                        it?.dispose()
                    }
                },
            )
        if (direct) {
            intake = ShareIntakeViewModel(app.koin.get(), { _, _ -> null }, Dispatchers.Main.immediate)
            intake?.accept(SharePayload("direct", emptyList(), ledger.id(target)))
        }
        compose.setContent {
            KoinIsolatedContext(app) {
                PyrycodeMobileTheme {
                    nav = rememberNavController()
                    DisposableEffect(nav) {
                        val listener =
                            NavController.OnDestinationChangedListener { _, destination, _ ->
                                if (destination.route == Routes.CONVERSATION_THREAD) {
                                    threadOpenedOnMain.set(Looper.myLooper() == Looper.getMainLooper())
                                }
                            }
                        nav.addOnDestinationChangedListener(listener)
                        onDispose { nav.removeOnDestinationChangedListener(listener) }
                    }
                    PyryNavHost(Routes.CHANNEL_LIST, navController = nav, openTarget = target.takeUnless { direct }, shareIntake = intake)
                }
            }
        }
        compose.waitForIdle()
    }

    private companion object {
        const val SAVED = "A /?#%"
        val NOTIFICATION_TAP_ROW_WAIT_MS = NOTIFICATION_TAP_ROW_WAIT.inWholeMilliseconds
    }
}
