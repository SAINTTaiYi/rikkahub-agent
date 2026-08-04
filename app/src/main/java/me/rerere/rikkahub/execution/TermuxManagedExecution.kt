package me.rerere.rikkahub.execution

import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.LegacyToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.StartableTool
import me.rerere.rikkahub.data.ai.tools.TermuxBridgeClient
import me.rerere.rikkahub.data.ai.tools.TermuxProcessStatus
import me.rerere.rikkahub.data.ai.tools.TermuxToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.ToolExecutionContext
import me.rerere.rikkahub.data.ai.tools.ToolExecutionHandle
import me.rerere.rikkahub.data.ai.tools.ToolResult
import me.rerere.rikkahub.data.ai.tools.local.shellSingleQuote
import me.rerere.rikkahub.data.execution.CompletionPolicy
import me.rerere.rikkahub.data.execution.ExecutionRuntime
import me.rerere.rikkahub.data.execution.ManagedExecutionRegistration
import me.rerere.rikkahub.data.execution.ManagedExecutionReservation
import me.rerere.rikkahub.data.execution.RequestedTerminalOutcome
import me.rerere.rikkahub.data.preferences.TermuxDefaults
import me.rerere.rikkahub.data.preferences.TermuxRuntime

data class TermuxManagedCommand(
    val shellCommand: String,
    val workingDirectory: String,
    val interactive: Boolean,
    val background: Boolean,
    val timeoutMs: Long,
)

internal object TermuxManagedCommandParser {
    fun parse(input: JsonElement): Result<TermuxManagedCommand> = runCatching {
        val obj = input.jsonObject
        val raw = obj["command"]?.jsonPrimitive?.contentOrNull
        val executable = obj["executable"]?.jsonPrimitive?.contentOrNull
        require(raw.isNullOrBlank() xor executable.isNullOrBlank()) {
            "termux_command_shape_invalid"
        }
        val arguments = obj["arguments"]?.jsonArray.orEmpty().map { element ->
            element.jsonPrimitive.contentOrNull ?: error("termux_argument_invalid")
        }
        require(arguments.all { '\u0000' !in it }) { "termux_argument_invalid" }
        val body = if (!raw.isNullOrBlank()) {
            require('\u0000' !in raw) { "termux_command_invalid" }
            val preamble = if (TermuxRuntime.aptWrapEnabled) {
                "export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a; " +
                    "apt(){ command apt -o Dpkg::Options::='--force-confdef' " +
                    "-o Dpkg::Options::='--force-confold' \"\$@\"; }; " +
                    "apt-get(){ command apt-get -o Dpkg::Options::='--force-confdef' " +
                    "-o Dpkg::Options::='--force-confold' \"\$@\"; }; " +
                    "export -f apt apt-get; "
            } else {
                ""
            }
            preamble + raw
        } else {
            buildString {
                append("exec ").append(shellSingleQuote(executable!!))
                arguments.forEach { argument -> append(' ').append(shellSingleQuote(argument)) }
            }
        }
        val rawTimeout = obj["timeout_seconds"]?.jsonPrimitive?.intOrNull
        val timeoutMs = when {
            rawTimeout == null || rawTimeout == 0 -> TermuxRuntime.commandTimeoutMs
            else -> rawTimeout.coerceIn(1, TermuxDefaults.MAX_COMMAND_TIMEOUT_SECONDS) * 1_000L
        }
        TermuxManagedCommand(
            shellCommand = body,
            workingDirectory = obj["working_dir"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank)
                ?: "/data/data/com.termux/files/home",
            interactive = obj["interactive"]?.jsonPrimitive?.contentOrNull
                ?.toBooleanStrictOrNull() ?: false,
            background = obj["background"]?.jsonPrimitive?.contentOrNull
                ?.toBooleanStrictOrNull() ?: false,
            timeoutMs = timeoutMs,
        )
    }
}

