package de.pyryco.mobile.di

import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.BuildConfig
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.cache.FileConversationCache
import de.pyryco.mobile.data.crypto.DeviceStaticKeyStore
import de.pyryco.mobile.data.crypto.KeystoreDeviceStaticKeyStore
import de.pyryco.mobile.data.crypto.KeystorePairedServerStore
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.NoiseClientInfo
import de.pyryco.mobile.data.network.NoiseSessionFactory
import de.pyryco.mobile.data.network.OkHttpRelayTransport
import de.pyryco.mobile.data.network.RelayConnectionController
import de.pyryco.mobile.data.network.RelayConnectionSupervisor
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.network.RelayTransportFactory
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.repository.CachingConversationRepository
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.RelayRepositoryCoordinator
import de.pyryco.mobile.data.repository.StableConversationRepository
import de.pyryco.mobile.lifecycle.LifecycleConnectionDriver
import de.pyryco.mobile.notifications.AttentionNotifier
import de.pyryco.mobile.push.PushTokenSink
import de.pyryco.mobile.ui.conversations.list.ChannelListViewModel
import de.pyryco.mobile.ui.conversations.list.DiscussionListViewModel
import de.pyryco.mobile.ui.conversations.thread.ComposerDraftStore
import de.pyryco.mobile.ui.conversations.thread.LiteralScreenViewModel
import de.pyryco.mobile.ui.conversations.thread.ThreadViewModel
import de.pyryco.mobile.ui.conversations.thread.asRememberedEffortStore
import de.pyryco.mobile.ui.onboarding.PairCodeViewModel
import de.pyryco.mobile.ui.onboarding.ScannerViewModel
import de.pyryco.mobile.ui.settings.ArchivedDiscussionsViewModel
import de.pyryco.mobile.ui.settings.SettingsHost
import de.pyryco.mobile.ui.settings.SettingsViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import okhttp3.WebSocket
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.io.File

