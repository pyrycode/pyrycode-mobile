package de.pyryco.mobile.e2e

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class EmulatorBluetoothSetupTest {
    @Test fun waitsForActualAdapterOffAfterDisable() =
        runTest {
            val commands = mutableListOf<String>()
            var states = 0
            disableEmulatorBluetooth { command ->
                commands += command
                if (command == "dumpsys bluetooth_manager") {
                    states++
                    if (states == 1) "state: BLE_TURNING_ON\nmEnable:false" else "state: OFF\nmEnable:false"
                } else {
                    ""
                }
            }
            assertEquals(listOf("cmd bluetooth_manager disable", "dumpsys bluetooth_manager", "dumpsys bluetooth_manager"), commands)
        }

    @Test fun persistedOffDoesNotSkipCancellingPendingRestarts() =
        runTest {
            val commands = mutableListOf<String>()
            disableEmulatorBluetooth { command ->
                commands += command
                if (command == "dumpsys bluetooth_manager") "state: OFF\nmEnable:false" else "0"
            }
            assertEquals(1, commands.count { it == "cmd bluetooth_manager disable" })
        }

    @Test fun persistedOnStillSendsOnlyOneDisableCommand() =
        runTest {
            val commands = mutableListOf<String>()
            disableEmulatorBluetooth { command ->
                commands += command
                if (command == "dumpsys bluetooth_manager") "state: OFF\nmEnable:false" else "1"
            }
            assertEquals(1, commands.count { it == "cmd bluetooth_manager disable" })
        }

    @Test(expected = TimeoutCancellationException::class)
    fun failedShutdownRejectsTheDeviceEnvironment() =
        runTest {
            disableEmulatorBluetooth { "state: BLE_TURNING_ON\nmEnable:false" }
        }

    @Test(expected = TimeoutCancellationException::class)
    fun offStateWithPendingEnableDoesNotDeclareReadiness() =
        runTest {
            disableEmulatorBluetooth { "state: OFF\nmEnable:true" }
        }
}
