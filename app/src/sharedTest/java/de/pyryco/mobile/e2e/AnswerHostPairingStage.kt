package de.pyryco.mobile.e2e

import androidx.compose.ui.test.ComposeTimeoutException
import kotlinx.coroutines.CancellationException

/** Pairing substages are fixed labels; payloads and host identities never enter records. */
internal enum class AnswerHostPairingStage(
    val label: String,
) {
    List("initial channel list"),
    Permission("camera permission"),
    Scanner("open scanner"),
    Code("open code form"),
    Enter("enter code and name"),
    Fingerprint("await fingerprint confirmation"),
    Confirm("confirm fingerprint"),
    ReturnList("return channel list"),
}

internal inline fun <T> answerHostPairingStep(
    stage: AnswerHostPairingStage,
    phoneState: () -> String,
    emit: (String) -> Unit,
    block: () -> T,
): T {
    emit("event=answer_pairing stage=${stage.label} outcome=started")
    try {
        return block().also {
            val state = if (stage == AnswerHostPairingStage.ReturnList) " ${phoneState()}" else ""
            emit("event=answer_pairing stage=${stage.label} outcome=passed$state")
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: ComposeTimeoutException) {
        val state = phoneState()
        val diagnostic = "answer pairing '${stage.label}' timed out; $state"
        emit("event=answer_pairing stage=${stage.label} outcome=timeout $state")
        throw AssertionError(diagnostic, error)
    } catch (error: Throwable) {
        emit("event=answer_pairing stage=${stage.label} outcome=failed ${phoneState()}")
        throw error
    }
}
