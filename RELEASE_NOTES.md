## What's Changed in v2.0.0
- 🚀 **New Features**: VASTAVIK CLI rebrand (purge ShellZero, app label `VASTAVIK CLI`, notification `VASTAVIK CLI Session Service`, headers `VASTAVIK CLI v2.0 (ARM64 Subsystem)`), EXALAB direct CDN for 5 distros (Debian/Ubuntu/Kali/Arch/Alpine) with `VASTAVIK-CLI-Agent/2.0` and 301/302 redirect handling.
- 🛠️ **Improvements**: Session isolation fix — `SessionManager` now stores `rootfsPath` dynamically (`$FILES_DIR/distros/<distro_id>`) and `shellPath` per distro, PRoot spawns with `<rootfsDirectory>` + `<shellPath>` (Alpine `/bin/sh`), DNS `1.1.1.1`/`8.8.8.8` guaranteed post-extract; usesCleartextTraffic + requestLegacyExternalStorage in manifest.
- 🐛 **Bug Fixes**: Fix **Kali Launches Debian** (hardcoded `$FILES_DIR/debian` → dynamic), fix **Exit not stopping service** (`stopSelfWithCleanup` kills PTYs, releases WakeLock, `stopForeground(STOP_FOREGROUND_REMOVE)`, `Process.killProcess`), fix **Notification count desync** (`SessionManager.removeSession` → `instance?.updateNotification(size)` + `stopSelfWithCleanup` if empty), fix **App not installed** (release now signed `v2` with debug keystore, verified `apksigner`).

### Architecture & Compatibility
- **Target**: Android ARM64-v8a (64-bit only)
- **Subsystem**: Minimal Debian ARM64 rootfs with `apt`
- **Commit SHA**: a012be5a0f2b6a7c9d8e0f1a2b3c4d5e6f7a8b9c0d  <!-- will be replaced with actual HEAD SHA before release -->
