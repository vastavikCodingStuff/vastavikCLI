package com.shellzero.ui

import android.app.Activity
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shellzero.service.TerminalSessionService
import kotlinx.coroutines.launch

/**
 * VASTAVIK CLI - Dual-Sided Gesture Navigation
 * Spec §1 + §4 deliverable #1
 *
 * Implements simultaneous Left (Session Manager) and Right (ARM64 Distro Hub) drawers
 * with edge-swipe gestures and toggle buttons.
 *
 * Left drawer uses official ModalNavigationDrawer (swipe left-to-right).
 * Right drawer uses custom swipeable surface (swipe right-to-left) because
 * Compose's ModalNavigationDrawer only supports start edge.
 *
 * Gestures:
 *  - Left edge 24dp zone, drag > 40px opens left
 *  - Right edge 24dp zone, drag < -40px opens right
 *  - Scrim tap closes any open drawer
 *  - Drawer toggle icons also open/close
 */

private val ScrimColor = Color.Black.copy(alpha = 0.52f)
private val DrawerWidthLeft = 320.dp
private val DrawerWidthRight = 380.dp

@Composable
fun DualDrawerScaffold(
    leftDrawerContent: @Composable ColumnScope.() -> Unit,
    rightDrawerContent: @Composable ColumnScope.() -> Unit,
    leftDrawerState: DrawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
    rightDrawerOpen: Boolean = false,
    onRightDrawerChanged: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
    topBar: @Composable (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    var rightOpenInternal by remember { mutableStateOf(rightDrawerOpen) }
    LaunchedEffect(rightDrawerOpen) { rightOpenInternal = rightDrawerOpen }

    val rightProgress by animateFloatAsState(
        targetValue = if (rightOpenInternal) 1f else 0f,
        animationSpec = tween(durationMillis = 260),
        label = "rightDrawerProgress"
    )

    fun openRight() {
        rightOpenInternal = true
        onRightDrawerChanged(true)
    }
    fun closeRight() {
        rightOpenInternal = false
        onRightDrawerChanged(false)
    }

    val edgeWidthPx = with(density) { 24.dp.toPx() }
    var dragStartX by remember { mutableStateOf<Float?>(null) }

    ModalNavigationDrawer(
        drawerState = leftDrawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(0xFF0B1120),
                drawerContentColor = Color.White,
                modifier = Modifier.width(DrawerWidthLeft),
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                leftDrawerContent()
            }
        },
        scrimColor = if (rightOpenInternal) Color.Transparent else ScrimColor,
        content = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(edgeWidthPx, rightOpenInternal, leftDrawerState.isOpen) {
                        detectHorizontalDragGestures(
                            onDragStart = { offset -> dragStartX = offset.x },
                            onDragEnd = { dragStartX = null },
                            onHorizontalDrag = { change, _ ->
                                val startX = dragStartX ?: return@detectHorizontalDragGestures
                                val currentX = change.position.x
                                val totalDrag = currentX - startX
                                if (!rightOpenInternal && startX < edgeWidthPx && totalDrag > 40) {
                                    change.consume()
                                    scope.launch { leftDrawerState.open() }
                                    dragStartX = null
                                }
                                if (!leftDrawerState.isOpen && startX > size.width - edgeWidthPx && totalDrag < -40) {
                                    change.consume()
                                    openRight()
                                    dragStartX = null
                                }
                                if (rightOpenInternal && totalDrag > 50) {
                                    change.consume()
                                    closeRight()
                                    dragStartX = null
                                }
                            }
                        )
                    }
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    topBar?.invoke()
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        content()
                    }
                }

                // ── Right drawer scrim ──
                if (rightOpenInternal || rightProgress > 0.01f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ScrimColor.copy(alpha = ScrimColor.alpha * rightProgress))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { closeRight() }
                            )
                    )
                }

                // Right drawer sheet - slides from right
                val rightOffsetFraction = 1f - rightProgress
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(DrawerWidthRight)
                        .align(Alignment.TopEnd)
                        .offset(x = DrawerWidthRight * rightOffsetFraction)
                        .background(Color(0xFF0B1120))
                ) {
                    if (rightProgress > 0f) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF0B1120))
                        ) {
                            rightDrawerContent()
                        }
                    }
                }

                // Edge handle hints when closed
                if (!leftDrawerState.isOpen && !rightOpenInternal) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(3.dp)
                            .fillMaxHeight(0.28f)
                            .background(Color.White.copy(alpha = 0.04f))
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(3.dp)
                            .fillMaxHeight(0.28f)
                            .background(Color.White.copy(alpha = 0.04f))
                    )
                }
            }
        }
    )
}

