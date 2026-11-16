package app.flick.profiles

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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
    BackHandler(onBack = onDismiss)
    ProfileChooser(profiles, activeProfileId, onChoose, showActive)
}

@Composable
internal fun ProfilePickerBackdrop() {
    Box(Modifier.fillMaxSize()
        .background(Brush.linearGradient(listOf(Color(0xFF1B2735), FlickColors.Background, Color(0xFF0C1119))))
        .pointerInput(Unit) { detectTapGestures(onTap = {}) })
}

/** Startup and manual switching share one neutral surface; personal photos appear after selection. */
@Composable
internal fun ProfileChooser(profiles: List<Profile>, activeProfileId: String, onChoose: (Profile) -> Unit, showActive: Boolean) {
    Box(Modifier.fillMaxSize()) {
        ProfilePickerBackdrop()
        Column(Modifier.align(Alignment.Center).widthIn(max = 600.dp).fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Text("Who’s watching?", style = MaterialTheme.typography.titleLarge)
            ProfileChoices(profiles, activeProfileId, onChoose, showActive)
        }
    }
}

/** Request initial focus only after its target is placed and this picker window is focused. */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun ProfileChoices(
    profiles: List<Profile>,
    activeProfileId: String,
    onChoose: (Profile) -> Unit,
    showActive: Boolean = true,
) {
    val scroll = rememberLazyListState()
    val initialId = profiles.find { it.id == activeProfileId }?.id ?: profiles.firstOrNull()?.id
    val initialFocus = remember(initialId) { FocusRequester() }
    var initialPlaced by remember(initialId) { mutableStateOf(false) }
    var focusPending by remember(initialId) { mutableStateOf(true) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(initialId, profiles.map { it.id }) {
        val index = profiles.indexOfFirst { it.id == initialId }
        if (index >= 0) scroll.scrollToItem(index / 3)
    }
    LaunchedEffect(focusPending, initialPlaced, windowFocused) {
        if (focusPending && initialPlaced && windowFocused) initialFocus.requestFocus()
    }
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 344.dp)
            .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup(),
        state = scroll,
        contentPadding = PaddingValues(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(profiles.chunked(3), key = { row -> row.joinToString { it.id } }) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(
                24.dp, Alignment.CenterHorizontally)) {
                row.forEach { profile ->
                    val initial = profile.id == initialId
                    val active = showActive && profile.id == activeProfileId
                    val focus = (if (initial) Modifier.focusRequester(initialFocus).onPlaced { initialPlaced = true } else Modifier)
                        .onFocusChanged { if (it.isFocused) focusPending = false }
                    ProfileAvatarChoice(profile, active, focus) { onChoose(profile) }
                }
            }
        }
    }
}

@Composable
private fun ProfileAvatarChoice(profile: Profile, active: Boolean, modifier: Modifier, onChoose: () -> Unit) {
    val pinProtected = !profile.child && profile.pinHash != null
    Column(Modifier.width(112.dp).height(128.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(84.dp)) {
            TvFocusSurface(onClick = onChoose, shape = CircleShape, containerColor = Color.Transparent,
                modifier = modifier.fillMaxSize().semantics {
                    contentDescription = profile.name
                    selected = active
                    val role = if (profile.child) "Child profile" else "Adult profile"
                    stateDescription = (if (active) "Active profile, " else "") + role + if (pinProtected) ", PIN protected" else ""
                }) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ProfileAvatar(profile, 72.dp) }
            }
            if (active) Box(Modifier.align(Alignment.BottomEnd).size(20.dp).background(FlickColors.Foreground, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, contentDescription = null, tint = FlickColors.FocusForeground, modifier = Modifier.size(14.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            Text(profile.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false))
            if (pinProtected) Icon(Icons.Default.Lock, contentDescription = null, tint = FlickColors.Secondary, modifier = Modifier.size(12.dp))
        }
    }
}
