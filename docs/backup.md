# Backup and restore

Open **Settings → Device → Backup & restore**. Save a backup ZIP file using Android's file picker. Choose that file on another TV, review the contents, then restore.

The backup includes launcher preferences, themes, profiles, app choices, weather location, individual wallpapers and profile pictures. Local images are copied into the file; restoring does not depend on the original file picker permission. Built-in wallpapers stay built-in. Online images remain links and are not downloaded during backup.

Photo folders are not copied or scanned. An existing folder permission can be reused on the same TV. On another TV, choose the folder again under Wallpapers. The restore preview reports this and any online image links it could not include. Links containing a username, password, query or fragment are omitted rather than sharing possible credentials.

Home Assistant sign-in, exposed controls, TV pairing and other device-specific settings remain on each TV. PINs and their verifiers are never exported. Matching adult profiles keep their existing protection on this TV. If another adult profile needs protection, restore asks for a new four-digit parent PIN. Restoring settings does not sign in to an adult profile.

Backups contain names, portraits and personal preferences. Keep them somewhere private. They are ZIP files, not encrypted vaults.

## File format

Version 1 uses a ZIP archive with `manifest.json` and `media/<sha256>` entries. The manifest has `format: "flick.backup"`, `version: 1`, native `settings`, optional `wallpaper`, a list of image hashes in `assets`, and short `notices`. Image references use `flick-asset:<sha256>` within the archive. They become app-owned content URIs on restore.

The reader rejects unknown archive versions, unexpected paths, missing or unreferenced images, mismatched checksums and ambiguous profile roles. Limits are 512 KiB of manifest JSON, 600 images, 16 MiB per image and 128 MiB for both the input archive and its uncompressed contents. Image headers and a sampled decode are checked before they become launcher sources. No archive filename becomes a filesystem path.

Restore validates and stages its data before replacing settings. A private recovery record holds the previous local settings while both preference stores are written. If the app stops mid-restore, startup restores those previous settings before loading profiles. That record stays inside the app and is never included in an exported backup.
