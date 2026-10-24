package de.pyryco.mobile.recording

import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.InnerFrameV2
import de.pyryco.mobile.data.network.PumpState
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.network.TransportEvent
import de.pyryco.mobile.data.repository.ManagedSessionPump
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Application-only connections shared by the real phone graph and its independent peer. */
internal class SuiteConnections private constructor(
    parent: CoroutineScope,
    private val tape: RecordedSuiteTape?,
    private val capture: SuiteCapture?,
    private val onExternal: (SuiteStep) -> Unit = { error("Unexpected recorded external event") },
    private val originalSpeed: Boolean = false,
    private val beforeRequestEnqueue: () -> Unit = {},
) : AutoCloseable {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val transports = mutableMapOf<String, RecordedTransport>()
    private val pumps = mutableMapOf<String, ReplayPump>()
    private val waiting = mutableMapOf<String, MutableList<SuiteStep>>()
    private val pending = AtomicInteger()
    private val progress = MutableStateFlow(0L)
    private val failure = AtomicReference<Throwable?>()
    private val controls = AtomicLong()
    private val controlReplies = java.util.concurrent.ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val deliveries = Channel<Delivery>(Channel.UNLIMITED)
    private val readers = CopyOnWriteArrayList<Job>()

    private sealed interface Delivery {
        data class Steps(
            val values: List<SuiteStep>,
        ) : Delivery

        data class Barrier(
            val done: CompletableDeferred<Unit>,
        ) : Delivery
    }

    init {
        scope.launch {
            for (delivery in deliveries) {
                when (delivery) {
                    is Delivery.Barrier -> delivery.done.complete(Unit)
                    is Delivery.Steps -> for (step in delivery.values) {
                        try {
                            delay(if (originalSpeed) step.delayMs else replayDelayMs(step.delayMs))
                            route(step)
                        } catch (error: Throwable) {
                            failure.compareAndSet(null, error)
                        }
                    }
                }
            }
        }
        tape?.initial()?.let(::enqueue)
    }

    @Synchronized fun transport(
        owner: String,
        live: () -> RelayTransport,
    ): RelayTransport {
        check(owner !in transports) { "Duplicate recorded suite connection owner" }
        return RecordedTransport(owner, if (capture != null) live() else null).also { transports[owner] = it }
    }

    fun pump(
        transport: RelayTransport,
        live: () -> ManagedSessionPump,
    ): ManagedSessionPump {
        val connection = transport as RecordedTransport
        return if (capture != null) ObservedPump(connection, live()) else ReplayPump(connection)
    }

    @Synchronized private fun enqueue(values: List<SuiteStep>) {
        if (values.isEmpty()) return
        pending.addAndGet(values.size)
        val (responses, scheduled) = values.partition { it.detail["replayResponse"] == kotlinx.serialization.json.JsonPrimitive(true) }
        responses.forEach(::route)
        if (scheduled.isEmpty()) return
        check(deliveries.trySend(Delivery.Steps(scheduled)).isSuccess) { "Recorded suite delivery closed" }
    }

    @Synchronized private fun request(
        owner: String,
        frame: Envelope,
    ): Boolean {
        progress.value += 1
        val result = checkNotNull(tape).send(owner, frame)
        beforeRequestEnqueue()
        enqueue(result.deliveries)
        return result.accepted
    }

    @Synchronized private fun lifecycle(
        direction: String,
        owner: String,
    ) {
        progress.value += 1
        if (capture != null) {
            capture.observe(SuiteStep(direction, owner))
        } else {
            enqueue(checkNotNull(tape).lifecycle(direction, owner))
        }
    }

    @Synchronized private fun route(step: SuiteStep) {
        progress.value += 1
        if (step.direction == "settings_snapshot") {
            enqueue(checkNotNull(tape).deliveredSnapshot(step))
            pending.decrementAndGet()
            return
        }
        if (step.direction == "external") {
            onExternal(step)
            pending.decrementAndGet()
            return
        }
        if (step.direction == "daemon" && step.owner == "control") {
            val frame = checkNotNull(step.message)
            checkNotNull(controlReplies.remove(frame.inReplyTo)).complete(frame.payload.jsonObject)
            pending.decrementAndGet()
            return
        }
        if (step.direction == "transport_down") {
            val code = step.detail["code"]?.jsonPrimitive?.intOrNull
            val reason = step.detail["reason"]?.jsonPrimitive?.contentOrNull
            tape?.connectionEnded(step.owner)
            checkNotNull(transports[step.owner]).remoteDown(code, reason)
            pending.decrementAndGet()
            return
        }
        val pump = pumps[step.owner]
        if (pump == null) {
            waiting.getOrPut(step.owner) { mutableListOf() } += step
        } else {
            pump.deliver(step)
            pending.decrementAndGet()
        }
    }

    @Synchronized private fun register(pump: ReplayPump) {
        check(pumps.put(pump.connection.owner, pump) == null) { "Duplicate suite pump" }
        waiting.remove(pump.connection.owner).orEmpty().forEach {
            pump.deliver(it)
            pending.decrementAndGet()
        }
    }

    fun control(
        action: String,
        body: String,
        live: () -> Unit,
    ) {
        val frame =
            Envelope(
                controls.incrementAndGet(),
                "test-control:$action",
                java.time.Instant
                    .now()
                    .toString(),
                buildJsonObject { put("body", body) },
            )
        if (capture != null) {
            capture.observe(SuiteStep("client", "control", frame))
            live()
        } else {
            if (action == "test-end") {
                runBlocking {
                    withTimeout(30000) {
                        while (!synchronized(this@SuiteConnections) {
                                enqueue(checkNotNull(tape).prepareTestEnd(frame))
                                tape.testEndReady(frame)
                            }
                        ) {
                            delay(10)
                        }
                        awaitIdle()
                    }
                }
            }
            check(request("control", frame)) { "Recorded suite control rejected" }
        }
    }

    /** A test must reach the captured close prefix before cancelling the app jobs that supply it. */
    suspend fun beforeClose(owner: String) {
        if (tape == null || synchronized(this) { transports[owner]?.localClose?.get() == true }) return
        withTimeout(30000) {
            while (!synchronized(this@SuiteConnections) {
                    if (transports[owner]?.localClose?.get() == true) {
                        true
                    } else {
                        enqueue(tape.prepareClose(owner))
                        tape.closeReady(owner)
                    }
                }
            ) {
                delay(10)
            }
            awaitIdle()
            val pump = synchronized(this@SuiteConnections) { pumps[owner] }
            pump?.awaitConsumedPrefix()
        }
    }

    /** Drain only this graph's phones while its activity and request-producing jobs are alive. */
    suspend fun beforePhoneRestart() {
        if (tape == null) return
        val owners =
            synchronized(this) {
                transports.values.filter { it.owner.startsWith("phone/") && !it.localClose.get() }.map { it.owner }
            }
        for (owner in owners) beforeClose(owner)
    }

    suspend fun beforePhoneTeardown() {
        val replay = tape ?: return
        for (owner in replay.phoneClosesBeforeTeardown()) {
            withTimeout(30000) {
                while (!synchronized(this@SuiteConnections) { owner in transports }) delay(10)
            }
            beforeClose(owner)
        }
        withTimeout(30000) {
            while (!synchronized(this@SuiteConnections) {
                    enqueue(replay.preparePhoneTeardown())
                    replay.phoneWorkBeforeTestEndReady()
                }
            ) {
                delay(10)
            }
            awaitIdle()
        }
    }

    /** Test-end has run; retain producers through the captured terminal observation or close prefix. */
    suspend fun completeBeforePhoneTeardown(): Boolean =
        withTimeout(30000) {
            val replay = tape ?: return@withTimeout false
            beforePhoneTeardown()
            if (!replay.scenarioCompletesBeforePhoneClose()) return@withTimeout false
            checkpoint("scenario-complete")
            beforePhoneRestart()
            true
        }

    suspend fun checkpoint(name: String) {
        require(name.matches(Regex("[a-z][a-z0-9-]{0,79}"))) { "Invalid test checkpoint name" }
        if (tape != null) {
            withTimeout(30000) {
                while (!tape.checkpointReady(name)) delay(10)
                awaitIdle()
            }
        }
        control("checkpoint", name) { }
    }

    suspend fun beforeNegativeObservation(name: String) {
        if (tape == null) return
        withTimeout(90000) {
            while (!tape.negativeObservationReady(name)) delay(10)
            awaitIdle()
        }
    }

    fun observeExternal(event: String) {
        checkNotNull(capture).observe(SuiteStep("external", "android", detail = buildJsonObject { put("event", event) }))
    }

    fun exchangeControl(
        action: String,
        body: JsonObject,
        live: () -> JsonObject,
    ): JsonObject {
        val frame =
            Envelope(
                controls.incrementAndGet(),
                "test-control:$action",
                java.time.Instant
                    .now()
                    .toString(),
                body,
            )
        if (capture != null) {
            capture.observe(SuiteStep("client", "control", frame))
            val result = live()
            capture.observe(
                SuiteStep("daemon", "control", frame.copy(type = "test-control-result", payload = result, inReplyTo = frame.id)),
            )
            return result
        }
        val reply = CompletableDeferred<JsonObject>()
        controlReplies[frame.id] = reply
        try {
            check(request("control", frame)) { "Recorded suite control rejected" }
            return runBlocking { withTimeout(65000) { reply.await() } }
        } finally {
            controlReplies.remove(frame.id)
        }
    }

    fun peerPermissionReady(
        pump: ManagedSessionPump?,
        modalId: String,
    ): Boolean =
        if (tape == null) {
            true
        } else {
            val replay = pump as? ReplayPump
            replay != null &&
                synchronized(this) {
                    pumps[replay.connection.owner] === replay && tape.peerPermissionReady(replay.connection.owner, modalId)
                }
        }

    suspend fun beforePeerPermission(
        pump: ManagedSessionPump,
        modalId: String,
        timeoutMs: Long,
    ) {
        if (tape == null) return
        withTimeout(timeoutMs) {
            while (!peerPermissionReady(pump, modalId)) {
                failure.get()?.let { throw it }
                if (pump.state.value is PumpState.Closed) return@withTimeout
                delay(10)
            }
        }
    }

    suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>()
        deliveries.send(Delivery.Barrier(done))
        done.await()
        failure.get()?.let { throw IllegalStateException("Recorded suite delivery failed", it) }
    }

    /** Settle this action's settings traffic before a fresh read supplies a UI expectation. */
    suspend fun beforeSettingsObservation(owner: String) {
        check(tape != null) { "Settings observation requires replay" }
        val pump = synchronized(this) { checkNotNull(pumps[owner]) { "Settings observation phone missing" } }

        fun checkOwner() {
            check(pumps[owner] === pump && !pump.connection.localClose.get() && pump.state.value is PumpState.Open) {
                "Settings observation phone changed or closed"
            }
        }
        withTimeout(30000) {
            while (true) {
                val observedProgress = synchronized(this@SuiteConnections) { progress.value }
                awaitIdle()
                synchronized(this@SuiteConnections) { checkOwner() }
                pump.awaitConsumedPrefix()
                val settled =
                    synchronized(this@SuiteConnections) {
                        checkOwner()
                        pending.get() == 0 && pump.consumptionCaughtUp() && tape.settingsObservationReady(owner)
                    }
                if (settled) return@withTimeout
                progress.first { it != observedProgress }
            }
        }
    }

    suspend fun awaitReaders() {
        val caller = currentCoroutineContext()[Job]
        readers.filter { it != caller }.forEach { it.join() }
    }

    fun assertComplete() {
        failure.get()?.let { throw IllegalStateException("Recorded suite delivery failed", it) }
        check(pending.get() == 0) { "Recorded suite deliveries remain pending" }
        checkNotNull(tape).assertComplete()
    }

    fun completedCapture(success: Boolean): List<SuiteStep> = checkNotNull(capture).completed(success)

    fun diagnosticCapture(): List<SuiteStep>? = checkNotNull(capture).diagnostic()

    override fun close() {
        deliveries.close()
        pumps.values.forEach { it.frames.close() }
        scope.cancel()
    }

    private inner class RecordedTransport(
        val owner: String,
        val live: RelayTransport?,
    ) : RelayTransport {
        val localClose = AtomicBoolean()
        val remoteClosed = AtomicBoolean()
        private val eventsChannel = Channel<TransportEvent>(Channel.UNLIMITED)
        var observePumpState: () -> Unit = {}
        override val inbound = live?.inbound ?: emptyFlow<InnerFrameV2>()
        override val events =
            live?.events?.onStart { currentCoroutineContext()[Job]?.let(readers::add) }?.onEach { event ->
                if (event is TransportEvent.Down) {
                    capture!!.observe(
                        SuiteStep(
                            "transport_down",
                            owner,
                            detail =
                                buildJsonObject {
                                    event.code?.let { put("code", it) }
                                    event.reason?.let { put("reason", it) }
                                },
                        ),
                    )
                }
            } ?: eventsChannel.receiveAsFlow()

        override fun connect() {
            lifecycle("connect", owner)
            if (live != null) {
                live.connect()
            } else {
                check(eventsChannel.trySend(TransportEvent.Up).isSuccess)
            }
        }

        override fun send(frame: InnerFrameV2): Boolean = live?.send(frame) ?: error("Raw transport use during application replay")

        override fun close() =
            synchronized(this@SuiteConnections) {
                // Publish closure and consume its lifecycle under the send lock. A second closer
                // must not return while the first has hidden the tape's following refused request.
                if (localClose.get()) return@synchronized
                observePumpState()
                if (!localClose.compareAndSet(false, true)) return@synchronized
                try {
                    lifecycle("close", owner)
                } catch (error: Throwable) {
                    if (capture != null) throw error
                    // Report at the outer rule, after the scenario's own assertion and physical teardown.
                    failure.compareAndSet(null, error)
                } finally {
                    if (live != null) live.close() else pumps[owner]?.localClosed()
                }
            }

        fun remoteDown(
            code: Int?,
            reason: String?,
        ) {
            remoteClosed.set(true)
            check(eventsChannel.trySend(TransportEvent.Down(code, reason, null)).isSuccess)
            eventsChannel.close()
        }
    }

    private inner class ObservedPump(
        val connection: RecordedTransport,
        val delegate: ManagedSessionPump,
    ) : ManagedSessionPump {
        private val opened = AtomicBoolean()
        private val ended = AtomicBoolean()
        private val explicitClose = AtomicBoolean()

        init {
            connection.observePumpState = { observeState(delegate.state.value) }
        }

        override val state = delegate.state
        private val stateObserver =
            scope
                .launch(start = CoroutineStart.UNDISPATCHED) {
                    delegate.state
                        .takeWhile { value ->
                            observeState(value)
                            value !is PumpState.Closed
                        }.collect()
                }.also(readers::add)
        override val inbound =
            delegate.inbound
                .onStart { currentCoroutineContext()[Job]?.let(readers::add) }
                .onEach {
                    observeState(delegate.state.value)
                    capture!!.observe(SuiteStep("daemon", connection.owner, it))
                }.onCompletion { capture!!.observe(SuiteStep("inbound_end", connection.owner)) }

        private fun observeState(value: PumpState) {
            when (value) {
                is PumpState.Open ->
                    if (opened.compareAndSet(false, true)) {
                        capture!!.observe(SuiteStep("open", connection.owner, capabilities = value.capabilities))
                    }
                is PumpState.Closed ->
                    if (!explicitClose.get() && !connection.localClose.get() && ended.compareAndSet(false, true)) {
                        capture!!.observe(SuiteStep("down", connection.owner))
                    }
                else -> Unit
            }
        }

        override fun start() = delegate.start()

        override fun send(envelope: Envelope): Boolean =
            synchronized(this@SuiteConnections) {
                observeState(delegate.state.value)
                capture!!.send(connection.owner, envelope) { delegate.send(envelope) }
            }

        override fun close() {
            explicitClose.set(true)
            stateObserver.cancel()
            delegate.close()
        }
    }

    private inner class ReplayPump(
        val connection: RecordedTransport,
    ) : ManagedSessionPump {
        override val state = MutableStateFlow<PumpState>(PumpState.Handshaking)
        val frames = Channel<Envelope>(Channel.UNLIMITED)
        private val queuedFrames = AtomicLong()
        private val consumedFrames = AtomicLong()
        override val inbound =
            flow {
                for (frame in frames) {
                    emit(frame)
                    // Receipt in the channel is not application consumption. In particular, the
                    // repository must apply a terminal history marker before its scope is cancelled.
                    consumedFrames.incrementAndGet()
                }
            }
        private val started = AtomicBoolean()

        suspend fun awaitConsumedPrefix() {
            val boundary = queuedFrames.get()
            while (consumedFrames.get() < boundary) delay(10)
        }

        fun consumptionCaughtUp(): Boolean = consumedFrames.get() >= queuedFrames.get()

        override fun start() {
            if (started.compareAndSet(false, true)) register(this)
        }

        override fun send(envelope: Envelope): Boolean =
            synchronized(this@SuiteConnections) {
                // NoiseSessionPump refuses locally once closed; a late upload chunk never reaches the wire.
                // Explicitly captured refused calls still match their full payload and recorded result.
                if ((connection.localClose.get() || connection.remoteClosed.get() || state.value !is PumpState.Open) &&
                    !checkNotNull(tape).hasEligibleRefusal(connection.owner)
                ) {
                    false
                } else {
                    request(connection.owner, envelope)
                }
            }

        override fun close() = connection.close()

        fun localClosed() {
            state.value = PumpState.Closed(null)
        }

        fun deliver(step: SuiteStep) {
            when (step.direction) {
                "open" -> state.value = PumpState.Open(connection.owner, step.capabilities)
                "down" -> {
                    state.value = PumpState.Closed(null)
                    connection.close()
                }
                "daemon" -> {
                    val frame = checkNotNull(step.message)
                    check(frames.trySend(frame).isSuccess)
                    queuedFrames.incrementAndGet()
                    tape!!.delivered(connection.owner, frame)
                    enqueue(tape.initial())
                }
                "inbound_end" -> {
                    tape!!.connectionEnded(connection.owner)
                    frames.close()
                }
                else -> error("Unsupported recorded suite delivery")
            }
        }
    }

    companion object {
        /**
         * Replay proves payload, order and correlation, not timing (docs/recorded-tests.md), so captured
         * gaps shrink by this divisor. Capture still records the real gaps; recordings stay unchanged.
         */
        const val REPLAY_DELAY_DIVISOR = 10L

        /** A nonzero gap keeps this much, or its captured value if shorter, so frames stay in separate UI frames. */
        const val REPLAY_MIN_GAP_MS = 20L

        /**
         * Recordings that replay at their captured speed. Each one asserts a transient on-screen state
         * that lasts only as long as a captured gap, so the scaled gap could end it before the
         * assertion sees it. The new-session scenario waits for the "wrapping up" label, which the
         * recording holds for about 4.5 seconds; scaled, it lasts about 0.45 seconds.
         */
        val ORIGINAL_SPEED_SCENARIOS = setOf("interactiveTurn_newSession_rendersSessionBoundaryDelimiter")

        fun replayDelayMs(capturedMs: Long): Long =
            if (capturedMs <= 0) 0 else maxOf(capturedMs / REPLAY_DELAY_DIVISOR, minOf(capturedMs, REPLAY_MIN_GAP_MS))

        fun replaying(
            scope: CoroutineScope,
            tape: RecordedSuiteTape,
            originalSpeed: Boolean = false,
            // Controlled concurrency probes pause after tape release, while the hub monitor is held.
            beforeRequestEnqueue: () -> Unit = {},
            onExternal: (SuiteStep) -> Unit = { error("Unexpected recorded external event") },
        ) = SuiteConnections(scope, tape, null, onExternal, originalSpeed, beforeRequestEnqueue)

        fun recording(
            scope: CoroutineScope,
            capture: SuiteCapture = SuiteCapture(),
        ) = SuiteConnections(scope, null, capture)
    }
}
