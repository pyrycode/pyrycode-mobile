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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class KeystorePairedServerStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var prefsName: String
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        prefsName = "test_paired_server_${UUID.randomUUID()}"
        dataStore =
            PreferenceDataStoreFactory.create(
                produceFile = { context.preferencesDataStoreFile(prefsName) },
            )
    }

    @After
    fun tearDown() {
        // Drop the shared Keystore wrap key and the test DataStore so each run is hermetic.
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(WRAP_KEY_ALIAS)) {
            keyStore.deleteEntry(WRAP_KEY_ALIAS)
        }
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
    fun save_overwritesPreviousRecord() {
        runBlocking {
            val store = newStore()
            val first = sampleRecord()
            val second = first.copy(serverId = "srv-other", token = "ffff0000ffff0000")
            store.save(first)
            store.save(second)
            assertEquals(second, newStore().load())
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val WRAP_KEY_ALIAS = "pyrycode.paired_server_wrap"
        const val PREF_KEY = "pyrycode.paired_server"
    }
}