class TermuxManagedStartableTool(
    private val legacyTool: Tool,
    private val supervisor: TermuxManagedSupervisor,
    private val ledger: ManagedExecutionLedger,
    private val tokenProvider: ExecutionTokenProvider,
    private val scope: CoroutineScope,
    private val registration: ManagedExecutionRegistration? = null,
) : StartableTool {
    override suspend fun start(
        args: JsonElement,
        context: ToolExecutionContext,
    ): ToolExecutionHandle {
        val parsed = TermuxManagedCommandParser.parse(args).getOrElse { failure ->
            return completedLegacy(context, errorResult(failure.message ?: "termux_command_invalid"))
        }
        if (parsed.interactive || !parsed.background) {
            val deferred = scope.async { legacyTool.execute(args) }
            return LegacyToolExecutionHandle(
                executionId = if (parsed.interactive) "termux-interactive-${context.runId}" else "termux-capture-${context.runId}",
                result = deferred,
            )
        }
        val nativeId = "tx_${UUID.randomUUID().toString().replace("-", "")}"
        val executionId = managedExecutionId(ManagedExecutionRuntime.TERMUX, nativeId)
        val token = tokenProvider.tokenFor(nativeId)
        val now = System.currentTimeMillis()
        ledger.upsert(
            ManagedExecutionLedgerRecord(
                executionId = executionId,
                runtime = ManagedExecutionRuntime.TERMUX.idPrefix,
                nativeId = nativeId,
                ownerAssistantId = context.assistantId,
                ownerConversationId = context.conversationId.toString(),
                ownerOrigin = context.callOrigin.name,
                status = ManagedExecutionStatus.STARTING.name,
                tokenHash = sha256(token),
                createdAtMs = now,
                updatedAtMs = now,
            )
        )
        try {
            registration?.reserve(
                context = context,
                reservation = ManagedExecutionReservation(
                    executionId = executionId,
                    runtime = ExecutionRuntime.TERMUX,
                    completionPolicy = if (parsed.background) {
                        CompletionPolicy.DETACH_BACKGROUND
                    } else {
                        CompletionPolicy.WAIT_FOR_CHILDREN
                    },
                ),
            )
        } catch (failure: Throwable) {
            ledger.updateStatus(executionId, ManagedExecutionStatus.FAILED)
            throw failure
        }
        val identity = supervisor.start(
            nativeId,
            token,
            parsed.shellCommand,
            parsed.workingDirectory,
        ).getOrElse { failure ->
            ledger.updateStatus(executionId, ManagedExecutionStatus.FAILED)
            runCatching { registration?.failed(executionId, "termux_supervisor_start_failed") }
            return completedLegacy(
                context,
                errorResult(failure.message ?: "termux_supervisor_start_failed"),
            )
        }
        ledger.upsert(
            ManagedExecutionLedgerRecord(
                executionId = executionId,
                runtime = ManagedExecutionRuntime.TERMUX.idPrefix,
                nativeId = nativeId,
                ownerAssistantId = context.assistantId,
                ownerConversationId = context.conversationId.toString(),
                ownerOrigin = context.callOrigin.name,
                status = ManagedExecutionStatus.RUNNING.name,
                pid = identity.pid,
                processGroupId = identity.processGroupId,
                processStartTicks = identity.processStartTicks,
                tokenHash = sha256(token),
                createdAtMs = now,
                updatedAtMs = now,
            )
        )
        runCatching {
            registration?.running(
                executionId = executionId,
                runtimeInstanceMarker = identity.processStartTicks.toString(),
            )
        }
        val bridge = SupervisorBridge(supervisor, token, identity)
        val result: Deferred<ToolResult> = scope.async {
            if (parsed.background) {
                return@async listOf(
                    UIMessagePart.Text(buildJsonObject {
                        put("success", true)
                        put("mode", "managed_background")
                        put("execution_id", executionId)
                    }.toString())
                )
            }
            val terminal = withTimeoutOrNull(parsed.timeoutMs) {
                while (true) {
                    val status = supervisor.status(nativeId, token).getOrElse { failure ->
                        return@withTimeoutOrNull PollResult.Failed(
                            failure.message ?: "termux_status_failed"
                        )
                    }
                    if (!status.running) return@withTimeoutOrNull PollResult.Terminal(status)
                    delay(POLL_INTERVAL_MS)
                }
                @Suppress("UNREACHABLE_CODE")
                PollResult.Failed("termux_status_failed")
            }
            if (terminal == null) {
                val terminationConfirmed = terminateTimedOutCapture(
                    nativeId = nativeId,
                    token = token,
                    executionId = executionId,
                    identity = identity,
                )
                return@async timeoutResult(executionId, terminationConfirmed)
            }
            when (terminal) {
                is PollResult.Failed -> {
                    ledger.updateStatus(executionId, ManagedExecutionStatus.UNKNOWN)
                    errorResult(terminal.code, executionId)
                }
                is PollResult.Terminal -> {
                    val status = terminal.status
                    ledger.updateStatus(
                        executionId,
                        if (status.state == "exited") ManagedExecutionStatus.EXITED
                        else ManagedExecutionStatus.STOPPED,
                    )
                    runCatching {
                        registration?.exited(
                            executionId = executionId,
                            succeeded = status.exitCode == 0,
                            reasonCode = if (status.exitCode == 0) {
                                "termux_process_exited_zero"
                            } else {
                                "termux_process_exited_nonzero"
                            },
                        )
                    }
                    val logs = supervisor.logs(nativeId, token, 64 * 1024).getOrNull()
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("success", status.exitCode == 0)
                        put("mode", "managed_capture")
                        put("execution_id", executionId)
                        status.exitCode?.let { put("exit_code", it) }
                        put("stdout", logs?.stdout.orEmpty())
                        logs?.stderr?.takeIf(String::isNotBlank)?.let { put("stderr", it) }
                        if (logs?.truncated == true) put("truncated", true)
                    }.toString()))
                }
            }
        }
        return TermuxToolExecutionHandle(
            executionId = executionId,
            result = result,
            bridge = bridge,
            runId = nativeId,
            expectedPid = identity.pid,
            expectedPgid = identity.processGroupId,
            expectedStartTimeMillis = identity.processStartTicks,
            onCancellationRequested = { outcome ->
                registration?.cancelRequested(executionId, outcome)
            },
            onCancellationProbed = { _, stopped ->
                registration?.cancellationProbed(executionId, stopped)
            },
        )
    }

    /**
     * Timeout is not itself proof that the remote process ended. Preserve the stable TERM →
     * wait → KILL ordering, then independently ask the authenticated supervisor for a matching
     * identity so the ledger never claims a stopped task after an unverified signal.
     */
    private suspend fun terminateTimedOutCapture(
        nativeId: String,
        token: String,
        executionId: String,
        identity: TermuxSupervisorIdentity,
    ): Boolean {
        runCatching {
            registration?.cancelRequested(executionId, RequestedTerminalOutcome.TIMED_OUT)
        }
        ledger.updateStatus(executionId, ManagedExecutionStatus.STOP_REQUESTED)
        supervisor.stop(nativeId, token, force = false)
        delay(STOP_GRACE_MS)
        if (isConfirmedStopped(nativeId, token, identity)) {
            ledger.updateStatus(executionId, ManagedExecutionStatus.STOPPED)
            runCatching { registration?.cancellationProbed(executionId, true) }
            return true
        }

        supervisor.stop(nativeId, token, force = true)
        repeat(STOP_VERIFY_ATTEMPTS) { attempt ->
            if (isConfirmedStopped(nativeId, token, identity)) {
                ledger.updateStatus(executionId, ManagedExecutionStatus.STOPPED)
                runCatching { registration?.cancellationProbed(executionId, true) }
                return true
            }
            if (attempt + 1 < STOP_VERIFY_ATTEMPTS) delay(STOP_VERIFY_INTERVAL_MS)
        }
        runCatching { registration?.cancellationProbed(executionId, false) }
        return false
    }

    private suspend fun isConfirmedStopped(
        nativeId: String,
        token: String,
        expectedIdentity: TermuxSupervisorIdentity,
    ): Boolean = supervisor.status(nativeId, token).getOrNull()?.let { status ->
        status.identity == expectedIdentity && !status.running
    } == true

    private fun completedLegacy(
        context: ToolExecutionContext,
        result: ToolResult,
    ): LegacyToolExecutionHandle = LegacyToolExecutionHandle(
        executionId = "termux-rejected-${context.runId}",
        result = scope.async { result },
    )

    private sealed interface PollResult {
        data class Terminal(val status: TermuxSupervisorStatus) : PollResult
        data class Failed(val code: String) : PollResult
    }

    private class SupervisorBridge(
        private val supervisor: TermuxManagedSupervisor,
        private val token: String,
        private val identity: TermuxSupervisorIdentity,
    ) : TermuxBridgeClient {
        override suspend fun cancel(runId: String, force: Boolean): Boolean =
            supervisor.stop(runId, token, force).getOrNull()?.let { !it.running } ?: false

        override suspend fun status(runId: String): TermuxProcessStatus {
            val status = supervisor.status(runId, token).getOrElse {
                return TermuxProcessStatus(
                    runId,
                    identity.pid,
                    identity.processGroupId,
                    identity.processStartTicks,
                    running = true,
                    cancellationConfirmed = false,
                )
            }
            return TermuxProcessStatus(
                runId = runId,
                pid = status.identity.pid,
                pgid = status.identity.processGroupId,
                startTimeMillis = status.identity.processStartTicks,
                running = status.running,
                cancellationConfirmed = !status.running && status.identityVerified,
            )
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 250L
        const val STOP_GRACE_MS = 1_500L
        const val STOP_VERIFY_ATTEMPTS = 10
        const val STOP_VERIFY_INTERVAL_MS = 200L
    }
}

