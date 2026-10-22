package de.pyryco.mobile.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.pyryco.mobile.data.cache.MigratingConversationCache
import de.pyryco.mobile.data.cache.SavedAuthoritativeThread
import de.pyryco.mobile.data.cache.SavedThreadCoverage
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.network.MobileJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #2161: the bound cache keeps both stores under `noBackupFilesDir`: thread families in the Room database and its
 * sidecars, lists in the file root. Nothing lands under the backed-up `filesDir`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConversationCacheFactoryTest {
    private val saved =
        SavedAuthoritativeThread(
            "epoch",
            listOf(
                MobileJson
                    .parseToJsonElement(
                        """{"id":1,"rev":2,"kind":"future-kind","status":"done","active":false,"shown":true,"summary":"probe","content":{},"order":3}""",
                    ).jsonObject,
            ),
            5,
            4,
            SavedThreadCoverage(3, 3, 3, true),
        )

    @Test fun boundCacheStoresThreadsInRoomAndListsInFilesUnderNoBackupFilesDir() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val filesBefore = files(context.filesDir)
            val cache = openConversationCache(context)
            assertTrue(cache is MigratingConversationCache)
            val conversation =
                Conversation(
                    id = "probe",
                    name = null,
                    cwd = "/probe",
                    currentSessionId = "session",
                    sessionHistory = emptyList(),
                    isPromoted = true,
                    lastUsedAt = Instant.parse("2026-10-17T00:00:00Z"),
                )
            cache.writeConversations("probe-host", listOf(conversation)).getOrThrow()
            cache.writeAuthoritativeThread("probe-host", "probe", saved).getOrThrow()

            assertEquals(saved, openConversationCache(context).readAuthoritativeThread("probe-host", "probe"))
            assertTrue(File(context.noBackupFilesDir, "conversation-cache.db").isFile)
            assertTrue(files(File(context.noBackupFilesDir, "conversations")).any { it.endsWith("conversations.json") })
            // The thread went to Room: the file root holds no thread document for it.
            assertTrue(files(File(context.noBackupFilesDir, "conversations")).none { "/threads/" in it || "/authoritative-threads/" in it })
            assertEquals(filesBefore, files(context.filesDir))
        }

    /** Unpairing fences the forgotten host's thread saves; only saving its pairing again readmits them. */
    @Test fun repairInvariant_savedPairingReadmitsTheForgottenHostsThreadWrites() =
        runBlocking {
            val cache = openConversationCache(ApplicationProvider.getApplicationContext())
            val pairings =
                object : PairedServerCollectionStore {
                    override suspend fun load(): PairedServer? = null

                    override suspend fun save(record: PairedServer) = Unit

                    override suspend fun list(): List<PairedServerEntry> = emptyList()

                    override suspend fun loadById(serverId: String): PairedServerEntry? = null

                    override suspend fun setDisplayName(
                        serverId: String,
                        displayName: String?,
                    ) = Unit

                    override suspend fun remove(serverId: String) = Unit
                }
            val store = ObservablePairedServerStore(pairings, admitSavedHost(lazyOf(cache))) { cache.removeHost(it) }
            cache.writeAuthoritativeThread("probe-host", "probe", saved).getOrThrow()
            store.remove("probe-host")
            assertTrue(cache.writeAuthoritativeThread("probe-host", "probe", saved).isFailure)
            store.save(PairedServer("probe-host", "token", "wss://relay.invalid", "key"))
            cache.writeAuthoritativeThread("probe-host", "probe", saved).getOrThrow()
            assertEquals(saved, cache.readAuthoritativeThread("probe-host", "probe"))
        }

    @Test fun repairInvariant_cancelledSavedPairingFinishesAdmissionAfterMutexWait() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val database = openConversationCacheDatabase(context)
            try {
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val hold = AtomicBoolean(false)
                val cache =
                    MigratingConversationCache(File(context.noBackupFilesDir, "conversations"), database, Dispatchers.IO) {
                        if (hold.compareAndSet(true, false)) {
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                var persisted: PairedServer? = null
                val pairings =
                    object : PairedServerCollectionStore {
                        override suspend fun load(): PairedServer? = persisted

                        override suspend fun save(record: PairedServer) {
                            persisted = record
                        }

                        override suspend fun list(): List<PairedServerEntry> = listOfNotNull(persisted?.let(::PairedServerEntry))

                        override suspend fun loadById(serverId: String): PairedServerEntry? = list().find { it.record.serverId == serverId }

                        override suspend fun setDisplayName(
                            serverId: String,
                            displayName: String?,
                        ) = Unit

                        override suspend fun remove(serverId: String) {
                            persisted = null
                        }
                    }
                val admissionEntered = CompletableDeferred<Unit>()
                val admit = admitSavedHost(lazyOf(cache))
                val store =
                    ObservablePairedServerStore(pairings, {
                        admissionEntered.complete(Unit)
                        admit(it)
                    }) { cache.removeHost(it) }
                store.remove("probe-host")
                assertTrue(cache.writeAuthoritativeThread("probe-host", "probe", saved).isFailure)
                hold.set(true)
                val holder = launch { cache.writeAuthoritativeThread("other-host", "probe", saved).getOrThrow() }
                entered.await()
                try {
                    val record = PairedServer("probe-host", "token", "wss://relay.invalid", "key")
                    // Undispatched start reaches the held cache mutex before returning, proving contention without a delay.
                    val saver = launch(start = CoroutineStart.UNDISPATCHED) { store.save(record) }
                    admissionEntered.await()
                    assertEquals(record, store.load())
                    assertEquals(2L, store.revision.value)
                    saver.cancel()
                    release.complete(Unit)
                    holder.join()
                    saver.join()
                    assertTrue(saver.isCancelled)
                    cache.writeAuthoritativeThread("probe-host", "probe", saved).getOrThrow()
                    assertEquals(saved, cache.readAuthoritativeThread("probe-host", "probe"))
                } finally {
                    release.complete(Unit)
                    holder.join()
                }
            } finally {
                database.close()
            }
        }

    private fun files(root: File): Set<String> =
        root
            .walkTopDown()
            .filter { it.isFile }
            .map { it.path }
            .toSet()
}
