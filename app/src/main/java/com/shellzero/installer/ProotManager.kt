package com.shellzero.installer

import android.content.Context
import android.util.Log
import java.io.File

/**
 * VASTAVIK CLI - Self-Healing PRoot Binary Provisioner
 * Guarantees $FILES_DIR/bin/proot exists and is executable before any distro session starts.
 * Fixes error=2 (ENOENT) for proot launch.
 */
object ProotManager {
    private const val TAG = "ProotManager"

    fun ensureProotInstalled(context: Context): File {
        val binDir = File(context.filesDir, "bin")
        if (!binDir.exists()) {
            val created = binDir.mkdirs()
            Log.i(TAG, "Created bin dir ${binDir.absolutePath} success=$created")
        }

        val prootFile = File(binDir, "proot")

        // If not found or zero bytes, copy from assets or nativeLibDir
        if (!prootFile.exists() || prootFile.length() == 0L) {
            var copied = false
            try {
                // Try copying from assets first
                context.assets.open("bin/arm64-v8a/proot").use { input ->
                    prootFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                copied = true
                Log.i(TAG, "Copied proot from assets to ${prootFile.absolutePath} size=${prootFile.length()}")
            } catch (e: Exception) {
                Log.w(TAG, "Assets proot not found or copy failed: ${e.message}", e)
                // Fallback: Check if bundled as a native jni library (.so)
                try {
                    val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
                    if (nativeLib.exists() && nativeLib.length() > 0) {
                        nativeLib.copyTo(prootFile, overwrite = true)
                        copied = true
                        Log.i(TAG, "Copied proot from nativeLib ${nativeLib.absolutePath} to ${prootFile.absolutePath}")
                    } else {
                        Log.w(TAG, "Native lib not found at ${nativeLib.absolutePath} exists=${nativeLib.exists()}")
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "Fallback nativeLib copy failed", e2)
                }
                if (!copied) {
                    throw IllegalStateException("CRITICAL: ARM64 proot binary missing from assets and nativeLibs! Checked assets/bin/arm64-v8a/proot and ${context.applicationInfo.nativeLibraryDir}/libproot.so", e)
                }
            }
        } else {
            Log.i(TAG, "Proot already exists at ${prootFile.absolutePath} size=${prootFile.length()} executable=${prootFile.canExecute()}")
        }

        // Enforce execution permissions: rwxr-xr-x (chmod 755)
        try {
            prootFile.setReadable(true, false)
            prootFile.setExecutable(true, false)
            // Also try chmod 755 via Os
            try {
                android.system.Os.chmod(prootFile.absolutePath, 493) // 0755
            } catch (_: Exception) {}
            Log.i(TAG, "Proot permissions enforced: ${prootFile.absolutePath} executable=${prootFile.canExecute()} readable=${prootFile.canRead()}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set proot permissions", e)
        }

        if (!prootFile.canExecute()) {
            Log.w(TAG, "Proot not executable after chmod, trying setExecutable again")
            prootFile.setExecutable(true, false)
        }

        return prootFile
    }

    fun isProotInstalled(context: Context): Boolean {
        val f = File(context.filesDir, "bin/proot")
        return f.exists() && f.length() > 0 && f.canExecute()
    }
}
