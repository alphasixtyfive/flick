# Set up Flick

Flick supports Android TV and Google TV devices running Android 8.0 or later.

## Install and choose HOME

1. Download `Flick-<version>.apk` from the [latest release](https://github.com/alphasixtyfive/flick/releases/latest) and install it with Android’s installer or ADB.
2. Open Flick from the app list, then choose it in Android's Home/default-app settings.
3. Check Home, Back, app launch and return with the physical remote.

Flick TV's Android application ID is `app.flick`. Updates must use the same signing certificate as the installed app.
Keep the stock launcher installed and enabled so you can switch back through Android Settings.
Select the stock launcher before uninstalling Flick. Root and accessibility-service access are not required.

## Updates

Open **Settings → About → Updates**. Flick checks the latest stable GitHub release
when you ask; there is no background update check. Choose **Download update**, then **Install update**.
If Android asks, allow Flick under **Install unknown apps**, return to Flick and select
**Install update** again. Android's installer asks you to confirm or cancel.

Flick checks the download digest, package, version and signing certificate before opening the
installer. Updates preserve the installed app's data. A build signed with a different key cannot
replace your installation; use its original update source rather than uninstalling to work around this.
Unsigned CI artifacts are for build inspection, not installation.

## Home screen

Settings contains Home screen, Wallpapers, Home Assistant, Clock & weather, Profiles, Device and About.
App tile size, text size, top buttons and navigation sounds are under Home screen. If the Settings
button is hidden, an adult can hold the profile button or use the remote's Settings key.

Press OK to open an app or Continue watching item; hold OK to open its options. Android handles
App info and uninstall confirmation. Remove from Continue watching hides a title in Flick without
changing streaming history. **Restore Continue watching** in Home screen settings shows those hidden
titles again if their apps still offer them. It appears only while Continue watching is enabled and
Flick has access to Android TV recommendations.

All apps opens a full-screen grid with compact banners and app names. The number of columns adapts
to the available width. Extra Home favourites scroll horizontally rather than wrapping. A long-press menu stays
over the drawer; Back or adding/removing a Home favourite returns to the same app and scroll position.

Everyone can rearrange the apps on their own Home screen: hold OK on an app, choose **Move**,
use Left/Right, then press OK to save. Back cancels. Each profile keeps its own order.
Children can move only their allowed apps; adding or removing access, App info and uninstall remain adult controls.

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

For a practical setup using separate official app editions for adults and children, see
[Kids profiles and streaming accounts](docs/kids-profiles.md). It covers SmartTube stable/Beta,
the official Jellyfin debug copy, and the limits of this workaround.

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
If the box stays awake while the television switches off, choose its Home Assistant entity under
Profiles → On startup & wake → TV standby detection. Android screen-off is the default choice. Losing that signal also locks the current session. Local profile
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

See [Home Assistant examples](docs/home-assistant-examples.md) for a quick start, TV notifications,
camera images, notification buttons and a dashboard card.

Create a dedicated non-admin Home Assistant user. Sign in as that user and create a long-lived access
token under its profile → Security. Enter the server address and token in Settings → Home Assistant,
then choose up to sixteen controls. Saved tokens are encrypted on the device and are not shown again.
Choose a group to browse its controls alphabetically, or open Selected to review your choices.
Switch a row on to show the control in the sidebar, or off to remove it. Changes save immediately.

Flick supports lights, switches, toggle helpers (`input_boolean`), scenes, scripts, read-only sensors and cameras.
Locks, alarms and gate controls are excluded. Switches respond to a press while Home Assistant
confirms the change. Slower requests show a small spinner in the switch; a failed request returns it to
the reported state and shows an error. Scenes run directly. If the server is offline, use Retry in
Home Assistant settings.

Add a camera under Settings → Home Assistant → Controls → Cameras, then select it in the Home Assistant
sidebar to watch its live video. Press Back to return to the sidebar. Camera playback from the sidebar
uses Home Assistant's HLS stream and works without pairing or Android's Display over other apps permission.

### TV connection

Install the optional [Flick integration](https://github.com/alphasixtyfive/flick-home-assistant)
in Home Assistant for notifications and launcher management. Ordinary light controls work without it.

1. On the TV, open Settings → Home Assistant → TV connection → Pair TV.
2. In an administrator's Home Assistant session, open Settings → Devices & services → Add integration → Flick.
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

### Home screen text

1. Pair the TV, then enable Settings → Home Assistant → Home screen text on Flick.
2. In Home Assistant, open Settings → Devices & services → Flick → Configure → Home screen text.
3. Fill in Message with plain text or a template.

Home Assistant renders the template; Flick displays one plain-text message centred at the top,
up to 120 Unicode codepoints. Long output is shortened with an ellipsis. Empty output hides
its text without moving the controls, clock or apps. Children cannot see the message unless explicitly allowed
in Home Assistant, and it stays hidden on the profile chooser and PIN screen.
The TV page shows one preview and an Edit in Home Assistant QR code. Scan it with your phone,
choose your TV, then Configure → Home screen text. Edit Message in Home Assistant.
See the [integration instructions](https://github.com/alphasixtyfive/flick-home-assistant#home-screen-text)
for template examples and configuration.

## Themes

Open Settings → Home screen → Theme. Choose Midnight, Graphite or Warm; the change applies immediately.
Import a theme JSON to add another colour palette, or export the selected theme to edit it elsewhere.
Themes change menus and focus colours; wallpapers and layout remain separate. See [theme format](docs/themes.md).

## Backup & restore

Open Settings → Device → Backup & restore. Back up creates one backup ZIP file with launcher
preferences, profiles and accessible individual photos. Review the summary before choosing where to save it.
Passwords, parent PIN verifiers, Home Assistant credentials and pairing are excluded.

Restore previews the contents before replacing your launcher setup. Existing adult profiles keep their
local PINs; restoring protected profiles onto another TV asks for a new four-digit parent PIN. The TV's
Home Assistant connection and room controls stay unchanged. Photo folders may need to be selected again.
See [backup format and limits](docs/backup.md).

For a failed photo or network connection, adjust that feature in Settings. If stored launcher settings
cannot be read, Flick opens a restricted recovery screen. Use Android app settings to clear Flick's
data and configure it again. Clearing data deletes local profiles, credentials and preferences;
normal APK updates do not require it.
