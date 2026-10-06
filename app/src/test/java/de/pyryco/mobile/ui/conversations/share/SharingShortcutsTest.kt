package de.pyryco.mobile.ui.conversations.share

import android.content.Context
import android.content.pm.ShortcutManager
import androidx.test.core.app.ApplicationProvider
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.di.HostConversationSnapshot
import de.pyryco.mobile.notifications.NotificationTap
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SharingShortcutsTest {
    @get:Rule val folder = TemporaryFolder()

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
}
