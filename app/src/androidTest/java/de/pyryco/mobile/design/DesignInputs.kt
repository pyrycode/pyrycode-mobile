package de.pyryco.mobile.design

import androidx.lifecycle.SavedStateHandle
import de.pyryco.mobile.data.crypto.PairedServer
import de.pyryco.mobile.data.crypto.PairedServerCollectionStore
import de.pyryco.mobile.data.crypto.PairedServerEntry
import de.pyryco.mobile.data.model.BackgroundTaskRoster
import de.pyryco.mobile.data.model.ConnectionState
import de.pyryco.mobile.data.model.ConnectionStatus
import de.pyryco.mobile.data.model.HostModalState
import de.pyryco.mobile.data.model.LiveSessionEvent
import de.pyryco.mobile.data.model.PyrycodeLinkStatus
import de.pyryco.mobile.data.model.QuestionBatch
import de.pyryco.mobile.data.model.RelayLinkStatus
import de.pyryco.mobile.data.network.RelayConnectionController
import de.pyryco.mobile.data.repository.AttachmentOffer
import de.pyryco.mobile.data.repository.ConnectionStateSource
import de.pyryco.mobile.data.repository.ContextUsage
import de.pyryco.mobile.data.repository.ConversationRepository
import de.pyryco.mobile.data.repository.FakeConversationRepository
import de.pyryco.mobile.data.repository.SessionFacts
import de.pyryco.mobile.di.ConversationViewing
import de.pyryco.mobile.di.HostConversationConnection
import de.pyryco.mobile.di.HostConversationSource
import de.pyryco.mobile.di.RelayConnectionRegistry
import de.pyryco.mobile.di.ThreadDestinationFactory
import de.pyryco.mobile.ui.conversations.thread.AttachmentReader
import de.pyryco.mobile.ui.conversations.thread.ThreadViewModel
import de.pyryco.mobile.ui.onboarding.PairCodeViewModel
import de.pyryco.mobile.ui.onboarding.ScannerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The capture harness's Koin override: every thread and pairing input the fake graph cannot emit, as hot
 * flows a test sets before or after launch. [install] redefines `ThreadViewModel`, `ScannerViewModel` and
 * `PairCodeViewModel` over the app graph; [uninstall] restores the app's own definitions. Later audits set
 * values here and never edit this class.
 *
 * Every thread input applies to whichever thread opens. The question batch, roster, count and repository
 * flows ignore the conversation id here. [hostModal] does not: the view model scopes it with
 * `HostModalState.scopedTo`, so a prompt shows only when its `conversationId` is the open thread's.
 *
 * [install] also replaces the app's `HostConversationSource` with one demo host whose prompts are
 * [hostModal] and [questionBatch] (#1507), so the channel list marks each prompt's conversation Waiting, as
 * production's one host source does for both screens. With both empty the list draws as it did before.
 */
class DesignInputs {
    val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
    val liveSessionEvents = MutableSharedFlow<LiveSessionEvent>(replay = 16)

    /** The host's prompts; each `ModalUiState.Open` shows only in the thread its `conversationId` names. */
    val hostModal = MutableStateFlow(HostModalState())
    val questionBatch = MutableStateFlow<QuestionBatch?>(null)
    val backgroundTasks = MutableStateFlow<BackgroundTaskRoster?>(null)
    val backgroundTaskCount = MutableStateFlow(0)
    val pairingRejected = MutableStateFlow(false)
    val attachmentOffers = MutableStateFlow<List<AttachmentOffer>>(emptyList())
    val sessionFacts = MutableStateFlow<SessionFacts?>(null)
    val contextUsage = MutableStateFlow<ContextUsage?>(null)

    /**
     * What the scanner's and the pair-code screen's post-confirm wait observes. `null` holds the connecting
     * state until `PAIRING_VERIFICATION_DEADLINE_MS` (30 s), which then fails as unavailable with Retry.
     * `RelayLinkStatus.DaemonAbsent` fails the same way at once; `PairingRejected` fails without Retry.
     */
    val pairingStatus = MutableStateFlow<ConnectionStatus?>(null)

    /**
     * The in-memory paired-host store both pairing view models save to instead of the Keystore store, oldest
     * first. Seed an entry to give the pair-code screen's re-pair target a stored name.
     */
    val pairedHosts = mutableListOf<PairedServerEntry>()

    /** While `true`, a pairing save suspends, holding the pair-code screen in its saving state. */
    val holdSaves = MutableStateFlow(false)

    /** While `true`, the thread's question answer throws, as a failed send does, so Continue ends `Failed` (#1502). */
    @Volatile var failQuestionSends = false

    /** The view models the override built last, for states only an event reaches. */
    val thread = MutableStateFlow<ThreadViewModel?>(null)
    val scanner = MutableStateFlow<ScannerViewModel?>(null)
    internal val pairCode = MutableStateFlow<PairCodeViewModel?>(null)

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var appSource: HostConversationSource? = null
    private var listSource: HostConversationSource? = null

    fun install() {
        val koin = GlobalContext.get()
        appSource = koin.get<HostConversationSource>()
        val fake = koin.get<FakeConversationRepository>()
        val demo =
            HostConversationConnection(
                HostConversationSource.DEMO_SERVER_ID,
                "Demo",
                MutableStateFlow(fake),
                MutableStateFlow(ConnectionStatus(RelayLinkStatus.Connected, PyrycodeLinkStatus.Connected)),
                modals = hostModal,
                questionBatches = questionBatch.map { listOfNotNull(it) }.stateIn(scope, SharingStarted.Eagerly, emptyList()),
            )
        val source =
            HostConversationSource(
                MutableStateFlow(listOf(demo)),
                { if (it == HostConversationSource.DEMO_SERVER_ID) fake else null },
                viewing = koin.get(),
            )
        listSource = source
        loadKoinModules(listOf(module(), module { single { source } }))
    }

    fun uninstall() {
        val original = appSource
        loadKoinModules(if (original == null) listOf(appDefinitions()) else listOf(appDefinitions(), module { single { original } }))
        listSource?.dispose()
        scope.cancel()
    }

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
                    answerQuestionBatch = { _, _ -> check(!failQuestionSends) { "design: the question send fails" } },
                    backgroundTasks = { backgroundTasks },
                    backgroundTaskCount = { backgroundTaskCount },
                    pairingRejected = pairingRejected,
                    attachmentReader = get<AttachmentReader>(),
                ).also {
                    thread.value = it
                    // As production does: the open thread marks its conversation viewed until it is cleared.
                    val handle = get<SavedStateHandle>()
                    it.addCloseable(
                        get<ConversationViewing>().view(
                            handle.get<String>("serverId").orEmpty(),
                            handle.get<String>("conversationId").orEmpty(),
                        ),
                    )
                }
            }
            viewModel {
                ScannerViewModel(store, controller) { pairingStatus }.also { scanner.value = it }
            }
            viewModel {
                val target = get<SavedStateHandle>().get<String>("serverId")?.takeIf { it.isNotEmpty() }
                PairCodeViewModel(store, controller, { pairingStatus }, target).also { pairCode.value = it }
            }
        }

    private val controller =
        object : RelayConnectionController {
            override fun connect() = Unit

            override fun close() = Unit
        }

    private val store =
        object : PairedServerCollectionStore {
            override suspend fun load(): PairedServer? = pairedHosts.lastOrNull()?.record

            override suspend fun save(record: PairedServer) {
                holdSaves.first { !it }
                val name = loadById(record.serverId)?.displayName
                pairedHosts.removeAll { it.record.serverId == record.serverId }
                pairedHosts += PairedServerEntry(record, name)
            }

            override suspend fun list() = pairedHosts.toList()

            override suspend fun loadById(serverId: String) = pairedHosts.lastOrNull { it.record.serverId == serverId }

            override suspend fun setDisplayName(
                serverId: String,
                displayName: String?,
            ) {
                pairedHosts.replaceAll { if (it.record.serverId == serverId) it.copy(displayName = displayName) else it }
            }

            override suspend fun remove(serverId: String) {
                pairedHosts.removeAll { it.record.serverId == serverId }
            }
        }

    // A copy of appModule's three definitions. Reloading appModule itself would re-run its eager singletons.
    private fun appDefinitions(): Module =
        module {
            viewModel {
                val handle = get<SavedStateHandle>()
                get<ThreadDestinationFactory>().thread(handle, get(), get(), get(), get(), get()).also { thread ->
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
            viewModel {
                val registry = get<RelayConnectionRegistry>()
                val target = get<SavedStateHandle>().get<String>("serverId")?.takeIf { it.isNotEmpty() }
                PairCodeViewModel(get(), registry, registry::pairingStatus, target)
            }
        }
}
