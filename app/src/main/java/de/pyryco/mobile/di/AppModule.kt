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
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.network.NoiseClientInfo
import de.pyryco.mobile.data.network.NoiseSessionFactory
import de.pyryco.mobile.data.network.NoiseSessionPump
import de.pyryco.mobile.data.network.OkHttpRelayTransport
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
import org.koin.dsl.module

val appModule =
    module {
        single<DataStore<Preferences>> {
            PreferenceDataStoreFactory.create(
                produceFile = { androidContext().preferencesDataStoreFile("app_prefs") },
            )
        }
        single { AppPreferences(get()) }
        single { KeystoreDeviceStaticKeyStore(get()) } bind DeviceStaticKeyStore::class
        single { KeystorePairedServerStore(get()) } bind PairedServerStore::class
        single { NoiseClientInfo(deviceName = Build.MODEL, clientVersion = BuildConfig.VERSION_NAME) }
        single { NoiseSessionFactory(get(), get(), get()) }
        single<WebSocket.Factory> { OkHttpRelayTransport.defaultClient() }
        single<RelayTransportFactory> {
            val client = get<WebSocket.Factory>()
            val info = get<NoiseClientInfo>()
            RelayTransportFactory { paired -> OkHttpRelayTransport(paired, info, client) }
        }
        // Concrete-only: the ConversationRepository interface is bound by conversationRepositoryModule
        // (#350), which flag-selects this Fake (the default) or the StableConversationRepository facade.
        single { FakeConversationRepository() }
        // #307: real WS-backed source. Bound but dormant — #302's driver drives the first connect().
        single { RelayConnectionSupervisor(get(), get()) } bind ConnectionStateSource::class
        // #302: process-lifecycle driver. Eagerly created at startKoin (Application.onCreate, main
        // thread) so it registers as a ProcessLifecycleOwner observer immediately; resolvable so a
        // future FCM service can get() it for onPushWake(). Reuses the dormant supervisor singleton.
        single(createdAtStart = true) {
            LifecycleConnectionDriver(
                controller = get<RelayConnectionSupervisor>(),
                lifecycle = ProcessLifecycleOwner.get().lifecycle,
            ).also { it.start() }
        }
        // #351: connection-scoped coordinator. Eagerly started so it observes currentConnection for the
        // process lifetime — per live connection it starts a fresh Noise pump and builds a remote repo,
        // publishing it on currentRepository for the #352 facade. Does NOT bind ConversationRepository:
        // that binding lives in conversationRepositoryModule (#350), flag-gated.
        single(createdAtStart = true) {
            val sessionFactory = get<NoiseSessionFactory>()
            RelayRepositoryCoordinator(
                connections = get<RelayConnectionSupervisor>().currentConnection,
                relayStatus = get<RelayConnectionSupervisor>().relayStatus,
                createPump = { transport -> NoiseSessionPump(transport, sessionFactory) },
                // #365: close #359's device_name: "" defer + supply the connect-time token read.
                deviceName = get<NoiseClientInfo>().deviceName,
                pushToken = { get<AppPreferences>().pushToken.first() },
            ).also { it.start() }
        }
        // #352: the stable facade ViewModels hold across connection churn — delegates to whichever
        // connection-scoped repo the coordinator publishes on currentRepository, switching on churn.
        // Registered as its own resolvable type only; conversationRepositoryModule (#350) flag-selects
        // whether this facade or the Fake wins the ConversationRepository binding.
        single { StableConversationRepository(get<RelayRepositoryCoordinator>().currentRepository) }
        viewModel { ScannerViewModel() }
        viewModel { ChannelListViewModel(get(), get()) }
        viewModel { DiscussionListViewModel(get()) }
        viewModel {
            SettingsViewModel(get(), get(), get<RelayRepositoryCoordinator>().connectionStatus)
        }
        viewModel { ArchivedDiscussionsViewModel(get()) }
        viewModel { ThreadViewModel(get(), get(), get(), get()) }
        // #381: resolvable so #382's nav destination can obtain it (get() → SavedStateHandle +
        // ConversationRepository). Per-conversation scoping of the obtained instance is #382's job.
        viewModel { LiteralScreenViewModel(get(), get()) }
    }

/**
 * Binds the [ConversationRepository] interface, flag-selecting the implementation (#350). This is the
 * **only** definition that binds the interface — [appModule] registers both candidates concrete-only.
 *
 * [useRelay] defaults to the compile-time [BuildConfig.USE_RELAY_REPOSITORY] (OFF), so the production
 * graph binds [FakeConversationRepository] until the relay backend is functional end-to-end. The
 * selector only resolves the two already-registered singletons by type — it never constructs either,
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