val appModule =
    module {
        single<DataStore<Preferences>> {
            PreferenceDataStoreFactory.create(
                produceFile = { androidContext().preferencesDataStoreFile("app_prefs") },
            )
        }
        single { AppPreferences(get()) }
        // #796: the cache's only binding. A `single` because FileConversationCache's Mutex is per
        // instance, so two instances over one root would lose an update against each other.
        //
        // The root is noBackupFilesDir, never filesDir. The manifest ships allowBackup="true" with
        // empty backup and data-extraction rules, so anything under filesDir rides cloud backup and
        // device-to-device transfer — while the Keystore-wrapped pairing credentials that authorize
        // reading this content do not. A restore would render one machine's conversation names and
        // cwds on a device that never paired the host and cannot reach it. noBackupFilesDir is
        // excluded from both paths by definition, keeping the cache exactly as transferable as the
        // credentials it belongs to. ConversationCacheBindingInstrumentedTest holds this.
        single<ConversationCache> { FileConversationCache(File(androidContext().noBackupFilesDir, "conversations")) }
        single { KeystoreDeviceStaticKeyStore(get()) } bind DeviceStaticKeyStore::class
        // #790: a removed pairing takes its host's unsent composer text with it, and (#798) its cached
        // conversation content. Bound here rather than in the unpair controller so neither screen that
        // opens the Edit host modal carries a draft-store or cache dependency it does not otherwise use,
        // and so any future removal path inherits the eviction. `save` and `setDisplayName` deliberately
        // do not evict: re-pairing the same id and renaming a host both keep their drafts and content.
        single {
            ObservablePairedServerStore(KeystorePairedServerStore(get()), forgetRemovedHost(get(), lazy { get() }))
        } binds arrayOf(PairedServerStore::class, PairedServerCollectionStore::class)
        single { NoiseClientInfo(deviceName = Build.MODEL, clientVersion = BuildConfig.VERSION_NAME) }
        single {
            RelayConnectionFactory(
                get(),
                get(),
                get(),
                // #361: every host observes the stored token, so a rotation re-registers on open hosts.
                pushTokens = get<AppPreferences>().pushToken,
            )
        }
        single(createdAtStart = true) {
            RelayConnectionRegistry(get(), get())
        } onClose { it?.dispose() }
        single<RelayConnectionController> { get<RelayConnectionRegistry>() }
        single<ConnectionStateSource> { get<RelayConnectionRegistry>() }
        // Test/diagnostic aliases resolve the retained selection; they never create an owner.
        factory<RelayConnectionBundle> { checkNotNull(get<RelayConnectionRegistry>().selected.value) { "no paired host" } }
        factory<NoiseSessionFactory> { get<RelayConnectionBundle>().sessionFactory }
        factory<RelayConnectionSupervisor> { get<RelayConnectionBundle>().supervisor }
        factory<RelayRepositoryCoordinator> { get<RelayConnectionBundle>().coordinator }
        single<WebSocket.Factory> { OkHttpRelayTransport.defaultClient() }
        single<RelayTransportFactory> {
            val client = get<WebSocket.Factory>()
            val info = get<NoiseClientInfo>()
            RelayTransportFactory { paired -> OkHttpRelayTransport(paired, info, client) }
        }
        // Concrete-only: the ConversationRepository interface is bound by conversationRepositoryModule
        // (#350), which selects the StableConversationRepository facade by default or this demo Fake.
        single { FakeConversationRepository() }
        // #302: process-lifecycle driver. Eagerly created at startKoin (Application.onCreate, main
        // thread) so it registers as a ProcessLifecycleOwner observer immediately; resolvable so the
        // FCM service (#361) can get() it for onPushWake(). The registry owns all saved hosts.
        single(createdAtStart = true) {
            LifecycleConnectionDriver(
                controller = get<RelayConnectionController>(),
                lifecycle = ProcessLifecycleOwner.get().lifecycle,
            ).also { it.start() }
        } onClose { it?.dispose() }
        // #685: alerts. Eager for the driver's reason — a push can start the process with no activity,
        // and the publisher must already be subscribed when the wake's hosts connect. The ledger sits
        // in noBackupFilesDir beside the conversation cache: it holds digests only, and never travels.
        single(createdAtStart = true) {
            AttentionNotifier(
                context = androidContext(),
                alerts = get<HostConversationSource>().alerts,
                notificationsEnabled = get<AppPreferences>().notificationsEnabled,
                isForeground = {
                    ProcessLifecycleOwner
                        .get()
                        .lifecycle.currentState
                        .isAtLeast(Lifecycle.State.STARTED)
                },
                ledgerFile = File(androidContext().noBackupFilesDir, "attention_alerts"),
            )
        } onClose { it?.dispose() }
        // #361: the FCM service's token writes outlive the service instance that received them.
        single { PushTokenSink(get()) } onClose { it?.dispose() }
        // The stable facade follows the registry's selection and that host's connection churn.
        // Registered as its own resolvable type only; conversationRepositoryModule (#350) flag-selects
        // whether this facade or the Fake wins the ConversationRepository binding.
        single { StableConversationRepository(get<RelayConnectionRegistry>().currentRepository) }
        // #789: unsent composer text, one store for the app process. App-scoped rather than
        // destination-scoped is the whole point — a draft has to outlive the back-stack entry that
        // typed it. Holds no connection and no disk handle, so it is unaffected by reconnects and by
        // the lifecycle driver's background close.
        single { ComposerDraftStore() }
        viewModel { ScannerViewModel() }
        viewModel {
            val registry = get<RelayConnectionRegistry>()
            // #842: the route's optional target host; blank is the unrouted add-host entry.
            val target = get<SavedStateHandle>().get<String>("serverId")?.takeIf { it.isNotEmpty() }
            PairCodeViewModel(get(), registry, registry::pairingStatus, target)
        }
        // The third dependency is the paired-server store the Edit host modal reads and writes (#744).
        viewModel { ChannelListViewModel(get(), get(), get()) }
        viewModel { DiscussionListViewModel(get(), get()) }
        viewModel { get<ThreadDestinationFactory>().settings(get(), get()) }
        viewModel { get<ThreadDestinationFactory>().archive(get()) }
        viewModel {
            val handle = get<SavedStateHandle>()
            get<ThreadDestinationFactory>().thread(handle, get(), get()).also { thread ->
                // #877: the thread is what knows its conversation is being viewed. The view opens the
                // conversation on its own host and holds it read until this view model is cleared.
                val viewing =
                    get<ConversationViewing>().view(
                        handle.get<String>("serverId").orEmpty(),
                        handle.get<String>("conversationId").orEmpty(),
                    )
                thread.addCloseable(viewing)
            }
        }
        viewModel { get<ThreadDestinationFactory>().literal(get()) }
    }

