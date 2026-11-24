# Changelog

All notable open-source milestones and version notes for **CryptionChat** are documented in this file.

## [78.6 (beta)] - 2026-11-24 (versionCode 78)

### Updates & UX
- **Floating Background Update Pill**: Added `AutoUpdateManager.UpdateProgressListener` and a top floating progress banner (`BaseActivity.kt`, `layout_global_update_banner.xml`) showing live progress during silent background downloads and completion, clickable from any main screen to open the channel changelog (`Beta`/`Stable`).
- **Immediate Foreground Update Check**: Updated `AutoUpdateManager.onActivityReady` to trigger an immediate server check when opening/resuming the app while keeping the 10-minute throttle for background service polling (`78.5 (beta)`).
- **Silent Auto-Updater (`AutoUpdateManager.kt`, `AutoUpdateEvaluator.kt`)**: Replaced blocking pre-download update dialogs with silent background APK downloads (Beta & Stable channels, `Range` resume support, Tor/proxy aware via `ApiClient`, dedicated notification channel, cryptographic APK signature verification), direct Android system installer prompt on app open, and automatic downloaded-APK cleanup via `BootReceiver` (`ACTION_MY_PACKAGE_REPLACED`) and `MyApplication` (`78.4 (beta)`).

### Localization
- **Automatic English & Italian Support**: Added full runtime localization (`values/strings.xml` default English, `values-it/strings.xml` Italian, `locales_config.xml` for Android 13+) across 1,000+ strings, all layouts, menus, dialogs, foreground services, notifications, and locale-aware date/time formatting (`78.3 (beta)`).

### Compatibility
- **Android 16 KB Page Size Support**: Upgraded `sqlcipher-android` to `4.9.0@aar` and aligned native libraries and uncompressed packaging (`useLegacyPackaging = false`) for full 16 KB device compatibility (`78.2 (beta)`).

## [78.1 (beta)] - 2026-11-18 (versionCode 78)

### Security & Cryptography
- X3DH asynchronous Zero-Knowledge session bootstrap (`secp256r1`) paired with Double Ratchet forward-secret messaging (`X3dhHelper.kt`, `DoubleRatchetHelper.kt`, `E2eeSessionOrchestrator.kt`).
- Group E2EE messaging using Sender Keys (`SenderKeyHelper.kt`).
- Encrypted local Room database via SQLCipher (`AppDatabase.kt`) with `dr_vault` and `trusted_identities` tables.
- Unified OkHttp networking stack (`ApiClient.kt`, `SecureGlideModule.kt`) with SPKI Certificate Pinning and fail-closed Tor SOCKS5 routing (`OrbotHelper.kt`).
- Anti-forensics & emergency protection: Panic Code, Dead Man's Switch (`PanicHelper.kt`), active screenshot detection (`ScreenshotDestroyer.kt`), biometric gate (`BiometricHelper.kt`), and root detection (`RootDetectionHelper.kt`).

### Media, Calls & AI
- Encrypted WebRTC voice calls with dual-account TURN fallback (`WebRtcClient.kt`, `MeteredcaManager.kt`).
- On-device offline audio transcription powered by native `whisper.cpp` JNI (`WhisperLib.kt`) and Vosk (`AudioTranscriber.kt`).
- Generative AI image & translation helpers (`AIImageHelper.kt`, `AIDialogHelper.kt`).

### Open-Source Release Packaging
- Added standardized project structure (`LICENSE` Apache-2.0, `NOTICE` mandatory attribution, `CONTRIBUTING.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md`, `docs/`, `.github/`).
- Automated source sanitization and pre-push security audit pipeline.
