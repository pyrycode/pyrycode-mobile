package de.pyryco.mobile.data.network

import de.pyryco.mobile.data.model.Question
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.QuestionOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The two v2 **question** payloads (#822): `question_shown` (claude's whole `AskUserQuestion` batch in
 * one frame) and `question_dismissed` (its retirement). Wire SSOT: pyrycode `docs/protocol-mobile.md`
 * § Question (v2). Every field is always present on the wire, so each is required and non-null here —
 * the strict posture of desktop's `parseQuestionShownPayload`: a missing or wrong-typed field fails the
 * whole frame. Unknown keys are tolerated through [MobileJson]. Always decode through [MobileJson].
 *
 * `question`, `header`, `label` and `description` are claude-authored and unsanitised; they are carried
 * verbatim and nothing here logs them or puts them in an exception message.
 */
@Serializable
internal data class QuestionOptionDto(
    val label: String,
    val description: String,
)

/**
 * [multiSelect] is a raw [JsonPrimitive] because [MobileJson] decodes a quoted `"false"` into a
 * `Boolean`; [toQuestion] checks the type itself, as desktop's `requireBoolean` does.
 */
@Serializable
internal data class QuestionDto(
    val question: String,
    val header: String,
    val options: List<QuestionOptionDto>,
    @SerialName("multi_select") val multiSelect: JsonPrimitive,
)

@Serializable
internal data class QuestionShownPayloadDto(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("question_batch_id") val questionBatchId: String,
    val questions: List<QuestionDto>,
)

/**
 * `outcome` and `source` are producer-owned open vocabularies, decoded for strictness and not closed to
 * a set: any value retires the batch.
 */
@Serializable
internal data class QuestionDismissedPayloadDto(
    @SerialName("question_batch_id") val questionBatchId: String,
    val outcome: String,
    val source: String,
)

/** Total field copy in wire order. Throws [SerializationException] when a `multi_select` is not a JSON boolean. */
internal fun QuestionShownPayloadDto.toBatch(): QuestionBatch =
    QuestionBatch(
        conversationId = conversationId,
        questionBatchId = questionBatchId,
        questions = questions.map { it.toQuestion() },
    )

private fun QuestionDto.toQuestion(): Question =
    Question(
        question = question,
        header = header,
        options = options.map { QuestionOption(it.label, it.description) },
        multiSelect = multiSelect.strictBoolean(),
    )

/** Static message: never interpolate the offending value (the `readEffectiveEffort` posture). */
private fun JsonPrimitive.strictBoolean(): Boolean =
    (if (isString) null else booleanOrNull) ?: throw SerializationException("question_shown: multi_select must be a boolean")

/**
 * One entry of an outbound `question_answer` (#825): the question by index, never by its claude-authored
 * text, and the operator's [values] verbatim.
 */
@Serializable
internal data class QuestionAnswerEntryDto(
    @SerialName("question_index") val questionIndex: Int,
    val values: List<String>,
)

/**
 * Outbound `question_answer` (#825), field-for-field with the daemon's `question_answer.json` fixture.
 * No `conversation_id` (the batch id is the sole correlation) and no vendor `response` field.
 */
@Serializable
internal data class QuestionAnswerPayloadDto(
    @SerialName("question_batch_id") val questionBatchId: String,
    @SerialName("answer_token") val answerToken: String,
    val answers: List<QuestionAnswerEntryDto>,
)

/** Outbound `question_refused` (#825): the batch id and the token, and nothing else. */
@Serializable
internal data class QuestionRefusedPayloadDto(
    @SerialName("question_batch_id") val questionBatchId: String,
    @SerialName("answer_token") val answerToken: String,
)
