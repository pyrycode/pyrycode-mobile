package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.Question
import de.pyryco.mobile.data.model.QuestionAnswer
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.QuestionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuestionModalStateTest {
    private val question = Question("Which?", "Pick", listOf(QuestionOption("A", "")), multiSelect = false)
    private val batch = QuestionBatch("chat", "batch-1", listOf(question))

    // #1349: the Other answer is sent trimmed, as desktop's resolveQuestionAnswers does.
    @Test
    fun other_answer_is_sent_trimmed() {
        val state = QuestionModalState(batch, listOf(QuestionSelection(otherTicked = true, otherText = "  yes  ")))

        assertEquals(listOf(QuestionAnswer(0, listOf("yes"))), state.answers())
    }

    @Test
    fun whitespace_only_other_text_is_no_value() {
        val state = QuestionModalState(batch, listOf(QuestionSelection(otherTicked = true, otherText = " \t ")))

        assertEquals(emptyList<String>(), state.selections[0].values(question))
        assertNull(state.answers())
    }
}
