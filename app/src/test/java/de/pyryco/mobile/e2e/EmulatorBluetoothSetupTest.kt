package de.pyryco.mobile.e2e

import org.junit.Assert.assertEquals
import org.junit.Test

class EmulatorBluetoothSetupTest {
    @Test fun waitsForActualAdapterOffAfterDisable() {
        val commands = mutableListOf<String>()
        disableEmulatorBluetooth { command ->
            commands += command
            "wait-for-state:STATE_OFF: Success"
        }
        assertEquals(listOf("cmd bluetooth_manager disable", "cmd bluetooth_manager wait-for-state:STATE_OFF"), commands)
    }

    @Test fun persistedOffDoesNotSkipCancellingPendingRestarts() {
        val commands = mutableListOf<String>()
        disableEmulatorBluetooth { command ->
            commands += command
            if (command.contains("wait-for-state")) "wait-for-state:STATE_OFF: Success" else "0"
        }
        assertEquals(1, commands.count { it == "cmd bluetooth_manager disable" })
    }

    @Test fun persistedOnStillSendsOnlyOneDisableCommand() {
        val commands = mutableListOf<String>()
        disableEmulatorBluetooth { command ->
            commands += command
            if (command.contains("wait-for-state")) "wait-for-state:STATE_OFF: Success" else "1"
        }
        assertEquals(1, commands.count { it == "cmd bluetooth_manager disable" })
    }

    @Test(expected = IllegalStateException::class)
    fun failedShutdownRejectsTheDeviceEnvironment() {
        disableEmulatorBluetooth { "wait-for-state:STATE_OFF: Failed with status=-1" }
    }
}
