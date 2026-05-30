package de.pyryco.mobile.data.crypto

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
class KeystoreDeviceStaticKeyStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var prefsName: String
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        prefsName = "test_device_static_${UUID.randomUUID()}"
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

    private fun newStore() = KeystoreDeviceStaticKeyStore(dataStore)

    @Test
    fun generatePersistReload_returnsByteIdenticalKeypair() {
        runBlocking {
            // Genuine process death is survived by construction: the DataStore file and the
            // AndroidKeyStore alias both outlive the process. A fresh store instance over the
            // same DataStore is the automatable proxy for an app restart.
            val kp1 = newStore().loadOrCreate("srv")
            val kp2 = newStore().loadOrCreate("srv")
            assertArrayEquals(kp1.publicKey, kp2.publicKey)
            assertArrayEquals(kp1.privateKey, kp2.privateKey)
            assertEquals(32, kp1.publicKey.size)
            assertEquals(32, kp1.privateKey.size)
        }
    }

    @Test
    fun persistedPrivate_isNotRawKeyBytes() {
        runBlocking {
            val kp = newStore().loadOrCreate("srv")
            val stored =
                dataStore.data.first()[stringPreferencesKey("pyrycode.device_static.srv")]
            val blob = Base64.decode(requireNotNull(stored), Base64.NO_WRAP)
            // iv(12) + ciphertext(32) + GCM tag(16) = 60 bytes; never the plaintext scalar.
            assertFalse(blob.contentEquals(kp.privateKey))
            assertTrue(blob.size > 32)
        }
    }

    @Test
    fun publicKey_matchesGeneratedMirror() {
        runBlocking {
            val store = newStore()
            val kp = store.loadOrCreate("srv")
            // Reads only the plaintext DataStore mirror; no Keystore round-trip (see impl).
            assertArrayEquals(kp.publicKey, store.publicKey("srv"))
        }
    }

    @Test
    fun publicKey_isNullBeforeGenerate() {
        runBlocking {
            assertNull(newStore().publicKey("never-generated"))
        }
    }

    @Test
    fun differentServerIds_yieldDifferentKeypairs() {
        runBlocking {
            val store = newStore()
            val a = store.loadOrCreate("A")
            val b = store.loadOrCreate("B")
            assertFalse(a.publicKey.contentEquals(b.publicKey))
            assertFalse(a.privateKey.contentEquals(b.privateKey))
        }
    }

    @Test
    fun keystoreLoss_throwsAndDoesNotRegenerate() {
        runBlocking {
            val store = newStore()
            store.loadOrCreate("srv")
            // Removing the wrap key makes the persisted blob undecryptable. The store must
            // surface DeviceStaticKeyException rather than silently minting a new identity.
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.deleteEntry(WRAP_KEY_ALIAS)
            var threw = false
            try {
                store.loadOrCreate("srv")
            } catch (expected: DeviceStaticKeyException) {
                threw = true
            }
            assertTrue("expected DeviceStaticKeyException after wrap-key loss", threw)
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val WRAP_KEY_ALIAS = "pyrycode.device_static_wrap"
    }
}
