package com.shellzero.distro

import android.content.Context
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * ShellZero - ARM64 Distro Hub: Download, SHA/integrity, and un-tar extraction
 * Spec §3 + §4 deliverable #5
 *
 * Storage:
 *  - Downloads: $FILES_DIR/downloads/<distro_id>.tar.xz
 *  - Distros:   $FILES_DIR/distros/<distro_id>/  (isolated)
 *  - Binaries:  $FILES_DIR/bin/proot (shared)
 */

// ──────────────────────────────────────────────────────────────────────
// Catalog
// ──────────────────────────────────────────────────────────────────────

data class DistroInfo(
    val id: String,              // folder name
    val name: String,
    val description: String,
    val version: String,
    val tag: String,             // logo/tag short e.g. "debian", "ubuntu"
    val compressedSize: String,  // display e.g. "~45MB"
    val downloadUrl: String,
    val archiveName: String,     // e.g. debian-bookworm-arm64.tar.xz
    val sha256: String? = null,  // optional integrity hash
    val isDefault: Boolean = false
)

object DistroCatalog {
    val all: List<DistroInfo> = listOf(
        DistroInfo(
            id = "debian",
            name = "Debian Minimal",
            description = "Rock-solid, stripped-down Debian Bookworm/Trixie. Recommended default with apt pre-configured.",
            version = "Trixie / Bookworm",
            tag = "debian",
            compressedSize = "~48MB",
            downloadUrl = "https://github.com/debuerreotype/docker-debian-artifacts/raw/dist-arm64/bookworm/rootfs.tar.xz",
            archiveName = "debian-rootfs-arm64.tar.xz",
            isDefault = true
        ),
        DistroInfo(
            id = "ubuntu",
            name = "Ubuntu Minimal",
            description = "Official Ubuntu 24.04 LTS minimal ARM64 rootfs. Ideal for familiar apt + snap workflows.",
            version = "24.04 LTS",
            tag = "ubuntu",
            compressedSize = "~52MB",
            downloadUrl = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04-base-arm64.tar.gz",
            archiveName = "ubuntu-base-24.04-arm64.tar.gz"
        ),
        DistroInfo(
            id = "kali",
            name = "Kali Linux",
            description = "NetHunter/Kali core ARM64 rootfs with penetration testing repos. Rolling.",
            version = "2024.4",
            tag = "kali",
            compressedSize = "~68MB",
            downloadUrl = "https://kali.download/nethunter-images/current/rootfs/kali-nethunter-rootfs-nano-arm64.tar.xz",
            archiveName = "kali-nethunter-arm64.tar.xz"
        ),
        DistroInfo(
            id = "arch",
            name = "Arch Linux ARM",
            description = "Arch Linux ARM64 (aarch64) tarball. Rolling-release, pacman included.",
            version = "2024.12",
            tag = "arch",
            compressedSize = "~58MB",
            downloadUrl = "http://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz",
            archiveName = "archlinuxarm-aarch64.tar.gz"
        ),
        DistroInfo(
            id = "alpine",
            name = "Alpine Linux",
            description = "Ultra-small (~5MB) musl/busybox environment. Perfect for quick shell & scripts.",
            version = "3.20",
            tag = "alpine",
            compressedSize = "~5MB",
            downloadUrl = "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.0-aarch64.tar.gz",
            archiveName = "alpine-minirootfs-aarch64.tar.gz"
        ),
        DistroInfo(
            id = "fedora",
            name = "Fedora Minimal",
            description = "Red Hat / Fedora ARM64 cloud-base. dnf-ready, systemd-free in PRoot.",
            version = "40",
            tag = "fedora",
            compressedSize = "~62MB",
            downloadUrl = "https://download.fedoraproject.org/pub/fedora/linux/releases/40/Container/aarch64/images/Fedora-Container-Base-40-1.14.aarch64.tar.xz",
            archiveName = "fedora-base-40-aarch64.tar.xz"
        )
    )

    fun get(id: String): DistroInfo? = all.find { it.id == id }
    val default: DistroInfo get() = all.first { it.isDefault }
}

