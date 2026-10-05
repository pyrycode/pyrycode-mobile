package de.pyryco.mobile.e2e

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.ThreadItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

internal const val SELECTION_REPLY = "amber cobalt jade"
internal const val SELECTION_PROMPT =
    "Reply with exactly these three words on one line, plain text, no punctuation or explanation: amber cobalt jade"

/** Real pointer selection and Android's floating Copy menu; no substituted toolbar or clipboard. */
internal fun ComposeTestRule.assertFinishedReplySystemCopy(
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

    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("selection baseline", "unrelated clipboard baseline")) }
    runOnIdle {
        assertEquals(
            "unrelated clipboard baseline",
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString(),
        )
    }

    body.performTouchInput { longClick(press) }
    waitForIdle()
    // Espresso addresses the platform toolbar's real view, outside the Compose semantics tree.
    onView(withText(context.getString(android.R.string.copy))).perform(click())
    runOnIdle {
        val copied =
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString()
        assertEquals("system Copy must replace the baseline with only the selected word", word, copied)
        assertTrue("selection must be shorter than the assistant reply", requireNotNull(copied).length < reply.length)
    }
}
