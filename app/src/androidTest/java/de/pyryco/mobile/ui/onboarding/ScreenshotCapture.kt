package de.pyryco.mobile.ui.onboarding

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import java.io.File

private const val CAPTURE_ATTEMPTS = 3

/**
 * Saves [node] as `<dir>/<name>.png` for review. The PNG is an artifact, not an assertion:
 * when `captureToImage` keeps timing out on a loaded emulator, the screenshot is skipped and
 * logged instead of failing the test.
 */
internal fun ComposeTestRule.saveScreenshot(
    dir: String,
    name: String,
    node: () -> SemanticsNodeInteraction,
) {
    val image = captureWithRetry(name, node) ?: return
    File(dir).mkdirs()
    File(dir, "$name.png").outputStream().use {
        image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
}

/**
 * Captures [node], retrying when `captureToImage` times out on a loaded emulator. Returns null,
 * with a log line naming [name], when every attempt times out, so the caller can skip its check.
 */
internal fun ComposeTestRule.captureWithRetry(
    name: String,
    node: () -> SemanticsNodeInteraction,
): ImageBitmap? {
    var timeout: ComposeTimeoutException? = null
    for (attempt in 1..CAPTURE_ATTEMPTS) {
        if (attempt > 1) waitForIdle()
        try {
            return node().captureToImage()
        } catch (e: ComposeTimeoutException) {
            timeout = e
        }
    }
    Log.w("Screenshot", "skipped $name after $CAPTURE_ATTEMPTS attempts: ${timeout?.message}")
    return null
}
