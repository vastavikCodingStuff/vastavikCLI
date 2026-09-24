package com.shellzero.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * VASTAVIK CLI - Automated GitHub Release In-App Updater
 * Spec: queries GitHub Releases API, compares semver, shows green pill, downloads & installs via FileProvider.
 */

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class UpdateAvailable(
        val latestVersion: String,
        val currentVersion: String,
        val releaseNotes: String,
        val downloadUrl: String,
        val apkName: String
    ) : UpdateState()
    data class Downloading(val progress: Int) : UpdateState()
    object Downloaded : UpdateState()
    data class Error(val message: String) : UpdateState()
}

object UpdateManager {
    private const val TAG = "UpdateManager"
    private const val OWNER = "vastavikCodingStuff"
    private const val REPO = "vastavikCLI"
    private const val API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val USER_AGENT = "VASTAVIK-CLI-Agent/2.0"

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var latestDownloadFile: File? = null

    fun reset() {
        _state.value = UpdateState.Idle
    }

    /**
     * Automated Version Check on Launch (and pull-to-refresh / background coroutine)
     * Compares BuildConfig.VERSION_NAME with remote tag_name (strip v, semver).
     */
    suspend fun checkForUpdate(context: Context): UpdateState = withContext(Dispatchers.IO) {
        _state.value = UpdateState.Checking
        try {
            val url = URL(API_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", USER_AGENT)
                instanceFollowRedirects = true
                useCaches = false
            }
            // Follow redirects for GitHub API (301/302)
            HttpURLConnection.setFollowRedirects(true)
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                throw Exception("GitHub API $code: $err")
            }
            val jsonText = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val json = JSONObject(jsonText)
            val tagName = json.optString("tag_name", "")
            val body = json.optString("body", "")
            val assets = json.optJSONArray("assets")

            val latestVersionRaw = tagName.trim()
            val latestVersion = latestVersionRaw.removePrefix("v").removePrefix("V").trim()
            val currentVersionRaw = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
            } catch (_: Exception) { "0.0.0" }
            val currentVersion = currentVersionRaw.removePrefix("v").removePrefix("V").trim()

            Log.i(TAG, "Check: local $currentVersionRaw ($currentVersion) vs remote $tagName ($latestVersion)")

            if (latestVersion.isBlank() || currentVersion.isBlank()) {
                throw Exception("Empty version: local=$currentVersionRaw remote=$tagName")
            }

            val cmp = compareSemver(latestVersion, currentVersion)
            if (cmp <= 0) {
                // No update
                _state.value = UpdateState.Idle
                Log.i(TAG, "No update: local $currentVersion >= remote $latestVersion")
                return@withContext _state.value
            }

