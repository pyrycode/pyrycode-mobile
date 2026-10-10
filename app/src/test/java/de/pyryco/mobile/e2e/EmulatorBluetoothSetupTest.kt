package de.pyryco.mobile.e2e

import org.junit.Assert.assertEquals
import org.junit.Test

class EmulatorBluetoothSetupTest {
    @Test fun persistedOffDoesNotSkipCancellingPendingRestarts() {
        val commands = mutableListOf<String>()
        disableEmulatorBluetooth { command ->
            commands += command
            "0"
        }
        assertEquals(1, commands.count { it == "cmd bluetooth_manager disable" })
    }

    @Test fun persistedOnStillSendsOnlyOneDisableCommand() {
        val commands = mutableListOf<String>()
        disableEmulatorBluetooth { command ->
            commands += command
            "1"
        }
        assertEquals(1, commands.count { it == "cmd bluetooth_manager disable" })
    }
}
