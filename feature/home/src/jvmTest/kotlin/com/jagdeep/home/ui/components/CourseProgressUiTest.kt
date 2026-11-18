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
        onNodeWithText("Confirm progress profile").assertIsDisplayed()
        onNodeWithText("Saved profile: Recent user").assertExists()
        runOnIdle { assertNull(confirmed) }
        onNodeWithText("Use this profile").performClick()
        runOnIdle { assertEquals("sagarmeinyog:recent",confirmed) }
        onNodeWithText("Saved profile: Recent user").performClick()
        onNodeWithText("Older user").performClick()
        onNodeWithText("Use this profile").performClick()
        runOnIdle { assertEquals("sagarmeinyog:older",confirmed) }
        onNodeWithText("Enter a new profile").performClick()
        onNodeWithText("Use this profile").assertIsNotEnabled()
        onNodeWithText("Profile name").performTextInput("Separate person")
        onNodeWithText("Use this profile").performClick()
        runOnIdle { assertEquals("Separate person",confirmed) }
    }

    @Test fun `detected signed in account is offered before manual entry`() = runDesktopComposeUiTest {
        var confirmed: String? = null
        setContent { AppTheme {
            CourseProgressPanel(ProgressProfileRequest("token", detectedProfileName = "Jagdeep Naik"), null) { _, name -> confirmed = name }
        } }
        onNodeWithText("Signed-in SMY account detected").assertIsDisplayed()
        onNodeWithText("Jagdeep Naik").assertIsDisplayed()
        onNodeWithText("Use this profile").performClick()
        runOnIdle { assertEquals("Jagdeep Naik", confirmed) }
    }
}
