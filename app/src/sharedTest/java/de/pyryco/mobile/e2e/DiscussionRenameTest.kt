package de.pyryco.mobile.e2e

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.pyryco.mobile.data.repository.ConversationFilter
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.StableConversationRepository
import de.pyryco.mobile.ui.conversations.components.RenameDialog
import de.pyryco.mobile.ui.theme.PyrycodeMobileTheme
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiscussionRenameTest {
    @get:Rule val compose = createComposeRule()

    @Test fun delayedDialogDoesNotReplaceComposer() {
        val composer = mutableStateOf("")
        val submissions = mutableListOf<String>()
        compose.setContent {
            PyrycodeMobileTheme {
                val focus = remember { FocusRequester() }
                var opening by remember { mutableStateOf(false) }
                var visible by remember { mutableStateOf(false) }
                var title by remember { mutableStateOf("old name") }
                Column {
                    Text(title)
                    BasicTextField(composer.value, { composer.value = it }, Modifier.focusRequester(focus))
                    Button(onClick = { opening = true }) { Text("Open rename") }
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
                LaunchedEffect(opening) {
                    if (opening) {
                        delay(200)
                        visible = true
                    }
                }
                if (visible) {
                    RenameDialog(title, onSubmit = {
                        submissions.add(it)
                        title = it
                        visible = false
                    }, onDismiss = { visible = false })
                }
            }
        }
        compose.waitUntil(1_000) { compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Open rename").performClick()
        compose.renameDiscussionInDialog("archive-1992", "Name", "Save", 1_000)
        assertEquals("the rename drive must not type into the thread composer", "", composer.value)
        assertEquals(listOf("archive-1992"), submissions)
    }

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    @Test
    fun owningHostGapDoesNotLoseRenameWhenAnotherHostIsReady() =
        runTest {
            val owner = MutableStateFlow<ConversationRepository?>(null)
            val otherHost = MutableStateFlow<ConversationRepository?>(FakeConversationRepository())
            val live = FakeConversationRepository()
            val chat = live.createDiscussion(null)
            val stable = StableConversationRepository(owner)
            val published = MutableStateFlow<ConversationRepository?>(FakeConversationRepository())
            // Mirror the coordinator's lagging publication with its synchronous value already absent.
            val current =
                object : StateFlow<ConversationRepository?> by published {
                    override val value: ConversationRepository? get() = owner.value
                }
            val visible = mutableStateOf(true)
            val title = mutableStateOf("old name")
            var submitted = 0
            var rejected = 0
            compose.setContent {
                PyrycodeMobileTheme {
                    Text(title.value)
                    if (visible.value) {
                        RenameDialog(title.value, onSubmit = {
                            submitted++
                            visible.value = false
                            // Same absent-delegate rejection the thread's guarded write consumes.
                            try {
                                title.value = runBlocking { stable.rename(chat.id, it).name.orEmpty() }
                            } catch (_: IllegalStateException) {
                                rejected++
                            }
                        }, onDismiss = { visible.value = false })
                    }
                }
            }
            compose.renameDiscussionInDialog(
                "archive-after-gap",
                "Name",
                "Save",
                1_000,
                diagnostic = {
                    "submitted=$submitted, rejected=$rejected, owner ready=${owner.value != null}, other ready=${otherHost.value != null}"
                },
                awaitOwningHost = {
                    assertEquals(null, owner.value)
                    assertTrue("another host is ready while the owner is absent", otherHost.value != null)
                    runBlocking {
                        withTimeout(1_000) {
                            launch {
                                owner.value = live
                                published.value = live
                            }
                            assertSame(live, current.awaitDiscussionRenameOwner())
                        }
                    }
                },
            )
            assertEquals(1, submitted)
            assertEquals(0, rejected)
            assertEquals(
                "archive-after-gap",
                live
                    .observeConversations(ConversationFilter.All)
                    .first()
                    .single { it.id == chat.id }
                    .name,
            )
        }

    @Test fun matchingFieldDoesNotProveRename() {
        compose.setContent {
            PyrycodeMobileTheme { RenameDialog("old name", onSubmit = {}, onDismiss = {}) }
        }
        val failure =
            assertThrows(AssertionError::class.java) {
                compose.renameDiscussionInDialog("archive-unconfirmed", "Name", "Save", 200)
            }
        assertTrue(failure.message.orEmpty().contains("await dialog dismissal"))
        assertTrue(failure.cause is ComposeTimeoutException)
    }

    @Test fun reopenedDialogSubmitsEachNameOnce() {
        val visible = mutableStateOf(true)
        val title = mutableStateOf("old name")
        val submissions = mutableListOf<String>()
        compose.setContent {
            PyrycodeMobileTheme {
                Text(title.value)
                if (visible.value) {
                    RenameDialog(title.value, onSubmit = {
                        submissions.add(it)
                        title.value = it
                        visible.value = false
                    }, onDismiss = { visible.value = false })
                }
            }
        }
        compose.renameDiscussionInDialog("first-name", "Name", "Save", 1_000)
        compose.runOnIdle { visible.value = true }
        compose.renameDiscussionInDialog("second-name", "Name", "Save", 1_000)
        assertEquals(listOf("first-name", "second-name"), submissions)
    }

    @Test fun diagnosticFailurePreservesOriginalTimeout() {
        val original = ComposeTimeoutException("original wait")
        val failure = discussionRenameFailure("await renamed title", { error("diagnostic failed") }, original)
        assertSame(original, failure.cause)
        assertEquals("discussion rename: await renamed title; diagnostic unavailable", failure.message)
    }
}
