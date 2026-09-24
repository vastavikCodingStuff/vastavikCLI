package com.shellzero.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.shellzero.updater.UpdateManager
import com.shellzero.updater.UpdateState
import kotlinx.coroutines.launch

private val DialogBg = Color(0xFF0B1120)
private val CardBg = Color(0xFF1E293B)
private val Emerald = Color(0xFF22C55E)
private val TextPrimary = Color(0xFFE2E8F0)
private val TextMuted = Color(0xFF94A3B8)

@Composable
fun UpdateDialog(
    latestVersion: String,
    currentVersion: String,
    releaseNotes: String,
    downloadUrl: String,
    apkName: String,
    onDismiss: () -> Unit,
    onUpdateNow: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updateState by UpdateManager.state.collectAsState()
    var isDownloading by remember { mutableStateOf(false) }

    // Ensure display versions have v prefix
    val displayLatest = if (latestVersion.startsWith("v")) latestVersion else "v$latestVersion"
    val displayCurrent = if (currentVersion.startsWith("v")) currentVersion else "v$currentVersion"

    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        containerColor = DialogBg,
        title = {
            Column {
                Text(
                    text = "Update Available",
                    color = Emerald,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "VASTAVIK CLI $displayCurrent → $displayLatest",
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Current vs New
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(CardBg)
                            .padding(12.dp)
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Current", color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        Text(displayCurrent, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Emerald.copy(alpha = 0.15f))
                            .padding(12.dp)
                            .weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("New", color = Emerald, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text(displayLatest, color = Emerald, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    }
                }

                // Release Notes
                Text(
                    text = "Release Notes",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(CardBg)
                        .padding(12.dp)
                ) {
                    Text(
                        text = releaseNotes.ifBlank { "No release notes." },
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp
                    )
                }

                // Download progress
                if (updateState is UpdateState.Downloading) {
                    val prog = (updateState as UpdateState.Downloading).progress
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Downloading... $prog%",
                            color = Emerald,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        LinearProgressIndicator(
                            progress = { prog / 100f },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = Emerald,
                            trackColor = CardBg
                        )
                    }
                }
                if (updateState is UpdateState.Error) {
                    Text(
                        text = "Error: ${(updateState as UpdateState.Error).message}",
                        color = Color(0xFFEF4444),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = {
            val isDownloadingState = updateState is UpdateState.Downloading
            Button(
                onClick = {
                    if (isDownloadingState) return@Button
                    isDownloading = true
                    scope.launch {
                        UpdateManager.downloadAndInstall(context, downloadUrl, apkName)
                        isDownloading = false
                        // onUpdateNow can be used to close dialog after launch
                        // Keep dialog open to show Downloaded state, but allow dismiss
                    }
                    onUpdateNow()
                },
                enabled = !isDownloadingState,
                colors = ButtonDefaults.buttonColors(containerColor = Emerald, contentColor = Color.White),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = if (isDownloadingState) "Downloading..." else "Update Now",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (updateState is UpdateState.Downloading) return@TextButton
                    onDismiss()
                }
            ) {
                Text("Dismiss", color = TextMuted, fontFamily = FontFamily.Monospace)
            }
        }
    )
}