// ──────────────────────────────────────────────────────────────────────
// Status
// ──────────────────────────────────────────────────────────────────────

sealed class DistroStatus {
    data object NotInstalled : DistroStatus()
    data class Downloading(val progress: Float, val bytesDone: Long, val totalBytes: Long?) : DistroStatus()
    data class Downloaded(val file: File) : DistroStatus()
    data class Extracting(val progress: Float, val currentFile: String) : DistroStatus()
    data object Installed : DistroStatus()
    data class Error(val message: String) : DistroStatus()
}

// ──────────────────────────────────────────────────────────────────────
// Downloader
// ──────────────────────────────────────────────────────────────────────

object DistroDownloader {

    private const val TAG = "DistroDownloader"

    // Per-distro status flows (id -> status)
    private val statusMap = mutableMapOf<String, MutableStateFlow<DistroStatus>>()

    private fun statusFlow(id: String): MutableStateFlow<DistroStatus> =
        statusMap.getOrPut(id) { MutableStateFlow(DistroStatus.NotInstalled) }

    fun observeStatus(id: String): StateFlow<DistroStatus> = statusFlow(id).asStateFlow()

    fun getStatus(id: String): DistroStatus = statusFlow(id).value

    fun refreshStatuses(context: Context) {
        for (info in DistroCatalog.all) {
            val installed = isInstalled(context, info.id)
            val downloading = statusFlow(info.id).value is DistroStatus.Downloading || statusFlow(info.id).value is DistroStatus.Extracting
            if (!downloading) {
                statusFlow(info.id).value = if (installed) DistroStatus.Installed else DistroStatus.NotInstalled
            }
        }
    }

    fun getDownloadsDir(context: Context): File = File(context.filesDir, "downloads").apply { mkdirs() }
    fun getDistroRoot(context: Context, id: String): File = File(context.filesDir, "distros/$id")
    fun getDownloadFile(context: Context, info: DistroInfo): File = File(getDownloadsDir(context), "${info.id}.tar.${if (info.archiveName.endsWith(".gz")) "gz" else "xz"}")

    fun isInstalled(context: Context, id: String): Boolean {
        val root = getDistroRoot(context, id)
        // Alpine uses /bin/sh vs /bin/bash
        return root.exists() && (File(root, "bin/bash").exists() || File(root, "bin/sh").exists())
    }

    fun isDownloaded(context: Context, info: DistroInfo): Boolean {
        val f = getDownloadFile(context, info)
        return f.exists() && f.length() > 1024 * 1024 // at least 1MB
    }

    // ── Public workflow: download + install (download then extract) ──

    /**
     * Combined "Download & Install": streaming HTTP download with live progress bar
     * into $FILES_DIR/downloads/, then auto-extract into $FILES_DIR/distros/<id>/.
     */
    suspend fun downloadAndInstall(context: Context, info: DistroInfo): Boolean {
        val ok = download(context, info)
        if (!ok) return false
        return extract(context, info)
    }

    suspend fun download(context: Context, info: DistroInfo): Boolean = withContext(Dispatchers.IO) {
        val destFile = getDownloadFile(context, info)
        destFile.parentFile?.mkdirs()

        // If already downloaded and status is Downloaded, reuse
        if (destFile.exists() && destFile.length() > 0) {
            // Check if already installed? Still allow re-download? For now skip download
            // But we want to ensure integrity: if file exists, emit Downloaded and return true
            // User can delete to re-download; here we skip if file looks complete
            // We don't know total size, so we just attempt resume? Simpler: skip if exists
            Log.i(TAG, "Download file exists ${destFile.absolutePath} size=${destFile.length()}, skipping download")
            statusFlow(info.id).value = DistroStatus.Downloaded(destFile)
            return@withContext true
        }

        statusFlow(info.id).value = DistroStatus.Downloading(0f, 0, null)
        var connection: HttpURLConnection? = null
        try {
            val url = URL(info.downloadUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "ShellZero/1.0 (Linux; Android aarch64)")
            }
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IOException("HTTP $responseCode for ${info.downloadUrl}: ${connection.responseMessage}")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 }
            val input = BufferedInputStream(connection.inputStream)
            val output = BufferedOutputStream(FileOutputStream(destFile))
            val buffer = ByteArray(32 * 1024)
            var bytesDone = 0L
            var lastEmit = 0L
            var read: Int

