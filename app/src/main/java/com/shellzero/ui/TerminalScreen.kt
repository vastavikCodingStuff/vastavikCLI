package com.shellzero.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shellzero.installer.DebianInstaller
import com.shellzero.terminal.SessionManager
import com.shellzero.terminal.SessionState
import com.shellzero.terminal.TerminalSession
import com.shellzero.terminal.TerminalSessionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// ──────────────────────────────────────────────────────────────────────
// Colors - Obsidian Dark Aesthetic (spec)
// ──────────────────────────────────────────────────────────────────────

private val ObsidianBg = Color(0xFF080C14)      // Background
private val SurfaceDark = Color(0xFF0F172A)
private val KeyTile = Color(0xFF1E293B)         // Key Tile
private val KeyTilePressed = Color(0xFF334155)
private val AccentGreen = Color(0xFF22C55E)     // Active/Latched
private val AccentBlue = Color(0xFF38BDF8)
private val TextPrimary = Color(0xFFE2E8F0)
private val TextMuted = Color(0xFF94A3B8)
private val TerminalBg = Color(0xFF080C14)
private val CursorColor = AccentGreen

// ──────────────────────────────────────────────────────────────────────
// ViewModel - bridges TerminalSession to Compose
// ──────────────────────────────────────────────────────────────────────

class TerminalViewModel : ViewModel() {
    private var session: TerminalSession? = null
    private var activeState: SessionState? = null
    private var displayJob: Job? = null
    private var activeObserverJob: Job? = null

    var displayText by mutableStateOf("")
        private set

    var isCtrlLatched by mutableStateOf(false)
        private set
    var isAltLatched by mutableStateOf(false)
        private set

    var isInstalling by mutableStateOf(false)
        private set
    var installProgress by mutableStateOf(0f)
        private set
    var installMessage by mutableStateOf("")
        private set

    // Expose active distro name for header
    var activeDistroName by mutableStateOf("Debian")
        private set
    var activeSessionName by mutableStateOf("Session 1")
        private set

    fun attachSession(s: TerminalSession) {
        session = s
        activeDistroName = (s.getDistroId().replaceFirstChar { it.uppercase() })
        activeSessionName = s.getSessionId()
        displayJob?.cancel()
        displayJob = viewModelScope.launch {
            s.displayText.collectLatest { text ->
                displayText = text
            }
        }
    }

    fun attachState(state: SessionState) {
        activeState = state
        session = state.terminalEmulator
        activeDistroName = state.distroName
        activeSessionName = state.displayName
        displayJob?.cancel()
        displayJob = viewModelScope.launch {
            state.terminalEmulator.displayText.collectLatest { text ->
                displayText = text
            }
        }
    }

    fun ensureSession(context: android.content.Context) {
        if (session != null && activeState != null) return
        // Prefer new multi-session engine
        val state = SessionManager.getOrCreateDefault(context)
        attachState(state)
        observeActiveSessions()
    }

    fun observeActiveSessions() {
        if (activeObserverJob != null) return
        activeObserverJob = viewModelScope.launch {
            SessionManager.activeSession.collectLatest { state ->
                if (state != null) {
                    attachState(state)
                } else {
                    // No active session; try to ensure one exists? keep old display
                }
            }
        }
    }

    fun setInstalling(active: Boolean, progress: Float = 0f, msg: String = "") {
        isInstalling = active
        installProgress = progress
        installMessage = msg
    }

    // ── Input handling with sticky modifiers ──

    fun toggleCtrl() { isCtrlLatched = !isCtrlLatched }
    fun toggleAlt() { isAltLatched = !isAltLatched }

    fun sendInput(text: String) {
        if (text.isEmpty()) return
        // Apply latching modifiers to single char input (CTRL / ALT + key)
        // If latch is active, transform next key and auto-release (sticky behavior)
        val processed = when {
            isCtrlLatched && text.length == 1 -> {
                val c = text[0]
                // CTRL + <letter> -> 0x01-0x1A, case-insensitive
                // Also handle special mappings
                val ctrlByte = when (c.lowercaseChar()) {
                    in 'a'..'z' -> (c.lowercaseChar() - 'a' + 1).toChar()
                    else -> null
                }
                // Release latch after one use (sticky)
                isCtrlLatched = false
                if (ctrlByte != null) ctrlByte.toString() else text
            }
            isAltLatched && text.length == 1 -> {
                isAltLatched = false
                // ALT sends ESC prefix (Meta)
                "\u001b$text"
            }
            else -> text
        }
        session?.write(processed)
        // If we still have latch but input was multi-char (like pasted), keep latch?
        // Spec says next pressed key transmits mapped byte, so we handle only single char above.
    }

