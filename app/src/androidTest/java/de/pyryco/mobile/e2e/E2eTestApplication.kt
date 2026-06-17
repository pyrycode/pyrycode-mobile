package de.pyryco.mobile.e2e

import android.app.Application
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.di.appModule
import de.pyryco.mobile.di.conversationRepositoryModule
import kotlinx.coroutines.runBlocking
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

/**
 * Test-only [Application] for the interactive-stream e2e prototype (#337 / #642 rung 3). It is
 * substituted for the production [de.pyryco.mobile.PyryApp] by [E2eInstrumentationRunner] **only**
 * when the instrumentation is started with the e2e relay arguments, so it never affects an ordinary
 * `connectedAndroidTest` run. It diverges from `PyryApp` in exactly two ways:
 *
 *  1. It binds the **real** relay-backed repository (`conversationRepositoryModule(useRelay = true)`)
 *     instead of the compile-time-flagged fake, so the structured live stream is actually exercised.
 *     This is the runtime equivalent of flipping `USE_RELAY_REPOSITORY`, scoped to the e2e run.
 *  2. It pre-writes a [PairedServer] built from the instrumentation arguments into the **same**
 *     [PairedServerStore] the app reads, so the app boots straight to the channel list (no QR scan)
 *     and dials the host relay at `ws://10.0.2.2:<port>` — the emulator's alias for the host loopback.
 *
 * No secret is hardcoded: the device token and the server keys arrive as instrumentation arguments
 * that `scripts/e2e-emulator.sh` mints at run time via `pyry pair`.
 */
class E2eTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val args = InstrumentationRegistry.getArguments()
        val paired =
            PairedServer(
                serverId = requireArg(args, ARG_SERVER_ID),
                token = requireArg(args, ARG_TOKEN),
                relayUrl = requireArg(args, ARG_RELAY_URL),
                serverStaticPublicKey = requireArg(args, ARG_SERVER_STATIC_PUBLIC_KEY),
            )
        // Start the production graph but force the relay-backed repository binding on.
        val koin =
            startKoin {
                androidContext(this@E2eTestApplication)
                modules(appModule, conversationRepositoryModule(useRelay = true))
            }.koin
        // Persist into the SAME store singleton MainActivity reads, so load() != null → the app starts
        // on the channel list. Blocking is fine here: one small write during Application.onCreate.
        runBlocking { koin.get<PairedServerStore>().save(paired) }
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
