package de.pyryco.mobile.data.repository

import de.pyryco.mobile.data.model.QuestionAnswer
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.withDismissed
import de.pyryco.mobile.data.model.withShown
import de.pyryco.mobile.data.network.Envelope
import de.pyryco.mobile.data.network.MobileJson
import de.pyryco.mobile.data.network.QuestionAnswerEntryDto
import de.pyryco.mobile.data.network.QuestionAnswerPayloadDto
import de.pyryco.mobile.data.network.QuestionDismissedPayloadDto
import de.pyryco.mobile.data.network.QuestionRefusedPayloadDto
import de.pyryco.mobile.data.network.QuestionShownPayloadDto
import de.pyryco.mobile.data.network.toBatch
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_QUESTION_ANSWER
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_QUESTION_DISMISSED
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_QUESTION_REFUSED
import de.pyryco.mobile.data.repository.RemoteConversationRepository.Companion.TYPE_QUESTION_SHOWN
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/**
 * The clarification batches on one connection (#913): their held state, the fold of `question_shown` /
 * `question_dismissed`, and the answer and refusal sends, split out of [RemoteConversationRepository] the
 * way the thread store was (#912). The repository keeps the routing: its `onInbound` arm calls [apply]
 * only behind the negotiated `interactive` gate, and its public
 * [RemoteConversationRepository.questionBatches], [RemoteConversationRepository.answerQuestionBatch] and
 * [RemoteConversationRepository.refuseQuestionBatch] hand off to [batches], [answer] and [refuse].
 *
 * [send] is the repository's pump send and [nextRequestId] its one request-id counter, so every question
 * frame takes its envelope id from the same sequence as every other request.
 *
 * One instance per repository, and a fresh repository per connection (#351), so the state is
 * connection-scoped exactly as it was when it lived in the repository. Nothing here logs.
 */
internal class QuestionBatchProjection(
    private val send: (Envelope) -> Boolean,
    private val nextRequestId: () -> Long,
) {
    /**
     * The clarification batches outstanding on **this connection** (#822), folded from `question_shown` /
     * `question_dismissed` by the single inbound collector. Held state rather than a `replay = 0` event
     * stream: the daemon's connect-time reconcile re-sends every outstanding batch in one burst, and an
     * event stream folded downstream could lose part of it to a late subscriber. A new connection builds a
     * new repository, so this starts empty and the reconcile rebuilds it — the protocol's reset-on-reconnect
     * rule, the opposite of `currentModal`'s retain. On the concrete repository only, like
     * [RemoteConversationRepository.modalEvents].
     */
    private val mutableQuestionBatches = MutableStateFlow<List<QuestionBatch>>(emptyList())
    val batches: StateFlow<List<QuestionBatch>> = mutableQuestionBatches.asStateFlow()

    /**
     * Fold one question envelope (#822) into [batches] via [withShown] / [withDismissed]. The decode
     * is strict ([QuestionShownPayloadDto], [QuestionDismissedPayloadDto]); any failure is an
     * [IllegalArgumentException] ([kotlinx.serialization.SerializationException] ⊂ it) and changes nothing,
     * so the lone inbound collector survives. The exception is discarded unlogged: kotlinx messages can
     * quote the JSON input.
     */
    fun apply(envelope: Envelope) {
        try {
            when (envelope.type) {
                TYPE_QUESTION_SHOWN -> {
                    val batch = MobileJson.decodeFromJsonElement<QuestionShownPayloadDto>(envelope.payload).toBatch()
                    mutableQuestionBatches.update { it.withShown(batch) }
                }
                TYPE_QUESTION_DISMISSED -> {
                    val dismissed = MobileJson.decodeFromJsonElement<QuestionDismissedPayloadDto>(envelope.payload)
                    mutableQuestionBatches.update { it.withDismissed(dismissed.questionBatchId) }
                }
            }
        } catch (e: IllegalArgumentException) {
            return
        }
    }

    /** The body of [RemoteConversationRepository.answerQuestionBatch]; its KDoc states the contract. */
    fun answer(
        questionBatchId: String,
        answers: List<QuestionAnswer>,
    ) {
        val batch = heldQuestionBatch(questionBatchId)
        require(answers.map { it.questionIndex }.sorted() == batch.questions.indices.toList()) {
            "$TYPE_QUESTION_ANSWER must answer every question exactly once"
        }
        val payload =
            QuestionAnswerPayloadDto(
                questionBatchId = questionBatchId,
                answerToken = questionToken("answer", questionBatchId),
                answers = answers.sortedBy { it.questionIndex }.map { QuestionAnswerEntryDto(it.questionIndex, it.values) },
            )
        sendQuestionFrame(TYPE_QUESTION_ANSWER, MobileJson.encodeToJsonElement(payload))
    }

    /** The body of [RemoteConversationRepository.refuseQuestionBatch]; its KDoc states the contract. */
    fun refuse(questionBatchId: String) {
        heldQuestionBatch(questionBatchId)
        val payload = QuestionRefusedPayloadDto(questionBatchId, questionToken("refuse", questionBatchId))
        sendQuestionFrame(TYPE_QUESTION_REFUSED, MobileJson.encodeToJsonElement(payload))
    }

    private fun heldQuestionBatch(questionBatchId: String): QuestionBatch =
        checkNotNull(mutableQuestionBatches.value.firstOrNull { it.questionBatchId == questionBatchId }) {
            "question batch not outstanding"
        }

    private fun sendQuestionFrame(
        type: String,
        payload: JsonElement,
    ) {
        val request = Envelope(id = nextRequestId(), type = type, ts = Clock.System.now().toString(), payload = payload)
        check(send(request)) { "$type not sent: session not connected" }
    }

    /**
     * The `answer_token` for a question send (#825): the verb and the daemon-minted batch nonce, so a
     * retry of the same send reuses the token while an answer and a refusal, or two batches, never share
     * one. It carries no answer value and no claude-authored text. Secrecy does not matter; the daemon's
     * real dedup is its one-shot consume of the batch id.
     */
    private fun questionToken(
        verb: String,
        questionBatchId: String,
    ): String = "$verb:$questionBatchId"
}
