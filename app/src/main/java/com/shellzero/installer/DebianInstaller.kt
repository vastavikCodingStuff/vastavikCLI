package com.shellzero.installer

import android.content.Context
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.*

/**
 * ShellZero Debian ARM64 Installer
 *
 * - Handles asset extraction of statically compiled ARM64 proot
 *   from assets/bin/arm64-v8a/proot
 * - Extracts debian-rootfs-arm64.tar.xz from assets/ into $FILES_DIR/debian
 * - Sets executable permissions via File.setExecutable(true) + Os.chmod
 * - Pre-configures DNS in $FILES_DIR/debian/etc/resolv.conf
 *
 * Requires in app/src/main/assets/:
 *   - bin/arm64-v8a/proot  (statically compiled, ~1.2MB)
 *   - debian-rootfs-arm64.tar.xz (minimal Debian Bookworm/Trixie slim, ~40-70MB compressed)
 *
 * Gradle dependency needed for extraction:
 *   implementation("org.apache.commons:commons-compress:1.26.2")
 *   implementation("org.tukaani:xz:1.9")
 */
object DebianInstaller {

    private const val TAG = "DebianInstaller"
    private const val DEBIAN_DIR_NAME = "debian"
    private const val BIN_DIR_NAME = "bin"
    private const val PROOT_ASSET_PATH = "bin/arm64-v8a/proot"
    private const val ROOTFS_ASSET = "debian-rootfs-arm64.tar.xz"
    private const val RESOLV_CONF_RELATIVE = "etc/resolv.conf"

    sealed class InstallState {
        data object Idle : InstallState()
        data object Checking : InstallState()
        data class Extracting(val progress: Float, val currentFile: String) : InstallState()
        data object Configuring : InstallState()
        data object Success : InstallState()
        data class Error(val message: String, val throwable: Throwable? = null) : InstallState()
        data object AlreadyInstalled : InstallState()
    }

    private val _state = MutableStateFlow<InstallState>(InstallState.Idle)
    val state: StateFlow<InstallState> = _state

    val isInstalled: Boolean
        get() = _state.value is InstallState.Success || _state.value is InstallState.AlreadyInstalled

    /**
     * Main entry point: call on very first launch (e.g., from MainActivity onCreate).
     * Checks if $FILES_DIR/debian exists, if not extracts rootfs.
     *
     * @return true if ready to launch shell, false if error
     */
    suspend fun installIfNeeded(context: Context): Boolean = withContext(Dispatchers.IO) {
        val filesDir = context.filesDir
        val debianDir = File(filesDir, DEBIAN_DIR_NAME)
        val prootFile = File(filesDir, "$BIN_DIR_NAME/proot")

        _state.value = InstallState.Checking

        // Fast path: already installed and valid
        if (debianDir.exists() && debianDir.isDirectory && File(debianDir, "bin/bash").exists() && prootFile.exists() && prootFile.canExecute()) {
            Log.i(TAG, "Debian rootfs already installed at ${debianDir.absolutePath}")
            ensureResolvConf(debianDir)
            ensureProotPermissions(prootFile)
            _state.value = InstallState.AlreadyInstalled
            return@withContext true
        }

        try {
            // 1. Ensure bin dir and extract proot
            _state.value = InstallState.Extracting(0f, "proot")
            extractProot(context, prootFile)

            // 2. Extract rootfs tar.xz
            _state.value = InstallState.Extracting(0.05f, ROOTFS_ASSET)
            if (!assetExists(context, ROOTFS_ASSET)) {
                throw FileNotFoundException("Asset $ROOTFS_ASSET not found. Place minimal Debian rootfs in src/main/assets/")
            }

            // Clean partial install if exists
            if (debianDir.exists()) {
                Log.w(TAG, "Cleaning partial debian dir")
                debianDir.deleteRecursively()
            }
            debianDir.mkdirs()

            extractTarXz(context, ROOTFS_ASSET, debianDir) { progress, fileName ->
                _state.value = InstallState.Extracting(progress, fileName)
            }

            // 3. Post-configure
            _state.value = InstallState.Configuring
            ensureResolvConf(debianDir)
            fixPermissions(debianDir)
            createTmpSymlink(filesDir, debianDir)

            Log.i(TAG, "Debian installation completed successfully")
            _state.value = InstallState.Success
            true
        } catch (e: Exception) {
            Log.e(TAG, "Installation failed", e)
            _state.value = InstallState.Error(e.message ?: "Unknown error", e)
            false
        }
    }

