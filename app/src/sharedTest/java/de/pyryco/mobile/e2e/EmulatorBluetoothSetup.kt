package de.pyryco.mobile.e2e

internal fun disableEmulatorBluetooth(shell: (String) -> String) {
    // Recovery can restart without changing bluetooth_on; disable also cancels queued restarts.
    shell("cmd bluetooth_manager disable")
    val result = shell("cmd bluetooth_manager wait-for-state:STATE_OFF")
    check(result.lineSequence().any { it.trim() == "wait-for-state:STATE_OFF: Success" }) {
        "Emulator Bluetooth did not reach OFF before test execution"
    }
}
