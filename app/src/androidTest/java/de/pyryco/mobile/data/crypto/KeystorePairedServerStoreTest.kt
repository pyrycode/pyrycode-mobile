package de.pyryco.mobile.data.crypto

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.security.KeyStore
import java.security.ProviderException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.SecretKey

@RunWith(AndroidJUnit4::class)
class KeystorePairedServerStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var prefsName: String
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var dataJob: CompletableJob
    private var createdDeviceKey = false

    @Before
    fun setUp() {
        prefsName = "test_paired_server_${UUID.randomUUID()}"
        openDataStore()
    }

    private fun openDataStore() {
        dataJob = SupervisorJob()
        dataStore =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + dataJob),
                produceFile = { context.preferencesDataStoreFile(prefsName) },
            )
    }

    private suspend fun reopen() {
        dataJob.cancelAndJoin()
        openDataStore()
    }

    @After
    fun tearDown() {
        runBlocking { dataJob.cancelAndJoin() }
        // Drop the shared Keystore wrap key and the test DataStore so each run is hermetic.
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(WRAP_KEY_ALIAS)) {
            keyStore.deleteEntry(WRAP_KEY_ALIAS)
        }
        if (createdDeviceKey) keyStore.deleteEntry("pyrycode.device_static_wrap")
        context.preferencesDataStoreFile(prefsName).delete()
    }

    private fun newStore() = KeystorePairedServerStore(dataStore)

    // Adversarial sample: pubkey carries base64-std '+'/'/'/'=', relayUrl keeps its /v1/client
    // path plus a query param, token is a long hex string. Proves byte-faithful round-trip.
    private fun sampleRecord() =
        PairedServer(
            serverId = "srv-7f3a9c2e",
            token = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90",
            relayUrl = "wss://relay.example.com:8443/v1/client?region=eu-west-1",
            serverStaticPublicKey = "ab+/cd+/ef+/gh+/ij+/kl+/mn+/op+/qr+/st+/uv8=",
        )

    @Test
    fun recordString_redactsEveryCredentialField() {
        val sample = sampleRecord()
        for (value in listOf(sample.serverId, sample.token, sample.relayUrl, sample.serverStaticPublicKey)) {
            assertFalse(sample.toString().contains(value))
        }
    }

    @Test
    fun saveThenLoad_recoversAllFourFieldsByteAccurate() {
        runBlocking {
            val sample = sampleRecord()
            newStore().save(sample)
            val loaded = newStore().load()
            assertEquals(sample, loaded)
            // Assert each field individually so byte-faithfulness is explicit, especially the
            // adversarial base64 pubkey and the relayUrl path/query.
            assertEquals(sample.serverId, loaded?.serverId)
            assertEquals(sample.token, loaded?.token)
            assertEquals(sample.relayUrl, loaded?.relayUrl)
            assertEquals(sample.serverStaticPublicKey, loaded?.serverStaticPublicKey)
        }
    }

    @Test
    fun freshStoreOverSameDataStore_loadsPersistedRecord() {
        runBlocking {
            // Genuine process death is survived by construction: the DataStore file and the
            // AndroidKeyStore alias both outlive the process. A fresh store instance over the
            // same DataStore is the automatable proxy for an app restart.
            val sample = sampleRecord()
            newStore().save(sample)
            assertEquals(sample, newStore().load())
        }
    }

    @Test
    fun load_returnsNullWhenNoRecordSaved() {
        runBlocking {
            assertNull(newStore().load())
        }
    }

    @Test
    fun persistedBlob_isCiphertextNotPlaintext() {
        runBlocking {
            val sample = sampleRecord()
            newStore().save(sample)
            val stored = dataStore.data.first()[stringPreferencesKey(PREF_KEY)]
            val blob = Base64.decode(requireNotNull(stored), Base64.NO_WRAP)
            // The plaintext token must not survive into the at-rest blob as a UTF-8 substring,
            // and the blob carries IV(12) + GCM tag(16) overhead beyond the plaintext JSON.
            assertFalse(String(blob, Charsets.ISO_8859_1).contains(sample.token))
            assertTrue(blob.size > 28)
        }
    }

    @Test
    fun load_returnsNullWhenWrapKeyLost() {
        runBlocking {
            // The key divergence from #291: an undecryptable record resolves to null (→ re-pair),
            // never an exception, never a crash, never a regenerated record.
            newStore().save(sampleRecord())
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.deleteEntry(WRAP_KEY_ALIAS)
            assertNull(newStore().load())
        }
    }

    @Test
    fun load_returnsNullWhenBlobIsCorrupt() {
        runBlocking {
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey(PREF_KEY)] = "@@@@not-valid-base64@@@@"
            }
            assertNull(newStore().load())
        }
    }

    @Test
    fun save_selectsMostRecentRecord() {
        runBlocking {
            val store = newStore()
            val first = sampleRecord()
            val second = first.copy(serverId = "srv-other", token = "ffff0000ffff0000")
            store.save(first)
            store.save(second)
            assertEquals(second, newStore().load())
        }
    }

    @Test
    fun collection_reopensWithExactIdentityNamesAndLatestSelection() =
        runBlocking {
            val first = sampleRecord()
            val second = first.copy(serverId = first.serverId.uppercase(), token = "second-secret\n\"é")
            val store: PairedServerCollectionStore = newStore()
            store.save(first)
            store.save(second)
            assertNull(store.loadById(first.serverId)?.displayName)
            store.setDisplayName(first.serverId, "shared local name")
            store.setDisplayName(second.serverId, "shared local name")
            reopen()
            assertEquals(listOf(first, second), newStore().list().map { it.record })
            assertEquals(PairedServerEntry(first, "shared local name"), newStore().loadById(first.serverId))
            assertEquals(second, newStore().load())
            val replacement = first.copy(token = "replacement-token", serverStaticPublicKey = "+/new=", relayUrl = "wss://other")
            newStore().save(replacement)
            reopen()
            assertEquals(listOf(second, replacement), newStore().list().map { it.record })
            assertEquals("shared local name", newStore().loadById(first.serverId)?.displayName)
            assertEquals(replacement, newStore().load())
            assertNull(newStore().loadById("unknown"))
        }

    @Test
    fun namesAndRemoval_preserveCredentialsOrderAndDeviceKeys() =
        runBlocking {
            val first = sampleRecord()
            val second = first.copy(serverId = "../host-two")
            val keys = KeystoreDeviceStaticKeyStore(dataStore).loadOrCreate(first.serverId)
            createdDeviceKey = true
            val unrelated = dataStore.data.first().asMap()
            newStore().save(first)
            newStore().save(second)
            newStore().setDisplayName(first.serverId, "name")
            newStore().setDisplayName(first.serverId, null)
            newStore().setDisplayName(second.serverId, "second name")
            val beforeNoOps = blob()
            newStore().remove("unknown")
            newStore().setDisplayName("unknown", "ignored")
            assertEquals(beforeNoOps, blob())
            reopen()
            assertEquals(PairedServerEntry(first), newStore().loadById(first.serverId))
            assertEquals(second, newStore().load())
            newStore().remove(second.serverId)
            reopen()
            assertEquals(first, newStore().load())
            assertNull(newStore().loadById(second.serverId))
            newStore().remove(first.serverId)
            reopen()
            assertTrue(newStore().list().isEmpty())
            assertNull(newStore().load())
            newStore().save(second)
            assertNull(newStore().loadById(second.serverId)?.displayName)
            assertEquals(
                unrelated,
                dataStore.data
                    .first()
                    .asMap()
                    .filterKeys { it.name != PREF_KEY },
            )
            val recovered = KeystoreDeviceStaticKeyStore(dataStore).loadOrCreate(first.serverId)
            assertArrayEquals(keys.privateKey, recovered.privateKey)
            assertArrayEquals(keys.publicKey, recovered.publicKey)
        }

    @Test
    fun overlappingSaves_acrossInstancesRetainBothWrites() =
        runBlocking {
            val entered = AtomicInteger()
            val bothEntered = CompletableDeferred<Unit>()
            val gated =
                object : DataStore<Preferences> by dataStore {
                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                        if (entered.incrementAndGet() == 2) bothEntered.complete(Unit)
                        bothEntered.await()
                        return dataStore.updateData(transform)
                    }
                }
            val records = listOf(sampleRecord(), sampleRecord().copy(serverId = "second"))
            withTimeout(10_000) {
                coroutineScope {
                    records.map { record -> async { KeystorePairedServerStore(gated).save(record) } }.awaitAll()
                }
            }
            reopen()
            assertEquals(records.toSet(), newStore().list().map { it.record }.toSet())
        }

    @Test
    fun legacy_readsWithoutWritingAndMigratesOnNextMutation() =
        runBlocking {
            val first = sampleRecord()
            seedLegacy(first)
            val legacyBlob = blob()
            repeat(2) {
                reopen()
                assertEquals(listOf(PairedServerEntry(first)), newStore().list())
                assertEquals(first, newStore().load())
                assertEquals(legacyBlob, blob())
            }
            val second = first.copy(serverId = "second")
            newStore().save(second)
            reopen()
            assertEquals(listOf(first, second), newStore().list().map { it.record })
            assertFalse(legacyBlob == blob())
            newStore().remove(first.serverId)
            repeat(2) {
                reopen()
                assertEquals(listOf(PairedServerEntry(second)), newStore().list())
            }
        }

    @Test
    fun legacy_removalAndRenameCanBeFirstMutation() =
        runBlocking {
            val first = sampleRecord()
            seedLegacy(first)
            newStore().setDisplayName(first.serverId, "local")
            reopen()
            assertEquals(PairedServerEntry(first, "local"), newStore().list().single())
            seedLegacy(first)
            newStore().remove(first.serverId)
            repeat(2) {
                reopen()
                assertNull(newStore().load())
                assertTrue(newStore().list().isEmpty())
            }
        }

    @Test
    fun failedMutations_preserveBlobAndSelectionIncludingLegacyMigration() =
        runBlocking {
            val first = sampleRecord()
            val second = first.copy(serverId = "second")
            for (legacy in listOf(false, true)) {
                if (legacy) {
                    seedLegacy(first)
                } else {
                    newStore().save(first)
                    newStore().save(second)
                }
                val original = blob()
                val entries = newStore().list()
                val selection = newStore().load()
                val faults = FaultStore(dataStore)
                val store = KeystorePairedServerStore(faults)
                for (fault in listOf(IOException(first.token), ProviderException(first.token))) {
                    faults.failure = fault
                    for (mutate in listOf<suspend () -> Unit>(
                        { store.save(first.copy(token = "changed")) },
                        { store.setDisplayName(first.serverId, "changed") },
                        { store.remove(selection!!.serverId) },
                    )) {
                        val error = mutationFailure(mutate)
                        assertFalse(error.stackTraceToString().contains(first.token))
                        assertNull(error.cause)
                        assertEquals(original, blob())
                        assertEquals(selection, newStore().load())
                    }
                }
                reopen()
                assertEquals(entries, newStore().list())
                assertEquals(selection, newStore().load())
                newStore().save(second)
                assertEquals(second, newStore().load())
            }
        }

    @Test
    fun readFailureIsGraceful_butMutationsFailAndCancellationPropagates() =
        runBlocking {
            newStore().save(sampleRecord())
            val original = blob()
            val readError = IOException(sampleRecord().token)
            val faults =
                object : DataStore<Preferences> {
                    var error: Exception = readError
                    override val data: Flow<Preferences> get() = flow { throw error }

                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = throw error
                }
            val store = KeystorePairedServerStore(faults)
            assertTrue(store.list().isEmpty())
            assertNull(store.load())
            mutationFailure { store.save(sampleRecord()) }
            faults.error = CancellationException("cancelled")
            for (operation in listOf<suspend () -> Unit>({ store.list() }, { store.save(sampleRecord()) })) {
                try {
                    operation()
                    error("Cancellation must propagate")
                } catch (error: CancellationException) {
                    assertSame(faults.error, error)
                }
            }
            assertEquals(original, blob())
        }

    @Test
    fun encryptedCollection_redactsStringsLogsAndParserFailures() =
        runBlocking {
            val sample = sampleRecord()
            val name = "private-local-name"
            val messages = mutableListOf<String>()
            val originalSink = RelayLog.sink
            val originalEnabled = RelayLog.enabled
            RelayLog.enabled = true
            RelayLog.sink = { _, _, message -> messages.add(message) }
            try {
                newStore().save(sample)
                newStore().setDisplayName(sample.serverId, name)
                val bytes = Base64.decode(blob(), Base64.NO_WRAP).toString(Charsets.ISO_8859_1)
                val printable = newStore().list().toString() + sample.toString()
                for (value in listOf(sample.serverId, sample.token, sample.relayUrl, sample.serverStaticPublicKey, name)) {
                    assertFalse(bytes.contains(value))
                    assertFalse(printable.contains(value))
                }
                writePlaintext("{\"token\":\"${sample.token}\",\"entries\":false,\"version\":1}")
                val before = blob()
                assertTrue(newStore().list().isEmpty())
                val error = mutationFailure { newStore().save(sample) }
                assertFalse(error.stackTraceToString().contains(sample.token))
                assertEquals(before, blob())
                assertTrue(messages.any { it.contains("status=ok") })
                assertTrue(messages.any { it.contains("status=failed") })
                for (value in listOf(sample.serverId, sample.token, sample.relayUrl, sample.serverStaticPublicKey, name)) {
                    assertFalse(messages.joinToString().contains(value))
                }
            } finally {
                RelayLog.sink = originalSink
                RelayLog.enabled = originalEnabled
            }
        }

    @Test
    fun missingKeyAndInvalidEnvelopes_readGracefullyWithoutMutation() =
        runBlocking {
            val sample = sampleRecord()
            newStore().save(sample)
            val record = Json.encodeToString(sample)
            for (plaintext in listOf(
                "{\"version\":2,\"entries\":[]}",
                "{\"version\":1,\"entries\":[{\"record\":$record},{\"record\":$record}]}",
            )) {
                writePlaintext(plaintext)
                assertTrue(newStore().list().isEmpty())
            }
            newStoreFromValidSeed(sample)
            val valid = blob()
            val corrupted = Base64.decode(valid, Base64.NO_WRAP).also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            dataStore.edit { it[stringPreferencesKey(PREF_KEY)] = Base64.encodeToString(corrupted, Base64.NO_WRAP) }
            assertNull(newStore().load())
            dataStore.edit { it[stringPreferencesKey(PREF_KEY)] = valid }
            KeyStore.getInstance(ANDROID_KEYSTORE).apply {
                load(null)
                deleteEntry(WRAP_KEY_ALIAS)
            }
            assertTrue(newStore().list().isEmpty())
            mutationFailure { newStore().remove(sample.serverId) }
            assertEquals(valid, blob())
        }

    private suspend fun blob(): String = requireNotNull(dataStore.data.first()[stringPreferencesKey(PREF_KEY)])

    private suspend fun newStoreFromValidSeed(record: PairedServer) {
        dataStore.edit { it.remove(stringPreferencesKey(PREF_KEY)) }
        newStore().save(record)
    }

    private suspend fun seedLegacy(record: PairedServer) {
        newStoreFromValidSeed(record)
        writePlaintext(Json.encodeToString(record))
    }

    // Independent legacy writer: same AES envelope as the pre-collection store, no collection codec.
    private suspend fun writePlaintext(plaintext: String) {
        val key = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.getKey(WRAP_KEY_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        dataStore.edit { it[stringPreferencesKey(PREF_KEY)] = Base64.encodeToString(encrypted, Base64.NO_WRAP) }
    }

    private suspend fun mutationFailure(operation: suspend () -> Unit): PairedServerStoreException {
        try {
            operation()
        } catch (error: PairedServerStoreException) {
            return error
        }
        error("Mutation must report failure")
    }

    private class FaultStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> by delegate {
        var failure: Exception? = null

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            delegate.updateData { before ->
                val after = transform(before)
                failure?.let { throw it }
                after
            }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val WRAP_KEY_ALIAS = "pyrycode.paired_server_wrap"
        const val PREF_KEY = "pyrycode.paired_server"
    }
}
