package de.pyryco.mobile.ui.conversations.thread

import de.pyryco.mobile.data.model.ConversationAgent
import de.pyryco.mobile.data.model.Question
import de.pyryco.mobile.data.model.QuestionAnswer
import de.pyryco.mobile.data.model.QuestionBatch
import kotlinx.coroutines.flow.map

/**
 * The operator's picks for one question of a held [QuestionBatch] (#661). An option is named by its
 * index, never its claude-authored label. [otherText] is operator-authored, held independently of
 * [otherTicked] (desktop's `questionPicksStore`), and never logged.
 */
data class QuestionSelection(
    val optionIndices: Set<Int> = emptySet(),
    val otherTicked: Boolean = false,
    val otherText: String = "",
) {
    fun withOption(
        optionIndex: Int,
        multiSelect: Boolean,
    ): QuestionSelection =
        when {
            !multiSelect -> copy(optionIndices = setOf(optionIndex), otherTicked = false)
            optionIndex in optionIndices -> copy(optionIndices = optionIndices - optionIndex)
            else -> copy(optionIndices = optionIndices + optionIndex)
        }

    /** Ticking Other on a single-choice question clears its option pick: radio semantics. */
    fun withOtherTicked(
        ticked: Boolean,
        multiSelect: Boolean,
    ): QuestionSelection = copy(otherTicked = ticked, optionIndices = if (ticked && !multiSelect) emptySet() else optionIndices)

    /** The values sent for this question: option labels in option order, then Other text trimmed (desktop's `resolveQuestionAnswers`). */
    fun values(question: Question): List<String> =
        question.options.indices
            .filter { it in optionIndices }
            .map { question.options[it].label } +
            listOfNotNull(otherText.trim().takeIf { otherTicked && it.isNotEmpty() })
}

/** Where the one answer-or-refusal send of a question batch stands (#661). */
enum class QuestionSendPhase { Idle, Sending, Sent, Failed }

/**
 * The question modal for the open conversation's held batch (#661). [selections] is index-aligned with
 * [QuestionBatch.questions]. Once a send is in flight or has succeeded the modal is [locked] until the
 * daemon's `question_dismissed` removes the batch. [agent] is the conversation's, for the title (#1116).
 */
data class QuestionModalState(
    val batch: QuestionBatch,
    val selections: List<QuestionSelection> = List(batch.questions.size) { QuestionSelection() },
    val phase: QuestionSendPhase = QuestionSendPhase.Idle,
    val agent: ConversationAgent = ConversationAgent.Claude,
    val generation: Long = 0,
) {
    val locked: Boolean get() = phase == QuestionSendPhase.Sending || phase == QuestionSendPhase.Sent

    /** One answer covering every question, or null while any question has no value. */
    fun answers(): List<QuestionAnswer>? =
        batch.questions.mapIndexed { index, question ->
            val values = selections[index].values(question)
            if (values.isEmpty()) return null
            QuestionAnswer(index, values)
        }

    val canContinue: Boolean get() = !locked && answers() != null
}

sealed interface QuestionModalEvent {
    data class OptionToggled(
        val questionIndex: Int,
        val optionIndex: Int,
    ) : QuestionModalEvent

    data class OtherToggled(
        val questionIndex: Int,
    ) : QuestionModalEvent

    /** Typing ticks Other, with the single-choice clear. */
    data class OtherTextChanged(
        val questionIndex: Int,
        val text: String,
    ) : QuestionModalEvent

    data object Continue : QuestionModalEvent

    data object Cancel : QuestionModalEvent
}
