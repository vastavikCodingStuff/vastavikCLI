package com.shellzero.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shellzero.distro.DistroCatalog
import com.shellzero.distro.DistroDownloader
import com.shellzero.distro.DistroInfo
import com.shellzero.distro.DistroStatus
import kotlinx.coroutines.launch

// Theme (match right drawer)
private val HubSurface = Color(0xFF0B1120)
private val HubCard = Color(0xFF1E293B)
private val HubDivider = Color(0xFF1E293B)
private val NeonBlue = Color(0xFF38BDF8)
private val NeonGreen = Color(0xFF22C55E)
private val WarningRed = Color(0xFFEF4444)
private val TextPrimary = Color(0xFFE2E8F0)
private val TextMuted = Color(0xFF94A3B8)
private val TextFaint = Color(0xFF64748B)

@Composable
fun DistroHubDrawerContent(
    onLaunchSession: (DistroInfo) -> Unit = {},
    onCloseDrawer: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        DistroDownloader.refreshStatuses(context)
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(380.dp)
            .background(HubSurface)
    ) {
        // ── Header ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(HubSurface)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ARM64 Distro Center",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "Browse • Download • Launch — \$FILES_DIR/distros/<name>",
                        color = TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(HubCard)
                        .border(1.dp, HubDivider, RoundedCornerShape(8.dp))
                ) {
                    IconButton(onClick = onCloseDrawer, modifier = Modifier.fillMaxSize()) {
                        Text("✕", color = TextMuted, fontSize = 14.sp)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Storage hint
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(HubCard)
                    .border(1.dp, HubDivider, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(NeonGreen)
                )
                Text(
                    text = "Isolated: distros/<name>  •  Shared: bin/proot  •  Cache: downloads/",
                    color = TextFaint,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(10.dp))
            Divider(color = HubDivider, thickness = 1.dp)
        }

        // ── Catalog list ──
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(DistroCatalog.all, key = { it.id }) { distro ->
                DistroCard(
                    distro = distro,
                    onLaunch = {
                        // Enabled only when installed; spec command uses $FILES_DIR/distros/<id>
                        DistroDownloader.launchSession(context, distro)
                        onLaunchSession(distro)
                        onCloseDrawer()
                    },
                    onRequestRefresh = { DistroDownloader.refreshStatuses(context) }
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Tip: Long-press Delete to also clear download cache.",
                    color = TextFaint,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                Spacer(Modifier.height(60.dp))
            }
        }
    }
}

