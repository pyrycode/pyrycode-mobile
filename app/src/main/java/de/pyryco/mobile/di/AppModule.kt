package de.pyryco.mobile.di

import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.ProcessLifecycleOwner
import de.pyryco.mobile.BuildConfig
import de.pyryco.mobile.data.crypto.DeviceStaticKeyStore
import de.pyryco.mobile.data.crypto.KeystoreDeviceStaticKeyStore
import de.pyryco.mobile.data.crypto.KeystorePairedServerStore
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.network.NoiseClientInfo
import de.pyryco.mobile.data.network.NoiseSessionFactory
import de.pyryco.mobile.data.network.OkHttpRelayTransport
import de.pyryco.mobile.data.network.RelayConnectionController
import de.pyryco.mobile.data.network.RelayConnectionSupervisor
import de.pyryco.mobile.data.network.RelayTransportFactory
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.RelayRepositoryCoordinator
import de.pyryco.mobile.data.repository.StableConversationRepository
import de.pyryco.mobile.lifecycle.LifecycleConnectionDriver
import de.pyryco.mobile.ui.conversations.list.ChannelListViewModel
import de.pyryco.mobile.ui.conversations.list.DiscussionListViewModel
import de.pyryco.mobile.ui.conversations.thread.LiteralScreenViewModel
import de.pyryco.mobile.ui.conversations.thread.ThreadViewModel
import de.pyryco.mobile.ui.onboarding.ScannerViewModel
import de.pyryco.mobile.ui.settings.ArchivedDiscussionsViewModel
import de.pyryco.mobile.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.first
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
        viewModel { ChannelListViewModel(get(), get()) }
        viewModel { DiscussionListViewModel(get()) }
        viewModel {
            SettingsViewModel(get(), get(), get<RelayConnectionRegistry>().connectionStatus)
        }
        viewModel { ArchivedDiscussionsViewModel(get()) }
        viewModel {
            val registry = get<RelayConnectionRegistry>()
            ThreadViewModel(
                get(),
                get(),
                get(),
                get(),
                registry.liveSessionEvents,
                registry.currentModal,
                answerModal = registry::answerModal,
                cancelModal = registry::cancelModal,
                interrupt = registry::interrupt,
            )
        }
        // #381: resolvable so #382's nav destination can obtain it (get() → SavedStateHandle +
        // ConversationRepository). Per-conversation scoping of the obtained instance is #382's job.
        viewModel { LiteralScreenViewModel(get(), get()) }
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
        single<ConversationRepository> {
            if (useRelay) get<StableConversationRepository>() else get<FakeConversationRepository>()
        }
    }
