## What's Changed in v1.1.1
- 🚀 **New Features**: No new features — patch release focused on installability.
- 🛠️ **Improvements**: Release build now correctly signed (v1 + v2) and strictly `arm64-v8a` only; added `signingConfigs.release` using debug keystore for immediate installability (replace with dedicated `release.keystore` for production), verified via `apksigner verify` and `unzip -l` lib check.
- 🐛 **Bug Fixes**: Fix **"App not installed as package appears to be invalid"** — previous `v1.1.0` artifact was `app-release-unsigned.apk` (no signingConfig) rejected by PackageManager. Now `app-release.apk` / `app-arm64-v8a-release.apk` is signed (1 signer, v2 true) and installs on ARM64 devices.

### Architecture & Compatibility
- **Target**: Android ARM64-v8a (64-bit only)
- **Subsystem**: Minimal Debian ARM64 rootfs with `apt`
- **Commit SHA**: f17d0a285d1b06f4145bf9b914f7ec21ab9fe65e
