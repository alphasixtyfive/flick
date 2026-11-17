package app.flick.themes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import app.flick.ui.components.TvChoiceRow
import app.flick.ui.components.TvLazyPanel
import app.flick.ui.components.TvSettingRow

@Composable
fun ThemePanel(appearance: AppearanceSettings, onSelect: (String) -> Unit,
    onImport: () -> Unit, onExport: (ThemeDefinition) -> Unit, onRemove: (String) -> Unit, onBack: () -> Unit) {
    val selected = ThemeCatalog.resolve(appearance)
    val exportFocus = remember { FocusRequester() }
    TvLazyPanel("Theme", onBack = onBack) {
        ThemeCatalog.themes(appearance).forEach { theme ->
            item(key = theme.id) {
                TvChoiceRow(theme.name, selected = theme.id == selected.id, onClick = { onSelect(theme.id) })
            }
        }
        item(key = "import") { TvSettingRow("Import theme", icon = Icons.Default.FileDownload, onClick = onImport) }
        item(key = "export") {
            TvSettingRow("Export theme", icon = Icons.Default.FileUpload, modifier = Modifier.focusRequester(exportFocus), onClick = { onExport(selected) })
        }
        if (appearance.importedThemes.any { it.id == selected.id }) item(key = "remove") {
            TvSettingRow("Remove imported theme", icon = Icons.Default.DeleteOutline,
                onClick = { exportFocus.requestFocus(); onRemove(selected.id) })
        }
    }
}
