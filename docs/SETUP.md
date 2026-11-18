# Developer Setup Guide

This guide explains how to configure and compile **CryptionChat** from the open-source repository.

## 1. Requirements

- **Java Development Kit (JDK)**: 17
- **Android SDK**:
  - `compileSdk`: 37
  - `minSdk`: 29 (Android 10)
  - `targetSdk`: 34
- **Android NDK & CMake**: CMake `3.22.1` (required for `app/src/main/cpp/CMakeLists.txt`)
- **Gradle**: Included via `./gradlew` / `gradlew.bat`

## 2. Replace Open-Source Placeholders

For security reasons, the public repository contains sanitized placeholders instead of live server endpoints or API keys. Update the following before running against your own backend:

1. **Backend Server URL** (`app/src/main/java/com/lollo1344/cryptionchat/ApiClient.kt`, `MainActivity.kt`, `UpdateHandler.kt`, `AndroidManifest.xml`):
   - Replace `your-server.example.com` with your HTTPS backend domain.
   - Update the SPKI certificate pins in `ApiClient.applyCertificatePinning(...)` to match your server's TLS certificate chain.
2. **TURN / STUN Relay Credentials** (`app/src/main/java/com/lollo1344/cryptionchat/MeteredcaManager.kt` & `WebRtcClient.kt`):
   - Replace `YOUR_METERED_APP1_NAME`, `YOUR_METERED_API1_KEY`, `YOUR_METERED_APP2_NAME`, `YOUR_METERED_API2_KEY`, `YOUR_TURN_USERNAME`, and `YOUR_TURN_PASSWORD` with your own Metered.ca or self-hosted TURN credentials.
3. **Device ID Salt** (`app/src/main/java/com/lollo1344/cryptionchat/DeviceUtils.kt`):
   - Replace `YOUR_STATIC_HARDWARE_SALT` with your own unique salt string.

## 3. Configure Release Signing (Optional for Debug, Required for Signed Release)

1. Copy `keystore.properties.example` to `keystore.properties`:
   ```bash
   cp keystore.properties.example keystore.properties
   ```
2. Edit `keystore.properties` with your keystore path and passwords:
   ```properties
   storePassword=your_store_password
   keyAlias=your_key_alias
   keyPassword=your_key_password
   storeFile=path/to/your-release-keystore.jks
   ```
   > `keystore.properties` and `*.jks` / `*.keystore` are ignored by `.gitignore`. Never commit them.

## 4. Build & Test Commands

Run unit tests:
```bash
./gradlew testDebugUnitTest
```

Build release APK (splits by `armeabi-v7a` and `arm64-v8a`):
```bash
./gradlew assembleRelease
```

Output APKs will be placed under `app/build/outputs/apk/release/`.
