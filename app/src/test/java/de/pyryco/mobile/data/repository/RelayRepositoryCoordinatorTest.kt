package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.Conversation
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.ModalOption
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.CAPABILITY_INTERACTIVE
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.InnerFrameV2
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.network.TransportEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the #351 connection-scoped coordinator: per live `currentConnection` transport
 * it starts a fresh Noise pump and constructs a [RemoteConversationRepository] against it, publishing
 * the live repository on [RelayRepositoryCoordinator.currentRepository]; on drop it cancels the
 * connection scope and closes the pump (wiping keys). JUnit4 + `runTest`, a `StandardTestDispatcher`
 * driven with `runCurrent()`, hand fakes (no MockK) — mirroring `RelayConnectionSupervisorTest` /
 * `RemoteConversationRepositoryTest`. The repository is the **real** one so AC #4 exercises the genuine
 * read paths over the fake pump. Every test ends with `coordinator.close()` so the perpetual
 * `connections.collect` does not hang `runTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayRepositoryCoordinatorTest {
    // ---- AC #2: a live connection starts a pump and publishes a repository ----------------------

    @Test
    fun liveConnection_startsPumpAndPublishesRepository() =
        runTest {
            val env = newEnv()
            env.connections.value = StubRelayTransport()
            runCurrent()
            // #421: the repository is published only once the Noise pump reaches Open, not on bare
            // socket-up. A pre-Open list_conversations send returns false and is dropped, so exposing
            // the repo before Open stranded the conversation list on its loading state forever.
            assertNull("not published until the pump is Open", env.coordinator.currentRepository.value)
            env.pumps.single().open()
            runCurrent()

            assertNotNull(env.coordinator.currentRepository.value)
            assertEquals(1, env.pumps.size)
            assertTrue("the per-connection pump is started", env.pumps.single().started)

            env.coordinator.close()
        }

    // ---- AC #2/#3: between connections the published repository is null --------------------------

    @Test
    fun betweenConnections_repositoryIsNull() =
        runTest {
            val env = newEnv()
            assertNull("no connection yet", env.coordinator.currentRepository.value)

            env.connections.value = StubRelayTransport()
            runCurrent()
            env.pumps.single().open()
            runCurrent()
            assertNotNull(env.coordinator.currentRepository.value)

            env.connections.value = null
            runCurrent()
            assertNull("the connection cleared", env.coordinator.currentRepository.value)

            env.coordinator.close()
        }

    // ---- AC #4: the conversation-list read path functions end-to-end over the live pump ----------

    @Test
    fun listPath_overLivePump_yieldsProjectedConversationList() =
        runTest {
            val env = newEnv()
            env.connections.value = StubRelayTransport()
            runCurrent()
            env.pumps.single().open()
            runCurrent()
            val repo = requireNotNull(env.coordinator.currentRepository.value)
            val pump = env.pumps.single()

            val emissions = mutableListOf<List<Conversation>>()
            backgroundScope.launch { repo.observeConversations(ConversationFilter.All).collect { emissions += it } }
            runCurrent()

            // The subscription drove a list_conversations request over the coordinator-built pump.
            assertEquals("list_conversations", pump.sent.single().type)

            // Wire order is ascending by last_used_at; the projection sorts most-recent-first.
            pump.push(
                conversationsEnvelope(
                    """
                    {"conversations":[
                      {"id":"older","name":"Older","is_promoted":true,"cwd":"/p/older","last_message_ts":"2026-05-08T09:00:00Z","last_used_at":"2026-05-08T09:00:00Z"},
                      {"id":"newer","name":"Newer","is_promoted":true,"cwd":"/p/newer","last_message_ts":"2026-05-08T11:00:00Z","last_used_at":"2026-05-08T11:00:00Z"}
                    ]}
                    """.trimIndent(),
                ),
            )
            runCurrent()

            assertEquals(listOf("newer", "older"), emissions.last().map { it.id })

            env.coordinator.close()
        }

    // ---- AC #4: the thread read path functions end-to-end over the live pump --------------------

    @Test
    fun threadPath_overLivePump_yieldsOrderedThread() =
        runTest {
            val env = newEnv()
            env.connections.value = StubRelayTransport()
            runCurrent()
            env.pumps.single().open()
            runCurrent()
            val repo = requireNotNull(env.coordinator.currentRepository.value)
            val pump = env.pumps.single()

            val emissions = mutableListOf<List<ThreadItem>>()
            backgroundScope.launch { repo.observeMessages("c1").collect { emissions += it } }
            runCurrent()

            // The subscription drove a backfill_since request over the coordinator-built pump.
            assertEquals("backfill_since", pump.sent.single().type)

            pump.push(
                messageChunkEnvelope(
                    listOf(
                        chunkRow("c1", "m1", "user", "first"),
                        chunkRow("c1", "m2", "assistant", "second"),
                    ),
                ),
            )
            runCurrent()
            pump.push(messageEnvelope("c1", "m3", "user", "live", "2026-05-31T12:00:00Z"))
            runCurrent()

            assertEquals(listOf("m1", "m2", "m3"), messageIds(emissions.last()))

            env.coordinator.close()
        }

    // ---- AC #3: a dropped connection closes the pump and stops the repository collector ----------

    @Test
    fun connectionDrop_closesPumpAndStopsCollector() =
        runTest {
            val env = newEnv()
            env.connections.value = StubRelayTransport()
            runCurrent()
            env.pumps.single().open()
            runCurrent()
            val repo1 = requireNotNull(env.coordinator.currentRepository.value)
            val pump1 = env.pumps.single()

            env.connections.value = null
            runCurrent()

            // Key-wipe invariant: dropping the pump reference closed it (wiping session keys); no repo.
            assertTrue("the pump is closed on teardown (keys wiped)", pump1.closed)
            assertNull(env.coordinator.currentRepository.value)

            // The repository's single inbound collector is cancelled with the child scope: a frame
            // pushed to the old pump after teardown surfaces nowhere — observing the dead repo's list
            // never projects (its projection writer is gone).
            val leaked = mutableListOf<List<Conversation>>()
            backgroundScope.launch { repo1.observeConversations(ConversationFilter.All).collect { leaked += it } }
            runCurrent()
            pump1.push(
                conversationsEnvelope(
                    """{"conversations":[{"id":"ghost","name":"Ghost","is_promoted":true,"cwd":"/g","last_message_ts":"2026-05-08T09:00:00Z","last_used_at":"2026-05-08T09:00:00Z"}]}""",
                ),
            )
            runCurrent()
            assertEquals(emptyList<List<Conversation>>(), leaked)

            env.coordinator.close()
        }

    // ---- AC #3: reconnect builds a fresh pump + repository with no state carried over ------------

    @Test
    fun reconnect_buildsFreshPumpAndRepository_noCarryover() =
        runTest {
            val env = newEnv()

            // Connection 1 loads a snapshot containing c1.
            env.connections.value = StubRelayTransport()
            runCurrent()
            env.pumps[0].open()
            runCurrent()
            val repo1 = requireNotNull(env.coordinator.currentRepository.value)
            val pump1 = env.pumps[0]
            val list1 = mutableListOf<List<Conversation>>()
            backgroundScope.launch { repo1.observeConversations(ConversationFilter.All).collect { list1 += it } }
            runCurrent()
            pump1.push(
                conversationsEnvelope(
                    """{"conversations":[{"id":"c1","name":"One","is_promoted":true,"cwd":"/c1","last_message_ts":"2026-05-08T09:00:00Z","last_used_at":"2026-05-08T09:00:00Z"}]}""",
                ),
            )
            runCurrent()
            assertEquals(listOf("c1"), list1.last().map { it.id })

            // Drop, then reconnect over a fresh transport.
            env.connections.value = null
            runCurrent()
            env.connections.value = StubRelayTransport()
            runCurrent()
            env.pumps[1].open()
            runCurrent()

            val repo2 = requireNotNull(env.coordinator.currentRepository.value)
            val pump2 = env.pumps[1]

            assertEquals(2, env.pumps.size)
            assertTrue("the previous pump was closed", pump1.closed)
            assertTrue("the fresh pump is started", pump2.started)
            assertFalse("the fresh pump is live", pump2.closed)
            assertNotSame("a distinct pump per connection", pump1, pump2)
            assertNotSame("a distinct repository per connection", repo1, repo2)

            // No projection state carried over: before any snapshot on connection 2, the new
            // repository's list yields nothing — c1 did not leak across the connection boundary.
            val list2 = mutableListOf<List<Conversation>>()
            backgroundScope.launch { repo2.observeConversations(ConversationFilter.All).collect { list2 += it } }
            runCurrent()
            assertEquals(emptyList<List<Conversation>>(), list2)

            env.coordinator.close()
        }

    // ---- #493: the reconnect gating race must not re-expose #421 ---------------------------------

    // AC #1 (regression, must be RED against pre-fix `main`): on a DIRECT A→B reconnect (no interposed
    // `null`), the new connection's repo must never be exposed while B's own pump is still Handshaking.
    // The old two-StateFlow `combine` gate paired B's fresh repo (delivered directly off
    // `mutableRepository`) with A's stale cached `Open` pump-state (the `flatMapLatest` over
    // `activePumpFlow` lags a coroutine hop), transiently emitting B's repo pre-Open — exactly #421's
    // "list never loads" (the facade's one-shot `list_conversations` fires and is dropped). The transient
    // is caught by COLLECTING every emission (the settled `.value` is `null` on both pre- and post-fix,
    // since a full `runCurrent()` drains to `(repoB, Handshaking) → null`).
    @Test
    fun reconnect_directAtoB_neverExposesRepoWhileNewPumpHandshaking() =
        runTest {
            val env = newEnv()

            // Record EVERY emission of currentRepository across the reconnect.
            val emissions = mutableListOf<ConversationRepository?>()
            backgroundScope.launch { env.coordinator.currentRepository.collect { emissions += it } }

            // Connection A reaches Open → its repo is exposed.
            env.connections.value = StubRelayTransport()
            runCurrent()
            env.pumps[0].open()
            runCurrent()
            val repoA = requireNotNull(env.coordinator.currentRepository.value)
            assertSame("A's repo is exposed once its own pump is Open", repoA, emissions.last())

            // Direct A→B reconnect: DO NOT interpose a `null` (with its own runCurrent), and DO NOT open
            // pumps[1] — leave B's fresh pump at Handshaking.
            env.connections.value = StubRelayTransport()
            runCurrent()

            // The only non-null repo ever observed is A's; B's repo is never exposed pre-Open.
            assertEquals(
                "B's repo must never be exposed while its own pump is Handshaking",
                emptyList<ConversationRepository>(),
                emissions.filterNotNull().filter { it !== repoA },
            )
            assertNull("settled currentRepository is null while B handshakes", env.coordinator.currentRepository.value)

            env.coordinator.close()
        }

    // AC #2: across the same direct A→B reconnect, B's repo appears in currentRepository only once B's OWN
    // pump reaches Open — never during the Handshaking window. The two distinct non-null repos ever
    // exposed are exactly [repoA, repoB], in that order.
    @Test
    fun reconnect_directAtoB_exposesRepoOnlyOnceNewPumpReachesOpen() =
        runTest {
            val env = newEnv()

            val emissions = mutableListOf<ConversationRepository?>()
            backgroundScope.launch { env.coordinator.currentRepository.collect { emissions += it } }

            env.connections.value = StubRelayTransport()
            runCurrent()
            env.pumps[0].open()
            runCurrent()
            val repoA = requireNotNull(env.coordinator.currentRepository.value)

            // Direct A→B reconnect; leave B at Handshaking → B's repo stays hidden.
            env.connections.value = StubRelayTransport()
            runCurrent()
            assertNull("B's repo hidden while its pump handshakes", env.coordinator.currentRepository.value)

            // B's own pump reaches Open → B's repo is exposed for the first time, distinct from A's.
            env.pumps[1].open()
            runCurrent()
            val repoB = requireNotNull(env.coordinator.currentRepository.value)
            assertNotSame("a distinct repo per connection", repoA, repoB)
            assertSame("the newly-exposed repo is B's own", repoB, emissions.last())
            assertEquals(
                "B appears only from Open onward — the only non-null repos exposed are A then B",
                listOf(repoA, repoB),
                emissions.filterNotNull().distinct(),
            )

            env.coordinator.close()
        }

    // AC #3: after a disconnect→reconnect where the new pump reaches Open, the one-shot list_conversations
    // SUCCEEDS over connection B (not dropped pre-Open as in #421) and the list loads rather than spinning.
    @Test
    fun reconnect_afterOpen_listConversationsSucceedsAndListLoads() =
        runTest {
            val env = newEnv()

            // Connection A up and Open.
            env.connections.value = StubRelayTransport()
            runCurrent()
            env.pumps[0].open()
            runCurrent()

            // Disconnect, then reconnect over a fresh transport; drive B's pump to Open.
            env.connections.value = null
            runCurrent()
            env.connections.value = StubRelayTransport()
            runCurrent()
            env.pumps[1].open()
            runCurrent()

            val repo2 = requireNotNull(env.coordinator.currentRepository.value)
            val pump2 = env.pumps[1]

            // The facade subscribes on the freshly-exposed repo → one list_conversations reaches pump B's
            // send (post-Open, so the send succeeded rather than being silently dropped).
            val emissions = mutableListOf<List<Conversation>>()
            backgroundScope.launch { repo2.observeConversations(ConversationFilter.All).collect { emissions += it } }
            runCurrent()
            assertEquals("list_conversations", pump2.sent.single().type)

            // The daemon replies with a snapshot → the list loads.
            pump2.push(
                conversationsEnvelope(
                    """{"conversations":[{"id":"c9","name":"Nine","is_promoted":true,"cwd":"/c9","last_message_ts":"2026-05-08T09:00:00Z","last_used_at":"2026-05-08T09:00:00Z"}]}""",
                ),
            )
            runCurrent()
            assertEquals(listOf("c9"), emissions.last().map { it.id })

            env.coordinator.close()
        }

    // ---- AC #3: closing the coordinator tears down the active connection -------------------------

    @Test
    fun close_tearsDownActiveConnection() =
        runTest {
            val env = newEnv()
            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump = env.pumps.single()

            env.coordinator.close()
            runCurrent()

            assertTrue("close() wipes the active pump", pump.closed)
            assertNull(env.coordinator.currentRepository.value)
        }

    // ---- #365: connect-time push-token re-registration ------------------------------------------

    // AC #1: a fresh session reaching Open with a stored token sends exactly one register_push_token
    // carrying the live device name — never an empty device_name.
    @Test
    fun onOpen_withStoredToken_registersOnceWithLiveDeviceName() =
        runTest {
            val env = newEnv(deviceName = "Pixel-8", pushToken = { "fcm-tok" })
            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump = env.pumps.single()

            pump.open()
            runCurrent()

            val sent = pump.sent.single { it.type == "register_push_token" }
            assertEquals(
                MobileJson.parseToJsonElement("""{"platform":"fcm","token":"fcm-tok","device_name":"Pixel-8"}"""),
                sent.payload,
            )

            // Resolve the awaiting coroutine cleanly before teardown.
            pump.push(ackEnvelope(sent.id))
            runCurrent()

            env.coordinator.close()
        }

    // AC #2: no stored token → the connect-time hook is a no-op (sends nothing, does not error).
    @Test
    fun onOpen_withNoStoredToken_sendsNothing() =
        runTest {
            val env = newEnv(deviceName = "Pixel-8", pushToken = { null })
            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump = env.pumps.single()

            pump.open()
            runCurrent()

            assertTrue(
                "no register_push_token when no token is stored",
                pump.sent.none { it.type == "register_push_token" },
            )
            assertNotNull("the repository is still published", env.coordinator.currentRepository.value)

            env.coordinator.close()
        }

    // AC #3: a reconnect re-registers — exactly once per connection (fresh hook per connection, not a
    // leaked single-fire).
    @Test
    fun reconnect_reRegistersOncePerConnection() =
        runTest {
            val env = newEnv(deviceName = "Pixel-8", pushToken = { "fcm-tok" })

            // Connection 1 reaches Open → one register frame on pump 1.
            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump1 = env.pumps[0]
            pump1.open()
            runCurrent()
            val sent1 = pump1.sent.single { it.type == "register_push_token" }
            pump1.push(ackEnvelope(sent1.id))
            runCurrent()

            // Drop, then reconnect over a fresh transport → one register frame on pump 2.
            env.connections.value = null
            runCurrent()
            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump2 = env.pumps[1]
            pump2.open()
            runCurrent()
            val sent2 = pump2.sent.single { it.type == "register_push_token" }
            pump2.push(ackEnvelope(sent2.id))
            runCurrent()

            assertEquals(1, pump1.sent.count { it.type == "register_push_token" })
            assertEquals(1, pump2.sent.count { it.type == "register_push_token" })

            env.coordinator.close()
        }

    // AC #4: a registration failure on connect does not crash or wedge the connection.
    @Test
    fun registrationFailure_doesNotCrashOrWedgeTheConnection() =
        runTest {
            val env = newEnv(deviceName = "Pixel-8", pushToken = { "fcm-tok" })

            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump1 = env.pumps.single()
            pump1.open()
            runCurrent()

            val sent = pump1.sent.single { it.type == "register_push_token" }
            pump1.push(errorEnvelope(sent.id, code = "server.binary_busy", retryable = true))
            runCurrent()

            // The failure was swallowed: the repository is still live.
            assertNotNull(env.coordinator.currentRepository.value)

            // A subsequent drop/reconnect still works (the connection is not wedged).
            env.connections.value = null
            runCurrent()
            env.connections.value = StubRelayTransport()
            runCurrent()
            env.pumps.last().open()
            runCurrent()
            assertNotNull(env.coordinator.currentRepository.value)

            env.coordinator.close()
        }

    // AC #1 (boundary): a session that closes before ever reaching Open registers nothing.
    @Test
    fun preOpenClosed_abortsWithoutRegistering() =
        runTest {
            val env = newEnv(deviceName = "Pixel-8", pushToken = { "fcm-tok" })
            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump = env.pumps.single()

            pump.closeState(null)
            runCurrent()

            assertTrue(
                "no register_push_token when the session closed before Open",
                pump.sent.none { it.type == "register_push_token" },
            )

            env.coordinator.close()
        }

    // ---- #392: pyrycode-leg readiness + combined two-part connection status ----------------------

    // AC #1/#2: with no live pump (no connection) the pyrycode leg is Down — the not-ready floor.
    @Test
    fun noPump_pyrycodeLegIsDown() =
        runTest {
            val env = newEnv()
            runCurrent()

            assertEquals(PyrycodeLinkStatus.Down, env.coordinator.connectionStatus.value.pyrycode)

            env.coordinator.close()
        }

    // AC #2 (false-green guard): a live socket whose pump has not completed the handshake reads
    // Handshaking — never Connected on bare socket-up.
    @Test
    fun bareSocketUp_isHandshaking_neverConnected() =
        runTest {
            val env = newEnv()
            env.connections.value = StubRelayTransport()
            runCurrent()

            val pyrycode = env.coordinator.connectionStatus.value.pyrycode
            assertEquals(PyrycodeLinkStatus.Handshaking, pyrycode)
            assertNotEquals(PyrycodeLinkStatus.Connected, pyrycode)

            env.coordinator.close()
        }

    // AC #1/#2: the leg reaches Connected only after the pump reaches Open (handshake complete).
    @Test
    fun pumpOpen_pyrycodeLegIsConnected() =
        runTest {
            val env = newEnv()
            env.connections.value = StubRelayTransport()
            runCurrent()
            assertEquals(PyrycodeLinkStatus.Handshaking, env.coordinator.connectionStatus.value.pyrycode)

            env.pumps.single().open()
            runCurrent()

            assertEquals(PyrycodeLinkStatus.Connected, env.coordinator.connectionStatus.value.pyrycode)

            env.coordinator.close()
        }

    // AC #1: a closed session maps to Down regardless of the close cause — the no-log discrimination:
    // Closed.cause is never read by the mapping.
    @Test
    fun pumpClosed_pyrycodeLegIsDown_regardlessOfCause() =
        runTest {
            val env = newEnv()
            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump = env.pumps.single()
            pump.open()
            runCurrent()
            assertEquals(PyrycodeLinkStatus.Connected, env.coordinator.connectionStatus.value.pyrycode)

            pump.closeState(RuntimeException("handshake timeout"))
            runCurrent()

            assertEquals(PyrycodeLinkStatus.Down, env.coordinator.connectionStatus.value.pyrycode)

            env.coordinator.close()
        }

    // AC #2: the readiness leg follows the current pump across a reconnect with no carryover —
    // Connected, then Down on drop, then Handshaking → Connected on the fresh pump.
    @Test
    fun pyrycodeLeg_tracksLivePumpAcrossReconnect() =
        runTest {
            val env = newEnv()

            env.connections.value = StubRelayTransport()
            runCurrent()
            env.pumps[0].open()
            runCurrent()
            assertEquals(PyrycodeLinkStatus.Connected, env.coordinator.connectionStatus.value.pyrycode)

            // Drop: no live pump → Down.
            env.connections.value = null
            runCurrent()
            assertEquals(PyrycodeLinkStatus.Down, env.coordinator.connectionStatus.value.pyrycode)

            // Fresh connection: a brand-new pump starts at Handshaking, not carrying the old Connected.
            env.connections.value = StubRelayTransport()
            runCurrent()
            assertEquals(PyrycodeLinkStatus.Handshaking, env.coordinator.connectionStatus.value.pyrycode)

            env.pumps[1].open()
            runCurrent()
            assertEquals(PyrycodeLinkStatus.Connected, env.coordinator.connectionStatus.value.pyrycode)

            env.coordinator.close()
        }

    // AC #3/#4: the combined model reflects each leg independently — the relay leg moves while the
    // pyrycode leg is held Connected, then the pyrycode leg moves while the relay leg is held.
    @Test
    fun combinedModel_reflectsEachLegIndependently() =
        runTest {
            val env = newEnv()
            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump = env.pumps.single()
            pump.open()
            runCurrent()

            // pyrycode held at Connected; drive the relay leg to DaemonAbsent.
            env.relayStatus.value = RelayLinkStatus.DaemonAbsent
            runCurrent()
            assertEquals(
                ConnectionStatus(relay = RelayLinkStatus.DaemonAbsent, pyrycode = PyrycodeLinkStatus.Connected),
                env.coordinator.connectionStatus.value,
            )

            // relay back to Connected; drive the pyrycode leg down via a session close.
            env.relayStatus.value = RelayLinkStatus.Connected
            pump.closeState(null)
            runCurrent()
            assertEquals(
                ConnectionStatus(relay = RelayLinkStatus.Connected, pyrycode = PyrycodeLinkStatus.Down),
                env.coordinator.connectionStatus.value,
            )

            env.coordinator.close()
        }

    // ---- #406: live turn-state events reach the coordinator seam --------------------------------

    // AC #1: a decoded live-session event from the connection-scoped concrete repository surfaces on
    // the coordinator's stable liveSessionEvents seam. The pump must be Open WITH the interactive
    // capability for the decode gate to open.
    @Test
    fun liveSessionEvents_surfaceTurnStateFromLiveConnection() =
        runTest {
            val env = newEnv()
            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump = env.pumps.single()
            pump.open(capabilities = setOf(CAPABILITY_INTERACTIVE))
            runCurrent()

            val events = mutableListOf<LiveSessionEvent>()
            backgroundScope.launch { env.coordinator.liveSessionEvents.collect { events += it } }
            runCurrent()

            pump.push(turnStateEnvelope("c1", "thinking"))
            runCurrent()

            assertEquals(
                listOf(LiveSessionEvent.TurnState("c1", LiveSessionEvent.TurnState.Phase.Thinking)),
                events,
            )

            env.coordinator.close()
        }

    // AC #1: the seam survives reconnection — events from a fresh connection's repo continue to drive
    // it (flatMapLatest switched to the new repo), and the dead pump no longer surfaces.
    @Test
    fun liveSessionEvents_surviveReconnection() =
        runTest {
            val env = newEnv()
            val events = mutableListOf<LiveSessionEvent>()
            backgroundScope.launch { env.coordinator.liveSessionEvents.collect { events += it } }

            // Connection 1: open with the capability, push a turn_state.
            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump1 = env.pumps[0]
            pump1.open(capabilities = setOf(CAPABILITY_INTERACTIVE))
            runCurrent()
            pump1.push(turnStateEnvelope("c1", "thinking"))
            runCurrent()

            // Reconnect over a fresh transport (fresh pump #2) and push a turn_state on it.
            env.connections.value = null
            runCurrent()
            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump2 = env.pumps[1]
            pump2.open(capabilities = setOf(CAPABILITY_INTERACTIVE))
            runCurrent()
            pump2.push(turnStateEnvelope("c2", "responding"))
            runCurrent()

            // A push on the now-dead pump #1 surfaces nowhere — flatMapLatest cancelled its collection.
            pump1.push(turnStateEnvelope("c1", "idle"))
            runCurrent()

            assertEquals(
                listOf(
                    LiveSessionEvent.TurnState("c1", LiveSessionEvent.TurnState.Phase.Thinking),
                    LiveSessionEvent.TurnState("c2", LiveSessionEvent.TurnState.Phase.Responding),
                ),
                events,
            )

            env.coordinator.close()
        }

    // ---- #492: the "current modal" projection is folded once at the process-scoped coordinator ---

    // AC #4 (the regression): a modal_shown that arrives with NO collector on currentModal is still
    // accumulated — the Eagerly, process-scoped fold ran before any thread screen subscribed. Reading
    // `.value` with no subscriber is the proof (contrast the liveSessionEvents cold-flow test, which needs a
    // backgroundScope collector). This is precisely the drop the old per-ThreadViewModel fold suffered:
    // the coordinator's modal seam is `replay = 0`, so an event fired before the VM subscribed was lost.
    @Test
    fun currentModal_accumulatesModalShownBeforeAnySubscriber() =
        runTest {
            val env = newEnv()
            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump = env.pumps.single()
            pump.open(capabilities = setOf(CAPABILITY_INTERACTIVE))
            runCurrent()

            // No collector on currentModal — mirrors "no thread screen on the back stack".
            pump.push(modalShownEnvelope("m1"))
            runCurrent()

            assertEquals(
                ModalUiState.Open(
                    modalId = "m1",
                    modalClass = "permission",
                    title = "Allow?",
                    prompt = "Run rm -rf build/?",
                    options = listOf(ModalOption("allow", "Allow"), ModalOption("deny", "Deny")),
                    defaultOptionId = "deny",
                ),
                env.coordinator.currentModal.value,
            )

            env.coordinator.close()
        }

    // The process-scoped fold sits downstream of the `flatMapLatest` seam, so a connection drop does not
    // restart the `scan`: a still-Open modal is RETAINED, not reset to Hidden (documents the teardown
    // decision — the answer path is guarded by the deterministic answerModal/cancelModal null-guard, never
    // by this UI projection, so retaining a stale Open cannot send an answer on a dead connection).
    @Test
    fun currentModal_retainsOpenModalAcrossConnectionDrop() =
        runTest {
            val env = newEnv()
            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump = env.pumps.single()
            pump.open(capabilities = setOf(CAPABILITY_INTERACTIVE))
            runCurrent()
            pump.push(modalShownEnvelope("m1"))
            runCurrent()
            assertTrue(env.coordinator.currentModal.value is ModalUiState.Open)

            // Connection drops (teardownActive nulls activeRemoteRepo → emptyFlow); the scan holds its value.
            env.connections.value = null
            runCurrent()

            assertEquals("m1", (env.coordinator.currentModal.value as ModalUiState.Open).modalId)

            env.coordinator.close()
        }

    // #412 AC #4: the replay cursor is coordinator-scoped, so it survives connection churn — a fresh
    // per-connection repo keeps recording into the same high-water mark the prior connection advanced.
    @Test
    fun replayCursor_survivesReconnect() =
        runTest {
            val env = newEnv()

            // Connection 1: open with the capability, record an event_id.
            env.connections.value = StubRelayTransport()
            runCurrent()
            env.pumps[0].open(capabilities = setOf(CAPABILITY_INTERACTIVE))
            runCurrent()
            env.pumps[0].push(turnStateEnvelope("c1", "thinking", eventId = 100))
            runCurrent()
            assertEquals(100L, env.coordinator.replayCursor.latest)

            // Reconnect over a fresh transport (fresh pump #2): the cursor persists across the churn …
            env.connections.value = null
            runCurrent()
            env.connections.value = StubRelayTransport()
            runCurrent()
            assertEquals(100L, env.coordinator.replayCursor.latest)

            // … and the new connection's repo keeps folding into the same mark (out-of-order ignored).
            env.pumps[1].open(capabilities = setOf(CAPABILITY_INTERACTIVE))
            runCurrent()
            env.pumps[1].push(turnStateEnvelope("c2", "responding", eventId = 5))
            runCurrent()
            assertEquals(100L, env.coordinator.replayCursor.latest)
            env.pumps[1].push(turnStateEnvelope("c2", "responding", eventId = 200))
            runCurrent()
            assertEquals(200L, env.coordinator.replayCursor.latest)

            env.coordinator.close()
        }

    // ---- #451: outbound modal answer/cancel passthrough to the connection-scoped concrete repo ----

    // A live connection (pump Open) → answerModal delegates: exactly one modal_answer frame carrying the
    // modal_id/option_id is sent, and the suspend completes on the correlated ack.
    @Test
    fun answerModal_withActiveConnection_delegatesAndCompletesOnAck() =
        runTest {
            val env = newEnv()
            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump = env.pumps.single()
            pump.open()
            runCurrent()

            backgroundScope.launch { env.coordinator.answerModal("m1", "allow_once") }
            runCurrent()

            val sent = pump.sent.single { it.type == "modal_answer" }
            val payload = sent.payload.jsonObject
            assertEquals("m1", payload["modal_id"]?.jsonPrimitive?.content)
            assertEquals("allow_once", payload["option_id"]?.jsonPrimitive?.content)

            // The correlated ack completes the awaiting suspend without throwing.
            pump.push(ackEnvelope(sent.id))
            runCurrent()

            env.coordinator.close()
        }

    // A live connection (pump Open) → cancelModal delegates: one modal_cancel frame carrying the modal_id.
    @Test
    fun cancelModal_withActiveConnection_delegatesAndCompletesOnAck() =
        runTest {
            val env = newEnv()
            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump = env.pumps.single()
            pump.open()
            runCurrent()

            backgroundScope.launch { env.coordinator.cancelModal("m1") }
            runCurrent()

            val sent = pump.sent.single { it.type == "modal_cancel" }
            assertEquals(
                "m1",
                sent.payload.jsonObject["modal_id"]
                    ?.jsonPrimitive
                    ?.content,
            )

            pump.push(ackEnvelope(sent.id))
            runCurrent()

            env.coordinator.close()
        }

    // The load-bearing new branch: no active connection → IllegalStateException (the not-connected path
    // the ViewModel catches and surfaces as the non-crashing error signal).
    @Test
    fun answerModal_withNoActiveConnection_throwsIllegalState() =
        runTest {
            val env = newEnv()
            runCurrent()

            val outcome = runCatching { env.coordinator.answerModal("m1", "allow_once") }
            assertTrue(outcome.exceptionOrNull() is IllegalStateException)

            env.coordinator.close()
        }

    @Test
    fun cancelModal_withNoActiveConnection_throwsIllegalState() =
        runTest {
            val env = newEnv()
            runCurrent()

            val outcome = runCatching { env.coordinator.cancelModal("m1") }
            assertTrue(outcome.exceptionOrNull() is IllegalStateException)

            env.coordinator.close()
        }

    // A live connection forwards B explicitly even after a control send for A.
    @Test
    fun interrupt_withActiveConnection_targetsBAfterA() =
        runTest {
            val env = newEnv()
            env.connections.value = StubRelayTransport()
            runCurrent()
            val pump = env.pumps.single()
            pump.open()
            runCurrent()

            env.coordinator.interrupt("c-a")
            val before = pump.sent.size
            env.coordinator.interrupt("c-b")
            runCurrent()

            val sent = pump.sent.drop(before).single()
            assertEquals("interrupt", sent.type)
            assertEquals(MobileJson.parseToJsonElement("""{"conversation_id":"c-b"}"""), sent.payload)

            env.coordinator.close()
        }

    // No active connection → IllegalStateException (the not-connected path the ViewModel swallows).
    @Test
    fun interrupt_withNoActiveConnection_throwsIllegalState() =
        runTest {
            val env = newEnv()
            runCurrent()

            val outcome = runCatching { env.coordinator.interrupt("c-b") }
            assertTrue(outcome.exceptionOrNull() is IllegalStateException)

            env.coordinator.close()
        }

    // ---- helpers ---------------------------------------------------------------------------------

    private fun TestScope.newEnv(
        deviceName: String = "",
        pushToken: suspend () -> String? = { null },
        relayStatus: MutableStateFlow<RelayLinkStatus> = MutableStateFlow(RelayLinkStatus.Connected),
    ): Env {
        val connections = MutableStateFlow<RelayTransport?>(null)
        val pumps = mutableListOf<FakeManagedPump>()
        val coordinator =
            RelayRepositoryCoordinator(
                connections = connections,
                relayStatus = relayStatus,
                createPump = { FakeManagedPump().also { pumps += it } },
                dispatcher = StandardTestDispatcher(testScheduler),
                deviceName = deviceName,
                pushToken = pushToken,
            )
        coordinator.start()
        return Env(connections, pumps, coordinator, relayStatus)
    }

    private class Env(
        val connections: MutableStateFlow<RelayTransport?>,
        val pumps: MutableList<FakeManagedPump>,
        val coordinator: RelayRepositoryCoordinator,
        val relayStatus: MutableStateFlow<RelayLinkStatus>,
    )

    private fun messageIds(thread: List<ThreadItem>): List<String> = thread.map { (it as ThreadItem.MessageItem).message.id }

    private fun chunkRow(
        conversationId: String,
        messageId: String,
        role: String,
        text: String,
    ): String = """{"conversation_id":"$conversationId","message_id":"$messageId","role":"$role","text":"$text"}"""

    private fun conversationsEnvelope(rawConversationsPayload: String): Envelope =
        Envelope(
            id = 1L,
            type = "conversations",
            ts = TS,
            payload = MobileJson.parseToJsonElement(rawConversationsPayload),
        )

    private fun messageChunkEnvelope(rows: List<String>): Envelope =
        Envelope(
            id = 1L,
            type = "message_chunk",
            ts = TS,
            payload = MobileJson.parseToJsonElement("""{"messages":[${rows.joinToString(",")}]}"""),
        )

    private fun messageEnvelope(
        conversationId: String,
        messageId: String,
        role: String,
        text: String,
        ts: String,
    ): Envelope =
        Envelope(
            id = 1L,
            type = "message",
            ts = ts,
            payload =
                MobileJson.parseToJsonElement(
                    """{"conversation_id":"$conversationId","message_id":"$messageId","role":"$role","text":"$text"}""",
                ),
        )

    private fun turnStateEnvelope(
        conversationId: String,
        state: String,
        eventId: Long? = null,
    ): Envelope =
        Envelope(
            id = 1L,
            type = "turn_state",
            ts = TS,
            payload = MobileJson.parseToJsonElement("""{"conversation_id":"$conversationId","state":"$state"}"""),
            eventId = eventId,
        )

    /** A `modal_shown` envelope (#437/#492) — mirrors RemoteConversationRepositoryTest's wire shape; the
     *  fields default to a two-option permission modal so the projection test asserts verbatim carry. */
    private fun modalShownEnvelope(
        modalId: String,
        modalClass: String = "permission",
        title: String = "Allow?",
        prompt: String = "Run rm -rf build/?",
        options: List<Pair<String, String>> = listOf("allow" to "Allow", "deny" to "Deny"),
        defaultOptionId: String = "deny",
    ): Envelope {
        val optionsJson = options.joinToString(",") { (id, label) -> """{"id":"$id","label":"$label"}""" }
        return Envelope(
            id = 1L,
            type = "modal_shown",
            ts = TS,
            payload =
                MobileJson.parseToJsonElement(
                    """{"modal_id":"$modalId","class":"$modalClass","title":"$title","prompt":"$prompt","options":[$optionsJson],"default_option_id":"$defaultOptionId"}""",
                ),
        )
    }

    /** Empty-`ack` reply correlated to [inReplyTo] — the register_push_token success signal. */
    private fun ackEnvelope(inReplyTo: Long): Envelope =
        Envelope(id = 99L, type = "ack", ts = TS, payload = MobileJson.parseToJsonElement("{}"), inReplyTo = inReplyTo)

    /** A server `error` reply correlated to [inReplyTo] — exercises the swallow path. */
    private fun errorEnvelope(
        inReplyTo: Long,
        code: String,
        retryable: Boolean = true,
    ): Envelope =
        Envelope(
            id = 99L,
            type = "error",
            ts = TS,
            payload = MobileJson.parseToJsonElement("""{"code":"$code","message":"boom","retryable":$retryable}"""),
            inReplyTo = inReplyTo,
        )

    /** Channel-backed [ManagedSessionPump] fake: unlimited inbound buffer, a drivable lifecycle [state],
     *  and start/close lifecycle flags. Defaults to [PumpState.Handshaking] so tests that never drive it
     *  to Open keep the connect-time hook dormant (the register frame never appears). */
    private class FakeManagedPump : ManagedSessionPump {
        private val inboundChannel = Channel<Envelope>(Channel.UNLIMITED)

        override val inbound: Flow<Envelope> = inboundChannel.receiveAsFlow()

        private val mutableState = MutableStateFlow<PumpState>(PumpState.Handshaking)

        override val state: StateFlow<PumpState> = mutableState.asStateFlow()

        val sent = mutableListOf<Envelope>()

        var started = false
            private set

        var closed = false
            private set

        override fun send(envelope: Envelope): Boolean {
            sent += envelope
            return true
        }

        override fun start() {
            started = true
        }

        override fun close() {
            closed = true
            inboundChannel.close()
        }

        /** Drive the handshake to completion: the connect-time hook awaits this transition.
         *  [capabilities] mirror `hello_ack`'s negotiated set — the live-event decode gate
         *  (#385) only opens when `CAPABILITY_INTERACTIVE` is present. */
        fun open(
            connId: String = "c1",
            capabilities: Set<String> = emptySet(),
        ) {
            mutableState.value = PumpState.Open(connId, capabilities)
        }

        /** Drive a terminal close without ever reaching Open (pre-Open fault). */
        fun closeState(cause: Throwable? = null) {
            mutableState.value = PumpState.Closed(cause)
        }

        fun push(envelope: Envelope) {
            inboundChannel.trySend(envelope)
        }
    }

    /** A non-null transport token; the [RelayRepositoryCoordinator] only hands the reference to
     *  `createPump` and never collects its streams, so the fake pump ignores it entirely. */
    private class StubRelayTransport : RelayTransport {
        override val inbound: Flow<InnerFrameV2> = emptyFlow()
        override val events: Flow<TransportEvent> = emptyFlow()

        override fun connect() = Unit

        override fun send(frame: InnerFrameV2): Boolean = true

        override fun close() = Unit
    }

    private companion object {
        const val TS = "2026-05-31T00:00:00Z"
    }
}
