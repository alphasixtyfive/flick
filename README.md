<p align="center">
  <img src="docs/assets/flick.svg" width="112" height="112" alt="Flick icon">
</p>

<h1 align="center">Flick</h1>

A simple, customisable launcher for Android TV, built with Kotlin and Compose for TV.
Requires Android 8.0 or later.

- Favourite apps, All apps and Continue watching, with remote long-press menus.
- Adult and child profiles, parent PINs and profile photos.
- Photo slideshows from selected pictures, document folders or image URLs.
- Clock, weather, navigation sounds and configurable home-screen controls.
- Home Assistant lights, switches, scenes and device status.
- Optional TV pairing for notifications and remote profile/wallpaper management.

## Get started

See [SETUP.md](SETUP.md) for installation, profiles, photos and Home Assistant setup.

## Home Assistant

Light controls connect directly to Home Assistant. The optional
[Flick TV component](https://github.com/alphasixtyfive/flick-home-assistant) adds paired devices,
notifications over other apps and launcher management. Pairing and management are enabled on the TV.

See [setup](SETUP.md#home-assistant) and the [connection protocol](docs/home-assistant-protocol.md).

## Build

Use JDK 21 and Android SDK platform 35. Set `JAVA_HOME` and `ANDROID_HOME`, then run:

```powershell
./gradlew.bat assembleRelease
```

On macOS/Linux, use `sh ./gradlew assembleRelease`. The APK is written to
`app/build/outputs/apk/release/`. Local builds use debug signing.

See [CONTRIBUTING.md](CONTRIBUTING.md) for checks and [ARCHITECTURE.md](ARCHITECTURE.md) for the code layout.

## Licence

Flick is [MIT licensed](LICENSE). Bundled Google Sans Flex TV is distributed under the
[SIL Open Font License](app/src/main/assets/licenses/google-sans-flex-ofl.txt).
See [third-party notices](THIRD_PARTY_NOTICES.md) for the font, Home Assistant artwork and dependencies.
