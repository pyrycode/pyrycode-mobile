package de.pyryco.mobile.ui.conversations.share

import android.content.ContextWrapper
import android.content.pm.ShortcutManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.crypto.KeystorePairedServerStore
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.di.HostConversationSource
import de.pyryco.mobile.di.ObservablePairedServerStore
import de.pyryco.mobile.di.hostConversationModule
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.dsl.onClose
import java.io.File
import java.io.IOException
import java.security.ProviderException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Recovery through the real encrypted store needs Android Keystore. No activity is launched. */
@RunWith(AndroidJUnit4::class)
class SharingShortcutStoreFailureTest {
    @Test fun failedStartupAndRevisionReadsPreservePersistedRecencyAndSystemTargetsUntilRecovery() =
        runBlocking {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(base.cacheDir, "shortcut-store-failure").apply { mkdirs() }
            val context =
                object : ContextWrapper(base) {
                    override fun getNoBackupFilesDir() = directory
                }
            val failure = AtomicReference<Exception?>()
            val reads = AtomicInteger()
            val data =
                object : DataStore<Preferences> {
                    var preferences = emptyPreferences()
                    override val data =
                        flow {
                            reads.incrementAndGet()
                            failure.get()?.let { throw it }
                            emit(preferences)
                        }

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                        preferences = transform(preferences)
                        return preferences
                    }
                }
            val store = ObservablePairedServerStore(KeystorePairedServerStore(data)) { }
            store.save(PairedServer("demo", "test", "wss://unused.example", "test"))

            fun graph() =
                koinApplication {
                    androidContext(context)
                    modules(
                        hostConversationModule(true),
                        module {
                            single { store }
                            single { HostConversationSource.demo(FakeConversationRepository()) } onClose { it?.dispose() }
                        },
                    )
                }

            suspend fun await(check: () -> Boolean) {
                withTimeout(10_000) { while (!check()) delay(10) }
            }
            val manager = context.getSystemService(ShortcutManager::class.java)
            val targets = listOf("seed-channel-personal", "seed-channel-pyrycode-mobile").map { HostConversationTarget("demo", it) }
            var app = graph()
            try {
                var publisher = app.koin.get<SharingShortcuts>()
                val source = app.koin.get<HostConversationSource>()
                await { source.snapshots.value.any { it.channels.size >= 2 } }
                targets.forEach { publisher.opened(it) }
                val file = File(directory, "sharing_shortcuts")
                val persisted = file.readText()

                fun projection() = manager.dynamicShortcuts.map { Triple(it.id, it.rank, it.shortLabel.toString()) }.toSet()
                val system = projection()
                for (error in listOf(IOException(), ProviderException(), IllegalArgumentException())) {
                    app.close()
                    failure.set(error)
                    val startupRead = reads.get()
                    app = graph()
                    publisher = app.koin.get()
                    await { reads.get() > startupRead }
                    delay(50)
                    assertEquals(persisted, file.readText())
                    assertEquals(system, projection())
                    val revisionRead = reads.get()
                    store.setDisplayName("demo", "revision")
                    await { reads.get() > revisionRead }
                    delay(50)
                    assertEquals(persisted, file.readText())
                    assertEquals(system, projection())
                    failure.set(null)
                    store.setDisplayName("demo", "recovered")
                    assertEquals(targets.first(), withTimeout(10_000) { publisher.resolve(RecentShareTargets(4).id(targets.first())) })
                    assertEquals(targets.reversed(), RecentShareTargets(4, file.readText()).entries.map { it.target })
                }
            } finally {
                app.close()
            }
        }
}
