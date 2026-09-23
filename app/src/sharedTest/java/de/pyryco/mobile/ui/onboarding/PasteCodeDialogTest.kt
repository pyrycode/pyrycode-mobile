package de.pyryco.mobile.ui.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Base64

/**
 * The paste dialog is the second producer of a raw untrusted pairing payload (#501). These tests pin
 * its store-free contract: a valid paste hands the trimmed payload to [onValidPayload] (which
 * MainActivity feeds to the shared fingerprint-confirm gate) and NEVER persists; a bad paste keeps
 * its inline error and never hands off. The dialog holds no reference to PairedServerStore, so
 * "paste cannot persist" is true by construction — these tests pin the callback contract.
 */
@RunWith(AndroidJUnit4::class)
class PasteCodeDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun title_label_and_buttons_render() {
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                PasteCodeDialog(onDismiss = {}, onValidPayload = {})
            }
        }

        composeTestRule.onNode(hasText("Enter pairing code")).assertIsDisplayed()
        composeTestRule.onNode(hasText("Pairing code")).assertIsDisplayed()
        composeTestRule.onNodeWithText("Pair").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun validPaste_handsOffPayload_andShowsNoError() {
        var captured: String? = null
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                PasteCodeDialog(onDismiss = {}, onValidPayload = { captured = it })
            }
        }

        val code = validPairingCode()
        composeTestRule.onNode(hasSetTextAction()).performTextInput(code)
        composeTestRule.onNodeWithText("Pair").performClick()

        assertEquals(code, captured)
        composeTestRule.onNodeWithText("Invalid pairing code").assertDoesNotExist()
    }

    @Test
    fun validPaste_trimsSurroundingWhitespace_beforeHandOff() {
        var captured: String? = null
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                PasteCodeDialog(onDismiss = {}, onValidPayload = { captured = it })
            }
        }

        val code = validPairingCode()
        composeTestRule.onNode(hasSetTextAction()).performTextInput("  $code  ")
        composeTestRule.onNodeWithText("Pair").performClick()

        assertEquals(code, captured)
    }

    @Test
    fun invalidPaste_showsInlineError_andDoesNotHandOff() {
        var captured: String? = null
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                PasteCodeDialog(onDismiss = {}, onValidPayload = { captured = it })
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextInput("garbage")
        composeTestRule.onNodeWithText("Pair").performClick()

        composeTestRule.onNodeWithText("Invalid pairing code").assertIsDisplayed()
        assertNull(captured)
    }

    @Test
    fun cancel_invokesOnDismiss_notOnValidPayload() {
        var dismissed = 0
        var captured: String? = null
        composeTestRule.setContent {
            PyrycodeMobileTheme {
                PasteCodeDialog(
                    onDismiss = { dismissed++ },
                    onValidPayload = { captured = it },
                )
            }
        }

        composeTestRule.onNode(hasSetTextAction()).performTextInput(validPairingCode())
        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(1, dismissed)
        assertNull(captured)
    }

    private companion object {
        /**
         * A valid outer base64url-no-pad pairing string (mirrors PairingPayloadParserTest.wrap(json)),
         * built here since the unit-test fixture helpers are not on the androidTest classpath. The inner
         * server_static_pubkey is base64-std (32 zero bytes) — the two-alphabet trap.
         */
        fun validPairingCode(): String {
            val pubkey = Base64.getEncoder().encodeToString(ByteArray(32))
            val json =
                """{"server":"srv-1","relay":"wss://relay.example.com",""" +
                    """"token":"tok-123","server_static_pubkey":"$pubkey"}"""
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
        }
    }
}
