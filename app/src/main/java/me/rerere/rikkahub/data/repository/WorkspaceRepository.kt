package me.rerere.rikkahub.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDAO
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.ai.transformers.WorkspaceRuleFileMetadata
import me.rerere.rikkahub.data.ai.transformers.WorkspaceRulesFileSource
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.RootfsInstallProgress
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceProcessManager
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceStorageArea
import me.rerere.workspace.WorkspaceStorageMode
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.uuid.Uuid

class WorkspaceRepository(
    private val dao: WorkspaceDAO,
    private val manager: WorkspaceManager,
    private val rootfsInstaller: RootfsInstaller,
    private val settingsStore: SettingsStore,
    private val processManager: WorkspaceProcessManager,
) : WorkspaceRulesFileSource {
    fun listFlow(): Flow<List<WorkspaceEntity>> = dao.listFlow()

    suspend fun getAll(): List<WorkspaceEntity> = dao.getAll()

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        val workspaces = dao.getAll()
        for (workspace in workspaces) {
            val dir = manager.workspaceDir(workspace.root)
            if (!dir.exists()) {
                Log.w(TAG, "Workspace directory missing, retaining as BROKEN: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.BROKEN.name)
                continue
            }
            manager.ensureWorkspace(workspace.root, workspace.storageModeValue())
            val statusName = workspace.shellStatus
            if ((statusName == WorkspaceShellStatus.READY.name || statusName == WorkspaceShellStatus.INSTALLING.name)
                && !manager.hasRootfs(workspace.root)
            ) {
                Log.w(TAG, "Rootfs missing, resetting shell status: id=${workspace.id}")
                updateShellState(workspace.id, WorkspaceShellStatus.DISABLED.name)
            }
        }
    }

    suspend fun getById(id: String): WorkspaceEntity? = dao.getById(id)

    suspend fun create(
        name: String,
        storageMode: WorkspaceStorageMode = WorkspaceStorageMode.PRIVATE,
    ): WorkspaceEntity {
        val id = Uuid.random().toString()
        val now = System.currentTimeMillis()
        val finalName = name.trim().ifBlank { "Workspace" }
        require(!isNameTaken(finalName, excludeId = null)) {
            "Workspace name already exists: $finalName"
        }
        val workspace = WorkspaceEntity(
            id = id,
            name = finalName,
            root = id,
            storageMode = storageMode.name,
            createdAt = now,
            updatedAt = now,
            lastAccessAt = null,
        )
        manager.ensureWorkspace(workspace.root, storageMode)
        dao.upsert(workspace)
        return workspace
    }

    /** Move the visible workspace tree once; private rootfs and process metadata never move. */
    suspend fun changeStorageMode(id: String, targetMode: WorkspaceStorageMode): Boolean {
        val workspace = dao.getById(id) ?: return false
        val sourceMode = workspace.storageModeValue()
        if (sourceMode == targetMode) return true
        val stopped = processManager.stopByWorkspace(id, force = true)
        if (!stopped.ok) return false
        return withContext(Dispatchers.IO + NonCancellable) {
            val source = manager.filesDir(workspace.root, sourceMode)
            val target = manager.filesDir(workspace.root, targetMode)
            require(!target.exists() || target.listFiles().isNullOrEmpty()) {
                "Target workspace storage is not empty"
            }
            val staging = File(target.parentFile, ".${workspace.root}.migrating")
            if (staging.exists()) staging.deleteRecursively()
            staging.mkdirs()
            if (source.exists() && !source.copyRecursively(staging, overwrite = false)) {
                staging.deleteRecursively()
                return@withContext false
            }
            if (target.exists()) target.deleteRecursively()
            if (!staging.renameTo(target)) {
                staging.deleteRecursively()
                return@withContext false
            }
            val updated = workspace.copy(
                storageMode = targetMode.name,
                updatedAt = System.currentTimeMillis(),
            )
            try {
                dao.upsert(updated)
                manager.setStorageMode(workspace.root, targetMode)
            } catch (error: Throwable) {
                runCatching { dao.upsert(workspace) }
                target.deleteRecursively()
                throw error
            }
            // Delete only after both durable resolvers point at the completed target. A failed
            // cleanup may leave a private duplicate, but never loses or splits the live tree.
            if (source.exists()) source.deleteRecursively()
            true
        }
    }

    suspend fun rename(id: String, name: String): Boolean {
        val workspace = dao.getById(id) ?: return false
        val finalName = name.trim().ifBlank { workspace.name }
        require(!isNameTaken(finalName, excludeId = id)) {
            "Workspace name already exists: $finalName"
        }
        dao.upsert(
            workspace.copy(
                name = finalName,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 名字是否已被其他 workspace 占用（trim 后精确匹配，排除 [excludeId] 自身） */
    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val workspace = dao.getById(id) ?: return false
        val overrides = workspace.toolApprovalOverrides() + (toolName to needsApproval)
        dao.upsert(
            workspace.copy(
                toolApprovals = JsonInstant.encodeToString(overrides),
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun installRootfs(
        id: String,
        url: String,
        onProgress: (RootfsInstallProgress) -> Unit = {},
    ): Boolean {
        val workspace = dao.getById(id) ?: return false
        updateShellState(workspace, WorkspaceShellStatus.INSTALLING.name)
        try {
            // runInterruptible 让协程取消转成线程中断, 打断 install 内阻塞的下载/解压循环
            runInterruptible(Dispatchers.IO) {
                rootfsInstaller.install(workspace.root, url, onProgress)
            }
            updateShellState(workspace, WorkspaceShellStatus.READY.name)
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw e
        } catch (e: InterruptedException) {
            withContext(NonCancellable) {
                restoreShellState(workspace)
            }
            throw CancellationException("Rootfs install cancelled").also { it.initCause(e) }
        } catch (e: Throwable) {
            Log.e(TAG, "installRootfs failed: workspace=${workspace.id}, root=${workspace.root}, url=$url", e)
            updateShellState(workspace, WorkspaceShellStatus.BROKEN.name)
            throw e
        }
    }

    suspend fun listFiles(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext emptyList()
        manager.ensureWorkspace(workspace.root, workspace.storageModeValue())
        manager.listFiles(workspace.root, path, area)
    }

    suspend fun readText(
        id: String,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root, workspace.storageModeValue())
        manager.readText(workspace.root, path)
    }

    override suspend fun stat(
        workspaceId: String,
        path: String,
    ): WorkspaceRuleFileMetadata? = withContext(Dispatchers.IO) {
        val workspace = dao.getById(workspaceId) ?: return@withContext null
        val file = resolveRuleFile(workspace, path) ?: return@withContext null
        if (!file.isFile) return@withContext null
        WorkspaceRuleFileMetadata(file.length(), file.lastModified())
    }

    override suspend fun read(
        workspaceId: String,
        path: String,
        maxBytes: Int,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (maxBytes <= 0) return@withContext ByteArray(0)
        val workspace = dao.getById(workspaceId) ?: return@withContext null
        val file = resolveRuleFile(workspace, path) ?: return@withContext null
        if (!file.isFile) return@withContext null
        val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
        file.inputStream().use { input ->
            val buffer = ByteArray(4 * 1024)
            var remaining = maxBytes
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
        output.toByteArray()
    }

    suspend fun writeText(
        id: String,
        path: String,
        text: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root, workspace.storageModeValue())
        manager.writeText(workspace.root, path, text, overwrite)
    }

    /**
     * 读取文本用于应用内预览/编辑, 支持两个存储区.
     * FILES 区走 [WorkspaceManager.readText] (自带大小保护); LINUX 区通过 exportFile 读入内存,
     * 因此这里对 LINUX 区显式做大小限制, 避免大文件撑爆内存.
     */
    suspend fun readTextForPreview(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): String = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root)
        when (area) {
            WorkspaceStorageArea.FILES -> manager.readText(workspace.root, path)
            WorkspaceStorageArea.LINUX -> {
                val size = manager.fileSize(workspace.root, path, area)
                require(size <= MAX_PREVIEW_BYTES) {
                    "文件过大, 无法预览 (${size} bytes)"
                }
                ByteArrayOutputStream().use { out ->
                    manager.exportFile(workspace.root, path, area, out)
                    out.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    suspend fun importFile(
        id: String,
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root, workspace.storageModeValue())
        manager.importFile(workspace.root, destinationPath, area, fileName, inputStream)
    }

    suspend fun fileSize(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.fileSize(workspace.root, path, area)
    }

    suspend fun exportFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        outputStream: OutputStream,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.exportFile(workspace.root, path, area, outputStream)
    }

    suspend fun rootfsFileSize(
        id: String,
        path: String,
        allowSharedStorage: Boolean = false,
    ): Long = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.rootfsFileSize(workspace.root, path, allowSharedStorage)
    }

    suspend fun exportRootfsFile(
        id: String,
        path: String,
        outputStream: OutputStream,
        allowSharedStorage: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.exportRootfsFile(workspace.root, path, outputStream, allowSharedStorage)
    }

    suspend fun deleteFile(
        id: String,
        area: WorkspaceStorageArea,
        path: String,
        recursive: Boolean,
    ): Boolean {
        val deleted = withContext(Dispatchers.IO) {
            val workspace = dao.getById(id) ?: return@withContext false
            manager.deleteFile(workspace.root, path, recursive, area)
        }
        return deleted
    }

    suspend fun moveFile(
        id: String,
        source: String,
        target: String,
        overwrite: Boolean,
    ): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        manager.ensureWorkspace(workspace.root, workspace.storageModeValue())
        manager.moveFile(workspace.root, source, target, overwrite)
    }

    suspend fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        allowSharedStorage: Boolean = false,
    ): WorkspaceCommandResult {
        val workspace = dao.getById(id) ?: error("Workspace not found: $id")
        // runInterruptible 让协程取消转化为线程中断，从而打断阻塞的 Process.waitFor 并杀掉进程
        return runInterruptible(Dispatchers.IO) {
            manager.ensureWorkspace(workspace.root, workspace.storageModeValue())
            manager.executeCommand(
                workspace.root,
                command,
                cwd,
                timeoutMillis,
                stdin,
                allowSharedStorage,
            )
        }
    }

    suspend fun delete(id: String): Boolean = deleteDetailed(id).ok

    suspend fun deleteDetailed(id: String): WorkspaceDeleteResult {
        val workspace = dao.getById(id)
            ?: return WorkspaceDeleteResult(ok = false, code = "WORKSPACE_NOT_FOUND")
        val stopped = processManager.stopByWorkspace(id, force = true)
        if (!stopped.ok) {
            Log.e(TAG, "Workspace deletion blocked by managed processes: id=$id, processes=${stopped.failedProcessIds}")
            return WorkspaceDeleteResult(
                ok = false,
                code = "WORKSPACE_PROCESS_STOP_FAILED",
                failedProcessIds = stopped.failedProcessIds,
            )
        }
        val filesDeleted = try {
            withContext(Dispatchers.IO) {
                manager.deleteWorkspace(workspace.root)
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) { processManager.releaseWorkspaceDeletion(id) }
            throw error
        }
        if (!filesDeleted) {
            processManager.releaseWorkspaceDeletion(id)
            return WorkspaceDeleteResult(ok = false, code = "WORKSPACE_DELETE_FAILED")
        }
        dao.deleteById(id)
        cleanupAssistantReferences(id)
        return WorkspaceDeleteResult(ok = true, code = "WORKSPACE_DELETED")
    }

    private suspend fun cleanupAssistantReferences(workspaceId: String) {
        settingsStore.update { settings ->
            settings.copy(
                assistants = settings.assistants.map { assistant ->
                    if (assistant.workspaceId?.toString() == workspaceId) {
                        assistant.copy(workspaceId = null)
                    } else {
                        assistant
                    }
                }
            )
        }
    }

    private suspend fun restoreShellState(workspace: WorkspaceEntity) {
        updateShellState(workspace.id, workspace.shellStatus)
    }

    private suspend fun updateShellState(
        workspace: WorkspaceEntity,
        shellStatus: String,
    ) = updateShellState(workspace.id, shellStatus)

    private suspend fun updateShellState(
        workspaceId: String,
        shellStatus: String,
    ) {
        dao.updateShellStatus(
            id = workspaceId,
            shellStatus = shellStatus,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun resolveRuleFile(
        workspace: WorkspaceEntity,
        path: String,
    ): File? = runCatching {
        val normalized = path.trim().replace('\\', '/')
        if (normalized != "/workspace" && !normalized.startsWith("/workspace/")) {
            return@runCatching null
        }
        if (normalized.contains('\u0000')) return@runCatching null
        val relative = normalized.removePrefix("/workspace").trimStart('/')
        manager.ensureWorkspace(workspace.root, workspace.storageModeValue())
        val root = manager.filesDir(workspace.root).canonicalFile
        val target = if (relative.isEmpty()) root else File(root, relative).canonicalFile
        if (target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            target
        } else {
            null
        }
    }.getOrNull()

    companion object {
        private const val TAG = "WorkspaceRepository"
        private const val MAX_PREVIEW_BYTES = 512L * 1024
    }

    private fun WorkspaceEntity.storageModeValue(): WorkspaceStorageMode =
        WorkspaceStorageMode.entries.firstOrNull { it.name == storageMode }
            ?: WorkspaceStorageMode.PRIVATE
}

data class WorkspaceDeleteResult(
    val ok: Boolean,
    val code: String,
    val failedProcessIds: List<String> = emptyList(),
)
