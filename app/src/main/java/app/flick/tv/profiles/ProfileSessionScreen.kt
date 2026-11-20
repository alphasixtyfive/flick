package app.flick.tv.profiles

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import app.flick.tv.home.HomeActions
import app.flick.tv.home.HomeState
import app.flick.tv.ui.components.TvMessage
import app.flick.tv.ui.components.navigationFeedback

/** No launcher content or controls exist behind the startup picker. Back returns here from the PIN. */
@Composable
internal fun ProfileSessionScreen(state: HomeState, actions: HomeActions) {
    var selected by remember(state.profileSession.generation) { mutableStateOf<Profile?>(null) }
    BackHandler { actions.cancelProfileSelection(); selected = null }
    Box(Modifier.fillMaxSize().navigationFeedback()) {
        val target = selected?.let { chosen -> state.settings.profiles.find { it.id == chosen.id } }
        if (target == null) ProfileChooser(state.settings.profiles, state.settings.activeProfileId,
            showActive = false, onChoose = { profile ->
                actions.dismissMessage()
                actions.clearProfileError()
                if (!profile.child && profile.pinHash != null) selected = profile
                else actions.chooseProfile(profile, "") { result ->
                    result.fold(onSuccess = { actions.dismissMessage() }, onFailure = {
                        actions.showMessage(it.message?.take(160) ?: "This profile could not be opened")
                    })
                }
            })
        else {
            ProfilePickerBackdrop()
            ProfilePin(target, state.profileError.takeIf { state.profileErrorTarget == target.id },
                onBack = { actions.cancelProfileSelection(); selected = null; actions.clearProfileError() }, onSuccess = {},
                onConfirm = { pin, complete -> actions.chooseProfile(target, pin, complete) })
        }
        TvMessage(state.message, actions.dismissMessage)
    }
}
