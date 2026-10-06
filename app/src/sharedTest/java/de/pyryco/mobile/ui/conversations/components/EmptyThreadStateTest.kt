package de.pyryco.mobile.ui.conversations.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.R
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #1625: Figma 696:4989 draws the empty-thread line in bodySmall (12sp/16sp line height), not the
 * bodyMedium (14sp/20sp) this screen drew before, which read 192dp wide against the frame's 171dp.
 */
@RunWith(AndroidJUnit4::class)
class EmptyThreadStateTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun text_uses_body_small() {
        composeTestRule.setContent {
            PyrycodeMobileTheme { EmptyThreadState() }
        }
        val string = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.thread_empty_state)

        val results = mutableListOf<TextLayoutResult>()
        composeTestRule.onNodeWithText(string).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }

        assertEquals(
            12.sp,
            results
                .single()
                .layoutInput.style.fontSize,
        )
        assertEquals(
            16.sp,
            results
                .single()
                .layoutInput.style.lineHeight,
        )
    }
}
