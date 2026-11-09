package com.jagdeep.home.ui.components

import androidx.compose.ui.test.*
import com.jagdeep.home.domain.ProgressProfileRequest
import com.jagdeep.ui.themes.AppTheme
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class CourseProgressUiTest {
    @Test fun `given displayed user then profile confirmation is explicit and editable`() = runDesktopComposeUiTest {
        var confirmed: String? = null
        setContent { AppTheme { CourseProgressPanel(ProgressProfileRequest("token","Same name"),null) { token, name -> assertEquals("token",token); confirmed=name } } }
        onNodeWithText("Confirm progress profile").assertIsDisplayed()
        runOnIdle { assertNull(confirmed) }
        onNodeWithText("Local profile name").performTextClearance()
        onNodeWithText("Local profile name").performTextInput("Same name second user")
        onNodeWithText("Use this profile").performClick()
        runOnIdle { assertEquals("Same name second user",confirmed) }
    }
}
