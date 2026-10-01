package de.pyryco.mobile.design

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerStore
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.ModalUiState
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.network.RelayConnectionController
import de.pyryco.mobile.data.repository.AttachmentOffer
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.ContextUsage
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.SessionFacts
import de.pyryco.mobile.di.ConversationViewing
import de.pyryco.mobile.di.RelayConnectionRegistry
import de.pyryco.mobile.di.ThreadDestinationFactory
import de.pyryco.mobile.ui.conversations.thread.AttachmentReader
import de.pyryco.mobile.ui.conversations.thread.ThreadViewModel
import de.pyryco.mobile.ui.onboarding.ScannerViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.context.loadKoinModules
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The capture harness's Koin override: every thread and scanner input the fake graph cannot emit, as hot
 * flows a test sets before or after launch. [install] redefines `ThreadViewModel` and `ScannerViewModel`
 * over the app graph; [uninstall] restores the app's own definitions. Later audits set values here and
 * never edit this class.
 *
 * Every thread input applies to whichever thread opens. Inputs keyed by conversation in production
 * (question batch, roster, count, repository flows) ignore the id here.
 */
class DesignInputs {
    val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
    val liveSessionEvents = MutableSharedFlow<LiveSessionEvent>(replay = 16)
    val hostModal = MutableStateFlow<ModalUiState>(ModalUiState.Hidden)
    val questionBatch = MutableStateFlow<QuestionBatch?>(null)
    val backgroundTasks = MutableStateFlow<BackgroundTaskRoster?>(null)
    val backgroundTaskCount = MutableStateFlow(0)
    val pairingRejected = MutableStateFlow(false)
    val attachmentOffers = MutableStateFlow<List<AttachmentOffer>>(emptyList())
    val sessionFacts = MutableStateFlow<SessionFacts?>(null)
    val contextUsage = MutableStateFlow<ContextUsage?>(null)

    /** What the scanner's post-confirm wait observes; `null` keeps it waiting (the connecting state). */
    val pairingStatus = MutableStateFlow<ConnectionStatus?>(null)

    /** Records the scanner's Confirm saves instead of writing the Keystore store. */
    val savedPairings = mutableListOf<PairedServer>()

    /** The view models the override built last, for states only an event reaches. */
    val thread = MutableStateFlow<ThreadViewModel?>(null)
    val scanner = MutableStateFlow<ScannerViewModel?>(null)

    /**
     * Every input flow the open thread collects, for checks that the override reaches it. [attachmentOffers]
     * is left out: at this commit no thread code reads `observeAttachmentOffers`, so nothing subscribes.
     */
    val threadInputs: Map<String, MutableSharedFlow<*>>
        get() =
            mapOf(
                "connectionState" to connectionState,
                "liveSessionEvents" to liveSessionEvents,
                "hostModal" to hostModal,
                "questionBatch" to questionBatch,
                "backgroundTasks" to backgroundTasks,
                "backgroundTaskCount" to backgroundTaskCount,
                "pairingRejected" to pairingRejected,
                "sessionFacts" to sessionFacts,
                "contextUsage" to contextUsage,
            )

    fun install() = loadKoinModules(module())

    fun uninstall() = loadKoinModules(appDefinitions())

    private fun module(): Module =
        module {
            viewModel {
                val fake = get<FakeConversationRepository>()
                val repository =
                    object : ConversationRepository by fake {
                        override fun observeAttachmentOffers(conversationId: String) = attachmentOffers

                        override fun observeSessionFacts(conversationId: String) = sessionFacts

                        override fun observeContextUsage(conversationId: String) = contextUsage
                    }
                val connection =
                    object : ConnectionStateSource {
                        override fun observe() = connectionState

                        override suspend fun retry() = Unit
                    }
                ThreadViewModel(
                    get<SavedStateHandle>(),
                    repository,
                    connection,
                    get(),
                    liveSessionEvents = liveSessionEvents,
                    hostModal = hostModal,
                    // No app draft stores: production binds them to a host coordinator, and only without one
                    // does the view model read questionBatch itself, as on the demo host.
                    questionBatch = { questionBatch },
                    backgroundTasks = { backgroundTasks },
                    backgroundTaskCount = { backgroundTaskCount },
                    pairingRejected = pairingRejected,
                    attachmentReader = get<AttachmentReader>(),
                ).also { thread.value = it }
            }
            viewModel {
                val store =
                    object : PairedServerStore {
                        override suspend fun load(): PairedServer? = savedPairings.lastOrNull()

                        override suspend fun save(record: PairedServer) {
                            savedPairings += record
                        }
                    }
                val controller =
                    object : RelayConnectionController {
                        override fun connect() = Unit

                        override fun close() = Unit
                    }
                ScannerViewModel(store, controller) { pairingStatus }.also { scanner.value = it }
            }
        }

    // A copy of appModule's two definitions. Reloading appModule itself would re-run its eager singletons.
    private fun appDefinitions(): Module =
        module {
            viewModel {
                val handle = get<SavedStateHandle>()
                get<ThreadDestinationFactory>().thread(handle, get(), get(), get(), get()).also { thread ->
                    val viewing =
                        get<ConversationViewing>().view(
                            handle.get<String>("serverId").orEmpty(),
                            handle.get<String>("conversationId").orEmpty(),
                        )
                    thread.addCloseable(viewing)
                }
            }
            viewModel {
                val registry = get<RelayConnectionRegistry>()
                ScannerViewModel(get(), registry, registry::pairingStatus)
            }
        }
}
