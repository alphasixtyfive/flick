package app.flick.tv.backup

import androidx.core.content.FileProvider

/** Only the restored-media path declared in backup_media_paths.xml is shareable. */
class BackupMediaProvider : FileProvider()
