package com.jagdeep.home.ui.components

import androidx.compose.ui.test.*
import com.jagdeep.ui.themes.AppTheme
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class CourseAutomationGuideUiTest {
    @Test
    fun `given course help then user can read setup intervention and stop instructions and dismiss it`() = runDesktopComposeUiTest {
        setContent { AppTheme { CourseAutomationGuide() } }
        onNodeWithText("Setup and how it works").performClick()
        onNodeWithText("Sagar Mein Yog automation guide").assertIsDisplayed()
        onNodeWithText("sign in yourself", substring = true).performScrollTo().assertIsDisplayed()
        onNodeWithText("Automatic: starting this routine", substring = true).performScrollTo().assertIsDisplayed()
        onNodeWithText("Unknown answer:", substring = true).performScrollTo().assertIsDisplayed()
        onNodeWithText("Stopping the app does not cancel", substring = true).performScrollTo().assertIsDisplayed()
        onNodeWithText("Got it").performClick()
        onNodeWithText("Sagar Mein Yog automation guide").assertDoesNotExist()
    }
}
