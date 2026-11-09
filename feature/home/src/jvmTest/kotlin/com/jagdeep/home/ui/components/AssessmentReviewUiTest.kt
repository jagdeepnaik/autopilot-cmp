package com.jagdeep.home.ui.components

import androidx.compose.ui.test.*
import com.jagdeep.home.domain.AssessmentFailureRequest
import com.jagdeep.home.domain.AssessmentReviewQuestion
import com.jagdeep.ui.themes.AppTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class AssessmentReviewUiTest {
    @Test
    fun `given a reviewed correction then only approval saves it`() = runDesktopComposeUiTest {
        var saved: Map<String, String>? = null
        val failure = AssessmentFailureRequest("failure", "Module", true,
            listOf(AssessmentReviewQuestion("q", "Which answer?", listOf("First", "Second"), "First", "First")))
        setContent { AppTheme { AssessmentReview(failure, false, null) { token, answers ->
            assertEquals("failure", token)
            saved = answers
        } } }
        onNodeWithText("Review and correct answers").performClick()
        onNodeWithText("Second").performClick()
        onNodeWithText("Review changes").performClick()
        runOnIdle { assertNull(saved) }
        onNodeWithText("Approve and save").performClick()
        runOnIdle { assertEquals(mapOf("q" to "Second"), saved) }
    }
}