/**
 * Binds the [ConversationRepository] interface, flag-selecting the implementation (#350). This is the
 * **only** definition that binds the interface — [appModule] registers both candidates concrete-only.
 *
 * [useRelay] defaults to the compile-time [BuildConfig.USE_RELAY_REPOSITORY] (ON). Build with
 * `-PuseRelayRepository=false` to select [FakeConversationRepository] for a demo. The selector
 * only resolves the two already-registered singletons by type — it never constructs either,
 * so it carries none of their dependency weight and opens no connection.
 *
 * Tests and `@Preview`s force fake mode by passing `useRelay = false` explicitly, independent of the
 * build flag. Kept as its own module (not folded into [appModule]) so the binding is unit-testable
 * via real Koin resolution without dragging in [appModule]'s Android-bound graph.
 */
fun conversationRepositoryModule(useRelay: Boolean = BuildConfig.USE_RELAY_REPOSITORY): Module =
    module {
        includes(hostConversationModule(useRelay))
        single<ConversationRepository> {
            if (useRelay) get<StableConversationRepository>() else get<FakeConversationRepository>()
        }
    }

/** Shared with relay instrumentation, which replaces only the compatibility repository binding. */
fun hostConversationModule(
    useRelay: Boolean,
    decorateRepository: (ConversationRepository) -> ConversationRepository = { it },
): Module =
    module {
        // #797: the demo branch resolves no cache, as HostConversationSource's does below.
        single { ThreadDestinationFactory(useRelay, get(), get(), get(), decorateRepository, cache = if (useRelay) get() else null) }
        // #877: one viewing tracker per app, shared by the thread destinations and the host source.
        single { ConversationViewing() }
        single {
            // The demo branch resolves no cache: there is nothing persisted for a fake host to restore.
            if (useRelay) {
                HostConversationSource.relay(get(), cache = get(), viewing = get())
            } else {
                HostConversationSource.demo(get<FakeConversationRepository>(), viewing = get())
            }
        } onClose { it?.dispose() }
    }

