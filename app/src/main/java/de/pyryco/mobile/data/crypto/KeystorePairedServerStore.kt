package de.pyryco.mobile.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import de.pyryco.mobile.data.network.RelayLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [PairedServerStore] backed by the Android Keystore (mechanism (a): wrap-at-rest, ADR-0006).
 *
 * The paired-server collection is JSON-serialized, UTF-8-encoded, then AES-256-GCM-encrypted under a
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PairedServerCollectionStore {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): PairedServer? = list().lastOrNull()?.record

    override suspend fun loadById(serverId: String): PairedServerEntry? = list().find { it.record.serverId == serverId }

    override suspend fun list(): List<PairedServerEntry> = readSnapshot().getOrDefault(emptyList())

    override suspend fun readSnapshot(): Result<List<PairedServerEntry>> =
        withContext(ioDispatcher) {
            try {
                Result.success(decodeEntries(dataStore.data.first()[PREF_KEY]))
            } catch (e: Exception) {
                val code = failureCode(e) ?: throw e
                RelayLog.d { "paired_store operation=read status=failed code=$code" }
                // Provider/parser causes may contain credentials; return only the classified code.
                Result.failure(PairedServerStoreException("paired server read failed: $code"))
            }
        }

    override suspend fun save(record: PairedServer) =
        mutate("save") { entries ->
            val name = entries.find { it.record.serverId == record.serverId }?.displayName
            entries.filterNot { it.record.serverId == record.serverId } + PairedServerEntry(record, name)
        }

    override suspend fun setDisplayName(
        serverId: String,
        displayName: String?,
    ) = mutate("rename") { entries ->
        if (entries.none { it.record.serverId == serverId }) {
            null
        } else {
            entries.map { if (it.record.serverId == serverId) it.copy(displayName = displayName) else it }
        }
    }

    override suspend fun remove(serverId: String) =
        mutate("remove") { entries ->
            if (entries.none { it.record.serverId == serverId }) {
                null
            } else {
                entries.filterNot { it.record.serverId == serverId }
            }
        }

    private suspend fun mutate(
        operation: String,
        transform: (List<PairedServerEntry>) -> List<PairedServerEntry>?,
    ) {
        withContext(ioDispatcher) {
            try {
                // The read must be inside edit: a separate read loses overlapping saves.
                dataStore.edit { prefs ->
                    val entries = transform(decodeEntries(prefs[PREF_KEY])) ?: return@edit
                    val plaintext = json.encodeToString(StoredPairings(1, entries)).toByteArray(Charsets.UTF_8)
                    prefs[PREF_KEY] = encode(wrap(plaintext))
                }
                RelayLog.d { "paired_store operation=$operation status=ok" }
            } catch (e: Exception) {
                val code = failureCode(e) ?: throw e
                RelayLog.d { "paired_store operation=$operation status=failed code=$code" }
                // Parser/provider causes can contain plaintext; never attach the original cause.
                throw PairedServerStoreException("paired server $operation failed: $code")
            }
        }
    }

    private fun decodeEntries(stored: String?): List<PairedServerEntry> {
        if (stored == null) return emptyList()
        val blob = decode(stored)
        require(blob.size > GCM_IV_LENGTH) { "invalid paired store envelope" }
        val wrapKey = getWrapKey() ?: throw GeneralSecurityException("paired store key missing")
        val payload = json.parseToJsonElement(unwrap(blob, wrapKey).toString(Charsets.UTF_8)).jsonObject
        if ("version" !in payload && "entries" !in payload) {
            return listOf(PairedServerEntry(json.decodeFromJsonElement<PairedServer>(payload)))
        }
        val collection = json.decodeFromJsonElement<StoredPairings>(payload)
        require(collection.version == 1) { "unsupported paired store version" }
        require(
            collection.entries
                .map { it.record.serverId }
                .distinct()
                .size == collection.entries.size,
        ) {
            "duplicate paired store identity"
        }
        return collection.entries
    }

    // Cancellation and unrelated programming errors are deliberately not classified.
    private fun failureCode(error: Exception): String? =
        when (error) {
            is GeneralSecurityException, is ProviderException, is SecurityException -> "keystore"
            is IOException -> "io"
            is IllegalArgumentException -> "invalid_data"
            else -> null
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

        // One collection, one fixed key — server-id lives inside the encrypted blob, never as a
        // pref-key component, so #291's attacker-influenced key-collision surface does not exist.
        val PREF_KEY = stringPreferencesKey("pyrycode.paired_server")

        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_BITS = 128
        const val WRAP_KEY_SIZE_BITS = 256
    }
}

@Serializable
private data class StoredPairings(
    val version: Int,
    val entries: List<PairedServerEntry>,
) {
    override fun toString(): String = "StoredPairings([REDACTED])"
}
