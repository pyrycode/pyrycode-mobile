package de.pyryco.mobile.recording

import android.os.Bundle
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.crypto.DeviceStaticKeyStore
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.NoiseClientInfo
import de.pyryco.mobile.data.network.NoiseSessionPump
import de.pyryco.mobile.data.network.OkHttpRelayTransport
import de.pyryco.mobile.data.network.RelayTransport
import de.pyryco.mobile.data.network.RelayTransportFactory
import de.pyryco.mobile.data.preferences.AppPreferences
import de.pyryco.mobile.data.repository.ManagedSessionPump
import de.pyryco.mobile.di.ObservablePairedServerStore
import de.pyryco.mobile.di.RelayConnectionFactory
import de.pyryco.mobile.di.RelayConnectionRegistry
import de.pyryco.mobile.di.RelaySessionPumpFactory
import de.pyryco.mobile.e2e.E2eTestApplication
import de.pyryco.mobile.e2e.OwnedReplyProbe
import de.pyryco.mobile.e2e.SettingsReadProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.runner.Description
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.dsl.module
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

/** Test-only composition root. The normal application has no reference to this class. */
internal object RecordedSuiteNative {
    private val credentialFields =
        setOf(
            "token",
            "peerToken",
            "serverStaticPublicKey",
            "pairCodeB",
            "answerPairCode",
            "answerPeerToken",
            "answerServerStaticPublicKey",
            "bypassFixtureAuthorization",
            "sessionErrorAuthorization",
        )
    private val localFields = setOf("class", "recordedSuite", "suiteFile", "stream", "debug", "listener")
    private var current: Session? = null
    val active get() = current != null

    fun applicationStarting(args: Bundle) {
        if (args.getString("recordedSuite") == "capture") current = Session(null, args)
    }