    fun sendRawBytes(bytes: String) {
        // For ESC, arrows, etc., bypass latching except CTRL
        // If CTRL is latched and we send a special key, combine?
        if (isCtrlLatched) {
            // Example: CTRL+C already handled as \u0003 directly from ExtraKeys
            isCtrlLatched = false
        }
        if (isAltLatched) {
            session?.write("\u001b$bytes")
            isAltLatched = false
        } else {
            session?.write(bytes)
        }
    }

    fun sendControlChar(ctrlChar: Char) {
        // Direct control bytes: e.g., \u0003 for CTRL+C
        isCtrlLatched = false
        isAltLatched = false
        session?.write(ctrlChar.toString())
    }

    fun sendKeyCodeSpec(label: String) {
        // Maps extra key bar labels to bytes/sequences per spec
        when (label) {
            "ESC" -> {
                if (isCtrlLatched || isAltLatched) {
                    // CTRL+ESC or ALT+ESC? Just send ESC
                    isCtrlLatched = false; isAltLatched = false
                }
                session?.write("\u001b")
            }
            "TAB" -> session?.write("\t")
            "HOME" -> session?.write("\u001b[H") // or \u001b[1~
            "END" -> session?.write("\u001b[F")  // or \u001b[4~
            "PGUP" -> session?.write("\u001b[5~")
            "PGDN" -> session?.write("\u001b[6~")
            "▲" -> session?.write("\u001b[A")
            "▼" -> session?.write("\u001b[B")
            "▶" -> session?.write("\u001b[C")
            "◀" -> session?.write("\u001b[D")
            else -> sendInput(label)
        }
        // Auto-release after non-modifier key if latch was on? Already handled?
        // For navigation keys with CTRL held, e.g., CTRL+Up, we could combine - not needed per spec
    }

    fun resize(rows: Int, cols: Int) {
        session?.resizePty(rows, cols)
    }

    fun clear() {
        session?.clearBuffer()
    }

    override fun onCleared() {
        // Don't destroy session; it lives in foreground service
        super.onCleared()
    }
}

// ──────────────────────────────────────────────────────────────────────
// TerminalScreen - Jetpack Compose
// ──────────────────────────────────────────────────────────────────────

