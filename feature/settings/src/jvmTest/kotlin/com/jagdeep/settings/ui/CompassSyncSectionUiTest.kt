package com.jagdeep.settings.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.InputAccessGate
import com.jagdeep.persistence.preferences.InMemoryPreferencesStore
import com.jagdeep.settings.domain.SettingsViewModel
import com.jagdeep.settings.ui.screens.SettingsScreen
import com.jagdeep.shared.compass.CompassAccount
import com.jagdeep.shared.compass.CompassSignInResult
import com.jagdeep.shared.compass.CompassSync
import com.jagdeep.shared.compass.CompassSyncState
import com.jagdeep.shared.preferences.AppPreferences
import com.jagdeep.ui.themes.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalTestApi::class)
class CompassSyncSectionUiTest {
    private val gate = object : InputAccessGate {
        override fun access() = InputAccess.Granted
        override fun requestAccess() = true
    }

    private class FakeSync(initial: CompassSyncState) : CompassSync {
        override val state = MutableStateFlow(initial)
        val signIns = mutableListOf<Pair<String, String>>()
        var syncs = 0
        override suspend fun signIn(email: String, password: String): CompassSignInResult {
            signIns += email to password
            state.value = state.value.copy(account = CompassAccount("Tester"))
            return CompassSignInResult.SignedIn
        }
        override suspend fun signOut() { state.value = state.value.copy(account = null) }
        override fun syncNow() { syncs++ }
        override fun answersChanged() = Unit
    }

    @Test fun given_signed_out_when_the_user_signs_in_then_the_account_and_sync_controls_replace_the_form() = runDesktopComposeUiTest(1000, 2400) {
        val sync = FakeSync(CompassSyncState(available = true, pendingUploads = 3))
        val vm = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, compassSync = sync)
        setContent { AppTheme(darkTheme = false) { SettingsScreen(vm, {}, "1.0.0") } }
        onNodeWithText("Sign in").performScrollTo().assertIsNotEnabled()
        onNodeWithText("Email").performTextInput("person@example.com")
        onNodeWithText("Password").performTextInput("test-password")
        // The password is masked on screen.
        onNodeWithText("•".repeat("test-password".length)).assertExists()
        onNodeWithText("Sign in").assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(listOf("person@example.com" to "test-password"), sync.signIns)
        onNodeWithText("Signed in to Compass as Tester").performScrollTo().assertExists()
        onNodeWithText("3 confirmed answers are waiting to be sent.").assertExists()
        onNodeWithText("Sync now").performClick()
        assertEquals(1, sync.syncs)
        onNodeWithText("Sign out").performClick()
        waitForIdle()
        onNodeWithText("Email").performScrollTo().assertExists()
    }

    @Test fun given_no_secure_storage_then_settings_explains_sync_is_unavailable() = runDesktopComposeUiTest(1000, 2400) {
        val vm = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, compassSync = FakeSync(CompassSyncState(available = false)))
        setContent { AppTheme(darkTheme = false) { SettingsScreen(vm, {}, "1.0.0") } }
        onNodeWithText("Not available on this computer. Sync needs the system's secure storage to keep your session safe.").performScrollTo().assertExists()
        onNodeWithText("Email").assertDoesNotExist()
    }
}