            // Optional SHA256 digest
            val digest = info.sha256?.let { MessageDigest.getInstance("SHA-256") }

            input.use { ins ->
                output.use { outs ->
                    while (true) {
                        read = ins.read(buffer)
                        if (read == -1) break
                        outs.write(buffer, 0, read)
                        bytesDone += read
                        digest?.update(buffer, 0, read)
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 120) {
                            val prog = if (total != null && total > 0) bytesDone.toFloat() / total.toFloat() else (bytesDone % 50_000_000 / 50_000_000f * 0.9f)
                            statusFlow(info.id).value = DistroStatus.Downloading(prog.coerceIn(0f, 0.99f), bytesDone, total)
                            lastEmit = now
                        }
                    }
                    outs.flush()
                }
            }

            // Verify SHA if provided
            if (info.sha256 != null && digest != null) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(info.sha256, ignoreCase = true)) {
                    destFile.delete()
                    throw IOException("SHA256 mismatch for ${info.id}: expected ${info.sha256}, got $actual")
                }
                Log.i(TAG, "SHA verified for ${info.id}")
            }

            statusFlow(info.id).value = DistroStatus.Downloaded(destFile)
            Log.i(TAG, "Download completed for ${info.id} -> ${destFile.absolutePath} bytes=$bytesDone")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${info.id}", e)
            statusFlow(info.id).value = DistroStatus.Error(e.message ?: "Download failed")
            // Keep partial file? Delete to avoid confusion
            try { destFile.delete() } catch (_: Exception) {}
            false
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Extract: unpacks $FILES_DIR/downloads/<id>.tar.* into $FILES_DIR/distros/<id>/
     * and configures execution permissions.
     */
    suspend fun extract(context: Context, info: DistroInfo): Boolean = withContext(Dispatchers.IO) {
        val archiveFile = getDownloadFile(context, info)
        if (!archiveFile.exists()) {
            statusFlow(info.id).value = DistroStatus.Error("Archive not downloaded")
            return@withContext false
        }
        val destDir = getDistroRoot(context, info.id)
        statusFlow(info.id).value = DistroStatus.Extracting(0f, "preparing")

        try {
            if (destDir.exists()) {
                Log.w(TAG, "Cleaning existing distro dir ${destDir.absolutePath}")
                destDir.deleteRecursively()
            }
            destDir.mkdirs()

            val isGz = info.archiveName.endsWith(".gz") || archiveFile.name.endsWith(".gz")
            val isXz = info.archiveName.endsWith(".xz") || archiveFile.name.endsWith(".xz")

            FileInputStream(archiveFile).use { fis ->
                BufferedInputStream(fis).use { bis ->
                    val decompressed: InputStream = when {
                        isXz -> XZCompressorInputStream(bis)
                        isGz -> GzipCompressorInputStream(bis)
                        else -> bis
                    }
                    decompressed.use { compIn ->
                        TarArchiveInputStream(compIn).use { tarIn ->
                            var entry = tarIn.nextTarEntry
                            var extractedCount = 0
                            var lastEmit = 0L
                            while (entry != null) {
                                val outFile = File(destDir, entry.name)
                                // Zip-Slip protection
                                if (!outFile.canonicalPath.startsWith(destDir.canonicalPath + File.separator) && outFile.canonicalPath != destDir.canonicalPath) {
                                    throw IOException("Entry outside dest: ${entry.name}")
                                }

                                if (entry.isDirectory) {
                                    outFile.mkdirs()
                                } else {
                                    outFile.parentFile?.mkdirs()
                                    if (entry.isSymbolicLink) {
                                        try {
                                            if (outFile.exists()) outFile.delete()
                                            Os.symlink(entry.linkName, outFile.absolutePath)
                                        } catch (e: Exception) {
                                            Log.w(TAG, "Symlink failed ${entry.name} -> ${entry.linkName}", e)
                                        }
                                    } else {
                                        FileOutputStream(outFile).use { fos ->
                                            tarIn.copyTo(fos)
                                        }
                                        if ((entry.mode and 0x40) != 0 || (entry.mode and 0x49) != 0) {
                                            outFile.setExecutable(true, false)
                                        }
                                        try { Os.chmod(outFile.absolutePath, entry.mode and 511) } catch (_: Exception) {}
                                    }
                                }

                                extractedCount++
                                val now = System.currentTimeMillis()
                                if (now - lastEmit > 100) {
                                    val prog = (0.05f + (extractedCount % 5000 / 5000f * 0.9f)).coerceIn(0f, 0.95f)
                                    statusFlow(info.id).value = DistroStatus.Extracting(prog, entry.name)
                                    lastEmit = now
                                }

                                entry = tarIn.nextTarEntry
                            }
                        }
                    }
                }
            }

            // Post-configure: ensure DNS + perms like DebianInstaller
            ensureResolvConf(destDir)
            fixPermissions(destDir)

            statusFlow(info.id).value = DistroStatus.Installed
            Log.i(TAG, "Extract completed for ${info.id} -> ${destDir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Extract failed for ${info.id}", e)
            statusFlow(info.id).value = DistroStatus.Error(e.message ?: "Extract failed")
            try { destDir.deleteRecursively() } catch (_: Exception) {}
            false
        }
    }

    /**
     * Delete: removes installed rootfs to free storage; keeps download cache unless user cleans downloads too.
     */
    suspend fun delete(context: Context, info: DistroInfo, deleteArchive: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        try {
            val root = getDistroRoot(context, info.id)
            if (root.exists()) root.deleteRecursively()
            if (deleteArchive) {
                val arch = getDownloadFile(context, info)
                if (arch.exists()) arch.delete()
            }
            statusFlow(info.id).value = DistroStatus.NotInstalled
            Log.i(TAG, "Deleted ${info.id} distroroot=${root.absolutePath} archiveDelete=$deleteArchive")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Delete failed for ${info.id}", e)
            statusFlow(info.id).value = DistroStatus.Error(e.message ?: "Delete failed")
            false
        }
    }

    /**
     * Launch Session: spawns new terminal session isolated inside distro's rootfs via PRoot.
     */
    fun launchSession(context: Context, info: DistroInfo, rows: Int = 24, cols: Int = 80): Boolean {
        if (!isInstalled(context, info.id)) {
            Log.w(TAG, "launchSession failed: ${info.id} not installed")
            statusFlow(info.id).value = DistroStatus.Error("Not installed")
            return false
        }
        // Delegate to SessionManager
        com.shellzero.terminal.SessionManager.createSession(
            context = context,
            distroId = info.id,
            distroName = info.name
        )
        Log.i(TAG, "Launched session for ${info.id}")
        return true
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers (mirrors DebianInstaller post-configure)
    // ──────────────────────────────────────────────────────────────────

    private fun ensureResolvConf(distroRoot: File) {
        val etcDir = File(distroRoot, "etc")
        etcDir.mkdirs()
        val resolvConf = File(etcDir, "resolv.conf")
        try {
            if (resolvConf.exists() && resolvConf.length() > 0) {
                val content = resolvConf.readText()
                if (content.contains("1.1.1.1")) return
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
            if (resolvConf.exists() && resolvConf.canonicalPath != resolvConf.absolutePath) {
                resolvConf.delete()
            }
            resolvConf.writeText(dns)
            try { Os.chmod(resolvConf.absolutePath, 420) } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(TAG, "ensureResolvConf failed", e)
        }
    }

    private fun fixPermissions(distroRoot: File) {
        val critical = listOf("bin/bash", "bin/sh", "usr/bin/bash", "usr/bin/env", "bin/ls")
        for (rel in critical) {
            val f = File(distroRoot, rel)
            if (f.exists()) {
                f.setExecutable(true, false)
                try { Os.chmod(f.absolutePath, 493) } catch (_: Exception) {}
            }
        }
        File(distroRoot, "tmp").apply {
            mkdirs()
            try { Os.chmod(absolutePath, 511) } catch (_: Exception) {}
        }
        File(distroRoot, "root").mkdirs()
    }
}
