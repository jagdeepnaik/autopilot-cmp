package com.jagdeep.home.ui.components

import androidx.compose.ui.test.*
import com.jagdeep.home.domain.ProgressProfileRequest
import com.jagdeep.persistence.room.learning.LearningAccount
import com.jagdeep.ui.themes.AppTheme
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class CourseProgressUiTest {
    @Test fun `choose recent profile or create a separate profile without a website username`() = runDesktopComposeUiTest {
        var confirmed: String? = null
        val profiles=listOf(LearningAccount("sagarmeinyog:recent","Recent user",10),LearningAccount("sagarmeinyog:older","Older user",20))
        setContent { AppTheme { CourseProgressPanel(ProgressProfileRequest("token",profiles=profiles),null) { token, name -> assertEquals("token",token); confirmed=name } } }
        onNodeWithText("Choose progress profile").assertIsDisplayed()
        onNodeWithText("recent · Most recent").assertExists()
        runOnIdle { assertNull(confirmed) }
        onNodeWithText("Use this profile").performClick()
        runOnIdle { assertEquals("sagarmeinyog:recent",confirmed) }
        onNodeWithText("Older user").performClick()
        onNodeWithText("Use this profile").performClick()
        runOnIdle { assertEquals("sagarmeinyog:older",confirmed) }
        onNodeWithText("Create new profile").performClick()
        onNodeWithText("Use this profile").assertIsNotEnabled()
        onNodeWithText("Profile name").performTextInput("Separate person")
        onNodeWithText("Use this profile").performClick()
        runOnIdle { assertEquals("Separate person",confirmed) }
    }
}
