package com.shellzero.terminal

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * VASTAVIK CLI - Multi-Session Engine Architecture
 * Spec §2: maintains synchronized list of TerminalSession instances,
 * supports adding, switching without terminating background, graceful close on `exit 0`.
 *
 * All sessions run under the same unkillable TerminalSessionService foreground notification.
 *
 * This is the authoritative manager. The legacy `TerminalSessionManager` now delegates here.
 */

// Type alias so spec field `terminalEmulator: TerminalEmulator` maps to our existing engine
typealias TerminalEmulator = TerminalSession

/**
 * Spec exact data class + extended fields for distro isolation.
 * Fixes "Kali Launches Debian" bug: stores active rootfsPath dynamically.
 * VASTAVIK CLI requires SessionState to route to $FILES_DIR/distros/<distroId>.
 */
data class SessionState(
    val id: String,
    val index: Int,
    var name: String,
    val distroId: String,
    val rootfsPath: String,
    val ptyProcess: PtyProcess?,
    val terminalView: TerminalEmulator,
    val distroName: String = distroId,
    val createdAt: Long = System.currentTimeMillis()
) {
    // Backward-compat aliases
    var customName: String
        get() = name
        set(value) { name = value }
    val terminalEmulator: TerminalEmulator get() = terminalView
    // Convenience: exposes the underlying TerminalSession directly
    val session: TerminalSession get() = terminalView
    val displayName: String get() = name.ifBlank { "$distroName (sh)" }
}

object SessionManager {

    private const val TAG = "SessionManager"

    private val lock = ReentrantLock()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val idCounter = AtomicInteger(0)
    private val indexCounter = AtomicInteger(0)

    // Backing synchronized list
    private val _sessions = MutableStateFlow<List<SessionState>>(emptyList())
    val sessions: StateFlow<List<SessionState>> = _sessions.asStateFlow()

    private val _activeSession = MutableStateFlow<SessionState?>(null)
    val activeSession: StateFlow<SessionState?> = _activeSession.asStateFlow()

    val sessionCount: Int get() = _sessions.value.size
    val hasSessions: Boolean get() = _sessions.value.isNotEmpty()

    // ──────────────────────────────────────────────────────────────────
    // Core API
    // ──────────────────────────────────────────────────────────────────

    /**
     * Create a new PTY shell session isolated inside distro's rootfs via PRoot.
     * Fixes "Kali Launches Debian" bug: uses dynamic rootfsDirectory, not hardcoded debian.
     * Does NOT terminate existing sessions; switches active canvas to new one.
     *
     * @param distroId  folder under $FILES_DIR/distros/<id> or "debian" for legacy
     * @param distroName display name
     * @param rootfsDirectory explicit absolute path to distro root (e.g., $FILES_DIR/distros/kali) — if null, resolved via distroId
     * @param shellPath shell to launch inside PRoot (e.g., "/bin/bash" or "/bin/sh" for Alpine)
     * @param customName optional custom name, defaults to "$distroName (sh)"
     */
    fun createSession(
        context: Context,
        distroId: String = "debian",
        distroName: String = "Debian",
        customName: String? = null,
        rootfsDirectory: String? = null,
        shellPath: String = "/bin/bash",
        rows: Int = 24,
        cols: Int = 80,
        startImmediately: Boolean = true
    ): SessionState = lock.withLock {

        val id = "sz-${idCounter.incrementAndGet()}-${System.currentTimeMillis() % 10000}"
        val index = indexCounter.incrementAndGet()
        val name = customName?.takeIf { it.isNotBlank() } ?: "$distroName (sh)"

        // Resolve rootfs path: use explicit rootfsDirectory if provided (spec fix), else resolve via distroId
        val rootfsDir = rootfsDirectory?.let { File(it) } ?: resolveDistroRoot(context, distroId)
        val rootfsPath = rootfsDir.absolutePath

        // Create TerminalSession that will spawn PRoot with that rootfs and shell
        val terminalSession = TerminalSession(
            context = context.applicationContext,
            sessionId = id,
            initialRows = rows,
            initialCols = cols,
            distroId = distroId,
            distroRoot = rootfsDir,
            shellPath = shellPath
        )

        // Pre-create PtyProcess placeholder; actual process is inside TerminalSession.start()
        val state = SessionState(
            id = id,
            index = index,
            name = name,
            distroId = distroId,
            rootfsPath = rootfsPath,
            ptyProcess = null, // will be populated after start via session.ptyProcess
            terminalView = terminalSession,
            distroName = distroName
        )

        // Attach exit listener: when shell exits `exit 0`, gracefully close session
        terminalSession.setOnExitListener { exitCode ->
            scope.launch {
                Log.i(TAG, "Session $id (${state.displayName}) exited with $exitCode, closing gracefully")
                closeSession(id)
            }
        }

        val updated = _sessions.value + state
        _sessions.value = updated
        _activeSession.value = state

        Log.i(TAG, "Created session id=$id index=$index name=$name distro=$distroId root=${rootfsDir.absolutePath} total=${updated.size}")

        if (startImmediately) {
            // Start on IO thread
            scope.launch(Dispatchers.IO) {
                terminalSession.start()
                // After start, update notification via service (service observes sessionCount)
                // Optionally refresh state with ptyProcess reference
                refreshPtyReference(id)
            }
        }

        // Update foreground notification badge
        updateNotificationBadge(context)

        state
    }