            // Find ARM64 APK in assets: matching *arm64*.apk or .apk
            var downloadUrl = ""
            var apkName = ""
            if (assets != null) {
                // Prefer arm64
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    val browserUrl = asset.optString("browser_download_url", "")
                    if (name.contains("arm64", ignoreCase = true) && name.endsWith(".apk", ignoreCase = true)) {
                        downloadUrl = browserUrl
                        apkName = name
                        break
                    }
                }
                // Fallback to any .apk
                if (downloadUrl.isBlank()) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        val browserUrl = asset.optString("browser_download_url", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = browserUrl
                            apkName = name
                            break
                        }
                    }
                }
            }
            if (downloadUrl.isBlank()) {
                throw Exception("No APK asset found in release $tagName")
            }

            val state = UpdateState.UpdateAvailable(
                latestVersion = latestVersionRaw, // keep original tag with v for display
                currentVersion = currentVersionRaw,
                releaseNotes = body.ifBlank { "No release notes." },
                downloadUrl = downloadUrl,
                apkName = apkName
            )
            _state.value = state
            Log.i(TAG, "Update available: $currentVersion -> $latestVersion")
            return@withContext state
        } catch (e: Exception) {
            Log.e(TAG, "checkForUpdate failed", e)
            val err = UpdateState.Error(e.message ?: "Check failed")
            _state.value = err
            return@withContext err
        }
    }

    /**
     * Download APK with live progress to getExternalFilesDir(DIRECTORY_DOWNLOADS)
     */
    suspend fun downloadApk(context: Context, downloadUrl: String, apkName: String): File? = withContext(Dispatchers.IO) {
        try {
            _state.value = UpdateState.Downloading(0)
            val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads").apply { mkdirs() }
            downloadsDir.mkdirs()
            val destFile = File(downloadsDir, apkName.ifBlank { "VASTAVIK-CLI-update.apk" })
            if (destFile.exists()) destFile.delete()

            val url = URL(downloadUrl)
            var conn: HttpURLConnection? = null
            // Follow redirects for CDN
            HttpURLConnection.setFollowRedirects(true)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/octet-stream")
            }
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                // Handle redirect manually if needed (GitHub releases redirect to CDN)
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    if (!loc.isNullOrBlank()) {
                        conn.disconnect()
                        return@withContext downloadApk(context, loc, apkName)
                    }
                }
                throw Exception("Download HTTP $code: ${conn.responseMessage}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 }
            val input = BufferedInputStream(conn.inputStream)
            val output = FileOutputStream(destFile)
            val buffer = ByteArray(32 * 1024)
            var bytesDone = 0L
            var lastProgress = -1
            input.use { ins ->
                output.use { outs ->
                    var read: Int
                    while (ins.read(buffer).also { read = it } != -1) {
                        outs.write(buffer, 0, read)
                        bytesDone += read
                        if (total != null && total > 0) {
                            val prog = ((bytesDone * 100) / total).toInt().coerceIn(0, 100)
                            if (prog != lastProgress) {
                                _state.value = UpdateState.Downloading(prog)
                                lastProgress = prog
                            }
                        } else {
                            // Indeterminate: emit based on bytes
                            val prog = (bytesDone % 50_000_000 * 100 / 50_000_000).toInt().coerceIn(0, 99)
                            if (prog != lastProgress) {
                                _state.value = UpdateState.Downloading(prog)
                                lastProgress = prog
                            }
                        }
                    }
                    outs.flush()
                }
            }
            conn.disconnect()
            _state.value = UpdateState.Downloading(100)
            latestDownloadFile = destFile
            _state.value = UpdateState.Downloaded
            Log.i(TAG, "Downloaded to ${destFile.absolutePath} bytes=$bytesDone")
            return@withContext destFile
        } catch (e: Exception) {
            Log.e(TAG, "downloadApk failed", e)
            _state.value = UpdateState.Error(e.message ?: "Download failed")
            return@withContext null
        }
    }

    /**
     * Trigger standard Android installer via FileProvider
     */
    fun installApk(context: Context, apkFile: File? = latestDownloadFile) {
        val file = apkFile ?: latestDownloadFile ?: run {
            _state.value = UpdateState.Error("No APK file")
            return
        }
        if (!file.exists()) {
            _state.value = UpdateState.Error("APK not found")
            return
        }
        try {
            val apkUri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(installIntent)
            Log.i(TAG, "Launched installer for ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "installApk failed", e)
            _state.value = UpdateState.Error("Install failed: ${e.message}")
        }
    }

    suspend fun downloadAndInstall(context: Context, downloadUrl: String, apkName: String) {
        val file = downloadApk(context, downloadUrl, apkName)
        if (file != null) {
            installApk(context, file)
        }
    }

    // Semver compare: returns >0 if a > b, <0 if a < b, 0 if equal
    internal fun compareSemver(a: String, b: String): Int {
        fun parse(v: String): List<Int> {
            // Strip any suffix like -beta, +build
            val clean = v.split("-", "+")[0]
            return clean.split(".").map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        }
        val pa = parse(a)
        val pb = parse(b)
        val maxLen = maxOf(pa.size, pb.size)
        for (i in 0 until maxLen) {
            val av = pa.getOrElse(i) { 0 }
            val bv = pb.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }
}
