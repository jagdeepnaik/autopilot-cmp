package com.jagdeep.settings.ui.navigation

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.jagdeep.automation.InputAccessGate
import com.jagdeep.persistence.preferences.PreferencesStore
import com.jagdeep.settings.domain.CosmosAccountsViewModel
import com.jagdeep.settings.domain.CosmosMachinesViewModel
import com.jagdeep.settings.domain.SettingsViewModel
import com.jagdeep.settings.ui.screens.CosmosAccountsScreen
import com.jagdeep.settings.ui.screens.CosmosMachinesScreen
import com.jagdeep.settings.ui.screens.SettingsScreen
import com.jagdeep.shared.preferences.AppPreferences
import com.jagdeep.ui.navigation.Navigator
import com.jagdeep.ui.navigation.RouteFlow
import com.jagdeep.ui.navigation.RouteTopLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** The admin's Cosmos accounts page, opened from Settings. Owned by this feature. */
@Serializable
data object RouteCosmosAccounts : RouteFlow

/** The admin's Cosmos machines page (approve or revoke computers), opened from Settings. Owned by this feature. */
@Serializable
data object RouteCosmosMachines : RouteFlow

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
    cosmosSync: com.jagdeep.shared.cosmos.CosmosSync = com.jagdeep.shared.cosmos.NoCosmosSync,
    runLogFiles: com.jagdeep.shared.diagnostics.RunLogFiles = com.jagdeep.shared.diagnostics.NoRunLogFiles,
    updateCheck: com.jagdeep.shared.update.UpdateCheck = com.jagdeep.shared.update.NoUpdateCheck,
    /** Opens a link in the default browser (the one-time Cosmos sign-in link); false when it couldn't. */
    openBrowser: suspend (String) -> Boolean = { false },
) {
    entry<RouteTopLevel.Settings> {
        val viewModel: SettingsViewModel = viewModel(factory = viewModelFactory {
            initializer { SettingsViewModel(preferencesStore, accessGate, browserConnected, onInstallExtension, routineTransfer, displayWakeProblem, answerBanks, extensionStatus, onTestSound, cosmosSync, runLogFiles, updateCheck, openBrowser) }
        })
        SettingsScreen(viewModel, onBack = navigator::goBack, appVersion = appVersion,
            onOpenAccounts = { navigator.navigate(RouteCosmosAccounts) }, onOpenMachines = { navigator.navigate(RouteCosmosMachines) })
    }
    entry<RouteCosmosAccounts> {
        val viewModel: CosmosAccountsViewModel = viewModel(factory = viewModelFactory { initializer { CosmosAccountsViewModel(cosmosSync.admin) } })
        CosmosAccountsScreen(viewModel, onBack = navigator::goBack)
    }
    entry<RouteCosmosMachines> {
        val viewModel: CosmosMachinesViewModel = viewModel(factory = viewModelFactory { initializer { CosmosMachinesViewModel(cosmosSync.admin) } })
        CosmosMachinesScreen(viewModel, onBack = navigator::goBack)
    }
}
