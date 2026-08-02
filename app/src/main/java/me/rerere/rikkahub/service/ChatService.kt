package me.rerere.rikkahub.service

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.FinalAnswerRecoveryStatus
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.UIMessageState
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.GenerationPersistenceBarrier
import me.rerere.rikkahub.data.ai.sanitizeTransientConversationToolResults
import me.rerere.rikkahub.data.ai.ToolCallOrigin
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.WebSearchPolicy
import me.rerere.rikkahub.data.ai.tools.createConversationTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.DEFAULT_AUTO_MODEL_ID
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.datastore.getChatModelForAssistant
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.workspace.WorkspaceShellStatus
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.ai.GenerationRunControl
import me.rerere.rikkahub.data.ai.tools.CancelRequestResult
import me.rerere.rikkahub.data.ai.tools.ToolCancelReason
import me.rerere.rikkahub.service.chat.ChatCommand
import me.rerere.rikkahub.service.chat.PetDialogueCommand
import me.rerere.rikkahub.service.chat.CancelCurrentToolCommand
import me.rerere.rikkahub.service.chat.CommandEnvelope
import me.rerere.rikkahub.service.chat.CommandOrigin
import me.rerere.rikkahub.service.chat.CommandOutcome
import me.rerere.rikkahub.service.chat.ConversationRuntime
import me.rerere.rikkahub.service.chat.InterruptCommand
import me.rerere.rikkahub.service.chat.InterruptRegenerateCommand
import me.rerere.rikkahub.service.chat.PersistenceCoordinator
import me.rerere.rikkahub.service.chat.RawUserContent
import me.rerere.rikkahub.service.chat.RuntimeCommandExecutor
import me.rerere.rikkahub.service.chat.RuntimeHydrator
import me.rerere.rikkahub.service.chat.SendMessageCommand
import me.rerere.rikkahub.service.chat.StopCommand
import me.rerere.rikkahub.service.chat.SteerCommand
import me.rerere.rikkahub.service.chat.SteeringScope
import me.rerere.rikkahub.service.chat.SubmitResult
import me.rerere.rikkahub.service.chat.ToolApprovalCommand
import me.rerere.rikkahub.service.chat.ToolDecision
import me.rerere.rikkahub.service.chat.NormalCommand
import me.rerere.rikkahub.service.chat.RegenerateCommand
import me.rerere.rikkahub.service.chat.RunOutcome
import me.rerere.rikkahub.service.chat.DispatcherProvider
import me.rerere.rikkahub.service.chat.DurableCommandQueue
import me.rerere.rikkahub.service.chat.EmergencyCommand
import me.rerere.rikkahub.service.chat.FastPathContext
import me.rerere.rikkahub.service.chat.FastPathDecision
import me.rerere.rikkahub.service.chat.FastPathRouter
import me.rerere.rikkahub.service.chat.FastPathCommitPlan
import me.rerere.rikkahub.service.chat.buildFastPathCommitPlan
import me.rerere.rikkahub.service.chat.ResumeAfterApprovalCommand
import me.rerere.rikkahub.service.chat.ResumeQueueCommand
import me.rerere.rikkahub.service.chat.ClearPendingQueueCommand
import me.rerere.rikkahub.service.chat.CancelQueuedCommand
import me.rerere.rikkahub.service.chat.CancelSteeringCommand
import me.rerere.rikkahub.service.chat.UpdateQueuedMessageCommand
import me.rerere.rikkahub.service.chat.PromoteQueuedMessageToSteeringCommand
import me.rerere.rikkahub.service.chat.QueuedMessageUiEntry
import me.rerere.rikkahub.subagent.allowsTool
import me.rerere.rikkahub.subagent.generationMaxSteps

private const val TAG = "ChatService"
private const val FAST_PATH_TOOL_BUDGET_MS = 30_000L

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    // Compression/title/suggestion calls are non-interactive. On generic OpenAI-compatible
    // gateways, an explicit disabled reasoning field (for example `reasoning_effort: low`) can
    // itself be rejected with HTTP 400. Keep ordinary chat behavior unchanged, but omit it here.
    omitReasoningConfigurationWhenOff = true,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

internal data class TrackedCommandSubmission(
    val submission: SubmitResult,
    val outcome: Deferred<CommandOutcome>,
)

private fun rejectedTrackedCommand(reason: String) = TrackedCommandSubmission(
    submission = SubmitResult.Rejected(reason),
    outcome = CompletableDeferred(CommandOutcome.Rejected(reason)),
)

data class ChatEmergencyStopResult(
    val runtimeCount: Int,
    val stoppedRuntimeCount: Int,
    val clearedQueueCount: Int,
    val failures: Map<String, String> = emptyMap(),
) {
    val ok: Boolean get() = failures.isEmpty() &&
        stoppedRuntimeCount == runtimeCount && clearedQueueCount == runtimeCount
}

internal fun resolveGenerationCommandId(
    activeCommandId: Uuid?,
    runId: Uuid?,
): Uuid? = activeCommandId ?: runId

internal fun List<UIMessage>.withResponseCorrelation(
    annotation: UIMessageAnnotation?,
): List<UIMessage> {
    annotation ?: return this
    val sourceIndex = indexOfLast { message ->
        message.role == MessageRole.USER && annotation in message.annotations
    }
    if (sourceIndex < 0) return this
    return mapIndexed { index, message ->
        if (index > sourceIndex && message.role == MessageRole.ASSISTANT && annotation !in message.annotations) {
            message.copy(annotations = message.annotations + annotation)
        } else {
            message
        }
    }
}

private fun UIMessageAnnotation.isResponseCorrelation(): Boolean =
    this is UIMessageAnnotation.QuickCapture || this is UIMessageAnnotation.PetHandoff

internal data class ChatEmergencyRuntimeTarget(
    val conversationId: Uuid,
    val submitStop: () -> ChatEmergencyCommandSubmission,
    val clearQueue: suspend () -> ChatEmergencyCommandSubmission,
)

internal data class ChatEmergencyCommandSubmission(
    val submission: SubmitResult,
    val outcome: Deferred<CommandOutcome>,
)

internal suspend fun stopChatRuntimeSnapshot(
    targets: List<ChatEmergencyRuntimeTarget>,
): ChatEmergencyStopResult {
    val reports = coroutineScope {
        targets.map { target ->
            async {
                val failures = linkedMapOf<String, String>()
                val stop = runCatching { target.submitStop() }.getOrElse { error ->
                    ChatEmergencyCommandSubmission(
                        SubmitResult.Rejected(error.message ?: error.javaClass.simpleName),
                        CompletableDeferred(CommandOutcome.Failed(error)),
                    )
                }
                val clear = runCatching { target.clearQueue() }.getOrElse { error ->
                    ChatEmergencyCommandSubmission(
                        SubmitResult.Rejected(error.message ?: error.javaClass.simpleName),
                        CompletableDeferred(CommandOutcome.Failed(error)),
                    )
                }
                val stopConfirmed = confirmEmergencySubmission(
                    key = "${target.conversationId}:stop",
                    command = stop,
                    failures = failures,
                )
                val clearConfirmed = confirmEmergencySubmission(
                    key = "${target.conversationId}:queue",
                    command = clear,
                    failures = failures,
                )
                Triple(stopConfirmed, clearConfirmed, failures)
            }
        }.awaitAll()
    }
    val stopped = reports.count { it.first }
    val cleared = reports.count { it.second }
    val failures = linkedMapOf<String, String>().apply {
        reports.forEach { putAll(it.third) }
    }
    return ChatEmergencyStopResult(
        runtimeCount = targets.size,
        stoppedRuntimeCount = stopped,
        clearedQueueCount = cleared,
        failures = failures,
    )
}

private suspend fun confirmEmergencySubmission(
    key: String,
    command: ChatEmergencyCommandSubmission,
    failures: MutableMap<String, String>,
): Boolean {
    when (val result = command.submission) {
        is SubmitResult.Accepted -> Unit
        is SubmitResult.QueueFull -> {
            failures[key] = "Queue full (${result.limit})"
            return false
        }
        is SubmitResult.Rejected -> {
            failures[key] = result.reason
            return false
        }
        is SubmitResult.RuntimeUnavailable -> {
            failures[key] = result.reason
            return false
        }
    }
    val outcome = withTimeoutOrNull(CHAT_EMERGENCY_CONFIRM_TIMEOUT_MS) { command.outcome.await() }
    if (outcome == CommandOutcome.Completed) return true
    failures[key] = when (outcome) {
        null -> "Timed out waiting for Runtime confirmation"
        is CommandOutcome.Rejected -> outcome.reason
        is CommandOutcome.Conflict -> outcome.reason
        is CommandOutcome.NotApplied -> outcome.reason
        is CommandOutcome.Failed -> outcome.error.message ?: outcome.error.javaClass.simpleName
        else -> outcome.toString()
    }
    return false
}

private const val CHAT_EMERGENCY_CONFIRM_TIMEOUT_MS = 30_000L

internal fun Conversation.withGeneratedTitle(title: String): Conversation = copy(title = title)

internal fun Conversation.withGeneratedSuggestions(suggestions: List<String>): Conversation =
    copy(chatSuggestions = suggestions)

private fun Conversation.latestAssistantNeedsFinalAnswer(): Boolean =
    currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
        ?.state == UIMessageState.INCOMPLETE_NO_VISIBLE_ANSWER

internal suspend fun runRegenerationTransaction(
    restore: suspend () -> Unit,
    operation: suspend () -> RunOutcome,
): RunOutcome {
    return try {
        val outcome = operation()
        if (outcome !is RunOutcome.Completed && outcome !is RunOutcome.WaitingApproval) {
            withContext(NonCancellable) { restore() }
        }
        outcome
    } catch (error: Throwable) {
        runCatching {
            withContext(NonCancellable) { restore() }
        }.exceptionOrNull()?.let(error::addSuppressed)
        throw error
    }
}

internal suspend fun <T> withCommandHeadlessScope(
    conversationId: Uuid,
    origin: CommandOrigin,
    control: GenerationRunControl? = null,
    block: suspend () -> T,
): T {
    if (origin != CommandOrigin.CRON) return block()
    me.rerere.rikkahub.data.ai.tools.HeadlessConversations.markTransient(conversationId)
    val released = AtomicBoolean(false)
    val release = {
        if (released.compareAndSet(false, true)) {
            me.rerere.rikkahub.data.ai.tools.HeadlessConversations.unmarkTransient(conversationId)
        }
    }
    val cancellationRegistration = control?.registerCancellationCallback(release)
    return try {
        block()
    } finally {
        cancellationRegistration?.close()
        release()
    }
}

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

/**
 * Append one applied steering audit message to Conversation JSON.
 *
 * Both persistent (yellow) and transient (purple) guidance stay visible after the run.
 * The command id is the exactly-once key: retries, process recovery, or duplicate runtime
 * callbacks return the original snapshot instead of adding a second history card.
 */
