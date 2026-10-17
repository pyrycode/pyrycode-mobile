package de.pyryco.mobile.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.pyryco.mobile.data.cache.MigratingConversationCache
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
    @Test fun boundCacheStoresThreadsInRoomAndListsInFilesUnderNoBackupFilesDir() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val filesBefore = files(context.filesDir)
            val cache = openConversationCache(context)
            assertTrue(cache is MigratingConversationCache)
            val row = ThreadItem.MessageItem(Message("m", "s", Role.User, "probe", Instant.parse("2026-10-17T00:00:00Z"), false))
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

    private fun files(root: File): Set<String> =
        root
            .walkTopDown()
            .filter { it.isFile }
            .map { it.path }
            .toSet()
}
