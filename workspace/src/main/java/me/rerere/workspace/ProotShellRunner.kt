package me.rerere.workspace

import java.io.File

data class WorkspaceBindMount(
    val source: File,
    val target: String,
) {
    init {
        require(target.startsWith("/")) { "Bind mount target must be absolute: $target" }
    }
}

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val extraBindMounts: List<WorkspaceBindMount> = emptyList(),
    private val sharedStorageBindMount: WorkspaceBindMount? = null,
    mountResolver: WorkspaceMountResolver? = null,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner, ManagedWorkspaceProcessLauncher {
    private val patchLock = Any()
    private val mounts = mountResolver ?: WorkspaceMountResolver(extraBindMounts, sharedStorageBindMount)

    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (!context.linuxDir.hasUsableRootfs()) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "Rootfs is not installed",
            )
        }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot executable not found: ${proot.absolutePath}",
            )
        }
        if (!loader.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot loader not found: ${loader.absolutePath}",
            )
        }

        context.tempDir.mkdirs()
        synchronized(patchLock) { patcher.patch(context.linuxDir) }
        val process = ProcessBuilder(
            buildCommand(
                proot = proot,
                filesDir = context.filesDir,
                linuxDir = context.linuxDir,
                cwd = context.prootCwd(),
                commandText = context.command,
                allowSharedStorage = context.allowSharedStorage,
            ),
        )
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                environment()["TMPDIR"] = context.tempDir.absolutePath
            }
            .start()

        return process.readResult(context.timeoutMillis, context.stdin)
    }

    override fun startManagedProcess(context: ManagedWorkspaceProcessContext): Process {
        require(context.command.isNotBlank()) { "Command is required" }
        require('\u0000' !in context.command) { "Command contains NUL" }
        require('\u0000' !in context.cwd) { "Working directory contains NUL" }
        require(context.linuxDir.hasUsableRootfs()) { "Rootfs is not installed" }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        require(proot.isFile) { "proot executable not found: ${proot.absolutePath}" }
        require(loader.isFile) { "proot loader not found: ${loader.absolutePath}" }

        context.tempDir.mkdirs()
        synchronized(patchLock) { patcher.patch(context.linuxDir) }
        return ProcessBuilder(
            buildCommand(
                proot = proot,
                filesDir = context.filesDir,
                linuxDir = context.linuxDir,
                cwd = managedProotCwd(context.cwd),
                commandText = context.command,
                allowSharedStorage = context.allowSharedStorage,
            ),
        )
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                environment()["TMPDIR"] = context.tempDir.absolutePath
            }
            .start()
    }

    internal fun buildCommand(
        proot: File,
        filesDir: File,
        linuxDir: File,
        cwd: String,
        commandText: String,
        allowSharedStorage: Boolean = false,
    ): List<String> {
        val command = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            "-r",
            linuxDir.absolutePath,
            "-w",
            cwd,
        )

        mounts.bindMounts(filesDir, allowSharedStorage).forEach { mount ->
            command += "-b"
            command += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
        }

        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                command += "-b"
                command += path
            }
        }

        command += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "/bin/bash",
            "-l",
            "-c",
            // 命令通过位置参数传入, 避免任何转义; eval "$2" 对命令文本只求值一次, 等价于 bash -c "$cmd"
            "cd -- \"\$1\" && eval \"\$2\"",
            "rikkahub",
            cwd,
            commandText,
        )
        return command
    }

    private fun WorkspaceShellContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WORKSPACE_DIR
        } else {
            "$WORKSPACE_DIR/$normalized"
        }
    }

    internal fun managedProotCwd(cwd: String): String {
        val normalized = cwd.trim().replace('\\', '/')
        if (normalized.isBlank()) return WORKSPACE_DIR
        return if (normalized.startsWith('/')) {
            "/" + normalized.trim('/')
        } else {
            "$WORKSPACE_DIR/${normalized.trim('/')}"
        }
    }

    private fun File.hasUsableRootfs(): Boolean =
        isDirectory && File(this, "bin/sh").isFile

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR
    }
}
