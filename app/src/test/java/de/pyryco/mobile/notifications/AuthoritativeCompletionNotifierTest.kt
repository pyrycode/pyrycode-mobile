package de.pyryco.mobile.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.R
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.CAPABILITY_THREAD
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.RelayLog
import de.pyryco.mobile.data.repository.AuthoritativeThreadStore
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.RemoteConversationRepository
import de.pyryco.mobile.data.repository.SessionPump
import de.pyryco.mobile.di.AttentionAlert
import de.pyryco.mobile.di.HostConversationConnection
import de.pyryco.mobile.di.HostConversationSource
import de.pyryco.mobile.ui.conversations.list.HostConversationTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AuthoritativeCompletionNotifierTest {
    @get:Rule val folder = TemporaryFolder()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    @Test
    fun recoveredCompletionPostsCertifiedPrivatePreviewAndExactTap() =
        fixture {
            // A push wake has already reconnected: no running edge, legacy event or screen collector.
            val request = open()
            host.pump.recover(request)
            runCurrent()
            assertEquals(
                "catch-up must be certified before publication",
                3L,
                host.repository.authoritativeThreads
                    .current("a", "same")
                    .checkpoint,
            )
            assertEquals("admitted completion must be held for the notifier", 1, host.repository.authoritativeCompletions.value.size)
            val notification = shadowOf(manager).allNotifications.single()
            assertEquals("Certified private reply", notification.extras.getString(Notification.EXTRA_TEXT))
            assertEquals(
                app.getString(R.string.notification_turn_completed_neutral),
                notification.publicVersion.extras.getString(Notification.EXTRA_TEXT),
            )
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertEquals(HostConversationTarget("a", "same"), NotificationTap.target(shadowOf(notification.contentIntent).savedIntent))
            assertFalse(File(folder.root, "alerts").readText().contains("Certified"))
            assertTrue(host.pump.sent.none { it.type in setOf("request_history", "backfill_since") })
        }

    @Test
    fun unreadSummaryRecoversWithoutAnOpenThread() =
        fixture {
            host.summary(read = 0, shown = 2)
            runCurrent()
            host.pump.recover(host.pump.query())
            runCurrent()
            assertEquals(1, shadowOf(manager).allNotifications.size)
            host.summary(read = 0, shown = 2)
            runCurrent()
            assertEquals(2, host.pump.sent.count { it.type == "thread_catch_up" })
        }

    @Test
    fun partialAndRejectedRecoveryCannotPost() =
        fixture {
            open()
            val request = host.pump.query()
            host.pump.batch(request, 3, listOf(item(2, "assistant_message"), item(3, "turn_end")))
            runCurrent()
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
            // A malformed success must not certify already-upserted complete items.
            host.pump.push(
                "thread_items",
                buildJsonObject {
                    put("conversation_id", "same")
                    put("epoch", "e")
                    put("version", 3)
                    put("items", JsonArray(emptyList()))
                    put("final", true)
                    put("lower_order", 0)
                },
                request,
            )
            host.pump.push(
                "turn_end",
                MobileJson.parseToJsonElement("""{"conversation_id":"same","turn_id":"raw","stop_reason":"end_turn"}""") as JsonObject,
            )
            host.pump.push("turn_state", MobileJson.parseToJsonElement("""{"conversation_id":"same","state":"idle"}""") as JsonObject)
            runCurrent()
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
            assertFalse(File(folder.root, "alerts").exists())
        }

    @Test
    fun overlapKeepsNewestAdmittedRevisionAndPostsOnce() =
        fixture {
            open()
            val request = host.pump.query()
            val newest = item(2, "assistant_message", rev = 5, text = "Newest certified reply")
            host.pump.batch(request, 5, listOf(newest))
            // An overlapping older batch cannot replace the accepted reply revision.
            host.pump.batch(request, 5, listOf(item(2, "assistant_message"), item(3, "turn_end")))
            host.pump.finish(request, 5)
            runCurrent()
            val first = shadowOf(manager).allNotifications.single()
            assertEquals("Newest certified reply", first.extras.getString(Notification.EXTRA_TEXT))
            host.pump.live("same", item(3, "turn_end"), 5)
            open()
            val replay = host.pump.query()
            host.pump.recover(replay, version = 5)
            runCurrent()
            assertSame(first, shadowOf(manager).allNotifications.single())
            assertEquals(1, File(folder.root, "alerts").readLines().size)
        }

    @Test
    fun reconnectAndRestartRetainLedgerIdentity() =
        fixture {
            open()
            host.pump.recover(host.pump.query())
            runCurrent()
            val first = shadowOf(manager).allNotifications.single()
            replaceHost()
            open()
            host.pump.recover(host.pump.query())
            runCurrent()
            assertSame(first, shadowOf(manager).allNotifications.single())
            restart()
            replaceHost()
            open()
            host.pump.recover(host.pump.query())
            runCurrent()
            assertSame(first, shadowOf(manager).allNotifications.single())
            assertEquals(1, File(folder.root, "alerts").readLines().size)
        }

    @Test
    fun retiredOwnerCannotSpendReplacementCompletion() =
        fixture {
            val retired = host
            open(retired)
            retired.pump.recover(retired.pump.query())
            // Retire synchronously before queued inbound/source/notifier work can run.
            replaceHost()
            runCurrent()
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
            open()
            host.pump.recover(host.pump.query())
            runCurrent()
            assertEquals(1, shadowOf(manager).allNotifications.size)
        }

    @Test
    fun confirmedReadsSuppressAndCancelOnlyTheirCompletion() =
        fixture {
            host.summary(read = 4, shown = 4)
            runCurrent()
            val request = open()
            host.pump.recover(request)
            runCurrent()
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
            host.summary(read = 4, shown = 6)
            runCurrent()
            host.pump.recover(host.pump.query(), version = 6, end = 6)
            runCurrent()
            assertEquals(1, shadowOf(manager).allNotifications.size)
            host.summary(read = 6, shown = 7)
            runCurrent()
            assertEquals(1, shadowOf(manager).allNotifications.size)
            host.summary(read = 7, shown = 7)
            runCurrent()
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
            assertEquals(2, File(folder.root, "alerts").readLines().size)
        }

    @Test
    fun identitiesRemainIsolatedAcrossHostsAndConversations() =
        fixture {
            val other = Host("b", test.backgroundScope)
            connections.value = listOf(host.connection, other.connection)
            runCurrent()
            for (owner in listOf(host, other)) {
                for (conversation in listOf("same", "other")) {
                    open(owner, conversation)
                    owner.pump.recover(owner.pump.query())
                    runCurrent()
                }
            }
            val targets = shadowOf(manager).allNotifications.map { NotificationTap.target(shadowOf(it.contentIntent).savedIntent) }.toSet()
            assertEquals(
                setOf(
                    HostConversationTarget("a", "same"),
                    HostConversationTarget("a", "other"),
                    HostConversationTarget("b", "same"),
                    HostConversationTarget("b", "other"),
                ),
                targets,
            )
            assertEquals(4, File(folder.root, "alerts").readLines().size)
        }

    @Test
    fun uncertifiedPreviewUsesSafeFallback() =
        fixture {
            open()
            val request = host.pump.query()
            val child = JsonObject(item(2, "assistant_message") + ("parent" to JsonPrimitive(9)))
            host.pump.batch(request, 3, listOf(child, item(3, "turn_end")))
            host.pump.finish(request, 3)
            runCurrent()
            val posted = shadowOf(manager).allNotifications.single()
            assertEquals(app.getString(R.string.notification_turn_completed_neutral), posted.extras.getString(Notification.EXTRA_TEXT))
            assertEquals(posted.extras.getString(Notification.EXTRA_TEXT), posted.publicVersion.extras.getString(Notification.EXTRA_TEXT))
        }

    @Test
    fun retiredQueuedAlertCannotConsumeThePersistentLedger() =
        fixture {
            notifier.dispose()
            val alerts = MutableSharedFlow<AttentionAlert>(extraBufferCapacity = 8)
            notifier =
                AttentionNotifier(
                    app,
                    alerts,
                    MutableStateFlow(true),
                    isMuted = { _, _ -> false },
                    agentOf = { _, _ -> null },
                    nameOf = { _, _ -> null },
                    isForeground = { false },
                    ledgerFile = File(folder.root, "alerts"),
                    dispatcher = dispatcher,
                )
            runCurrent()
            alerts.emit(AttentionAlert("a", "same", AttentionAlert.Kind.TurnCompleted, "same-completion", isCurrent = { false }))
            runCurrent()
            assertFalse(File(folder.root, "alerts").exists())
            alerts.emit(AttentionAlert("a", "same", AttentionAlert.Kind.TurnCompleted, "same-completion"))
            runCurrent()
            assertEquals(1, shadowOf(manager).allNotifications.size)
            assertEquals(1, File(folder.root, "alerts").readLines().size)
        }

    @Test
    fun liveCompletionWaitsForCertifiedReplyAcrossQueryOverlap() =
        fixture {
            open()
            val older = host.pump.query()
            host.pump.live("same", item(2, "assistant_message"), 2)
            host.pump.live("same", item(3, "turn_end"), 3)
            runCurrent()
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
            // The older request cannot cover the completion. A fresh request must remain queued.
            host.pump.batch(older, 2, listOf(item(2, "assistant_message")))
            host.pump.finish(older, 2)
            runCurrent()
            val fresh = host.pump.query()
            assertTrue(fresh.id != older.id)
            host.pump.recover(fresh)
            runCurrent()
            val posted = shadowOf(manager).allNotifications.single()
            assertEquals("Certified private reply", posted.extras.getString(Notification.EXTRA_TEXT))
            host.pump.live("same", item(3, "turn_end"), 3)
            runCurrent()
            assertSame(posted, shadowOf(manager).allNotifications.single())
            assertEquals(1, File(folder.root, "alerts").readLines().size)
        }

    @Test
    fun reconnectCatchUpCertifiesNewReplyBeyondTheHeldWindow() =
        fixture {
            val baseline = open()
            host.pump.batch(baseline, 13, listOf(item(1, "user_message")))
            host.pump.finish(baseline, 13)
            runCurrent()
            val retained = host.repository.authoritativeThreads
            host.available = false
            host = Host("a", test.backgroundScope, retained)
            connections.value = listOf(host.connection)
            runCurrent()
            host.summary(read = 1, shown = 17)
            runCurrent()
            val request = host.pump.query()
            assertEquals(JsonPrimitive(13), (request.payload as JsonObject)["version"])
            host.pump.batch(request, 19, listOf(item(16, "assistant_message", rev = 17), item(17, "turn_end")))
            runCurrent()
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
            host.pump.finish(request, 19)
            runCurrent()
            assertEquals(14uL, retained.current("a", "same").coverage?.upperOrder)
            assertEquals(
                "Certified private reply",
                shadowOf(manager)
                    .allNotifications
                    .single()
                    .extras
                    .getString(Notification.EXTRA_TEXT),
            )
        }

    @Test
    fun resetReusingEpochRejectsRetiredQueryAndAcceptsFreshCompletion() =
        fixture {
            open()
            val retired = host.pump.query()
            host.pump.batch(retired, 3, listOf(item(2, "assistant_message"), item(3, "turn_end")))
            runCurrent()
            host.repository.authoritativeRoute.reset("same", "e")
            host.pump.finish(retired, 3)
            runCurrent()
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
            val fresh = host.pump.query()
            assertTrue(fresh.id != retired.id)
            host.pump.recover(fresh)
            runCurrent()
            assertEquals(1, shadowOf(manager).allNotifications.size)
        }

    @Test
    fun activeOrChildCompletionAndForeignResponseCannotCertifyAnAlert() =
        fixture {
            open()
            val request = host.pump.query()
            host.pump.batch(request, 3, listOf(item(3, "turn_end")))
            host.pump.push(
                "thread_items",
                buildJsonObject {
                    put("conversation_id", "foreign")
                    put("epoch", "e")
                    put("version", 3)
                    put("items", JsonArray(emptyList()))
                    put("final", true)
                    put("lower_order", 0)
                    put("upper_order", 3)
                    put("continuation", 0)
                    put("older_exists", false)
                },
                request,
            )
            runCurrent()
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
            open()
            val fresh = host.pump.query()
            val active = JsonObject(item(4, "turn_end") + ("active" to JsonPrimitive(true)))
            val child = JsonObject(item(5, "turn_end") + ("parent" to JsonPrimitive(9)))
            // Retire the old partial facts rather than letting another valid query certify them.
            host.repository.authoritativeRoute.reset("same", "e")
            host.pump.finish(fresh, 5)
            runCurrent()
            val clean = host.pump.query()
            host.pump.batch(clean, 5, listOf(active, child))
            host.pump.finish(clean, 5)
            runCurrent()
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
        }

    private fun fixture(block: suspend Harness.() -> Unit) =
        runTest {
            val previousSink = RelayLog.sink
            val previousEnabled = RelayLog.enabled
            val diagnostics = mutableListOf<String>()
            RelayLog.enabled = true
            RelayLog.sink = { _, _, message -> diagnostics += message }
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val harness = Harness(this)
            try {
                runCurrent()
                harness.block()
                assertTrue(diagnostics.none { "Certified private reply" in it || "Newest certified reply" in it })
            } catch (failure: Throwable) {
                throw AssertionError(diagnostics.joinToString("\n"), failure)
            } finally {
                harness.close()
                RelayLog.sink = previousSink
                RelayLog.enabled = previousEnabled
            }
        }

    private inner class Harness(
        val test: TestScope,
    ) {
        var host = Host("a", test.backgroundScope)
        val connections = MutableStateFlow(listOf(host.connection))
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        var source = newSource()
        var notifier = newNotifier()

        private fun newSource() = HostConversationSource(connections, { id -> if (id == "a") host.repository else null }, dispatcher)

        private fun newNotifier() =
            AttentionNotifier(
                app,
                source.alerts,
                MutableStateFlow(true),
                isMuted = { _, _ -> false },
                agentOf = { _, _ -> null },
                nameOf = { _, _ -> null },
                isForeground = { false },
                ledgerFile = File(folder.root, "alerts"),
                dispatcher = dispatcher,
                readMarks = source.readMarks,
                readMarksOf = source::currentReadMarks,
            )

        fun runCurrent() = test.runCurrent()

        fun open(
            owner: Host = host,
            conversation: String = "same",
        ): Envelope {
            owner.repository.openAuthoritativeThread(conversation)
            runCurrent()
            return owner.pump.query()
        }

        fun replaceHost() {
            host.available = false
            host = Host("a", test.backgroundScope)
            connections.value = listOf(host.connection)
        }

        fun restart() {
            close()
            source = newSource()
            notifier = newNotifier()
            runCurrent()
        }

        fun close() {
            notifier.dispose()
            source.dispose()
        }
    }

    private class Host(
        val id: String,
        scope: CoroutineScope,
        store: AuthoritativeThreadStore = AuthoritativeThreadStore(),
    ) {
        var available = true
        val pump = Pump()
        val repository =
            RemoteConversationRepository(
                pump,
                scope,
                negotiatedCapabilities = {
                    if (available) setOf(CAPABILITY_THREAD, CAPABILITY_INTERACTIVE) else emptySet()
                },
                authoritativeHostId = id,
                authoritativeThreads = store,
            )
        val repositories = MutableStateFlow<ConversationRepository?>(repository)
        val connection =
            HostConversationConnection(
                id,
                null,
                repositories,
                MutableStateFlow(ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)),
                repository.liveSessionEvents,
            )

        fun summary(
            read: Long,
            shown: Long,
        ) = pump.push(
            "conversations",
            MobileJson.parseToJsonElement(
                    """{"conversations":[{"id":"same","name":"test","is_promoted":true,"cwd":"/test","last_message_ts":"$TS","last_used_at":"$TS","current_session_id":"s","read_up_to":$read,"last_shown_version":$shown}]}""",
            ) as JsonObject,
        )
    }

    private class Pump : SessionPump {
        private val input = Channel<Envelope>(Channel.UNLIMITED)
        override val inbound = input.receiveAsFlow()
        val sent = mutableListOf<Envelope>()
        private var next = 100L

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            val raw = envelope.payload as? JsonObject
            if (envelope.type == "thread_catch_up" && raw?.get("epoch") == JsonPrimitive("")) {
                push(
                    "thread_reset",
                    buildJsonObject {
                        put("conversation_id", raw.getValue("conversation_id"))
                        put("epoch", "e")
                        put("version", 0)
                    },
                    envelope,
                )
            }
            return true
        }

        fun query() = sent.last { it.type == "thread_catch_up" }

        fun push(
            type: String,
            raw: JsonObject,
            request: Envelope? = null,
        ) {
            input.trySend(Envelope(next++, type, TS, raw, inReplyTo = request?.id))
        }

        fun recover(
            request: Envelope,
            version: Long = 3,
            end: Long = 3,
        ) {
            batch(request, version, listOf(item(1, "user_message"), item(2, "assistant_message"), item(end, "turn_end")))
            finish(request, version)
        }

        fun batch(
            request: Envelope,
            version: Long,
            items: List<JsonObject>,
        ) {
            val conversation = (request.payload as JsonObject).getValue("conversation_id")
            push(
                "thread_items",
                buildJsonObject {
                    put("conversation_id", conversation)
                    put("epoch", "e")
                    put("version", version)
                    put("final", false)
                    put("items", JsonArray(items))
                },
                request,
            )
        }

        fun finish(
            request: Envelope,
            version: Long,
        ) {
            val raw = request.payload as JsonObject
            push(
                "thread_items",
                buildJsonObject {
                    put("conversation_id", raw.getValue("conversation_id"))
                    put("epoch", "e")
                    put("version", version)
                    put("final", true)
                    put("items", JsonArray(emptyList()))
                    if (raw["version"] == JsonPrimitive(0)) {
                        put("lower_order", 0)
                        put("upper_order", version + 1)
                        put("continuation", 0)
                        put("older_exists", false)
                    } else {
                        put("from_version", raw.getValue("version"))
                    }
                },
                request,
            )
        }

        fun live(
            conversation: String,
            item: JsonObject,
            version: Long,
        ) = push(
            "thread_item_added",
            buildJsonObject {
                put("conversation_id", conversation)
                put("epoch", "e")
                put("version", version)
                put("item", item)
            },
        )
    }

    private companion object {
        const val TS = "2026-10-19T00:00:00Z"

        fun item(
            id: Long,
            kind: String,
            rev: Long = id,
            text: String = "Certified private reply",
        ): JsonObject =
            buildJsonObject {
                put("id", id)
                put("rev", rev)
                put("order", id)
                put("kind", kind)
                put("status", "done")
                put("active", false)
                put("shown", kind != "turn_end")
                put("session", "s")
                put("turn", "t")
                put("no_child", true)
                put("summary", "inert")
                put("content", buildJsonObject { put("text", text) })
            }
    }
}
