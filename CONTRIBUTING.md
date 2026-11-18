# Contributing to CryptionChat

Thank you for your interest in contributing to **CryptionChat**!

## License & Attribution

CryptionChat is licensed under the **Apache License, Version 2.0** with a mandatory attribution [`NOTICE`](NOTICE) file.
By submitting a contribution, you agree that your contribution will be licensed under the same Apache-2.0 terms and that proper attribution to the original author (**Lorenzo Pucci / lollo1344**) is preserved.

## How to Contribute

1. **Fork** the repository and create a new branch from `main` (`feat/my-feature` or `fix/my-bugfix`).
2. **Keep changes focused**: avoid unrelated refactoring or formatting noise.
3. **Security hygiene (Critical)**:
   - NEVER commit `keystore.properties`, `*.jks`, `*.keystore`, `local.properties`, `.env`, API keys, TURN credentials, or private server URLs.
   - Use placeholders (`YOUR_...` or `your-server.example.com`) for any endpoint or key examples.
   - Ensure Tor fail-closed / Certificate Pinning / E2EE invariants are not weakened.
4. **Verify before opening a PR**:
   - Run unit tests:
     ```bash
     ./gradlew testDebugUnitTest
     ```
   - Verify release build compiles cleanly (unsigned if no local `keystore.properties`):
     ```bash
     ./gradlew assembleRelease
     ```
5. **Open a Pull Request** using the provided PR template and describe what changed and how it was tested.

## Reporting Bugs & Suggesting Features

Please use the GitHub Issue Templates in `.github/ISSUE_TEMPLATE/` and provide Android version, app version, and reproducible steps (without sharing private keys or sensitive logs).
