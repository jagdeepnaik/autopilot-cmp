package com.jagdeep.autopilot.presentation.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.jagdeep.home.ui.navigation.RouteRunningRoutine
import com.jagdeep.home.ui.navigation.RouteRoutineEditor
import com.jagdeep.ui.navigation.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RunningNavigationTest {
    @Test fun `given start on another page then open running destination once preserve drafts and keep summary on stop`() = runTest {
        val home = NavBackStack<NavKey>(RouteTopLevel.Home, RouteRoutineEditor("draft"))
        val navigation = NavigationState(RouteTopLevel.Home,mutableStateOf<NavKey>(RouteTopLevel.Settings),mapOf(
            RouteTopLevel.Home to home,RouteTopLevel.Settings to NavBackStack<NavKey>(RouteTopLevel.Settings)))
        val navigator=Navigator(navigation)
        val active=MutableStateFlow(false)
        backgroundScope.launch { observeRunningNavigation(active,navigator) }
        runCurrent(); assertEquals(RouteTopLevel.Settings,navigation.topLevelRoute)
        active.value=true; runCurrent()
        assertEquals(RouteTopLevel.Home,navigation.topLevelRoute)
        assertEquals(listOf(RouteTopLevel.Home,RouteRoutineEditor("draft"),RouteRunningRoutine),home.toList())
        // Back does not change the app-scoped runner or immediately force navigation again.
        navigator.goBack(); runCurrent(); assertEquals(RouteRoutineEditor("draft"),home.last())
        active.value=false; runCurrent(); active.value=true; runCurrent()
        assertEquals(RouteRunningRoutine,home.last())
        active.value=false; runCurrent(); assertEquals(RouteRunningRoutine,home.last())
        navigator.navigate(RouteTopLevel.Settings); active.value=true; runCurrent()
        assertEquals(1,home.count { it==RouteRunningRoutine })
    }
}
