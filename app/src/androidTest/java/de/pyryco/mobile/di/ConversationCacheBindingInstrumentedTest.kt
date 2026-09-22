package de.pyryco.mobile.di

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.cache.ConversationCache
import de.pyryco.mobile.data.model.Conversation
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File

/**
 * Holds the one property #795 could not prove for itself: the app binds the conversation cache under
 * `Context.noBackupFilesDir`, never `Context.filesDir` (#796).
 *
 * The manifest ships `android:allowBackup="true"` with empty backup and data-extraction rules, so a
 * root under `filesDir` would carry cached conversation names and cwds into cloud backup and
 * device-to-device transfer — while the Keystore-wrapped pairing credentials that authorize reading
 * that content do not transfer. The restored device would render one machine's conversations to
 * someone who never paired the host and cannot reach it. `noBackupFilesDir` is excluded from both
 * paths by definition, which keeps the cache exactly as transferable as the credentials it belongs to.
 *
 * Black-box on purpose: [ConversationCache] exposes no root, so the proof writes through the bound
 * instance and inspects where the bytes landed. The two directories are siblings
 * (`.../files` and `.../no_backup`), so the presence and absence assertions are independent.
 */
@RunWith(AndroidJUnit4::class)
class ConversationCacheBindingInstrumentedTest {
    @Test
    fun boundCacheWritesUnderNoBackupFilesDirAndNeverUnderFilesDir() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = GlobalContext.get().get<ConversationCache>()
        val serverId = "conversation-cache-binding-probe"
        val row =
            Conversation(
                id = "probe",
                name = null,
                cwd = "/probe",
                currentSessionId = "session",
                sessionHistory = emptyList(),
                isPromoted = true,
                lastUsedAt = Instant.parse("2026-09-22T00:00:00Z"),
            )
        val filesBefore = documents(context.filesDir)
        try {
            runBlocking {
                assertTrue("bound cache rejected a write", cache.writeConversations(serverId, listOf(row)).isSuccess)
                assertEquals(listOf("probe"), cache.readConversations(serverId).map { it.id })
            }
            assertTrue(
                "bound cache stored nothing under noBackupFilesDir",
                documents(context.noBackupFilesDir).isNotEmpty(),
            )
            assertEquals(
                "bound cache wrote a conversation document under the backed-up filesDir",
                filesBefore,
                documents(context.filesDir),
            )
        } finally {
            runBlocking { cache.removeHost(serverId) }
        }
    }

    private fun documents(root: File): Set<String> =
        root
            .walkTopDown()
            .filter { it.isFile && it.name == "conversations.json" }
            .map { it.path }
            .toSet()
}
