package de.pyryco.mobile.di

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.koin.core.KoinApplication

/**
 * Owns the Koin containers a JVM unit test resolves a [HostConversationSource] from, and proves each
 * source stopped collecting before the owning test method returns (#726).
 *
 * The source Koin builds takes `hostConversationModule`'s `Dispatchers.Default` default, so its
 * collectors run on real worker threads outside the test scheduler, and a screen ViewModel resolved
 * from the same container subscribes to [HostConversationSource.snapshots] on a
 * `Dispatchers.Main`-bound `viewModelScope`. A publish that lands after the class calls
 * `Dispatchers.resetMain()` therefore resumes that subscriber into a torn-down dispatcher; the
 * resulting failure is raised on the worker thread and attaches to whichever test is running, not to
 * the one that leaked. Closing the container is not enough on its own — the close has to be ordered
 * before `resetMain()` and proven to have happened.
 *
 * Usage: resolve through [source], call [closeAndAssertStopped] in the test's own `finally`, and call
 * [assertAllClosed] from the class `@After` **before** `Dispatchers.resetMain()`.
 */
class KoinHostSources {
    private class Tracked(
        val app: KoinApplication,
        val source: HostConversationSource,
        val liveHostId: String,
    )

    private val tracked = mutableListOf<Tracked>()

    /**
     * Resolves the shared source from [app] and registers it for the disposal proof. [liveHostId] is
     * a host this container resolves a repository for while the source is alive, so the proof below
     * cannot pass vacuously.
     */
    fun source(
        app: KoinApplication,
        liveHostId: String = HostConversationSource.DEMO_SERVER_ID,
    ): HostConversationSource = app.koin.get<HostConversationSource>().also { tracked += Tracked(app, it, liveHostId) }

    /**
     * Closes every registered container and proves each source stopped collecting. Idempotent, so a
     * test's own `finally` and the class `@After` net can both call it.
     *
     * `repositoryFor` is the load-bearing check: it is `@Synchronized` on the same instance monitor
     * the worker threads take, so reading it here gives the happens-before edge, and it returns
     * `null` only once `dispose()` has set `disposed` — which means `scope.cancel()` already ran and
     * every later `reconcile`/`update` returns before it can publish. `snapshots` is a cheap
     * secondary check; it can read empty for a container with no hosts, so it is not the proof.
     */
    fun closeAndAssertStopped() {
        val closing = tracked.toList()
        tracked.clear()
        closing.forEach { it.app.close() }
        closing.forEach {
            assertNull("source still resolves ${it.liveHostId} after Koin close", it.source.repositoryFor(it.liveHostId))
            assertTrue(
                "source still holds snapshots after Koin close",
                it.source.snapshots.value
                    .isEmpty(),
            )
        }
    }

    /**
     * `@After` net. Closes anything the test left open first — a red test must not hand a live
     * `Dispatchers.Default` collector to the next test — then fails naming that omission.
     */
    fun assertAllClosed() {
        val leaked = tracked.size
        closeAndAssertStopped()
        assertTrue("test left $leaked Koin container(s) holding a HostConversationSource open", leaked == 0)
    }
}
