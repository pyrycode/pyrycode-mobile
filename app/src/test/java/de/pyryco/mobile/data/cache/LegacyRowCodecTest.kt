package de.pyryco.mobile.data.cache

import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyRowCodecTest {
    @Test
    fun canonicalRowSeamRoundTripsAndKeepsProofInputs() {
        val row = ThreadItem.MessageItem(Message("identity", "session", Role.User, "saved text", Instant.fromEpochSeconds(1), false))
        assertEquals(listOf(row), decodeLegacyRows(listOf(encodeLegacyRow(row))))
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        assertEquals(cachedThreadRowProof(row), digest.digest(encodeLegacyRow(row).toByteArray()).toHexString())
    }
}
