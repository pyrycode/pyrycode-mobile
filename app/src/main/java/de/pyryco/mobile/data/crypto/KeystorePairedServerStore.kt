package de.pyryco.mobile.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [PairedServerStore] backed by the Android Keystore (mechanism (a): wrap-at-rest, ADR-0006).
 *
 * The [PairedServer] record is JSON-serialized, UTF-8-encoded, then AES-256-GCM-encrypted under a
 * hardware-backed, non-exportable, uid-scoped Keystore wrap key — with the per-encrypt
 * Keystore-generated IV prepended — and stored as `base64(iv ‖ ciphertext)` under a single key in
 * the app-private [DataStore]. This is a second consumer of #291's proven mechanism, with three
 * deliberate divergences: a JSON document (not raw bytes) as plaintext, a graceful `null`-returning
 * [load] (not a throw), and a dedicated wrap-key alias.
 *
 * Takes only [DataStore]; AndroidKeyStore operations go through the keystore daemon and need no
 * `Context`, keeping the data layer portable behind the interface.
 */
class KeystorePairedServerStore(
    private val dataStore: DataStore<Preferences>,
) : PairedServerStore {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): PairedServer? =
        withContext(Dispatchers.IO) {
            val stored = dataStore.data.first()[PREF_KEY] ?: return@withContext null
            // Graceful: any decode / missing-key / decrypt / parse failure resolves to null
            // (→ re-pair). The catch set is specific so CancellationException still propagates.
            try {
                val blob = decode(stored)
                if (blob.size <= GCM_IV_LENGTH) return@withContext null
                val wrapKey = getWrapKey() ?: return@withContext null
                val plaintext = unwrap(blob, wrapKey)
                json.decodeFromString<PairedServer>(plaintext.toString(Charsets.UTF_8))
            } catch (e: GeneralSecurityException) {
                null
            } catch (e: IOException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }

    override suspend fun save(record: PairedServer) {
        withContext(Dispatchers.IO) {
            try {
                val plaintext = json.encodeToString(record).toByteArray(Charsets.UTF_8)
                val wrapped = wrap(plaintext)
                dataStore.edit { prefs -> prefs[PREF_KEY] = encode(wrapped) }
            } catch (e: GeneralSecurityException) {
                throw PairedServerStoreException("paired server save failed: ${e.javaClass.simpleName}", e)
            } catch (e: IOException) {
                throw PairedServerStoreException("paired server save failed: ${e.javaClass.simpleName}", e)
            }
        }
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrapKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun unwrap(
        blob: ByteArray,
        wrapKey: SecretKey,
    ): ByteArray {
        val iv = blob.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = blob.copyOfRange(GCM_IV_LENGTH, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrapKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    /** Non-creating lookup so [load] never mints a key: a missing alias → blob is undecryptable → null. */
    private fun getWrapKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(WRAP_KEY_ALIAS, null) as? SecretKey
    }

    private fun getOrCreateWrapKey(): SecretKey {
        getWrapKey()?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec
                .Builder(
                    WRAP_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(WRAP_KEY_SIZE_BITS)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val WRAP_KEY_ALIAS = "pyrycode.paired_server_wrap"

        // One record, one fixed key — server-id lives inside the encrypted blob, never as a
        // pref-key component, so #291's attacker-influenced key-collision surface does not exist.
        val PREF_KEY = stringPreferencesKey("pyrycode.paired_server")

        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
        const val WRAP_KEY_SIZE_BITS = 256
    }
}
