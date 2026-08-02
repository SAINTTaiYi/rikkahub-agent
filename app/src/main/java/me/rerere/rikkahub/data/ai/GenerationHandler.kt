package me.rerere.rikkahub.data.ai

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.service.AgentOverlay
import me.rerere.rikkahub.service.RikkaAccessibilityService
import me.rerere.rikkahub.assistant.InvocationSurfaceContextProvider
import me.rerere.rikkahub.assistant.ActivityOverlayToolHandoffPolicy
import me.rerere.rikkahub.assistant.SystemAssistantActivityOverlayCoordinator
import me.rerere.rikkahub.assistant.SystemAssistantHostKind
import me.rerere.rikkahub.assistant.toProviderAddendum
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.accumulate
import me.rerere.ai.core.merge
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.GenerationTerminal
import me.rerere.ai.ui.GenerationTerminalTracker
import me.rerere.ai.ui.GenerationCompletionPolicy
import me.rerere.ai.ui.GenerationOutcome
import me.rerere.ai.ui.FinalAnswerRecoveryDecision
import me.rerere.ai.ui.FinalAnswerRecoveryAttemptDecision
import me.rerere.ai.ui.FinalAnswerRecoveryAttemptPolicy
import me.rerere.ai.ui.FinalAnswerRecoveryFailure
import me.rerere.ai.ui.FinalAnswerRecoveryMessagePolicy
import me.rerere.ai.ui.FinalAnswerRecoveryPolicy
import me.rerere.ai.ui.FinalAnswerRecoveryStatus
import me.rerere.ai.ui.UIMessageAnnotation
import me.rerere.ai.ui.UIMessageState
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.MessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_FINAL_ANSWER_REMINDER_PROMPT
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.diagnostics.RecentGenerationDiagnostics
import me.rerere.rikkahub.diagnostics.GenerationDiagnosticHandle
import me.rerere.rikkahub.diagnostics.RequestBreakdownDiagnostic
import java.io.File
import java.security.MessageDigest
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.ai.transformers.transforms
import me.rerere.rikkahub.data.ai.transformers.visualTransforms
import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits
import me.rerere.rikkahub.data.ai.tools.buildMemoryTools
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPlanRequest
import me.rerere.rikkahub.data.ai.execution.ToolExecutionPlanResult
import me.rerere.rikkahub.data.ai.execution.ToolPreExecutionDecision
import me.rerere.rikkahub.data.ai.execution.ToolRuntime
import me.rerere.rikkahub.data.ai.execution.ToolStartableResolver
import me.rerere.rikkahub.data.ai.execution.ToolBatchCandidate
import me.rerere.rikkahub.data.ai.execution.ToolBatchExecutionOutcome
import me.rerere.rikkahub.data.ai.execution.ToolExecutionBatchCoordinator
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.ai.tools.TRANSIENT_CONVERSATION_READER_TOOL_NAMES
import me.rerere.rikkahub.data.capability.CapabilitySubject
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.security.resolveProviderBinding
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.toolcatalog.ToolDiscoverySession
import me.rerere.rikkahub.toolcatalog.ToolDiscoveryMetrics
import me.rerere.rikkahub.toolcatalog.ToolExperienceRecorder
import me.rerere.rikkahub.toolcatalog.ToolSurfaceBuilder
import me.rerere.rikkahub.utils.applyPlaceholders
import java.util.Locale
import kotlin.time.Clock
import kotlin.uuid.Uuid

private const val TAG = "GenerationHandler"
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

internal fun shouldSpillToolOutputToFile(
    toolName: String,
    totalChars: Int,
    hasShellAccess: Boolean,
): Boolean = toolName !in TRANSIENT_CONVERSATION_READER_TOOL_NAMES &&
    totalChars > MAX_TOOL_OUTPUT_CHARS && hasShellAccess
private const val FINAL_ANSWER_MAX_TOKENS = 4096
private const val FINAL_ANSWER_MAX_ATTEMPTS = 10
private const val FINAL_ANSWER_RESERVE_MS = 45_000L
private val FINAL_ANSWER_RESERVED_CUSTOM_BODY_KEYS = setOf(
    "tools",
    "tool_choice",
    "functions",
    "function_call",
    "parallel_tool_calls",
    "stream",
    "reasoning",
    "reasoning_effort",
    "thinking",
    "enable_thinking",
    "thinking_budget",
    "max_tokens",
    "max_completion_tokens",
    "max_output_tokens",
    "response_format",
)

private enum class GenerationRequestPurpose {
    NORMAL,
    FINAL_ANSWER_RECOVERY,
}

internal data class GenerationFinalizationStep(
    val forceFinalization: Boolean,
    val skipResumableTools: Boolean,
)

private data class BatchReadyTool(
    val index: Int,
    val tool: UIMessagePart.Tool,
    val toolDef: Tool,
    val args: JsonObject,
    val executionContext: ToolExecutionContext,
)

internal fun generationFinalizationStep(
    stepIndex: Int,
    maxSteps: Int,
    wallClockNeedsFinalization: Boolean,
    loopGuardNeedsFinalization: Boolean,
): GenerationFinalizationStep {
    val plannedSteps = maxSteps.coerceAtLeast(1)
    val maxStepsNeedsFinalization = stepIndex >= plannedSteps - 1
    return GenerationFinalizationStep(
        forceFinalization = wallClockNeedsFinalization ||
            loopGuardNeedsFinalization || maxStepsNeedsFinalization,
        // The extra index exists only to summarize after a tool completed on the last planned
        // step. It must never start another side effect, and budget/loop trips stop tools now.
        skipResumableTools = wallClockNeedsFinalization ||
            loopGuardNeedsFinalization || stepIndex >= plannedSteps,
    )
}

private fun List<UIMessage>.replaceLastMessage(message: UIMessage): List<UIMessage> =
    if (isEmpty()) this else dropLast(1) + message

private fun List<UIMessage>.currentTurnForFinalAnswer(): List<UIMessage> {
    val turnStart = indexOfLast { message ->
        message.role == MessageRole.USER &&
            message.annotations.none { it is UIMessageAnnotation.Steering }
    }
    return if (turnStart >= 0) subList(turnStart, size) else takeLast(1)
}

private fun List<UIMessage>.redactedInputCharacterCount(): Int = sumOf { message ->
    message.parts.sumOf { part ->
        when (part) {
            is UIMessagePart.Text -> part.text.length
            is UIMessagePart.Reasoning -> part.reasoning.length
            is UIMessagePart.Tool -> part.input.length + part.output.sumOf { output ->
                when (output) {
                    is UIMessagePart.Text -> output.text.length
                    is UIMessagePart.Reasoning -> output.reasoning.length
                    else -> 1
                }
            }
            else -> 1
        }
    }
}

private fun UIMessage.withFinalAnswerRecovery(
    commandId: String,
    reason: String,
    status: FinalAnswerRecoveryStatus,
    attempt: Int,
    state: UIMessageState,
): UIMessage {
    val marker = UIMessageAnnotation.FinalAnswerRecovery(
        commandId = commandId,
        reason = reason.take(200),
        status = status,
        attempt = attempt,
    )
    val updatedAnnotations = annotations.filterNot { annotation ->
        annotation is UIMessageAnnotation.FinalAnswerRecovery &&
            annotation.commandId == commandId
    } + marker
    val terminal = state == UIMessageState.COMPLETED ||
        state == UIMessageState.INTERRUPTED ||
        state == UIMessageState.INCOMPLETE_NO_VISIBLE_ANSWER ||
        state == UIMessageState.FAILED
    return copy(
        annotations = updatedAnnotations,
        state = state,
        finishedAt = if (terminal) {
            Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        } else {
            null
        },
    )
}

/**
 * Replace older tool-result `Image` parts with a small text elision so the same JPEGs
 * aren't re-encoded into base64 on every subsequent step. We keep the
 * [IMAGE_KEEP_LAST_N_TOOL_RESULTS] most-recent tool-result-bearing assistant messages
 * verbatim and elide everything older. User uploads (`role=USER`) are NEVER elided —
 * those are real input the model needs to reason over. Assistant-generated images
 * (model image-gen output) are also kept verbatim as those are visible product, not
 * intermediate reasoning state.
 */
private fun List<UIMessage>.ageOldToolImages(): List<UIMessage> {
    var toolResultsWithImagesSeen = 0
    return this.asReversed().map { msg ->
        if (msg.role == MessageRole.USER) return@map msg
        val hasImageInTool = msg.parts.any { p ->
            p is UIMessagePart.Tool && p.output.any { it is UIMessagePart.Image }
        }
        if (!hasImageInTool) return@map msg
        toolResultsWithImagesSeen++
        if (toolResultsWithImagesSeen <= IMAGE_KEEP_LAST_N_TOOL_RESULTS) return@map msg
        val newParts = msg.parts.map { part ->
            if (part is UIMessagePart.Tool) {
                val newOutput = part.output.map { o ->
                    if (o is UIMessagePart.Image) {
                        UIMessagePart.Text(
                            "[image elided — original at ${o.url}; superseded by newer screenshots]"
                        )
                    } else o
                }
                part.copy(output = newOutput)
            } else part
        }
        msg.copy(parts = newParts)
    }.asReversed()
}

@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>,
        val persistenceBarrier: GenerationPersistenceBarrier = GenerationPersistenceBarrier.NONE,
    ) : GenerationChunk
}

@Serializable
enum class GenerationPersistenceBarrier {
    NONE,
    PENDING_APPROVAL,
}

private const val TAG_GH_LOOP = "GenHandlerLoop"

/**
 * If the model calls the same tool with the same exact JSON args this many times within a
 * single user turn, we refuse the next execution and inject a "loop_detected" envelope. The
 * threshold is INCLUSIVE of the prior occurrences, so a value of 3 means: first call runs,
 * second call runs, third call runs — fourth identical call is blocked. Picked low enough
 * that runaway loops can't drain the user's API tokens but high enough to allow legitimate
 * retries (a notification key going stale between read and dismiss, etc.).
 */
private const val LOOP_GUARD_REPEAT_THRESHOLD = 3

// The per-turn wall-clock budget was hardcoded here (most recently 10 min). It now lives in
// ToolRuntimeLimits.turnBudgetMs (default 10 min), user-configurable via Settings -> Termux;
// every read site below uses that holder directly.

/**
 * Max number of times the loop guard can trip in a single turn before we force-end the
 * turn entirely. Prevents the "model keeps trying different tools, each gets loop-detected"
 * pattern that produced the 27-step / 141K-token disaster. Ultra keeps the detector but
 * allows a long, bounded chain before it asks the model for a tool-free final response.
 */
// The wall-clock budget, user cancellation, and tool safety checks still bound execution.
internal const val ULTRA_MAX_TOOL_LOOP_STEPS = 512

internal fun ultraLoopGuardNeedsFinalization(loopGuardTripCount: Int): Boolean =
    loopGuardTripCount >= ULTRA_MAX_TOOL_LOOP_STEPS

/**
 * Number of most-recent tool-result-bearing messages whose `Image` parts are kept
 * verbatim in the prompt. Older tool-result images are replaced with a small text
 * elision so the same JPEG isn't re-encoded into base64 on every step. Without this
 * a screen-automation turn that takes 5 screenshots makes the provider re-pay
 * ~1–2MB × 5 base64 encode + upload on every subsequent step.
 *
 * 2 is the smallest value that lets the model do "look at this screenshot, decide
 * action; take new screenshot, compare" — needs both the previous and the current
 * screenshot in context. Anything older has been superseded.
 */
private const val IMAGE_KEEP_LAST_N_TOOL_RESULTS = 2

/**
 * Some read-only tools measure a real-time signal where re-calling after a TTL is
 * legitimate (battery drains, screens change, sensors update). For these, the loop guard
 * lets identical calls through if the most recent identical call is older than the TTL.
 * Without this, asking the model "what's the battery now?" after a previous reading just
 * regurgitates the stale value and the user has no idea.
 *
 * Tools NOT in this map are treated as side-effecting / idempotent-input: re-calling with
 * identical args is a loop, not a refresh. Add new freshness-sensitive tools here.
 */
private val FRESHNESS_TTL_MS_BY_TOOL: Map<String, Long> = mapOf(
    "get_battery_status" to 30_000L,
    "get_audio_info" to 30_000L,
    "get_telephony_info" to 30_000L,
    "get_wifi_info" to 30_000L,
    "get_storage_info" to 60_000L,
    "get_brightness" to 10_000L,
    "get_volume" to 10_000L,
    "get_location" to 30_000L,
    "get_time_info" to 5_000L,
    "read_sensor" to 5_000L,
    "take_screenshot" to 5_000L,
    "read_window_tree" to 5_000L,
    "list_active_notifications" to 5_000L,
    "list_jobs" to 60_000L,
)

