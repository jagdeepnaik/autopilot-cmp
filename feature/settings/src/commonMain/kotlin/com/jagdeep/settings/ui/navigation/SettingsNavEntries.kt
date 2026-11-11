package com.jagdeep.settings.ui.navigation

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.jagdeep.automation.InputAccessGate
import com.jagdeep.persistence.preferences.PreferencesStore
import com.jagdeep.settings.domain.SettingsViewModel
import com.jagdeep.settings.ui.screens.SettingsScreen
import com.jagdeep.shared.preferences.AppPreferences
import com.jagdeep.ui.navigation.Navigator
import com.jagdeep.ui.navigation.RouteTopLevel
import kotlinx.coroutines.flow.Flow

fun EntryProviderScope<NavKey>.settingsEntryProvider(
    navigator: Navigator,
    preferencesStore: PreferencesStore<AppPreferences>,
    accessGate: InputAccessGate,
    appVersion: String,
    browserConnected: Flow<Boolean>,
    onInstallExtension: suspend () -> String?,
    routineTransfer: com.jagdeep.shared.routines.RoutineTransfer = com.jagdeep.shared.routines.NoRoutineTransfer,
    displayWakeProblem: Flow<String?> = kotlinx.coroutines.flow.flowOf(null),
    answerBanks: com.jagdeep.shared.answers.AnswerBankFiles = com.jagdeep.shared.answers.NoAnswerBankFiles,
    extensionStatus: Flow<com.jagdeep.shared.browser.BrowserExtensionStatus> = kotlinx.coroutines.flow.flowOf(com.jagdeep.shared.browser.BrowserExtensionStatus()),
    onTestSound: suspend () -> String? = { "Sound testing isn't available." },
) {
    entry<RouteTopLevel.Settings> {
        val viewModel: SettingsViewModel = viewModel(factory = viewModelFactory {
            initializer { SettingsViewModel(preferencesStore, accessGate, browserConnected, onInstallExtension, routineTransfer, displayWakeProblem, answerBanks, extensionStatus, onTestSound) }
        })
        SettingsScreen(viewModel, onBack = navigator::goBack, appVersion = appVersion)
    }
}
