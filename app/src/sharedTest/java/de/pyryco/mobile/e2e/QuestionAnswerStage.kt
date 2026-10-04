package de.pyryco.mobile.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import kotlinx.coroutines.TimeoutCancellationException

/** Fixed operation labels for #1703; no daemon-authored data enters the diagnostic. */
internal enum class QuestionAnswerStage(
    val label: String,
) {
    PairPhone("pair answer host on phone"),
    CreateChat("create and name answer conversation"),
    OpenPeer("open answer peer"),
    OpenThread("open answer conversation on phone"),
    SendFirstQuestion("send phone-answered question prompt"),
    AwaitFirstQuestion("await first question_shown on peer"),
    DrawFirstQuestion("draw first inline question on phone"),
    SubmitPhoneAnswer("select phone option and submit enabled Continue"),
    AwaitPhoneDismissal("await phone answer's question_dismissed on peer"),
    EndPhoneTurn("await phone-answered turn_end"),
    RemovePhoneQuestion("remove phone-answered inline question"),
    SendSecondQuestion("send peer-answered question prompt"),
    AwaitSecondQuestion("await second question_shown on peer"),
    DrawSecondQuestion("draw second inline question on phone"),
    SubmitPeerAnswer("send peer answer and await question_dismissed"),
    RemovePeerQuestion("remove peer-answered inline question without a phone tap"),
    EndPeerTurn("await peer-answered turn_end"),
}

/** Name a timed-out operation at its original deadline, with only a fixed label and content-free link state. */
internal inline fun <T> questionAnswerStep(
    stage: QuestionAnswerStage,
    linkState: () -> String,
    block: () -> T,
): T =
    try {
        block()
    } catch (e: TimeoutCancellationException) {
        throw AssertionError("question-answer step '${stage.label}' timed out; ${linkState()}", e)
    } catch (e: ComposeTimeoutException) {
        throw AssertionError("question-answer step '${stage.label}' timed out; ${linkState()}", e)
    }