/**
 * UI-observation tools that read screen/device state without changing it. Used by the loop
 * guard's reset rule below: when the model drives a UI it runs an act-observe cycle and
 * naturally repeats the same observation call (read_window_tree / take_screenshot with
 * identical args) after every action. Those repeats are progress, NOT a loop, so an
 * intervening ACTION (any executed tool NOT in this set) resets the observation repeat count.
 * Tools that ARE in this set do not reset each other, so a model that merely alternates
 * observers on a frozen screen still trips the guard (the token-drain case we must catch).
 *
 * This is the freshness-sensitive realtime readers plus find_node (the other pure screen
 * reader). Keep it to genuine read-only observers: wrongly adding an ACTION tool here would
 * stop it from resetting the counter and reintroduce the false-positive loop_detected.
 */
private val READ_ONLY_OBSERVATION_TOOLS: Set<String> =
    FRESHNESS_TTL_MS_BY_TOOL.keys + "find_node"

/** One prior executed tool call in the current turn, in chronological order. */
internal data class PriorToolCall(
    val toolName: String,
    val signature: String,
    val epochMs: Long,
)

internal data class LoopGuardDecision(
    val block: Boolean,
    val priorOccurrences: Int,
)

/**
 * Pure, testable loop-detection decision, extracted from [GenerationHandler.generateText] so
 * the act-observe reset and freshness-TTL rules can be unit-tested without an Android Context.
 */
internal object LoopGuard {
    fun evaluate(
        priorCalls: List<PriorToolCall>,
        toolName: String,
        signature: String,
        nowMs: Long,
        threshold: Int = LOOP_GUARD_REPEAT_THRESHOLD,
        readOnlyTools: Set<String> = READ_ONLY_OBSERVATION_TOOLS,
        freshnessTtlMs: Map<String, Long> = FRESHNESS_TTL_MS_BY_TOOL,
    ): LoopGuardDecision {
        // For observation tools, only repeats since the most recent ACTION count: acting on
        // the world is progress, so identical observations taken before it are stale for
        // loop-detection purposes. Side-effecting tools count every identical call in the
        // turn (re-sending the same message 3x is a loop regardless of what ran between).
        val relevant = if (toolName in readOnlyTools) {
            val lastActionIdx = priorCalls.indexOfLast { it.toolName !in readOnlyTools }
            if (lastActionIdx >= 0) priorCalls.subList(lastActionIdx + 1, priorCalls.size)
            else priorCalls
        } else {
            priorCalls
        }
        val matching = relevant.filter { it.signature == signature }
        val priorOccurrences = matching.size
        if (priorOccurrences < threshold) return LoopGuardDecision(false, priorOccurrences)
        // Freshness-TTL bypass: a real-time reader re-called after its TTL is a refresh, not
        // a loop; let it through so the model gets a fresh reading instead of a stale one.
        val ttl = freshnessTtlMs[toolName]
        if (ttl != null && nowMs - matching.maxOf { it.epochMs } >= ttl) {
            return LoopGuardDecision(false, priorOccurrences)
        }
        return LoopGuardDecision(true, priorOccurrences)
    }
}

