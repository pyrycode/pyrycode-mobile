package de.pyryco.mobile.di

import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.BuildConfig
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
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConnectionStateSource
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.RelayRepositoryCoordinator
import de.pyryco.mobile.data.repository.StableConversationRepository
import de.pyryco.mobile.lifecycle.LifecycleConnectionDriver
import de.pyryco.mobile.ui.conversations.list.ChannelListViewModel
import de.pyryco.mobile.ui.conversations.list.DiscussionListViewModel
import de.pyryco.mobile.ui.conversations.thread.LiteralScreenViewModel
import de.pyryco.mobile.ui.conversations.thread.ThreadViewModel
import de.pyryco.mobile.ui.onboarding.PairCodeViewModel
import de.pyryco.mobile.ui.onboarding.ScannerViewModel
import de.pyryco.mobile.ui.settings.ArchivedDiscussionsViewModel
import de.pyryco.mobile.ui.settings.SettingsHost
import de.pyryco.mobile.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
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

val appModule =
    module {
        single<DataStore<Preferences>> {
            PreferenceDataStoreFactory.create(
                produceFile = { androidContext().preferencesDataStoreFile("app_prefs") },
            )
        }
        single { AppPreferences(get()) }
        single { KeystoreDeviceStaticKeyStore(get()) } bind DeviceStaticKeyStore::class
        single { ObservablePairedServerStore(KeystorePairedServerStore(get())) } binds
            arrayOf(PairedServerStore::class, PairedServerCollectionStore::class)
        single { NoiseClientInfo(deviceName = Build.MODEL, clientVersion = BuildConfig.VERSION_NAME) }
        single {
            RelayConnectionFactory(
                get(),
                get(),
                get(),
                pushToken = { get<AppPreferences>().pushToken.first() },
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
        // thread) so it registers as a ProcessLifecycleOwner observer immediately; resolvable so a
        // future FCM service can get() it for onPushWake(). The registry owns all saved hosts.
        single(createdAtStart = true) {
            LifecycleConnectionDriver(
                controller = get<RelayConnectionController>(),
                lifecycle = ProcessLifecycleOwner.get().lifecycle,
            ).also { it.start() }
        }
        // The stable facade follows the registry's selection and that host's connection churn.
        // Registered as its own resolvable type only; conversationRepositoryModule (#350) flag-selects
        // whether this facade or the Fake wins the ConversationRepository binding.
        single { StableConversationRepository(get<RelayConnectionRegistry>().currentRepository) }
        viewModel { ScannerViewModel() }
        viewModel {
            val registry = get<RelayConnectionRegistry>()
            PairCodeViewModel(get(), registry, registry::pairingStatus)
        }
        // The third dependency is the paired-server store the Edit host modal reads and writes (#744).
        viewModel { ChannelListViewModel(get(), get(), get()) }
        viewModel { DiscussionListViewModel(get(), get()) }
        viewModel { get<ThreadDestinationFactory>().settings(get(), get()) }
        viewModel { get<ThreadDestinationFactory>().archive(get()) }
        viewModel { get<ThreadDestinationFactory>().thread(get(), get()) }
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
        single { ThreadDestinationFactory(useRelay, get(), get(), get(), decorateRepository) }
        single {
            if (useRelay) HostConversationSource.relay(get()) else HostConversationSource.demo(get<FakeConversationRepository>())
        } onClose { it?.dispose() }
    }

/** Destination ownership is captured once; compatibility selection is only a flat-list adapter. */
internal class ThreadDestinationFactory(
    private val useRelay: Boolean,
    private val registry: RelayConnectionRegistry,
    private val fake: FakeConversationRepository,
    private val store: PairedServerCollectionStore,
    private val decorateRepository: (ConversationRepository) -> ConversationRepository,
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
            decorateRepository(StableConversationRepository(repositories))
        }

    fun thread(
        handle: SavedStateHandle,
        preferences: AppPreferences,
    ): ThreadViewModel {
        val serverId = handle.get<String>("serverId").orEmpty()
        val bundle = if (useRelay) registry.connectionFor(serverId) else null
        val repository = repository(serverId, bundle)
        RelayLog.d { "event=thread_destination_bound" }
        if (!useRelay && serverId == HostConversationSource.DEMO_SERVER_ID) {
            return ThreadViewModel(handle, repository, FakeConnectionStateSource(), preferences)
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
            preferences,
            liveSessionEvents = bundle?.coordinator?.liveSessionEvents ?: emptyFlow(),
            currentModal = bundle?.coordinator?.currentModal ?: MutableStateFlow(ModalUiState.Hidden),
            answerModal = { modal, option -> checkNotNull(bundle).coordinator.answerModal(modal, option) },
            cancelModal = { modal -> checkNotNull(bundle).coordinator.cancelModal(modal) },
            interrupt = { id -> checkNotNull(bundle).coordinator.interrupt(id) },
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
