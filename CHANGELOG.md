# Changelog

All notable open-source milestones and version notes for **CryptionChat** are documented in this file.

## [78.1 (beta)] - Current (versionCode 78)

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
