package com.jagdeep.settings.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
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
import com.jagdeep.shared.cosmos.CosmosAccount
import com.jagdeep.shared.cosmos.CosmosSignInResult
import com.jagdeep.shared.cosmos.CosmosSync
import com.jagdeep.shared.cosmos.CosmosSyncState
import com.jagdeep.shared.preferences.AppPreferences
import com.jagdeep.ui.themes.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalTestApi::class)
class CosmosSyncSectionUiTest {
    private val gate = object : InputAccessGate {
        override fun access() = InputAccess.Granted
        override fun requestAccess() = true
    }

    private class FakeSync(initial: CosmosSyncState) : CosmosSync {
        override val state = MutableStateFlow(initial)
        val signIns = mutableListOf<Pair<String, String>>()
        var syncs = 0
        /** When set, sign-in needs this authenticator code (TOTP_REQUIRED without it, TOTP_INVALID otherwise). */
        var requiredCode: String? = null
        val codes = mutableListOf<String?>()
        override suspend fun signIn(email: String, password: String, totpCode: String?): CosmosSignInResult {
            signIns += email to password
            codes += totpCode
            requiredCode?.let { expected ->
                if (totpCode == null) return CosmosSignInResult.TotpRequired
                if (totpCode != expected) return CosmosSignInResult.TotpInvalid
            }
            state.value = state.value.copy(account = CosmosAccount("Tester"))
            return CosmosSignInResult.SignedIn
        }
        override suspend fun signOut() { state.value = state.value.copy(account = null) }
        override fun syncNow() { syncs++ }
        override fun answersChanged() = Unit
    }

    @Test fun given_signed_out_when_the_user_signs_in_then_the_account_and_sync_controls_replace_the_form() = runDesktopComposeUiTest(1000, 2400) {
        val sync = FakeSync(CosmosSyncState(available = true, pendingUploads = 3))
        val vm = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, cosmosSync = sync)
        setContent { AppTheme(darkTheme = false) { SettingsScreen(vm, {}, "1.0.0") } }
        onNodeWithText("Sign in").performScrollTo().assertIsNotEnabled()
        onNodeWithText("Email").performTextInput("person@example.com")
        onNodeWithText("Password").performTextInput("test-password")
        // The password is masked on screen.
        onNodeWithText("•".repeat("test-password".length)).assertExists()
        onNodeWithText("Sign in").assertIsEnabled().performClick()
        waitForIdle()
        assertEquals(listOf("person@example.com" to "test-password"), sync.signIns)
        onNodeWithText("Signed in to Cosmos as Tester").performScrollTo().assertExists()
        onNodeWithText("3 confirmed answers are waiting to be sent.").assertExists()
        onNodeWithText("Sync now").performClick()
        assertEquals(1, sync.syncs)
        onNodeWithText("Sign out").performClick()
        waitForIdle()
        onNodeWithText("Email").performScrollTo().assertExists()
    }

    @Test fun given_the_admin_when_cosmos_asks_for_a_code_then_the_code_field_appears_and_signs_in() = runDesktopComposeUiTest(1000, 2400) {
        val sync = FakeSync(CosmosSyncState(available = true)).apply { requiredCode = "123456" }
        val vm = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, cosmosSync = sync)
        setContent { AppTheme(darkTheme = false) { SettingsScreen(vm, {}, "1.0.0") } }
        onNodeWithText("Email").performScrollTo().performTextInput("admin@example.com")
        onNodeWithText("Password").performTextInput("test-password")
        onNodeWithText("Sign in").performClick()
        waitForIdle()
        onNodeWithText("Authenticator code").performScrollTo().assertExists()
        onNodeWithText("Enter the code from your authenticator app. You can also use a recovery code.").assertExists()
        // The password stays (masked) for the second request; Verify waits for a code.
        onNodeWithText("•".repeat("test-password".length)).assertExists()
        onNodeWithText("Verify").assertIsNotEnabled()
        // Only letters, digits and dashes are typed into the field.
        onNodeWithText("Authenticator code").performTextInput("65 43#21")
        onNodeWithText("Verify").assertIsEnabled().performClick()
        waitForIdle()
        onNodeWithText("That code didn't work. Try the newest one.").performScrollTo().assertExists()
        onNodeWithText("Authenticator code").performTextInput("123456")
        onNodeWithText("Verify").performClick()
        waitForIdle()
        assertEquals(listOf(null, "654321", "123456"), sync.codes)
        assertEquals(List(3) { "admin@example.com" to "test-password" }, sync.signIns)
        onNodeWithText("Signed in to Cosmos as Tester").performScrollTo().assertExists()
    }

    @Test fun given_no_secure_storage_then_settings_explains_sync_is_unavailable() = runDesktopComposeUiTest(1000, 2400) {
        val vm = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, cosmosSync = FakeSync(CosmosSyncState(available = false)))
        setContent { AppTheme(darkTheme = false) { SettingsScreen(vm, {}, "1.0.0") } }
        onNodeWithText("Not available on this computer. Sync needs the system's secure storage to keep your session safe.").performScrollTo().assertExists()
        onNodeWithText("Email").assertDoesNotExist()
    }

    @Test fun given_run_logs_waiting_when_signed_in_then_settings_counts_them_and_opens_the_folder() = runDesktopComposeUiTest(1000, 2400) {
        var opened = 0
        val files = object : com.jagdeep.shared.diagnostics.RunLogFiles {
            override val isAvailable = true
            override suspend fun openFolder(): String? { opened++; return null }
        }
        val sync = FakeSync(CosmosSyncState(available = true, account = CosmosAccount("Tester"), runLogsWaiting = 3))
        val vm = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, cosmosSync = sync, runLogFiles = files)
        setContent { AppTheme(darkTheme = false) { SettingsScreen(vm, {}, "1.0.0") } }
        onNodeWithText("Run logs: 3 waiting to upload.").performScrollTo().assertExists()
        onNodeWithText("Open logs folder").performClick()
        waitForIdle()
        assertEquals(1, opened)
    }

    @Test fun given_a_newer_build_is_published_when_settings_opens_then_about_offers_it_with_install() = runDesktopComposeUiTest(1000, 2400) {
        var checks = 0
        val updates = object : com.jagdeep.shared.update.UpdateCheck {
            override val update = MutableStateFlow<com.jagdeep.shared.update.AppUpdate?>(com.jagdeep.shared.update.AppUpdate("1.1.1", 171, "https://cosmos.test/downloads"))
            override fun check() { checks++ }
        }
        val vm = SettingsViewModel(InMemoryPreferencesStore(AppPreferences()), gate, updateCheck = updates)
        setContent { AppTheme(darkTheme = false) { SettingsScreen(vm, {}, "1.1.0 (170 · abc1234)") } }
        onNodeWithText("Update available: 1.1.1 (171)").performScrollTo().assertExists()
        onNodeWithText("Install").assertExists()
        assertEquals(1, checks)
        updates.update.value = null
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Update available: 1.1.1 (171)").fetchSemanticsNodes().isEmpty() }
    }
}
