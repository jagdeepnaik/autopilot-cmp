package com.jagdeep.home.ui.components

import androidx.compose.ui.test.*
import com.jagdeep.home.domain.DgsCourseChoice
import com.jagdeep.ui.themes.AppTheme
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class DgsCourseDialogTest {
    @Test fun `numbered courses require an available selection before opening`() = runDesktopComposeUiTest(1000,800) {
        var selected: String?=null
        setContent { AppTheme { DgsCourseDialog(listOf(DgsCourseChoice("a","Example course","18",true),DgsCourseChoice("b","Old course","20",false)),{selected=it},{}) } }
        onNodeWithText("Choose your DGS course").assertIsDisplayed()
        onNodeWithText("Open course").assertIsNotEnabled()
        onNodeWithText("2. Old course").assertDoesNotExist()
        onNodeWithText("1. Example course").performClick()
        onNodeWithText("Open course").performClick()
        runOnIdle {assertEquals("a",selected)}
    }
}