@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel = viewModel(),
    context: android.content.Context = androidx.compose.ui.platform.LocalContext.current
) {
    var textFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom when new output arrives
    LaunchedEffect(viewModel.displayText) {
        // Scroll after composition
        scope.launch {
            // Small delay to let LazyColumn recompose
            delay(30)
            if (listState.layoutInfo.totalItemsCount > 0) {
                listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
            }
        }
    }

    // Ensure session
    LaunchedEffect(Unit) {
        viewModel.ensureSession(context)
    }

    // Estimate rows/cols from screen size - naive but functional
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    LaunchedEffect(config.screenWidthDp, config.screenHeightDp) {
        // Approx cols = width / charWidth (8dp at 13sp), rows = height / lineHeight (18dp)
        val cols = (config.screenWidthDp / 8).coerceIn(20, 240)
        val rows = (config.screenHeightDp / 18).coerceIn(10, 100)
        viewModel.resize(rows, cols)
    }

    Scaffold(
        containerColor = ObsidianBg,
        bottomBar = {
            ExtraKeysBar(
                isCtrlLatched = viewModel.isCtrlLatched,
                isAltLatched = viewModel.isAltLatched,
                onCtrlToggle = { viewModel.toggleCtrl() },
                onAltToggle = { viewModel.toggleAlt() },
                onKeyPress = { label -> viewModel.sendKeyCodeSpec(label) },
                onControlByte = { byte -> viewModel.sendControlChar(byte) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(ObsidianBg)
        ) {
            // Top bar - mimics terminal header (shows active distro/session from SessionManager)
            TerminalHeader(
                isCtrl = viewModel.isCtrlLatched,
                isAlt = viewModel.isAltLatched,
                distroName = viewModel.activeDistroName,
                sessionName = viewModel.activeSessionName,
                onClear = { viewModel.clear() }
            )

            // Installation overlay
            if (viewModel.isInstalling) {
                InstallProgress(
                    progress = viewModel.installProgress,
                    message = viewModel.installMessage
                )
            }

            // Terminal canvas
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(TerminalBg)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                // New modern approach: render buffer as scrollable text with monospace
                // For full VT100 fidelity, replace with `TerminalView` using terminal-emulator lib
                // Here we provide performant scroll buffer with selectable text

                // Hidden input field to capture soft keyboard input
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        // Diff to find what user typed
                        val old = textFieldValue.text
                        val new = newValue.text
                        if (new.length > old.length) {
                            val inserted = new.substring(old.length)
                            // Handle enter, backspace, etc.
                            // BasicTextField already handles composition; we forward
                            viewModel.sendInput(inserted)
                        } else if (new.length < old.length) {
                            // Backspace
                            viewModel.sendRawBytes("\u007f") // DEL
                        }
                        // Reset to empty to keep field ready for next input (like Termux)
                        textFieldValue = TextFieldValue("")
                    },
                    textStyle = TextStyle(
                        color = Color.Transparent,
                        fontSize = 1.sp
                    ),
                    cursorBrush = SolidColor(Color.Transparent),
                    keyboardOptions = KeyboardOptions.Default,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp) // invisible but focusable
                        .onPreviewKeyEvent { event ->
                            // Intercept hardware keys, DPAD, etc.
                            if (event.type == KeyEventType.KeyDown) {
                                when (event.key) {
                                    Key.DirectionUp -> { viewModel.sendKeyCodeSpec("▲"); true }
                                    Key.DirectionDown -> { viewModel.sendKeyCodeSpec("▼"); true }
                                    Key.DirectionLeft -> { viewModel.sendKeyCodeSpec("◀"); true }
                                    Key.DirectionRight -> { viewModel.sendKeyCodeSpec("▶"); true }
                                    Key.Enter, Key.NumPadEnter -> { viewModel.sendRawBytes("\r"); true }
                                    Key.Backspace -> { viewModel.sendRawBytes("\u007f"); true }
                                    Key.Tab -> { viewModel.sendKeyCodeSpec("TAB"); true }
                                    Key.Escape -> { viewModel.sendKeyCodeSpec("ESC"); true }
                                    else -> false
                                }
                            } else false
                        }
                )

                // Visible buffer
                // Split displayText into lines for LazyColumn performance
                val lines = remember(viewModel.displayText) {
                    viewModel.displayText.split("\n")
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { /* focus hidden field - need focus requester */ },
                    verticalArrangement = Arrangement.Bottom
                ) {
                    items(lines.size) { idx ->
                        val line = lines[idx]
                        // Simple ANSI stripping for now - replace with real parser via `terminal-emulator`
                        val clean = stripAnsi(line)
                        androidx.compose.material3.Text(
                            text = clean.ifEmpty { " " },
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 17.sp,
                            softWrap = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    // Cursor line
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Text(
                                text = "▉",
                                color = CursorColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }

                // Tap to show keyboard - placeholder
                // In real app, use LocalSoftwareKeyboardController + FocusRequester
            }
        }
    }
}

@Composable
private fun TerminalHeader(
    isCtrl: Boolean,
    isAlt: Boolean,
    distroName: String = "Debian",
    sessionName: String = "Session 1",
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(AccentGreen)
            )
            androidx.compose.material3.Text(
                text = "ShellZero • $distroName arm64 • $sessionName",
                color = TextPrimary,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
            if (isCtrl) Badge("CTRL", AccentGreen)
            if (isAlt) Badge("ALT", AccentBlue)
        }
        TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
            androidx.compose.material3.Text("Clear", color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Badge(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.2f))
            .border(1.dp, color, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        androidx.compose.material3.Text(label, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun InstallProgress(progress: Float, message: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceDark)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.compose.material3.Text(
            "Installing Debian (ARM64)...",
            color = TextPrimary,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = AccentGreen,
            trackColor = KeyTile
        )
        Spacer(Modifier.height(6.dp))
        androidx.compose.material3.Text(
            message,
            color = TextMuted,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
    }
}

// ──────────────────────────────────────────────────────────────────────
// Extra Keys Bar - Termux-style two rows, pinned above soft keyboard
// ──────────────────────────────────────────────────────────────────────

@Composable
fun ExtraKeysBar(
    isCtrlLatched: Boolean,
    isAltLatched: Boolean,
    onCtrlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onKeyPress: (String) -> Unit,
    onControlByte: (Char) -> Unit
) {
    // Pinned bottom bar; when soft keyboard is hidden it stays at bottom.
    // When keyboard shows, WindowInsets.ime ensures it docks above keyboard if using
    // Modifier.imePadding() in Scaffold. Here we use navigationBars + ime padding.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ObsidianBg)
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding(), // keeps bar above soft keyboard
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Row 1: ESC HOME ▲ END PGUP TAB
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ExtraKeyButton("ESC", weight = 1f, isLatched = false, onClick = { onKeyPress("ESC") })
            ExtraKeyButton("HOME", weight = 1f, isLatched = false, onClick = { onKeyPress("HOME") })
            ExtraKeyButton("▲", weight = 1f, isLatched = false, onClick = { onKeyPress("▲") })
            ExtraKeyButton("END", weight = 1f, isLatched = false, onClick = { onKeyPress("END") })
            ExtraKeyButton("PGUP", weight = 1f, isLatched = false, onClick = { onKeyPress("PGUP") })
            ExtraKeyButton("TAB", weight = 1f, isLatched = false, onClick = { onKeyPress("TAB") })
        }
        // Row 2: CTRL ALT ◀ ▼ ▶ PGDN
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // CTRL sticky
            ExtraKeyButton(
                label = "CTRL",
                weight = 1f,
                isLatched = isCtrlLatched,
                accent = AccentGreen,
                onClick = { onCtrlToggle() },
                onLongPress = { onControlByte('\u0003') } // long-press CTRL sends Ctrl+C (SIGINT) like Termux
            )
            ExtraKeyButton(
                label = "ALT",
                weight = 1f,
                isLatched = isAltLatched,
                accent = AccentBlue,
                onClick = { onAltToggle() }
            )
            ExtraKeyButton("◀", weight = 1f, isLatched = false, onClick = { onKeyPress("◀") })
            ExtraKeyButton("▼", weight = 1f, isLatched = false, onClick = { onKeyPress("▼") })
            ExtraKeyButton("▶", weight = 1f, isLatched = false, onClick = { onKeyPress("▶") })
            ExtraKeyButton("PGDN", weight = 1f, isLatched = false, onClick = { onKeyPress("PGDN") })
        }

        // Optional third row hint: quick CTRL combos (C, D, Z, L) when CTRL latched
        if (isCtrlLatched) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // When CTRL is latched, show shortcuts bar - tapping sends control byte
                // CTRL+C \u0003, CTRL+D \u0004, CTRL+Z \u001a, CTRL+L \u000c
                CtrlShortcutButton("C", "\u0003", AccentGreen, onControlByte)
                CtrlShortcutButton("D", "\u0004", AccentGreen, onControlByte)
                CtrlShortcutButton("Z", "\u001a", AccentGreen, onControlByte)
                CtrlShortcutButton("L", "\u000c", AccentGreen, onControlByte)
                CtrlShortcutButton("\\", "\u001c", AccentGreen, onControlByte)
                CtrlShortcutButton("]", "\u001d", AccentGreen, onControlByte)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowScope.ExtraKeyButton(
    label: String,
    weight: Float,
    isLatched: Boolean,
    accent: Color = AccentGreen,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null
) {
    val bg by animateColorAsState(
        targetValue = if (isLatched) accent else KeyTile,
        label = "keyBg"
    )
    val fg = if (isLatched) Color.Black else TextPrimary
    val border = if (isLatched) accent else Color.Transparent

    Box(
        modifier = Modifier
            .weight(weight)
            .height(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            ),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Text(
            text = label,
            color = fg,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1
        )
        if (isLatched) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color.White.copy(alpha = 0.9f))
            )
        }
    }
}

