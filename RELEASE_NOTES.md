## What's Changed in v2.1.3
- 🚀 **New Features**: No new major features — patch consolidates proot bundling.
- 🛠️ **Improvements**: Bundled static ARM64 `proot` binary (`app/src/main/assets/bin/arm64-v8a/proot`, ELF aarch64, 245KB) at build time via `downloadArm64Proot` (now finds existing asset and skips download), `ProotManager` now correctly copies from assets to `$FILES_DIR/bin/proot` with `chmod 755`.
- 🐛 **Bug Fixes**: Fix **proot binary missing** (`CRITICAL: ... missing from assets and nativeLibs!` with HTTP 404 fallback) — previous `v2.1.2` APK was built without `proot` asset (EXALAB URL is 404), now APK always contains `proot` and runtime no longer throws, even on BlueStacks.

### Architecture & Compatibility
- **Target**: Android ARM64-v8a (64-bit only)
- **Subsystem**: Minimal Debian ARM64 rootfs with `apt`
- **Commit SHA**: a48c4cc8d9e0f1a2b3c4d5e6f7a8b9c0d  <!-- will be replaced with actual HEAD SHA before release -->
