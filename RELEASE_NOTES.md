## What's Changed in v1.1.0
- 🚀 **New Features**: Dual-sided gesture navigation (left Session Manager + right ARM64 Distro Hub), persistent multi-session engine with `SessionState`/`SessionManager`, 6-distro ARM64 catalog (Debian, Ubuntu, Kali, Arch, Alpine, Fedora) with isolated `$FILES_DIR/distros/<id>` storage and streaming `DistroDownloader` (download → extract → launch → delete), unkillable foreground service badge updates.
- 🛠️ **Improvements**: Obsidian dark theme (`#0B1120`/`#1E293B`) with neon cyan/green active highlights, Termux-style ExtraKeys bar with sticky CTRL/ALT, PTY `TIOCSWINSZ` resize via JNI `openpty`, PRoot commands per-distro isolation, DNS verification (`resolv.conf` 1.1.1.1/8.8.8.8) for `apt`.
- 🐛 **Bug Fixes**: Fixed `onTaskRemoved` service persistence, Zip-Slip protection on tar extraction, legacy `debian` → `distros/debian` migration, wake-lock toggle notification sync, session switch without terminating background PTYs.

### Architecture & Compatibility
- **Target**: Android ARM64-v8a (64-bit only)
- **Subsystem**: Minimal Debian ARM64 rootfs with `apt`
- **Commit SHA**: f7383997c877f75d86fc30d2aa61b1dfe3882173
