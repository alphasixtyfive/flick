package app.flick.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.flick.ui.components.TvDialog
import app.flick.ui.components.TvFocusSurface
import app.flick.ui.theme.FlickColors

@Composable
internal fun ProfilePicker(
    profiles: List<Profile>,
    activeProfileId: String,
    onChoose: (Profile) -> Unit,
    onDismiss: () -> Unit,
    showActive: Boolean = true,
) {
    val scroll = rememberLazyListState()
    val activeFocus = remember { FocusRequester() }
    LaunchedEffect(activeProfileId) {
        val index = profiles.indexOfFirst { it.id == activeProfileId }
        if (index >= 0) {
            scroll.scrollToItem(index / 3)
            withFrameNanos { }
            activeFocus.requestFocus()
        }
    }
    TvDialog("Who’s watching?", onDismiss = onDismiss, requestInitialFocus = false) {
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 344.dp),
            state = scroll,
            contentPadding = PaddingValues(4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(profiles.chunked(3), key = { row -> row.joinToString { it.id } }) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    row.forEach { profile ->
                        val initial = profile.id == activeProfileId
                        val active = showActive && initial
                        val focus = if (initial) Modifier.focusRequester(activeFocus) else Modifier
                        TvFocusSurface(
                            onClick = { onChoose(profile) },
                            shape = RoundedCornerShape(app.flick.ui.theme.TvLayout.RowRadius),
                            modifier = focus.width(88.dp).height(124.dp).semantics {
                                selected = active
                                stateDescription = if (active) "Active profile" else if (profile.child) "Child profile" else "Adult profile"
                            },
                        ) {
                            Column(
                                Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Box(Modifier.size(56.dp)) {
                                    ProfileAvatar(profile, 56.dp)
                                    if (active) Box(
                                        Modifier.align(Alignment.BottomEnd).size(18.dp).background(FlickColors.Foreground, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = FlickColors.FocusForeground, modifier = Modifier.size(14.dp))
                                    }
                                }
                                Text(profile.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                Text(if (active) "Active" else if (profile.child) "Child" else if (!showActive) "Adult" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LocalContentColor.current.copy(alpha = .74f), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
