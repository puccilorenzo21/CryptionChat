# Security Policy

## Supported Versions

Only the latest release on the `main` branch is actively maintained with security updates.

## Cryptographic & Privacy Architecture Summary

CryptionChat is designed around Zero-Knowledge client-side security:

- **1-to-1 End-to-End Encryption**: Asynchronous **X3DH** (Extended Triple Diffie-Hellman on `secp256r1`) session bootstrap + **Double Ratchet** forward-secret messaging (`X3dhHelper.kt`, `DoubleRatchetHelper.kt`, `E2eeSessionOrchestrator.kt`).
- **Group End-to-End Encryption**: Sender Keys protocol (`SenderKeyHelper.kt`).
- **Local Storage Encryption**: Room database encrypted at rest via **SQLCipher** (`AppDatabase.kt`) and **AndroidX Security Crypto** (`SecurePrefs.kt`) backed by Android Keystore.
- **Network Privacy & Transport Hardening**: Optional **Tor SOCKS5** proxy routing (via Orbot) with fail-closed DNS/cleartext blocking and **SPKI Certificate Pinning** (`ApiClient.kt`, `SecureGlideModule.kt`).
- **Emergency & Device Protection**: Biometric lock, screenshot detection/blocking (`ScreenshotDestroyer.kt`), Panic Code, Dead Man's Switch (`PanicHelper.kt`), and root detection (`RootDetectionHelper.kt`).
- **Offline Speech Transcription**: Local on-device transcription via `whisper.cpp` (JNI/NDK) and Vosk (`WhisperLib.kt`, `AudioTranscriber.kt`).

## Sanitization in the Open-Source Distribution

Before publication, secrets and private operational configurations are replaced with placeholders:
- `ApiClient.kt` server URL (`your-server.example.com`)
- `MeteredcaManager.kt` TURN/STUN API keys and credentials (`YOUR_METERED_*`, `YOUR_TURN_*`)
- `DeviceUtils.kt` static hardware salt (`YOUR_STATIC_HARDWARE_SALT`)
- Developer-only test/maintenance whitelists are disabled.

## Reporting a Vulnerability

If you discover a security vulnerability in CryptionChat:

1. **Do not** disclose it publicly in an issue or pull request before a fix is coordinated.
2. Submit a private report via GitHub's [**Private Vulnerability Reporting**](https://github.com/puccilorenzo21/CryptionChat/security/advisories/new) (**Security** tab -> **Report a vulnerability**).
3. Include:
   - Affected file(s) / component
   - Threat model and reproduction steps or proof of concept
   - Suggested remediation if available
