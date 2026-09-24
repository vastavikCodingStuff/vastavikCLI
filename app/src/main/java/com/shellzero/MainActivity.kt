package com.shellzero

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.shellzero.distro.DistroDownloader
import com.shellzero.installer.DebianInstaller
import com.shellzero.service.TerminalSessionService
import com.shellzero.terminal.SessionManager
import com.shellzero.ui.ShellZeroApp
import com.shellzero.ui.TerminalViewModel
import kotlinx.coroutines.launch

/**
 * ShellZero MainActivity
 * - Requests POST_NOTIFICATIONS on Android 13+
 * - Triggers DebianInstaller.installIfNeeded() on first launch with progress UI
 * - Starts TerminalSessionService (unkillable foreground)
 * - Hosts TerminalScreen (Compose)
 */
class MainActivity : ComponentActivity() {

    private val viewModel = TerminalViewModel()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // No-op; service can still run without notification permission but won't show ongoing NOTIF
        if (granted) startTerminalService()
        else startTerminalService() // still start, channel will be silent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkNotificationPermissionAndStart()

        // Observe installer state to drive UI (shared viewModel with Compose)
        lifecycleScope.launch {
            DebianInstaller.state.collect { state ->
                when (state) {
                    is DebianInstaller.InstallState.Extracting -> {
                        viewModel.setInstalling(true, state.progress, state.currentFile)
                    }
                    is DebianInstaller.InstallState.Configuring -> {
                        viewModel.setInstalling(true, 0.97f, "Configuring DNS & permissions...")
                    }
                    is DebianInstaller.InstallState.Success,
                    is DebianInstaller.InstallState.AlreadyInstalled -> {
                        viewModel.setInstalling(false)
                        // Use new multi-session engine: ensure at least one session exists
                        // SessionManager will create first Debian session and make it active
                        viewModel.ensureSession(this@MainActivity)
                        // Also ensure default distro status is refreshed
                        DistroDownloader.refreshStatuses(this@MainActivity)
                        // If SessionManager already has sessions, ensure notification badge updates
                        SessionManager.getOrCreateDefault(this@MainActivity)
                    }
                    is DebianInstaller.InstallState.Error -> {
                        viewModel.setInstalling(false)
                    }
                    else -> {}
                }
            }
        }

        lifecycleScope.launch {
            val ok = DebianInstaller.installIfNeeded(this@MainActivity)
            if (ok) {
                startTerminalService()
                DistroDownloader.refreshStatuses(this@MainActivity)
            }
        }

        setContent {
            ShellZeroApp(viewModel = viewModel)
        }
    }

    private fun checkNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> {
                    // already granted
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return
                }
            }
        }
        startTerminalService()
    }

    private fun startTerminalService() {
        TerminalSessionService.start(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do NOT stop service - let it survive per Termux model
        // Only stop if user explicitly presses Exit
    }
}