    /**
     * Spec-compliant: create new session with explicit rootfsDirectory and shellPath.
     * Used by Distro Center "Launch Session" to ensure Kali doesn't launch Debian.
     */
    fun createNewSession(
        context: Context,
        distroName: String,
        distroId: String,
        rootfsDirectory: String,
        shellPath: String = "/bin/bash"
    ): SessionState = createSession(
        context = context,
        distroId = distroId,
        distroName = distroName,
        rootfsDirectory = rootfsDirectory,
        shellPath = shellPath
    )

    /**
     * Helper for Distro Center drawer: validates extraction then spawns.
     * Mirrors spec's launchDistroSession.
     */
    fun launchDistroSession(
        context: Context,
        distroId: String,
        distroName: String,
        rootfsDirectory: String,
        shellPath: String = "/bin/bash",
        onNotExtracted: (() -> Unit)? = null
    ): SessionState? {
        val distroRootfs = File(rootfsDirectory)
        if (!distroRootfs.exists() || !File(distroRootfs, "bin").exists()) {
            Log.w(TAG, "$distroName is not extracted yet at $rootfsDirectory")
            onNotExtracted?.invoke()
            // Fallback toast if context is Activity
            try {
                android.widget.Toast.makeText(context, "$distroName is not extracted yet.", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
            return null
        }
        return createNewSession(context, distroName, distroId, rootfsDirectory, shellPath)
    }

    /**
     * Convenience: create new session and switch active canvas to it.
     * Used by `[+] NEW SESSION` button.
     */
    fun createNewSessionAndSwitch(
        context: Context,
        distroId: String = "debian",
        distroName: String = "Debian"
    ): SessionState = createSession(context, distroId, distroName)

    fun getSession(id: String): SessionState? = lock.withLock {
        _sessions.value.find { it.id == id }
    }

    fun getAllSessions(): List<SessionState> = _sessions.value

    fun getOrCreateDefault(context: Context): SessionState = lock.withLock {
        _sessions.value.firstOrNull() ?: createSession(context)
    }

    /**
     * Switch active displayed buffer without terminating background processes.
     * UI should observe `activeSession` flow and rebind to `session.displayText`.
     */
    fun switchTo(context: Context, id: String): Boolean = lock.withLock {
        val target = _sessions.value.find { it.id == id } ?: run {
            Log.w(TAG, "switchTo: session $id not found")
            return false
        }
        _activeSession.value = target
        Log.i(TAG, "Switched active to $id (${target.displayName})")
        updateNotificationBadge(context)
        true
    }

    fun renameSession(id: String, newName: String): Boolean = lock.withLock {
        val idx = _sessions.value.indexOfFirst { it.id == id }
        if (idx == -1) return false
        val trimmed = newName.trim().ifBlank { return false }
        val old = _sessions.value[idx]
        val updatedState = old.copy(name = trimmed)
        // Preserve object identity for terminalView/ptyProcess; copy keeps them
        val mutable = _sessions.value.toMutableList()
        mutable[idx] = updatedState
        _sessions.value = mutable
        // If active, update active reference
        if (_activeSession.value?.id == id) {
            _activeSession.value = updatedState
        }
        Log.i(TAG, "Renamed session $id to $trimmed")
        true
    }

    /**
     * Gracefully close session: destroy PTY, remove from list, switch active if needed.
     * Spec fix: sync with TerminalSessionService immediately.
     */
    fun closeSession(id: String): Boolean = lock.withLock {
        val target = _sessions.value.find { it.id == id } ?: return false
        try {
            // Spec: kill child PRoot process forcibly
            target.ptyProcess?.destroyForcibly()
            target.terminalView.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "destroy failed for $id", e)
            try { target.terminalView.destroy() } catch (_: Exception) {}
        }
        val remaining = _sessions.value.filterNot { it.id == id }
        _sessions.value = remaining
        // Switch active if we closed the active one
        if (_activeSession.value?.id == id) {
            _activeSession.value = remaining.lastOrNull() // most recent remaining
        }
        Log.i(TAG, "Closed session $id remaining=${remaining.size}")
        // Spec fix: sync count with foreground service immediately
        try {
            com.shellzero.service.TerminalSessionService.instance?.updateNotification(remaining.size)
            if (remaining.isEmpty()) {
                com.shellzero.service.TerminalSessionService.instance?.stopSelfWithCleanup()
            }
        } catch (e: Exception) { Log.w(TAG, "notify service failed", e) }
        // Fallback intent-based badge update
        try {
            // Use last known context if available via remaining session? No-op if no context
        } catch (_: Exception) {}
        true
    }

    /**
     * Spec-mandated removeSession: direct alias for closeSession with extra handling.
     * Called from left drawer long-press.
     */
    fun removeSession(sessionId: String) {
        val session = _sessions.value.find { it.id == sessionId }
        session?.ptyProcess?.destroyForcibly()
        closeSession(sessionId)
        // If the active session was deleted, switch to the nearest available session (handled in closeSession)
        // Sync count already handled in closeSession
        if (_sessions.value.isEmpty()) {
            try { com.shellzero.service.TerminalSessionService.instance?.stopSelfWithCleanup() } catch (_: Exception) {}
        }
    }

    /**
     * Kill via long-press dialog: alias to closeSession but with logging.
     */
    fun killSession(id: String): Boolean {
        Log.i(TAG, "Kill requested for $id")
        return closeSession(id)
    }

    /** Spec alias for destroyAllSessions */
    fun destroyAllSessions() = terminateAll()

    /**
     * Terminate all sessions (called by TerminalSessionService on Exit action).
     */
    fun terminateAll() = lock.withLock {
        Log.i(TAG, "Terminating all ${ _sessions.value.size } sessions")
        // Copy to avoid concurrent modification
        val copy = _sessions.value.toList()
        copy.forEach { state ->
            try { state.terminalEmulator.destroy() } catch (_: Exception) {}
        }
        _sessions.value = emptyList()
        _activeSession.value = null
        indexCounter.set(0)
    }

    /**
     * Handle shell exit automatically: same as closeSession but invoked from TerminalSession exit listener.
     */
    internal fun handleShellExit(id: String, exitCode: Int) {
        scope.launch {
            closeSession(id)
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    /**
     * Resolve distro root folder.
     * New isolation: $FILES_DIR/distros/<distro_id>
     * Legacy fallback: $FILES_DIR/debian for "debian"
     * Shared binaries remain in $FILES_DIR/bin/proot
     */
    fun resolveDistroRoot(context: Context, distroId: String): File {
        val filesDir = context.filesDir
        val isolated = File(filesDir, "distros/$distroId")
        if (isolated.exists() && File(isolated, "bin/bash").exists()) {
            return isolated
        }
        // Legacy path for Debian
        if (distroId == "debian") {
            val legacy = File(filesDir, "debian")
            if (legacy.exists()) return legacy
            // If neither exists, return isolated as target for future install
            return isolated
        }
        return isolated
    }

    fun getDistroRoot(context: Context, distroId: String): File = resolveDistroRoot(context, distroId)

    fun isDistroInstalled(context: Context, distroId: String): Boolean {
        val root = resolveDistroRoot(context, distroId)
        return root.exists() && File(root, "bin/bash").exists()
    }

    private fun refreshPtyReference(id: String) = lock.withLock {
        // No-op if SessionState already holds reference via terminalEmulator.ptyProcess
        // But we can update SessionState.ptyProcess field via copy if needed
        val idx = _sessions.value.indexOfFirst { it.id == id }
        if (idx != -1) {
            val old = _sessions.value[idx]
            val pty = old.terminalEmulator.getPtyProcess()
            if (pty != null && old.ptyProcess == null) {
                val updated = old.copy(ptyProcess = pty)
                val mutable = _sessions.value.toMutableList()
                mutable[idx] = updated
                _sessions.value = mutable
                if (_activeSession.value?.id == id) _activeSession.value = updated
            }
        }
    }

    private fun updateNotificationBadge(context: Context) {
        // Trigger service to refresh notification - service observes SessionManager.sessionCount
        // We send a broadcast-like intent to update; simplest is to re-start service with ACTION_START
        try {
            val svcIntent = android.content.Intent(context, com.shellzero.service.TerminalSessionService::class.java).apply {
                action = com.shellzero.service.TerminalSessionService.ACTION_UPDATE_BADGE
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(svcIntent)
            } else {
                context.startService(svcIntent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "updateNotificationBadge failed", e)
        }
    }

    // For backward compat: expose flow as legacy manager does
    fun forEach(action: (SessionState) -> Unit) = _sessions.value.forEach(action)
}
