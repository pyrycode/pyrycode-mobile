package de.pyryco.mobile.e2e

internal fun disableEmulatorBluetooth(shell: (String) -> String) {
    // Recovery can restart without changing bluetooth_on; disable also cancels queued restarts.
    shell("cmd bluetooth_manager disable")
}
