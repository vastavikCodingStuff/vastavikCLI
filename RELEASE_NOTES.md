## What's Changed in v2.1.0
- 🚀 **New Features**: Automated GitHub Release In-App Updater — on launch queries `GET /repos/vastavikCodingStuff/vastavikCLI/releases/latest`, compares `v` stripped semver, shows vibrant green pill `#22C55E` with `⬇ v<LATEST>` in top bar next to drawer/exit, tap opens Update Dialog (Current vs New + Release Notes), **Update Now** finds `*arm64*.apk` asset, streams via `HttpURLConnection` to `getExternalFilesDir(DOWNLOADS)` with live progress, installs via `FileProvider` + `Intent.ACTION_VIEW` (`application/vnd.android.package-archive`).
- 🛠️ **Improvements**: `UpdateManager` `StateFlow<UpdateState>` (Idle/Checking/UpdateAvailable/Downloading/Downloaded/Error) with `followRedirects` + `VASTAVIK-CLI-Agent/2.0`, `DualDrawerTopBar` LaunchedEffect check, `UpdateDialog` with Current/New cards and progress bar, `AndroidManifest` `REQUEST_INSTALL_PACKAGES` + `FileProvider` (`@xml/file_provider_paths` for downloads/external/files/cache).
- 🐛 **Bug Fixes**: Fix `BuildConfig` unresolved (use `packageManager.getPackageInfo`), fix `Icons.Filled.Download` missing (use Text ⬇), fix `clip`/`RoundedCornerShape` imports.

### Architecture & Compatibility
- **Target**: Android ARM64-v8a (64-bit only)
- **Subsystem**: Minimal Debian ARM64 rootfs with `apt`
- **Commit SHA**: 736a559a0f1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0  <!-- will be replaced with actual HEAD SHA before release -->
