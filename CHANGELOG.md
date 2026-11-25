# Changelog

All notable open-source milestones and version notes for **CryptionChat** are documented in this file.

## [78.7 (beta)] - 2026-11-25 (versionCode 78)

### Security (`CVE-2026-114938`)
- **Build Toolchain & CI Patching**: Upgraded the Gradle daemon JVM toolchain (`gradle/gradle-daemon-jvm.properties`) to patched **Amazon Corretto `21.0.13.1+2-LTS`** and pinned GitHub Actions CI (`.github/workflows/android-ci.yml`) to patched **Eclipse Temurin `21.0.13.1`**.
- **JVM Defense-in-Depth**: Enabled `-XX:-UseBASE64Intrinsics` across Gradle daemon args (`gradle.properties`), JVM unit test tasks (`app/build.gradle.kts`), and CI (`JAVA_TOOL_OPTIONS`) to mitigate the x86-64 AVX-512 VBMI Base64 intrinsic vulnerability (`CVE-2026-114938`).
- **Defensive Base64 Validation (`SafeBase64.kt`)**: Added strict pre-decoding length, alphabet, and padding validation across all cryptographic helpers (`DoubleRatchetHelper`, `EcdhHelper`, `EncryptionHelper`, `IdentityKeyManager`, `SenderKeyHelper`), verified with dedicated unit tests (`SafeBase64UnitTest`).

### Battery & Energy Optimization
- **Deep Sleep-Friendly Background Service (`NotificationService.kt`, `PollingPowerPolicy.kt`)**: Removed the continuous `PARTIAL_WAKE_LOCK` previously held during the 25s passive HTTP long-poll wait; short processing WakeLocks (`<= 8s`) are now acquired only when real events arrive (messages, incoming calls, WebRTC signals, key updates).
- **Network & Screen-Off Power Adaptation**: Added `ConnectivityManager.NetworkCallback` to fully suspend background polling when offline, exponential backoff on transient network errors (`5s -> 60s`), and adaptive deep-idle pauses (`15s`/`20s`, strictly within the 45s incoming-call window) on cellular/Power Save when the screen is off.
- **Reduced Background Traffic & IPC Overhead**: Throttled fallback `E2eeSessionOrchestrator.syncDistributions()` (immediate on hints/messages; periodic fallback increased to 5 min screen-on / 15 min screen-off), tuned `WatchdogReceiver` interval from 15 min to 2 hours with corrected `triggerAtMillis` (`ServiceScheduler.kt`), cached `DeviceUtils.getDeviceId()` and `ApiClient` auth credentials/Tor reachability, and cached `AutoUpdateManager` package/directory metadata to avoid repeated `PackageManager` IPC and FUSE/`MediaProvider` calls.

### UI Efficiency & Memory/Resource Leak Fixes
- **Foreground Polling & Storage Optimization**: Added dirty-checking before writing to `EncryptedSharedPreferences` in `MainActivity` and `ChatActivity`, batched chat metadata updates with `apply()`, throttled in-chat key distribution syncs and `invalidateOptionsMenu()`, added instant home-list refresh via local broadcast, and enforced `MaintenanceChecker` throttling in `BaseActivity`.
- **Native Memory & Stream Cleanup**: Guaranteed `Model.close()`, `Recognizer.close()`, `MediaCodec.release()`, and `MediaExtractor.release()` in `AudioTranscriber`; wrapped `ContentResolver` streams in `.use {}` (`FileUtils`, `ChatBackgroundManager`, `MainActivity`); added `WebView.destroy()` on changelog dismissal (`BaseActivity`); suspended background video on `MainActivity.onPause()`; detached `RecyclerView.adapter` in `ChatActivity.onDestroy()`; and switched `ScreenshotDestroyer` to `applicationContext` with shared coroutine scopes.

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
