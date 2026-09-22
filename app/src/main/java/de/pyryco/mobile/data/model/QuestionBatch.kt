package de.pyryco.mobile.data.model

/**
 * One offered choice of a [Question] (#822). Both strings are **claude-authored** and unsanitised
 * (pyrycode `docs/protocol-mobile.md` § Question (v2) SECURITY): hold them as inert text, never use one
 * as a key, never log one. An option has no id; its [label] is its identity on the wire.
 */
data class QuestionOption(
    val label: String,
    val description: String,
)

/** One question of a [QuestionBatch], in claude's own order. [question] and [header] are claude-authored. */
data class Question(
    val question: String,
    val header: String,
    val options: List<QuestionOption>,
    val multiSelect: Boolean,
)

/**
 * A clarification batch claude is blocked on (#822): the whole `AskUserQuestion` call, raised by
 * `question_shown` and retired by `question_dismissed`. Not a modal — it is scoped to [conversationId],
 * and several can be outstanding at once. The two ids are **daemon-asserted** and are the only keys.
 */
data class QuestionBatch(
    val conversationId: String,
    val questionBatchId: String,
    val questions: List<Question>,
)

/**
 * Folds a `question_shown` into the held batches, following desktop's `reduceQuestionBatches`: a batch
 * with no questions is out of contract and changes nothing (it neither adds nor replaces); a held batch
 * with the same [QuestionBatch.questionBatchId] is replaced in place (the reconnect reconcile re-sends
 * under the original id); anything else is appended.
 */
internal fun List<QuestionBatch>.withShown(batch: QuestionBatch): List<QuestionBatch> {
    if (batch.questions.isEmpty()) return this
    val index = indexOfFirst { it.questionBatchId == batch.questionBatchId }
    return if (index < 0) this + batch else toMutableList().also { it[index] = batch }
}

/** Removes the batch a `question_dismissed` retires. An unknown or already-removed id changes nothing. */
internal fun List<QuestionBatch>.withDismissed(questionBatchId: String): List<QuestionBatch> =
    if (none { it.questionBatchId == questionBatchId }) this else filterNot { it.questionBatchId == questionBatchId }

/**
 * The batch held for [conversationId], or null. claude blocks on one `AskUserQuestion` at a time, so two
 * for one conversation is out of contract; the first held wins, desktop's `selectBatchFor` rule.
 */
internal fun List<QuestionBatch>.batchFor(conversationId: String): QuestionBatch? = firstOrNull { it.conversationId == conversationId }
