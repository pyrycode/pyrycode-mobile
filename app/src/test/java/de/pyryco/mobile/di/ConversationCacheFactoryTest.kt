package de.pyryco.mobile.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.pyryco.mobile.data.cache.MigratingConversationCache
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #2161: the bound cache keeps both stores under `noBackupFilesDir`: thread families in the Room database and its
 * sidecars, lists in the file root. Nothing lands under the backed-up `filesDir`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConversationCacheFactoryTest {
    private val row = ThreadItem.MessageItem(Message("m", "s", Role.User, "probe", Instant.parse("2026-10-17T00:00:00Z"), false))

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
            cache.writeThread("probe-host", "probe", listOf(row)).getOrThrow()

            assertEquals(listOf(row), openConversationCache(context).readThread("probe-host", "probe"))
            assertTrue(File(context.noBackupFilesDir, "conversation-cache.db").isFile)
            assertTrue(files(File(context.noBackupFilesDir, "conversations")).any { it.endsWith("conversations.json") })
            // The thread went to Room: the file root holds no thread document for it.
            assertTrue(files(File(context.noBackupFilesDir, "conversations")).none { "/threads/" in it })
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
            cache.writeThread("probe-host", "probe", listOf(row)).getOrThrow()
            store.remove("probe-host")
            assertTrue(cache.writeThread("probe-host", "probe", listOf(row)).isFailure)
            store.save(PairedServer("probe-host", "token", "wss://relay.invalid", "key"))
            cache.writeThread("probe-host", "probe", listOf(row)).getOrThrow()
            assertEquals(listOf(row), cache.readThread("probe-host", "probe"))
        }

    private fun files(root: File): Set<String> =
        root
            .walkTopDown()
            .filter { it.isFile }
            .map { it.path }
            .toSet()
}
