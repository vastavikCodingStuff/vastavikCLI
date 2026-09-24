package com.shellzero.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.shellzero.terminal.SessionManager
import com.shellzero.terminal.TerminalSession
import com.shellzero.terminal.TerminalSessionManager

/**
 * VASTAVIK CLI - Unkillable Foreground Service
 * Termux lifecycle model: START_STICKY + onTaskRemoved() no-op + WakeLock
 *
 * Manifest must declare: android:foregroundServiceType="specialUse"
 */
class TerminalSessionService : Service() {

    companion object {
        const val CHANNEL_ID = "terminal_session_channel"
        const val CHANNEL_NAME = "VASTAVIK CLI Session Service"
        const val NOTIFICATION_ID = 1001

        // Spec mandates com.vastavik.cli.ACTION_EXIT, keep legacy com.shellzero for compat
        const val ACTION_EXIT = "com.vastavik.cli.ACTION_EXIT"
        const val ACTION_EXIT_LEGACY = "com.shellzero.action.EXIT"
        const val ACTION_TOGGLE_WAKELOCK = "com.vastavik.cli.ACTION_TOGGLE_WAKELOCK"
        const val ACTION_TOGGLE_WAKELOCK_LEGACY = "com.shellzero.action.TOGGLE_WAKELOCK"
        const val ACTION_START = "com.vastavik.cli.ACTION_START"
        const val ACTION_START_LEGACY = "com.shellzero.action.START_SERVICE"
        const val ACTION_UPDATE_BADGE = "com.shellzero.action.UPDATE_BADGE"
        var instance: TerminalSessionService? = null
            private set

        fun start(context: Context) {
            val intent = Intent(context, TerminalSessionService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TerminalSessionService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var isWakeLockHeld: Boolean = false

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        acquireWakeLockIfNeeded(initial = false) // start without wakelock, user toggles
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_EXIT, ACTION_EXIT_LEGACY -> {
                stopSelfWithCleanup()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_WAKELOCK, ACTION_TOGGLE_WAKELOCK_LEGACY -> {
                toggleWakeLock()
                // refresh notification to reflect new state
                updateNotification()
                return START_STICKY
            }
            ACTION_UPDATE_BADGE -> {
                updateNotification()
                return START_STICKY
            }
            ACTION_START, ACTION_START_LEGACY, null -> {
                // Normal start
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }

        // Ensure foreground even if intent is null (system restart with START_STICKY)
        if (intent == null || intent.action == null) {
            try {
                startForeground(NOTIFICATION_ID, buildNotification())
            } catch (_: Exception) {
                // Already in foreground
            }
        } else if (intent.action != ACTION_EXIT && intent.action != ACTION_TOGGLE_WAKELOCK) {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        return START_STICKY
    }

    /**
     * Critical: Termux model - swiping away app must NOT kill service or PRoot child.
     * We intentionally do nothing here except ensure service restarts.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Re-launch service if task is removed (swiped from recents)
        // START_STICKY will handle restart, but we explicitly ensure foreground notification remains
        val restartIntent = Intent(applicationContext, TerminalSessionService::class.java).apply {
            action = ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                startForegroundService(restartIntent)
            } catch (_: IllegalStateException) {
                // Fallback if background start restriction
            }
        } else {
            startService(restartIntent)
        }
        // Do NOT call stopSelf() - keep PRoot process alive
    }

    override fun onDestroy() {
        releaseWakeLock()
        SessionManager.terminateAll()
        TerminalSessionManager.terminateAll()
        // Remove notification
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
            @Suppress("DEPRECATION")
            try { stopForeground(true) } catch (_: Exception) {}
        }
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ──────────────────────────────────────────────────────────────────────
    // WakeLock Management
    // ──────────────────────────────────────────────────────────────────────

    private fun getWakeLock(): PowerManager.WakeLock {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "VASTAVIK CLI::TerminalWakeLock"
            ).apply {
                setReferenceCounted(false)
            }
        }
        return wakeLock!!
    }

    private fun acquireWakeLockIfNeeded(initial: Boolean = true) {
        val wl = getWakeLock()
        if (!wl.isHeld) {
            // 10 hours timeout as safety to avoid indefinite battery drain if service leaks
            wl.acquire(10 * 60 * 60 * 1000L)
            isWakeLockHeld = true
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                try { it.release() } catch (_: Exception) {}
            }
        }
        isWakeLockHeld = false
    }

    private fun toggleWakeLock() {
        if (isWakeLockHeld) {
            releaseWakeLock()
        } else {
            acquireWakeLockIfNeeded()
        }
    }

    private fun handleExit() {
        stopSelfWithCleanup()
    }

    /**
     * Spec-mandated complete cleanup for Exit action.
     * Kills PTYs, releases wake lock, removes notification, kills process.
     */
    fun stopSelfWithCleanup() {
        try {
            SessionManager.destroyAllSessions()
        } catch (_: Exception) { SessionManager.terminateAll() }
        try { SessionManager.terminateAll() } catch (_: Exception) {}
        TerminalSessionManager.terminateAll()
        if (wakeLock?.isHeld == true) {
            try { wakeLock?.release() } catch (_: Exception) {}
        }
        isWakeLockHeld = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
            @Suppress("DEPRECATION")
            try { stopForeground(true) } catch (_: Exception) {}
        }
        stopSelf()
        instance = null
        try { android.os.Process.killProcess(android.os.Process.myPid()) } catch (_: Exception) {}
    }

    /**
     * Spec-mandated live sync: called by SessionManager when sessions change.
     * If count <=0, cleanly exit service.
     */
    fun updateNotification(sessionCount: Int) {
        if (sessionCount <= 0) {
            stopSelfWithCleanup()
            return
        }
        try {
            val notification = buildNotification(sessionCount)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Fallback to generic update
            try { updateNotification() } catch (_: Exception) {}
        }
    }

    // Overload to build with explicit count (used by updateNotification(count))
    private fun buildNotification(sessionCount: Int): Notification {
        val contentText = "VASTAVIK CLI ($sessionCount session${if (sessionCount > 1) "s" else ""} active)"
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val exitIntent = Intent(this, TerminalSessionService::class.java).apply { action = ACTION_EXIT }
        val exitPending = PendingIntent.getService(this, 101, exitIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val toggleIntent = Intent(this, TerminalSessionService::class.java).apply { action = ACTION_TOGGLE_WAKELOCK }
        val togglePending = PendingIntent.getService(this, 2, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val wakeLockLabel = if (isWakeLockHeld) "Release Wake Lock" else "Acquire Wake Lock"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VASTAVIK CLI")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_more)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(NotificationCompat.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Exit", exitPending).build())
            .addAction(NotificationCompat.Action.Builder(if (isWakeLockHeld) android.R.drawable.ic_lock_idle_lock else android.R.drawable.ic_lock_idle_alarm, wakeLockLabel, togglePending).build())
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .build()
    }

    // ──────────────────────────────────────────────────────────────────────
    // Notification
    // ──────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // non-intrusive, persistent
            ).apply {
                description = "Persistent VASTAVIK CLI terminal sessions"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // Prefer new multi-session engine count, fall back to legacy for migration
        val newCount = SessionManager.sessionCount
        val legacyCount = TerminalSessionManager.sessionCount
        val sessionCount = maxOf(newCount, legacyCount)
        val contentText = if (sessionCount == 0) "VASTAVIK CLI (1 session active)"
        else "VASTAVIK CLI ($sessionCount session${if (sessionCount > 1) "s" else ""} active)"

        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val exitIntent = Intent(this, TerminalSessionService::class.java).apply { action = ACTION_EXIT }
        val exitPending = PendingIntent.getService(
            this, 1, exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, TerminalSessionService::class.java).apply { action = ACTION_TOGGLE_WAKELOCK }
        val togglePending = PendingIntent.getService(
            this, 2, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val wakeLockLabel = if (isWakeLockHeld) "Release Wake Lock" else "Acquire Wake Lock"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VASTAVIK CLI")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_more) // Replace with R.drawable.ic_terminal
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Exit",
                    exitPending
                ).build()
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    if (isWakeLockHeld) android.R.drawable.ic_lock_idle_lock
                    else android.R.drawable.ic_lock_idle_alarm,
                    wakeLockLabel,
                    togglePending
                ).build()
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .build()
    }

    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }
}

/**
 * Optional: keep a lightweight manager for sessions if not already implemented elsewhere.
 * If TerminalSessionManager already exists, this duplicate should be removed.
 */
// See TerminalSession.kt for actual implementation
