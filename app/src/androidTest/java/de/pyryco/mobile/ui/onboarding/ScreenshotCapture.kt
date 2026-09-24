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
    var image: ImageBitmap? = null
    var timeout: ComposeTimeoutException? = null
    for (attempt in 1..CAPTURE_ATTEMPTS) {
        if (attempt > 1) waitForIdle()
        try {
            image = node().captureToImage()
            break
        } catch (e: ComposeTimeoutException) {
            timeout = e
        }
    }
    if (image == null) {
        Log.w("Screenshot", "skipped $name after $CAPTURE_ATTEMPTS attempts: ${timeout?.message}")
        return
    }
    File(dir).mkdirs()
    File(dir, "$name.png").outputStream().use {
        image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
}
