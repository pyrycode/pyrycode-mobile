package de.pyryco.mobile.data.cache

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite upgrade/reopen coverage, discovered by the configured device UI gate. */
@RunWith(AndroidJUnit4::class)
class LegacyDiscardMigrationDeviceTest {
    @Test fun preservationInvariant_productionUpgradeAndFreshReopen() {
        LegacyDiscardMigrationTest().preservationInvariant_productionUpgradeAndFreshReopen()
    }
}
