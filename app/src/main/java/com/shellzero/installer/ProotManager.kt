package com.shellzero.installer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * VASTAVIK CLI - Self-Healing PRoot Binary Provisioner
 * Guarantees $FILES_DIR/bin/proot exists and is executable before any distro session starts.
 * Fixes error=2 (ENOENT) for proot launch.
 * Supports Gradle auto-fetch at build time + runtime OkHttp fallback.
 */
object ProotManager {
    private const val TAG = "ProotManager"
    private const val PROOT_FALLBACK_URL = "https://raw.githubusercontent.com/EXALAB/Anlinux-Resources/master/Rootfs/PRoot/arm64/proot"

    /**
     * Suspend version per spec: auto-downloads via OkHttp if asset missing, with status callback.
     */
    suspend fun ensureProotInstalled(context: Context, onStatusUpdate: (String) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val binDir = File(context.filesDir, "bin")
        if (!binDir.exists()) binDir.mkdirs()

        val prootFile = File(binDir, "proot")

        // 1. Try unpacking from APK assets (bundled via Gradle downloadArm64Proot task)
        if (!prootFile.exists() || prootFile.length() < 50000L) {
            var assetCopied = false
            try {
                context.assets.open("bin/arm64-v8a/proot").use { input ->
                    prootFile.outputStream().use { output -> input.copyTo(output) }
                }
                assetCopied = prootFile.exists() && prootFile.length() >= 50000L
                if (assetCopied) Log.i(TAG, "Copied proot from assets to ${prootFile.absolutePath} size=${prootFile.length()}")
            } catch (e: Exception) {
                Log.w(TAG, "Assets proot not found: ${e.message}")
            }

            // 2. Fallback: Download static binary directly via OkHttp (follow redirects)
            if (!assetCopied) {
                // Check nativeLib fallback first (for .so bundling)
                try {
                    val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
                    if (nativeLib.exists() && nativeLib.length() >= 50000L) {
                        nativeLib.copyTo(prootFile, overwrite = true)
                        assetCopied = true
                        Log.i(TAG, "Copied proot from nativeLib ${nativeLib.absolutePath}")
                    }
                } catch (_: Exception) {}

                if (!assetCopied) {
                    onStatusUpdate("Fetching PRoot engine...")
                    Log.i(TAG, "Fetching PRoot from $PROOT_FALLBACK_URL")
                    val client = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()
                    val request = Request.Builder().url(PROOT_FALLBACK_URL).header("User-Agent", "VASTAVIK-CLI-Agent/2.0").build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IllegalStateException("Failed to download PRoot: HTTP ${response.code} ${response.message}")
                        val body = response.body ?: throw IllegalStateException("Empty body for PRoot download")
                        body.byteStream().use { stream ->
                            prootFile.outputStream().use { output -> stream.copyTo(output) }
                        }
                        Log.i(TAG, "Downloaded proot via OkHttp to ${prootFile.absolutePath} size=${prootFile.length()}")
                    }
                }
            }
        } else {
            Log.i(TAG, "Proot already exists at ${prootFile.absolutePath} size=${prootFile.length()} executable=${prootFile.canExecute()}")
        }

        // Set permissions: chmod 755
        try {
            prootFile.setReadable(true, false)
            prootFile.setExecutable(true, false)
            try { android.system.Os.chmod(prootFile.absolutePath, 493) } catch (_: Exception) {}
        } catch (e: Exception) { Log.w(TAG, "chmod failed", e) }

        if (!prootFile.exists() || prootFile.length() < 50000L) {
            throw IllegalStateException("PRoot binary verification failed. Size: ${prootFile.length()} bytes. Checked assets/bin/arm64-v8a/proot and ${context.applicationInfo.nativeLibraryDir}/libproot.so and $PROOT_FALLBACK_URL")
        }
        if (!prootFile.canExecute()) {
            prootFile.setExecutable(true, false)
        }
        Log.i(TAG, "Proot ready at ${prootFile.absolutePath} size=${prootFile.length()} executable=${prootFile.canExecute()}")
        return@withContext prootFile
    }

    /**
     * Legacy non-suspend wrapper for callers not in coroutine (e.g., TerminalSession.start()).
     * Delegates to suspend version via runBlocking.
     */
    fun ensureProotInstalledBlocking(context: Context): File {
        return kotlinx.coroutines.runBlocking { ensureProotInstalled(context) }
    }

    // Backward-compat alias for old callers
    fun ensureProotInstalledLegacy(context: Context): File = ensureProotInstalledBlocking(context)

    fun isProotInstalled(context: Context): Boolean {
        val f = File(context.filesDir, "bin/proot")
        return f.exists() && f.length() >= 50000L && f.canExecute()
    }
}
