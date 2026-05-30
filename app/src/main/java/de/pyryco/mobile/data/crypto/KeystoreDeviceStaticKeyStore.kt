package de.pyryco.mobile.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.southernstorm.noise.protocol.Noise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [DeviceStaticKeyStore] backed by the Android Keystore (mechanism (a): wrap-at-rest).
 *
 * The raw 32-byte X25519 private scalar is AES-256-GCM-encrypted under a hardware-backed,
 * non-exportable, uid-scoped Keystore wrap key, then stored — with the per-encrypt
 * Keystore-generated IV prepended — in the app-private [DataStore]. The 32-byte public key is
 * mirrored in plaintext (non-secret) for fast reads. The scalar materialises in process memory
 * only transiently, while a keypair is loaded; that is inherent to noise-java's software X25519.
 *
 * Takes only [DataStore]; AndroidKeyStore operations go through the keystore daemon and need
 * no `Context`, keeping the data layer portable.
 */
class KeystoreDeviceStaticKeyStore(
    private val dataStore: DataStore<Preferences>,
) : DeviceStaticKeyStore {
    private val mutex = Mutex()

    override suspend fun loadOrCreate(serverId: String): DeviceStaticKeyPair =
        withContext(Dispatchers.IO) {
            // The mutex serialises check-then-generate so a keypair is never silently regenerated.
            mutex.withLock {
                runCatchingKeystore {
                    val prefs = dataStore.data.first()
                    val privateBlob = prefs[privateKey(serverId)]
                    val publicMirror = prefs[publicKey(serverId)]
                    when {
                        privateBlob != null && publicMirror != null ->
                            DeviceStaticKeyPair(decodePublic(publicMirror), unwrap(decode(privateBlob)))

                        privateBlob == null && publicMirror == null ->
                            generate(serverId)

                        else ->
                            throw DeviceStaticKeyException("corrupt device static key store")
                    }
                }
            }
        }

    override suspend fun publicKey(serverId: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val mirror = dataStore.data.first()[publicKey(serverId)] ?: return@withContext null
            runCatchingKeystore { decodePublic(mirror) }
        }

    private suspend fun generate(serverId: String): DeviceStaticKeyPair {
        val scalar = ByteArray(KEY_LENGTH).also { SecureRandom().nextBytes(it) }
        val derivedPublic = derivePublicKey(scalar)
        val wrapped = wrap(scalar)
        dataStore.edit { prefs ->
            prefs[privateKey(serverId)] = encode(wrapped)
            prefs[publicKey(serverId)] = encode(derivedPublic)
        }
        return DeviceStaticKeyPair(derivedPublic, scalar)
    }

    /** Derive the X25519 public key through noise-java so it matches what #275's DH produces. */
    private fun derivePublicKey(scalar: ByteArray): ByteArray {
        val dh = Noise.createDH(DH_NAME)
        try {
            dh.setPrivateKey(scalar, 0)
            val derived = ByteArray(KEY_LENGTH)
            dh.getPublicKey(derived, 0)
            return derived
        } finally {
            dh.destroy()
        }
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrapKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        if (blob.size <= GCM_IV_LENGTH) {
            throw DeviceStaticKeyException("wrapped private key blob is too short")
        }
        val iv = blob.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = blob.copyOfRange(GCM_IV_LENGTH, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrapKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val scalar = cipher.doFinal(ciphertext)
        if (scalar.size != KEY_LENGTH) {
            throw DeviceStaticKeyException("unwrapped private key has unexpected length")
        }
        return scalar
    }

    private fun getOrCreateWrapKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(WRAP_KEY_ALIAS, null) as? SecretKey)?.let { return it }
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

    private fun decodePublic(encoded: String): ByteArray {
        val decoded = decode(encoded)
        if (decoded.size != KEY_LENGTH) {
            throw DeviceStaticKeyException("stored public key has unexpected length")
        }
        return decoded
    }

    private fun privateKey(serverId: String) = stringPreferencesKey(PRIVATE_PREFIX + serverId)

    private fun publicKey(serverId: String) = stringPreferencesKey(PUBLIC_PREFIX + serverId)

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    private inline fun <T> runCatchingKeystore(block: () -> T): T =
        try {
            block()
        } catch (e: DeviceStaticKeyException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw DeviceStaticKeyException("device static key operation failed: ${e.javaClass.simpleName}", e)
        } catch (e: IOException) {
            throw DeviceStaticKeyException("device static key operation failed: ${e.javaClass.simpleName}", e)
        } catch (e: IllegalArgumentException) {
            throw DeviceStaticKeyException("device static key operation failed: ${e.javaClass.simpleName}", e)
        }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val WRAP_KEY_ALIAS = "pyrycode.device_static_wrap"

        // The two key namespaces diverge in the character right after "pyrycode.device_static"
        // ('.' vs '_'), so no attacker-influenced server-id can make a private-blob key collide
        // with another server's public-mirror key. Do not switch the public mirror to a `.pub`
        // suffix appended after the server-id — that would reintroduce the collision.
        const val PRIVATE_PREFIX = "pyrycode.device_static."
        const val PUBLIC_PREFIX = "pyrycode.device_static_pub."

        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DH_NAME = "25519"
        const val KEY_LENGTH = 32
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
        const val WRAP_KEY_SIZE_BITS = 256
    }
}