    /**
     * Synchronous check helper for quick UI gating.
     */
    fun isDebianInstalled(context: Context): Boolean {
        val debianDir = File(context.filesDir, DEBIAN_DIR_NAME)
        return debianDir.exists() && File(debianDir, "bin/bash").exists()
    }

    fun getDebianDir(context: Context): File = File(context.filesDir, DEBIAN_DIR_NAME)
    fun getProotFile(context: Context): File = File(context.filesDir, "$BIN_DIR_NAME/proot")

    // ──────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────

    private fun assetExists(context: Context, assetPath: String): Boolean {
        return try {
            context.assets.open(assetPath).close()
            true
        } catch (_: IOException) {
            false
        }
    }

    private suspend fun extractProot(context: Context, destFile: File) = withContext(Dispatchers.IO) {
        destFile.parentFile?.mkdirs()
        context.assets.open(PROOT_ASSET_PATH).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
        ensureProotPermissions(destFile)
        Log.i(TAG, "proot extracted to ${destFile.absolutePath} executable=${destFile.canExecute()}")
    }

    private fun ensureProotPermissions(prootFile: File) {
        prootFile.setExecutable(true, false)
        prootFile.setReadable(true, false)
        try {
            Os.chmod(prootFile.absolutePath, 448) // 0700
        } catch (e: Exception) {
            Log.w(TAG, "Os.chmod failed for proot, fallback to setExecutable", e)
        }
        // Verify
        if (!prootFile.canExecute()) {
            Log.w(TAG, "proot still not executable after chmod")
        }
    }

    private fun ensureResolvConf(debianDir: File) {
        val etcDir = File(debianDir, "etc")
        etcDir.mkdirs()
        val resolvConf = File(etcDir, "resolv.conf")
        // Always overwrite to ensure apt works; backup if exists and is symlink
        try {
            if (resolvConf.exists() && resolvConf.length() > 0) {
                val content = resolvConf.readText()
                if (content.contains("1.1.1.1") && content.contains("8.8.8.8")) {
                    Log.i(TAG, "resolv.conf already configured")
                    return
                }
            }
        } catch (_: Exception) {}

        val dns = """
            # ShellZero auto-generated resolv.conf
            nameserver 1.1.1.1
            nameserver 8.8.8.8
            nameserver 9.9.9.9
            options edns0
        """.trimIndent()

        try {
            // Remove symlink if it points to systemd-resolved stub
            if (resolvConf.exists()) {
                // Check if symlink via canonical vs absolute
                if (resolvConf.canonicalPath != resolvConf.absolutePath) {
                    resolvConf.delete()
                }
            }
            resolvConf.writeText(dns)
            // 0644
            try { Os.chmod(resolvConf.absolutePath, 420) } catch (_: Exception) {}
            Log.i(TAG, "Wrote resolv.conf to ${resolvConf.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write resolv.conf", e)
        }
    }

    private fun fixPermissions(debianDir: File) {
        // Ensure critical binaries are executable
        val criticalBins = listOf(
            "bin/bash", "bin/sh", "usr/bin/bash", "usr/bin/env",
            "bin/ls", "bin/cat", "usr/bin/apt", "usr/bin/apt-get", "usr/bin/dpkg"
        )
        for (rel in criticalBins) {
            val f = File(debianDir, rel)
            if (f.exists()) {
                f.setExecutable(true, false)
                try { Os.chmod(f.absolutePath, 493) } catch (_: Exception) {} // 0755
            }
        }
        // Ensure /tmp perms
        File(debianDir, "tmp").apply {
            mkdirs()
            try { Os.chmod(absolutePath, 511) } catch (_: Exception) {} // 0777
        }
    }

