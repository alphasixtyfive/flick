# Themes

Choose Midnight, Graphite or Warm in Settings → Home screen → Theme. Selecting a theme
changes menu, text and focus colours. Wallpapers, profiles, app order and layout stay independent.
Imported themes appear beside the built-in choices. You can keep up to eight imports and
remove a selected import to return to Midnight.

Use **Export theme** to save a JSON file, or **Import theme** to choose one edited on another
device. The palette is copied into Flick, so the original file is not needed afterward.
Theme files contain a name and colours, without account information or launcher settings.
Importing an identical theme selects the existing one. To import a changed palette under the
same name, remove the old import first or give the edited file a different theme name.

The [example theme](examples/graphite.theme.json) can be edited in a text editor:

```json
{
  "format": "flick-theme",
  "version": 1,
  "name": "My Graphite",
  "colors": {
    "background": "#171717",
    "surface": "#222222",
    "surfaceRaised": "#2D2D2D",
    "foreground": "#F0F0F0",
    "secondary": "#BDBDBD",
    "focusBackground": "#E2E2E2",
    "focusForeground": "#191919"
  }
}
```

Version 1 supports opaque `#RRGGBB` colours and dark surfaces with light text. This keeps the
clock and other text readable over photo wallpapers. Text and focus contrast are checked
before import; unsupported fields, unreadable palettes and files larger than 16 KiB are rejected.
The format does not load scripts, fonts, images or external stylesheets.

An unsupported or damaged saved appearance falls back to Midnight without changing profiles
or their protection. Home Assistant notification overlays retain their neutral colours.
