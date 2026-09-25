package de.pyryco.mobile

import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Grants `POST_NOTIFICATIONS` as if the operator had already answered the channel list's one-time
 * prompt (#685). Without it a fresh device shows Android's permission dialog over `MainActivity` on
 * the first channel-list composition, and the test finds no Compose root. Call it before the channel
 * list composes: from the class's `init` block when a rule launches the activity (rules start after
 * construction), else from `@Before`.
 */
fun grantNotificationPermission() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.uiAutomation.grantRuntimePermission(
        instrumentation.targetContext.packageName,
        Manifest.permission.POST_NOTIFICATIONS,
    )
}
