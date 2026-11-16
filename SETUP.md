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
The app drawer uses a five-column grid with app names beneath the banners. A long-press menu stays
over the drawer; Back or adding/removing a Home favourite returns to the same app and scroll position.

Continue watching uses Android's Watch Next provider, so availability depends on the apps and TV box.
Enable it in Home screen settings and grant TV-listings access when Android asks.

Choose a weather location through city search in Clock & weather, then select Celsius or Fahrenheit.
Location search and conditions use Open-Meteo.
The weather icon reflects the reported condition and day/night value. Returning from another app
keeps the last reading visible while it refreshes; changing location clears the old location's reading.

## Profiles

Open Settings → Profiles to add or edit people. Select the photo row in an existing profile to choose
an avatar through Android's document picker. Select a profile from the home-screen avatar to switch users.

Select **Profile wallpaper** while editing an existing profile to choose a built-in wallpaper or your own picture. That picture
stays fixed while the profile is active; other profiles keep their own picture or the shared slideshow.
Select **Use shared wallpapers** to remove the override. Only adults can change profile wallpapers.

Set a parent PIN on every adult before adding a child profile, then choose the child's allowed apps.
For an existing child profile, **Allowed apps** opens a separate screen where each switch saves
automatically. Name, role and PIN edits still use Save profile; app switches do not save those drafts.
These limits apply to Flick's app list; they do not create separate Android users or restrict content
inside another app. Set up child accounts and content limits in Plex, Jellyfin and other media apps too.

Unlocking an adult profile opens a numeric keypad with four masked slots. Use the D-pad or the
remote's number keys; the fourth digit checks the PIN automatically. Delete removes a digit and
Back returns to the profile chooser. PINs are exactly four digits, including leading zeroes.
Failed attempts can trigger a temporary lockout.

Profiles → On startup & wake offers Ask who’s watching and Resume last profile. Families with a child
default to Ask unless an adult explicitly chooses Resume. Ask keeps Flick locked until someone chooses
a profile: children open directly, and adults enter their PIN even when they were the last person watching.
Back from the PIN returns to the chooser. Set a PIN for each adult before selecting Ask.

Ask resets when Flick's process starts or Android reports that the box's screen turned off. Returning
from Plex, another app or a photo picker keeps the current session. Flick does not interrupt another app.
If the box stays awake while the television switches off, choose the physical television under
Home Assistant → Physical TV. Losing that signal also locks the current session. Local profile
selection works offline; while the signal remains unavailable, TV-only power changes cannot be observed.

## Photos

Open Settings → Wallpapers:

- Built-in wallpapers previews nine included pictures. Choose one or use all as a slideshow; no network or photo provider is needed.
- Choose photos adds several images through Android's document picker.
- Photo folder adds a folder to the slideshow.
- Selected photos lets you preview and remove individual selections.
- Slideshow contains timing, shuffle, dimming and Next photo.
- Advanced accepts direct HTTP(S) image links and can clear the photo selection.

Adding photos keeps existing selections. A folder scan includes its immediate JPEG, PNG and WebP
files, up to 500 photos; it does not scan subfolders. Android must grant persistent read access.
If a provider revokes access, select the folder or picture again.

A fresh installation starts with Midnight Ribbon. Updating Flick preserves your current photo selection,
including an intentionally empty selection. Full-resolution versions are in the [4K wallpaper collection](wallpapers/).

NAS folders need an installed Android document provider that exposes them. Flick does not include
an SMB client. Existing HTTP photo servers can supply direct image links under Advanced.
An empty selection uses the built-in backdrop. Automatic rotation pauses while another app is open.

## Screensaver

Settings → Device → Screensaver opens Android's screensaver settings. Choose an installed screensaver
there for idle videos or photos. [Aerial Views](https://github.com/theothernt/AerialViews) supports
aerial videos and local or network media. It is optional and must be installed separately.
The screensaver is managed by Android; Flick's Home wallpaper stays independent.

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

Launcher management is a separate opt-in and requires an unlocked adult session on the TV. It lets Home Assistant administrators manage
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
