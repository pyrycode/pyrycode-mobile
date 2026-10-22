package de.pyryco.mobile.data.cache

import org.junit.Assert.assertTrue
import org.junit.Test

/** A removed storage family must not remain available through successful-empty compatibility APIs. */
class LegacyStorageRetirementTest {
    @Test
    fun retirementInvariant_noLegacyContentEntryPointsRemain() {
        val retired = setOf("readThread", "writeThread", "readHistoryPosition", "writeHistoryPosition")
        for (type in listOf(ConversationCache::class.java, FileConversationCache::class.java, MigratingConversationCache::class.java)) {
            val remaining = type.methods.map { it.name.substringBefore('-') }.filter { it in retired }
            assertTrue("${type.simpleName} exposes retired content APIs: $remaining", remaining.isEmpty())
        }
    }
}