class TermuxManagedStartableFactory(
    private val supervisor: TermuxManagedSupervisor,
    private val ledger: ManagedExecutionLedger,
    private val tokenProvider: ExecutionTokenProvider,
    private val scope: CoroutineScope,
    private val registration: ManagedExecutionRegistration? = null,
) {
    fun create(legacyTool: Tool): StartableTool = TermuxManagedStartableTool(
        legacyTool = legacyTool,
        supervisor = supervisor,
        ledger = ledger,
        tokenProvider = tokenProvider,
        scope = scope,
        registration = registration,
    )
}

class TermuxManagedExecutionAdapter(
    private val ledger: ManagedExecutionLedger,
    private val supervisor: TermuxManagedSupervisor,
    private val tokenProvider: ExecutionTokenProvider,
) : ManagedExecutionAdapter {
    override val runtime = ManagedExecutionRuntime.TERMUX

    override suspend fun list(
        caller: ManagedExecutionCaller,
        includeStopped: Boolean,
    ): List<ManagedExecutionSnapshot> = ownedRecords(caller).mapNotNull { record ->
        refresh(record).getOrNull()
    }.filter { includeStopped || it.alive }

    override suspend fun status(
        caller: ManagedExecutionCaller,
        executionId: String,
    ): ManagedExecutionResult {
        val record = ownedRecord(caller, executionId)
            ?: return ManagedExecutionResult.Error("execution_not_found")
        return refresh(record).fold(
            onSuccess = ManagedExecutionResult::Snapshot,
            onFailure = { ManagedExecutionResult.Error(it.message ?: "execution_status_failed") },
        )
    }

    override suspend fun logs(
        caller: ManagedExecutionCaller,
        executionId: String,
        tailBytes: Int,
    ): ManagedExecutionResult {
        val record = ownedRecord(caller, executionId)
            ?: return ManagedExecutionResult.Error("execution_not_found")
        val snapshot = refresh(record).getOrElse {
            return ManagedExecutionResult.Error(it.message ?: "execution_status_failed")
        }
        val logs = supervisor.logs(record.nativeId, verifiedToken(record), tailBytes).getOrElse {
            return ManagedExecutionResult.Error(it.message ?: "execution_logs_failed")
        }
        return ManagedExecutionResult.Logs(
            snapshot,
            ManagedExecutionLogs(logs.stdout, logs.stderr, logs.truncated),
        )
    }

    override suspend fun stop(
        caller: ManagedExecutionCaller,
        executionId: String,
        force: Boolean,
    ): ManagedExecutionResult {
        val record = ownedRecord(caller, executionId)
            ?: return ManagedExecutionResult.Error("execution_not_found")
        return stopRecord(record, force)
    }

    override suspend fun emergencyStop(): List<ManagedExecutionSnapshot> =
        ledger.list().filter { it.runtime == runtime.idPrefix }.map { record ->
            when (val result = stopRecord(record, force = true)) {
                is ManagedExecutionResult.Stopped -> result.execution
                is ManagedExecutionResult.Snapshot -> result.execution
                else -> record.toSnapshot(ManagedExecutionStatus.UNKNOWN, alive = true, uncertain = true)
            }
        }

    private suspend fun stopRecord(
        record: ManagedExecutionLedgerRecord,
        force: Boolean,
    ): ManagedExecutionResult {
        val token = runCatching { verifiedToken(record) }.getOrElse {
            return ManagedExecutionResult.Snapshot(
                record.toSnapshot(ManagedExecutionStatus.UNKNOWN, alive = true, uncertain = true)
            )
        }
        ledger.updateStatus(record.executionId, ManagedExecutionStatus.STOP_REQUESTED)
        var status = supervisor.stop(record.nativeId, token, force).getOrElse {
            return ManagedExecutionResult.Snapshot(
                record.toSnapshot(ManagedExecutionStatus.STOP_REQUESTED, alive = true, uncertain = true)
            )
        }
        if (!force && status.running) {
            for (attempt in 0 until 10) {
                delay(200L)
                status = supervisor.status(record.nativeId, token).getOrDefault(status)
                if (!status.running) break
            }
        }
        val identityMatches = record.matches(status.identity)
        if (!identityMatches || status.running) {
            return ManagedExecutionResult.Snapshot(
                record.toSnapshot(ManagedExecutionStatus.STOP_REQUESTED, alive = true, uncertain = true)
            )
        }
        ledger.updateStatus(record.executionId, ManagedExecutionStatus.STOPPED)
        return ManagedExecutionResult.Stopped(
            record.toSnapshot(ManagedExecutionStatus.STOPPED, alive = false)
        )
    }

    private suspend fun refresh(record: ManagedExecutionLedgerRecord): Result<ManagedExecutionSnapshot> = runCatching {
        val status = supervisor.status(record.nativeId, verifiedToken(record)).getOrThrow()
        require(record.matches(status.identity)) { "execution_identity_mismatch" }
        val mapped = when (status.state) {
            "starting" -> ManagedExecutionStatus.STARTING
            "running" -> ManagedExecutionStatus.RUNNING
            "stop_requested" -> ManagedExecutionStatus.STOP_REQUESTED
            "exited" -> ManagedExecutionStatus.EXITED
            "stopped" -> ManagedExecutionStatus.STOPPED
            "failed" -> ManagedExecutionStatus.FAILED
            else -> ManagedExecutionStatus.UNKNOWN
        }
        ledger.updateStatus(record.executionId, mapped)
        record.toSnapshot(
            status = mapped,
            alive = status.running && status.identityVerified,
            uncertain = status.running && !status.identityVerified,
            exitCode = status.exitCode,
        )
    }

    private suspend fun ownedRecords(caller: ManagedExecutionCaller) = ledger.list().filter { record ->
        record.runtime == runtime.idPrefix && record.isOwnedBy(caller)
    }

    private suspend fun ownedRecord(
        caller: ManagedExecutionCaller,
        executionId: String,
    ) = ownedRecords(caller).firstOrNull { it.executionId == executionId }

    private fun verifiedToken(record: ManagedExecutionLedgerRecord): String {
        val token = tokenProvider.tokenFor(record.nativeId)
        require(MessageDigest.isEqual(
            sha256(token).toByteArray(),
            record.tokenHash.orEmpty().toByteArray(),
        )) { "execution_token_unavailable" }
        return token
    }
}

