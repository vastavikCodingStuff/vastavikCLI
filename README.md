# VastavikCLI — VASTAVIK CLI

> **Standalone Native Android Terminal (ARM64) with Embedded Debian + PRoot — Unkillable, Termux-style, Multi-Session & ARM64 Distro Hub.**

[![Android](https://img.shields.io/badge/Android-arm64--v8a%20only-brightgreen)](app/build.gradle.kts)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-blue)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-4285F4)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-MIT-lightgrey)](LICENSE)

This repository implements **VASTAVIK CLI**, a high-performance Android terminal emulator in **Kotlin + Jetpack Compose** targeting **exclusively `arm64-v8a` (`aarch64`)** with an embedded minimal **Debian ARM64** subsystem, **PRoot** rootless execution, **unkillable foreground service**, **Termux-style Extra Keys**, **dual-drawer navigation**, **persistent multi-session engine**, and a dedicated **ARM64 Distro Hub** for 6 isolated Linux distributions.

---

## Architecture at a Glance

```
App (Kotlin + Jetpack Compose, arm64-v8a only)
 ├─ ui/TerminalScreen.kt + ExtraKeysBar          → monospace canvas, 2-row sticky CTRL/ALT, VT100 sequences, imePadding
 ├─ ui/DualDrawerScaffold.kt                     → left ModalNavigationDrawer + right custom swipeable (edge 24dp gestures)
 ├─ ui/SessionDrawerContent.kt (Left)            → obsidian #0B1120, numbered sessions, neon active border, Rename/Kill, [+] NEW SESSION
 ├─ ui/DistroHubDrawerContent.kt (Right)         → ARM64 Distro Center: 6 cards, progress, Download/Extract/Launch/Delete
 ├─ terminal/SessionManager.kt + SessionState    → synchronized multi-session engine (StateFlow, ReentrantLock), active switch without kill
 ├─ terminal/TerminalSession.kt + PtyProcess.kt  → JNI openpty/fork/exec + ProcessBuilder fallback, TIOCSWINSZ resize, per-distro PRoot
 ├─ distro/DistroDownloader.kt + DistroCatalog   → streaming HTTP → $FILES_DIR/downloads/ → distros/<id>/, XZ/Gz, SHA, resolv.conf
 ├─ installer/DebianInstaller.kt                 → extracts assets/bin/arm64-v8a/proot + debian-rootfs-arm64.tar.xz → $FILES_DIR/debian
 ├─ service/TerminalSessionService.kt            → START_STICKY, onTaskRemoved() resilient, PARTIAL_WAKE_LOCK, terminal_session_channel
 └─ cpp/pty.cpp (libVASTAVIK CLI-pty.so)            → native PTY, ioctl(TIOCSWINSZ), SIGWINCH
      └─ PRoot: $FILES_DIR/bin/proot -r $FILES_DIR/distros/<id> -0 -b /dev -b /proc ... /bin/bash --login
           └─ apt/dpkg works (resolv.conf 1.1.1.1/8.8.8.8 pre-seeded)
```

---

## Features

### 1. Embedded Minimal Debian ARM64 Subsystem
- **Target:** exclusively `arm64-v8a`/`aarch64`, **Debian Slim** (Bookworm/Trixie, ~40–70 MB compressed, `coreutils`+`bash`+`dpkg`+`apt`+`tar`+`glibc`).
- **Rootless via PRoot:** statically compiled `assets/bin/arm64-v8a/proot` extracted to `$FILES_DIR/bin/proot` on first launch (`File.setExecutable(true)` + `Os.chmod 0700`).
- **First-launch extraction:** if `$FILES_DIR/debian` missing, shows progress while streaming `debian-rootfs-arm64.tar.xz` via `TarArchiveInputStream` + `XZCompressorInputStream` (Zip-Slip protected, `Os.symlink` handling).
- **Launch command:**
  ```bash
  $FILES_DIR/bin/proot -r $FILES_DIR/debian -0 -b /dev -b /proc -b /sys -b /system -b $FILES_DIR/debian/tmp:/tmp -b $FILES_DIR/debian/dev/shm:/dev/shm -w /root /usr/bin/env -i HOME=/root TERM=xterm-256color LANG=C.UTF-8 PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/bash --login
  ```
- **apt ready:** DNS pre-configured in `$FILES_DIR/debian/etc/resolv.conf` (`1.1.1.1`, `8.8.8.8`, `9.9.9.9`).

### 2. Unkillable Foreground Service (Termux Lifecycle Model)
- `TerminalSessionService.kt` as persistent high-priority `ForegroundService` with `START_STICKY` and `onTaskRemoved()` that **does NOT kill** the session when swiped from recents (re-launches via `startForegroundService`).
- Holds `PowerManager.PARTIAL_WAKE_LOCK` (10h timeout safety, toggleable) to prevent deep sleep.
- Ongoing notification `terminal_session_channel` (`IMPORTANCE_LOW`, persistent): `VASTAVIK CLI (N sessions active)` with **Exit** (terminates all PTYs + service) and **Acquire/Release Wake Lock** dynamic toggle. Badge updates via `SessionManager.sessionCount` + `ACTION_UPDATE_BADGE`.

### 3. Termux-Style Extra Keys Bar (Virtual Toolbar)
- **Pinned above soft keyboard** (`Modifier.imePadding()` + `navigationBars`), stays at bottom when keyboard hidden.
- **Obsidian dark:** background `#080C14`, tile `#1E293B`, pressed `#334155`, active `#22C55E`/`#38BDF8`.
- **Rows:** Row1 `ESC HOME ▲ END PGUP TAB` — Row2 `CTRL ALT ◀ ▼ ▶ PGDN`; when `CTRL` latched, extra `^C ^D ^Z ^L ^\ ^]` shortcuts appear.
- **Sticky modifiers:** `CTRL`/`ALT` latch with neon badge + dot; next key sends control byte (`CTRL+C` → `\u0003`, `CTRL+D` → `\u0004`, etc.), arrows emit `\u001b[A`…`[D`, `ESC` → `\u001b`.

### 4. Dual-Sided Gesture Navigation (Left & Right Drawers)
- **Left Drawer (Session Manager, swipe L→R or ☰):** obsidian `#0B1120` surface, `#1E293B` dividers, header `VASTAVIK CLI` + active count + `KEYBOARD` toggle, numbered sessions (`1,2,3…`) with custom names (`Debian (sh)` default), neon cyan/green active border, long-press → **Rename Session** / **Kill Session** dialogs, footer `[+] NEW SESSION` spawns independent PTY and switches canvas.
- **Right Drawer (ARM64 Distro Hub, swipe R→L or ⬢):** title `ARM64 Distro Center`, 6 isolated distros in `$FILES_DIR/distros/<name>` (shared `bin/proot`), cards show badge/version/size/status (`Installed`/`Not Installed`/`Downloading XX%`), actions `Download & Install` (streaming HTTP → `downloads/` with progress) → `Extract` → `Launch Session` (PRoot isolated) → `Delete`. Edge indicators + scrim tap-to-close; implemented via `ModalNavigationDrawer` (left) + custom swipeable surface (right) in `DualDrawerScaffold.kt`.

### 5. Multi-Session Engine Architecture (`SessionManager.kt`)
```kotlin
data class SessionState(
    val id: String, val index: Int, var customName: String,
    val distroName: String, val distroId: String,
    val ptyProcess: PtyProcess?, val terminalEmulator: TerminalEmulator // = TerminalSession
)
```
- Synchronized `ReentrantLock` + `StateFlow<List<SessionState>>` + `StateFlow<SessionState?> activeSession`.
- `createSession(distroId, distroName)` / `createNewSessionAndSwitch()` / `switchTo(id)` without terminating background PTYs / `renameSession` / `killSession` / `closeSession` on `exit 0` via `TerminalSession.setOnExitListener`.
- All sessions run under the same unkillable service; `TerminalViewModel` observes `activeSession` and re-binds `displayText` flow, handles `resizePty(rows,cols)` on config change.
- Legacy `TerminalSessionManager` delegates to `SessionManager` for backward compatibility.

### 6. ARM64 Distro Hub (`DistroDownloader.kt` + `DistroCatalog`)
| Distro | Version | Size | Archive | Description |
|---|---|---|---|---|
| **Debian Minimal (Default)** | Trixie/Bookworm | ~48 MB | `debian-rootfs-arm64.tar.xz` | Rock-solid, stripped, `apt` pre-configured |
| **Ubuntu Minimal** | 24.04 LTS | ~52 MB | `ubuntu-base-24.04-base-arm64.tar.gz` | Official Ubuntu base |
| **Kali Linux** | 2024.4 | ~68 MB | `kali-nethunter-rootfs-nano-arm64.tar.xz` | NetHunter core, pentest repos |
| **Arch Linux ARM** | 2024.12 | ~58 MB | `ArchLinuxARM-aarch64-latest.tar.gz` | Rolling, `pacman` |
| **Alpine Linux** | 3.20 | ~5 MB | `alpine-minirootfs-3.20.0-aarch64.tar.gz` | Ultra-small musl/busybox |
| **Fedora Minimal** | 40 | ~62 MB | `Fedora-Container-Base-40.aarch64.tar.xz` | DNF-ready cloud-base |

- **Workflow per card:** `Installed`/`Not Installed`/`Downloading (XX%)`/`Extracting` chip + `Download & Install` (streaming `HttpURLConnection` 32KB buffer, `Downloading(progress,bytesDone,total)`) → `Extract` (`XZ`/`Gzip` → `TarArchiveInputStream`, Zip-Slip check, `Os.symlink`, `chmod`, `resolv.conf`) → `Launch Session` (PRoot `-r $FILES_DIR/distros/<id>`) → `Delete` (removes `distros/<id>/`, optional cache).
- SHA256 integrity check supported when `sha256` provided in catalog.

---

## Project Structure — Deliverables

| Spec File | Actual Path | Notes |
|---|---|---|
| `TerminalSessionService.kt` | `app/src/main/java/com/VASTAVIK CLI/service/TerminalSessionService.kt` | `START_STICKY`, `onTaskRemoved`, WakeLock, `terminal_session_channel` |
| `DebianInstaller.kt` | `app/src/main/java/com/VASTAVIK CLI/installer/DebianInstaller.kt` | asset extract, `resolv.conf`, `chmod` |
| `TerminalSession.kt` & `PtyProcess.kt` | `app/src/main/java/com/VASTAVIK CLI/terminal/` | distro-aware `TerminalSession(distroId,distroRoot)`, JNI PTY |
| `TerminalScreen.kt` | `app/src/main/java/com/VASTAVIK CLI/ui/TerminalScreen.kt` | Compose canvas, `TerminalViewModel`, `ExtraKeysBar` |
| `AndroidManifest.xml` additions | `app/src/main/AndroidManifest.xml` | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `WAKE_LOCK`, `POST_NOTIFICATIONS` |
| `DualDrawerScaffold.kt` | `app/src/main/java/com/VASTAVIK CLI/ui/DualDrawerScaffold.kt` | left `ModalNavigationDrawer` + right custom swipeable |
| `SessionDrawerContent.kt` | `app/src/main/java/com/VASTAVIK CLI/ui/SessionDrawerContent.kt` | left drawer UI, rename/kill, `[+] NEW SESSION` |
| `DistroHubDrawerContent.kt` | `app/src/main/java/com/VASTAVIK CLI/ui/DistroHubDrawerContent.kt` | right drawer, 6 cards, progress, launch/delete |
| `SessionManager.kt` | `app/src/main/java/com/VASTAVIK CLI/terminal/SessionManager.kt` | `SessionState`, synchronized flows |
| `DistroDownloader.kt` | `app/src/main/java/com/VASTAVIK CLI/distro/DistroDownloader.kt` | catalog, download/extract/launch |
| `MainActivity.kt` | `app/src/main/java/com/VASTAVIK CLI/MainActivity.kt` | permission, `DebianInstaller`, `SessionManager`, `VASTAVIK CLIApp` |
| Native PTY | `app/src/main/cpp/pty.cpp` + `CMakeLists.txt` | `openpty`, `TIOCSWINSZ`, `SIGWINCH` |
| Governance | `.agents.md` | Branching, verified commits, PR, squash-merge, release flow |
| Build | `app/build.gradle.kts` | `abiFilters arm64-v8a`, `versionCode 2`, `versionName 1.1.0` |

---

## Quick Start

1. **Prerequisites:** Android Studio Hedgehog+, JDK 17, NDK (for `libVASTAVIK CLI-pty.so`), Android SDK `compileSdk 34`.
2. **Assets (not committed due to size):**
   ```
   app/src/main/assets/bin/arm64-v8a/proot                 # ~1.2 MB static aarch64
   app/src/main/assets/debian-rootfs-arm64.tar.xz           # ~48 MB Debian slim
   # Optional additional distros will be downloaded on-demand to $FILES_DIR/downloads/
   ```
   Build `proot` from `termux/proot` or use `https://github.com/termux/proot/releases`. Build Debian rootfs via `debootstrap --variant=minbase --arch=arm64 bookworm`.

3. **Build:**
   ```bash
   ./gradlew assembleDebug              # arm64-v8a debug APK
   ./gradlew assembleRelease            # arm64-v8a release → app/build/outputs/apk/release/app-arm64-v8a-release.apk
   unzip -l app/build/outputs/apk/release/app-arm64-v8a-release.apk | grep lib/  # verify only lib/arm64-v8a/
   ```

4. **First launch:** `LinearProgressIndicator` streams extraction, writes `etc/resolv.conf`, then `SessionManager.createSession("debian","Debian")` → PRoot → `bash --login`. Open left drawer (☰) for sessions, right drawer (⬢) for distro hub.

5. **Try:**
   ```bash
   apt update && apt upgrade
   apt install neofetch && neofetch
   # New session: left drawer → [+] NEW SESSION
   # Try Alpine: right drawer → Alpine → Download & Install → Launch Session
   ```

---

## Build & Release (per `.agents.md`)

- **Pre-commit:** `./gradlew assembleDebug` or `./gradlew test` must pass before any PR.
- **Small Change (docs, bug, UI tweak):** `fix/`/`chore/` branch → signed commit (`git commit -S`) → `gh pr create` → `gh pr view --json statusCheckRollup,commits` (all SUCCESS/VERIFIED) → `gh pr merge --squash --delete-branch` → `git checkout main && git pull && git fetch --prune`.
- **Big Change (feature, subsystem):** Same as above on `feature/` branch, then bump `versionCode`/`versionName` in `app/build.gradle.kts`, `assembleRelease`, verify `arm64-v8a` only, `gh release create v1.1.0 app/build/outputs/apk/release/app-arm64-v8a-release.apk#Vastavik-Terminal-v1.1.0-arm64.apk --title "Release v1.1.0" --notes-file RELEASE_NOTES.md` with formatted notes.

See [`.agents.md`](.agents.md) for the full authoritative lifecycle, branching, verified commits (`git commit -S` + verified email), PR + squash-merge flow, and release notes template. Current version: **1.1.0** (`versionCode 2`) — see [RELEASE_NOTES.md](RELEASE_NOTES.md).

---

## Notes

- PTY prefers JNI `openpty`; fallback `ProcessBuilder` sends `SIGWINCH` but cannot do true `TIOCSWINSZ`.
- For full VT100 fidelity, replace `stripAnsi`+`LazyColumn` with `com.termux:terminal-emulator`.
- Monospace `FontFamily.Monospace` 13sp, cursor `▉` in `AccentGreen`.
- Storage: shared `bin/proot`, isolated `distros/<id>`, cache `downloads/` (all under `context.filesDir`).

---

*Built for Android ARM64-v8a (64-bit only). Issues & PRs welcome — please follow `.agents.md` governance.*
