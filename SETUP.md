# Set up Flick

Flick supports Android TV and Google TV devices running Android 8.0 or later.

## Install and choose HOME

1. [Build the APK](README.md#build) and install it with Android's installer or an authorized ADB connection.
2. Open Flick from the app list, then choose it in Android's Home/default-app settings.
3. Check Home, Back, app launch and return with the physical remote.

The package is `app.flick`. Updates must use the same signing certificate as the installed app.
Keep the stock launcher installed and enabled so you can switch back through Android Settings.
Select the stock launcher before uninstalling Flick. Root and accessibility-service access are not required.

## Home screen

Settings contains Home screen, Wallpapers, Home Assistant, Clock & weather, Profiles, Device and About.
App tile size, text size, top buttons and navigation sounds are under Home screen. If the Settings
button is hidden, an adult can hold the profile button or use the remote's Settings key.

Press OK to open an app or Continue watching item; hold OK to open its options. Android handles
App info and uninstall confirmation. Remove from Continue watching hides a title in Flick without
changing streaming history. Restore removed titles is under Home screen settings.

Continue watching uses Android's Watch Next provider, so availability depends on the apps and TV box.
Enable it in Home screen settings and grant TV-listings access when Android asks.

Choose a weather location through city search in Clock & weather, then select Celsius or Fahrenheit.
Location search and conditions use Open-Meteo.

## Profiles

Open Settings → Profiles to add or edit people. Select the photo row in an existing profile to choose
an avatar through Android's document picker. Select a profile from the home-screen avatar to switch users.

Set a parent PIN on every adult before adding a child profile, then choose the child's allowed apps.
These limits apply to Flick's app list; they do not create separate Android users or restrict content
inside another app. Set up child accounts and content limits in Plex, Jellyfin and other media apps too.

Unlocking an adult profile opens a numeric keypad with four masked slots. Use the D-pad or the
remote's number keys; the fourth digit checks the PIN automatically. Delete removes a digit and
Back returns to the profile chooser. PINs are exactly four digits, including leading zeroes.
Failed attempts can trigger a temporary lockout.

## Photos

Open Settings → Wallpapers:

- Choose photos adds several images through Android's document picker.
- Photo folder adds a folder to the slideshow.
- Selected photos lets you preview and remove individual selections.
- Slideshow contains timing, shuffle, dimming and Next photo.
- Advanced accepts direct HTTP(S) image links and can clear the photo selection.

Adding photos keeps existing selections. A folder scan includes its immediate JPEG, PNG and WebP
files, up to 500 photos; it does not scan subfolders. Android must grant persistent read access.
If a provider revokes access, select the folder or picture again.

NAS folders need an installed Android document provider that exposes them. Flick does not include
an SMB client. Existing HTTP photo servers can supply direct image links under Advanced.
An empty selection uses the built-in backdrop. Automatic rotation pauses while another app is open.

## Home Assistant

Create a dedicated non-admin Home Assistant user. Sign in as that user and create a long-lived access
token under its profile → Security. Enter the server address and token in Settings → Home Assistant,
then choose up to sixteen controls. Saved tokens are encrypted on the device and are not shown again.

Flick supports lights, switches, toggle helpers (`input_boolean`), scenes/scripts and read-only sensors/binary
sensors. Locks, alarms and gate controls are excluded. Rows show reported state, pending changes and
errors; scenes run directly. If the server is offline, use Retry in Home Assistant settings.

### TV integration

Install the optional [Flick TV component](https://github.com/alphasixtyfive/flick-home-assistant)
in Home Assistant for notifications and launcher management. Ordinary light controls work without it.

1. On the TV, open Settings → Home Assistant → TV integration → Pair TV.
2. In an administrator's Home Assistant session, open Settings → Devices & services → Add integration → Flick TV.
3. Enter the TV's six-digit code and confirm its name. The code expires after five minutes.

Pairing uses the existing non-admin TV account and an outbound WebSocket connection.
No server or port needs to be opened on the TV.

Launcher management is a separate opt-in on the TV. It lets Home Assistant administrators manage
profiles, parent PINs, app allowlists and online wallpapers, including selecting an adult profile.
PIN changes use a masked administrator form, not service arguments or entity attributes.

For notifications over other apps, open Notifications → Display over other apps and grant Android
permission. Brief cards leave app input alone. Persistent cards and cards with actions take remote
focus, starting on Dismiss; Back dismisses them. Up to two actions are supported. Switching profiles
clears queued cards, and child profiles receive no action buttons. Images need direct HTTP(S) links
without login credentials; signed links work. A failed image leaves the text visible. Android may
hide overlays on protected screens. Cards are not kept after the app process restarts.

The optional foreground service keeps the connection available while other apps are open.
Android can restore an enabled, paired connection after process loss. After force-stop, reboot or
vendor power-management shutdown, open Flick again. Connection off pauses notifications; Unpair TV
removes pairing. Ordinary light controls stay configured.

## Import setup

Settings → Device → Import setup reads a JSON document and asks for confirmation before applying it.
The format has a top-level `settings` object and optional `wallpaper` and `homeAssistant` objects.
Keep setup files private: they can contain credentials, PIN verifiers and private photo links.
Import saves each feature's configuration separately. On a different device, select photo folders
again to grant access.

For a failed photo or network connection, adjust that feature in Settings. If stored launcher settings
cannot be read, Flick opens a restricted recovery screen. Use Android app settings to clear Flick's
data and configure it again. Clearing data deletes local profiles, credentials and preferences;
normal APK updates do not require it.
