package de.pyryco.mobile.ui.onboarding

import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.crypto.PairedServerStoreException
import de.pyryco.mobile.data.network.RelayConnectionController

/**
 * Orchestrates the Scanner confirm/paste side effect (#489): persist the freshly-paired [server], then
 * start the relay supervision loop so the connection comes up **immediately** — without the
 * background→foreground cycle that previously kick-started [LifecycleConnectionDriver]'s
 * `onStart → connect()`.
 *
 * Extracted as a free, Android-free `suspend fun` (it holds no `NavController`, no `Context`, no VM)
 * so this ordering is unit-testable on its own. Its callers, [ScannerViewModel] and [PairCodeViewModel],
 * launch it on their own scope and then wait for the saved record's status.
 *
 * Ordering — `save → connect → onPersisted`:
 * - [controller].connect() runs **only after** [store].save returns, so a persist that throws never
 *   dials a record that did not persist (AC 1 fail-closed): the connection always tracks a record that
 *   is actually on disk and re-loadable after process death.
 * - connect() is called **before** [onPersisted] because both callers then wait for the saved record's
 *   status (#1385, #1386), which needs a started loop. connect() launches the loop on the supervisor's own
 *   scope, so a cancelled caller does not race the loop start; it is idempotent, so a re-pair while a loop
 *   is already running is a no-op (the reload-per-dial change switches servers on the next dial instead).
 *
 * The `catch` is narrow ([PairedServerStoreException] only) so a `CancellationException` from a
 * cancelled save propagates rather than being swallowed. Logging stays in the caller's [onFailed] so
 * this function is log-free (and thus honours the transport's no-secret-logging posture).
 */
suspend fun confirmPairingAndConnect(
    server: PairedServer,
    store: PairedServerStore,
    controller: RelayConnectionController,
    onPersisted: () -> Unit,
    onFailed: (PairedServerStoreException) -> Unit,
) {
    try {
        store.save(server)
        controller.connect()
        onPersisted()
    } catch (e: PairedServerStoreException) {
        onFailed(e)
    }
}
