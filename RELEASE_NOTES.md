## What's Changed in v2.1.2
- 🚀 **New Features**: No new major features — patch consolidates proot auto-fetch and hardware keyboard.
- 🛠️ **Improvements**: Gradle `downloadArm64Proot` now lenient (no fail on offline, runtime fallback), `ProotManager` OkHttp auto-download with `VASTAVIK-CLI-Agent/2.0` and chmod 755, `TerminalScreen` now handles both soft IME and BlueStacks hardware `KeyEvent` (ENTER/DEL/TAB/ESC/DPAD + unicode fallback) with `FocusRequester` + `onKeyEvent`.
- 🐛 **Bug Fixes**: Fix **proot binary missing** (assets empty → now bundled at build or downloaded at runtime instead of `CRITICAL: ... missing`), fix **BlueStacks typing not working** (previously only soft keyboard, now physical PC keys pipe to `ptyProcess`), fix **error=2** already patched in v2.1.1, fix **ShellZero branding** already purged.

### Architecture & Compatibility
- **Target**: Android ARM64-v8a (64-bit only)
- **Subsystem**: Minimal Debian ARM64 rootfs with `apt`
- **Commit SHA**: 3c4af5547e212400f3d53f9330bfabb07826b917
