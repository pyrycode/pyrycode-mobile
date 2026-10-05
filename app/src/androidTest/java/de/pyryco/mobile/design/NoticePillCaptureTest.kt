package de.pyryco.mobile.design

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.conversations.components.NoticePill
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device-only: retains real system bars and nonblank hardware framebuffer pixels. */
@RunWith(AndroidJUnit4::class)
class NoticePillCaptureTest {
    @get:Rule(order = 0)
    val viewport = ViewportRule()

    @get:Rule(order = 1)
    val rule = createEmptyComposeRule()

    @get:Rule(order = 2)
    val design = DesignCapture(rule)

    @Test fun bothVariantsAt412By892() {
        design.launch()
        checkNotNull(design.scenario).onActivity { activity ->
            activity.setContent {
                PyrycodeMobileTheme(darkTheme = true, dynamicColor = false) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(
                            Modifier.padding(horizontal = 16.dp, vertical = 80.dp),
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                        ) {
                            NoticePill("Host connection down!", isError = false, onDismiss = {})
                            NoticePill("Host connection down!", isError = true)
                        }
                    }
                }
            }
        }
        design.capture("pill-1757", "both-variants", "347:6617,347:6619")
    }
}
