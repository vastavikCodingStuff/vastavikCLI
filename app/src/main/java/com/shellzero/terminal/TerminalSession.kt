package com.shellzero.terminal

import android.content.Context
import android.util.Log
import com.shellzero.installer.DebianInstaller
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * TerminalSession - Manages a single PRoot shell instance
 *
 * Responsibilities:
 *  - Spawns PRoot command via PtyProcess
 *  - Manages stdio streams (VT100/ANSI)
 *  - Handles window resize events (resizePty)
 *  - Exposes terminal buffer as StateFlow for Compose
 *  - Cleans up on destroy
 */
class TerminalSession(
    private val context: Context,
    private val sessionId: String = "session-${System.currentTimeMillis()}",
    initialRows: Int = 24,
    initialCols: Int = 80,
    private val distroId: String = "debian",
    private val distroRoot: File? = null,
    private val shellPath: String = "/bin/bash"
) {
    companion object {
        private const val TAG = "TerminalSession"
    }

    private var ptyProcess: PtyProcess? = null
    private var onExitListener: ((Int) -> Unit)? = null
    fun setOnExitListener(listener: (Int) -> Unit) { onExitListener = listener }
    fun getPtyProcess(): PtyProcess? = ptyProcess
    fun getDistroId(): String = distroId
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Terminal buffer - simple string builder + StateFlow for Compose
    // For production, integrate with a full VT emulator like `com.termux:terminal-emulator`
    private val _output = MutableStateFlow(StringBuilder())
    private val _displayText = MutableStateFlow("")
    val displayText: StateFlow<String> = _displayText

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    var rows: Int = initialRows
        private set
    var cols: Int = initialCols
        private set

    private var readerJob: Job? = null
    private var errorReaderJob: Job? = null

    fun start() {
        if (_isRunning.value) {
            Log.w(TAG, "Session $sessionId already running")
            return
        }

        // Resolve distro root: use injected distroRoot, else isolated distros/<id> or legacy debian
        // VASTAVIK CLI fix: never hardcode to $FILES_DIR/debian; always route to $FILES_DIR/distros/<distroId>
        val filesDir = context.filesDir
        val isolatedFallback = File(filesDir, "distros/$distroId")
        val legacyDebian = File(filesDir, "debian")
        val targetRoot: File = distroRoot ?: when {
            isolatedFallback.exists() && (File(isolatedFallback, "bin/bash").exists() || File(isolatedFallback, "bin/sh").exists()) -> isolatedFallback
            distroId == "debian" && legacyDebian.exists() && (File(legacyDebian, "bin/bash").exists() || File(legacyDebian, "bin/sh").exists()) -> legacyDebian
            isolatedFallback.exists() -> isolatedFallback
            distroId == "debian" -> legacyDebian
            else -> isolatedFallback
        }
        // VASTAVIK CLI Critical Fix: ensure proot exists and handle shell fallback + guest dirs
        // Use blocking wrapper since start() is not suspend; ProotManager also supports suspend with onStatusUpdate
        val prootBinary = try {
            com.shellzero.installer.ProotManager.ensureProotInstalledBlocking(context)
        } catch (e: Exception) {
            Log.e(TAG, "Proot not available", e)
            appendToBuffer("Failed to start shell: proot binary missing (${e.message})\r\n")
            // Also try suspend version with status update for UI
            try {
                appendToBuffer("Attempting to fetch PRoot engine...\r\n")
                kotlinx.coroutines.runBlocking {
                    com.shellzero.installer.ProotManager.ensureProotInstalled(context) { msg -> appendToBuffer("$msg\r\n") }
                }
            } catch (_: Exception) {}
            return
        }

        // Validate distro root exists
        if (!targetRoot.exists()) {
            Log.e(TAG, "Distro $distroId rootfs does not exist at ${targetRoot.absolutePath}")
            appendToBuffer("VASTAVIK CLI: $distroId not installed at ${targetRoot.absolutePath}. Please extract it first.\r\n")
            if (distroId == "debian") appendToBuffer("Or wait for embedded Debian extraction to finish.\r\n")
            return
        }

        // Ensure guest /root and /tmp exist inside extracted rootfs (host FS must have them before Pty)
        try {
            File(targetRoot, "root").mkdirs()
            File(targetRoot, "tmp").mkdirs()
            // Also ensure /dev/shm
            File(targetRoot, "dev/shm").mkdirs()
        } catch (_: Exception) {}

        // Handle Shell Fallback (Alpine vs Debian/Ubuntu/Arch/Kali)
        var effectiveShell = shellPath
        val shellFileCheck = File(targetRoot, effectiveShell.removePrefix("/"))
        if (!shellFileCheck.exists()) {
            // Try fallback
            val bashExists = File(targetRoot, "bin/bash").exists()
            val shExists = File(targetRoot, "bin/sh").exists()
            effectiveShell = when {
                effectiveShell == "/bin/bash" && !bashExists && shExists -> {
                    Log.w(TAG, "Shell $shellPath missing, falling back to /bin/sh for $distroId")
                    "/bin/sh"
                }
                effectiveShell == "/bin/sh" && !shExists && bashExists -> "/bin/bash"
                !bashExists && shExists -> "/bin/sh"
                else -> effectiveShell
            }
            val finalCheck = File(targetRoot, effectiveShell.removePrefix("/"))
            if (!finalCheck.exists()) {
                Log.e(TAG, "No shell found for $distroId at ${targetRoot.absolutePath} (tried $shellPath, fallback $effectiveShell)")
                appendToBuffer("VASTAVIK CLI: $distroId missing shell $effectiveShell\r\n")
                return
            }
        }

        val command: Array<String> = when (distroId) {
            "debian" -> {
                // Prefer legacy builder if target is legacy path and shell is bash, else dynamic
                val legacyDir = DebianInstaller.getDebianDir(context)
                if (targetRoot.absolutePath == legacyDir.absolutePath && effectiveShell == "/bin/bash") {
                    // Ensure proot still exists for legacy path
                    DebianInstaller.buildProotCommand(context).also { cmd ->
                        // Replace proot path with ensured binary if needed
                        if (cmd.isNotEmpty()) cmd[0] = prootBinary.absolutePath
                    }
                } else {
                    buildProotForDistro(context, targetRoot, effectiveShell, prootBinary)
                }
            }
            else -> buildProotForDistro(context, targetRoot, effectiveShell, prootBinary)
        }
        // VASTAVIK CLI fix: ensure PS1, TERM, SHELL for interactive prompt (Alpine /bin/sh needs PS1)
        val env = mapOf(
            "HOME" to "/root",
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR" to "/tmp",
            "SHELL" to effectiveShell,
            "PS1" to "\\u@\\h:\\w\\# "
        )
        // CRITICAL: Host working directory must ALWAYS be context.filesDir, NOT the inner chroot directory
        // The chroot working directory is set via PRoot's -w /root flag
        val cwd = context.filesDir.absolutePath

        Log.i(TAG, "Starting session $sessionId: ${command.joinToString(" ")}")

        try {
            ptyProcess = PtyProcess.spawn(command, env, cwd, rows, cols)
            _isRunning.value = true
            startReaderThreads()

            // Monitor process exit
            scope.launch {
                try {
                    val exitCode = withContext(Dispatchers.IO) { ptyProcess?.waitFor() ?: -1 }
                    Log.i(TAG, "Session $sessionId exited with $exitCode")
                    withContext(Dispatchers.Main) {
                        _isRunning.value = false
                        appendToBuffer("\r\n[Process exited with code $exitCode]\r\n")
                        onExitListener?.invoke(exitCode)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "WaitFor failed", e)
                }
            }

            appendToBuffer("VASTAVIK CLI v2.0 (ARM64 Subsystem) • $distroId • PRoot • PID ${ptyProcess?.pid}\r\n")
            appendToBuffer("Type 'apt update && apt upgrade' to initialize package manager.\r\n\r\n")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start session", e)
            appendToBuffer("Failed to start shell: ${e.message}\r\n")
            _isRunning.value = false
        }
    }

    private fun startReaderThreads() {
        val proc = ptyProcess ?: return

        readerJob = scope.launch {
            val buffer = ByteArray(8192)
            val input = proc.inputStream
            try {
                while (isActive && proc.isAlive) {
                    val n = withContext(Dispatchers.IO) {
                        try { input.read(buffer) } catch (e: IOException) { -1 }
                    }
                    if (n == -1) break
                    if (n > 0) {
                        val text = String(buffer, 0, n, Charsets.UTF_8)
                        appendToBuffer(text)
                    }
                }
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "Reader error", e)
            }
        }

        // Only needed for ProcessBuilder fallback (native PTY merges stderr)
        if (proc.errorStream.available() >= 0) {
            errorReaderJob = scope.launch {
                val buffer = ByteArray(4096)
                val err = proc.errorStream
                try {
                    while (isActive && proc.isAlive) {
                        val n = withContext(Dispatchers.IO) {
                            try {
                                if (err.available() > 0) err.read(buffer) else {
                                    delay(50); 0
                                }
                            } catch (_: IOException) { -1 }
                        }
                        if (n == -1) break
                        if (n > 0) {
                            val text = String(buffer, 0, n, Charsets.UTF_8)
                            appendToBuffer(text)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun appendToBuffer(text: String) {
        // Keep buffer bounded to avoid OOM (Termux uses 64k lines)
        synchronized(_output) {
            _output.value.append(text)
            // Trim if > 512KB
            if (_output.value.length > 512 * 1024) {
                val excess = _output.value.length - 400 * 1024
                _output.value.delete(0, excess)
            }
            _displayText.value = _output.value.toString()
        }
    }

    /**
     * Send input to shell. Applies CTRL/ALT latching handled in UI, but also supports raw bytes.
     */
    fun write(data: String) {
        write(data.toByteArray(Charsets.UTF_8))
    }

    fun write(bytes: ByteArray) {
        val proc = ptyProcess
        if (proc == null || !_isRunning.value) {
            Log.w(TAG, "Write ignored, no process")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                proc.outputStream.write(bytes)
                proc.outputStream.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Write failed", e)
            }
        }
    }

    /**
     * Send single byte (for CTRL codes)
     */
    fun sendControlByte(b: Int) {
        write(byteArrayOf(b.toByte()))
    }

    /**
     * VT100 resize - called from Compose when view size changes.
     */
    fun resizePty(newRows: Int, newCols: Int, xPixel: Int = 0, yPixel: Int = 0) {
        if (newRows == rows && newCols == cols) return
        rows = newRows
        cols = newCols
        Log.d(TAG, "resizePty $cols x $rows for $sessionId")
        ptyProcess?.resizePty(newRows, newCols, xPixel, yPixel)
        // Also send SIGWINCH is handled inside PtyProcess
    }

    /**
     * Clear buffer (e.g., clear command or Ctrl+L)
     */
    fun clearBuffer() {
        synchronized(_output) {
            _output.value.clear()
            _displayText.value = ""
        }
    }

    fun destroy() {
        Log.i(TAG, "Destroying session $sessionId")
        readerJob?.cancel()
        errorReaderJob?.cancel()
        scope.cancel()
        try {
            ptyProcess?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroy pty failed", e)
        }
        _isRunning.value = false
    }

    fun getSessionId(): String = sessionId
    fun getRootfsPath(): String = distroRoot?.absolutePath ?: ""

    private fun buildProotForDistro(context: Context, root: File, shell: String = "/bin/bash", prootFile: File? = null): Array<String> {
        val filesDir = context.filesDir.absolutePath
        val proot = prootFile?.absolutePath ?: "$filesDir/bin/proot"
        val rootPath = root.absolutePath
        // Ensure tmp dir exists on host for bind
        try { File(root, "tmp").mkdirs() } catch (_: Exception) {}
        // VASTAVIK CLI spec: dynamic rootfsDirectory with DNS guarantee, interactive -i -l
        return arrayOf(
            proot,
            "-r", rootPath,
            "-0",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/system",
            "-b", "${File(root, "tmp").absolutePath}:/tmp",
            "-b", "$rootPath/dev/shm:/dev/shm",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "SHELL=$shell",
            "PS1=\\u@\\h:\\w\\# ",
            shell, "-i", "-l"
        )
    }
}

// ──────────────────────────────────────────────────────────────────────
// Singleton manager - holds all sessions for the foreground service
// ──────────────────────────────────────────────────────────────────────

/**
 * Legacy manager - now delegates to SessionManager for unified multi-session engine.
 * Kept for backward compatibility with TerminalSessionService and older callers.
 */
object TerminalSessionManager {
    private const val TAG = "TerminalSessionManager"

    // Legacy map retained for direct TerminalSession callers, but primary source is SessionManager
    private val legacySessions = ConcurrentHashMap<String, TerminalSession>()
    private val idCounter = AtomicInteger(0)

    val sessionCount: Int get() = SessionManager.sessionCount.coerceAtLeast(legacySessions.size)
    val hasSessions: Boolean get() = SessionManager.hasSessions || legacySessions.isNotEmpty()

    fun createSession(context: Context, rows: Int = 24, cols: Int = 80): TerminalSession {
        // Delegate to new engine
        val state = SessionManager.createSession(context, "debian", "Debian", rows = rows, cols = cols, startImmediately = false)
        // Also track in legacy map for callers that expect legacy behavior
        legacySessions[state.id] = state.terminalEmulator
        Log.i(TAG, "Created via SessionManager id=${state.id} legacySize=${legacySessions.size}")
        return state.terminalEmulator
    }

    fun getSession(id: String): TerminalSession? =
        SessionManager.getSession(id)?.terminalEmulator ?: legacySessions[id]

    fun getAllSessions(): List<TerminalSession> =
        SessionManager.getAllSessions().map { it.terminalEmulator } + legacySessions.values.filter { ls -> SessionManager.getAllSessions().none { it.id == ls.getSessionId() } }

    fun getOrCreateDefault(context: Context): TerminalSession {
        val existing = SessionManager.getAllSessions().firstOrNull()?.terminalEmulator
        if (existing != null) return existing
        if (legacySessions.isNotEmpty()) return legacySessions.values.first()
        val state = SessionManager.createSession(context, "debian", "Debian")
        legacySessions[state.id] = state.terminalEmulator
        return state.terminalEmulator
    }

    fun removeSession(id: String) {
        legacySessions.remove(id)?.destroy()
        SessionManager.closeSession(id)
        Log.i(TAG, "Removed session $id")
    }

    fun terminateAll() {
        Log.i(TAG, "Terminating all - delegate to SessionManager + legacy")
        SessionManager.terminateAll()
        legacySessions.values.forEach { it.destroy() }
        legacySessions.clear()
    }

    fun forEach(action: (TerminalSession) -> Unit) {
        SessionManager.getAllSessions().forEach { action(it.terminalEmulator) }
        // also legacy not in new manager
        legacySessions.values.forEach { ls ->
            if (SessionManager.getSession(ls.getSessionId()) == null) action(ls)
        }
    }
}