class GenerationHandler(
    private val context: Context,
    private val providerManager: ProviderManager,
    private val json: Json,
    private val memoryRepo: MemoryRepository,
    private val conversationRepo: ConversationRepository,
    private val aiLoggingManager: AILoggingManager,
    private val systemPromptBuilder: SystemPromptBuilder,
    private val toolExecutionGate: ToolExecutionGate,
    private val toolRuntime: ToolRuntime,
    private val toolStartableResolver: ToolStartableResolver,
    private val toolExecutionBatchCoordinator: ToolExecutionBatchCoordinator,
    private val contextBroker: me.rerere.rikkahub.context.ContextBroker,
    private val contextDiagnosticsStore: me.rerere.rikkahub.context.ContextDiagnosticsStore,
    private val secondUserSecretVault: me.rerere.rikkahub.security.SecondUserSecretVault? = null,
    private val secretPlaintextSessions: me.rerere.rikkahub.security.SecretPlaintextSessionManager? = null,
    private val ephemeralToolResults: me.rerere.rikkahub.security.EphemeralToolResultStore? = null,
    private val runtimeSecretRedactor: me.rerere.rikkahub.security.RuntimeSecretRedactor? = null,
    private val toolExperienceRecorder: ToolExperienceRecorder? = null,
) {
    fun generateText(
        settings: Settings,
        model: Model,
        messages: List<UIMessage>,
        inputTransformers: List<InputMessageTransformer> = emptyList(),
        outputTransformers: List<OutputMessageTransformer> = emptyList(),
        assistant: Assistant,
        // Privilege / surface override from ChatService. It is deliberately never sourced from
        // Assistant.unrestricted; that field is now a migration marker only.
        unrestrictedOverride: Boolean = false,
        capabilitySubject: CapabilitySubject? = null,
        selectedPrivilegedConversation: Boolean = false,
        memories: List<AssistantMemory>? = null,
        tools: List<Tool> = emptyList(),
        /** Definitions used only to resume already-persisted calls; never sent to a Provider. */
        runtimeOnlyTools: List<Tool> = emptyList(),
        /** Per-parent-run progressive directory selection for the active second user only. */
        toolDiscoverySession: ToolDiscoverySession? = null,
        /** False for a restricted child profile that did not inherit `memory_tool`. */
        memoryToolAllowed: Boolean = true,
        startableTools: Map<String, me.rerere.rikkahub.data.ai.tools.StartableTool> = emptyMap(),
        maxSteps: Int = ULTRA_MAX_TOOL_LOOP_STEPS + 1,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        // Returns true when the user has pre-approved [toolName] for this turn (e.g.
        // "Allow for this chat" or "Always Allow" granted earlier). When true, the loop
        // below skips the Pending flip and lets the tool execute. ChatService injects the
        // closure that reads ToolApprovalAllowList + ToolApprovalPreferences. Default
        // returns false so callers that don't care still get vanilla approval gating.
        isToolAutoApproved: suspend (toolName: String) -> Boolean = { false },
        // Optional per-call addendum appended to the system prompt. Used by surfaces that
        // need the model to know runtime context (e.g. "you're talking via Telegram, the
        // chat_id is 12345") without polluting the user message body — without this the
        // preamble is replayed in user history every turn, burning ~80 tokens × N turns.
        systemAddendum: String? = null,
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        // P0: The origin of this tool call. Determines which security policies apply.
        // LocalChat is the default (safe fallback). Remote callers MUST set this explicitly.
        callOrigin: ToolCallOrigin = ToolCallOrigin.LocalChat,
        commandOrigin: me.rerere.rikkahub.service.chat.CommandOrigin =
            me.rerere.rikkahub.service.chat.CommandOrigin.INTERNAL,
        conversationId: Uuid? = null,
        commandId: Uuid? = null,
        runControl: GenerationRunControl? = null,
        isHeadless: Boolean = false,
        isSubAgent: Boolean = false,
        invocationSurfaceContextProvider: InvocationSurfaceContextProvider? = null,
        isEmergencyStopActive: suspend () -> Boolean = { false },
    ): Flow<GenerationChunk> {
        val sensitiveOwnerToolInputs =
            mutableMapOf<String, me.rerere.rikkahub.security.SensitiveToolArgument>()
        return flow {
        // Suppress unused warnings for API-compat params restored for ChatService call sites.
        // (No thinking/answer finalize experiment — intentionally not used here.)
        @Suppress("UNUSED_EXPRESSION", "UNUSED_VARIABLE")
        val _compat = Pair(unrestrictedOverride, commandId)
        @Suppress("UNUSED_VARIABLE")
        val _emergency = isEmergencyStopActive
        val configuredProvider = model.findProvider(settings.providers) ?: error("Provider not found")
        // A second-user Provider binding is resolved only in the local execution layer. Its
        // plaintext never becomes part of settings, model messages, tool output, or diagnostics.
        val provider = resolveSecondUserProviderBinding(
            configuredProvider = configuredProvider,
            capabilitySubject = capabilitySubject,
            conversationId = conversationId,
            origin = callOrigin,
        )
        val providerImpl = providerManager.getProviderByType(provider)
        val secretEgressBinding = capabilitySubject
            ?.takeIf { it.type == me.rerere.rikkahub.data.capability.SubjectType.LOCAL_SECOND_USER }
            ?.let { subject ->
                val active = me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry.current()
                if (active?.subjectId == subject.id &&
                    active.assistantId == assistant.id &&
                    active.conversationId == conversationId
                ) {
                    me.rerere.rikkahub.security.SecretPlaintextSessionBinding(
                        authoritySubjectId = active.subjectId,
                        authorityEpoch = active.authorityEpoch,
                        assistantId = assistant.id.toString(),
                        conversationId = active.conversationId.toString(),
                        modelId = model.id.toString(),
                        providerId = provider.id.toString(),
                    )
                } else null
            }

        // Replay safety: scan the input messages for tools that were Approved + began
        // execution but never produced output (process killed mid-execute). Without this
        // pass, the loop below would treat them as "Approved, ready to run" and execute
        // them AGAIN on replay — could double-charge a remote, duplicate a message send,
        // re-overwrite a file. Flip them to Denied so the model sees a deterministic
        // envelope and decides whether to retry deliberately.
        var messages: List<UIMessage> = messages.map { msg ->
            val newParts = msg.parts.map { part ->
                if (part is UIMessagePart.Tool && part.isInterruptedAttempt) {
                    Log.w(TAG, "replay: ${part.toolName} (${part.toolCallId}) had executionStartedAt set with empty output → Denied(interrupted_unknown_outcome)")
                    part.copy(approvalState = ToolApprovalState.Denied(
                        "interrupted_unknown_outcome: a previous attempt to execute this tool started " +
                            "but did not complete (process killed mid-execute). The side effect MAY OR " +
                            "MAY NOT have happened. Verify the target state before retrying — do not " +
                            "blindly re-run the same call."
                    ))
                } else part
            }
            if (newParts == msg.parts) msg else msg.copy(parts = newParts)
        }
        // One assistant UI message can span many provider calls separated by tool execution.
        // Keep a call-level accumulator outside generateInternal so the displayed usage is the
        // real total for the whole turn instead of a field-wise mix of unrelated calls.
        var accumulatedUsage: TokenUsage? = messages.lastOrNull()
            ?.takeIf { it.role == MessageRole.ASSISTANT }
            ?.usage
        fun parseToolArguments(tool: UIMessagePart.Tool): Result<JsonElement> {
            val sensitive = sensitiveOwnerToolInputs.remove(tool.toolCallId)
            return try {
                if (sensitive == null) {
                    runCatching { json.parseToJsonElement(tool.input.ifBlank { "{}" }) }
                } else {
                    sensitive.use { chars ->
                        runCatching { json.parseToJsonElement(chars.concatToString().ifBlank { "{}" }) }
                    }
                }
            } finally {
                sensitive?.close()
            }
        }

        val turnStartMs = android.os.SystemClock.elapsedRealtime()
        // Device context is collected exactly once for a run and is only appended to this
        // provider request. It is neither turned into a user message nor persisted in chat
        // history or memory capture. The factory returns null when identity is incomplete.
        val autoContextSystemAddendum = conversationId?.let { id ->
            me.rerere.rikkahub.context.ContextRequestFactory.create(
                commandOrigin = commandOrigin,
                toolCallOrigin = callOrigin,
                assistant = assistant,
                conversationId = id.toString(),
                runId = runControl?.runId?.toString(),
                commandId = commandId?.toString(),
                isHeadless = isHeadless,
                isSubAgent = isSubAgent,
            )
        }?.let { request ->
            val snapshot = contextBroker.collect(request)
            contextDiagnosticsStore.record(request, snapshot)
            snapshot.toSystemAddendum()
        }
        var loopGuardTripCount = 0
        val recoveryCommandKey = commandId?.toString()
            ?: "${conversationId ?: "unknown"}:${messages.lastOrNull { it.role == MessageRole.USER }?.id}"
        val generationDiagnostics = RecentGenerationDiagnostics.begin(recoveryCommandKey)
        var finalAnswerRecoveryAttempts = messages.asSequence()
            .flatMap { it.annotations.asSequence() }
            .filterIsInstance<UIMessageAnnotation.FinalAnswerRecovery>()
            .filter { it.commandId == recoveryCommandKey }
            .maxOfOrNull { it.attempt }
            ?: 0
        var modelCallIndex = 0

        // One extra index is reserved solely for a tool-free summary after a tool that completed
        // on the last planned step. [generationFinalizationStep] prevents that index from starting
        // another side effect.
        generationLoop@ for (stepIndex in 0..maxSteps.coerceAtLeast(1)) {
            var stepTerminal: GenerationTerminal? = null
            // Wall-clock cap: any single user turn that has been running longer than the
            // budget is force-ended, regardless of whether the model wants more steps.
            // This is the second line of defence after maxSteps; without it a model that
            // discovers many distinct tool calls (each within the loop guard) can still
            // run for hours.
            val elapsedMs = android.os.SystemClock.elapsedRealtime() - turnStartMs
            val finalizationThresholdMs =
                (ToolRuntimeLimits.turnBudgetMs - FINAL_ANSWER_RESERVE_MS).coerceAtLeast(0L)
            val wallClockNeedsFinalization = elapsedMs >= finalizationThresholdMs
            // Repeated loop-guard trips mean the model is flailing: it bumps into the
            // guard, picks a different tool, that one also gets guarded, and so on. After
            // N trips we just stop — the model is not going to recover, and every extra
            // step is paid for in tokens.
            val loopGuardNeedsFinalization =
                ultraLoopGuardNeedsFinalization(loopGuardTripCount)
            val finalizationStep = generationFinalizationStep(
                stepIndex = stepIndex,
                maxSteps = maxSteps,
                wallClockNeedsFinalization = wallClockNeedsFinalization,
                loopGuardNeedsFinalization = loopGuardNeedsFinalization,
            )
            val forceFinalization = finalizationStep.forceFinalization
            if (forceFinalization) {
                Log.w(
                    TAG,
                    "generateText: entering tool-free finalization at step #$stepIndex " +
                        "(wallClock=$wallClockNeedsFinalization, loopGuard=$loopGuardNeedsFinalization, " +
                        "maxSteps=${stepIndex >= maxSteps.coerceAtLeast(1) - 1})",
                )
            }

            Log.i(TAG, "streamText: start step #$stepIndex (${model.id})")

            var pendingTools = messages.lastOrNull()?.getTools()?.filter {
                it.canResumeExecution
            } ?: emptyList()
            if (forceFinalization && finalizationStep.skipResumableTools && pendingTools.isNotEmpty()) {
                val skippedIds = pendingTools.mapTo(hashSetOf()) { it.toolCallId }
                val reason = when {
                    wallClockNeedsFinalization -> "turn_time_budget_exhausted"
                    loopGuardNeedsFinalization -> "turn_loop_guard_exhausted"
                    else -> "turn_step_budget_exhausted"
                }
                val lastMessage = messages.last()
                messages = messages.replaceLastMessage(
                    lastMessage.copy(
                        parts = lastMessage.parts.map { part ->
                            if (part is UIMessagePart.Tool && part.toolCallId in skippedIds) {
                                part.copy(
                                    approvalState = ToolApprovalState.Denied(reason),
                                    output = listOf(
                                        UIMessagePart.Text(
                                            json.encodeToString(buildJsonObject {
                                                put("error", JsonPrimitive("tool_skipped_for_finalization"))
                                                put("detail", JsonPrimitive(reason))
                                            }),
                                        ),
                                    ),
                                )
                            } else {
                                part
                            }
                        },
                    ),
                )
                pendingTools = emptyList()
                emit(GenerationChunk.Messages(messages))
            }
            val steeringDeliveries = runControl?.let { control ->
                takeSteeringForProviderCheckpoint(
                    runControl = control,
                    modelCallIndex = modelCallIndex++,
                    hasResumableTools = pendingTools.isNotEmpty(),
                )
            }.orEmpty()
            val providerTailMessages = ProviderTailMessages.fromSteering(steeringDeliveries)
            val effectiveSystemAddendum = listOfNotNull(
                systemAddendum,
                autoContextSystemAddendum,
                if (forceFinalization) {
                    settings.finalAnswerReminderPrompt.trim().ifBlank {
                        DEFAULT_FINAL_ANSWER_REMINDER_PROMPT
                    }
                } else {
                    null
                },
            ).joinToString("\n\n").ifBlank { null }

            val completingAlreadyAcceptedTools = pendingTools.isNotEmpty() &&
                !finalizationStep.skipResumableTools
            val candidateTools = buildList {
                if (assistant.enableMemory && memoryToolAllowed) {
                    val memoryAssistantId = if (assistant.useGlobalMemory) {
                        MemoryRepository.GLOBAL_MEMORY_ID
                    } else {
                        assistant.id.toString()
                    }
                    buildMemoryTools(
                        json = json,
                        onCreation = { input ->
                            memoryRepo.addMemory(
                                scopeId = memoryAssistantId,
                                input = input,
                                originAssistantId = assistant.id.toString(),
                            )
                        },
                        onUpdate = { id, input ->
                            memoryRepo.updateMemory(id, input)
                        },
                        onDelete = { id ->
                            memoryRepo.deleteMemory(id)
                        },
                        onQuery = { input ->
                            memoryRepo.queryDetailed(
                                assistantId = assistant.id,
                                query = input.query,
                                includeGlobal = assistant.useGlobalMemory,
                                limit = input.limit,
                                tags = input.tags,
                                kind = input.kind,
                                includeArchived = input.includeArchived,
                            )
                        },
                    ).let(this::addAll)
                }
                addAll(tools)
            }
            val candidateSurface = ToolSurfaceBuilder.build(candidateTools)
            val providerToolsInternal = buildList {
                Log.i(TAG, "generateInternal: build tools($assistant)")
                if (!forceFinalization || completingAlreadyAcceptedTools) {
                    val pinnedToolNames = pendingTools.mapTo(linkedSetOf()) { it.toolName }
                    if (toolDiscoverySession != null) {
                        addAll(toolDiscoverySession.providerTools(candidateSurface, pinnedToolNames))
                    } else {
                        addAll(candidateSurface.definitions)
                    }
                }
            }
            val toolsInternal = (providerToolsInternal + runtimeOnlyTools)
                .distinctBy { it.name }

            // Mixed-state guard: if the last message has tools STILL in Pending (waiting
            // on user approval keyboard) but nothing canResumeExecution, the existing
            // path would call generateInternal and start a brand-new assistant turn,
            // orphaning the Pending tool. Bail out instead and let handleToolApproval
            // re-enter when the user taps the keyboard.
            if (pendingTools.isEmpty()) {
                val lastHasPending = messages.lastOrNull()?.parts?.any { p ->
                    p is UIMessagePart.Tool && p.isPending
                } == true
                if (lastHasPending) {
                    Log.i(TAG, "generateText: last message has Pending tools; waiting for approval, not regenerating")
                    break
                }
            }

            val toolsToProcess: List<UIMessagePart.Tool>

            // Skip generation if we have approved/denied tool calls to handle
            if (pendingTools.isEmpty()) {
                try {
                    stepTerminal = generateInternal(
                        assistant = assistant,
                        settings = settings,
                        systemAddendum = effectiveSystemAddendum,
                        messages = messages,
                        onUpdateMessages = {
                            messages = it.transforms(
                                transformers = outputTransformers,
                                context = context,
                                model = model,
                                assistant = assistant,
                                settings = settings
                            )
                            emit(
                                GenerationChunk.Messages(
                                    messages.visualTransforms(
                                        transformers = outputTransformers,
                                        context = context,
                                        model = model,
                                        assistant = assistant,
                                        settings = settings
                                    )
                                )
                            )
                        },
                        transformers = inputTransformers,
                        model = model,
                        providerImpl = providerImpl,
                        provider = provider,
                        tools = providerToolsInternal,
                        memories = memories ?: emptyList(),
                        stream = if (forceFinalization) false else assistant.streamOutput,
                        processingStatus = processingStatus,
                        conversationSystemPrompt = conversationSystemPrompt,
                        conversationModeInjectionIds = conversationModeInjectionIds,
                        conversationLorebookIds = conversationLorebookIds,
                        workspaceCwd = workspaceCwd,
                        runControl = runControl,
                        contextMessages = if (forceFinalization) {
                            messages.currentTurnForFinalAnswer()
                        } else if (messages.lastOrNull()?.getTools()?.isNotEmpty() == true) {
                            messages.selectToolLoopContinuationContext()
                        } else {
                            null
                        },
                        requestPurpose = if (forceFinalization) {
                            GenerationRequestPurpose.FINAL_ANSWER_RECOVERY
                        } else {
                            GenerationRequestPurpose.NORMAL
                        },
                        diagnosticHandle = generationDiagnostics,
                        providerTailMessages = providerTailMessages,
                        steeringDeliveries = steeringDeliveries,
                        invocationSurfaceContextProvider = invocationSurfaceContextProvider,
                        callOrigin = callOrigin,
                        secretEgressBinding = secretEgressBinding,
                        onRawSensitiveToolInput = { tool ->
                            sensitiveOwnerToolInputs.remove(tool.toolCallId)?.close()
                            sensitiveOwnerToolInputs[tool.toolCallId] =
                                me.rerere.rikkahub.security.SensitiveToolArgument.from(tool.input)
                        },
                        conversationId = conversationId,
                        commandId = commandId,
                        toolDiscoveryMetrics = toolDiscoverySession?.metrics(),
                        usageBase = accumulatedUsage,
                    )
                } catch (t: Throwable) {
                    if (t is CancellationException &&
                        runControl?.hasUndeliveredSteering() == true &&
                        !runControl.isRunCancellationRequested()
                    ) {
                        continue@generationLoop
                    }
                    // CancellationException is honoured verbatim — stopGeneration has its
                    // own cancelToolByUser path that marks tools cancelled. We only need
                    // to handle non-cancel failures here.
                    if (t !is CancellationException) {
                        // Server 5xx, JSON parse failure, OOM during chunk-merge, etc. Without
                        // this transition, any tool already at Auto/Pending in the just-built
                        // assistant message is stranded — the next user turn replays the
                        // conversation with tool parts in an in-between state and downstream
                        // filtering misbehaves. We mark them Denied with a generation_failed
                        // envelope so the shape is deterministic on replay.
                        val lastMsg = messages.lastOrNull()
                        if (lastMsg != null) {
                            val newParts = lastMsg.parts.map { part ->
                                if (part is UIMessagePart.Tool &&
                                    (part.approvalState is ToolApprovalState.Auto ||
                                        part.approvalState is ToolApprovalState.Pending)) {
                                    part.copy(approvalState = ToolApprovalState.Denied(
                                        "generation_failed: ${t.javaClass.simpleName}: ${t.message.orEmpty()}"
                                    ))
                                } else part
                            }
                            messages = messages.dropLast(1) + lastMsg.copy(parts = newParts)
                            emit(GenerationChunk.Messages(messages))
                        }
                    }
                    throw t
                }
                accumulatedUsage = messages.lastOrNull()?.usage ?: accumulatedUsage
                messages = messages.onGenerationFinish(
                    transformers = outputTransformers,
                    context = context,
                    model = model,
                    assistant = assistant,
                    settings = settings
                )
                messages = messages.slice(0 until messages.lastIndex) + messages.last().copy(
                    finishedAt = Clock.System.now()
                        .toLocalDateTime(TimeZone.currentSystemDefault())
                )
                emit(GenerationChunk.Messages(messages))

                // Guidance submitted while this provider call was finishing wins over the old
                // tool plan and over final-answer recovery. Completed output stays visible; only
                // side effects that have not started are converted into stable skipped results.
                if (runControl?.hasUndeliveredSteering() == true) {
                    val last = messages.last()
                    messages = messages.replaceLastMessage(
                        last.copy(
                            parts = last.parts.map { part ->
                                if (part is UIMessagePart.Tool && !part.isExecuted) {
                                    part.skippedDueToGuidance()
                                } else {
                                    part
                                }
                            },
                        ),
                    )
                    emit(GenerationChunk.Messages(messages))
                    continue@generationLoop
                }

                if (forceFinalization && messages.last().getTools().any { !it.isExecuted }) {
                    val last = messages.last()
                    messages = messages.replaceLastMessage(
                        last.copy(
                            parts = last.parts.map { part ->
                                if (part is UIMessagePart.Tool && !part.isExecuted) {
                                    part.copy(
                                        approvalState = ToolApprovalState.Denied(
                                            "finalization_tool_calls_disabled",
                                        ),
                                        output = listOf(
                                            UIMessagePart.Text(
                                                "Tool calls are disabled while generating the final answer.",
                                            ),
                                        ),
                                    )
                                } else {
                                    part
                                }
                            },
                        ),
                    )
                    emit(GenerationChunk.Messages(messages))
                }

                val tools = messages.last().getTools().filter { !it.isExecuted }
                if (tools.isEmpty()) {
                    val terminal = stepTerminal
                        ?: GenerationTerminal.missingTransportTerminal("No provider terminal was captured.")
                    val outcome = GenerationCompletionPolicy.evaluate(
                        message = messages.last(),
                        terminal = terminal,
                    )
                    generationDiagnostics.markOutcome(outcome)
                    val cancelled = runControl?.isRunCancellationRequested() == true ||
                        runControl?.isUpdateFenced() == true
                    val emergencyStop = isEmergencyStopActive()
                    val recoveryDecision = FinalAnswerRecoveryPolicy.decide(
                        outcome = outcome,
                        attempts = finalAnswerRecoveryAttempts,
                        maxAttempts = FINAL_ANSWER_MAX_ATTEMPTS,
                        cancelled = cancelled,
                        emergencyStopActive = emergencyStop,
                    )

                    if (recoveryDecision == FinalAnswerRecoveryDecision.Attempt) {
                        val recoveryReason = terminal.providerReason
                            ?: terminal.category.name.lowercase()
                        var recoveryComplete = false
                        var recoveryStream = false
                        while (finalAnswerRecoveryAttempts < FINAL_ANSWER_MAX_ATTEMPTS) {
                            currentCoroutineContext().ensureActive()
                            if (runControl?.hasUndeliveredSteering() == true) {
                                continue@generationLoop
                            }
                            val recoverySteeringDeliveries = runControl?.let { control ->
                                takeSteeringForProviderCheckpoint(
                                    runControl = control,
                                    modelCallIndex = modelCallIndex++,
                                    hasResumableTools = false,
                                )
                            }.orEmpty()
                            val recoveryTailMessages =
                                ProviderTailMessages.fromSteering(recoverySteeringDeliveries)
                            finalAnswerRecoveryAttempts += 1
                            val attempt = finalAnswerRecoveryAttempts
                            generationDiagnostics.markRecovery(attempt, "STARTED")
                            val recoveryBase = messages.replaceLastMessage(
                                messages.last().withFinalAnswerRecovery(
                                    commandId = recoveryCommandKey,
                                    reason = recoveryReason,
                                    status = FinalAnswerRecoveryStatus.STARTED,
                                    attempt = attempt,
                                    state = UIMessageState.STREAMING,
                                ),
                            )
                            messages = recoveryBase
                            emit(GenerationChunk.Messages(messages))
                            processingStatus.value =
                                "Generating final answer ($attempt/$FINAL_ANSWER_MAX_ATTEMPTS)"

                            var recoveredMessages = recoveryBase
                            var recoveryTimedOut = false
                            val recoveryResult = try {
                                val remainingRecoveryMs = ToolRuntimeLimits.turnBudgetMs -
                                    (android.os.SystemClock.elapsedRealtime() - turnStartMs)
                                val recoveryTerminal = if (remainingRecoveryMs > 0L) {
                                    withTimeoutOrNull(remainingRecoveryMs) {
                                        generateInternal(
                                            assistant = assistant,
                                            settings = settings,
                                            systemAddendum = listOfNotNull(
                                                effectiveSystemAddendum,
                                                settings.finalAnswerReminderPrompt.trim().ifBlank {
                                                    DEFAULT_FINAL_ANSWER_REMINDER_PROMPT
                                                },
                                            ).joinToString("\n\n"),
                                            messages = recoveryBase,
                                            onUpdateMessages = { recoveredMessages = it },
                                            transformers = inputTransformers,
                                            model = model,
                                            providerImpl = providerImpl,
                                            provider = provider,
                                            tools = emptyList(),
                                            memories = memories ?: emptyList(),
                                            stream = recoveryStream,
                                            processingStatus = processingStatus,
                                            conversationSystemPrompt = conversationSystemPrompt,
                                            conversationModeInjectionIds = conversationModeInjectionIds,
                                            conversationLorebookIds = conversationLorebookIds,
                                            workspaceCwd = workspaceCwd,
                                            runControl = runControl,
                                            contextMessages = recoveryBase.currentTurnForFinalAnswer(),
                                            requestPurpose = GenerationRequestPurpose.FINAL_ANSWER_RECOVERY,
                                            diagnosticHandle = generationDiagnostics,
                                            providerTailMessages = recoveryTailMessages,
                                            steeringDeliveries = recoverySteeringDeliveries,
                                            invocationSurfaceContextProvider = invocationSurfaceContextProvider,
                                            callOrigin = callOrigin,
                                            conversationId = conversationId,
                                            commandId = commandId,
                                            usageBase = accumulatedUsage,
                                        )
                                    }
                                } else {
                                    null
                                }
                                if (recoveryTerminal == null) {
                                    recoveryTimedOut = true
                                    Result.failure(IllegalStateException("recovery_time_budget_exhausted"))
                                } else {
                                    Result.success(recoveryTerminal)
                                }
                            } catch (t: Throwable) {
                                if (t is CancellationException &&
                                    runControl?.hasUndeliveredSteering() == true &&
                                    !runControl.isRunCancellationRequested()
                                ) {
                                    continue@generationLoop
                                }
                                if (t is CancellationException) throw t
                                Result.failure(t)
                            } finally {
                                processingStatus.value = null
                            }

                            accumulatedUsage = recoveredMessages.lastOrNull()?.usage
                                ?: accumulatedUsage

                            currentCoroutineContext().ensureActive()
                            if (runControl?.hasUndeliveredSteering() == true) {
                                continue@generationLoop
                            }
                            val recoveryInvalidated = isEmergencyStopActive() ||
                                runControl?.isRunCancellationRequested() == true ||
                                runControl?.isUpdateFenced() == true
                            val recoveryTerminal = recoveryResult.getOrNull()
                            if (recoveryInvalidated || recoveryTerminal == null) {
                                val failure = when {
                                    recoveryInvalidated ->
                                        FinalAnswerRecoveryFailure.CANCELLED_OR_EMERGENCY
                                    recoveryTimedOut ->
                                        FinalAnswerRecoveryFailure.TIME_BUDGET_EXHAUSTED
                                    else -> FinalAnswerRecoveryFailure.PROVIDER_EXCEPTION
                                }
                                val nextStep = FinalAnswerRecoveryAttemptPolicy.afterFailure(
                                    failure = failure,
                                    attempt = attempt,
                                    maxAttempts = FINAL_ANSWER_MAX_ATTEMPTS,
                                )
                                val retry = nextStep as? FinalAnswerRecoveryAttemptDecision.Retry
                                if (retry != null) {
                                    messages = recoveryBase.replaceLastMessage(
                                        recoveryBase.last().withFinalAnswerRecovery(
                                            commandId = recoveryCommandKey,
                                            reason = recoveryResult.exceptionOrNull()?.javaClass?.simpleName
                                                ?: "recovery_provider_failed",
                                            status = FinalAnswerRecoveryStatus.FAILED,
                                            attempt = attempt,
                                            state = UIMessageState.STREAMING,
                                        ),
                                    )
                                    emit(GenerationChunk.Messages(messages))
                                    generationDiagnostics.markRecovery(attempt, "RETRYING")
                                    recoveryStream = retry.stream
                                    continue
                                }
                                val stopReason =
                                    (nextStep as FinalAnswerRecoveryAttemptDecision.Stop).reason
                                messages = recoveryBase.replaceLastMessage(
                                    recoveryBase.last().withFinalAnswerRecovery(
                                        commandId = recoveryCommandKey,
                                        reason = stopReason,
                                        status = FinalAnswerRecoveryStatus.FAILED,
                                        attempt = attempt,
                                        state = UIMessageState.INCOMPLETE_NO_VISIBLE_ANSWER,
                                    ),
                                )
                                emit(GenerationChunk.Messages(messages))
                                generationDiagnostics.markRecovery(attempt, "FAILED")
                                break
                            }

                            recoveredMessages = recoveredMessages.onGenerationFinish(
                                transformers = outputTransformers,
                                context = context,
                                model = model,
                                assistant = assistant,
                                settings = settings,
                            )
                            val recoveryOutcome = GenerationCompletionPolicy.evaluate(
                                message = recoveredMessages.last(),
                                terminal = recoveryTerminal,
                            )
                            generationDiagnostics.markOutcome(recoveryOutcome)
                            val recoveryAddedTool = recoveredMessages.last().parts
                                .drop(recoveryBase.last().parts.size)
                                .any { it is UIMessagePart.Tool }
                            if (recoveryOutcome == GenerationOutcome.Completed && !recoveryAddedTool) {
                                val recoveredFinalMessage =
                                    FinalAnswerRecoveryMessagePolicy.mergeVisibleAnswer(
                                        original = recoveryBase.last(),
                                        recoveryCandidate = recoveredMessages.last(),
                                    )
                                messages = recoveredMessages.replaceLastMessage(
                                    recoveredFinalMessage.withFinalAnswerRecovery(
                                        commandId = recoveryCommandKey,
                                        reason = recoveryReason,
                                        status = FinalAnswerRecoveryStatus.SUCCEEDED,
                                        attempt = attempt,
                                        state = UIMessageState.COMPLETED,
                                    ),
                                )
                                emit(GenerationChunk.Messages(messages))
                                generationDiagnostics.markRecovery(attempt, "SUCCEEDED")
                                recoveryComplete = true
                                break
                            }

                            val nextStep = FinalAnswerRecoveryAttemptPolicy.afterFailure(
                                failure = if (recoveryAddedTool) {
                                    FinalAnswerRecoveryFailure.TOOL_CALL
                                } else {
                                    FinalAnswerRecoveryFailure.NO_VISIBLE_ANSWER
                                },
                                attempt = attempt,
                                maxAttempts = FINAL_ANSWER_MAX_ATTEMPTS,
                            )
                            val retryDecision =
                                nextStep as? FinalAnswerRecoveryAttemptDecision.Retry
                            val retry = retryDecision != null
                            if (retryDecision != null) recoveryStream = retryDecision.stream
                            messages = recoveryBase.replaceLastMessage(
                                recoveryBase.last().withFinalAnswerRecovery(
                                    commandId = recoveryCommandKey,
                                    reason = when {
                                        retry -> "recovery_returned_no_visible_answer"
                                        else ->
                                            (nextStep as FinalAnswerRecoveryAttemptDecision.Stop).reason
                                    },
                                    status = FinalAnswerRecoveryStatus.FAILED,
                                    attempt = attempt,
                                    state = if (retry) {
                                        UIMessageState.STREAMING
                                    } else {
                                        UIMessageState.INCOMPLETE_NO_VISIBLE_ANSWER
                                    },
                                ),
                            )
                            emit(GenerationChunk.Messages(messages))
                            generationDiagnostics.markRecovery(
                                attempt,
                                if (retry) "RETRYING" else "FAILED",
                            )
                            if (!retry) break
                        }

                        if (!recoveryComplete &&
                            messages.last().state != UIMessageState.INCOMPLETE_NO_VISIBLE_ANSWER
                        ) {
                            messages = messages.replaceLastMessage(
                                messages.last().withFinalAnswerRecovery(
                                    commandId = recoveryCommandKey,
                                    reason = "recovery_attempts_exhausted",
                                    status = FinalAnswerRecoveryStatus.FAILED,
                                    attempt = finalAnswerRecoveryAttempts,
                                    state = UIMessageState.INCOMPLETE_NO_VISIBLE_ANSWER,
                                ),
                            )
                            emit(GenerationChunk.Messages(messages))
                        }
                        break@generationLoop
                    }

                    val terminalState = when (outcome) {
                        GenerationOutcome.Completed -> UIMessageState.COMPLETED
                        is GenerationOutcome.Interrupted -> UIMessageState.INTERRUPTED
                        is GenerationOutcome.Failed -> UIMessageState.FAILED
                        is GenerationOutcome.NeedsFinalAnswer ->
                            UIMessageState.INCOMPLETE_NO_VISIBLE_ANSWER
                        GenerationOutcome.AwaitingToolApproval -> UIMessageState.WAITING_TOOL
                        GenerationOutcome.ContinueToolLoop ->
                            UIMessageState.INCOMPLETE_NO_VISIBLE_ANSWER
                    }
                    messages = messages.replaceLastMessage(messages.last().copy(state = terminalState))
                    emit(GenerationChunk.Messages(messages))
                    break
                }

                // Imperative loop (was .map) so we can call the suspending
                // [isToolAutoApproved] FRESH per-tool, not from a frozen pre-resolved set.
                // Without this, a grant landing between the pre-resolve and the .map
                // (user taps Always-Allow on tool X mid-iteration) gets ignored — X
                // flips to Pending and a duplicate prompt is emitted even though X is
                // now persisted-approved.
                var hasPendingApproval = false
                val updatedTools = ArrayList<UIMessagePart.Tool>(tools.size)
                for (tool in tools) {
                    val toolDef = toolsInternal.find { it.name == tool.toolName }
                    // HARDLINE check: certain command patterns (rm -rf /, mkfs, shutdown,
                    // fork bomb, …) are blocked unconditionally — even "Always Allow"
                    // can't override. We check BEFORE the auto-approval lookup so a
                    // permanently-allowed termux/ssh tool still can't smuggle one of
                    // these through. Result: tool is marked Denied with the hardline
                    // reason, the regular Denied branch downstream emits an error
                    // envelope to the model without executing.
                    val hardlineReason = me.rerere.rikkahub.data.ai.tools
                        .HardlineCommandGuard.checkTool(tool.toolName, tool.input)
                    val transformed = when {
                        hardlineReason != null && tool.approvalState is ToolApprovalState.Auto -> {
                            Log.w(TAG, "hardline-blocked ${tool.toolName}: $hardlineReason")
                            tool.copy(approvalState = ToolApprovalState.Denied(
                                "blocked by safety floor (hardline): $hardlineReason. " +
                                    "This command cannot run via the agent under any " +
                                    "circumstances. If the user genuinely needs it, they " +
                                    "should run it themselves in a terminal outside the agent."
                            ))
                        }
                        callOrigin == ToolCallOrigin.PetHandoffAuto &&
                            tool.approvalState is ToolApprovalState.Auto -> {
                            // Auto handoff may enqueue a task, but it never inherits grants and
                            // every tool returns to an unlocked trusted-app approval surface.
                            hasPendingApproval = true
                            tool.copy(approvalState = ToolApprovalState.Pending)
                        }
                        // Tool needs approval and state is Auto:
                        toolDef?.needsApproval(tool.inputAsJson()) == true &&
                            tool.approvalState is ToolApprovalState.Auto -> {
                            // Fresh per-tool auto-approval check (was a frozen pre-
                            // resolved set). Costs a DataStore.first() per tool but tools
                            // are typically <5 per turn so the latency is negligible, and
                            // freshness matters for the YOLO toggle / mid-iteration grants.
                            if (isToolAutoApproved(tool.toolName)) {
                                tool  // leave as Auto so the executor runs it without prompting
                            } else {
                                hasPendingApproval = true
                                tool.copy(approvalState = ToolApprovalState.Pending)
                            }
                        }
                        // State is Pending -> keep waiting
                        tool.approvalState is ToolApprovalState.Pending -> {
                            hasPendingApproval = true
                            tool
                        }

                        else -> tool
                    }
                    updatedTools.add(transformed)
                }

                // If any tools were updated to Pending, update the message and break
                if (updatedTools != tools) {
                    val lastMessage = messages.last()
                    val updatedParts = lastMessage.parts.map { part ->
                        if (part is UIMessagePart.Tool) {
                            updatedTools.find { it.toolCallId == part.toolCallId } ?: part
                        } else {
                            part
                        }
                    }
                    messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
                    emit(
                        GenerationChunk.Messages(
                            messages = messages,
                            persistenceBarrier = GenerationPersistenceBarrier.PENDING_APPROVAL,
                        ),
                    )
                }

                // If there are pending approvals, break and wait for user
                if (hasPendingApproval) {
                    Log.i(TAG, "generateText: waiting for tool approval")
                    break
                }

                toolsToProcess = updatedTools
            } else {
                // Resuming after user interaction - use the resumable tools directly.
                Log.i(TAG, "generateText: resuming with ${pendingTools.size} resumable tools")
                toolsToProcess = messages.last().getTools().filter { it.canResumeExecution }
            }

            // Handle tools (execute approved tools, handle denied tools)
            val executedTools = arrayListOf<UIMessagePart.Tool>()
            val activityOverlayActive = conversationId?.let { id ->
                invocationSurfaceContextProvider?.currentContext(callOrigin, id, commandId)
            }?.hostKind == SystemAssistantHostKind.ACTIVITY_OVERLAY
            if (activityOverlayActive) {
            toolsToProcess.forEach { tool ->
                when (tool.approvalState) {
                    is ToolApprovalState.Denied -> {
                        // Tool was denied by user
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(
                                    json.encodeToString(
                                        buildJsonObject {
                                            put(
                                                "error",
                                                JsonPrimitive("Tool execution denied by user. Reason: ${reason.ifBlank { "No reason provided" }}")
                                            )
                                        }
                                    )
                                )
                            )
                        )
                    }

                    is ToolApprovalState.Answered -> {
                        // Tool was answered by user (e.g., ask_user tool)
                        val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                        executedTools += tool.copy(
                            output = listOf(
                                UIMessagePart.Text(answer)
                            )
                        )
                    }

                    is ToolApprovalState.Pending -> {
                        // Should not reach here, but just in case
                    }

                    else -> {
                        // Auto or Approved - execute the tool.
                        //
                        // Defence-in-depth HARDLINE re-check: the primary check at line ~442
                        // only runs when approvalState is Auto (the generation step that just
                        // proposed the tool). On the resume path (pendingTools branch above)
                        // tools arrive here with state=Approved and skip that block entirely.
                        // Re-check here so that a hardline-matched tool persisted in Approved
                        // state from an old DB row (pre-hardline schema, direct DB edit) can
                        // never execute via the resume path.
                        val resumeHardlineReason = me.rerere.rikkahub.data.ai.tools
                            .HardlineCommandGuard.checkTool(tool.toolName, tool.input)
                        if (resumeHardlineReason != null) {
                            Log.w(TAG, "generateText: resume-path hardline re-check blocked ${tool.toolName}: $resumeHardlineReason")
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(buildJsonObject {
                                            put("error", JsonPrimitive(
                                                "blocked by safety floor (hardline): $resumeHardlineReason. " +
                                                    "This command cannot run via the agent under any circumstances."
                                            ))
                                        })
                                    )
                                )
                            )
                            return@forEach
                        }

                        // Loop-guard: check whether the model has already called this exact
                        // tool with the same args multiple times in this turn. Refuse a
                        // repeat run and inject a "loop_detected" envelope so the model has
                        // to pivot to a different approach. Cost safety net.
                        val signature = toolLoopSignature(tool.toolName, tool.input)
                        // "This turn" = since the most recent user message. Earlier
                        // identical calls in PREVIOUS turns aren't the model flailing
                        // now — they're history, and counting them produces a confusing
                        // "you already called this 3 times in this turn" envelope after
                        // a single fresh call.
                        val turnStartIndex = messages.indexOfLast { it.role == MessageRole.USER }
                        val turnSlice = messages.subList(
                            (turnStartIndex + 1).coerceAtLeast(0),
                            messages.size
                        )
                        // Flatten this turn's executed tool calls in chronological order. The
                        // epoch ms (for the freshness-TTL bypass) comes from the parent
                        // message's finish/create time, matching the prior inline behaviour.
                        val priorCalls = turnSlice.flatMap { msg ->
                            val epochMs = (msg.finishedAt ?: msg.createdAt)
                                .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
                            msg.parts.filterIsInstance<UIMessagePart.Tool>()
                                .filter { it.isExecuted }
                                .map { PriorToolCall(it.toolName, it.toolName + "::" + it.input, epochMs) }
                        }
                        val loopDecision = LoopGuard.evaluate(
                            priorCalls = priorCalls,
                            toolName = tool.toolName,
                            signature = signature,
                            nowMs = System.currentTimeMillis(),
                        )
                        val priorOccurrences = loopDecision.priorOccurrences
                        if (loopDecision.block) {
                            loopGuardTripCount++
                            Log.w(TAG, "generateText: loop-guard tripped on $signature (${priorOccurrences + 1} repeat, trip #$loopGuardTripCount this turn); injecting bail-out envelope")
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put("error", JsonPrimitive("loop_detected"))
                                                put(
                                                    "recovery", JsonPrimitive(
                                                        "You have already called ${tool.toolName} with identical arguments " +
                                                            "${priorOccurrences} time(s) in this turn without making progress. " +
                                                            "Stop retrying. Either: (a) change the args meaningfully, (b) try a " +
                                                            "different tool that addresses the underlying request, or (c) hand " +
                                                            "back to the user with what you have so far. Examples: for 'search " +
                                                            "X in chrome' use open_url(\"https://www.google.com/search?q=X\") " +
                                                            "instead of fighting Chrome's URL bar via set_text; for terminal " +
                                                            "tasks use termux_run_command instead of typing into Termux."
                                                    )
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                            // Skip the actual execution. The next generation step will see
                            // this envelope and (if the model is well-prompted by the skill
                            // docs) will pivot to a different approach.
                            return@forEach
                        }
                        // Pre-parse args BEFORE the runCatching block so we can surface a
                        // clean structured envelope when the LLM provider truncates the
                        // streaming response mid-string (max_tokens hit, network drop, etc.).
                        // Without this, kotlinx.serialization's raw exception message —
                        // which includes the entire failed input — lands in the LLM-facing
                        // `detail` field, can be thousands of tokens, and the model often
                        // retries the same too-big call.
                        val parsedArgs = parseToolArguments(tool)
                        if (parsedArgs.isFailure) {
                            val cause = parsedArgs.exceptionOrNull()
                            Log.w(
                                TAG,
                                "tool ${tool.toolName} args failed to parse " +
                                    "(likely truncated stream, exception=${cause?.javaClass?.simpleName ?: "unknown"})",
                            )
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(buildJsonObject {
                                            put("error", JsonPrimitive("invalid_tool_args"))
                                             put(
                                                 "detail",
                                                 JsonPrimitive("Tool arguments were not valid JSON."),
                                            )
                                            put(
                                                "recovery",
                                                JsonPrimitive(
                                                    "Tool args JSON failed to parse — most often the provider's " +
                                                        "stream was cut off mid-string by max_tokens or a network drop. " +
                                                        "Retry with a shorter call. For long payloads (e.g. a 4000-char " +
                                                        "message), split into multiple smaller tool calls or shrink the " +
                                                        "content."
                                                ),
                                            )
                                            put(
                                                "exception",
                                                JsonPrimitive(cause?.javaClass?.simpleName ?: "JsonParseException"),
                                            )
                                        })
                                    )
                                )
                            )
                            return@forEach
                        }
                        var executionBoundaryStarted = false
                        runCatching {
                            val toolDef = toolsInternal.find { toolDef -> toolDef.name == tool.toolName }
                                ?: error("Tool ${tool.toolName} not found")
                            val args = parsedArgs.getOrThrow()
                            Log.i(TAG, toolExecutionLogSummary(toolDef.name, args))
                            when (runControl?.beginToolExecutionOrYieldToSteering(tool.toolCallId)) {
                                ToolStartDecision.YieldToSteering -> {
                                    executedTools += tool.skippedDueToGuidance()
                                    return@forEach
                                }
                                ToolStartDecision.RunCancelled -> throw CancellationException(
                                    "Run cancelled before tool execution",
                                )
                                ToolStartDecision.Proceed -> executionBoundaryStarted = true
                                null -> Unit
                            }
                            val liveSurface = conversationId?.let { id ->
                                invocationSurfaceContextProvider?.currentContext(
                                    callOrigin,
                                    id,
                                    commandId,
                                )
                            }
                            if (liveSurface?.hostKind == SystemAssistantHostKind.ACTIVITY_OVERLAY &&
                                ActivityOverlayToolHandoffPolicy.requiresOverlayDismissal(toolDef.name)
                            ) {
                                check(SystemAssistantActivityOverlayCoordinator.dismissAndAwait()) {
                                    "AI-key overlay did not close before ${toolDef.name}"
                                }
                                check(withTimeoutOrNull(1_500L) {
                                    while (RikkaAccessibilityService.instance
                                            ?.rootInActiveWindow?.packageName?.toString() == context.packageName
                                    ) {
                                        delay(25L)
                                    }
                                    true
                                } == true) {
                                    "RikkaHub remained the active window; ${toolDef.name} was not executed"
                                }
                            }
                            // Mark the tool as "execution started" BEFORE actually running.
                            // ChatService persists this when it sees the chunk so a process
                            // kill between mark-and-output leaves a clear breadcrumb on disk:
                            // on replay we'll see Approved + executionStartedAt + empty output
                            // and refuse to silently re-run. The mark survives via the
                            // existing emit-and-persist plumbing — see ChatService chunk
                            // handler's needsImmediatePersist branch.
                            val markedTool = tool.copy(executionStartedAt = System.currentTimeMillis())
                            run {
                                val lastMsg = messages.lastOrNull()
                                if (lastMsg != null) {
                                    val markedParts = lastMsg.parts.map { p ->
                                        if (p is UIMessagePart.Tool && p.toolCallId == tool.toolCallId) markedTool else p
                                    }
                                    messages = messages.dropLast(1) + lastMsg.copy(parts = markedParts)
                                    emit(GenerationChunk.Messages(messages))
                                }
                            }
                            // Hard-cap individual tool execution at the remaining wall-clock
                            // budget so a single tool with its OWN long timeout (camera 5min,
                            // ssh_exec timeout_seconds=300) can't carry the turn past the
                            // global ${ToolRuntimeLimits.turnBudgetMs}ms cap. If the budget is
                            // already blown when we start the tool, return a structured
                            // wall-clock envelope instead of even attempting.
                            val remainingMs = ToolRuntimeLimits.turnBudgetMs -
                                (android.os.SystemClock.elapsedRealtime() - turnStartMs)
                            val executionContext = if (conversationId != null && runControl != null) {
                                ToolExecutionContext(
                                    runId = runControl.runId,
                                    conversationId = conversationId,
                                    assistantId = assistant.id.toString(),
                                    callOrigin = callOrigin,
                                    toolCallId = tool.toolCallId,
                                    workspaceId = assistant.workspaceId?.toString(),
                                    workspaceCwd = workspaceCwd,
                                    capabilitySubject = capabilitySubject,
                                    selectedPrivilegedConversation = selectedPrivilegedConversation,
                                )
                            } else {
                                null
                            }
                            val startable = executionContext?.let { owner ->
                                startableTools[toolDef.name]
                                    ?: toolStartableResolver.resolve(toolDef, owner)
                            }
                            val runtimeResult = toolRuntime.execute(
                                ToolExecutionPlanRequest(
                                    toolCallId = tool.toolCallId,
                                    toolName = toolDef.name,
                                    args = args,
                                    executionContext = executionContext,
                                    startableTool = startable,
                                    legacyExecute = { element ->
                                        toolDef.execute(element.jsonObject)
                                    },
                                    runControl = runControl,
                                    wallClockBudgetMs = remainingMs.coerceAtLeast(0L),
                                    preExecutionGate = {
                                        when (val gate = toolExecutionGate.evaluate(
                                            toolName = tool.toolName,
                                            origin = callOrigin,
                                            conversationId = conversationId,
                                            commandId = commandId,
                                            arguments = args as? JsonObject,
                                            capabilitySubject = executionContext?.capabilitySubject,
                                            selectedPrivilegedConversation =
                                                executionContext?.selectedPrivilegedConversation == true,
                                            frozenCapabilities =
                                                executionContext?.frozenCapabilities.orEmpty(),
                                            unrestrictedOverride = unrestrictedOverride,
                                        )) {
                                            ToolExecutionGate.GateResult.Allowed ->
                                                ToolPreExecutionDecision.Allow
                                            is ToolExecutionGate.GateResult.Denied -> {
                                                Log.w(
                                                    TAG,
                                                    "generateText: gate blocked ${tool.toolName} " +
                                                        "from $callOrigin: ${gate.reason}",
                                                )
                                                ToolPreExecutionDecision.Deny(
                                                    errorCode = "tool_blocked",
                                                    reason = gate.reason,
                                                )
                                            }
                                        }
                                    },
                                )
                            )
                            if (runtimeResult is ToolExecutionPlanResult.TimedOut) {
                                Log.w(
                                    TAG,
                                    "generateText: ${toolDef.name} cancelled - " +
                                        "wall-clock budget exhausted",
                                )
                            }
                            toolExperienceRecorder?.recordIfEligible(
                                definition = toolDef,
                                result = runtimeResult,
                                context = executionContext,
                            )
                            val result = runtimeResult.output
                            // Upstream tool-output truncation: when the workspace shell is
                            // available, oversized text output is spilled to /tool_outputs/
                            // and replaced with a preview + read/grep instructions so the
                            // model can pull the full payload on demand instead of burning
                            // the context window.
                            val hasShellAccess = toolsInternal.any { it.name == "workspace_shell" }
                            executedTools += markedTool.copy(
                                output = maybeTruncateToolOutput(
                                    tool.toolCallId,
                                    tool.toolName,
                                    result,
                                    hasShellAccess,
                                )
                            )
                        }.also {
                            if (executionBoundaryStarted) {
                                runControl?.finishToolExecution(tool.toolCallId)
                            }
                        }.onFailure {
                            if (it is CancellationException) throw it
                            // Stack trace stays in logcat for debugging; the JSON envelope
                            // sent BACK to the LLM gets just the exception's message and a
                            // short class hint. Stuffing the full multi-frame R8-obfuscated
                            // trace into `error` (the prior behaviour) burned hundreds of
                            // tokens per failure, confused the model, and surfaced
                            // user-visible "java.lang.IllegalStateException at ..." walls
                            // for what was usually a one-line "name is required" problem.
                            if (isSensitivePrivilegedTool(tool.toolName)) {
                                Log.w(
                                    TAG,
                                    "sensitive tool ${tool.toolName} failed " +
                                        "(${it.javaClass.simpleName}); detail redacted",
                                )
                            } else {
                                Log.w(TAG, "tool ${tool.toolName} threw", it)
                            }
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put("error", JsonPrimitive("tool_failed"))
                                                put(
                                                    "detail",
                                                    // Cap at 500 chars so a tool that throws with
                                                    // a giant message (e.g. an OkHttp body dump or
                                                    // an echoed input arg) doesn't ship 8000+
                                                    // tokens back to the LLM on every failure.
                                                    JsonPrimitive(
                                                        if (isSensitivePrivilegedTool(tool.toolName)) {
                                                            "Sensitive tool execution failed; detail redacted."
                                                        } else {
                                                            (it.message ?: it.javaClass.simpleName).take(500)
                                                        },
                                                    ),
                                                )
                                                // Class name as a separate hint so the LLM can
                                                // distinguish validation (IllegalStateException /
                                                // IllegalArgumentException) from runtime issues.
                                                put(
                                                    "exception",
                                                    JsonPrimitive(it.javaClass.simpleName),
                                                )
                                            }
                                        )
                                    )
                                )
                            )
                        }
                    }
                }
            }
            } else {
                val hasShellAccess = toolsInternal.any { it.name == "workspace_shell" }
                val readySegment = mutableListOf<BatchReadyTool>()

                fun toolFailure(
                    tool: UIMessagePart.Tool,
                    failure: Throwable,
                ): UIMessagePart.Tool {
                    if (isSensitivePrivilegedTool(tool.toolName)) {
                        Log.w(
                            TAG,
                            "sensitive tool ${tool.toolName} failed " +
                                "(${failure.javaClass.simpleName}); detail redacted",
                        )
                    } else {
                        Log.w(TAG, "tool ${tool.toolName} threw", failure)
                    }
                    return tool.copy(
                        output = listOf(
                            UIMessagePart.Text(
                                json.encodeToString(
                                    buildJsonObject {
                                        put("error", JsonPrimitive("tool_failed"))
                                        put(
                                            "detail",
                                            JsonPrimitive(
                                                if (isSensitivePrivilegedTool(tool.toolName)) {
                                                    "Sensitive tool execution failed; detail redacted."
                                                } else {
                                                    (failure.message ?: failure.javaClass.simpleName).take(500)
                                                },
                                            ),
                                        )
                                        put(
                                            "exception",
                                            JsonPrimitive(failure.javaClass.simpleName),
                                        )
                                    },
                                ),
                            ),
                        ),
                    )
                }

                suspend fun executeReadyTool(
                    ready: BatchReadyTool,
                    markedTool: UIMessagePart.Tool,
                    batchDeadlineMs: Long,
                ): UIMessagePart.Tool = runCatching {
                    Log.i(TAG, toolExecutionLogSummary(ready.toolDef.name, ready.args))
                    val remainingMs = (
                        batchDeadlineMs - android.os.SystemClock.elapsedRealtime()
                        ).coerceAtLeast(0L)
                    val startable = startableTools[ready.toolDef.name]
                        ?: toolStartableResolver.resolve(ready.toolDef, ready.executionContext)
                    val runtimeResult = toolRuntime.execute(
                        ToolExecutionPlanRequest(
                            toolCallId = ready.tool.toolCallId,
                            toolName = ready.toolDef.name,
                            args = ready.args,
                            executionContext = ready.executionContext,
                            startableTool = startable,
                            legacyExecute = { element ->
                                ready.toolDef.execute(element.jsonObject)
                            },
                            runControl = runControl,
                            wallClockBudgetMs = remainingMs,
                            preExecutionGate = {
                                when (val gate = toolExecutionGate.evaluate(
                                    toolName = ready.tool.toolName,
                                    origin = callOrigin,
                                    conversationId = conversationId,
                                    commandId = commandId,
                                    arguments = ready.args,
                                    capabilitySubject = ready.executionContext.capabilitySubject,
                                    selectedPrivilegedConversation =
                                        ready.executionContext.selectedPrivilegedConversation,
                                    frozenCapabilities = ready.executionContext.frozenCapabilities,
                                    unrestrictedOverride = unrestrictedOverride,
                                )) {
                                    ToolExecutionGate.GateResult.Allowed ->
                                        ToolPreExecutionDecision.Allow

                                    is ToolExecutionGate.GateResult.Denied -> {
                                        Log.w(
                                            TAG,
                                            "generateText: gate blocked ${ready.tool.toolName} " +
                                                "from $callOrigin: ${gate.reason}",
                                        )
                                        ToolPreExecutionDecision.Deny(
                                            errorCode = "tool_blocked",
                                            reason = gate.reason,
                                        )
                                    }
                                }
                            },
                        ),
                    )
                    if (runtimeResult is ToolExecutionPlanResult.TimedOut) {
                        Log.w(
                            TAG,
                            "generateText: ${ready.toolDef.name} cancelled - " +
                                "wall-clock budget exhausted",
                        )
                    }
                    toolExperienceRecorder?.recordIfEligible(
                        definition = ready.toolDef,
                        result = runtimeResult,
                        context = ready.executionContext,
                    )
                    markedTool.copy(
                        output = maybeTruncateToolOutput(
                            ready.tool.toolCallId,
                            ready.tool.toolName,
                            runtimeResult.output,
                            hasShellAccess,
                        ),
                    )
                }.getOrElse { failure ->
                    if (failure is CancellationException) throw failure
                    toolFailure(ready.tool, failure)
                }

                suspend fun flushReadySegment() {
                    if (readySegment.isEmpty()) return

                    val readyByCallId = readySegment.associateBy { it.tool.toolCallId }
                    val markedTools = mutableMapOf<String, UIMessagePart.Tool>()
                    var batchDeadlineMs = 0L
                    val batchResults = toolExecutionBatchCoordinator.execute(
                        candidates = readySegment.map { ready ->
                            ToolBatchCandidate(
                                index = ready.index,
                                toolCallId = ready.tool.toolCallId,
                                toolName = ready.toolDef.name,
                                args = ready.args,
                                context = ready.executionContext,
                            )
                        },
                        enabled = settings.parallelReadOnlyToolsEnabled,
                        maxParallelism = settings.maxParallelReadOnlyTools,
                        runControl = runControl,
                        onBatchStarted = { batch ->
                            batchDeadlineMs = android.os.SystemClock.elapsedRealtime() +
                                (
                                    ToolRuntimeLimits.turnBudgetMs -
                                        (android.os.SystemClock.elapsedRealtime() - turnStartMs)
                                    ).coerceAtLeast(0L)
                            val startedAt = System.currentTimeMillis()
                            batch.forEach { candidate ->
                                val ready = readyByCallId.getValue(candidate.toolCallId)
                                markedTools[candidate.toolCallId] = ready.tool.copy(
                                    executionStartedAt = startedAt,
                                )
                            }
                            val lastMessage = messages.lastOrNull()
                            if (lastMessage != null) {
                                val markedParts = lastMessage.parts.map { part ->
                                    if (part is UIMessagePart.Tool) {
                                        markedTools[part.toolCallId] ?: part
                                    } else {
                                        part
                                    }
                                }
                                messages = messages.dropLast(1) + lastMessage.copy(parts = markedParts)
                                emit(GenerationChunk.Messages(messages))
                            }
                        },
                        execute = { candidate ->
                            val ready = readyByCallId.getValue(candidate.toolCallId)
                            executeReadyTool(
                                ready = ready,
                                markedTool = markedTools.getValue(candidate.toolCallId),
                                batchDeadlineMs = batchDeadlineMs,
                            )
                        },
                    )
                    batchResults.forEach { result ->
                        val ready = readyByCallId.getValue(result.candidate.toolCallId)
                        when (val outcome = result.outcome) {
                            is ToolBatchExecutionOutcome.Executed -> executedTools += outcome.value
                            ToolBatchExecutionOutcome.SkippedDueToSteering -> {
                                executedTools += ready.tool.skippedDueToGuidance()
                            }
                        }
                    }
                    readySegment.clear()
                }

                for ((index, tool) in toolsToProcess.withIndex()) {
                    when (tool.approvalState) {
                        is ToolApprovalState.Denied -> {
                            flushReadySegment()
                            val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                            executedTools += tool.copy(
                                output = listOf(
                                    UIMessagePart.Text(
                                        json.encodeToString(
                                            buildJsonObject {
                                                put(
                                                    "error",
                                                    JsonPrimitive(
                                                        "Tool execution denied by user. Reason: " +
                                                            reason.ifBlank { "No reason provided" },
                                                    ),
                                                )
                                            },
                                        ),
                                    ),
                                ),
                            )
                        }

                        is ToolApprovalState.Answered -> {
                            flushReadySegment()
                            val answer = (tool.approvalState as ToolApprovalState.Answered).answer
                            executedTools += tool.copy(output = listOf(UIMessagePart.Text(answer)))
                        }

                        is ToolApprovalState.Pending -> {
                            flushReadySegment()
                        }

                        else -> {
                            val resumeHardlineReason = me.rerere.rikkahub.data.ai.tools
                                .HardlineCommandGuard.checkTool(tool.toolName, tool.input)
                            if (resumeHardlineReason != null) {
                                flushReadySegment()
                                Log.w(
                                    TAG,
                                    "generateText: resume-path hardline re-check blocked " +
                                        "${tool.toolName}: $resumeHardlineReason",
                                )
                                executedTools += tool.copy(
                                    output = listOf(
                                        UIMessagePart.Text(
                                            json.encodeToString(
                                                buildJsonObject {
                                                    put(
                                                        "error",
                                                        JsonPrimitive(
                                                            "blocked by safety floor (hardline): " +
                                                                "$resumeHardlineReason. This command cannot run " +
                                                                "via the agent under any circumstances.",
                                                        ),
                                                    )
                                                },
                                            ),
                                        ),
                                    ),
                                )
                                continue
                            }

                            val signature = toolLoopSignature(tool.toolName, tool.input)
                            val turnStartIndex = messages.indexOfLast { it.role == MessageRole.USER }
                            val turnSlice = messages.subList(
                                (turnStartIndex + 1).coerceAtLeast(0),
                                messages.size,
                            )
                            val priorCalls = turnSlice.flatMap { message ->
                                val epochMs = (message.finishedAt ?: message.createdAt)
                                    .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
                                message.parts.filterIsInstance<UIMessagePart.Tool>()
                                    .filter { it.isExecuted }
                                    .map { part ->
                                        PriorToolCall(
                                            part.toolName,
                                            part.toolName + "::" + part.input,
                                            epochMs,
                                        )
                                    }
                            }
                            val loopDecision = LoopGuard.evaluate(
                                priorCalls = priorCalls,
                                toolName = tool.toolName,
                                signature = signature,
                                nowMs = System.currentTimeMillis(),
                            )
                            if (loopDecision.block) {
                                flushReadySegment()
                                loopGuardTripCount++
                                Log.w(
                                    TAG,
                                    "generateText: loop-guard tripped on $signature " +
                                        "(${loopDecision.priorOccurrences + 1} repeat, " +
                                        "trip #$loopGuardTripCount this turn)",
                                )
                                executedTools += tool.copy(
                                    output = listOf(
                                        UIMessagePart.Text(
                                            json.encodeToString(
                                                buildJsonObject {
                                                    put("error", JsonPrimitive("loop_detected"))
                                                    put(
                                                        "recovery",
                                                        JsonPrimitive(
                                                            "You have already called ${tool.toolName} with identical " +
                                                                "arguments ${loopDecision.priorOccurrences} time(s) in this " +
                                                                "turn without making progress. Stop retrying. Either change " +
                                                                "the arguments, use a different tool, or hand back to the user.",
                                                        ),
                                                    )
                                                },
                                            ),
                                        ),
                                    ),
                                )
                                continue
                            }

                            val parsedArgs = parseToolArguments(tool)
                            val args = parsedArgs.getOrNull()
                            if (args !is JsonObject) {
                                flushReadySegment()
                                val cause = parsedArgs.exceptionOrNull()
                                    ?: IllegalArgumentException("Tool arguments must be a JSON object")
                                Log.w(
                                    TAG,
                                    "tool ${tool.toolName} args failed to parse " +
                                        "(exception=${cause.javaClass.simpleName})",
                                )
                                executedTools += tool.copy(
                                    output = listOf(
                                        UIMessagePart.Text(
                                            json.encodeToString(
                                                buildJsonObject {
                                                    put("error", JsonPrimitive("invalid_tool_args"))
                                                    put(
                                                        "detail",
                                                        JsonPrimitive("Tool arguments were not a valid JSON object."),
                                                    )
                                                    put(
                                                        "recovery",
                                                        JsonPrimitive(
                                                            "Tool args JSON failed to parse or was not an object. " +
                                                                "Retry with a shorter valid JSON object.",
                                                        ),
                                                    )
                                                    put(
                                                        "exception",
                                                        JsonPrimitive(cause.javaClass.simpleName),
                                                    )
                                                },
                                            ),
                                        ),
                                    ),
                                )
                                continue
                            }

                            val toolDef = toolsInternal.find { it.name == tool.toolName }
                            if (toolDef == null) {
                                flushReadySegment()
                                executedTools += toolFailure(
                                    tool,
                                    IllegalStateException("Tool ${tool.toolName} not found"),
                                )
                                continue
                            }

                            val executionContext = if (conversationId != null && runControl != null) {
                                ToolExecutionContext(
                                    runId = runControl.runId,
                                    conversationId = conversationId,
                                    assistantId = assistant.id.toString(),
                                    callOrigin = callOrigin,
                                    toolCallId = tool.toolCallId,
                                    workspaceId = assistant.workspaceId?.toString(),
                                    workspaceCwd = workspaceCwd,
                                    capabilitySubject = capabilitySubject,
                                    selectedPrivilegedConversation = selectedPrivilegedConversation,
                                )
                            } else {
                                null
                            }
                            if (executionContext == null) {
                                flushReadySegment()
                                executedTools += tool.copy(
                                    output = listOf(
                                        UIMessagePart.Text(
                                            json.encodeToString(
                                                buildJsonObject {
                                                    put(
                                                        "error",
                                                        JsonPrimitive("tool_execution_context_missing"),
                                                    )
                                                    put(
                                                        "detail",
                                                        JsonPrimitive(
                                                            "Tool execution requires assistant, conversation, run, " +
                                                                "and origin identity.",
                                                        ),
                                                    )
                                                },
                                            ),
                                        ),
                                    ),
                                )
                                continue
                            }

                            readySegment += BatchReadyTool(
                                index = index,
                                tool = tool,
                                toolDef = toolDef,
                                args = args,
                                executionContext = executionContext,
                            )
                        }
                    }
                }
                flushReadySegment()
            }

            if (executedTools.isEmpty()) {
                // No results to add (all tools were pending)
                break
            }

            // Update last message with executed tools (NOT create TOOL message)
            val lastMessage = messages.last()
            val updatedParts = lastMessage.parts.map { part ->
                if (part is UIMessagePart.Tool) {
                    executedTools.find { it.toolCallId == part.toolCallId } ?: part
                } else part
            }
            messages = messages.dropLast(1) + lastMessage.copy(parts = updatedParts)
            emit(
                GenerationChunk.Messages(
                    messages.transforms(
                        transformers = outputTransformers,
                        context = context,
                        model = model,
                        assistant = assistant,
                        settings = settings
                    )
                )
            )
        }

    }

        .onStart {
            // Reset per-turn navigation tracking and surface the overlay so the user
            // sees that automation is happening even when the agent runs from Telegram.
            AgentTurnTracker.reset()
            AgentOverlay.show(context)
        }
        .onCompletion {
            sensitiveOwnerToolInputs.values.forEach { it.close() }
            sensitiveOwnerToolInputs.clear()
            AgentOverlay.hide(context)
            handleAutoReturnAfterTurn()
        }
        .flowOn(Dispatchers.IO)
    }

    private suspend fun resolveSecondUserProviderBinding(
        configuredProvider: ProviderSetting,
        capabilitySubject: CapabilitySubject?,
        conversationId: Uuid?,
        origin: ToolCallOrigin,
    ): ProviderSetting {
        val subject = capabilitySubject
            ?.takeIf { it.type == me.rerere.rikkahub.data.capability.SubjectType.LOCAL_SECOND_USER }
            ?: return configuredProvider
        val vault = secondUserSecretVault ?: return configuredProvider
        if (!me.rerere.rikkahub.assistant.SecondUserAuthorityRegistry.matches(
                subject.id,
                conversationId,
                origin,
            )
        ) return configuredProvider
        return when (val resolution = vault.resolveProviderBinding(
            provider = configuredProvider,
            subjectId = subject.id,
        )) {
            me.rerere.rikkahub.security.SecretBindingResolution.NotBound -> configuredProvider
            is me.rerere.rikkahub.security.SecretBindingResolution.Ready -> resolution.value
            is me.rerere.rikkahub.security.SecretBindingResolution.Unavailable ->
                error("second_user_secret_${resolution.code}")
        }
    }

    /**
     * If the agent navigated away from RikkaHub during this turn (launch_app / open_url) and
     * the user is still on that destination, bring RikkaHub back to the foreground so the
     * user is not stranded inside Chrome / Termux / etc. If the user manually switched apps
     * mid-turn, we skip the auto-return and surface a Toast explaining the safety behavior.
     */
    private fun handleAutoReturnAfterTurn() {
        if (!AgentTurnTracker.didNavigateAway()) return
        // Only auto-return when the agent actually drove the destination app via screen
        // automation (tap, click_node, set_text, swipe, scroll, global_action). A pure
        // "open Chrome and stay there" request is just launch_app + a text reply — yanking
        // the user back to RikkaHub in that case defeats the purpose of the request.
        if (!AgentTurnTracker.didAutomate()) return
        val destination = AgentTurnTracker.lastDestination()
        val currentForeground = RikkaAccessibilityService.instance
            ?.rootInActiveWindow?.packageName?.toString()

        val userSwitchedAway = destination != null
            && currentForeground != null
            && currentForeground != destination
            && currentForeground != context.packageName

        if (userSwitchedAway) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context.applicationContext,
                    "RikkaHub: skipped auto-return because you switched apps. (Safety feature)",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // startActivity throws ActivityNotFoundException / SecurityException —
            // both Exception. Catching Throwable here would also swallow JVM errors
            // (OOM, StackOverflowError); let those propagate.
            Log.w(TAG, "auto-return launch failed", e)
        }
    }

    private suspend fun generateInternal(
        assistant: Assistant,
        settings: Settings,
        systemAddendum: String? = null,
        messages: List<UIMessage>,
        onUpdateMessages: suspend (List<UIMessage>) -> Unit,
        transformers: List<MessageTransformer>,
        model: Model,
        providerImpl: Provider<ProviderSetting>,
        provider: ProviderSetting,
        tools: List<Tool>,
        memories: List<AssistantMemory>,
        stream: Boolean,
        processingStatus: MutableStateFlow<String?> = MutableStateFlow(null),
        conversationSystemPrompt: String? = null,
        conversationModeInjectionIds: Set<Uuid> = emptySet(),
        conversationLorebookIds: Set<Uuid> = emptySet(),
        workspaceCwd: String? = null,
        runControl: GenerationRunControl? = null,
        contextMessages: List<UIMessage>? = null,
        requestPurpose: GenerationRequestPurpose = GenerationRequestPurpose.NORMAL,
        diagnosticHandle: GenerationDiagnosticHandle,
        providerTailMessages: ProviderTailMessages = ProviderTailMessages.Empty,
        steeringDeliveries: List<SteeringDelivery> = emptyList(),
        invocationSurfaceContextProvider: InvocationSurfaceContextProvider? = null,
        callOrigin: ToolCallOrigin = ToolCallOrigin.LocalChat,
        secretEgressBinding: me.rerere.rikkahub.security.SecretPlaintextSessionBinding? = null,
        onRawSensitiveToolInput: (UIMessagePart.Tool) -> Unit = {},
        conversationId: Uuid? = null,
        commandId: Uuid? = null,
        toolDiscoveryMetrics: ToolDiscoveryMetrics? = null,
        usageBase: TokenUsage? = null,
    ): GenerationTerminal {
        val sourceContext = contextMessages ?: messages
        val selectedContext = sourceContext.selectOrdinaryChatContext(assistant.contextMessageSize)
        if (selectedContext.size < sourceContext.size) {
            val boundaryHash = selectedContext.firstOrNull()?.id
                ?.toString()
                ?.toByteArray()
                ?.let { MessageDigest.getInstance("SHA-256").digest(it) }
                ?.take(6)
                ?.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
                ?: "empty"
            Log.i(
                TAG,
                "context staircase total=${sourceContext.size} retained=${selectedContext.size} " +
                    "start=${sourceContext.size - selectedContext.size} boundary=$boundaryHash",
            )
        }
        val persistentSteeringContext = preparePersistentSteeringContext(
            selectedContext.ageOldToolImages(),
        )
        val invocationSurfaceAddendum = conversationId?.let { id ->
            invocationSurfaceContextProvider
                ?.currentContext(callOrigin, id, commandId)
                ?.toProviderAddendum()
        }
        val providerSystemAddendum = listOfNotNull(
            systemAddendum,
            persistentSteeringContext.systemAddendum,
            invocationSurfaceAddendum,
        ).joinToString("\n\n").ifBlank { null }
        // OpenAI-compatible gateways may hoist every system message to the front even when it
        // appears at the JSON tail. Anchor per-request context to the current user turn instead,
        // preserving the long history prefix across tasks and the exact prefix inside tool loops.
        // Responses/native providers keep the established combined system layout.
        val useAnchoredVolatileContext = provider is ProviderSetting.OpenAI && !provider.useResponseApi
        var breakdownAssistantPrompt = ""
        var breakdownUserIdentityPrompt = ""
        var breakdownMemoryPrompt = ""
        var breakdownRecentChatsPrompt = ""
        var breakdownToolPrompts = emptyList<String>()
        val systemPromptLayout = run {
            // Conversation-level system prompt override (upstream): when the assistant
            // allows it and the conversation supplies one, it replaces the assistant prompt.
            val effectiveSystemPrompt =
                if (assistant.allowConversationSystemPrompt && !conversationSystemPrompt.isNullOrBlank()) {
                    conversationSystemPrompt
                } else {
                    assistant.systemPrompt
                }
            val memoryPrompt = if (assistant.enableMemory) {
                buildMemoryPrompt(
                    memories = memories,
                    includeContextual = requestPurpose == GenerationRequestPurpose.NORMAL,
                )
            } else ""
            val recentChatsPrompt = if (
                requestPurpose == GenerationRequestPurpose.NORMAL && assistant.enableRecentChatsReference
            ) {
                buildRecentChatsPrompt(assistant, conversationRepo)
            } else ""
            val toolPrompts = tools.map { tool -> tool.systemPrompt(model, messages) }
            val userIdentityPrompt = buildUserIdentityPrompt(
                settings.displaySetting.userNickname,
            )
            breakdownAssistantPrompt = effectiveSystemPrompt
            breakdownUserIdentityPrompt = userIdentityPrompt
            breakdownMemoryPrompt = memoryPrompt
            breakdownRecentChatsPrompt = recentChatsPrompt
            breakdownToolPrompts = toolPrompts
            // Split stable instructions from runtime data. Chat Completions anchor runtime data
            // to the current user turn (below); otherwise an ever-changing device/memory
            // addendum can cut the reusable prefix after only the system prompt.
            val (stableSystem, volatileSystem) = systemPromptBuilder.buildSections(
                assistantPrompt = effectiveSystemPrompt,
                userIdentityPrompt = userIdentityPrompt,
                memoryPrompt = memoryPrompt,
                recentChatsPrompt = recentChatsPrompt,
                toolPrompts = toolPrompts,
                systemAddendum = providerSystemAddendum,
            )
            ProviderSystemPromptLayout.create(
                stableSystem = stableSystem,
                volatileSystem = volatileSystem,
                conversationMessages = providerTailMessages.appendTo(persistentSteeringContext.messages),
                useAnchoredVolatileContext = useAnchoredVolatileContext,
            )
        }
        val contextPreparer = GenerationProviderContextPreparer()
        val providerIdentityMessages = prepareSecondUserProviderMessages(systemPromptLayout.initialMessages)
        val providerEphemeralMessages = if (
            secretEgressBinding != null &&
            secretPlaintextSessions?.isOpenFor(secretEgressBinding) == true
        ) {
            ephemeralToolResults?.materializeForProvider(providerIdentityMessages, secretEgressBinding)
                ?: providerIdentityMessages
        } else {
            providerIdentityMessages
        }
        val initialContextPreparation = contextPreparer.prepareOrdinaryChat(
            messages = providerEphemeralMessages,
            configuredContextWindowTokens = model.userContextWindowTokens,
            advertisedContextWindowTokens = model.contextLength,
        )
        val providerContext = initialContextPreparation.messages
        val transformedMessages = providerContext.transforms(
            transformers = transformers,
            context = context,
            model = model,
            assistant = assistant,
            settings = settings,
            conversationModeInjectionIds = conversationModeInjectionIds,
            conversationLorebookIds = conversationLorebookIds,
            processingStatus = processingStatus,
            workspaceCwd = workspaceCwd,
        )
        val contextPreparation = contextPreparer.prepareOrdinaryChat(
            messages = systemPromptLayout.applyVolatileContext(transformedMessages),
            configuredContextWindowTokens = model.userContextWindowTokens,
            advertisedContextWindowTokens = model.contextLength,
        )
        val internalMessages = contextPreparation.messages
        val requestedMaxTokens = if (requestPurpose == GenerationRequestPurpose.FINAL_ANSWER_RECOVERY) {
            FINAL_ANSWER_MAX_TOKENS
        } else {
            assistant.maxTokens
        }
        Log.i(
            TAG,
            "contextPolicy: windowTokens=${contextPreparation.configuredContextWindowTokens}, " +
                "estimatedRequestTokens=${contextPreparation.estimatedRequestTokens}, " +
                "summaryUsed=${contextPreparation.summaryUsed}, " +
                "windowSource=user_configured_manual, " +
                "advertisedModelWindowTokens=${contextPreparation.advertisedContextWindowTokens ?: "none"}",
        )

        var messages: List<UIMessage> = messages
        val terminalTracker = GenerationTerminalTracker()
        val params = TextGenerationParams(
            model = model,
            temperature = assistant.temperature,
            topP = assistant.topP,
            maxTokens = requestedMaxTokens,
            tools = tools,
            reasoningLevel = if (requestPurpose == GenerationRequestPurpose.FINAL_ANSWER_RECOVERY) {
                ReasoningLevel.OFF
            } else {
                assistant.reasoningLevel
            },
            customHeaders = buildList {
                addAll(assistant.customHeaders)
                addAll(model.customHeaders)
            },
            customBody = buildList {
                addAll(assistant.customBodies)
                addAll(model.customBodies)
            }.let { bodies ->
                if (requestPurpose == GenerationRequestPurpose.FINAL_ANSWER_RECOVERY) {
                    bodies.filterNot { it.key.lowercase() in FINAL_ANSWER_RESERVED_CUSTOM_BODY_KEYS }
                } else {
                    bodies
                }
            },
        )
        var providerStarted = false
        var providerCallUsage: TokenUsage? = null
        fun markProviderStarted() {
            if (providerStarted) return
            providerStarted = true
            runControl?.markSteeringProviderStarted(steeringDeliveries)
        }
        aiLoggingManager.addLog(
            AILogging.Generation(
                modelId = model.modelId,
                providerType = provider::class.simpleName ?: "unknown",
                messageCount = internalMessages.size,
                inputCharacters = internalMessages.redactedInputCharacterCount(),
                toolCount = tools.size,
                requestPurpose = requestPurpose.name,
                stream = stream,
            )
        )
        val requestMode = "${requestPurpose.name.lowercase()}:${if (stream) "stream" else "single"}"
        val breakdown = RequestBreakdownDiagnostic.create(
            generationId = diagnosticHandle.generationId,
            providerCallIndex = diagnosticHandle.nextProviderCallIndex(),
            modelId = model.modelId,
            providerType = provider::class.simpleName ?: "unknown",
            requestMode = requestMode,
            finalMessages = internalMessages,
            tools = tools,
            assistantPrompt = breakdownAssistantPrompt,
            userIdentityPrompt = breakdownUserIdentityPrompt,
            toolSystemPrompts = breakdownToolPrompts,
            memoryPrompt = breakdownMemoryPrompt,
            recentChatsPrompt = breakdownRecentChatsPrompt,
            dynamicSystemAddendum = providerSystemAddendum,
            memoryCount = memories.size,
            enabledSkillNames = assistant.enabledSkills,
            toolCatalogCandidateCount = toolDiscoveryMetrics?.candidateCount,
            toolCatalogSelectedSchemaCount = toolDiscoveryMetrics?.selectedSchemaCount,
            toolCatalogStage = toolDiscoveryMetrics?.stage,
            toolFastLaneShortcutLibraryCount = toolDiscoveryMetrics?.fastLaneShortcutLibraryCount,
            toolFastLaneInjectedSchemaCount = toolDiscoveryMetrics?.fastLaneInjectedSchemaCount,
            toolFastLaneBundleId = toolDiscoveryMetrics?.fastLaneBundleId,
        )
        diagnosticHandle.recordRequestBreakdown(context.filesDir, breakdown)
        val providerOutcome = try {
            DefaultProviderTurnRunner(runControl).run(
                ProviderTurnRequest(
                    stream = stream,
                    streamCall = {
                        providerImpl.streamText(
                            providerSetting = provider,
                            messages = internalMessages,
                            params = params,
                        )
                    },
                    singleCall = {
                        providerImpl.generateText(
                            providerSetting = provider,
                            messages = internalMessages,
                            params = params,
                        )
                    },
                    onChunk = { chunk ->
                        markProviderStarted()
                        terminalTracker.observe(chunk)
                        messages = messages.handleMessageChunk(chunk = chunk, model = model)
                        messages.lastOrNull()?.parts
                            ?.filterIsInstance<UIMessagePart.Tool>()
                            ?.filter { tool ->
                                tool.toolName == "owner_secret_manage" &&
                                    runtimeSecretRedactor?.containsKnownSecret(tool.input) == true
                            }
                            ?.forEach(onRawSensitiveToolInput)
                        runtimeSecretRedactor?.let { redactor ->
                            messages = redactor.redactMessages(messages)
                        }
                        chunk.usage?.let { usage ->
                            providerCallUsage = providerCallUsage.merge(usage)
                            val accumulated = usageBase.accumulate(providerCallUsage)
                            messages = messages.mapIndexed { index, message ->
                                if (index == messages.lastIndex) {
                                    message.copy(usage = accumulated)
                                } else {
                                    message
                                }
                            }
                        }
                        onUpdateMessages(messages)
                    },
                )
            )
        } catch (t: Throwable) {
            if (!providerStarted) runControl?.markSteeringDeliveryFailed(steeringDeliveries)
            throw t
        }
        if (providerOutcome == ProviderTurnOutcome.CancelledForSteering) {
            if (!providerStarted) runControl?.markSteeringDeliveryFailed(steeringDeliveries)
            throw CancellationException("Provider child was cancelled for newer guidance")
        }
        val dsmlRecovery = messages.lastOrNull()?.recoverDsmlToolCalls(
            allowedToolNames = tools.mapTo(linkedSetOf(), Tool::name),
        )
        if (dsmlRecovery?.detected == true) {
            messages = messages.replaceLastMessage(dsmlRecovery.message)
            dsmlRecovery.recoveredTools
                .filter { tool ->
                    tool.toolName == "owner_secret_manage" &&
                        runtimeSecretRedactor?.containsKnownSecret(tool.input) == true
                }
                .forEach(onRawSensitiveToolInput)
            runtimeSecretRedactor?.let { redactor ->
                messages = redactor.redactMessages(messages)
            }
            onUpdateMessages(messages)
        }
        val trackedTerminal = if (dsmlRecovery?.malformed == true) {
            GenerationTerminal.missingTransportTerminal(
                "Malformed DSML tool call was suppressed before persistence.",
            )
        } else {
            terminalTracker.finish()
        }
        val terminal = messages.lastOrNull()?.let(trackedTerminal::withMessageStats)
            ?: trackedTerminal
        providerCallUsage?.let { usage ->
            diagnosticHandle.recordProviderUsage(
                filesDir = context.filesDir,
                promptTokens = usage.promptTokens,
                cachedTokens = usage.cachedTokens,
                completionTokens = usage.completionTokens,
            )
        }
        diagnosticHandle.record(
            terminal = terminal,
            modelId = model.modelId,
            providerType = provider::class.simpleName ?: "unknown",
            requestMode = requestMode,
            contextOriginalTokens = maxOf(
                initialContextPreparation.estimatedRequestTokens,
                contextPreparation.estimatedRequestTokens,
            ),
            contextPlannedTokens = contextPreparation.estimatedRequestTokens,
            contextWindowTokens = contextPreparation.configuredContextWindowTokens,
            contextCompressed = contextPreparation.summaryUsed,
            historicalReasoningRemoved = 0,
        )
        return terminal
    }

    private fun maybeTruncateToolOutput(
        toolCallId: String,
        toolName: String,
        output: List<UIMessagePart>,
        hasShellAccess: Boolean,
    ): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val nonTextParts = output.filter { it !is UIMessagePart.Text }
        val totalChars = textParts.sumOf { it.text.length }

        // Cross-conversation excerpts are memory-only. Their Reader enforces a 40k text budget,
        // and the generic /tool_outputs spill would violate the single-command lifetime.
        if (!shouldSpillToolOutputToFile(toolName, totalChars, hasShellAccess)) return output

        Log.i(TAG, "maybeTruncateToolOutput: truncating tool $toolCallId output ($totalChars chars)")

        val fullText = textParts.joinToString("\n") { it.text }
        val preview = fullText.take(TOOL_OUTPUT_PREVIEW_CHARS)

        val fileName = "${toolCallId}.txt"
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(preview)
                }
            )
        ) + nonTextParts
    }

    fun translateText(
        settings: Settings,
        sourceText: String,
        targetLanguage: Locale,
        onStreamUpdate: ((String) -> Unit)? = null
    ): Flow<String> = flow {
        val model = settings.providers.findModelById(settings.translateModeId)
            ?: error("Translation model not found")
        val provider = model.findProvider(settings.providers)
            ?: error("Translation provider not found")

        val providerHandler = providerManager.getProviderByType(provider)

        if (!ModelRegistry.QWEN_MT.match(model.modelId)) {
            // Use regular translation with prompt
            val prompt = settings.translatePrompt.applyPlaceholders(
                "source_text" to sourceText,
                "target_lang" to targetLanguage.toString(),
            )

            var messages = listOf(UIMessage.user(prompt))
            var translatedText = ""

            providerHandler.streamText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    reasoningLevel = ReasoningLevel.fromBudgetTokens(settings.translateThinkingBudget),
                ),
            ).collect { chunk ->
                messages = messages.handleMessageChunk(chunk)
                translatedText = messages.lastOrNull()?.toText() ?: ""

                if (translatedText.isNotBlank()) {
                    onStreamUpdate?.invoke(translatedText)
                    emit(translatedText)
                }
            }
        } else {
            // Use Qwen MT model with special translation options
            val messages = listOf(UIMessage.user(sourceText))
            val chunk = providerHandler.generateText(
                providerSetting = provider,
                messages = messages,
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.3f,
                    topP = 0.95f,
                    customBody = listOf(
                        CustomBody(
                            key = "translation_options",
                            value = buildJsonObject {
                                put("source_lang", JsonPrimitive("auto"))
                                put(
                                    "target_lang",
                                    JsonPrimitive(targetLanguage.getDisplayLanguage(Locale.ENGLISH))
                                )
                            }
                        )
                    )
                ),
            )
            val translatedText = chunk.choices.firstOrNull()?.message?.toText() ?: ""

            if (translatedText.isNotBlank()) {
                onStreamUpdate?.invoke(translatedText)
                emit(translatedText)
            }
        }
    }.flowOn(Dispatchers.IO)
}
