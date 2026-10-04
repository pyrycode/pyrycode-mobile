package de.pyryco.mobile.e2e

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.StableConversationRepository
import de.pyryco.mobile.di.appModule
import de.pyryco.mobile.di.conversationRepositoryModule
import de.pyryco.mobile.di.hostConversationModule
import de.pyryco.mobile.lifecycle.LifecycleConnectionDriver
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Test Application installed by [E2eInstrumentationRunner] for every instrumented run.
 *
 * It reads the instrumentation arguments in [onCreate] — which runs **after** the instrumentation has
 * registered them (unlike the runner's `newApplication`, which runs before) — and branches:
 *
 *  * **No e2e relay arguments** → explicitly starts Koin with the fake repository binding,
 *    independent of the production build mode. Existing component tests keep their fake environment.
 *  * **e2e relay arguments present** (the interactive-stream prototype, #337 / #642 rung 3) → it
 *    1. binds the real relay-backed repository via [tappedRelayRepositoryModule] — the runtime equivalent
 *       of flipping the compile-time `USE_RELAY_REPOSITORY`, plus #586's parser-gap tap; and
 *    2. pre-writes a [PairedServer] built from the arguments into the **same** [PairedServerStore] the
 *       app reads, so the app boots straight to the channel list (no QR scan) and dials the host relay
 *       at `ws://10.0.2.2:<port>` — the emulator's alias for the host loopback.
 *
 * No secret is hardcoded: the token + keys arrive as arguments minted at run time by `pyry pair` (see
 * `scripts/e2e-emulator.sh`).
 */
class E2eTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val args = InstrumentationRegistry.getArguments()
        val relayUrl = args.getString(ARG_RELAY_URL)
        if (relayUrl == null) {
            // Ordinary instrumented runs stay fake-backed in both real and demo builds.
            Log.i("E2E", "app.onCreate: no relayUrl arg → FAKE repository mode")
            startKoin {
                androidContext(this@E2eTestApplication)
                modules(appModule, conversationRepositoryModule(useRelay = false))
            }
            return
        }
        Log.i("E2E", "app.onCreate: relayUrl=$relayUrl → RELAY-backed mode; pairing serverId=${args.getString(ARG_SERVER_ID)}")
        // e2e run: bind the relay-backed repository and pre-pair from the injected arguments.
        val paired =
            PairedServer(
                serverId = requireArg(args, ARG_SERVER_ID),
                token = requireArg(args, ARG_TOKEN),
                relayUrl = relayUrl,
                serverStaticPublicKey = requireArg(args, ARG_SERVER_STATIC_PUBLIC_KEY),
            )
        val koin =
            startKoin {
                androidContext(this@E2eTestApplication)
                modules(appModule, tappedRelayRepositoryModule())
            }.koin
        // Persist into the SAME store singleton MainActivity reads, so load() != null → channel list.
        runBlocking {
            val store = koin.get<PairedServerStore>()
            store.save(paired)
            val readBack = store.load()
            Log.i(
                "E2E",
                "app.onCreate: pairing saved; load() readBack=${if (readBack != null) "OK serverId=${readBack.serverId}" else "NULL (save did not persist!)"}",
            )
        }
    }

    /**
     * The app restart an instrumented test can perform (#847): it cannot kill its own process, so it
     * rebuilds the run's original fake/relay object graph over the same on-device state — the Keystore-backed
     * paired-server store, the host-keyed conversation cache and `app_prefs`. Nothing is re-saved: the
     * hosts the store already holds are the state under test. Call with no activity alive (the old
     * view models hold the old graph) and on the main thread, which `ProcessLifecycleOwner` requires.
     *
     * Two carry-overs keep the rebuild honest. The running `DataStore` is handed to the new graph,
     * because `appModule` creates it with no `onClose` and a second instance over `app_prefs` throws
     * "multiple DataStores active". And the old [LifecycleConnectionDriver] is unregistered first, so
     * lifecycle edges reach only the new registry. `stopKoin` disposes the old registry and host sources
     * through their `onClose`, closing every old socket.
     */
    fun rebuildGraph() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "rebuildGraph must run on the main thread" }
        val old = GlobalContext.get()
        val preferences = old.get<DataStore<Preferences>>()
        val repositoryModule =
            if (InstrumentationRegistry.getArguments().getString(ARG_RELAY_URL) == null) {
                conversationRepositoryModule(useRelay = false)
            } else {
                tappedRelayRepositoryModule()
            }
        ProcessLifecycleOwner.get().lifecycle.removeObserver(old.get<LifecycleConnectionDriver>())
        stopKoin()
        startKoin {
            allowOverride(true)
            androidContext(this@E2eTestApplication)
            modules(appModule, repositoryModule, module { single<DataStore<Preferences>> { preferences } })
        }
        Log.i("E2E", "app.rebuildGraph: object graph rebuilt over the same on-device state")
    }

    /**
     * The relay branch's [ConversationRepository] binding: exactly what
     * `conversationRepositoryModule(useRelay = true)` binds (see `AppModule.kt:156-161`), wrapped in
     * #586's [TappingConversationRepository] so the parser-gap sentinel can observe the thread the app is
     * already subscribed to.
     *
     * It **replaces** that module rather than overriding it from a second one: Koin 4's override
     * semantics are not something an e2e harness should depend on, and replacement is unambiguous. The
     * cost is that the two definitions can now drift — keep this one a mirror of `AppModule.kt:156-161`.
     * Non-circular because `appModule` registers [StableConversationRepository] as its own concrete type
     * and `conversationRepositoryModule` is the only definition binding the interface.
     *
     * The no-relay branch above is untouched: the tap ships only where a real daemon can produce the frame.
     */
    private fun tappedRelayRepositoryModule(): Module =
        module {
            includes(hostConversationModule(useRelay = true, decorateRepository = ::TappingConversationRepository))
            single<ConversationRepository> { TappingConversationRepository(get<StableConversationRepository>()) }
        }

    private fun requireArg(
        args: Bundle,
        key: String,
    ): String =
        requireNotNull(args.getString(key)) {
            "missing instrumentation arg '$key' — pass it with -e $key <value> (scripts/e2e-emulator.sh does this)"
        }

    companion object {
        const val ARG_RELAY_URL = "relayUrl"
        const val ARG_TOKEN = "token"
        const val ARG_SERVER_ID = "serverId"
        const val ARG_SERVER_STATIC_PUBLIC_KEY = "serverStaticPublicKey"
    }
}