    fun begin(description: Description) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        if (args.getString("recordedSuite") == "capture") {
            checkNotNull(current).scenario = description.methodName
            return
        }
        if (description.className != RecordedMobileSuiteTest::class.java.name) return
        val fileName = args.getString("suiteFile")
        val text =
            if (fileName != null) {
                require(Regex("recorded-[a-f0-9]+\\.json").matches(fileName))
                File(instrumentation.targetContext.filesDir, fileName).readText()
            } else {
                instrumentation.context.assets
                    .open("recorded/suite/${description.methodName}.json")
                    .bufferedReader()
                    .use { it.readText() }
            }
        val index =
            instrumentation.context.assets
                .open("recorded/suite.json")
                .bufferedReader()
                .use { it.readText() }
        val policy = MobileJson.parseToJsonElement(index).jsonObject["queryPolicy"]?.jsonObject
        val parsed = RecordedSuiteDocument.parse(text)
        val queryDocument = policy?.let(parsed::withQueryPolicy) ?: parsed
        val readPolicy = MobileJson.parseToJsonElement(index).jsonObject["readReceiptPolicy"]?.jsonPrimitive
        val document = readPolicy?.let(queryDocument::withReadPolicy) ?: queryDocument
        check(document.scenario == description.methodName) { "Recording belongs to another method" }
        val session = Session(document, args)
        current = session
        val resolved = checkNotNull(session.tape).arguments(document.inputs)
        val arguments = resolved.getValue("arguments").jsonObject
        arguments.forEach { (key, value) -> args.putString(key, value.jsonPrimitive.content) }
        args.putString("relayUrl", "wss://recorded.invalid")
        args.putString("daemonFaultPort", "1")
        credentialFields.forEach { key ->
            val host =
                when (key) {
                    "pairCodeB" -> args.getString("serverIdB")
                    "answerPairCode" -> args.getString("answerServerId")
                    else -> args.getString("serverId")
                }
            args.putString(
                key,
                when {
                    key.contains("PairCode") || key == "pairCodeB" -> pairCode(checkNotNull(host))
                    key.contains("PublicKey") -> FAKE_KEY
                    key.endsWith("Authorization") -> "A".repeat(43)
                    else -> FAKE_TOKEN
                },
            )
        }
        args.putString("recordedSuite", "replay")
        InstrumentationRegistry.registerInstance(instrumentation, args)
        runBlocking {
            withTimeout(5000) {
                ProcessLifecycleOwner
                    .get()
                    .lifecycle.currentStateFlow
                    .first { !it.isAtLeast(Lifecycle.State.STARTED) }
            }
            val koin = GlobalContext.get()
            val store = koin.get<ObservablePairedServerStore>()
            session.originalPairings = store.readSnapshot().getOrThrow().map { it.record }
            koin.get<DataStore<Preferences>>().updateData {
                session.originalPreferences = it
                emptyPreferences()
            }
            session.originalPairings!!.forEach { store.remove(it.serverId) }
        }
        instrumentation.runOnMainSync { (instrumentation.targetContext.applicationContext as E2eTestApplication).rebuildGraph() }
        runBlocking {
            GlobalContext.get().get<PairedServerStore>().save(
                PairedServer(checkNotNull(args.getString("serverId")), FAKE_TOKEN, "wss://recorded.invalid", FAKE_KEY),
            )
        }
    }

    fun bindings(): Module {
        val session = current ?: return module { }
        return module {
            if (session.document != null) {
                single<de.pyryco.mobile.push.PushTokenSource> {
                    object : de.pyryco.mobile.push.PushTokenSource {
                        override fun isAvailable() = false

                        override suspend fun currentToken(): String = error("FCM requested during suite replay")
                    }
                }
            }
            single<de.pyryco.mobile.push.PushWakeHandler> {
                de.pyryco.mobile.push.PushWakeHandler {
                    check(session.document == null) { "Real push received during offline suite replay" }
                    session.hub.observeExternal("push-wake")
                    get<de.pyryco.mobile.lifecycle.LifecycleConnectionDriver>().onPushWake()
                }
            }
            single<RelayConnectionFactory> {
                val original = get<RelayTransportFactory>()
                val clientInfo = get<NoiseClientInfo>()
                val deviceKeys = get<DeviceStaticKeyStore>()
                RelayConnectionFactory(
                    deviceKeys,
                    RelayTransportFactory { pairing ->
                        session.remember(pairing)
                        val owner = session.owner("phone", pairing.serverId)
                        if (session.document != null) {
                            // Replay bypasses Noise, so create the real runner key before provisional sends use it.
                            val identity =
                                runBlocking { deviceKeys.loadOrCreate(pairing.serverId).publicKey }
                                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                            checkNotNull(session.tape).bindPhoneDevice(owner, identity)
                        }
                        OwnedReplyProbe.transport(
                            pairing.serverId,
                            session.hub.transport(owner) { original.create(pairing) },
                        )
                    },
                    session.document?.replayClientInfo(clientInfo) ?: clientInfo,
                    pushTokens =
                        if (session.document != null) {
                            session.pushTokens
                        } else {
                            get<AppPreferences>().pushToken.onEach { value ->
                                if (!value.isNullOrEmpty()) session.pushAddress(value)
                            }
                        },
                    messageTrail = get(),
                    pumpFactory =
                        RelaySessionPumpFactory { transport, factory, dispatcher, minimum ->
                            OwnedReplyProbe.wrap(
                                transport,
                                SettingsReadProbe.wrap(
                                    session.hub.pump(
                                        transport,
                                    ) { NoiseSessionPump(transport, factory, dispatcher, onClientMinimum = minimum) },
                                ),
                            )
                        },
                )
            }
        }
    }

    fun peerTransport(
        pairing: PairedServer,
        info: NoiseClientInfo,
    ): RelayTransport? =
        current?.let { session ->
            session.remember(pairing)
            session.hub.transport(session.owner("peer", pairing.serverId)) {
                OkHttpRelayTransport(pairing, info, OkHttpRelayTransport.defaultClient())
            }
        }

    fun pump(
        transport: RelayTransport,
        live: () -> ManagedSessionPump,
    ): ManagedSessionPump = current?.hub?.pump(transport, live) ?: live()

    fun peerPermissionReady(
        pump: ManagedSessionPump?,
        modalId: String,
    ): Boolean = current?.hub?.peerPermissionReady(pump, modalId) ?: true

    suspend fun beforePeerPermission(
        pump: ManagedSessionPump,
        modalId: String,
        timeoutMs: Long,
    ) {
        current?.hub?.beforePeerPermission(pump, modalId, timeoutMs)
    }

    fun control(
        action: String,
        body: String,
        live: () -> Unit,
    ) {
        current?.hub?.control(action, body, live) ?: live()
    }

    /** Keep the real sentinel, but a completed replay needs no producer's 90-second response budget. */
    fun negativeObservation(
        name: String,
        liveTimeoutMs: Long,
        originalWait: (Long) -> Unit,
    ) {
        if (!active) return originalWait(liveTimeoutMs)
        if (replaying) runBlocking { checkNotNull(current).hub.beforeNegativeObservation(name) }
        try {
            originalWait(if (replaying) 1000 else liveTimeoutMs)
        } catch (_: androidx.compose.ui.test.ComposeTimeoutException) {
            control("negative-absence", name) { }
            return
        }
        error("Negative control unexpectedly matched its impossible observation")
    }

    val replaying get() = current?.document != null

    /** Captured minimal notifications must be consumed before the final independent settings read. */
    fun beforeSettingsObservation(serverId: String) {
        val session = current?.takeIf { it.document != null } ?: return
        val owner = checkNotNull(session.currentOwner("phone", serverId)) { "Settings observation owner missing" }
        android.util.Log.i("RecordedSuite", "event=settings_observation_fence phase=started")
        runBlocking { session.hub.beforeSettingsObservation(owner) }
        check(current === session && session.currentOwner("phone", serverId) == owner) { "Settings observation owner changed" }
        android.util.Log.i("RecordedSuite", "event=settings_observation_fence phase=consumed")
    }

    /** Passive only: do not drain, checkpoint, or consult the upstream connection. */
    fun modelReopenProgress(serverId: String): String {
        val session = current ?: return "recording=inactive"
        return "phone_owner=${session.currentOwner("phone", serverId)} ${session.tape?.progress() ?: "recording=capture"}"
    }

    fun beforeHostLinkClose(serverId: String) {
        val session = current?.takeIf { it.document != null } ?: return
        val owner = session.currentOwner("phone", serverId) ?: return
        runBlocking { session.hub.beforeClose(owner) }
    }

    fun exchange(
        action: String,
        body: JsonObject,
        live: () -> JsonObject,
    ): JsonObject = current?.hub?.exchangeControl(action, body, live) ?: live()

    fun rememberCredential(value: String) {
        current?.credentials?.add(value)
    }

    fun inspectArtifactMember(bytes: ByteArray) {
        val session = current ?: return
        if (session.document == null) requireSafeCapturedBytes(bytes, session.credentials)
    }

    fun syntheticCredentials(server: String): Map<String, JsonPrimitive> =
        mapOf(
            "pairCode" to JsonPrimitive(pairCode(server)),
            "peerToken" to JsonPrimitive(FAKE_TOKEN),
            "serverStaticPublicKey" to JsonPrimitive(FAKE_KEY),
            "relayUrl" to JsonPrimitive("wss://recorded.invalid"),
        )

    @Synchronized fun uniqueStamp(): Long {
        val session = current ?: return System.currentTimeMillis()
        return if (session.document == null) {
            System.currentTimeMillis().also { session.clockReads += it }
        } else {
            check(session.clockIndex < session.clockReads.size) { "Unexpected generated suite input" }
            session.clockReads[session.clockIndex++]
        }
    }

    /** Older captures retain their original checks; only actual new captures acquire observation checkpoints. */
    fun checkpoint(name: String) {
        val session = current ?: return
        if (session.document != null && session.document.document["checkpointPolicy"] != JsonPrimitive("test-observations-v1")) return
        runBlocking { session.hub.checkpoint(name) }
    }

    /** A graph rebuild must drain its current phones before destroying their request producers. */
    fun beforePhoneRestart() {
        val session = current?.takeIf { it.document != null } ?: return
        runBlocking { session.hub.beforePhoneRestart() }
    }

    /** Keep the real screen active while a replay catches up with its captured pre-close requests. */
    fun beforePhoneTeardown(endSession: () -> Unit) {
        val session = current ?: return
        if (session.document == null || session.document.document["checkpointPolicy"] != JsonPrimitive("test-observations-v1")) return
        android.util.Log.i("RecordedSuite", "event=terminal_preparation_started")
        runBlocking { session.hub.beforePhoneTeardown() }
        endSession()
        session.scenarioCompletePrepared = runBlocking { session.hub.completeBeforePhoneTeardown() }
        android.util.Log.i("RecordedSuite", "event=terminal_preparation_finished early_checkpoint=${session.scenarioCompletePrepared}")
    }

    fun finish(success: Boolean) {
        val session = current ?: return
        try {
            if (success && !session.scenarioCompletePrepared) checkpoint("scenario-complete")
            GlobalContext.get().get<RelayConnectionRegistry>().dispose()
            runBlocking {
                withTimeout(10000) {
                    session.hub.awaitReaders()
                    session.hub.awaitIdle()
                }
            }
            if (!success) {
                if (session.document != null) error(checkNotNull(session.tape).progress())
                exportCapture(session, failed = true)
                return
            }
            if (session.document != null) {
                check(session.clockIndex == session.clockReads.size) { "Unconsumed generated suite inputs" }
                session.hub.assertComplete()
            } else {
                exportCapture(session, failed = false)
            }
        } catch (error: Throwable) {
            session.tape?.let { error.addSuppressed(IllegalStateException(it.progress())) }
            throw error
        } finally {
            session.hub.close()
            session.scope.cancel()
            current = null
            if (session.document != null) restoreOrdinaryTestEnvironment(session)
        }
    }

    private fun restoreOrdinaryTestEnvironment(session: Session) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        InstrumentationRegistry.registerInstance(instrumentation, Bundle(session.originalArguments))
        runBlocking {
            val koin = GlobalContext.get()
            session.originalPairings?.let { originals ->
                val store = koin.get<ObservablePairedServerStore>()
                store.readSnapshot().getOrThrow().forEach { store.remove(it.record.serverId) }
                originals.forEach { store.save(it) }
            }
            session.originalPreferences?.let { original ->
                koin.get<DataStore<Preferences>>().updateData { original }
            }
        }
        instrumentation.runOnMainSync { (instrumentation.targetContext.applicationContext as E2eTestApplication).rebuildGraph() }
    }

    private fun exportCapture(
        session: Session,
        failed: Boolean,
    ) {
        val steps = if (failed) session.hub.diagnosticCapture() ?: return else session.hub.completedCapture(true)
        val inputs =
            buildJsonObject {
                put("arguments", session.arguments)
                put("clockReads", JsonArray(session.clockReads.map(::JsonPrimitive)))
                put(
                    "hosts",
                    JsonArray(
                        session.hosts.map { (id, label) ->
                            buildJsonObject {
                                put("id", id)
                                put("label", label)
                            }
                        },
                    ),
                )
            }
        val declared =
            session.hosts.keys +
                session.arguments
                    .filterKeys { it.endsWith("Id") || it == "serverIdB" }
                    .values
                    .map { it.jsonPrimitive.content }
        val body = RecordedSuiteDocument.body(session.scenario, inputs, declared.toSet(), steps)
        requireSafeSuiteDocument(body)
        val output = if (failed) JsonObject(body + ("format" to JsonPrimitive("pyrycode.mobile-suite-failure"))) else body
        val serialized = output.toString()
        check(session.credentials.none { it.isNotEmpty() && serialized.contains(it) }) { "Credential rejected from suite capture" }
        val args = InstrumentationRegistry.getArguments()
        val fileName = requireNotNull(args.getString("suiteFile"))
        require(Regex("recorded-[a-f0-9]+\\.json").matches(fileName))
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, fileName + if (failed) ".failed" else "")
        val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        Files
            .newByteChannel(
                file.toPath(),
                options,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
            ).use {
                val bytes = ByteBuffer.wrap(serialized.toByteArray())
                while (bytes.hasRemaining()) it.write(bytes)
            }
    }

    private class Session(
        val document: RecordedSuiteDocument?,
        args: Bundle,
    ) {
        val originalArguments = Bundle(args)
        var scenarioCompletePrepared = false
        var originalPreferences: Preferences? = null
        var originalPairings: List<PairedServer>? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tape = document?.tape()
        val pushTokens = MutableStateFlow<String?>(null)
        val hub =
            if (tape == null) {
                SuiteConnections.recording(scope)
            } else {
                SuiteConnections.replaying(
                    scope,
                    tape,
                    originalSpeed = document?.scenario in SuiteConnections.ORIGINAL_SPEED_SCENARIOS,
                ) { event ->
                    when (
                        event.detail
                            .getValue("event")
                            .jsonPrimitive.content
                    ) {
                        "push-token" -> {
                            runBlocking { GlobalContext.get().get<AppPreferences>().setPushToken(RECORDED_PUSH_ADDRESS) }
                            pushTokens.value = RECORDED_PUSH_ADDRESS
                        }
                        "push-wake" -> GlobalContext.get().get<de.pyryco.mobile.lifecycle.LifecycleConnectionDriver>().onPushWake()
                    }
                }
            }
        var scenario = document?.scenario.orEmpty()
        val arguments =
            JsonObject(
                args
                    .keySet()
                    .filter {
                        it !in credentialFields && it !in localFields
                    }.associateWith { JsonPrimitive(args.getString(it).orEmpty()) },
            )
        val clockReads =
            document
                ?.inputs
                ?.getValue("clockReads")
                ?.jsonArray
                ?.map { it.jsonPrimitive.long }
                ?.toMutableList() ?: mutableListOf()
        var clockIndex = 0
        val credentials = java.util.concurrent.CopyOnWriteArraySet<String>()
        val hosts = linkedMapOf<String, String>()
        private val generations = mutableMapOf<String, Int>()

        init {
            credentialFields.mapNotNull(args::getString).filter { it.isNotEmpty() }.forEach(credentials::add)
            if (document != null) {
                tape!!.arguments(document.inputs).getValue("hosts").jsonArray.forEach { value ->
                    val host = value.jsonObject
                    hosts[host.getValue("id").jsonPrimitive.content] = host.getValue("label").jsonPrimitive.content
                }
            } else {
                listOf("serverId", "serverIdB", "answerServerId").mapNotNull(args::getString).distinct().forEach {
                    hosts[it] =
                        "host-${hosts.size}"
                }
            }
        }

        private var lastPushAddress: String? = null

        @Synchronized fun pushAddress(value: String) {
            credentials += value
            if (lastPushAddress == value) return
            lastPushAddress = value
            hub.observeExternal("push-token")
        }

        fun remember(pairing: PairedServer) {
            credentials += pairing.token
            credentials += pairing.serverStaticPublicKey
        }

        @Synchronized fun currentOwner(
            role: String,
            host: String,
        ): String? {
            val label = hosts[host] ?: return null
            val key = "$role/$label"
            val generation = generations[key]?.minus(1) ?: return null
            return "$key/$generation"
        }

        @Synchronized fun owner(
            role: String,
            host: String,
        ): String {
            if (document != null) check(host in hosts) { "Unexpected suite host" }
            val label = hosts.getOrPut(host) { "host-${hosts.size}" }
            val key = "$role/$label"
            val generation = generations[key] ?: 0
            generations[key] = generation + 1
            return "$key/$generation"
        }
    }

    private val FAKE_TOKEN = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 })
    private val FAKE_KEY = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })

    private fun pairCode(server: String): String {
        val payload =
            buildJsonObject {
                put("server", server)
                put("relay", "wss://recorded.invalid")
                put("token", FAKE_TOKEN)
                put("server_static_pubkey", FAKE_KEY)
            }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray())
    }
}
