package de.pyryco.mobile.ui.onboarding

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingPrefillTest {
    private fun launch(
        code: String? = CODE,
        name: String? = null,
    ) = Intent().apply {
        code?.let { putExtra(PairingPrefill.EXTRA_CODE, it) }
        name?.let { putExtra(PairingPrefill.EXTRA_NAME, it) }
    }

    @Test
    fun aTestBuildReadsTheCodeAndName() {
        val prefill = PairingPrefill.from(launch(code = " $CODE\n", name = " relchk "), debug = true)!!

        assertEquals(CODE, prefill.code)
        assertEquals("relchk", prefill.name)
    }

    @Test
    fun aReleaseBuildIgnoresTheExtras() {
        assertNull(PairingPrefill.from(launch(name = "relchk"), debug = false))
    }

    @Test
    fun aLaunchWithoutAUsableCodeOpensNothing() {
        assertNull(PairingPrefill.from(null, debug = true))
        assertNull(PairingPrefill.from(launch(code = null, name = "relchk"), debug = true))
        assertNull(PairingPrefill.from(launch(code = "  "), debug = true))
        assertNull(PairingPrefill.from(launch(code = "a".repeat(4097)), debug = true))
    }

    @Test
    fun theNameIsOptional() {
        assertEquals("", PairingPrefill.from(launch(), debug = true)!!.name)
    }

    @Test
    fun theCodeNeverReachesItsStringForm() {
        assertFalse(PairingPrefill.from(launch(), debug = true).toString().contains(CODE))
    }

    private companion object {
        const val CODE = "eyJzZXJ2ZXIiOiJob3N0LWEifQ"
    }
}