/**
 * Whether the saved host [serverId] names is in the rejected-pairing state (#843), for the thread's
 * Re-pair action. Matched by exact id, so another host's rejection never raises it, and a host that is
 * not saved reads `false`. Follows [connections] rather than one entry's status, because a successful
 * re-pair changes the saved record and the registry replaces that host's entry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun pairingRejected(
    connections: Flow<List<HostConversationConnection>>,
    serverId: String,
): Flow<Boolean> =
    connections
        .map { hosts -> hosts.firstOrNull { it.serverId == serverId } }
        .distinctUntilChanged()
        .flatMapLatest { host -> host?.status?.map { it.relay == RelayLinkStatus.PairingRejected } ?: flowOf(false) }
        .distinctUntilChanged()

/** Destination ownership is captured once; compatibility selection is only a flat-list adapter. */
internal class ThreadDestinationFactory(
    private val useRelay: Boolean,
    private val registry: RelayConnectionRegistry,
    private val fake: FakeConversationRepository,
    private val store: PairedServerCollectionStore,
    private val decorateRepository: (ConversationRepository) -> ConversationRepository,
    private val cache: ConversationCache? = null,
) {
    val hostConnections get() = registry.hostConnections

    fun selectedServerId(): String? =
        if (!useRelay) {
            HostConversationSource.DEMO_SERVER_ID
        } else {
            registry.hostConnections.value
                .firstOrNull {
                    registry.connectionFor(it.serverId) === registry.selected.value
                }?.serverId
        }

    fun hasHost(serverId: String): Boolean =
        if (!useRelay) serverId == HostConversationSource.DEMO_SERVER_ID else registry.connectionFor(serverId) != null

    suspend fun isSavedHost(serverId: String): Boolean =
        if (!useRelay) serverId == HostConversationSource.DEMO_SERVER_ID else store.loadById(serverId) != null

    fun repository(
        serverId: String,
        bundle: RelayConnectionBundle? = if (useRelay) registry.connectionFor(serverId) else null,
    ): ConversationRepository =
        if (!useRelay && serverId == HostConversationSource.DEMO_SERVER_ID) {
            fake
        } else {
            val repositories = bundle?.coordinator?.currentRepository ?: MutableStateFlow(null)
            val stable = StableConversationRepository(repositories)
            // #797: the thread cache sits under the hook, not in it, so an instrumentation decorator
            // (E2eTestApplication's TappingConversationRepository) observes the restored thread too. A
            // blank owner gets no cache, so no rows are ever filed under the empty id.
            decorateRepository(
                if (cache != null && serverId.isNotEmpty()) CachingConversationRepository(stable, cache, serverId) else stable,
            )
        }

    fun thread(
        handle: SavedStateHandle,
        // #789: the app-scoped composer-draft store. One process-wide singleton reaching every thread
        // destination, which is what lets a draft outlive the back-stack entry that typed it. #807 removed
        // the `AppPreferences` that used to sit beside it: the thread's model and effort come from the
        // daemon's session settings now, and no other thread state reads a device preference.
        draftStore: ComposerDraftStore,
        // #686: the one exception — the remembered effort a successful write sets and an opening
        // recalls. It never touches `defaultEffort`. The demo host stays inert.
        preferences: AppPreferences,
    ): ThreadViewModel {
        val serverId = handle.get<String>("serverId").orEmpty()
        val bundle = if (useRelay) registry.connectionFor(serverId) else null
        val repository = repository(serverId, bundle)
        RelayLog.d { "event=thread_destination_bound" }
        if (!useRelay && serverId == HostConversationSource.DEMO_SERVER_ID) {
            return ThreadViewModel(handle, repository, FakeConnectionStateSource(), draftStore)
        }
        val connection =
            object : ConnectionStateSource {
                override fun observe() = bundle?.supervisor?.observe() ?: flowOf(ConnectionState.Offline)

                override suspend fun retry() {
                    if (bundle != null) registry.retryHost(serverId, bundle)
                }
            }
        return ThreadViewModel(
            handle,
            repository,
            connection,
            draftStore,
            liveSessionEvents = bundle?.coordinator?.liveSessionEvents ?: emptyFlow(),
            hostModal = bundle?.coordinator?.currentModal ?: MutableStateFlow(ModalUiState.Hidden),
            answerModal = { modal, option, grant -> checkNotNull(bundle).coordinator.answerModal(modal, option, grant) },
            cancelModal = { modal -> checkNotNull(bundle).coordinator.cancelModal(modal) },
            interrupt = { id -> checkNotNull(bundle).coordinator.interrupt(id) },
            questionBatch = { id -> bundle?.coordinator?.observeQuestionBatch(id) ?: flowOf(null) },
            answerQuestionBatch = { batch, answers -> checkNotNull(bundle).coordinator.answerQuestionBatch(batch, answers) },
            refuseQuestionBatch = { batch -> checkNotNull(bundle).coordinator.refuseQuestionBatch(batch) },
            // #861: the walk restart waits for the published repository, not the socket — the supervisor's
            // Connected precedes the handshake that publishes it.
            repositoryAvailable = bundle?.coordinator?.currentRepository?.map { it != null } ?: flowOf(false),
            // #843: read through the registry by id, not off the captured bundle — a successful re-pair
            // replaces that bundle, and the thread must see the replacement to take the action away.
            pairingRejected = pairingRejected(registry.hostConnections, serverId),
            rememberedEffort = preferences.asRememberedEffortStore(),
        )
    }

    fun literal(handle: SavedStateHandle): LiteralScreenViewModel {
        RelayLog.d { "event=literal_destination_bound" }
        return LiteralScreenViewModel(handle, repository(handle.get<String>("serverId").orEmpty()))
    }

    /**
     * The Settings destination, owned by the server id the gear captured into its route (#749).
     * Unlike [thread] and [literal] the owner is optional — a blank one means the destination owns
     * no host — and unlike them it is never resolved to a bundle here: this screen reads identity
     * and status only, so an owner that is saved but not yet connected is still its owner.
     *
     * [preferences] is the one process-wide store, but Settings no longer reads or writes it
     * app-wide throughout: since #714 the default workspace is read and written under the owner's
     * own key, so the view model needs the owner for more than the Connection section. The other
     * eight preferences stay genuinely app-wide.
     *
     * Since #715 the archived-discussion count reads the **owner's** repository rather than the
     * compatibility one, so the number on the row matches the archive the row opens. Under a blank
     * owner that facade is backed by a permanently-`null` repository, whose cold reads are
     * `emptyList()` — a count of zero, which is the honest answer for a destination owning no host.
     *
     * Since #751 the view model also drives the Edit host modal for its own host, so it takes the
     * paired-server store this factory already holds — no new Koin definition, and the same single
     * instance the channel list's own editor writes through.
     */
    fun settings(
        handle: SavedStateHandle,
        preferences: AppPreferences,
    ): SettingsViewModel {
        val serverId = handle.get<String>("serverId").orEmpty()
        RelayLog.d { "event=settings_destination_bound" }
        // Since #683 the view model also downloads this host's diagnostic archive. The registry is
        // already held here, so the hand-off is one method reference and no new Koin definition —
        // and the view model receives only the question, never the registry or the store behind it.
        return SettingsViewModel(preferences, repository(serverId), serverId, hosts(), store, registry::requestDebugBundle)
    }

    /**
     * The Archive destination, owned by the server id the Settings row captured into its route
     * (#715).
     *
     * The owner's repository is resolved **once**, here, into a facade over that host's own
     * `currentRepository` — deliberately not looked up per restore tap, which would leave a gap
     * between deciding the host and writing to it. Because the facade is bound to one host's flow,
     * a selection change, a reconnect or an unpair can move neither the rows nor a pending restore,
     * and a host holding the same conversation id is unreachable from it by construction.
     *
     * Only the resolved label crosses into the view model, never a [SettingsHost] and never a
     * stored record: those carry the pairing token and the server static key, and neither this
     * screen nor its state has a redacting `toString`.
     */
    fun archive(handle: SavedStateHandle): ArchivedDiscussionsViewModel {
        val serverId = handle.get<String>("serverId").orEmpty()
        RelayLog.d { "event=archive_destination_bound" }
        return ArchivedDiscussionsViewModel(repository(serverId), hostLabel(serverId))
    }

    /**
     * One host's display name, or blank when no saved host matches — never another host's.
     *
     * Matched by the same exact, case-sensitive equality [hosts] and the registry's entry map use.
     * The name-or-id fallback repeats `SettingsHostRow.name` rather than sharing it, the way
     * `Conversation.displayName()` is deliberately repeated (#177): the rule is one line, and
     * sharing it would mean exporting a resolution helper for a single caller.
     */
    private fun hostLabel(serverId: String): Flow<String> =
        hosts().map { saved ->
            saved
                .firstOrNull { it.serverId == serverId }
                ?.let { it.displayName?.takeIf(String::isNotBlank) ?: it.serverId }
                .orEmpty()
        }

    /**
     * Every saved host's identity and its own live status.
     *
     * Two sources, because neither alone is enough: the registry's `hostConnections` carries the
     * per-host status flow and re-emits on every store revision (so a rename, a pairing and an
     * unpair all land), while the relay URL lives only in the stored record. One [store] read per
     * emission joins them — not one `loadById` per host, which would decrypt N times for the same
     * blob.
     *
     * The three display fields are copied out explicitly and the [PairedServerEntry] is dropped
     * here: it carries the pairing token and the server static key, and [SettingsHost] has no
     * redacting `toString` to stop a crash trace from rendering them.
     *
     * Cold by construction, so two Settings entries on the back stack do not share one projection;
     * the status flows inside it are the coordinators' existing hot ones, and collecting one never
     * dials a host.
     */
    private fun hosts(): Flow<List<SettingsHost>> =
        if (!useRelay) {
            flowOf(listOf(SettingsHost(HostConversationSource.DEMO_SERVER_ID, "Demo", DEMO_RELAY_URL, MutableStateFlow(DEMO_STATUS))))
        } else {
            registry.hostConnections.map { connections ->
                val saved = store.list().associateBy { it.record.serverId }
                connections.mapNotNull { connection ->
                    saved[connection.serverId]?.let { entry ->
                        SettingsHost(connection.serverId, entry.displayName, entry.record.relayUrl, connection.status)
                    }
                }
            }
        }

    private companion object {
        const val DEMO_RELAY_URL = "wss://demo.invalid"
        val DEMO_STATUS = ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)
    }
}
