package de.pyryco.mobile

import android.content.Context
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The launcher draws the icon outside the app, so its contract is the adaptive icon's layers (Figma 703:5001). */
@RunWith(AndroidJUnit4::class)
class LauncherIconResourcesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun launcherIconHasEveryLayer() {
        assertLayers(R.mipmap.ic_launcher)
    }

    @Test fun roundLauncherIconHasEveryLayer() {
        assertLayers(R.mipmap.ic_launcher_round)
    }

    private fun assertLayers(id: Int) {
        val icon = context.getDrawable(id)
        assertTrue("expected an adaptive icon, got $icon", icon is AdaptiveIconDrawable)
        icon as AdaptiveIconDrawable
        assertNotNull(icon.background)
        assertNotNull(icon.foreground)
        assertNotNull("themed icons need the monochrome layer", icon.monochrome)
    }
}
