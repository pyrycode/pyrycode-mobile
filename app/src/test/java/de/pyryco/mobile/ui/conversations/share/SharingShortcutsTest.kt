package de.pyryco.mobile.ui.conversations.share

import android.content.Context
import android.content.pm.ShortcutManager
import androidx.test.core.app.ApplicationProvider
import de.pyryco.mobile.data.cache.AttachmentStore
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.di.InertConversationCache
import de.pyryco.mobile.di.ObservablePairedServerStore
import de.pyryco.mobile.di.forgetRemovedHost
import de.pyryco.mobile.di.hostConversationModule
import de.pyryco.mobile.di.sharingShortcutHosts
import de.pyryco.mobile.notifications.NotificationTap
import de.pyryco.mobile.startApplicationGraph
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import de.pyryco.mobile.ui.conversations.thread.ComposerDraftStore
import de.pyryco.mobile.ui.conversations.thread.McpFailureAcknowledgements
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowShortcutManager
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SharingShortcutsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun failedStartupRecoversWithoutRevisionAndReconcilesLatestRowsBeforeOpening() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val manager = context.getSystemService(ShortcutManager::class.java)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val kept = HostConversationTarget("a", "same")
            val removed = HostConversationTarget("b", "same")
            val opened = HostConversationTarget("a", "new")
            val snapshots = MutableStateFlow(listOf(host("a", listOf(row("same"))), host("b", listOf(row("same")))))
            val file = folder.newFile("recover")
            file.writeText(
                RecentShareTargets(4)
                    .apply {
                        open(kept, "old")
                        open(removed, "old")
                    }.encode(),
            )
            var publisher = SharingShortcuts(context, snapshots, MutableStateFlow(setOf("a", "b")), file, dispatcher)
            runCurrent()
            publisher.dispose()
            val persisted = file.readText()
            val published = manager.dynamicShortcuts.map { Triple(it.id, it.rank, it.shortLabel.toString()) }
            val raw = RecoveringHostStore()
            var failed = true
            raw.read = { if (failed) Result.failure(Exception("unavailable")) else Result.success(raw.list()) }
            val store = ObservablePairedServerStore(raw) { }
            publisher = SharingShortcuts(context, snapshots, store.sharingShortcutHosts(), file, dispatcher)
            try {
                val opening = backgroundScope.async(dispatcher) { publisher.opened(opened) }
                runCurrent()
                snapshots.value = listOf(host("a", listOf(row("same", " renamed\u0000 "), row("new"))), host("b", emptyList()))
                advanceTimeBy(1_000)
                runCurrent()
                assertEquals(2, raw.reads)
                assertEquals(persisted, file.readText())
                assertEquals(published, manager.dynamicShortcuts.map { Triple(it.id, it.rank, it.shortLabel.toString()) })
                assertTrue(!opening.isCompleted)

                failed = false
                advanceTimeBy(1_000)
                runCurrent()
                assertTrue(opening.isCompleted)
                opening.await()
                assertEquals(0L, store.revision.value)
                val ledger = RecentShareTargets(4, file.readText())
                assertEquals(listOf(opened, kept), ledger.entries.map { it.target })
                assertEquals("renamed", ledger.entries.last().label)
                assertEquals(kept, publisher.resolve(ledger.id(kept)))
                assertEquals(opened, publisher.resolve(ledger.id(opened)))
                assertNull(publisher.resolve(ledger.id(removed)))
                assertEquals(
                    listOf(opened, kept),
                    manager.dynamicShortcuts.sortedBy { it.rank }.map { NotificationTap.target(it.intent) },
                )
                advanceTimeBy(10_000)
                runCurrent()
                assertEquals(3, raw.reads)
            } finally {
                publisher.dispose()
            }
        }

    @Test fun newRevisionCancelsObsoleteReadAndRetryWhileDisposalStopsRecovery() =
        runTest {
            val raw = RecoveringHostStore()
            var cancelledReads = 0
            var failed = true
            raw.read = {
                if (raw.reads == 1) {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelledReads++
                    }
                }
                if (failed) Result.failure(Exception("unavailable")) else Result.success(raw.list())
            }
            val store = ObservablePairedServerStore(raw) { }
            val emissions = mutableListOf<Set<String>>()
            val collection = backgroundScope.launch { store.sharingShortcutHosts().collect { emissions += it } }
            runCurrent()
            assertEquals(1, raw.reads)
            store.setDisplayName("a", "revision")
            runCurrent()
            assertEquals(1, cancelledReads)
            assertEquals(2, raw.reads)
            assertTrue(emissions.isEmpty())
            advanceTimeBy(999)
            runCurrent()
            assertEquals(2, raw.reads)
            failed = false
            store.setDisplayName("a", "next revision")
            runCurrent()
            assertEquals(3, raw.reads)
            assertEquals(listOf(setOf("a", "b")), emissions)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(3, raw.reads)
            failed = true
            store.setDisplayName("a", "failed revision")
            runCurrent()
            assertEquals(4, raw.reads)
            assertEquals(listOf(setOf("a", "b")), emissions)
            collection.cancel()
            runCurrent()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(4, raw.reads)
        }

    @Config(shadows = [RecordingShortcutManager::class])
    @Test
    fun confirmedUnpairCannotBeLostWhenRepairPrecedesTheRevisionObserver() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val a = HostConversationTarget("a", "same")
            val b = HostConversationTarget("b", "same")
            var saved = setOf("a", "b")
            val raw =
                object : PairedServerCollectionStore {
                    override suspend fun list() = saved.map { PairedServerEntry(PairedServer(it, "", "", "")) }

                    override suspend fun load() = list().lastOrNull()?.record

                    override suspend fun loadById(serverId: String) = list().find { it.record.serverId == serverId }

                    override suspend fun save(record: PairedServer) {
                        saved += record.serverId
                    }

                    override suspend fun remove(serverId: String) {
                        saved -= serverId
                    }

                    override suspend fun setDisplayName(
                        serverId: String,
                        displayName: String?,
                    ) = Unit
                }
            val snapshots = MutableStateFlow(listOf(host("a", listOf(row("same"))), host("b", listOf(row("same")))))
            val file = folder.newFile("unpair")
            lateinit var publisher: SharingShortcuts
            val store =
                ObservablePairedServerStore(
                    raw,
                    forgetRemovedHost(
                        ComposerDraftStore(),
                        McpFailureAcknowledgements(),
                        lazyOf(InertConversationCache),
                        lazyOf(AttachmentStore(folder.newFolder("attachments"), dispatcher)),
                        { publisher.removeHost(it) },
                    ),
                )
            publisher =
                SharingShortcuts(context, snapshots, store.revision.map { raw.list().map { it.record.serverId }.toSet() }, file, dispatcher)
            val id = RecentShareTargets(4).id(a)
            RecordingShortcutManager.removed.clear()
            try {
                runCurrent()
                publisher.opened(a)
                publisher.opened(b)
                withContext(dispatcher) {
                    store.remove("a")
                    store.save(PairedServer("a", "", "", ""))
                }
                runCurrent()
                assertNull(publisher.resolve(id))
                assertEquals(listOf(b), RecentShareTargets(4, file.readText()).entries.map { it.target })
                assertEquals(
                    listOf(b),
                    context.getSystemService(ShortcutManager::class.java).dynamicShortcuts.mapNotNull {
                        NotificationTap.target(it.intent)
                    },
                )
                assertTrue(id in RecordingShortcutManager.removed)
                snapshots.value = snapshots.value.map { it.copy(displayName = "reconnected") }
                runCurrent()
                assertNull(publisher.resolve(id))
            } finally {
                publisher.dispose()
            }
        }

    @Test fun applicationStartupRefreshesAndRemovesTargetsWithoutAnyActivity() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = FakeConversationRepository()
            val target = HostConversationTarget("demo", "seed-channel-personal")
            val file = File(context.noBackupFilesDir, "sharing_shortcuts")
            file.writeText(RecentShareTargets(4).apply { open(target, "old name") }.encode())
            stopKoin()
            val app =
                startApplicationGraph(
                    context,
                    listOf(
                        hostConversationModule(false),
                        module {
                            single { repository }
                        },
                    ),
                )
            try {
                val manager = context.getSystemService(ShortcutManager::class.java)
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    kotlinx.coroutines.withTimeout(5_000) {
                        while (manager.dynamicShortcuts.none { it.shortLabel.toString() == "Personal" }) {
                            kotlinx.coroutines.delay(10)
                        }
                        repository.rename(target.conversationId, "renamed")
                        while (manager.dynamicShortcuts.none { it.shortLabel.toString() == "renamed" }) {
                            kotlinx.coroutines.delay(10)
                        }
                        repository.archive(target.conversationId)
                        while (manager.dynamicShortcuts.isNotEmpty()) {
                            kotlinx.coroutines.delay(10)
                        }
                    }
                }
                assertTrue(RecentShareTargets(4, file.readText()).entries.isEmpty())
            } finally {
                app.close()
                stopKoin()
            }
        }

    @Test fun publisherRestartsWithoutErasingLoadingHostsAndRemovesOnlyAuthoritativeAbsence() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val manager = context.getSystemService(ShortcutManager::class.java)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val snapshots = MutableStateFlow(listOf(host("a", listOf(row("same"))), host("b", listOf(row("same")))))
            val saved = MutableStateFlow(setOf("a", "b"))
            val file = folder.newFile("recent")
            var publisher = SharingShortcuts(context, snapshots, saved, file, dispatcher)
            val a = HostConversationTarget("a", "same")
            val b = HostConversationTarget("b", "same")
            val id = RecentShareTargets(4).id(a)
            try {
                runCurrent()
                publisher.opened(a)
                publisher.opened(b)
                assertEquals(setOf(a, b), manager.dynamicShortcuts.mapNotNull { NotificationTap.target(it.intent) }.toSet())
                publisher.dispose()
                snapshots.value = emptyList()
                publisher = SharingShortcuts(context, snapshots, saved, file, dispatcher)
                runCurrent()
                assertEquals(a, publisher.resolve(id))
                snapshots.value = listOf(host("a", emptyList(), loaded = false), host("b", listOf(row("same"))))
                runCurrent()
                assertEquals(a, publisher.resolve(id))
                snapshots.value = listOf(host("a", listOf(row("same", " renamed\u0000 "))), host("b", listOf(row("same"))))
                runCurrent()
                assertEquals(
                    "renamed",
                    manager.dynamicShortcuts
                        .single { it.id == id }
                        .shortLabel
                        .toString(),
                )
                assertEquals(1, manager.dynamicShortcuts.single { it.id == id }.rank)
                snapshots.value = listOf(host("a", emptyList()), host("b", listOf(row("same"))))
                runCurrent()
                assertNull(publisher.resolve(id))
                assertEquals(listOf(b), manager.dynamicShortcuts.mapNotNull { NotificationTap.target(it.intent) })
                snapshots.value = listOf(host("a", listOf(row("same"))), host("b", listOf(row("same"))))
                runCurrent()
                assertNull(publisher.resolve(id))
                saved.value = setOf("a")
                runCurrent()
                assertTrue(manager.dynamicShortcuts.isEmpty())
            } finally {
                publisher.dispose()
            }
        }

    @Test fun platformPublicationNeverKeepsAnEvictedPairAndPersistedIdsRemainDistinct() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val manager = context.getSystemService(ShortcutManager::class.java)
            val rows = (0..5).map { row("$it") }
            val snapshots = MutableStateFlow(listOf(host("a", rows)))
            val publisher =
                SharingShortcuts(
                    context,
                    snapshots,
                    MutableStateFlow(setOf("a")),
                    folder.newFile("recent"),
                    StandardTestDispatcher(testScheduler),
                )
            try {
                runCurrent()
                rows.forEach { publisher.opened(HostConversationTarget("a", it.id)) }
                assertEquals(4, manager.dynamicShortcuts.size)
                assertEquals(
                    listOf("5", "4", "3", "2"),
                    manager.dynamicShortcuts
                        .sortedBy {
                            it.rank
                        }.map { NotificationTap.target(it.intent)?.conversationId },
                )
                assertTrue(manager.manifestShortcuts.isEmpty())
                assertTrue(manager.pinnedShortcuts.isEmpty())
                assertTrue(manager.dynamicShortcuts.all { it.categories == setOf(SHARE_CATEGORY) })
                assertNull(publisher.resolve(RecentShareTargets(4).id(HostConversationTarget("a", "0"))))
            } finally {
                publisher.dispose()
            }
        }

    private fun row(
        id: String,
        name: String = "name",
    ) = Conversation(id, name, "/w", "s", emptyList(), true, Instant.fromEpochMilliseconds(0))

    private fun host(
        id: String,
        rows: List<Conversation>,
        loaded: Boolean = true,
    ) = HostConversationSnapshot(
        id,
        null,
        ConnectionStatus(RelayLinkStatus.Offline, PyrycodeLinkStatus.Down),
        channels = rows,
        rowsLoaded = loaded,
    )

    private class RecoveringHostStore : PairedServerCollectionStore {
        var reads = 0
        var read: suspend () -> Result<List<PairedServerEntry>> = { Result.success(list()) }

        override suspend fun readSnapshot(): Result<List<PairedServerEntry>> {
            reads++
            return read()
        }

        override suspend fun list() = listOf("a", "b").map { PairedServerEntry(PairedServer(it, "", "", "")) }

        override suspend fun load() = list().last().record

        override suspend fun loadById(serverId: String) = list().find { it.record.serverId == serverId }

        override suspend fun save(record: PairedServer) = Unit

        override suspend fun remove(serverId: String) = Unit

        override suspend fun setDisplayName(
            serverId: String,
            displayName: String?,
        ) = Unit
    }
}

@Implements(ShortcutManager::class)
class RecordingShortcutManager : ShadowShortcutManager() {
    companion object {
        val removed = mutableListOf<String>()
    }

    @Implementation override fun removeLongLivedShortcuts(shortcutIds: List<String>) {
        removed += shortcutIds
        super.removeLongLivedShortcuts(shortcutIds)
    }
}