@Composable
private fun RowScope.CtrlShortcutButton(
    label: String,
    ctrlByte: String,
    accent: Color,
    onControlByte: (Char) -> Unit
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(KeyTilePressed)
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .clickable { onControlByte(ctrlByte[0]) },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Text(
            text = "^$label",
            color = accent,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ──────────────────────────────────────────────────────────────────────
// Helpers
// ──────────────────────────────────────────────────────────────────────

private fun stripAnsi(input: String): String {
    // Basic CSI strip: \u001b[...m and \u001b[...~ etc. For full fidelity use a parser.
    return input.replace(Regex("\u001B\\[[0-9;?]*[a-zA-Z]"), "")
        .replace(Regex("\u001B\\][^\\u0007]*\\u0007"), "")
        .replace("\r", "")
}

// ──────────────────────────────────────────────────────────────────────
// Preview / Entry Composable for MainActivity
// ──────────────────────────────────────────────────────────────────────

@Composable
fun ShellZeroApp(
    viewModel: TerminalViewModel = viewModel()
) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = ObsidianBg,
            surface = SurfaceDark,
            primary = AccentGreen
        )
    ) {
        // Dual-drawer scaffold: left = SessionManager, right = ARM64 Distro Hub
        // Gestures: swipe left-to-right for sessions, right-to-left for distros
        val leftState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val rightOpen = remember { mutableStateOf(false) }

        ShellZeroDualDrawerScaffold(
            leftDrawerState = leftState,
            rightOpen = rightOpen,
            onKeyboardToggle = {},
            terminalContent = {
                TerminalScreen(viewModel = viewModel)
            }
        )
    }
}

/**
 * Standalone entry without drawers (for preview / fallback).
 */
@Composable
fun ShellZeroAppWithoutDrawers() {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = ObsidianBg,
            surface = SurfaceDark,
            primary = AccentGreen
        )
    ) {
        TerminalScreen()
    }
}
