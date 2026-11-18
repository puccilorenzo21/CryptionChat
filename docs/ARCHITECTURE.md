# CryptionChat Architecture

This document describes the high-level architecture of the **CryptionChat** Android client (`com.lollo1344.cryptionchat`).

## 1. Cryptographic & E2EE Layer

- **`IdentityKeyManager.kt`**: Manages long-term identity signing & Diffie-Hellman keys, Signed PreKeys (SPK), One-Time PreKeys (OPK), and peer trust fingerprints (`TrustedIdentityDao.kt` / `TrustedIdentityEntity.kt`).
- **`X3dhHelper.kt`**: Implements Extended Triple Diffie-Hellman (`secp256r1`, domain info `CryptionChat-X3DH-v1`) for asynchronous session establishment without trusting the server with plaintext keys.
- **`DoubleRatchetHelper.kt`**: Implements the Double Ratchet state machine for 1-to-1 forward secrecy and break-in recovery, including an encrypted local message vault (`VaultDao.kt` / `VaultEntryEntity.kt`).
- **`SenderKeyHelper.kt`**: Manages Sender Keys distribution and symmetric ratcheting for encrypted group chats.
- **`E2eeSessionOrchestrator.kt` & `EncryptionHelper.kt`**: Coordinates session initialization, key rotation, and message encryption/decryption workflows.

## 2. Encrypted Local Storage

- **`AppDatabase.kt`**: Room database (`cryptionchat_local_db`, schema v3) encrypted via **SQLCipher** (`net.zetetic:sqlcipher-android`) with a random passphrase protected in **`SecurePrefs.kt`** (AndroidX `EncryptedSharedPreferences` / Android Keystore).
  - Entities: `LocalMessageEntity`, `VaultEntryEntity`, `TrustedIdentityEntity`.

## 3. Networking, Pinning & Tor Fail-Closed Routing

- **`ApiClient.kt` & `ApiService.kt`**: Retrofit + OkHttp client configured with:
  - **Bearer Token** authentication interceptor
  - **SPKI Certificate Pinning** (`CertificatePinner`, bypassed only for `.example.com` placeholders)
  - Optional **Tor SOCKS5** proxy routing via **`OrbotHelper.kt`** (`127.0.0.1:9050` default), enforcing fail-closed DNS and blocking cleartext leaks when proxy mode is enabled.
- **`SecureGlideModule.kt`**: Routes image/media loading through the same hardened `OkHttpClient` so media requests respect Tor and certificate pinning.

## 4. Real-Time Calls & Offline Media Processing

- **`WebRtcClient.kt`, `CallManager.kt`, `CallActivity.kt`**: WebRTC audio call stack (`io.github.webrtc-sdk:android`).
- **`MeteredcaManager.kt`**: Fetches dynamic STUN/TURN ICE server credentials with quota tracking and fallback handling.
- **`WhisperLib.kt` & `app/src/main/cpp/`**: CMake + NDK (`cryptionchat_native`) JNI bridge to `whisper.cpp` and `ggml` for local speech-to-text transcription.
- **`AudioTranscriber.kt`**: Manages offline transcription workflows including Vosk (`com.alphacephei:vosk-android`).

## 5. Active Device Privacy & Emergency Controls

- **`BaseActivity.kt`**: Centralizes `FLAG_SECURE`, biometric lock enforcement (`BiometricHelper.kt`), root detection checks (`RootDetectionHelper.kt`), and maintenance state checks.
- **`ScreenshotDestroyer.kt`**: Detects screenshot attempts and triggers configured protective responses.
- **`PanicHelper.kt`**: Implements local wipe, remote wipe, and Dead Man's Switch auto-destruction when the app is unused past the configured threshold.
- **`HideAppTileService.kt`**: Android Quick Settings tile for stealth app-hiding workflows.
