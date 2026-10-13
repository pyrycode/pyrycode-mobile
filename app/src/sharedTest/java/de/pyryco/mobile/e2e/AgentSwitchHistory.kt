package de.pyryco.mobile.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performScrollToNode
import de.pyryco.mobile.data.model.Message
import de.pyryco.mobile.data.model.Role
import de.pyryco.mobile.ui.conversations.components.MESSAGE_BUBBLE_TEST_TAG
import org.junit.Assert.assertTrue

/** Each row can occupy a different viewport; list-key indices prove rendered order. */
internal fun assertRenderedSwitchHistory(
    rule: ComposeTestRule,
    messages: List<Message>,
    expected: List<Pair<Role, String>>,
) {
    val list = rule.onNode(hasScrollToNodeAction(), useUnmergedTree = true)
    val indices =
        expected.map { (role, text) ->
            val message = messages.single { it.role == role && it.content.trim() == text }
            val target =
                if (role == Role.Assistant) {
                    hasTestTag("assistant-reply:${message.id}")
                } else {
                    hasText(text) and hasAnyAncestor(hasTestTag(MESSAGE_BUBBLE_TEST_TAG))
                }
            list.performScrollToNode(target)
            rule.onNode(target, useUnmergedTree = true).assertIsDisplayed()
            val index = list.fetchSemanticsNode().config[SemanticsProperties.IndexForKey]("msg:${message.id}")
            assertTrue("authored row has no rendered index", index >= 0)
            index
        }
    // ThreadScreen's LazyColumn reverses chronological rows: older messages have larger indices.
    assertTrue("authored message order changed in the thread", indices.zipWithNext().all { (first, next) -> first > next })
}
