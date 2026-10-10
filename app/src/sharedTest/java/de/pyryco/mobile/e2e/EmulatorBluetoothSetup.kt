package de.pyryco.mobile.e2e

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

internal suspend fun disableEmulatorBluetooth(shell: (String) -> String) {
    // Recovery can restart without changing bluetooth_on; disable also cancels queued restarts.
    shell("cmd bluetooth_manager disable")

    suspend fun awaitOff() {
        while (true) {
            val state = shell("dumpsys bluetooth_manager").lineSequence().map(String::trim).toSet()
            if ("state: OFF" in state && "mEnable:false" in state) break
            delay(50)
        }
    }
    withTimeout(10_000) {
        awaitOff()
        // API 33 recovery sets OFF before queuing a restart. Cancel after that transition too.
        shell("cmd bluetooth_manager disable")
        awaitOff()
    }
}