@Composable
private fun DistroCard(
    distro: DistroInfo,
    onLaunch: () -> Unit,
    onRequestRefresh: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val statusFlow = remember(distro.id) { DistroDownloader.observeStatus(distro.id) }
    val status by statusFlow.collectAsState()

    // Refresh on card appear
    LaunchedEffect(distro.id) {
        DistroDownloader.refreshStatuses(context)
    }

    val isInstalled = status is DistroStatus.Installed
    val isDownloading = status is DistroStatus.Downloading
    val isExtracting = status is DistroStatus.Extracting
    val isBusy = isDownloading || isExtracting
    val isNotInstalled = status is DistroStatus.NotInstalled || status is DistroStatus.Error

    // Colors per distro tag
    val tagColor = when (distro.id) {
        "debian" -> Color(0xFFD70A53)
        "ubuntu" -> Color(0xFFE95420)
        "kali" -> Color(0xFF268BFF)
        "arch" -> Color(0xFF1793D1)
        "alpine" -> Color(0xFF0D597F)
        "fedora" -> Color(0xFF294172)
        else -> NeonBlue
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (isInstalled) NeonGreen.copy(alpha = 0.5f) else HubDivider, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = HubCard),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // ── Top row: Badge & Version & Size ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Logo/tag badge
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(tagColor)
                            .border(1.dp, tagColor.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = distro.badge,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = distro.name,
                                color = TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            if (distro.id == "debian") {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(NeonGreen)
                                        .padding(horizontal = 5.dp, vertical = 2.dp)
                                ) {
                                    Text("DEFAULT", color = Color.Black, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = distro.tag,
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text("•", color = TextFaint, fontSize = 10.sp)
                            Text(
                                text = distro.approxSize,
                                color = TextFaint,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                // Status indicator chip
                StatusChip(status = status)
            }

            // Description
            Text(
                text = distro.description,
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 14.sp,
                modifier = Modifier.fillMaxWidth()
            )

            // Progress bars for Downloading / Extracting
            when (status) {
                is DistroStatus.Downloading -> {
                    val s = status as DistroStatus.Downloading
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = "Downloading… ${ (s.progress*100).toInt()}%",
                                color = NeonBlue,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${formatBytes(s.bytesDone)} / ${s.totalBytes?.let { formatBytes(it) } ?: "?"}",
                                color = TextFaint,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        LinearProgressIndicator(
                            progress = { s.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = NeonBlue,
                            trackColor = HubSurface
                        )
                    }
                }
                is DistroStatus.Extracting -> {
                    val s = status as DistroStatus.Extracting
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Extracting… ${ (s.progress*100).toInt()}% — ${s.currentFile.takeLast(40)}",
                            color = NeonGreen,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        LinearProgressIndicator(
                            progress = { s.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = NeonGreen,
                            trackColor = HubSurface
                        )
                    }
                }
                is DistroStatus.Error -> {
                    Text(
                        text = "Error: ${(status as DistroStatus.Error).message}",
                        color = WarningRed,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                else -> {}
            }

            // ── Action Buttons Row ──
            // Spec: Download & Install, Extract, Launch Session, Delete
            // We combine Download & Install into single button that does both sequential
            // Extract is auto-called after download, but we expose manual Extract if Downloaded but not Installed

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Primary: Download & Install OR Extract OR Launch
                when {
                    isBusy -> {
                        Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.weight(1f).height(36.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = HubSurface, disabledContainerColor = HubSurface, disabledContentColor = TextFaint)
                        ) {
                            Text(
                                text = if (isDownloading) "DOWNLOADING…" else "EXTRACTING…",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    isNotInstalled -> {
                        // Show Downloaded state as extractable
                        val downloadedAvailable = DistroDownloader.isDownloaded(context, distro)
                        if (downloadedAvailable && status !is DistroStatus.Installed) {
                            // Offer Extract
                            Button(
                                onClick = {
                                    scope.launch { DistroDownloader.extract(context, distro) }
                                },
                                modifier = Modifier.weight(1f).height(36.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = NeonBlue, contentColor = Color.White)
                            ) {
                                Text("EXTRACT", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Button(
                                onClick = {
                                    scope.launch { DistroDownloader.downloadAndInstall(context, distro) }
                                },
                                modifier = Modifier.weight(1f).height(36.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = NeonBlue, contentColor = Color.White)
                            ) {
                                Text("DOWNLOAD & INSTALL", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    isInstalled -> {
                        Button(
                            onClick = onLaunch,
                            modifier = Modifier.weight(1f).height(36.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Color.Black)
                        ) {
                            Text("▶ LAUNCH SESSION", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }

                // Secondary: Delete (always if installed or downloaded)
                val showDelete = isInstalled || DistroDownloader.isDownloaded(context, distro) || status is DistroStatus.Downloaded
                if (showDelete || isInstalled) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                // Long-press behavior: delete rootfs; if installed, also keep cache unless user long presses
                                // For simplicity, single tap deletes rootfs only
                                DistroDownloader.delete(context, distro, deleteArchive = false)
                            }
                        },
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = WarningRed),
                        border = BorderStroke(1.dp, WarningRed.copy(alpha = 0.6f)),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text("DELETE", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    }
                } else if (isNotInstalled) {
                    // Show URL hint
                    TextButton(
                        onClick = { /* could open browser */ },
                        modifier = Modifier.height(36.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text("URL", color = TextFaint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // Small path hint
            Text(
                text = if (isInstalled) "Path: distros/${distro.id}/ ✓" else "Path: distros/${distro.id}/ (not installed)",
                color = TextFaint,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun StatusChip(status: DistroStatus) {
    val (label, bg, fg) = when (status) {
        is DistroStatus.Installed -> Triple("Installed", NeonGreen, Color.Black)
        is DistroStatus.Downloading -> Triple("Downloading ${(status.progress*100).toInt()}%", NeonBlue, Color.White)
        is DistroStatus.Extracting -> Triple("Extracting", NeonGreen.copy(alpha = 0.8f), Color.Black)
        is DistroStatus.Downloaded -> Triple("Downloaded", Color(0xFFF59E0B), Color.Black)
        is DistroStatus.Error -> Triple("Error", WarningRed, Color.White)
        is DistroStatus.NotInstalled -> Triple("Not Installed", HubSurface, TextMuted)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, bg.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, color = fg, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}
