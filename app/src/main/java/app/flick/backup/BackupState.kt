package app.flick.backup

enum class BackupPreview { Export, Restore }

data class BackupState(val busy: Boolean = false, val preview: BackupPreview? = null,
    val summary: BackupSummary? = null, val needsParentPin: Boolean = false, val message: String? = null)
