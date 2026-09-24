## What's Changed in v2.1.1
- 🚀 **New Features**: No new major features — patch consolidates VASTAVIK CLI v2.0 rebrand and updater.
- 🛠️ **Improvements**: Interactive terminal now correctly shows `VASTAVIK CLI v2.0 (ARM64 Subsystem)` prompt with `PS1`/`TERM`/`SHELL` and `-i -l` login, window dims via `resizePty`; `ProotManager` self-heals `$FILES_DIR/bin/proot` with chmod 755.
- 🐛 **Bug Fixes**: Fix **soft keyboard not showing** (FocusRequester + hidden BasicTextField IME, Box clickable, LaunchedEffect auto-focus, LazyColumn focus), fix **shell prompt missing** (Alpine `/bin/sh` now gets `PS1`, Debian `PS1` ensured, PTY allocated with `isatty` true), fix **error=2 No such file or directory** for `proot` launch (host cwd now `context.filesDir` not `distros/.../root`, guest `/root`/`/tmp`/`dev/shm` mkdirs, shell fallback `bash→sh`), fix **Extra Keys Bar** direct pipe verified, fix **APK not installed** (release now signed v2).

### Architecture & Compatibility
- **Target**: Android ARM64-v8a (64-bit only)
- **Subsystem**: Minimal Debian ARM64 rootfs with `apt`
- **Commit SHA**: ebcb958e570108c595a3da7624d5dd5ff71a3f0f
