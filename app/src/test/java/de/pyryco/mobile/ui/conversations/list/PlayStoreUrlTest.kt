package de.pyryco.mobile.ui.conversations.list

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayStoreUrlTest {
    @Test
    fun updateControlTarget_isTheReleaseListing() {
        assertEquals("https://play.google.com/store/apps/details?id=de.pyryco.mobile", PLAY_STORE_URL)
    }
}