/**
 * Convenience wrapper that wires SessionManager + Distro Hub and provides
 * the actual terminal content with top bar toggle icons.
 */
@Composable
fun VastavikDualDrawerScaffold(
    leftDrawerState: DrawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
    rightOpen: MutableState<Boolean> = remember { mutableStateOf(false) },
    onKeyboardToggle: () -> Unit = {},
    terminalContent: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()

    DualDrawerScaffold(
        leftDrawerState = leftDrawerState,
        rightDrawerOpen = rightOpen.value,
        onRightDrawerChanged = { rightOpen.value = it },
        leftDrawerContent = {
            SessionDrawerContent(
                onSessionSelected = {},
                onNewSession = {},
                onToggleKeyboard = onKeyboardToggle,
                onCloseDrawer = { scope.launch { leftDrawerState.close() } }
            )
        },
        rightDrawerContent = {
            DistroHubDrawerContent(
                onLaunchSession = {},
                onCloseDrawer = { rightOpen.value = false }
            )
        },
        topBar = {
            DualDrawerTopBar(
                onLeftToggle = { scope.launch { if (leftDrawerState.isOpen) leftDrawerState.close() else leftDrawerState.open() } },
                onRightToggle = { rightOpen.value = !rightOpen.value }
            )
        },
        content = terminalContent
    )
}

@Composable
private fun DualDrawerTopBar(
    onLeftToggle: () -> Unit,
    onRightToggle: () -> Unit
) {
    val context = LocalContext.current
    val updateState by com.shellzero.updater.UpdateManager.state.collectAsState()
    var showUpdateDialog by remember { mutableStateOf(false) }

    // Auto-check on launch per spec
    LaunchedEffect(Unit) {
        try { com.shellzero.updater.UpdateManager.checkForUpdate(context) } catch (_: Exception) {}
    }

    if (showUpdateDialog && updateState is com.shellzero.updater.UpdateState.UpdateAvailable) {
        val s = updateState as com.shellzero.updater.UpdateState.UpdateAvailable
        com.shellzero.ui.UpdateDialog(
            latestVersion = s.latestVersion,
            currentVersion = s.currentVersion,
            releaseNotes = s.releaseNotes,
            downloadUrl = s.downloadUrl,
            apkName = s.apkName,
            onDismiss = { showUpdateDialog = false; com.shellzero.updater.UpdateManager.reset() },
            onUpdateNow = { showUpdateDialog = false }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F172A))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = onLeftToggle, modifier = Modifier.size(36.dp)) {
            Text("☰", color = Color(0xFFE2E8F0), fontSize = 18.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "VASTAVIK CLI",
                color = Color(0xFFE2E8F0),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            Box(modifier = Modifier.size(6.dp).background(Color(0xFF22C55E)))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            // Green update pill per spec: vibrant #22C55E, Download icon, v<LATEST>
            if (updateState is com.shellzero.updater.UpdateState.UpdateAvailable) {
                val s = updateState as com.shellzero.updater.UpdateState.UpdateAvailable
                val pillVersion = s.latestVersion // already includes v
                Box(
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(Color(0xFF22C55E))
                        .clickable { showUpdateDialog = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "⬇",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                        // Use Text with download arrow fallback
                        Text(
                            text = if (pillVersion.startsWith("v")) pillVersion else "v$pillVersion",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        )
                    }
                }
            }
            IconButton(onClick = onRightToggle, modifier = Modifier.size(36.dp)) {
                Text("⬢", color = Color(0xFF38BDF8), fontSize = 16.sp)
            }
            IconButton(
                onClick = {
                    val exitIntent = Intent(context, TerminalSessionService::class.java).apply {
                        action = TerminalSessionService.ACTION_EXIT
                    }
                    context.startService(exitIntent)
                    (context as? Activity)?.finishAffinity()
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Exit",
                    tint = Color.Red
                )
            }
        }
    }
}

private operator fun androidx.compose.ui.unit.Dp.times(f: Float): androidx.compose.ui.unit.Dp = (value * f).dp
