package de.pyryco.mobile.e2e

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Rect
import android.util.Log
import android.view.View
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.withText
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.security.MessageDigest

internal const val SELECTION_REPLY = "amber cobalt jade"
private const val CLIPBOARD_BASELINE = "unrelated clipboard baseline"
private const val DIAGNOSTIC_TAG = "SelectionCopy"
internal const val SELECTION_PROMPT =
    "Reply with exactly these three words on one line, plain text, no punctuation or explanation: amber cobalt jade"

/** Real pointer selection and Android's floating Copy menu; no substituted toolbar or clipboard. */
internal fun AndroidComposeTestRule<*, *>.assertFinishedReplySystemCopy(
    repository: ConversationRepository,
    conversationId: String,
    timeoutMillis: Long,
) {
    // Text can render while still streaming and unselectable. Gate on the phone's finalized row.
    val reply =
        runBlocking {
            withTimeout(timeoutMillis) {
                repository.observeMessages(conversationId).first { rows ->
                    rows.filterIsInstance<ThreadItem.MessageItem>().any {
                        it.message.role == Role.Assistant &&
                            !it.message.isStreaming &&
                            it.message.content == SELECTION_REPLY
                    }
                }
            }
        }.filterIsInstance<ThreadItem.MessageItem>()
            .single {
                it.message.role == Role.Assistant && it.message.content == SELECTION_REPLY
            }.message.content
    waitUntil(timeoutMillis) {
        onAllNodesWithText(reply, useUnmergedTree = true).fetchSemanticsNodes().size == 1
    }
    val body = onNodeWithText(reply, useUnmergedTree = true).assertIsDisplayed()
    val layouts = mutableListOf<TextLayoutResult>()
    body.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
    val layout = layouts.single()
    val word = "cobalt"
    val offset = reply.indexOf(word) + word.length / 2
    val press = layout.getBoundingBox(offset).center

    val context = activity
    val clipboard = activity.selectionClipboard()
    runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("selection baseline", CLIPBOARD_BASELINE)) }
    runOnIdle {
        assertEquals(
            CLIPBOARD_BASELINE,
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString(),
        )
    }

    val steps = mutableListOf<String>()

    fun record(step: String) {
        steps += step
        Log.i(DIAGNOSTIC_TAG, "event=selection_copy $step")
    }
    val bounds = body.fetchSemanticsNode().boundsInWindow
    runOnIdle {
        record("stage=press bodyWindow=$bounds localPress=$press layout=${layout.size} ${context.copyActivityStatus()}")
    }
    body.performTouchInput { longClick(press) }
    waitForIdle()
    val selection = body.fetchSemanticsNode().config.getOrNull(SemanticsProperties.TextSelectionRange)
    runOnIdle {
        record("stage=selection range=${selection ?: "unknown"} ${context.copyActivityStatus()}")
    }
    // Espresso addresses the platform toolbar's real view, outside the Compose semantics tree.
    val platformClick = click()
    onView(withText(context.getString(android.R.string.copy)))
        .inRoot(isPlatformPopup())
        .perform(
            object : ViewAction {
                override fun getConstraints() = platformClick.constraints

                override fun getDescription() = platformClick.description

                override fun perform(
                    uiController: UiController,
                    view: View,
                ) {
                    record(
                        "stage=before_copy ${view.copyMenuStatus()} ${context.copyActivityStatus()}",
                    )
                    // Exactly one real Android platform action, with the original Espresso constraints.
                    platformClick.perform(uiController, view)
                    record("stage=after_copy ${view.copyMenuStatus()} ${context.copyActivityStatus()}")
                }
            },
        )
    assertSelectedWordOnClipboard(clipboard, reply, word, timeoutMillis) {
        "steps=[${steps.joinToString("; ")}] finalActivity=[${context.copyActivityStatus()}]"
    }
}