    private fun createTmpSymlink(filesDir: File, debianDir: File) {
        // The spec mounts $FILES_DIR/debian/tmp:/tmp via -b $FILES_DIR/debian/tmp:/tmp
        // Just ensure host tmp exists
        val hostTmp = File(debianDir, "tmp")
        hostTmp.mkdirs()
        // Also ensure root home
        File(debianDir, "root").mkdirs()
    }

    /**
     * Extracts tar.xz using commons-compress with progress callback.
     * Streams from assets to avoid needing 2x storage.
     */
    private suspend fun extractTarXz(
        context: Context,
        assetName: String,
        destDir: File,
        onProgress: (Float, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val assetFd = context.assets.openFd(assetName)
        val totalBytes = assetFd.length
        assetFd.close()

        var extractedBytes = 0L
        var lastProgressEmit = 0L

        context.assets.open(assetName).use { assetInput ->
            XZCompressorInputStream(assetInput).use { xzIn ->
                TarArchiveInputStream(xzIn).use { tarIn ->
                    var entry = tarIn.nextTarEntry
                    while (entry != null) {
                        val outFile = File(destDir, entry.name)
                        // Security: prevent Zip Slip
                        if (!outFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator)) {
                            throw IOException("Entry is outside target dir: ${entry.name}")
                        }

                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            // Handle symlinks
                            if (entry.isSymbolicLink) {
                                try {
                                    // Create symlink via Os.symlink if available (API 21+)
                                    if (outFile.exists()) outFile.delete()
                                    Os.symlink(entry.linkName, outFile.absolutePath)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Symlink failed for ${entry.name} -> ${entry.linkName}: ${e.message}")
                                    // Fallback: copy link target text (not ideal but prevents crash)
                                }
                            } else {
                                FileOutputStream(outFile).use { fos ->
                                    tarIn.copyTo(fos)
                                }
                                // Restore executable bit
                                if ((entry.mode and 0x40) != 0 || (entry.mode and 0x49) != 0) {
                                    outFile.setExecutable(true, false)
                                }
                                try {
                                    // Use full mode if possible, mask to 0777
                                    Os.chmod(outFile.absolutePath, entry.mode and 511)
                                } catch (_: Exception) {}
                            }
                        }

                        // Progress estimate based on compressed bytes read
                        // Note: XZ stream doesn't give decompressed total, so we approximate via asset bytes
                        // For better UX we count entries
                        extractedBytes += entry.size.coerceAtLeast(0)
                        val now = System.currentTimeMillis()
                        if (now - lastProgressEmit > 80) {
                            // Approximate progress 5%..95% during extraction
                            val prog = 0.05f + (0.90f * (extractedBytes % 100_000_000) / 100_000_000f)
                                .coerceIn(0f, 0.95f)
                            onProgress(prog.coerceIn(0f, 0.95f), entry.name)
                            lastProgressEmit = now
                        }

                        entry = tarIn.nextTarEntry
                    }
                }
            }
        }
        onProgress(0.98f, "finalizing")
    }

    /**
     * Utility to get the exact PRoot launch command array for TerminalSession.
     * Mirrors spec command.
     */
    fun buildProotCommand(context: Context): Array<String> {
        val filesDir = context.filesDir.absolutePath
        val proot = "$filesDir/bin/proot"
        val debian = "$filesDir/debian"
        return arrayOf(
            proot,
            "-r", debian,
            "-0",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/system",
            "-b", "$debian/tmp:/tmp",
            "-b", "$debian/dev/shm:/dev/shm",
            "-w", "/root",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "/bin/bash", "--login"
        )
    }

    /**
     * Alternative: if user wants custom shell, e.g., /bin/sh
     */
    fun buildProotCommandWithShell(context: Context, shell: String = "/bin/bash"): Array<String> {
        val base = buildProotCommand(context).toMutableList()
        // Replace last element (/bin/bash) with custom shell
        base[base.lastIndex] = shell
        return base.toTypedArray()
    }
}
