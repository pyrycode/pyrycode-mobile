package de.pyryco.mobile.e2e

import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.di.appModule
import de.pyryco.mobile.di.conversationRepositoryModule
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * Test Application installed by [E2eInstrumentationRunner] for every instrumented run.
 *
 * It reads the instrumentation arguments in [onCreate] — which runs **after** the instrumentation has
 * registered them (unlike the runner's `newApplication`, which runs before) — and branches:
 *
 *  * **No e2e relay arguments** → behaves exactly like the production [de.pyryco.mobile.PyryApp]:
 *    starts Koin with the default (fake) repository binding. This keeps every existing component test
 *    on its unchanged environment.
 *  * **e2e relay arguments present** (the interactive-stream prototype, #337 / #642 rung 3) → it
 *    1. binds the real relay-backed repository (`conversationRepositoryModule(useRelay = true)`), the
 *       runtime equivalent of flipping the compile-time `USE_RELAY_REPOSITORY`; and
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
            // Ordinary instrumented run: identical to PyryApp (default fake repository binding).
            Log.i("E2E", "app.onCreate: no relayUrl arg → FAKE repository mode (PyryApp-equivalent)")
            startKoin {
                androidContext(this@E2eTestApplication)
                modules(appModule, conversationRepositoryModule())
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
                modules(appModule, conversationRepositoryModule(useRelay = true))
            }.koin
        // Persist into the SAME store singleton MainActivity reads, so load() != null → channel list.
        runBlocking {
            val store = koin.get<PairedServerStore>()
            store.save(paired)
            val readBack = store.load()
            Log.i("E2E", "app.onCreate: pairing saved; load() readBack=${if (readBack != null) "OK serverId=${readBack.serverId}" else "NULL (save did not persist!)"}")
        }
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
