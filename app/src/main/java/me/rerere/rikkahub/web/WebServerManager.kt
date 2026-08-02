package me.rerere.rikkahub.web

import android.content.Context
import android.util.Log
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.web.startWebServer
import java.net.ServerSocket

private const val TAG = "WebServerManager"
private const val HOST_LOOPBACK = "127.0.0.1"

data class WebServerState(
    val isRunning: Boolean = false,
    val isLoading: Boolean = false,
    val port: Int = 8080,
    val serviceName: String = DEFAULT_SERVICE_NAME,
    val localhostOnly: Boolean = false,
    val hostname: String? = null,
    val address: String? = null,
    val failure: WebServerFailure? = null
)

enum class WebServerFailure {
    StartFailed,
    StopFailed,
}

internal fun WebServerState.beginStart(): WebServerState = copy(
    isRunning = false,
    isLoading = true,
    hostname = null,
    address = null,
    failure = null,
)

internal fun WebServerState.startFailed(): WebServerState = copy(
    isRunning = false,
    isLoading = false,
    hostname = null,
    address = null,
    failure = WebServerFailure.StartFailed,
)

internal fun shouldStopWebServerService(
    state: WebServerState,
    wasRunning: Boolean,
): Boolean = !state.isRunning && !state.isLoading &&
    (wasRunning || state.failure != null)

class WebServerManager(
    private val context: Context,
    private val appScope: AppScope,
    private val chatService: ChatService,
    private val conversationRepo: ConversationRepository,
    private val folderRepo: FolderRepository,
    private val settingsStore: SettingsStore,
    private val filesManager: FilesManager
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val nsdRegistrar = NsdServiceRegistrar(context)

    private val _state = MutableStateFlow(WebServerState())
    val state: StateFlow<WebServerState> = _state.asStateFlow()

    fun start(
        port: Int = 8080,
        serviceName: String = DEFAULT_SERVICE_NAME,
        localhostOnly: Boolean = true
    ) {
        if (server != null) {
            Log.w(TAG, "Server already running")
            return
        }

        appScope.launch {
            // LAN pairing/TLS is intentionally not silently downgraded to plaintext. Until a
            // verified paired HTTPS transport is supplied, every request binds loopback even if
            // an older preference or an external intent requested all interfaces.
            val effectiveLocalhostOnly = true
            if (!localhostOnly) {
                Log.w(TAG, "Rejected plaintext LAN web-server request; using localhost only")
            }
            val host = HOST_LOOPBACK
            val baseState = WebServerState(
                port = port,
                serviceName = serviceName,
                localhostOnly = effectiveLocalhostOnly
            )
            try {
                _state.value = baseState.beginStart()
                Log.i(TAG, "Starting web server on $host:$port")
                if (!isPortAvailable(port)) {
                    Log.w(TAG, "Port $port is already in use")
                    _state.value = baseState.startFailed()
                    return@launch
                }
                server = startWebServer(port = port, host = host) {
                    configureWebApi(context, chatService, conversationRepo, folderRepo, settingsStore, filesManager)
                }.start(wait = false)

                _state.value = baseState.copy(isRunning = true)
                // 仅局域网模式注册 mDNS
                if (!effectiveLocalhostOnly) {
                    runCatching {
                        nsdRegistrar.register(
                            port = port,
                            serviceName = serviceName,
                            onRegistered = { info ->
                                _state.value = _state.value.copy(
                                    serviceName = info.serviceName,
                                    hostname = info.hostname,
                                    address = info.address.hostAddress
                                )
                            }
                        )
                    }.onFailure {
                        Log.w(TAG, "NSD register failed", it)
                    }
                }
                Log.i(TAG, "Web server started successfully on $host:$port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start web server", e)
                server = null
                _state.value = baseState.startFailed()
            }
        }
    }

    fun reportStartFailure(cause: Throwable) {
        Log.e(TAG, "Web server foreground service could not start", cause)
        server = null
        _state.value = _state.value.startFailed()
    }

    fun stop() {
        _state.value =
            _state.value.copy(
                isRunning = false,
                isLoading = true,
                hostname = null,
                address = null,
                failure = null,
            )
        appScope.launch {
            try {
                Log.i(TAG, "Stopping web server")
                server?.stop(1000, 2000)
                server = null
                runCatching {
                    nsdRegistrar.unregister()
                }.onFailure {
                    Log.w(TAG, "NSD unregister failed", it)
                }
                _state.value = _state.value.copy(isLoading = false)
                Log.i(TAG, "Web server stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop web server", e)
                server = null
                _state.value = _state.value.copy(
                    isRunning = false,
                    isLoading = false,
                    failure = WebServerFailure.StopFailed,
                )
            }
        }
    }

    fun restart(
        port: Int = _state.value.port,
        serviceName: String = _state.value.serviceName,
        localhostOnly: Boolean = _state.value.localhostOnly
    ) {
        stop()
        start(port, serviceName, localhostOnly)
    }

    private fun isPortAvailable(port: Int): Boolean {
        return try {
            ServerSocket(port).use { true }
        } catch (e: Exception) {
            false
        }
    }
}
