# ShellZero — Standalone Native Android Terminal (Debian ARM64 + PRoot)

> Ultra-lightweight, unkillable, Termux-style terminal with embedded minimal Debian (Bookworm/Trixie `arm64-v8a`) and `apt`.

---

## Architecture at a glance

```
App (Kotlin + Jetpack Compose)
 ├─ TerminalScreen.kt (Compose + ExtraKeysBar)  -> 2-row sticky CTRL/ALT, VT100 sequences
 ├─ TerminalSession.kt + PtyProcess.kt           -> PTY via JNI openpty() or ProcessBuilder fallback
 ├─ DebianInstaller.kt                           -> extracts assets/bin/arm64-v8a/proot + debian-rootfs-arm64.tar.xz
 └─ TerminalSessionService.kt (ForegroundService)-> START_STICKY, onTaskRemoved(), WakeLock, notification
      └─ PRoot: $FILES_DIR/bin/proot -r $FILES_DIR/debian -0 -b /dev -b /proc ... /bin/bash --login
           └─ apt / dpkg works (resolv.conf pre-seeded with 1.1.1.1 / 8.8.8.8)
```

---

## Deliverables (spec §4) — file map

| Spec file | Actual path |
|---|---|
| `TerminalSessionService.kt` | `app/src/main/java/com/shellzero/service/TerminalSessionService.kt` |
| `DebianInstaller.kt` | `app/src/main/java/com/shellzero/installer/DebianInstaller.kt` |
| `TerminalSession.kt` & `PtyProcess.kt` | `app/src/main/java/com/shellzero/terminal/TerminalSession.kt`, `PtyProcess.kt` |
| `TerminalScreen.kt` | `app/src/main/java/com/shellzero/ui/TerminalScreen.kt` |
| `AndroidManifest.xml` additions | `app/src/main/AndroidManifest.xml` |
| Native PTY (supplement) | `app/src/main/cpp/pty.cpp` + `CMakeLists.txt` |
| Entry activity | `app/src/main/java/com/shellzero/MainActivity.kt` |

---

## 1) Embedded Minimal Debian ARM64

- `assets/bin/arm64-v8a/proot` — statically compiled ARM64 proot (~1.2 MB). Build from `termux/proot` with `CGO_ENABLED=0` or use `https://github.com/termux/proot/releases`.
- `assets/debian-rootfs-arm64.tar.xz` — minimal Debian slim (40–70 MB compressed) built via `debootstrap --variant=minbase --arch=arm64 bookworm` + strip (`apt` + `dpkg` + `coreutils` + `bash` + `glibc` + `tar` only, remove docs/locales).
- `DebianInstaller.installIfNeeded()` checks `$FILES_DIR/debian/bin/bash` and `bin/proot` existence, streams XZ+tar via `commons-compress`, does Zip-Slip protection, handles symlinks via `Os.symlink`, sets `chmod 0700` on proot and `0755` on bins, writes `etc/resolv.conf` with `1.1.1.1`/`8.8.8.8`.

Exact launch command (spec):

```bash
$FILES_DIR/bin/proot -r $FILES_DIR/debian -0 -b /dev -b /proc -b /sys -b /system -b $FILES_DIR/debian/tmp:/tmp -b $FILES_DIR/debian/dev/shm:/dev/shm -w /root /usr/bin/env -i HOME=/root TERM=xterm-256color LANG=C.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/bash --login
```

---

## 2) Unkillable Foreground Service

- `START_STICKY`, `onTaskRemoved()` re-launches service (swipe-away safe)
- `PARTIAL_WAKE_LOCK` with toggle PendingIntent (`Acquire/Release Wake Lock`)
- Channel `terminal_session_channel` `IMPORTANCE_LOW`, actions `Exit` (kills all `PtyProcess`) + `Toggle WakeLock`
- Must declare `FOREGROUND_SERVICE_SPECIAL_USE` + `property android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`

---

## 3) Extra Keys Bar

- Obsidian: bg `#080C14`, tile `#1E293B`, accent `#22C55E`/`#38BDF8`
- Row1: `ESC HOME ▲ END PGUP TAB` — Row2: `CTRL ALT ◀ ▼ ▶ PGDN`
- CTRL/ALT are sticky (tap to latch, next key sends `\u0003` etc., green/blue badge), arrows send `\u001b[A`…`\u001b[D`, long-press CTRL → `Ctrl+C`.
- When CTRL latched, extra row `^C ^D ^Z ^L ^\ ^]` appears.
- Bar uses `Modifier.imePadding()` + `navigationBars` so it docks above soft keyboard and stays at bottom when hidden.

---

## Quick start

1. Place assets:
   ```
   app/src/main/assets/bin/arm64-v8a/proot
   app/src/main/assets/debian-rootfs-arm64.tar.xz
   ```
2. `gradle assembleDebug` (NDK required for `libshellzero-pty.so`; if absent, `PtyProcess` falls back to `ProcessBuilder`)
3. On first launch, progress `LinearProgressIndicator` shows extraction; thereafter `TerminalSessionService.start()` keeps session alive.

---

## Notes

- PTY: prefer JNI `openpty()` (this repo includes `cpp/pty.cpp`); fallback `ProcessBuilder` cannot do true `TIOCSWINSZ` but sends `SIGWINCH`.
- For full VT100 fidelity, replace the simple `stripAnsi`+`LazyColumn` with `com.termux:terminal-emulator` (`TerminalEmulator`, `TerminalBuffer`) and render via `Canvas`.
- Monospace: `FontFamily.Monospace` 13sp, cursor `▉` in `AccentGreen`.