internal fun Activity.selectionClipboard(): ClipboardManager = getSystemService(ClipboardManager::class.java)

private fun Activity.copyActivityStatus(): String =
    "activityWindowFocused=${hasWindowFocus()} finishing=$isFinishing destroyed=$isDestroyed " +
        "focusedView=${currentFocus?.javaClass?.simpleName ?: "none"} activityPackage=$opPackageName"

private fun View.copyMenuStatus(): String {
    val rootVisibleBounds = Rect()
    val visible = getGlobalVisibleRect(rootVisibleBounds)
    val location = IntArray(2)
    getLocationOnScreen(location)
    val screenBounds = Rect(location[0], location[1], location[0] + width, location[1] + height)
    return "menuScreen=$screenBounds rootVisible=$rootVisibleBounds visible=$visible shown=$isShown attached=$isAttachedToWindow " +
        "enabled=$isEnabled menuWindowFocused=${hasWindowFocus()} menuPackage=${context.opPackageName}"
}

internal fun ComposeTestRule.assertSelectedWordOnClipboard(
    clipboard: ClipboardManager,
    reply: String,
    word: String,
    timeoutMillis: Long,
    copyDiagnostics: () -> String = { "copyStep=not_recorded" },
) {
    // UI idleness is not the clipboard-result contract. Observe the actual selected-word write.
    var observations = 0
    var last = "clipboard=unobserved"
    val transitions = mutableListOf<String>()
    try {
        waitUntil("system Copy replaces the unrelated baseline with the selected word", timeoutMillis) {
            runOnIdle {
                val clip = clipboard.primaryClip
                val copied = clip.firstText()
                val outcome = clipboardOutcome(clip, copied, reply, word)
                observations++
                if (outcome != last && transitions.size < 6) transitions += outcome
                last = outcome
                copied == word
            }
        }
    } catch (failure: ComposeTimeoutException) {
        val steps = runOnIdle { copyDiagnostics() }
        val diagnostic = "observations=$observations last=[$last] transitions=[${transitions.joinToString("; ")}] $steps"
        Log.w(DIAGNOSTIC_TAG, "event=selection_copy_timeout $diagnostic")
        throw ComposeTimeoutException("${failure.message}; $diagnostic").apply { initCause(failure) }
    }
    runOnIdle {
        val clip = clipboard.primaryClip
        val copied = clip.firstText()
        Log.i(DIAGNOSTIC_TAG, "event=selection_copy_result ${clipboardOutcome(clip, copied, reply, word)}")
        assertEquals("system Copy must replace the baseline with only the selected word", word, copied)
        assertTrue("selection must be shorter than the assistant reply", requireNotNull(copied).length < reply.length)
    }
}

private fun ClipData?.firstText(): String? =
    this
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.text
        ?.toString()

/** Never include clipboard text or its label, including unrelated text left by another test. */
private fun clipboardOutcome(
    clip: ClipData?,
    copied: String?,
    reply: String,
    word: String,
): String {
    val kind =
        when {
            clip == null -> "absent"
            clip.itemCount == 0 -> "empty_clip"
            copied == null -> "non_text"
            copied == CLIPBOARD_BASELINE -> "baseline"
            copied == word -> "selected_word"
            copied == reply -> "whole_reply"
            copied.isEmpty() -> "empty_text"
            reply.contains(copied) -> "reply_span span=${reply.indexOf(copied)}:${reply.indexOf(copied) + copied.length}"
            else -> {
                // Bound hashing work too; the full length distinguishes larger clips sharing a prefix.
                val hash = MessageDigest.getInstance("SHA-256").digest(copied.take(256).toByteArray())
                "other_text prefixSha256=${hash.joinToString("") { "%02x".format(it) }}"
            }
        }
    return "clipboard=$kind items=${clip?.itemCount ?: 0} textLength=${copied?.length ?: "none"} " +
        "timestampMs=${clip?.description?.timestamp ?: "none"}"
}
