package com.jagdeep.autopilot.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.jagdeep.activity.ui.navigation.activityEntryProvider
import com.jagdeep.automation.InputAccessGate
import com.jagdeep.home.domain.ClickController
import com.jagdeep.home.domain.RoutineRepository
import com.jagdeep.home.ui.navigation.homeEntryProvider
import com.jagdeep.autopilot.installBrowserExtension
import com.jagdeep.persistence.preferences.PreferencesStore
import com.jagdeep.settings.ui.navigation.settingsEntryProvider
import com.jagdeep.shared.history.RunHistoryStore
import com.jagdeep.shared.live.LiveLog
import com.jagdeep.home.domain.RoutineFiles
import kotlinx.coroutines.flow.map
import com.jagdeep.shared.preferences.AppPreferences
import com.jagdeep.ui.navigation.NavigationState
import com.jagdeep.ui.navigation.Navigator
import com.jagdeep.ui.navigation.TopLevelPager
import com.jagdeep.ui.platform.isDesktopPlatform
import kotlinx.coroutines.flow.Flow

@Composable
fun AppNavHost(
    navigationState: NavigationState,
    navigator: Navigator,
    topLevelRoutes: List<NavKey>,
    preferencesStore: PreferencesStore<AppPreferences>,
    controller: ClickController,
    routineRepository: RoutineRepository,
    browserConnected: Flow<Boolean>,
    runHistory: RunHistoryStore,
    liveLog: LiveLog,
    routineFiles: RoutineFiles,
    accessGate: InputAccessGate,
    appVersion: String,
    now: () -> Long,
    newId: () -> String,
    appIcon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    displayWakeProblem: Flow<String?> = kotlinx.coroutines.flow.flowOf(null),
    answerBanks: com.jagdeep.shared.answers.AnswerBankFiles = com.jagdeep.shared.answers.NoAnswerBankFiles,
    extensionStatus: Flow<com.jagdeep.shared.browser.BrowserExtensionStatus> = kotlinx.coroutines.flow.flowOf(com.jagdeep.shared.browser.BrowserExtensionStatus()),
    onTestSound: suspend () -> String? = { "Sound testing isn't available." },
) {
    androidx.compose.runtime.LaunchedEffect(controller, navigator) {
        observeRunningNavigation(kotlinx.coroutines.flow.combine(controller.state, controller.page.state) { run, page ->
            run != com.jagdeep.home.domain.RunState.Idle || page.activity != null
        }, navigator)
    }
    val routineTransfer = androidx.compose.runtime.remember(routineRepository, routineFiles, controller) {
        com.jagdeep.home.domain.RoutineFileTransfer(routineRepository, routineFiles, newId) { controller.isActive }
    }
    val stacks = navigationState.rememberDecoratedStacks(entryProvider {
        homeEntryProvider(navigator, controller, routineRepository, newId, appIcon, routineFiles, answerBanks)
        activityEntryProvider(navigator, runHistory, now, liveLog, developerMode = preferencesStore.data.map { it.developerMode })
        settingsEntryProvider(navigator, preferencesStore, accessGate, appVersion, browserConnected, onInstallExtension = ::installBrowserExtension, routineTransfer = routineTransfer, displayWakeProblem = displayWakeProblem, answerBanks = answerBanks, extensionStatus = extensionStatus, onTestSound = onTestSound)
    })
    if (isDesktopPlatform()) {
        // Desktop navigates with the sidebar.
        NavDisplay(
            modifier = modifier,
            transitionSpec = { topLevelFadeTransition(topLevelRoutes) },
            popTransitionSpec = { topLevelFadeTransition(topLevelRoutes) },
            onBack = navigator::goBack,
            entries = navigationState.entriesInUse(stacks),
        )
    } else {
        // Phones: each top-level route is a page that swipes in with the finger.
        TopLevelPager(navigationState, navigator, topLevelRoutes, modifier) { route ->
            NavDisplay(
                transitionSpec = { slideForwardTransition() },
                popTransitionSpec = { slideBackTransition() },
                onBack = navigator::goBack,
                entries = stacks.getValue(route),
            )
        }
    }
}