internal fun Conversation.withSteeringAuditMessage(
    note: me.rerere.rikkahub.data.ai.SteeringNote,
): Conversation {
    val alreadyStored = messageNodes.any { node ->
        node.messages.any { message ->
            message.annotations.any { annotation ->
                annotation is UIMessageAnnotation.Steering &&
                    annotation.commandId == note.commandId.toString()
            }
        }
    }
    if (alreadyStored) return this

    val message = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(note.text)),
        annotations = listOf(
            UIMessageAnnotation.Steering(
                commandId = note.commandId.toString(),
                persistent = note.historyMode ==
                    me.rerere.rikkahub.service.chat.SteeringHistoryMode.PERSISTENT,
            )
        ),
    ).toMessageNode()
    return copy(messageNodes = messageNodes + message)
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val memoryV2Coordinator: me.rerere.rikkahub.memory.MemoryV2Coordinator,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val toolApprovalPreferences: me.rerere.rikkahub.data.preferences.ToolApprovalPreferences,
    private val capabilityGrantRepository:
        me.rerere.rikkahub.data.capability.CapabilityGrantRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val workflowRepository: WorkflowRepository,
    private val conversationDeletionPolicy:
        me.rerere.rikkahub.data.repository.ConversationDeletionPolicy,
    private val secondUserSecretVault: me.rerere.rikkahub.security.SecondUserSecretVault,
    private val durableCommandQueue: DurableCommandQueue,
    private val secondUserApprovalLifecycle:
        me.rerere.rikkahub.data.execution.SecondUserApprovalLifecycle,
    private val toolExecutionGate: me.rerere.rikkahub.data.ai.ToolExecutionGate,
    private val toolRuntime: me.rerere.rikkahub.data.ai.execution.ToolRuntime,
    private val pluginToolCatalog: me.rerere.rikkahub.plugin.PluginToolCatalog,
    private val pluginHookBridge: me.rerere.rikkahub.plugin.PluginHookBridge,
    private val agentSafetySettings: me.rerere.rikkahub.data.ai.AgentSafetySettings,
    private val shizukuBridgeManager: me.rerere.rikkahub.privilege.ShizukuBridgeManager,
    private val workspaceProcessManager: me.rerere.workspace.WorkspaceProcessManager,
    private val structuredPrivilegedCommandExecutor:
        me.rerere.rikkahub.privilege.StructuredPrivilegedCommandExecutor? = null,
    private val subAgentExecutionProfileRegistry:
        me.rerere.rikkahub.subagent.SubAgentExecutionProfileRegistry,
    private val setupTransactionCoordinator:
        me.rerere.rikkahub.setup.SetupTransactionCoordinator,
    private val displayAutomationRuntime: me.rerere.rikkahub.display.DisplayAutomationRuntime? = null,
    private val toolExperienceRepository: me.rerere.rikkahub.toolcatalog.ToolExperienceRepository,
    private val toolShortcutRepository: me.rerere.rikkahub.toolcatalog.ToolShortcutRepository,
    private val secondUserAuthorityService: me.rerere.rikkahub.assistant.SecondUserAuthorityService,
    private val hostOperationDao: me.rerere.rikkahub.owner.db.HostOperationDao,
    private val secretPlaintextSessions: me.rerere.rikkahub.security.SecretPlaintextSessionManager,
    private val ephemeralToolResults: me.rerere.rikkahub.security.EphemeralToolResultStore,
    private val runtimeSecretRedactor: me.rerere.rikkahub.security.RuntimeSecretRedactor,
    private val assistantRemovalService: me.rerere.rikkahub.data.repository.AssistantRemovalService,
    private val persistentTtsLibrary: me.rerere.rikkahub.tts.PersistentTtsLibrary,
    private val workspaceManagedProcessStarter: me.rerere.rikkahub.execution.WorkspaceManagedProcessStarter,
    private val hostLocalServiceDao: me.rerere.rikkahub.owner.db.HostLocalServiceDao,
    private val ownerHttpClient: okhttp3.OkHttpClient,
    private val workflowActionRunner: me.rerere.rikkahub.workflow.execution.WorkflowActionRunner,
    private val doctorChecks: me.rerere.rikkahub.ui.pages.setting.doctor.DoctorChecks,
    private val executionConsistencyDoctor: me.rerere.rikkahub.diagnostics.ExecutionConsistencyDoctor,
    private val ownerLocalServiceSupervisor: me.rerere.rikkahub.owner.OwnerLocalServiceSupervisor,
    private val agentRunRepository: me.rerere.rikkahub.data.agentrun.AgentRunRepository,
    private val ownerServiceSpecStore: me.rerere.rikkahub.owner.OwnerServiceSpecStore,
    private val ownerTermuxServiceLauncher: me.rerere.rikkahub.owner.OwnerTermuxServiceLauncher,
    private val ownerOperationFingerprinter: me.rerere.rikkahub.owner.OwnerOperationFingerprinter,
) {
    fun onConversationVisible(conversationId: Uuid) {
        val session = secretPlaintextSessions.state.value as?
            me.rerere.rikkahub.security.SecretPlaintextSessionState.Open ?: return
        if (session.binding.conversationId != conversationId.toString()) {
            secretPlaintextSessions.close(
                me.rerere.rikkahub.security.SecretPlaintextSessionCloseReason.CONVERSATION_CHANGED,
            )
        }
    }

    private val conversationLibraryReader =
        me.rerere.rikkahub.data.ai.tools.ConversationLibraryReader(conversationRepo)
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构�?
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)
    private val privilegedActionGuard = me.rerere.rikkahub.privilege.DefaultPrivilegedActionGuard(
        context.packageName
    )
    private val hardDenyPolicy = me.rerere.rikkahub.privilege.DefaultHardDenyPolicy(
        context.packageName,
        privilegedActionGuard,
    )
    private val privilegedManagementBackend by lazy {
        me.rerere.rikkahub.privilege.HostCapabilityRegistry(
            backend = me.rerere.rikkahub.privilege.RepositoryPrivilegedManagementBackend(
                settingsStore = settingsStore,
                conversationRepository = conversationRepo,
                skillManager = skillManager,
                workspaceRepository = workspaceRepository,
                workflowRepository = workflowRepository,
                conversationDeletionPolicy = conversationDeletionPolicy,
                secretVault = secondUserSecretVault,
                onConversationDeleted = ::dropSession,
            ),
        )
    }
    private val ownerOperationGateway by lazy {
        val ownerTtsHandler = me.rerere.rikkahub.owner.OwnerTtsOperationHandler(
            settingsStore = settingsStore,
            vault = secondUserSecretVault,
            library = persistentTtsLibrary,
        )
        val ownerServiceHandler = me.rerere.rikkahub.owner.OwnerLocalServiceOperationHandler(
            dao = hostLocalServiceDao,
            manager = workspaceProcessManager,
            starter = workspaceManagedProcessStarter,
            workspaces = workspaceRepository,
            httpClient = ownerHttpClient,
            specStore = ownerServiceSpecStore,
            termux = ownerTermuxServiceLauncher,
        )
        val executor = me.rerere.rikkahub.owner.OwnerOperationExecutor(
            dao = hostOperationDao,
            handler = me.rerere.rikkahub.owner.CompositeOwnerOperationHandler(
                me.rerere.rikkahub.security.SecretOwnerOperationHandler(
                    sessions = secretPlaintextSessions,
                    ephemeralResults = ephemeralToolResults,
                    settingsStore = settingsStore,
                    vault = secondUserSecretVault,
                ),
                me.rerere.rikkahub.owner.OwnerSettingsOperationHandler(
                    context = context,
                    settingsStore = settingsStore,
                    conversations = conversationRepo,
                    assistantRemoval = assistantRemovalService,
                    providerManager = providerManager,
                    vault = secondUserSecretVault,
                ),
                ownerTtsHandler,
                me.rerere.rikkahub.owner.OwnerEmotionTtsOperationHandler(
                    settingsStore = settingsStore,
                    serviceHandler = ownerServiceHandler,
                    ttsHandler = ownerTtsHandler,
                ),
                ownerServiceHandler,
                me.rerere.rikkahub.owner.OwnerMcpOperationHandler(
                    settingsStore = settingsStore,
                    manager = mcpManager,
                    httpClient = ownerHttpClient,
                    vault = secondUserSecretVault,
                ),
                me.rerere.rikkahub.owner.OwnerSkillOperationHandler(
                    settingsStore = settingsStore,
                    skillManager = skillManager,
                    httpClient = ownerHttpClient,
                ),
                me.rerere.rikkahub.owner.OwnerWorkflowOperationHandler(
                    repository = workflowRepository,
                    actionRunner = workflowActionRunner,
                ),
                me.rerere.rikkahub.owner.OwnerUiOperationHandler(),
                me.rerere.rikkahub.owner.OwnerDoctorOperationHandler(
                    checks = doctorChecks,
                    executionDoctor = executionConsistencyDoctor,
                    operationDao = hostOperationDao,
                    serviceDao = hostLocalServiceDao,
                    serviceSupervisor = ownerLocalServiceSupervisor,
                    plaintextSessions = secretPlaintextSessions,
                ),
                me.rerere.rikkahub.owner.ExistingHostOwnerOperationHandler(
                    privilegedManagementBackend,
                ),
            ),
            isEmergencyStopActive = agentSafetySettings::isEmergencyStop,
            containsRuntimeSecret = runtimeSecretRedactor::containsKnownSecret,
            fingerprinter = ownerOperationFingerprinter,
        )
        me.rerere.rikkahub.owner.AgentRunOwnerOperationGateway(
            delegate = executor,
            operations = hostOperationDao,
            runs = agentRunRepository,
        )
    }

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val runtimes = ConcurrentHashMap<Uuid, ConversationRuntime>()
    private val sessionLifecycleLock = Any()
    private val commandSequences = ConcurrentHashMap<Uuid, AtomicLong>()
    /**
     * The tool origin of the current conversation run. Approval continuation commands are
     * intentionally INTERNAL, so retain the originating surface while the run is alive;
     * otherwise a remote approval could be misclassified as LocalChat.
     */
    private val activeToolOrigins = ConcurrentHashMap<Uuid, ToolCallOrigin>()
    private val _sessionsVersion = MutableStateFlow(0L)

    /**
     * Per-conversation mutex serialising state-mutating operations: handleToolApproval,
     * stopGeneration, the chunk-handling save path, and explicit DB writes. Without this
     * the audit reports identified multiple write races where a fresh approval mutation
     * gets clobbered by a concurrent write from a stale snapshot. Generation chunks
     * themselves are NOT held under this mutex �?only the persist boundaries.
     */

    /**
     * Hydrate the in-memory session for [conversationId] from disk if no authoritative
     * state has been installed yet. Used by entry points that may be hit
     * after a process restart with an empty session map �?without this they read an
     * empty Conversation, mutate it, and `saveConversation` then OVERWRITES the persisted
     * state with empty content (silent data loss). Idempotent and cheap after hydration.
     */
    suspend fun ensureHydrated(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        if (session.isHydrated) return
        val fromDb = conversationRepo.getConversationById(conversationId) ?: return
        session.hydrateIfNeeded(fromDb)
    }

    // 错误状�?
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update { it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution) }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成�?
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    // 前台状态管�?
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> _isForeground.value = true
            Lifecycle.Event.ON_STOP -> _isForeground.value = false
            else -> {}
        }
    }

    init {
        // 添加生命周期观察�?
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    fun cleanup() = runCatching {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        val (runtimesToClose, sessionsToClose) = synchronized(sessionLifecycleLock) {
            val runtimeSnapshot = runtimes.values.toList()
            val sessionSnapshot = sessions.values.toList()
            runtimes.clear()
            sessions.clear()
            activeToolOrigins.clear()
            runtimeSnapshot to sessionSnapshot
        }
        runtimesToClose.forEach { it.close() }
        sessionsToClose.forEach { it.cleanup() }
    }.onFailure {
        // Don't let a teardown hiccup escape, but don't swallow it silently either �?
        // a failure here can leave the lifecycle observer registered (slow leak).
        Log.w(TAG, "cleanup failed", it)
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession =
        synchronized(sessionLifecycleLock) {
            sessions.computeIfAbsent(conversationId) { id ->
                val settings = settingsStore.settingsFlow.value
                lateinit var createdSession: ConversationSession
                createdSession = ConversationSession(
                    id = id,
                    initial = Conversation.ofId(
                        id = id,
                        assistantId = settings.getCurrentAssistant().id,
                    ),
                    scope = appScope,
                    onIdle = { removeSession(it, createdSession) },
                    canEvict = { runtimes[id]?.hasRetainedWork != true },
                )
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
                createdSession
            }
        }

    private fun removeSession(
        conversationId: Uuid,
        expectedSession: ConversationSession,
    ) {
        val removed = synchronized(sessionLifecycleLock) {
            val session = sessions[conversationId] ?: return
            if (session !== expectedSession) {
                Log.d(TAG, "removeSession: ignored stale idle callback for $conversationId")
                return
            }
            val runtime = runtimes[conversationId]
            if (session.isInUse || runtime?.hasRetainedWork == true) {
                Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
                return
            }
            if (!sessions.remove(conversationId, session)) return
            val removedRuntime = runtime?.takeIf { runtimes.remove(conversationId, it) }
            activeToolOrigins.remove(conversationId)
            Triple(session, removedRuntime, sessions.size)
        }
        removed.second?.close()
        removed.first.cleanup()
        _sessionsVersion.value++
        Log.i(TAG, "removeSession: $conversationId (remaining: ${removed.third})")
    }

    private fun resolveToolOrigin(conversationId: Uuid, origin: CommandOrigin): ToolCallOrigin {
        val resolved = when (origin) {
            CommandOrigin.APP_UI -> ToolCallOrigin.LocalChat
            CommandOrigin.TELEGRAM -> ToolCallOrigin.Telegram
            CommandOrigin.WEB_API -> ToolCallOrigin.WebServer
            CommandOrigin.CRON -> ToolCallOrigin.TrustedWorkflow
            CommandOrigin.SYSTEM_ASSISTANT -> ToolCallOrigin.SystemAssistant
            CommandOrigin.SYSTEM_ASSISTANT_KEYGUARD -> ToolCallOrigin.SystemAssistantKeyguard
            CommandOrigin.QUICK_CAPTURE -> ToolCallOrigin.QuickCapture
            CommandOrigin.PET_INTERACTION -> ToolCallOrigin.PetInteraction
            CommandOrigin.PET_HANDOFF_CONFIRMED -> ToolCallOrigin.PetHandoffConfirmed
            CommandOrigin.PET_HANDOFF_AUTO -> ToolCallOrigin.PetHandoffAuto
            // Approval continuation is an internal command, but it must retain the
            // surface that created the pending tool call. If no in-memory provenance is
            // available (for example after process death), fail closed as a workflow.
            CommandOrigin.INTERNAL -> activeToolOrigins[conversationId]
                ?: ToolCallOrigin.TrustedWorkflow
        }
        activeToolOrigins[conversationId] = resolved
        return resolved
    }

    /**
     * Origins and principals are separate: a remote request can carry the same assistant id as
     * a local conversation, but it never becomes that assistant's local second-user profile.
     */
    private fun capabilitySubjectFor(
        assistant: Assistant,
        conversationId: Uuid,
        origin: ToolCallOrigin,
        privilege: me.rerere.rikkahub.privilege.PrivilegedSessionContext? = null,
    ): me.rerere.rikkahub.data.capability.CapabilitySubject {
        if (privilege?.isPrivileged == true && privilege.expandLocalTools) {
            return me.rerere.rikkahub.data.capability.CapabilitySubject(
                id = requireNotNull(privilege.authoritySubjectId) {
                    "second_user_authority_snapshot_missing"
                },
                type = me.rerere.rikkahub.data.capability.SubjectType.LOCAL_SECOND_USER,
                privilegedConversationId = privilege.conversationId.toString(),
            )
        }
        val type = when (origin) {
            ToolCallOrigin.Telegram -> me.rerere.rikkahub.data.capability.SubjectType.TELEGRAM
            ToolCallOrigin.WebServer -> me.rerere.rikkahub.data.capability.SubjectType.WEB
            ToolCallOrigin.MCP -> me.rerere.rikkahub.data.capability.SubjectType.MCP
            ToolCallOrigin.ExternalIntent ->
                me.rerere.rikkahub.data.capability.SubjectType.EXTERNAL_AUTOMATION
            // Workflow snapshots are introduced independently; do not claim a grant exists
            // until the authoring path freezes it. Existing local workflows retain their
            // current gate while this migration is rolled out.
            ToolCallOrigin.TrustedWorkflow,
            ToolCallOrigin.LocalChat,
            ToolCallOrigin.SystemAssistant,
            ToolCallOrigin.SystemAssistantKeyguard,
            ToolCallOrigin.QuickCapture,
            ToolCallOrigin.PetInteraction,
            ToolCallOrigin.PetHandoffConfirmed,
            ToolCallOrigin.PetHandoffAuto,
            -> me.rerere.rikkahub.data.capability.SubjectType.LOCAL_ASSISTANT
        }
        val id = if (type == me.rerere.rikkahub.data.capability.SubjectType.LOCAL_ASSISTANT) {
            assistant.id.toString()
        } else {
            "${type.name.lowercase()}:${assistant.id}:$conversationId"
        }
        return me.rerere.rikkahub.data.capability.CapabilitySubject(id = id, type = type)
    }

    /**
     * The authority registry is intentionally fail-closed, but it used to be populated only by
     * an asynchronous app-start collector. A user who sent the first message immediately after a
     * cold start could therefore be demoted to an ordinary session and receive approval cards.
     * Admission re-reads the authoritative DataStore state and has no effect unless this exact
     * assistant/conversation is active, unlocked, and entered through a trusted local surface.
     */
    private suspend fun refreshSecondUserAuthorityForInvocation(
        assistant: Assistant,
        conversation: Conversation,
        origin: ToolCallOrigin,
    ) {
        if (origin !in me.rerere.rikkahub.data.ai.InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER) return
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val unlocked = keyguard?.let { !it.isDeviceLocked && !it.isKeyguardLocked } ?: true
        secondUserAuthorityService.admit(
            assistantId = assistant.id,
            conversationId = conversation.id,
            origin = origin,
            deviceUnlocked = unlocked,
        )
    }

    private fun getOrCreateRuntime(conversationId: Uuid): ConversationRuntime =
        synchronized(sessionLifecycleLock) {
            val session = getOrCreateSession(conversationId)
            runtimes.computeIfAbsent(conversationId) { id ->
                lateinit var createdRuntime: ConversationRuntime
                createdRuntime = ConversationRuntime(
                    appScope = appScope,
                    conversationId = id,
                    dispatchers = DispatcherProvider(),
                    executor = RuntimeCommandExecutor { envelope, control ->
                        executeRuntimeCommand(envelope, control)
                    },
                    hydrator = RuntimeHydrator {
                        ensureHydrated(id)
                    },
                    repairer = object : me.rerere.rikkahub.service.chat.RuntimeRepairer {
                        override suspend fun repair(
                            runId: Uuid,
                            reason: ToolCancelReason,
                        ): me.rerere.rikkahub.service.chat.InterruptCleanupResult {
                            finishInterruptedPendingTools(id, emptyMap())
                            return me.rerere.rikkahub.service.chat.InterruptCleanupResult.Completed
                        }

                        override suspend fun repair(
                            runId: Uuid,
                            reason: ToolCancelReason,
                            toolCancellationResults: Map<String, CancelRequestResult>,
                        ): me.rerere.rikkahub.service.chat.InterruptCleanupResult {
                            finishInterruptedPendingTools(id, toolCancellationResults)
                            return me.rerere.rikkahub.service.chat.InterruptCleanupResult.Completed
                        }
                    },
                    durableQueue = durableCommandQueue,
                    onBecameIdle = { runtimeId ->
                        synchronized(sessionLifecycleLock) {
                            if (runtimes[runtimeId] === createdRuntime) {
                                sessions[runtimeId]?.requestIdleCheck()
                            }
                        }
                    },
                    onRunJobChanged = { job -> session.attachRunJob(job) },
                    onPersistSteering = { note ->
                        val current = session.state.value
                        val updated = current.withSteeringAuditMessage(note)
                        if (updated !== current) {
                            saveConversation(id, updated)
                        }
                    },
                    onCancellationTimeout = { envelope, error ->
                        addError(
                            error = error,
                            conversationId = id,
                            title = context.getString(
                                if (envelope.command is me.rerere.rikkahub.service.chat.InterruptRegenerateCommand) {
                                    R.string.error_title_regenerate_message
                                } else {
                                    R.string.error_title_operation
                                }
                            ),
                        )
                    },
                )
                createdRuntime
            }
        }

    suspend fun submitCommand(
        conversationId: Uuid,
        command: ChatCommand,
        origin: CommandOrigin,
    ): SubmitResult {
        return submitCommand(
            conversationId = conversationId,
            command = command,
            origin = origin,
            dedupeKey = null,
            expiresAt = null,
            dependencies = emptyList(),
        )
    }

    private suspend fun submitCommand(
        conversationId: Uuid,
        command: ChatCommand,
        origin: CommandOrigin,
        dedupeKey: String?,
        expiresAt: kotlin.time.Instant?,
        dependencies: List<me.rerere.rikkahub.service.chat.CommandDependency>,
    ): SubmitResult = submitCommandTracked(
        conversationId = conversationId,
        command = command,
        origin = origin,
        dedupeKey = dedupeKey,
        expiresAt = expiresAt,
        dependencies = dependencies,
    ).submission

    private suspend fun submitCommandTracked(
        conversationId: Uuid,
        command: ChatCommand,
        origin: CommandOrigin,
        dedupeKey: String?,
        expiresAt: kotlin.time.Instant?,
        dependencies: List<me.rerere.rikkahub.service.chat.CommandDependency>,
        commandId: Uuid? = null,
    ): TrackedCommandSubmission {
        require(command !is me.rerere.rikkahub.service.chat.EmergencyCommand) {
            "EmergencyCommand must use submitEmergency()"
        }
        me.rerere.rikkahub.service.chat.emergencyStopCommandBlockReason(
            active = agentSafetySettings.emergencyStopFlow.first(),
            command = command,
        )?.let { reason -> return rejectedTrackedCommand(reason) }
        me.rerere.rikkahub.service.chat.SystemAssistantCommandSecurityPolicy
            .commandBlockReason(origin, command)
            ?.let { reason -> return rejectedTrackedCommand(reason) }
        val resolvedCommandId = commandId ?: Uuid.random()
        if (origin == CommandOrigin.SYSTEM_ASSISTANT && command !is StopCommand) {
            val validation = me.rerere.rikkahub.service.chat.SystemAssistantCommandSecurityPolicy
                .validateAdmissionTarget(
                    command = command,
                    conversationId = conversationId,
                    settings = settingsStore.settingsFlow.first(),
                    persistedConversation = conversationRepo.getConversationById(conversationId),
                )
            if (validation is me.rerere.rikkahub.service.chat.SystemAssistantTargetValidation.Invalid) {
                return rejectedTrackedCommand(validation.reason)
            }
        }
        if (origin == CommandOrigin.QUICK_CAPTURE && command !is StopCommand) {
            val validation = me.rerere.rikkahub.service.chat.QuickCaptureCommandSecurityPolicy
                .validateAdmission(
                    commandId = resolvedCommandId,
                    command = command,
                    conversationId = conversationId,
                    settings = settingsStore.settingsFlow.first(),
                    persistedConversation = conversationRepo.getConversationById(conversationId),
                )
            if (validation is me.rerere.rikkahub.service.chat.QuickCaptureTargetValidation.Invalid) {
                return rejectedTrackedCommand(validation.reason)
            }
        }
        val envelope = CommandEnvelope(
            id = resolvedCommandId,
            conversationId = conversationId,
            command = command,
            origin = origin,
            sequence = commandSequences.getOrPut(conversationId) { AtomicLong() }.incrementAndGet(),
            dedupeKey = dedupeKey,
            expiresAt = expiresAt,
            dependencies = dependencies,
        )
        return TrackedCommandSubmission(
            submission = getOrCreateRuntime(conversationId).enqueueEnvelope(envelope),
            outcome = envelope.result,
        )
    }

    suspend fun submitUserMessage(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        origin: CommandOrigin = CommandOrigin.APP_UI,
        dedupeKey: String? = null,
        expiresAt: kotlin.time.Instant? = null,
        annotations: List<UIMessageAnnotation> = emptyList(),
    ): SubmitResult = submitUserMessageTracked(
        conversationId = conversationId,
        content = content,
        answer = answer,
        origin = origin,
        dedupeKey = dedupeKey,
        expiresAt = expiresAt,
        annotations = annotations,
    ).submission

    suspend fun <T> runPetInteraction(
        conversationId: Uuid,
        block: suspend () -> T,
    ): me.rerere.rikkahub.service.chat.PetInteractionSlotResult<T> =
        getOrCreateRuntime(conversationId).runPetInteraction(block)

    internal suspend fun submitUserMessageTracked(
        conversationId: Uuid,
        content: List<UIMessagePart>,
        answer: Boolean = true,
        origin: CommandOrigin = CommandOrigin.APP_UI,
        dedupeKey: String? = null,
        expiresAt: kotlin.time.Instant? = null,
        annotations: List<UIMessageAnnotation> = emptyList(),
        assistantIdSnapshot: Uuid? = null,
        commandId: Uuid? = null,
        quickCaptureSessionId: Uuid? = null,
    ): TrackedCommandSubmission {
        if (content.isEmptyInputMessage()) {
            return TrackedCommandSubmission(
                submission = SubmitResult.Rejected("Empty message"),
                outcome = CompletableDeferred(CommandOutcome.Rejected("Empty message")),
            )
        }
        return submitCommandTracked(
            conversationId = conversationId,
            command = SendMessageCommand(
                content = RawUserContent(content, answer, annotations),
                assistantIdSnapshot = assistantIdSnapshot,
                quickCaptureSessionId = quickCaptureSessionId,
            ),
            origin = origin,
            dedupeKey = dedupeKey,
            expiresAt = expiresAt,
            dependencies = emptyList(),
            commandId = commandId,
        )
    }

    suspend fun submitSteer(
        conversationId: Uuid,
        text: String,
        scope: SteeringScope = SteeringScope.REMAINDER_OF_RUN,
        applyPolicy: me.rerere.rikkahub.service.chat.SteeringApplyPolicy =
            me.rerere.rikkahub.service.chat.SteeringApplyPolicy.AFTER_CHECKPOINT,
        origin: CommandOrigin = CommandOrigin.APP_UI,
        historyMode: me.rerere.rikkahub.service.chat.SteeringHistoryMode =
            me.rerere.rikkahub.service.chat.SteeringHistoryMode.TRANSIENT,
    ): SubmitResult {
        if (text.isBlank()) return SubmitResult.Rejected("Steering text cannot be blank")
        return submitCommand(
            conversationId = conversationId,
            command = SteerCommand(
                text = text,
                scope = scope,
                applyPolicy = applyPolicy,
                historyMode = historyMode,
            ),
            origin = origin,
        )
    }

    fun updateSteeringHistoryMode(
        conversationId: Uuid,
        commandId: Uuid,
        historyMode: me.rerere.rikkahub.service.chat.SteeringHistoryMode,
    ): Boolean = getOrCreateRuntime(conversationId)
        .updateSteeringHistoryMode(commandId, historyMode)

    suspend fun cancelCurrentTool(
        conversationId: Uuid,
        toolCallId: String,
        origin: CommandOrigin = CommandOrigin.APP_UI,
    ): SubmitResult {
        if (toolCallId.isBlank() || toolCallId.length > 256) {
            return SubmitResult.Rejected("Invalid tool call id")
        }
        return submitCommand(
            conversationId = conversationId,
            command = CancelCurrentToolCommand(toolCallId),
            origin = origin,
        )
    }

    fun submitInterrupt(
        conversationId: Uuid,
        replacement: List<UIMessagePart>,
        answer: Boolean = true,
        origin: CommandOrigin = CommandOrigin.APP_UI,
    ): SubmitResult = submitEmergency(
        conversationId = conversationId,
        command = InterruptCommand(SendMessageCommand(RawUserContent(replacement, answer))),
        origin = origin,
    )

    suspend fun resumeQueue(conversationId: Uuid, origin: CommandOrigin = CommandOrigin.APP_UI): SubmitResult =
        submitCommand(conversationId, ResumeQueueCommand(), origin)

    suspend fun clearPendingQueue(conversationId: Uuid, reason: String = "Cleared by user"): SubmitResult =
        submitCommand(conversationId, ClearPendingQueueCommand(reason), CommandOrigin.APP_UI)

    suspend fun cancelQueuedCommand(conversationId: Uuid, commandId: Uuid): SubmitResult =
        submitCommand(conversationId, CancelQueuedCommand(commandId), CommandOrigin.APP_UI)

    suspend fun cancelSteering(conversationId: Uuid, commandId: Uuid): SubmitResult =
        submitCommand(conversationId, CancelSteeringCommand(commandId), CommandOrigin.APP_UI)

    suspend fun updateQueuedMessage(
        conversationId: Uuid,
        commandId: Uuid,
        content: RawUserContent,
    ): SubmitResult = submitCommand(
        conversationId,
        UpdateQueuedMessageCommand(commandId, content),
        CommandOrigin.APP_UI,
    )

    suspend fun promoteQueuedMessageToSteering(
        conversationId: Uuid,
        commandId: Uuid,
    ): SubmitResult = submitCommand(
        conversationId,
        PromoteQueuedMessageToSteeringCommand(commandId),
        CommandOrigin.APP_UI,
    )

    fun submitEmergency(
        conversationId: Uuid,
        command: me.rerere.rikkahub.service.chat.EmergencyCommand,
        origin: CommandOrigin,
    ): SubmitResult {
        val envelope = CommandEnvelope(
            conversationId = conversationId,
            command = command,
            origin = origin,
            sequence = commandSequences.getOrPut(conversationId) { AtomicLong() }.incrementAndGet(),
        )
        return getOrCreateRuntime(conversationId).replaceEmergencyEnvelope(envelope)
    }

    /**
     * Stops only the Runtime instances that already exist. This never creates a session while
     * emergency stop is active, and the captured Runtime identity prevents a stale callback
     * from affecting a later replacement instance.
     */
    suspend fun stopAllActiveRuntimesForEmergency(): ChatEmergencyStopResult {
        secondUserApprovalLifecycle.invalidateAllPending(
            reasonCode = "emergency_stop",
            orphaned = false,
            source = me.rerere.rikkahub.data.execution.ExecutionStateSource.POLICY,
        ).forEach { conversation ->
            if (sessions.containsKey(conversation.id)) {
                updateConversation(conversation.id, conversation)
            }
        }
        val targets = synchronized(sessionLifecycleLock) {
            runtimes.entries.map { (conversationId, runtime) ->
                val stopEnvelope = CommandEnvelope(
                    conversationId = conversationId,
                    command = StopCommand(pauseQueue = true),
                    origin = CommandOrigin.INTERNAL,
                    sequence = commandSequences
                        .getOrPut(conversationId) { AtomicLong() }
                        .incrementAndGet(),
                )
                val clearEnvelope = CommandEnvelope(
                    conversationId = conversationId,
                    command = ClearPendingQueueCommand("Emergency stop"),
                    origin = CommandOrigin.INTERNAL,
                    sequence = commandSequences
                        .getOrPut(conversationId) { AtomicLong() }
                        .incrementAndGet(),
                )
                ChatEmergencyRuntimeTarget(
                    conversationId = conversationId,
                    submitStop = {
                        ChatEmergencyCommandSubmission(
                            submission = runtime.replaceEmergencyEnvelope(stopEnvelope),
                            outcome = stopEnvelope.result,
                        )
                    },
                    clearQueue = {
                        ChatEmergencyCommandSubmission(
                            submission = runtime.enqueueEnvelope(clearEnvelope),
                            outcome = clearEnvelope.result,
                        )
                    },
                )
            }
        }
        return stopChatRuntimeSnapshot(targets)
    }

    /**
     * Force-drop the in-memory session for [conversationId] regardless of refcount /
     * generation status. Used by /new in TelegramBotService to make sure a straggler
     * coroutine writing back to the session can't resurrect the conversation after the
     * user reset it. Safe to call when no session exists �?no-op.
     */
    fun dropSession(conversationId: Uuid) {
        val removed = synchronized(sessionLifecycleLock) {
            val runtime = runtimes.remove(conversationId)
            val session = sessions.remove(conversationId)
            activeToolOrigins.remove(conversationId)
            Triple(session, runtime, sessions.size)
        }
        removed.second?.close()
        removed.first?.cleanup()
        if (removed.first != null || removed.second != null) {
            _sessionsVersion.value++
            Log.i(TAG, "dropSession: $conversationId (remaining: ${removed.third})")
        }
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访�?----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
    }

    fun getRuntimeStateFlow(conversationId: Uuid): StateFlow<me.rerere.rikkahub.service.chat.RuntimeState> =
        getOrCreateRuntime(conversationId).runtimeState

    fun getQueueStatusFlow(conversationId: Uuid): StateFlow<me.rerere.rikkahub.service.chat.QueueStatus> =
        getOrCreateRuntime(conversationId).queueStatus

    fun getQueuedMessagesFlow(conversationId: Uuid): StateFlow<List<QueuedMessageUiEntry>> =
        getOrCreateRuntime(conversationId).queuedMessages

    fun getSteeringStatusFlow(conversationId: Uuid): StateFlow<Map<Uuid, me.rerere.rikkahub.data.ai.SteeringState>> =
        getOrCreateRuntime(conversationId).steeringStatus

    fun getSteeringEntriesFlow(
        conversationId: Uuid,
    ): StateFlow<Map<Uuid, me.rerere.rikkahub.service.chat.SteeringUiEntry>> =
        getOrCreateRuntime(conversationId).steeringEntries

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对�?----

    suspend fun initializeConversation(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        if (!session.isHydrated) {
            val conversation = conversationRepo.getConversationById(conversationId)
            if (conversation != null) {
                session.hydrateIfNeeded(conversation)
            } else {
                val currentSettings = settingsStore.settingsFlowRaw.first()
                val assistant = currentSettings.getCurrentAssistant()
                session.hydrateIfNeeded(
                    Conversation.ofId(
                        id = conversationId,
                        assistantId = assistant.id,
                        newConversation = true,
                    ).updateCurrentMessages(assistant.presetMessages)
                )
            }
        }
        settingsStore.updateAssistant(session.state.value.assistantId)
    }

    // ---- 发送消�?----

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return
        // Ordinary sends are in-memory FIFO commands.  Explicit interrupt UI
        // actions use submitEmergency(InterruptCommand) instead.
        appScope.launch {
            submitUserMessage(conversationId, content, answer, CommandOrigin.APP_UI)
        }
    }

    private suspend fun executeRuntimeCommand(
        envelope: CommandEnvelope<out ChatCommand>,
        control: GenerationRunControl,
    ): RunOutcome {
        val command = envelope.command
        me.rerere.rikkahub.service.chat.emergencyStopCommandBlockReason(
            active = agentSafetySettings.emergencyStopFlow.first(),
            command = command,
        )?.let { reason -> return RunOutcome.Rejected(reason) }
        me.rerere.rikkahub.service.chat.SystemAssistantCommandSecurityPolicy
            .commandBlockReason(envelope.origin, command)
            ?.let { reason -> return RunOutcome.Rejected(reason) }

        val acceptedSystemAssistantTarget = if (
            envelope.origin == CommandOrigin.SYSTEM_ASSISTANT && command !is StopCommand
        ) {
            when (val validation =
                me.rerere.rikkahub.service.chat.SystemAssistantCommandSecurityPolicy
                    .validateAcceptedTarget(
                        command = command,
                        conversationId = envelope.conversationId,
                        settings = settingsStore.settingsFlow.first(),
                        persistedConversation = conversationRepo.getConversationById(
                            envelope.conversationId,
                        ),
                    )
            ) {
                is me.rerere.rikkahub.service.chat.SystemAssistantTargetValidation.Invalid ->
                    return RunOutcome.Rejected(validation.reason)
                is me.rerere.rikkahub.service.chat.SystemAssistantTargetValidation.Valid -> validation
            }
        } else {
            null
        }
        val acceptedQuickCaptureTarget = if (
            envelope.origin == CommandOrigin.QUICK_CAPTURE && command !is StopCommand
        ) {
            when (val validation = me.rerere.rikkahub.service.chat.QuickCaptureCommandSecurityPolicy
                .validateAccepted(
                    command = command,
                    conversationId = envelope.conversationId,
                    settings = settingsStore.settingsFlow.first(),
                    persistedConversation = conversationRepo.getConversationById(envelope.conversationId),
                )
            ) {
                is me.rerere.rikkahub.service.chat.QuickCaptureTargetValidation.Invalid ->
                    return RunOutcome.Rejected(validation.reason)
                is me.rerere.rikkahub.service.chat.QuickCaptureTargetValidation.Valid -> validation
            }
        } else {
            null
        }
        val acceptedAssistantSnapshot = acceptedSystemAssistantTarget?.assistant ?: acceptedQuickCaptureTarget?.assistant

        return when (command) {
            is PetDialogueCommand -> RunOutcome.Rejected("pet_dialogue_command_is_memory_only")
            is SendMessageCommand -> executeSendMessageLegacy(
                commandId = envelope.id,
                origin = envelope.origin,
                conversationId = envelope.conversationId,
                content = command.content,
                control = control,
                acceptedAssistantSnapshot = acceptedAssistantSnapshot,
            )

            is InterruptCommand -> executeSendMessageLegacy(
                commandId = envelope.id,
                origin = envelope.origin,
                conversationId = envelope.conversationId,
                content = command.replacement.content,
                control = control,
                acceptedAssistantSnapshot = acceptedAssistantSnapshot,
            )

            is InterruptRegenerateCommand -> executeRegenerateInline(
                envelope.conversationId,
                envelope.origin,
                command.regeneration,
                control,
            )

            is ToolApprovalCommand -> executeToolApprovalInline(
                conversationId = envelope.conversationId,
                command = command,
                control = control,
                origin = envelope.origin,
                envelopeId = envelope.id,
            )

            is RegenerateCommand -> executeRegenerateInline(
                envelope.conversationId,
                envelope.origin,
                command,
                control,
            )

            is ResumeAfterApprovalCommand -> {
                handleMessageComplete(
                    envelope.conversationId,
                    origin = envelope.origin,
                    runControl = control,
                )
                pendingToolIds(envelope.conversationId).takeIf { it.isNotEmpty() }
                    ?.let { RunOutcome.WaitingApproval(it) }
                    ?: RunOutcome.Completed()
            }

            is NormalCommand -> RunOutcome.Rejected("Unsupported normal command: ${command::class.simpleName}")
            is StopCommand -> RunOutcome.Stopped(me.rerere.rikkahub.service.chat.InterruptCleanupResult.Completed)
            is me.rerere.rikkahub.service.chat.SteerCommand -> RunOutcome.Completed()
            is me.rerere.rikkahub.service.chat.CancelCurrentToolCommand -> {
                val request = control.requestCancelTool(
                    command.toolCallId,
                    me.rerere.rikkahub.data.ai.tools.ToolCancelReason(
                        "User cancelled tool ${command.toolCallId}",
                    ),
                )
                if (request is me.rerere.rikkahub.data.ai.tools.CancelRequestResult.NotFound) {
                    RunOutcome.Rejected("Tool call not found")
                } else {
                    val termination = control.awaitToolTermination(
                        command.toolCallId,
                        2.seconds,
                    )
                    if (termination ==
                        me.rerere.rikkahub.data.ai.tools.ToolTerminationState.StoppedConfirmed
                    ) {
                        RunOutcome.Completed()
                    } else {
                        RunOutcome.Rejected("Tool termination state is unknown")
                    }
                }
            }
        }
    }

    private suspend fun executeSendMessageLegacy(
        commandId: Uuid,
        origin: CommandOrigin,
        conversationId: Uuid,
        content: RawUserContent,
        control: GenerationRunControl,
        acceptedAssistantSnapshot: Assistant?,
    ): RunOutcome = withCommandHeadlessScope(conversationId, origin, control) {
        executeSendMessageScoped(
            commandId = commandId,
            origin = origin,
            conversationId = conversationId,
            content = content,
            control = control,
            acceptedAssistantSnapshot = acceptedAssistantSnapshot,
        )
    }

    private suspend fun executeSendMessageScoped(
        commandId: Uuid,
        origin: CommandOrigin,
        conversationId: Uuid,
        content: RawUserContent,
        control: GenerationRunControl,
        acceptedAssistantSnapshot: Assistant?,
    ): RunOutcome {
        try {
            val session = getOrCreateSession(conversationId)
            val targetBeforeMutation = session.state.value
            if (acceptedAssistantSnapshot != null &&
                (targetBeforeMutation.id != conversationId ||
                    targetBeforeMutation.assistantId != acceptedAssistantSnapshot.id)
            ) {
                return RunOutcome.Rejected(
                    "The accepted assistant target no longer matches this conversation.",
                )
            }
            finishInterruptedPendingTools(conversationId)
            val currentConversation = session.state.value
            val settings = settingsStore.settingsFlow.first()
            val assistant = acceptedAssistantSnapshot
                ?: settings.getAssistantById(currentConversation.assistantId)
                ?: settings.getCurrentAssistant()
            val processedContent = preprocessUserInputParts(content.parts, assistant)
            val fastPath = if (content.answer) {
                fastPathRouter.resolve(
                    FastPathContext(
                        commandId = commandId,
                        conversation = currentConversation,
                        content = processedContent,
                        origin = origin,
                        assistant = assistant,
                    )
                )
            } else FastPathDecision.NotMatched
            val fastPathPlan = buildFastPathCommitPlan(processedContent, fastPath)
            if (fastPathPlan is FastPathCommitPlan.Rejected) return RunOutcome.Rejected(fastPathPlan.reason)
            val userContent = when (fastPathPlan) {
                is FastPathCommitPlan.Handled -> fastPathPlan.userContent
                is FastPathCommitPlan.ContinueToModel -> fastPathPlan.userContent
                is FastPathCommitPlan.NotMatched -> fastPathPlan.userContent
                is FastPathCommitPlan.Rejected -> emptyList()
            }
            val userMessage = UIMessage(
                role = MessageRole.USER,
                parts = userContent,
                annotations = content.annotations,
            ).toMessageNode()
            val responseCorrelationAnnotation = content.annotations
                .filter { it.isResponseCorrelation() }
                .singleOrNull()
            val withUser = currentConversation.copy(
                messageNodes = currentConversation.messageNodes + userMessage,
            )
            when (fastPathPlan) {
                is FastPathCommitPlan.Handled -> {
                    saveConversation(
                        conversationId,
                        withUser.copy(
                            messageNodes = withUser.messageNodes + UIMessage(
                                role = MessageRole.ASSISTANT,
                                parts = fastPathPlan.assistantContent,
                                annotations = listOfNotNull(responseCorrelationAnnotation),
                            ).toMessageNode()
                        )
                    )
                    me.rerere.rikkahub.skills.FastPathRouterLog.record(
                        me.rerere.rikkahub.skills.FastPathRouterLog.Entry(
                            whenMs = System.currentTimeMillis(),
                            intent = "handled",
                            toolName = "fast_path",
                            userText = processedContent.filterIsInstance<UIMessagePart.Text>()
                                .joinToString(" ") { it.text }.take(120),
                            resultPreview = fastPathPlan.assistantContent.joinToString { it.toString() }.take(200),
                            skippedLlm = true,
                        )
                    )
                }
                else -> {
                    saveConversation(conversationId, withUser)
                    if (content.answer) {
                        // Must surface generation failures as RunOutcome.Failed. Swallowing them
                        // (propagateFailure=false) marks the durable command COMPLETED after only
                        // the user message is saved — UI shows loading then silence with no reply.
                        handleMessageComplete(
                            conversationId,
                            origin = origin,
                            runControl = control,
                            activeCommandId = commandId,
                            acceptedAssistantSnapshot = acceptedAssistantSnapshot,
                            responseCorrelationAnnotation = responseCorrelationAnnotation,
                            propagateFailure = true,
                        )
                    }
                }
            }
            _generationDoneFlow.emit(conversationId)
            return pendingToolIds(conversationId).takeIf { it.isNotEmpty() }
                ?.let { RunOutcome.WaitingApproval(it) }
                ?: if (getConversationFlow(conversationId).value.latestAssistantNeedsFinalAnswer()) {
                    RunOutcome.Failed(
                        IllegalStateException(
                            "The model did not produce a visible final answer after recovery.",
                        ),
                    )
                } else {
                    RunOutcome.Completed()
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            return RunOutcome.Failed(e)
        }
    }

    private fun pendingToolIds(conversationId: Uuid): Set<String> =
        getConversationFlow(conversationId).value.messageNodes
            .flatMap { it.messages }
            .flatMap { message -> message.parts }
            .filterIsInstance<UIMessagePart.Tool>()
            .filter { it.isPending }
            .mapNotNull { it.toolCallId }
            .toSet()

    /**
     * Phase 16 �?fast-path router entry. Returns `true` if the router successfully handled
     * the turn (synthesised an assistant message and stored it) so the caller knows to skip
     * the normal LLM dispatch. Returns `false` to fall through.
     */

    private val fastPathRouter = FastPathRouter { context ->
        if (me.rerere.rikkahub.data.ai.tools.HeadlessConversations.isHeadless(context.conversation.id)) {
            return@FastPathRouter FastPathDecision.NotMatched
        }
        if (!context.assistant.fastPathRouterEnabled) return@FastPathRouter FastPathDecision.NotMatched
        val userText = context.content.filterIsInstance<UIMessagePart.Text>()
            .joinToString(" ") { it.text }.trim()
        if (userText.isBlank()) return@FastPathRouter FastPathDecision.NotMatched
        val match = me.rerere.rikkahub.skills.FastPathRouter.route(userText)
            ?: return@FastPathRouter FastPathDecision.NotMatched
        val tools = localTools.getTools(
            context.assistant.localTools,
            me.rerere.rikkahub.data.ai.tools.ToolInvocationContext(
                callerAssistantId = context.assistant.id.toString(),
                callerConversationId = context.conversation.id.toString(),
                callerRunId = context.commandId.toString(),
                callerWorkspaceId = context.assistant.workspaceId?.toString(),
                callOrigin = resolveToolOrigin(context.conversation.id, context.origin),
                isHeadless = false,
            ),
        )
        val tool = tools.firstOrNull { it.name == match.toolName }
            ?: return@FastPathRouter FastPathDecision.NotMatched
        val hardlineReason = me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
            .checkTool(match.toolName, match.args.toString())
        if (hardlineReason != null) return@FastPathRouter FastPathDecision.NotMatched
        val rendered = try {
            val callOrigin = resolveToolOrigin(context.conversation.id, context.origin)
            refreshSecondUserAuthorityForInvocation(
                assistant = context.assistant,
                conversation = context.conversation,
                origin = callOrigin,
            )
            val privilege = me.rerere.rikkahub.privilege.DefaultPrivilegedSessionResolver.resolve(
                assistant = context.assistant,
                conversation = context.conversation,
                origin = callOrigin,
            )
            val capabilitySubject = capabilitySubjectFor(
                assistant = context.assistant,
                conversationId = context.conversation.id,
                origin = callOrigin,
                privilege = privilege,
            )
            val runtimeResult = toolRuntime.execute(
                me.rerere.rikkahub.data.ai.execution.ToolExecutionPlanRequest(
                    toolCallId = "fast-${context.commandId}",
                    toolName = tool.name,
                    args = match.args,
                    executionContext = me.rerere.rikkahub.data.ai.tools.ToolExecutionContext(
                        runId = context.commandId,
                        conversationId = context.conversation.id,
                        assistantId = context.assistant.id.toString(),
                        callOrigin = callOrigin,
                        capabilitySubject = capabilitySubject,
                        selectedPrivilegedConversation = privilege.isPrivileged,
                    ),
                    startableTool = null,
                    legacyExecute = { input -> tool.execute(input.jsonObject) },
                    runControl = null,
                    wallClockBudgetMs = FAST_PATH_TOOL_BUDGET_MS,
                    preExecutionGate = {
                        when (val gate = toolExecutionGate.evaluate(
                            toolName = tool.name,
                            origin = callOrigin,
                            conversationId = context.conversation.id,
                            commandId = context.commandId,
                            arguments = match.args,
                            capabilitySubject = capabilitySubject,
                            selectedPrivilegedConversation = privilege.isPrivileged,
                            unrestrictedOverride = false,
                        )) {
                            me.rerere.rikkahub.data.ai.ToolExecutionGate.GateResult.Allowed ->
                                me.rerere.rikkahub.data.ai.execution.ToolPreExecutionDecision.Allow
                            is me.rerere.rikkahub.data.ai.ToolExecutionGate.GateResult.Denied ->
                                me.rerere.rikkahub.data.ai.execution.ToolPreExecutionDecision.Deny(
                                    errorCode = "tool_blocked",
                                    reason = gate.reason,
                                )
                        }
                    },
                )
            )
            val out = runtimeResult.output
            val rawText = out.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            val parsed = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(rawText).jsonObject
            }.getOrNull()
            val formatted = if (match.format != null && parsed != null) {
                runCatching { match.format.invoke(parsed) }.getOrNull()
            } else null
            formatted?.takeIf { it.isNotBlank() } ?: rawText
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            me.rerere.rikkahub.skills.FastPathRouterLog.record(
                me.rerere.rikkahub.skills.FastPathRouterLog.Entry(
                    whenMs = System.currentTimeMillis(),
                    intent = match.intent,
                    toolName = match.toolName,
                    userText = userText.take(120),
                    resultPreview = "tool threw: ${e.message?.take(80)}",
                    skippedLlm = false,
                )
            )
            return@FastPathRouter FastPathDecision.ContinueToModel(context.content)
        }
        FastPathDecision.Handled(listOf(UIMessagePart.Text(rendered)))
    }

    private fun preprocessUserInputParts(
        parts: List<UIMessagePart>,
        assistant: Assistant,
    ): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val policy = if (regenerateAssistantMsg) {
            me.rerere.rikkahub.service.chat.RegeneratePolicy.INTERRUPT_CURRENT
        } else {
            me.rerere.rikkahub.service.chat.RegeneratePolicy.REJECT_IF_BUSY
        }
        val command = RegenerateCommand(
            targetMessageId = message.id,
            expectedTargetVersion = 0L,
            expectedBranchHeadMessageId = message.id,
            policy = policy,
        )
        appScope.launch {
            val tracked = submitCommandTracked(
                conversationId = conversationId,
                command = command,
                origin = CommandOrigin.APP_UI,
                dedupeKey = null,
                expiresAt = null,
                dependencies = emptyList(),
            )
            val submissionFailure = when (val submission = tracked.submission) {
                is SubmitResult.Accepted -> null
                is SubmitResult.QueueFull -> "Conversation queue is full (${submission.limit})"
                is SubmitResult.Rejected -> submission.reason
                is SubmitResult.RuntimeUnavailable -> submission.reason
            }
            if (submissionFailure != null) {
                addError(
                    IllegalStateException(submissionFailure),
                    conversationId,
                    title = context.getString(R.string.error_title_regenerate_message),
                )
                return@launch
            }

            val terminalFailure = when (val outcome = tracked.outcome.await()) {
                is CommandOutcome.Conflict -> outcome.reason
                is CommandOutcome.Rejected -> outcome.reason
                is CommandOutcome.NotApplied -> outcome.reason
                is CommandOutcome.Failed ->
                    outcome.error.message ?: outcome.error.toString()
                is CommandOutcome.SkippedDependencyFailed ->
                    "Required command failed: ${outcome.dependencyId}"
                else -> null
            }
            terminalFailure?.let { reason ->
                addError(
                    IllegalStateException(reason),
                    conversationId,
                    title = context.getString(R.string.error_title_regenerate_message),
                )
            }
        }
    }

    private suspend fun executeRegenerateInline(
        conversationId: Uuid,
        origin: CommandOrigin,
        command: RegenerateCommand,
        control: GenerationRunControl,
    ): RunOutcome {
        val session = getOrCreateSession(conversationId)
        val conversation = session.state.value
        val message = conversation.messageNodes.asSequence()
            .flatMap { it.messages.asSequence() }
            .firstOrNull { it.id == command.targetMessageId }
            ?: return RunOutcome.Conflict("Target message no longer exists")
        // Look up by id: UIMessagePart.metadata is a var, so contains(message) equals can
        // fail after streaming updates even when the id is still present.
        val node = conversation.getMessageNodeByMessageId(command.targetMessageId)
            ?: return RunOutcome.Conflict("Target message is not in the conversation")
        val indexAt = conversation.messageNodes.indexOf(node)
        if (indexAt < 0) return RunOutcome.Conflict("Target message is not in the conversation")
        return runRegenerationTransaction(
            restore = {
                val restored = mergeConversationState(conversationId) { current ->
                    current.copy(messageNodes = conversation.messageNodes)
                }
                saveConversation(conversationId, restored)
            },
        ) {
            try {
                if (message.role == MessageRole.USER) {
                    saveConversation(
                        conversationId,
                        conversation.copy(messageNodes = conversation.messageNodes.subList(0, indexAt + 1)),
                    )
                    handleMessageComplete(
                        conversationId,
                        origin = origin,
                        runControl = control,
                        propagateFailure = true,
                    )
                } else if (command.policy != me.rerere.rikkahub.service.chat.RegeneratePolicy.REJECT_IF_BUSY) {
                    handleMessageComplete(
                        conversationId,
                        origin = origin,
                        messageRange = 0..<indexAt,
                        runControl = control,
                        propagateFailure = true,
                    )
                }
                _generationDoneFlow.emit(conversationId)
                RunOutcome.Completed()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
                RunOutcome.Failed(e)
            }
        }
    }

    // ---- 处理工具调用审批 ----

    /** Scope of an "approve" decision. Once = this single tool call only. ChatScope =
     *  every future call of the same tool name in this conversation (until /new). Always =
     *  every future call of this tool name across the whole app, persisted to disk. */
    enum class ApprovalScope { Once, ChatScope, Always }

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
        scope: ApprovalScope = ApprovalScope.Once,
        toolName: String? = null,
        origin: CommandOrigin = CommandOrigin.APP_UI,
    ) {
        val decision = when {
            answer != null -> ToolDecision.Answered(answer)
            approved -> ToolDecision.Approved
            else -> ToolDecision.Denied(reason)
        }
        appScope.launch {
            val projection = secondUserApprovalLifecycle.findLatest(
                conversationId = conversationId.toString(),
                toolCallId = toolCallId,
            )
            submitCommand(
                conversationId,
                ToolApprovalCommand(
                    toolCallId = toolCallId,
                    decision = decision,
                    toolName = toolName,
                    scope = scope.name,
                    expectedStateVersion = projection?.stateVersion,
                    resolutionRequestId = Uuid.random().toString(),
                ),
                origin,
            )
        }
    }

    private suspend fun executeToolApprovalInline(
        conversationId: Uuid,
        command: ToolApprovalCommand,
        control: GenerationRunControl,
        origin: CommandOrigin,
        envelopeId: Uuid,
    ): RunOutcome {
        val decision = command.decision
        val approved = decision is ToolDecision.Approved
        val answer = (decision as? ToolDecision.Answered)?.answer
        val reason = (decision as? ToolDecision.Denied)?.reason.orEmpty()
        ensureHydrated(conversationId)
        val session = getOrCreateSession(conversationId)
        val conversation = session.state.value
        val approvalProjection = secondUserApprovalLifecycle.findLatest(
            conversationId = conversationId.toString(),
            toolCallId = command.toolCallId,
        )
        if (approvalProjection != null &&
            decision !is ToolDecision.Denied &&
            origin != CommandOrigin.APP_UI
        ) {
            return RunOutcome.Rejected("approval_requires_trusted_app")
        }
        if (
            approvalProjection != null &&
            approvalProjection.subjectType ==
                me.rerere.rikkahub.data.capability.SubjectType.LOCAL_SECOND_USER.name &&
            approvalProjection.subjectId != SecondUserAuthorityRegistry.current()?.subjectId &&
            decision !is ToolDecision.Denied
        ) {
            // A positive decision (including an answer that resumes execution) is tied to the
            // exact authority epoch that created the projection. A stale epoch can only be
            // revoked/denied; it can never resume a tool after reassignment.
            return RunOutcome.Rejected("second_user_authority_stale")
        }
        val newApprovalState = when {
            answer != null -> ToolApprovalState.Answered(answer)
            approved -> ToolApprovalState.Approved
            else -> ToolApprovalState.Denied(reason)
        }
        var foundPending = false
        var appliedPendingDecision = false
        var foundSameTerminal = false
        var foundConflictingTerminal = false
        val updatedNodes = conversation.messageNodes.map { node ->
            node.copy(messages = node.messages.map { msg ->
                msg.copy(parts = msg.parts.map { part ->
                    if (part !is UIMessagePart.Tool || part.toolCallId != command.toolCallId) return@map part
                    when (val transition = me.rerere.rikkahub.service.chat.resolveToolApproval(
                        current = part.approvalState,
                        requested = newApprovalState,
                    )) {
                        is me.rerere.rikkahub.service.chat.ToolApprovalTransition.Apply -> {
                            foundPending = true
                            appliedPendingDecision = true
                            part.copy(approvalState = transition.state)
                        }
                        me.rerere.rikkahub.service.chat.ToolApprovalTransition.Idempotent -> {
                            foundSameTerminal = true
                            part
                        }
                        me.rerere.rikkahub.service.chat.ToolApprovalTransition.Conflict -> {
                            foundConflictingTerminal = true
                            part
                        }
                        me.rerere.rikkahub.service.chat.ToolApprovalTransition.NotPending -> part
                    }
                })
            })
        }
        if (!foundPending) {
            if (foundSameTerminal && !foundConflictingTerminal && approvalProjection != null) {
                val durable = conversationRepo.getConversationById(conversationId) ?: conversation
                updateConversation(conversationId, durable)
                val canResume = durable.messageNodes
                    .flatMap { it.messages }
                    .flatMap { it.parts }
                    .filterIsInstance<UIMessagePart.Tool>()
                    .any { it.toolCallId == command.toolCallId && it.canResumeExecution }
                if (canResume) {
                    enqueueApprovalResume(conversationId, approvalProjection)
                }
            }
            return when {
                foundConflictingTerminal -> RunOutcome.Conflict("Tool approval already resolved with another decision")
                foundSameTerminal -> RunOutcome.Completed()
                else -> RunOutcome.Rejected("Tool approval is no longer pending")
            }
        }
        if (approved && command.toolName != null && command.scope != "Once") {
            withTimeout(5.seconds) {
                withContext(Dispatchers.IO) {
                    when (command.scope) {
                        ApprovalScope.ChatScope.name -> me.rerere.rikkahub.data.ai.tools
                            .ToolApprovalAllowList.grantForChat(conversationId, command.toolName)
                        ApprovalScope.Always.name -> toolApprovalPreferences.grantAlways(command.toolName)
                    }
                }
            }
        }
        val updatedConversation = conversation.copy(messageNodes = updatedNodes)
        val resolvedProjection = if (approvalProjection == null) {
            saveConversation(conversationId, updatedConversation)
            null
        } else {
            val persistedDecision = when {
                answer != null -> me.rerere.rikkahub.data.execution.PersistedApprovalDecision.ANSWERED
                approved -> me.rerere.rikkahub.data.execution.PersistedApprovalDecision.APPROVED
                else -> me.rerere.rikkahub.data.execution.PersistedApprovalDecision.DENIED
            }
            when (val result = secondUserApprovalLifecycle.resolve(
                currentConversation = conversation,
                updatedConversation = updatedConversation,
                toolCallId = command.toolCallId,
                decision = persistedDecision,
                expectedStateVersion = command.expectedStateVersion,
                resolutionRequestId = command.resolutionRequestId ?: envelopeId.toString(),
                trustedAppApproval = origin == CommandOrigin.APP_UI,
            )) {
                is me.rerere.rikkahub.data.execution.ApprovalResolutionResult.Applied -> {
                    updateConversation(conversationId, updatedConversation)
                    result.projection
                }
                is me.rerere.rikkahub.data.execution.ApprovalResolutionResult.Idempotent -> {
                    conversationRepo.getConversationById(conversationId)?.let {
                        updateConversation(conversationId, it)
                    }
                    result.projection
                }
                is me.rerere.rikkahub.data.execution.ApprovalResolutionResult.Conflict ->
                    return RunOutcome.Conflict(result.reasonCode)
                me.rerere.rikkahub.data.execution.ApprovalResolutionResult.Missing ->
                    return RunOutcome.Rejected("approval_projection_missing")
                me.rerere.rikkahub.data.execution.ApprovalResolutionResult.TrustedAppRequired ->
                    return RunOutcome.Rejected("approval_requires_trusted_app")
            }
        }
        val committedNodes = getConversationFlow(conversationId).value.messageNodes
        val hasPending = committedNodes
            .flatMap { it.messages }
            .flatMap { it.parts }
            .any { it is UIMessagePart.Tool && it.isPending }
        if (me.rerere.rikkahub.service.chat.shouldResumeAfterApproval(
                appliedPendingDecision = appliedPendingDecision,
                hasPendingAfterUpdate = hasPending,
            )) {
            // Resume through the runtime's dedicated approval lane. This keeps the
            // approval run single-owner and prevents a second model continuation
            // from racing ordinary queued messages.
            if (resolvedProjection == null) {
                submitCommand(
                    conversationId = conversationId,
                    command = ResumeAfterApprovalCommand,
                    origin = CommandOrigin.INTERNAL,
                )
            } else {
                enqueueApprovalResume(conversationId, resolvedProjection)
            }
        }
        _generationDoneFlow.emit(conversationId)
        return pendingToolIds(conversationId).takeIf { it.isNotEmpty() }
            ?.let { RunOutcome.WaitingApproval(it) }
            ?: RunOutcome.Completed()
    }

    private suspend fun enqueueApprovalResume(
        conversationId: Uuid,
        projection: me.rerere.rikkahub.data.execution.PendingToolApprovalRecord,
    ) {
        val requestId = projection.resolutionRequestId ?: "resolved"
        submitCommand(
            conversationId = conversationId,
            command = ResumeAfterApprovalCommand,
            origin = CommandOrigin.INTERNAL,
            dedupeKey = "approval-resume:${projection.approvalId}:$requestId",
            expiresAt = null,
            dependencies = emptyList(),
        )
    }


    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        origin: CommandOrigin = CommandOrigin.APP_UI,
        messageRange: ClosedRange<Int>? = null,
        runControl: GenerationRunControl? = null,
        activeCommandId: Uuid? = null,
        propagateFailure: Boolean = false,
        acceptedAssistantSnapshot: Assistant? = null,
        responseCorrelationAnnotation: UIMessageAnnotation? = null,
    ) {
        // Some continuation paths (regenerate, resume-after-approval) do not carry the
        // original command id into this method, but every live generation still owns a
        // stable run id. Resolve the identity once and use it consistently for surface
        // authorization, capability checks, tool budgets, and transient history access.
        val effectiveCommandId = resolveGenerationCommandId(
            activeCommandId = activeCommandId,
            runId = runControl?.runId,
        )
        suspend fun applyRunUpdate(block: suspend () -> Unit): Boolean =
            runControl?.runIfUpdatesAllowed(block) ?: run {
                block()
                true
            }

        val callOrigin = resolveToolOrigin(conversationId, origin)
        val settings = settingsStore.settingsFlow.first()
        // Resolve the assistant from this conversation's own assistantId �?the global
        // current-assistant pointer can have moved if the user switched assistants while
        // this generation was queued (multi-assistant crosstalk). Everything downstream
        // (model, memories, tools, sender name) keys off this resolved assistant.
        val initialConversation = getConversationFlow(conversationId).value
        val baseAssistant = acceptedAssistantSnapshot
            ?: settings.getAssistantById(initialConversation.assistantId)
            ?: if (callOrigin == ToolCallOrigin.SystemAssistant || callOrigin == ToolCallOrigin.QuickCapture) {
                throw IllegalStateException(
                    me.rerere.rikkahub.service.chat
                        .SYSTEM_ASSISTANT_TARGET_ASSISTANT_MISSING_REJECTION,
                )
            } else {
                settings.getCurrentAssistant()
            }
        val subAgentProfile = subAgentExecutionProfileRegistry.get(conversationId)
        val assistant = subAgentProfile?.let { profile ->
            baseAssistant.copy(
                chatModelId = profile.effectiveModelId,
                systemPrompt = profile.effectiveSystemPrompt,
            )
        } ?: baseAssistant
        refreshSecondUserAuthorityForInvocation(
            assistant = assistant,
            conversation = initialConversation,
            origin = callOrigin,
        )
        val privilegeContext = me.rerere.rikkahub.privilege.DefaultPrivilegedSessionResolver.resolve(
            assistant = assistant,
            conversation = initialConversation,
            origin = callOrigin,
        )
        val capabilitySubject = capabilitySubjectFor(
            assistant = assistant,
            conversationId = conversationId,
            origin = callOrigin,
            privilege = privilegeContext,
        )
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: throw IllegalStateException(
                "No chat model selected. Pick one in Settings �?Default models, or send /model in Telegram."
            )
        // Defence against an upstream-Settings bug where disabling all providers can leave
        // the assistant's chatModelId pointing at a model whose provider has enabled=false:
        // the model lookup walks every provider regardless of state, so without this gate
        // inference fires (and bills) against the "disabled" provider's API key. Surface
        // the disabled state clearly instead of silently spending tokens.
        val resolvedProvider = model.findProvider(settings.providers)
        if (resolvedProvider == null) {
            throw IllegalStateException(
                "Selected model '${model.displayName.ifBlank { model.modelId }}' has no matching provider. " +
                    "Pick a different model in Settings or with /model."
            )
        }
        if (!resolvedProvider.enabled) {
            throw IllegalStateException(
                "Provider '${resolvedProvider.name}' is disabled �?refusing to send. " +
                    "Re-enable it in Settings �?Providers, or pick a different model with /model."
            )
        }

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }

        val generationResult = runCatching {
            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (assistant.enableWebSearch ||
                    mcpManager.getAvailableToolsForAssistant(assistant.id).isNotEmpty()
                ) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value
            val isHeadless = me.rerere.rikkahub.data.ai.tools.HeadlessConversations
                .isHeadless(conversationId)
            val toolNameSurface = me.rerere.rikkahub.data.ai.tools.ToolNameSurface()
            val toolExecutionSurface = me.rerere.rikkahub.data.ai.tools.ToolExecutionSurface()
            val invocationCtx = me.rerere.rikkahub.data.ai.tools.ToolInvocationContext(
                callerAssistantId = assistant.id.toString(),
                callerConversationId = conversationId.toString(),
                callerRunId = runControl?.runId?.toString(),
                callerWorkspaceId = assistant.workspaceId?.toString(),
                callOrigin = callOrigin,
                callerModelId = model.id.toString(),
                callerProviderId = resolvedProvider.id.toString(),
                isHeadless = isHeadless,
                modelCanSeeImages = Modality.IMAGE in model.inputModalities,
                privilege = privilegeContext,
                toolNameSurface = toolNameSurface,
                toolExecutionSurface = toolExecutionSurface,
            )
            val workspaceShellSharedStorage =
                me.rerere.rikkahub.data.ai.tools.canMountSecondUserSharedStorage(
                    privilege = privilegeContext,
                    grants = capabilityGrantRepository.current(),
                )
            val secondUserDeviceAccessAddendum = if (privilegeContext.expandLocalTools) {
                val workspace = assistant.workspaceId?.toString()?.let { workspaceId ->
                    workspaceRepository.getById(workspaceId)
                }
                me.rerere.rikkahub.data.ai.tools.secondUserDeviceAccessAddendum(
                    privilege = privilegeContext,
                    workspaceId = workspace?.id,
                    workspaceStorageMode = workspace?.storageMode,
                    workspaceShellSharedStorage = workspaceShellSharedStorage,
                )
            } else {
                null
            }
            val localToolOptions = if (privilegeContext.expandLocalTools) {
                me.rerere.rikkahub.data.ai.tools.LocalToolOption.PRIVILEGED_IMPLEMENTED
            } else {
                assistant.localTools
            }
            val privilegedBridgeEnabled = agentSafetySettings
                .privilegedBridgeEnabledFlow.first()
            val privilegedBridgeStatus = shizukuBridgeManager.status()
            val deviceLocked = (context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)
                ?.let { it.isDeviceLocked || it.isKeyguardLocked } == true
            val hasAuthorizedInvocation = when (callOrigin) {
                ToolCallOrigin.QuickCapture -> me.rerere.rikkahub.quickcapture
                    .QuickCaptureInvocationRegistry
                    .hasAuthorizedRun(conversationId, effectiveCommandId)
                else -> me.rerere.rikkahub.assistant.SystemAssistantInvocationRegistry
                    .hasAuthorizedUnlockedInvocation(conversationId, effectiveCommandId)
            }
            val invocationSurfaceContext = me.rerere.rikkahub.quickcapture.InvocationSurfaceContexts
                .currentContext(callOrigin, conversationId, effectiveCommandId)
            val toolExposurePlan = me.rerere.rikkahub.data.ai.ToolExposurePlan.create(
                origin = callOrigin,
                deviceLocked = deviceLocked,
                hasAuthorizedInvocation = hasAuthorizedInvocation,
                surfaceContext = invocationSurfaceContext,
            )
            val invocationSurfaceCanExposeTools = toolExposurePlan.surfaceAvailable
            val webSearchToolsEnabled = WebSearchPolicy.canInject(
                assistant = assistant,
                origin = callOrigin,
                toolSurfaceAvailable = invocationSurfaceCanExposeTools,
            )
            fun canExposeTool(toolName: String): Boolean {
                return toolExposurePlan.canExpose(toolName)
            }
            fun canExposeLocalTool(toolName: String): Boolean {
                return canExposeTool(toolName)
            }
            val localToolDefinitions = localTools.getTools(localToolOptions, invocationCtx)
                .filter { tool -> canExposeLocalTool(tool.name) }
            val pluginToolRegistrations = if (invocationSurfaceCanExposeTools) {
                pluginToolCatalog.registrations(
                    me.rerere.rikkahub.plugin.PluginToolSurfaceRequest(
                        assistantId = assistant.id.toString(),
                        conversationId = conversationId.toString(),
                        runId = runControl?.runId?.toString().orEmpty(),
                        origin = callOrigin,
                        assistantEnabledPluginIds = assistant.enabledPluginIds,
                        isHeadless = isHeadless,
                        isSubAgent = subAgentProfile != null,
                        stateProjection = buildJsonObject {
                            put("version", 1)
                            put("surface", "local_chat")
                            put("assistant_name", assistant.name.take(80))
                            put("memory_enabled", assistant.enableMemory)
                        }.toString(),
                    ),
                )
            } else {
                emptyList()
            }
            val pluginPromptAddendum = pluginHookBridge.collectPromptAddendum(
                me.rerere.rikkahub.plugin.PluginPromptHookRequest(
                    assistantId = assistant.id.toString(),
                    conversationId = conversationId.toString(),
                    runId = runControl?.runId?.toString().orEmpty(),
                    origin = callOrigin,
                    assistantEnabledPluginIds = assistant.enabledPluginIds,
                    isHeadless = isHeadless,
                    isSubAgent = subAgentProfile != null,
                ),
            )
            val privilegedShellRegistration = if (
                invocationSurfaceCanExposeTools &&
                canExposeTool(me.rerere.rikkahub.privilege.PRIVILEGED_SHELL_TOOL_NAME) &&
                me.rerere.rikkahub.privilege.shouldInjectPrivilegedShell(
                    privilege = privilegeContext,
                    origin = callOrigin,
                    isHeadless = isHeadless,
                    privilegedBridgeEnabled = privilegedBridgeEnabled,
                    bridgeStatus = privilegedBridgeStatus,
                )
            ) {
                me.rerere.rikkahub.privilege.createExternalBridgeRunCommandTool(
                    shizukuBridgeManager,
                )
            } else {
                null
            }
            val structuredPrivilegedRegistration = if (
                invocationSurfaceCanExposeTools &&
                structuredPrivilegedCommandExecutor != null &&
                me.rerere.rikkahub.privilege.shouldInjectStructuredPrivilegedTools(
                    privilege = privilegeContext,
                    origin = callOrigin,
                    isHeadless = isHeadless,
                    privilegedBridgeEnabled = privilegedBridgeEnabled,
                    bridgeStatus = privilegedBridgeStatus,
                )
            ) {
                me.rerere.rikkahub.privilege.createStructuredPrivilegedTools(
                    structuredPrivilegedCommandExecutor,
                )
            } else {
                null
            }
            val structuredPrivilegedV2Registration = if (
                invocationSurfaceCanExposeTools &&
                structuredPrivilegedCommandExecutor != null &&
                me.rerere.rikkahub.privilege.shouldInjectStructuredPrivilegedV2Tools(
                    privilege = privilegeContext,
                    origin = callOrigin,
                    isHeadless = isHeadless,
                    privilegedBridgeEnabled = privilegedBridgeEnabled,
                    bridgeStatus = privilegedBridgeStatus,
                    deviceLocked = deviceLocked,
                )
            ) {
                me.rerere.rikkahub.privilege.createStructuredPrivilegedV2Tools(
                    structuredPrivilegedCommandExecutor,
                )
            } else {
                null
            }
            val verifiedAccessibilityToolDefinitions = if (
                invocationSurfaceCanExposeTools &&
                me.rerere.rikkahub.data.ai.tools.local.shouldInjectVerifiedAccessibilityTools(
                    privilege = privilegeContext,
                    origin = callOrigin,
                    isHeadless = isHeadless,
                )
            ) {
                me.rerere.rikkahub.data.ai.tools.local.verifiedAccessibilityTools(
                    invocationContext = invocationCtx,
                    displayTargetResolver = displayAutomationRuntime?.let { runtime ->
                        me.rerere.rikkahub.data.ai.tools.local.DisplayTargetResolver(runtime)
                    },
                )
            } else {
                emptyList()
            }
            val workspaceProcessTools = if (
                invocationSurfaceCanExposeTools &&
                me.rerere.rikkahub.data.ai.tools.shouldInjectWorkspaceProcessTools(
                    privilege = privilegeContext,
                    origin = callOrigin,
                    isHeadless = isHeadless,
                )
            ) {
                me.rerere.rikkahub.data.ai.tools.createWorkspaceProcessTools(
                    manager = workspaceProcessManager,
                    workspaceRepository = workspaceRepository,
                    defaultWorkspaceId = assistant.workspaceId?.toString(),
                    defaultCwd = conversation.workspaceCwd,
                )
            } else {
                emptyList()
            }

            // start generating
            val session = getOrCreateSession(conversationId)
            // Restore the pre-directory direct tool surface for the active local second user.
            // Other assistants and remote/headless origins keep their existing behaviour.
            // Tool experience and Fast Lane remain durable library features; they are simply no
            // longer required as a schema-discovery detour on this direct surface.
            val secondUserDirectToolSurface =
                privilegeContext.isPrivileged &&
                capabilitySubject.type == me.rerere.rikkahub.data.capability.SubjectType.LOCAL_SECOND_USER &&
                callOrigin in me.rerere.rikkahub.data.ai.InvocationSurfacePolicy.CONFIRMED_LOCAL_SECOND_USER &&
                !isHeadless &&
                subAgentProfile == null
            val toolSurfaceSession = if (secondUserDirectToolSurface) {
                me.rerere.rikkahub.toolcatalog.ToolDiscoverySession(
                    snapshot = me.rerere.rikkahub.toolcatalog.ToolSurfaceBuilder.snapshot(emptyList()),
                    experienceLookup = toolExperienceRepository,
                    experienceEditor = toolExperienceRepository,
                    shortcutEditor = toolShortcutRepository,
                    onSnapshotResolved = { snapshot ->
                        appScope.launch {
                            toolShortcutRepository.reconcileSnapshot(snapshot)
                        }
                    },
                    mode = me.rerere.rikkahub.toolcatalog.ToolSurfaceMode.DIRECT,
                )
            } else {
                null
            }
            val ownerToolSurfaceAvailable =
                me.rerere.rikkahub.data.ai.tools.isOwnerToolSurfaceAvailable(invocationCtx)
            val legacyOwnerRuntimeTools = if (ownerToolSurfaceAvailable) {
                buildList {
                    addAll(
                        me.rerere.rikkahub.data.ai.tools.createPrivilegedManagementTools(
                            invocationContext = invocationCtx,
                            guard = privilegedActionGuard,
                            backend = privilegedManagementBackend,
                            hardDenyPolicy = hardDenyPolicy,
                        ),
                    )
                    addAll(
                        me.rerere.rikkahub.setup.createSetupTools(
                            invocationContext = invocationCtx,
                            coordinator = setupTransactionCoordinator,
                        ),
                    )
                }
            } else {
                emptyList()
            }
            generationHandler.generateText(
                settings = settings,
                model = model,
                processingStatus = session.processingStatus,
                // Read once per call so the surface that wrote the addendum (Telegram bot,
                // anything else) gets its runtime context into the system prompt without
                // having to plumb a parameter all the way through sendMessage. Returns null
                // for in-app conversations that didn't register one.
                systemAddendum = listOfNotNull(
                    me.rerere.rikkahub.data.ai.tools.ConversationSystemAddendum
                        .get(conversationId),
                    secondUserDeviceAccessAddendum,
                    pluginPromptAddendum,
                    if (toolSurfaceSession != null) {
                        """
                        Direct tool surface: all currently eligible tool schemas are available in this turn.
                        Use a visible tool directly; do not search or open a directory first. Tool experiences
                        and Fast Lane metadata are hints, never authorization. Re-check the current schema,
                        permission state, and approval requirements before acting.
                        """.trimIndent()
                    } else null,
                ).joinToString("\n\n").ifBlank { null },
                isToolAutoApproved = { toolName ->
                    // YOLO mode ("I AM STUPID" toggle in Settings �?Tool approvals): every
                    // tool auto-approves. User opted into this explicitly. HARDLINE still
                    // blocks rm -rf / et al �?that check runs BEFORE auto-approval in
                    // GenerationHandler, so YOLO can't smuggle one through.
                    //
                    // Headless conversations (cron-driven) also auto-approve EVERY tool;
                    // the user pre-authorised the schedule itself at job-creation time
                    // and there's no UI surface to prompt at fire time.
                    //
                    // Otherwise: "Allow for this chat" (in-memory, per-conversation) OR
                    // "Always Allow" (DataStore-backed, across the whole app). The
                    // Once-grant lives in the message itself as
                    // ToolApprovalState.Approved, so it's already handled by the regular
                    // Pending �?Approved transition.
                    //
                    // ask_user is a human-input request, NOT a permission gate. It must pause
                    // for the user whenever there's a surface to ask on (the in-app question card
                    // or the Telegram clarify flow), so it ignores YOLO and the allow-lists �?
                    // otherwise it auto-executes its placeholder body and returns
                    // ask_user_unavailable. In a headless run (cron / sub-agent) there's nobody to
                    // answer, so it still auto-approves there and falls through to that graceful
                    // envelope instead of hanging the turn.
                    if (toolName == "ask_user") {
                        me.rerere.rikkahub.data.ai.tools.HeadlessConversations
                            .shouldAutoApprove(conversationId)
                    } else if (callOrigin in me.rerere.rikkahub.data.ai.InvocationSurfacePolicy.REMOTE) {
                        // Telegram/Web/MCP/external origins are separate principals. They never
                        // inherit second-user, YOLO, or local allow-list decisions. A future
                        // scoped AccessGrant is the only path that may pre-authorize them.
                        false
                    } else if (me.rerere.rikkahub.owner.OwnerAutonomyPolicy.canAutoApprove(
                            privilege = privilegeContext,
                            origin = callOrigin,
                            toolName = toolName,
                        )) {
                        true
                    } else if (
                        me.rerere.rikkahub.plugin.isPluginModelToolName(toolName) ||
                        toolName == "linux_grant_request" ||
                        toolName == "linux_grant_revoke"
                    ) {
                        // Ordinary assistants retain the existing fresh-approval floor. Only the
                        // live local Owner principal above bypasses it.
                        false
                    } else {
                        privilegeContext.autoApproveTools ||
                            (toolName == "call_phone" && privilegeContext.unrestrictedOverride &&
                            toolExecutionGate.canAutoApproveUnrestrictedCallNow(
                                callOrigin,
                            )) ||
                            toolApprovalPreferences.currentYolo() ||
                            me.rerere.rikkahub.data.ai.tools.HeadlessConversations
                                .shouldAutoApprove(conversationId) ||
                            me.rerere.rikkahub.data.ai.tools.ToolApprovalAllowList
                                .isAllowedForChat(conversationId, toolName) ||
                            toolApprovalPreferences.current().contains(toolName)
                    }
                },
                runtimeOnlyTools = legacyOwnerRuntimeTools,
                messages = conversation.currentMessages.let {
                    if (messageRange != null) {
                        it.subList(messageRange.start, messageRange.endInclusive + 1)
                    } else {
                        it
                    }
                },
                assistant = assistant,
                unrestrictedOverride = privilegeContext.unrestrictedOverride,
                capabilitySubject = capabilitySubject,
                selectedPrivilegedConversation = privilegeContext.isPrivileged,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                callOrigin = callOrigin,
                commandOrigin = origin,
                conversationId = conversationId,
                commandId = effectiveCommandId,
                runControl = runControl,
                isHeadless = isHeadless,
                isSubAgent = subAgentProfile != null,
                maxSteps = subAgentProfile?.generationMaxSteps() ?: 32,
                memoryToolAllowed = subAgentProfile?.allowsTool("memory_tool") ?: true,
                invocationSurfaceContextProvider =
                    me.rerere.rikkahub.quickcapture.InvocationSurfaceContexts,
                isEmergencyStopActive = {
                    agentSafetySettings.emergencyStopFlow.first()
                },
                startableTools = buildMap {
                    privilegedShellRegistration?.let { registration ->
                        if (canExposeTool(registration.definition.name)) {
                            put(registration.definition.name, registration.startable)
                        }
                    }
                    structuredPrivilegedRegistration?.let { registration ->
                        putAll(registration.startables.filterKeys(::canExposeTool))
                    }
                    structuredPrivilegedV2Registration?.let { registration ->
                        putAll(registration.startables.filterKeys(::canExposeTool))
                    }
                    pluginToolRegistrations.forEach { registration ->
                        put(registration.definition.name, registration.startable)
                    }
                },
                memories = if (!assistant.enableMemory) {
                    emptyList()
                } else {
                    val standingPreferences = memoryRepository.getUserApprovedStandingMemories(
                        assistantId = assistant.id,
                        includeGlobal = assistant.useGlobalMemory,
                    )
                    val query = conversation.currentMessages
                        .lastOrNull { it.role == MessageRole.USER }
                        ?.parts
                        ?.filterIsInstance<UIMessagePart.Text>()
                        ?.joinToString("\n") { it.text }
                        .orEmpty()
                    val relevantMemories = memoryRepository.queryRelevant(
                        assistantId = assistant.id,
                        query = query,
                        includeGlobal = assistant.useGlobalMemory,
                        excludeMemoryIds = standingPreferences.mapTo(hashSetOf()) { it.id },
                    ).map { it.memory }
                    (standingPreferences + relevantMemories).distinctBy { it.id }
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (webSearchToolsEnabled) {
                        addAll(createSearchTools(settings))
                    }
                    if (!privilegeContext.isPrivileged) {
                        addAll(
                            createConversationTools(conversationRepo, assistant.id).filter { tool ->
                                callOrigin == ToolCallOrigin.LocalChat || tool.name != "conversation_search"
                            }
                        )
                    } else if (assistant.allowConversationHistoryRead) {
                        addAll(
                            me.rerere.rikkahub.data.ai.tools.createSecondUserConversationReaderTools(
                                reader = conversationLibraryReader,
                                invocationContext = invocationCtx,
                                commandId = effectiveCommandId,
                                historyReadEnabled = true,
                                deviceUnlocked = { !deviceLocked },
                            )
                        )
                    }
                    addAll(localToolDefinitions)
                    addAll(pluginToolRegistrations.map { it.definition })
                    privilegedShellRegistration?.let { add(it.definition) }
                    structuredPrivilegedRegistration?.let { addAll(it.definitions) }
                    structuredPrivilegedV2Registration?.let { addAll(it.definitions) }
                    addAll(verifiedAccessibilityToolDefinitions)
                    if (privilegeContext.isPrivileged) {
                        add(
                            me.rerere.rikkahub.data.ai.tools.createConversationSendMessageTool(
                                invocationContext = invocationCtx,
                                conversationExists = conversationRepo::existsConversationById,
                                submit = { message ->
                                    submitUserMessage(
                                        conversationId = message.conversationId,
                                        content = message.parts,
                                        answer = message.answer,
                                        origin = me.rerere.rikkahub.service.chat.CommandOrigin.INTERNAL,
                                        dedupeKey = message.dedupeKey,
                                        annotations = message.annotations,
                                    )
                                },
                            )
                        )
                        if (ownerToolSurfaceAvailable) {
                            addAll(
                                me.rerere.rikkahub.data.ai.tools.createOwnerManagementTools(
                                    invocationContext = invocationCtx,
                                    gateway = ownerOperationGateway,
                                ),
                            )
                        }
                        addAll(workspaceProcessTools)
                    }
                    addAll(
                        createWorkspaceToolsIfReady(
                            workspaceId = assistant.workspaceId?.toString(),
                            cwd = conversation.workspaceCwd,
                            allowSharedStorage = workspaceShellSharedStorage,
                        ),
                    )
                    if (assistant.enabledSkills.isNotEmpty()) {
                        addAll(
                            createSkillTools(
                                enabledSkills = assistant.enabledSkills,
                                allSkills = skillManager.listSkills(),
                                skillManager = skillManager,
                                // Direct mode exposes current schemas in the provider request, so
                                // the legacy tool reference must not point the model back into a
                                // search/open directory that is intentionally absent here.
                                redirectSecondUserToolReference = false,
                            )
                        )
                    }
                    mcpManager.getAvailableToolsForAssistant(assistant.id).also { allTools ->
                        // Upstream name validation: a server name that isn't pure
                        // English+digits would produce an invalid `mcp__<name>__tool`
                        // surface, so surface it as an error rather than emit a tool the
                        // model can't address.
                        val invalidNames = allTools
                            .map { it.second }
                            .distinct()
                            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
                        if (invalidNames.isNotEmpty()) {
                            addError(
                                error = IllegalStateException(
                                    context.getString(
                                        R.string.error_mcp_invalid_server_name,
                                        invalidNames.joinToString(", ")
                                    )
                                ),
                                conversationId = conversationId,
                            )
                            return
                        }
                    }.forEach { (serverId, serverName, tool) ->
                        // Namespace MCP tools by a server-id slug so two enabled servers that
                        // each expose a tool of the same name don't collide (which would 400 or
                        // mis-route to whichever server registered last). Keep the `mcp__` prefix
                        // intact: HardlineCommandGuard and ToolApprovalDefaults both branch on
                        // `startsWith("mcp__")`. The slug is the first 8 hex chars of the id with
                        // dashes stripped; the validated server name follows for human-readable
                        // disambiguation, keeping the name within the 64-char /
                        // ^[a-zA-Z0-9_-]+$ limit. The execute lambda below still calls callTool
                        // with the REAL tool.name, since the namespacing exists only on the
                        // model-facing surface.
                        val serverSlug = serverId.toString().take(8).replace("-", "")
                        val mcpToolName = "mcp__" + serverSlug + "_" + serverName + "__" + tool.name
                        add(
                            Tool(
                                name = mcpToolName,
                                description = tool.description ?: "",
                                parameters = { tool.inputSchema },
                                // MCP servers' tool surfaces are opaque to us �?we can't
                                // tell read from write or safe from destructive �?so
                                // every MCP call is approval-gated by default. The user
                                // can grant Always-Allow per-tool to suppress prompts on
                                // a known-safe MCP server. The HARDLINE floor still
                                // applies via HardlineCommandGuard's `mcp__*` branch,
                                // which scans every string arg for shell-content
                                // patterns (rm -rf /, mkfs, shutdown, encoded payloads).
                                needsApproval = {
                                    me.rerere.rikkahub.data.ai.tools
                                        .ToolApprovalDefaults.requiresApproval(mcpToolName) ||
                                        tool.needsApproval
                                },
                                execute = {
                                    mcpManager.callTool(serverId, tool.name, it.jsonObject)
                                },
                            )
                        )
                    }
                }
                    .asSequence()
                    .filter { tool -> canExposeTool(tool.name) }
                    .filter { tool -> subAgentProfile?.allowsTool(tool.name) ?: true }
                    .toList()
                    .let { definitions ->
                        me.rerere.rikkahub.data.ai.stableProviderToolOrder(definitions)
                    }
                    .also { definitions ->
                        val memoryToolAvailable = assistant.enableMemory &&
                            (subAgentProfile?.allowsTool("memory_tool") ?: true)
                        val availableNames = buildSet {
                            definitions.mapTo(this) { it.name }
                            if (memoryToolAvailable) {
                                add("memory_tool")
                                add("memory_query")
                            }
                        }
                        val knownNames = buildSet {
                            me.rerere.rikkahub.data.capability.CapabilityCatalog
                                .allCapabilities()
                                .flatMapTo(this) { it.toolNames }
                            add("memory_tool")
                            add("memory_query")
                            addAll(availableNames)
                        }
                        check(toolNameSurface.publish(availableNames, knownNames)) {
                            "tool surface was already published for conversation $conversationId"
                        }
                        check(toolExecutionSurface.publish(definitions)) {
                            "tool execution surface was already published for conversation $conversationId"
                        }
                    },
                toolDiscoverySession = toolSurfaceSession,
            ).onCompletion {
                if (runControl?.isUpdateFenced() == true) return@onCompletion
                // 取消 Live Update 通知
                cancelLiveUpdateNotification(conversationId)

                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                if (!applyRunUpdate {
                        updateConversation(conversationId, updatedConversation)
                    }
                ) return@onCompletion

                // Show notification if app is not in foreground
                if (!updatedConversation.latestAssistantNeedsFinalAnswer() &&
                    !isForeground.value &&
                    settings.displaySetting.enableNotificationOnMessageGeneration
                ) {
                    sendGenerationDoneNotification(conversationId, senderName)
                }
            }.collect { chunk ->
                if (runControl?.isUpdateFenced() == true) return@collect
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val correlatedMessages = chunk.messages.withResponseCorrelation(
                            responseCorrelationAnnotation,
                        ).sanitizeTransientConversationToolResults()
                        val updatedConversation = getConversationFlow(conversationId).value
                            .updateCurrentMessages(correlatedMessages)
                        if (!applyRunUpdate {
                                if (chunk.persistenceBarrier ==
                                    GenerationPersistenceBarrier.PENDING_APPROVAL &&
                                    capabilitySubject.type ==
                                    me.rerere.rikkahub.data.capability.SubjectType.LOCAL_SECOND_USER
                                ) {
                                    val pendingTools = correlatedMessages
                                        .lastOrNull()
                                        ?.parts
                                        ?.filterIsInstance<UIMessagePart.Tool>()
                                        ?.filter { it.isPending }
                                        ?.map { tool ->
                                            me.rerere.rikkahub.data.execution.PendingApprovalTool(
                                                toolCallId = tool.toolCallId,
                                                toolName = tool.toolName,
                                                arguments = tool.inputAsJson() as? JsonObject
                                                    ?: JsonObject(emptyMap()),
                                            )
                                        }
                                        .orEmpty()
                                    secondUserApprovalLifecycle.persistPendingBarrier(
                                        conversation = updatedConversation,
                                        owner = me.rerere.rikkahub.data.execution.PendingApprovalOwner(
                                            runId = (runControl?.runId ?: effectiveCommandId).toString(),
                                            commandId = effectiveCommandId.toString(),
                                            conversationId = conversationId.toString(),
                                            subjectId = capabilitySubject.id,
                                            subjectType = capabilitySubject.type,
                                            origin = callOrigin,
                                        ),
                                        tools = pendingTools,
                                    )
                                }
                                updateConversation(conversationId, updatedConversation)
                            }
                        ) return@collect

                        // Persist immediately when a tool transitions to "execution
                        // started but no output yet" �?this writes the executionStartedAt
                        // breadcrumb to disk so a process kill mid-execute leaves a clear
                        // signal for the next replay (see GenerationHandler.kt's replay
                        // safety pass: Approved + executionStartedAt + empty �?Denied
                        // interrupted_unknown_outcome). Without this, the marker stays in
                        // memory only and replay can't distinguish "freshly approved,
                        // never tried" from "interrupted mid-execute" �?silent re-run.
                        val latestMessage = correlatedMessages.lastOrNull()
                        val needsImmediatePersist = latestMessage?.parts?.any { p ->
                            p is UIMessagePart.Tool &&
                                p.executionStartedAt != null &&
                                p.output.isEmpty() &&
                                p.approvalState is ToolApprovalState.Approved
                        } == true || latestMessage?.annotations?.any { annotation ->
                            annotation is UIMessageAnnotation.FinalAnswerRecovery &&
                                annotation.status == FinalAnswerRecoveryStatus.STARTED
                        } == true
                        if (needsImmediatePersist) {
                            applyRunUpdate {
                                saveConversation(conversationId, updatedConversation)
                            }
                        }

                        // 如果应用不在前台，发�?Live Update 通知
                        if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration && settings.displaySetting.enableLiveUpdateNotification) {
                            sendLiveUpdateNotification(conversationId, chunk.messages, senderName)
                        }
                    }
                }
            }
        }
        generationResult.onFailure {
            if (runControl?.isUpdateFenced() == true) return@onFailure
            if (it is CancellationException) throw it
            // 取消 Live Update 通知
            cancelLiveUpdateNotification(conversationId)

            // Persist the in-memory snapshot so the Auto/Pending �?Denied transitions
            // GenerationHandler did inside its try/catch (the "generation_failed" recovery
            // path) survive a process restart. Without this, the failure path only
            // updates memory and the persisted DB row keeps the stale Pending state
            // forever �?replay would re-run the loop against unrecoverable shape.
            runCatching {
                applyRunUpdate {
                    val final = getConversationFlow(conversationId).value
                    saveConversation(conversationId, final)
                }
            }.onFailure { saveErr ->
                Log.w(TAG, "handleMessageComplete: failure-path save failed", saveErr)
            }

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            if (runControl?.isUpdateFenced() == true) return@onSuccess
            applyRunUpdate {
                val finalConversation = getConversationFlow(conversationId).value
                saveConversation(conversationId, finalConversation)

                if (!finalConversation.latestAssistantNeedsFinalAnswer()) {
                    enqueueMemoryCapture(
                        conversationId = conversationId,
                        commandOrigin = origin,
                        toolOrigin = callOrigin,
                        assistant = baseAssistant,
                        conversation = finalConversation,
                        isSubAgent = subAgentProfile != null,
                    )
                    launchWithConversationReference(conversationId) {
                        generateTitle(conversationId, finalConversation)
                    }
                    launchWithConversationReference(conversationId) {
                        generateSuggestion(conversationId, finalConversation)
                    }
                }
            }
        }
        if (propagateFailure) generationResult.getOrThrow()
    }

    private fun enqueueMemoryCapture(
        conversationId: Uuid,
        commandOrigin: CommandOrigin,
        toolOrigin: ToolCallOrigin,
        assistant: Assistant,
        conversation: Conversation,
        isSubAgent: Boolean,
    ) {
        val messages = conversation.currentMessages
        val assistantIndex = messages.indexOfLast { it.role == MessageRole.ASSISTANT }
        if (assistantIndex <= 0) return
        val assistantMessage = messages[assistantIndex]
        val userMessage = messages.subList(0, assistantIndex)
            .lastOrNull { it.role == MessageRole.USER } ?: return
        val userText = userMessage.parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }.trim()
        val assistantText = assistantMessage.parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }.trim()
        if (userText.isEmpty() || assistantText.isEmpty()) return

        val captureOrigin = when (toolOrigin) {
            ToolCallOrigin.LocalChat -> me.rerere.rikkahub.memory.MemoryCaptureOrigin.APP_UI
            ToolCallOrigin.SystemAssistant ->
                me.rerere.rikkahub.memory.MemoryCaptureOrigin.SYSTEM_ASSISTANT
            ToolCallOrigin.SystemAssistantKeyguard ->
                me.rerere.rikkahub.memory.MemoryCaptureOrigin.SYSTEM_ASSISTANT_KEYGUARD
            ToolCallOrigin.QuickCapture -> me.rerere.rikkahub.memory.MemoryCaptureOrigin.QUICK_CAPTURE
            ToolCallOrigin.Telegram -> me.rerere.rikkahub.memory.MemoryCaptureOrigin.TELEGRAM
            ToolCallOrigin.WebServer -> me.rerere.rikkahub.memory.MemoryCaptureOrigin.WEB_API
            ToolCallOrigin.TrustedWorkflow -> if (commandOrigin == CommandOrigin.CRON) {
                me.rerere.rikkahub.memory.MemoryCaptureOrigin.CRON
            } else {
                me.rerere.rikkahub.memory.MemoryCaptureOrigin.INTERNAL
            }
            ToolCallOrigin.MCP,
            ToolCallOrigin.ExternalIntent,
            ToolCallOrigin.PetInteraction,
            ToolCallOrigin.PetHandoffAuto,
            -> me.rerere.rikkahub.memory.MemoryCaptureOrigin.INTERNAL
            ToolCallOrigin.PetHandoffConfirmed ->
                me.rerere.rikkahub.memory.MemoryCaptureOrigin.APP_UI
        }
        val scopeId = if (assistant.useGlobalMemory) {
            MemoryRepository.GLOBAL_MEMORY_ID
        } else {
            assistant.id.toString()
        }
        val isHeadless = isSubAgent ||
            me.rerere.rikkahub.data.ai.tools.HeadlessConversations.isHeadless(conversationId)
        appScope.launch(Dispatchers.IO) {
            runCatching {
                memoryV2Coordinator.capture(
                    me.rerere.rikkahub.memory.CompletedMemoryTurn(
                        assistantId = assistant.id,
                        scopeId = scopeId,
                        conversationId = conversationId,
                        userMessageId = userMessage.id,
                        assistantMessageId = assistantMessage.id,
                        origin = captureOrigin,
                        userText = userText,
                        assistantText = assistantText,
                        memoryEnabled = assistant.enableMemory,
                        autoSaveMode = assistant.memoryAutoSaveMode,
                        allowedOrigins = assistant.memoryCaptureOrigins,
                        isHeadless = isHeadless,
                        needsFinalAnswer = conversation.latestAssistantNeedsFinalAnswer(),
                        idleDelayMs = assistant.memoryIdleDelayMinutes
                            .coerceIn(1, 1_440) * 60_000L,
                        immediateCaptureThreshold = assistant.memoryImmediateCaptureThreshold
                            .coerceIn(1, 50),
                        // Freeze the selected context window on this capture. A later settings
                        // change must never alter the batch that this completed turn belongs to.
                        conversationContextTurns = assistant.memoryConversationContextTurns
                            .coerceIn(3, 30),
                        narrativeEventsEnabled = assistant.memoryNarrativeEventsEnabled,
                        insightsTheoriesEnabled = assistant.memoryInsightsTheoriesEnabled,
                    ),
                )
            }.onFailure { error ->
                Log.w(TAG, "Memory V2 capture failed after successful chat turn", error)
            }
        }
    }

    /**
     * Queues an explicit user selection for Memory V2. Assistant messages are context only; a
     * selection containing no user-authored text is rejected before anything is persisted.
     */
    suspend fun captureMemorySelection(
        conversationId: Uuid,
        selectedNodeIds: Set<Uuid>,
    ): me.rerere.rikkahub.memory.ManualMemorySelectionResult {
        val conversation = getConversationFlow(conversationId).value
        val assistant = settingsStore.settingsFlow.first()
            .getAssistantById(conversation.assistantId)
            ?: return me.rerere.rikkahub.memory.ManualMemorySelectionResult.FAILED
        if (!assistant.enableMemory) {
            return me.rerere.rikkahub.memory.ManualMemorySelectionResult.MEMORY_DISABLED
        }
        val selectedMessages = conversation.messageNodes
            .filter { it.id in selectedNodeIds }
            .map { it.currentMessage }
        val userMessages = selectedMessages.filter { it.role == MessageRole.USER }
        val userText = userMessages.joinToString("\n\n") { message ->
            message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        }.trim()
        if (userText.isBlank()) {
            return me.rerere.rikkahub.memory.ManualMemorySelectionResult.NO_USER_TEXT
        }
        val assistantMessages = selectedMessages.filter { it.role == MessageRole.ASSISTANT }
        val assistantText = assistantMessages.joinToString("\n\n") { message ->
            message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        }.trim()
        val evidenceAnchor = assistantMessages.lastOrNull()?.id ?: userMessages.last().id
        val scopeId = if (assistant.useGlobalMemory) {
            MemoryRepository.GLOBAL_MEMORY_ID
        } else {
            assistant.id.toString()
        }
        return runCatching {
            memoryV2Coordinator.capture(
                me.rerere.rikkahub.memory.CompletedMemoryTurn(
                    assistantId = assistant.id,
                    scopeId = scopeId,
                    conversationId = conversationId,
                    userMessageId = userMessages.first().id,
                    assistantMessageId = evidenceAnchor,
                    origin = me.rerere.rikkahub.memory.MemoryCaptureOrigin.APP_UI,
                    userText = userText,
                    assistantText = assistantText,
                    memoryEnabled = true,
                    autoSaveMode = assistant.memoryAutoSaveMode.takeUnless {
                        it == me.rerere.rikkahub.memory.MemoryAutoSaveMode.OFF
                    } ?: me.rerere.rikkahub.memory.MemoryAutoSaveMode.REVIEW_ALL,
                    allowedOrigins = setOf(me.rerere.rikkahub.memory.MemoryCaptureOrigin.APP_UI),
                    isHeadless = false,
                    needsFinalAnswer = false,
                    captureSource = me.rerere.rikkahub.memory.MemoryCaptureSource.MANUAL_SELECTION,
                    idleDelayMs = 0L,
                    immediateCaptureThreshold = 1,
                    conversationContextTurns = assistant.memoryConversationContextTurns
                        .coerceIn(3, 30),
                    narrativeEventsEnabled = assistant.memoryNarrativeEventsEnabled,
                    insightsTheoriesEnabled = assistant.memoryInsightsTheoriesEnabled,
                ),
            )
        }.fold(
            onSuccess = { result ->
                when (result) {
                    is me.rerere.rikkahub.memory.MemoryCaptureResult.Queued,
                    is me.rerere.rikkahub.memory.MemoryCaptureResult.Duplicate,
                    -> me.rerere.rikkahub.memory.ManualMemorySelectionResult.QUEUED

                    is me.rerere.rikkahub.memory.MemoryCaptureResult.Skipped ->
                        me.rerere.rikkahub.memory.ManualMemorySelectionResult.FAILED
                }
            },
            onFailure = {
                Log.w(TAG, "Manual Memory V2 selection capture failed", it)
                me.rerere.rikkahub.memory.ManualMemorySelectionResult.FAILED
            },
        )
    }

    private suspend fun createWorkspaceToolsIfReady(
        workspaceId: String?,
        cwd: String? = null,
        allowSharedStorage: Boolean = false,
    ): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(
            workspaceId = workspaceId,
            workspaceRepository = workspaceRepository,
            cwd = cwd,
            allowSharedStorage = allowSharedStorage,
        )
    }

    // ---- 检查无效消�?----

    private fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // 移除无效 tool (未执行的 Tool)
        messagesNodes = messagesNodes.mapIndexed { _, node ->
            // Check for Tool type with non-executed tools
            val hasPendingTools = node.currentMessage.getTools().any { !it.isExecuted }

            if (hasPendingTools) {
                // Keep messages that are ready to resume, such as approved/denied/answered tools.
                val hasResumableTool = node.currentMessage.getTools().any {
                    !it.isExecuted && it.approvalState.canResumeToolExecution()
                }
                if (hasResumableTool) {
                    return@mapIndexed node
                }

                // If all tools are executed, it's valid
                val allToolsExecuted = node.currentMessage.getTools().all { it.isExecuted }
                if (allToolsExecuted && node.currentMessage.getTools().isNotEmpty()) {
                    return@mapIndexed node
                }

                // Remove messages that still have unresolved tool approvals.
                return@mapIndexed node.copy(
                    messages = node.messages.filter { it.id != node.currentMessage.id },
                    selectIndex = node.selectIndex - 1
                )
            }
            node
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        updateConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
    }

    private fun cancelToolByUser(
        tool: UIMessagePart.Tool,
        cancellationResults: Map<String, CancelRequestResult>,
    ): UIMessagePart.Tool {
        val cancellationResult = cancellationResults[tool.toolCallId]
        val unknown = tool.isInterruptedAttempt &&
            cancellationResult !is CancelRequestResult.LocalWaitCancelledOnly
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    if (unknown) {
                        """{"status":"termination_unknown","error":"Tool execution was interrupted and its external side effect could not be confirmed."}"""
                    } else {
                        """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                    }
                )
            ),
            approvalState = ToolApprovalState.Denied(
                if (unknown) "Tool termination could not be confirmed" else "Generation cancelled by user"
            )
        )
    }

    private suspend fun finishInterruptedPendingTools(
        conversationId: Uuid,
        cancellationResults: Map<String, CancelRequestResult> = emptyMap(),
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastMessageId = currentConversation.messageNodes.lastOrNull()?.currentMessage?.id
        var changed = false
        val updatedNodes = currentConversation.messageNodes.map { node ->
            node.copy(messages = node.messages.map { message ->
                var updated = message.finishPendingTools {
                    cancelToolByUser(it, cancellationResults)
                }
                if (
                    message.id == lastMessageId &&
                    message.role == MessageRole.ASSISTANT &&
                    (message.state != UIMessageState.COMPLETED || message.finishedAt == null) &&
                    message.state != UIMessageState.FAILED
                ) {
                    updated = updated.copy(state = UIMessageState.INTERRUPTED)
                }
                if (updated != message) changed = true
                updated
            })
        }
        if (changed) saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return
            // Same defence as handleLlmTurn: don't burn tokens on a disabled provider.
            if (!provider.enabled) return

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model),
            )

            val generatedTitle = result.choices[0].message?.toText()?.trim().orEmpty()
            val updated = mergeConversationState(conversationId) { current ->
                if (!force && current.title.isNotBlank()) current
                else current.withGeneratedTitle(generatedTitle)
            }
            if (updated.title == generatedTitle) {
                conversationRepo.updateConversationTitle(conversationId, generatedTitle)
            }
        }.onFailure {
            if (it is CancellationException) throw it
            // Title generation is auxiliary �?a failure here doesn't block the chat
            // and surfaces visibly as a blank conversation title in the list. Don't
            // push it onto the user-facing error stream: when the title model 429s,
            // the next message sees title.isBlank()==true, tries again, 429s again,
            // and the user gets a popup per message until they switch models. Match
            // the generateSuggestion pattern (log only) to keep the surface quiet.
            Log.w(TAG, "generateTitle failed", it)
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return
            // Same defence as handleLlmTurn: don't burn tokens on a disabled provider.
            if (!provider.enabled) return

            sessions[conversationId]?.updateState { current ->
                current.withGeneratedSuggestions(emptyList())
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model),
            )
            val suggestions =
                result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            val limitedSuggestions = suggestions.take(10)
            mergeConversationState(conversationId) { current ->
                current.withGeneratedSuggestions(limitedSuggestions)
            }
            conversationRepo.updateConversationSuggestions(conversationId, limitedSuggestions)
        }.onFailure {
            if (it is CancellationException) throw it
            // Suggestion generation is auxiliary �?log only, don't push onto the
            // user-facing error stream (mirrors the generateTitle failure handling).
            Log.w(TAG, "generateSuggestion failed", it)
        }
    }

    // ---- 压缩对话历史 ----

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> = try {
        require(targetTokens in 100..32_000) { "Compression target must be between 100 and 32,000 tokens." }
        require(keepRecentMessages >= 0) { "Messages to keep cannot be negative." }

        val settings = settingsStore.settingsFlow.first()
        val configuredModel = settings.findModelById(settings.compressModelId)
        val configuredProvider = configuredModel?.findProvider(settings.providers)
        val conversationModel = settings.getChatModelForAssistant(conversation.assistantId)
        val conversationProvider = conversationModel?.findProvider(settings.providers)
        val binding = resolveCompressionModelBinding(
            configuredModel = configuredModel,
            configuredProvider = configuredProvider,
            configuredModelIsImplicitDefault = settings.compressModelId == DEFAULT_AUTO_MODEL_ID,
            conversationModel = conversationModel,
            conversationProvider = conversationProvider,
        )
        val model = binding.model
        val provider = binding.provider

        val providerHandler = providerManager.getProviderByType(provider)
        val allMessages = conversation.currentMessages

        // Split messages into those to compress and those to keep
        val retainedCount = keepRecentMessages.coerceAtMost(allMessages.size)
        val messagesToCompress = allMessages.dropLast(retainedCount)
        val messagesToKeep = allMessages.takeLast(retainedCount)
        if (messagesToCompress.isEmpty()) {
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        }

        suspend fun compressMessages(messages: List<UIMessage>): String {
            val contentToCompress = messages.joinToString("\n\n") { it.summaryAsText() }
            val prompt = settings.compressPrompt.applyPlaceholders(
                "content" to contentToCompress,
                "target_tokens" to targetTokens.toString(),
                "additional_context" to if (additionalPrompt.isNotBlank()) {
                    "Additional instructions from user: $additionalPrompt"
                } else "",
                "locale" to Locale.getDefault().displayName
            )

            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = backgroundTextGenerationParams(model).copy(maxTokens = targetTokens),
            )

            return result.choices.firstOrNull()?.message?.toText()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("Compression model returned no usable summary.")
        }

        // Do not fan out manual compression requests concurrently. Some OpenAI-compatible
        // gateways accept ordinary chat but reject parallel large summary requests with 400/429.
        val compressedSummaries = buildList {
            splitManualCompressionMessages(
                messages = messagesToCompress,
                contextWindowTokens = model.userContextWindowTokens,
                targetTokens = targetTokens,
            ).forEach { chunk ->
                add(compressMessages(chunk))
            }
        }

        // Create a stable manual-compression prefix followed by the exact requested tail.
        val newMessageNodes = buildManualCompressionMessages(
            compressedSummaries = compressedSummaries,
            messagesToKeep = messagesToKeep,
        ).map { it.toMessageNode() }
        val newConversation = conversation.copy(
            messageNodes = newMessageNodes,
            chatSuggestions = emptyList(),
        )

        saveConversation(conversationId, newConversation)
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }

    // ---- 通知 ----

    private fun sendGenerationDoneNotification(conversationId: Uuid, senderName: String) {
        // 先取�?Live Update 通知
        cancelLiveUpdateNotification(conversationId)

        val conversation = getConversationFlow(conversationId).value
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 1
        ) {
            title = senderName
            content = conversation.currentMessages.lastOrNull()?.toText()?.take(50)?.trim() ?: ""
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun getLiveUpdateNotificationId(conversationId: Uuid): Int {
        return conversationId.hashCode() + 10000
    }

    private fun sendLiveUpdateNotification(
        conversationId: Uuid,
        messages: List<UIMessage>,
        senderName: String
    ) {
        val lastMessage = messages.lastOrNull() ?: return
        val parts = lastMessage.parts

        // 确定当前状�?
        val (chipText, statusText, contentText) = determineNotificationContent(parts)

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = getLiveUpdateNotificationId(conversationId)
        ) {
            title = senderName
            content = contentText
            subText = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            useBigTextStyle = true
            contentIntent = getPendingIntent(context, conversationId)
            requestPromotedOngoing = true
            shortCriticalText = chipText
        }
    }

    private fun determineNotificationContent(parts: List<UIMessagePart>): Triple<String, String, String> {
        // 检查最近的 part 来确定状�?
        val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()

        return when {
            // 正在执行工具
            lastTool != null && !lastTool.isExecuted -> {
                // MCP tools are exposed as `mcp__<serverSlug>_<serverName>__<toolName>`; strip
                // both the prefix and the server segment so the notification shows the bare tool
                // name. Non-MCP tool names (no `mcp__` prefix) fall through unchanged via the
                // missingDelimiterValue, instead of being truncated at an embedded `__`.
                val toolName = lastTool.toolName
                    .removePrefix("mcp__")
                    .substringAfter("__", missingDelimiterValue = lastTool.toolName.removePrefix("mcp__"))
                Triple(
                    context.getString(R.string.notification_live_update_chip_tool),
                    context.getString(R.string.notification_live_update_tool, toolName),
                    lastTool.input.take(100)
                )
            }
            // 正在思考（Reasoning 未结束）
            lastReasoning != null && lastReasoning.finishedAt == null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_thinking),
                    context.getString(R.string.notification_live_update_thinking),
                    lastReasoning.reasoning.takeLast(200)
                )
            }
            // 正在写回�?
            lastText != null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_writing),
                    lastText.text.takeLast(200)
                )
            }
            // 默认状�?
            else -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_title),
                    ""
                )
            }
        }
    }

    private fun cancelLiveUpdateNotification(conversationId: Uuid) {
        context.cancelNotification(getLiveUpdateNotificationId(conversationId))
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ---- 对话状态更�?----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.replaceState(conversation)
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        mergeConversationState(conversationId, update)
    }

    private fun mergeConversationState(
        conversationId: Uuid,
        update: (Conversation) -> Conversation,
    ): Conversation {
        // ConversationSession serializes read-modify-write updates so concurrent writers
        // cannot overwrite each other. Also routes through checkFilesDelete so attached files keep
        // being garbage-collected when removed from the conversation.
        val session = getOrCreateSession(conversationId)
        return session.updateState { current ->
            val next = update(current)
            if (next.id != conversationId) current
            else {
                checkFilesDelete(next, current)
                next
            }
        }
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessions.containsKey(conversationId)) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return // 新会话且为空时不保存
        }
        // Refuse to overwrite a non-empty stored row with an empty in-memory snapshot.
        // This is the silent-data-loss guard: handleToolApproval / stopGeneration / etc.
        // could be called against an unhydrated session (post-restart), build an empty
        // updatedConversation, and call saveConversation. Without this guard we'd wipe
        // the Pending tool the user was trying to approve.
        if (exists && conversation.messageNodes.isEmpty()) {
            val storedHasContent = runCatching {
                conversationRepo.getConversationById(conversation.id)?.messageNodes?.isNotEmpty() == true
            }.getOrDefault(false)
            if (storedHasContent) {
                Log.w(TAG, "saveConversation: refusing to overwrite non-empty $conversationId with empty snapshot �?likely an unhydrated session")
                return
            }
        }

        val updatedConversation = conversation.copy()
        updateConversation(conversationId, updatedConversation)

        if (!exists) {
            conversationRepo.insertConversation(updatedConversation)
        } else {
            conversationRepo.updateConversation(updatedConversation)
        }
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkConversation = Conversation(
            id = Uuid.random(),
            assistantId = currentConversation.assistantId,
            messageNodes = copiedNodes,
            customSystemPrompt = currentConversation.customSystemPrompt,
            modeInjectionIds = currentConversation.modeInjectionIds,
            lorebookIds = currentConversation.lorebookIds,
        )

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // 停止当前会话生成任务（不清理会话缓存�?
    suspend fun stopGeneration(conversationId: Uuid) {
        submitEmergency(conversationId, StopCommand(), CommandOrigin.APP_UI)
    }

}