private fun ManagedExecutionLedgerRecord.isOwnedBy(caller: ManagedExecutionCaller): Boolean =
    ownerAssistantId == caller.assistantId &&
        ownerConversationId == caller.conversationId &&
        ownerOrigin == caller.origin.name

private fun ManagedExecutionLedgerRecord.matches(identity: TermuxSupervisorIdentity): Boolean =
    nativeId == identity.nativeId && pid == identity.pid &&
        processGroupId == identity.processGroupId && processStartTicks == identity.processStartTicks

private fun ManagedExecutionLedgerRecord.toSnapshot(
    status: ManagedExecutionStatus,
    alive: Boolean,
    uncertain: Boolean = false,
    exitCode: Int? = null,
) = ManagedExecutionSnapshot(
    executionId = executionId,
    runtime = ManagedExecutionRuntime.TERMUX,
    name = "Termux task ${nativeId.takeLast(8)}",
    status = status,
    alive = alive,
    startedAtMs = createdAtMs,
    runtimeInstanceMarker = processStartTicks?.toString(),
    lastExitCode = exitCode,
    terminationUncertain = uncertain,
)

private suspend fun ManagedExecutionLedger.updateStatus(
    executionId: String,
    status: ManagedExecutionStatus,
) {
    val current = list().firstOrNull { it.executionId == executionId } ?: return
    upsert(
        current.copy(
            status = status.name,
            updatedAtMs = System.currentTimeMillis(),
        )
    )
}

private fun errorResult(code: String, executionId: String? = null): ToolResult = listOf(
    UIMessagePart.Text(buildJsonObject {
        put("error", code)
        executionId?.let { put("execution_id", it) }
    }.toString())
)

private fun timeoutResult(
    executionId: String,
    terminationConfirmed: Boolean,
): ToolResult = listOf(
    UIMessagePart.Text(buildJsonObject {
        put("error", if (terminationConfirmed) "timeout" else "timeout_termination_unknown")
        put("execution_id", executionId)
        put("termination_confirmed", terminationConfirmed)
    }.toString())
)

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
