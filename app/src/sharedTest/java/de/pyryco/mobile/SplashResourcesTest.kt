package de.pyryco.mobile

import android.content.Context
import android.util.TypedValue
import androidx.compose.ui.graphics.toArgb
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.backgroundDark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The splash is system-drawn, so its contract is the resources the launcher theme names (Figma 701:5001). */
@RunWith(AndroidJUnit4::class)
class SplashResourcesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun splashBackgroundIsTheDarkSchemeBackground() {
        assertEquals(backgroundDark.toArgb(), context.getColor(R.color.splash_background))
    }

    @Test fun splashThemePaintsTheSplashBackground() {
        val theme = context.resources.newTheme().apply { applyStyle(R.style.Theme_PyrycodeMobile_SplashScreen, true) }
        val value = TypedValue()
        assertTrue(theme.resolveAttribute(androidx.core.splashscreen.R.attr.windowSplashScreenBackground, value, true))
        assertEquals(context.getColor(R.color.splash_background), value.data)
    }

    @Test fun splashMarkIsTheBrandGlacierBlue() {
        assertEquals(0xFF7AB8E8.toInt(), context.getColor(R.color.splash_mark))
    }

    @Test fun launcherIconBackgroundIsTheDarkSchemeBackground() {
        assertEquals(backgroundDark.toArgb(), context.getColor(R.color.ic_launcher_background))
    }
}
