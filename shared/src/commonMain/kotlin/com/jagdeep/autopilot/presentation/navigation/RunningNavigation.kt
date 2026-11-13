package com.jagdeep.autopilot.presentation.navigation

import com.jagdeep.home.ui.navigation.RouteRunningRoutine
import com.jagdeep.ui.navigation.Navigator
import com.jagdeep.ui.navigation.RouteTopLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Observe the app-scoped runner, including starts from toolbar/hot keys on another page. */
internal suspend fun observeRunningNavigation(active: Flow<Boolean>, navigator: Navigator) {
    active.distinctUntilChanged().collect { running ->
        if (running) {
            navigator.navigate(RouteTopLevel.Home)
            val stack = navigator.navigationState.backStacks.getValue(RouteTopLevel.Home)
            // A previous run can leave its result page on the stack. Reuse that destination.
            val index = stack.indexOf(RouteRunningRoutine)
            if (index >= 0) while (stack.lastIndex > index) stack.removeLastOrNull()
            else navigator.navigate(RouteRunningRoutine)
        }
    }
}
