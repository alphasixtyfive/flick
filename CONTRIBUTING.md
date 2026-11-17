# Contributing

See [ARCHITECTURE.md](ARCHITECTURE.md) for the code layout. Development requires JDK 21 and Android SDK 35.

## Checks

```sh
sh ./gradlew testDebugUnitTest lintRelease assembleRelease
```

On Windows, use `./gradlew.bat` with the same tasks. Before committing, run:

```sh
python scripts/check-privacy.py
```

The privacy check requires Gitleaks on `PATH` and checks source files and branch history.
Keep credentials, personal data and signing keys out of commits and issue reports.

## Release signing

Release builds are unsigned by default. CI verifies that build and uploads
`app-release-unsigned.apk`; it does not publish an installable release.

To sign a release, supply all four settings through environment variables or private
Gradle properties. Keep the keystore and credentials outside the checkout.

| Environment variable | Gradle property |
| --- | --- |
| `FLICK_KEYSTORE_FILE` | `flick.signing.storeFile` |
| `FLICK_KEYSTORE_PASSWORD` | `flick.signing.storePassword` |
| `FLICK_KEY_ALIAS` | `flick.signing.keyAlias` |
| `FLICK_KEY_PASSWORD` | `flick.signing.keyPassword` |

Gradle properties take precedence over environment variables. Partial configuration
fails the build. Signed output is `app-release.apk`.

Updates must use the installed app's signing identity and a higher version code.
Before publishing, verify the APK with Android's `apksigner`, check the package and
version, and compare its certificate fingerprint with the previous release. Publish
only that verified APK as `Flick-<version>.apk`, with its SHA-256 checksum. GitHub's
release-asset digest is also checked by Flick's updater. Never publish a CI artifact
or a development APK as an update.

## Changes

Keep pull requests focused and describe the behaviour they change. Use the shared TV components;
keep storage, networking and Android services outside composables. Optional features should not
hold up opening apps. Add tests for changed policy, state transitions and failure handling.

For input or layout changes, check D-pad navigation, long presses, Back and app return on an Android TV
device. Check offline and permission-denied paths when a change involves a network service or provider.
Include the device, Android version, Flick version and reproduction steps in bug reports.
