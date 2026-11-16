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

## Changes

Keep pull requests focused and describe the behaviour they change. Use the shared TV components;
keep storage, networking and Android services outside composables. Optional features should not
hold up opening apps. Add tests for changed policy, state transitions and failure handling.

For input or layout changes, check D-pad navigation, long presses, Back and app return on an Android TV
device. Check offline and permission-denied paths when a change involves a network service or provider.
Include the device, Android version, Flick version and reproduction steps in bug reports.
