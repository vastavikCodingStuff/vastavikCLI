package com.shellzero.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.*
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shellzero.terminal.SessionManager
import com.shellzero.terminal.SessionState

// ──────────────────────────────────────────────────────────────────────
// Theme constants - Obsidian Dark per spec
// ──────────────────────────────────────────────────────────────────────

private val DrawerSurface = Color(0xFF0B1120)   // outer surface
private val DrawerDivider = Color(0xFF1E293B)
private val CardBg = Color(0xFF1E293B)
private val CardBgActive = Color(0xFF0F172A)
private val NeonCyan = Color(0xFF38BDF8)
private val NeonGreen = Color(0xFF22C55E)
private val TextPrimary = Color(0xFFE2E8F0)
private val TextMuted = Color(0xFF94A3B8)
private val TextFaint = Color(0xFF64748B)

// ──────────────────────────────────────────────────────────────────────
// SessionDrawerContent - Left drawer (Termux-style)
// ──────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionDrawerContent(
    onSessionSelected: (SessionState) -> Unit = {},
    onNewSession: () -> Unit = {},
    onToggleKeyboard: () -> Unit = {},
    onCloseDrawer: () -> Unit = {}
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val sessions by SessionManager.sessions.collectAsState()
    val activeSession by SessionManager.activeSession.collectAsState()

    var renameTarget by remember { mutableStateOf<SessionState?>(null) }
    var killTarget by remember { mutableStateOf<SessionState?>(null) }
    var keyboardVisible by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(320.dp)
            .background(DrawerSurface)
            .padding(bottom = 8.dp)
    ) {
        // ── Header ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0B1120))
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ShellZero",
                        color = TextPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${sessions.size} session${if (sessions.size != 1) "s" else ""} active",
                        color = TextMuted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                // Close glyph (X)
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DrawerDivider)
                        .clickable { onCloseDrawer() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", color = TextMuted, fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Persistent KEYBOARD toggle button (spec)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        keyboardVisible = !keyboardVisible
                        if (keyboardVisible) keyboardController?.show() else keyboardController?.hide()
                        onToggleKeyboard()
                    },
                    modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (keyboardVisible) NeonCyan else DrawerDivider,
                        contentColor = if (keyboardVisible) Color.Black else TextPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text(
                        text = if (keyboardVisible) "KEYBOARD ON" else "KEYBOARD OFF",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                // Quick action: clear? optional
                OutlinedButton(
                    onClick = { /* could toggle extra keys */ },
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted),
                    border = BorderStroke(1.dp, DrawerDivider),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Text("⌨", fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            Divider(color = DrawerDivider, thickness = 1.dp)
        }

        // ── Session List ──
        if (sessions.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("No sessions", color = TextFaint, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Tap [+] NEW SESSION to start",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 10.dp)
            ) {
                items(sessions, key = { it.id }) { session ->
                    val isActive = activeSession?.id == session.id
                    SessionItem(
                        session = session,
                        isActive = isActive,
                        onClick = {
                            SessionManager.switchTo(context, session.id)
                            onSessionSelected(session)
                            onCloseDrawer()
                        },
                        onLongPress = {
                            // Open rename/kill chooser: for now, trigger rename dialog directly
                            // Long-press spec: quick dialog with "Rename Session" or "Kill Session"
                            renameTarget = session // we show unified dialog with both actions
                        }
                    )
                }
            }
        }

        // ── Divider + Footer ──
        Divider(color = DrawerDivider, thickness = 1.dp, modifier = Modifier.padding(horizontal = 12.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            // Prominent [+] NEW SESSION button
            Button(
                onClick = {
                    // Spawn new independent PTY shell session and switch active canvas to it
                    SessionManager.createNewSessionAndSwitch(context, "debian", "Debian")
                    onNewSession()
                    onCloseDrawer()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonGreen,
                    contentColor = Color.Black
                )
            ) {
                Text(
                    text = "[+]  NEW SESSION",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "Long-press a session to rename or kill",
                color = TextFaint,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }

    // ── Rename Dialog ──
    renameTarget?.let { target ->
        var newName by remember(target.id) { mutableStateOf(target.customName) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            containerColor = CardBg,
            title = {
                Text(
                    "Session ${target.index}",
                    color = TextPrimary,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Choose action for \"${target.displayName}\"",
                        color = TextMuted,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Custom name", color = TextMuted, fontSize = 11.sp) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = DrawerDivider,
                            cursorColor = NeonCyan
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    // Kill button inside dialog as per spec "Rename or Kill"
                    OutlinedButton(
                        onClick = {
                            killTarget = target
                            renameTarget = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.5f))
                    ) {
                        Text("Kill Session", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newName.isNotBlank() && newName != target.customName) {
                            SessionManager.renameSession(target.id, newName)
                        }
                        renameTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan, contentColor = Color.Black)
                ) {
                    Text("Rename", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) {
                    Text("Cancel", color = TextMuted, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
        )
    }

    // ── Kill Confirmation Dialog ──
    killTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { killTarget = null },
            containerColor = CardBg,
            title = { Text("Kill Session?", color = TextPrimary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Terminate \"${target.displayName}\" (index ${target.index})? This will kill its PTY process.",
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        SessionManager.killSession(target.id)
                        killTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444), contentColor = Color.White)
                ) {
                    Text("Kill", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { killTarget = null }) {
                    Text("Cancel", color = TextMuted, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionItem(
    session: SessionState,
    isActive: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val borderColor = if (isActive) NeonCyan else Color.Transparent
    val bg = if (isActive) CardBgActive else CardBg

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(
                width = if (isActive) 1.5.dp else 1.dp,
                color = if (isActive) borderColor else DrawerDivider.copy(alpha = 0.6f),
                shape = RoundedCornerShape(10.dp)
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            )
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                // Numbered circle: 1,2,3...
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isActive) NeonCyan else DrawerSurface)
                        .border(1.dp, if (isActive) NeonCyan else DrawerDivider, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "${session.index}",
                        color = if (isActive) Color.Black else TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.displayName,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (session.session.isRunning.value) NeonGreen else TextFaint)
                        )
                        Text(
                            text = "${session.distroName} • ${if (session.session.isRunning.value) "running" else "exited"} • ${session.id.takeLast(4)}",
                            color = TextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1
                        )
                    }
                }
            }

            // Active indicator dot
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(NeonGreen)
                )
            } else {
                Text("›", color = TextFaint, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
