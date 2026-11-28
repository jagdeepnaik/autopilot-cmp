package com.jagdeep.home.ui.util

import com.jagdeep.home.domain.RoutineKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutineKindTextTest {
    @Test
    fun `given hot keys when choosing a new routine then every kind is offered with its own description`() {
        assertEquals(RoutineKind.entries, availableKinds(floatingControls = false))
        RoutineKind.entries.forEach { assertEquals(it.description, it.descriptionFor(floatingControls = false)) }
    }

    @Test
    fun `given floating controls when choosing a new routine then only the tapping kinds are offered and say tap`() {
        assertEquals(listOf(RoutineKind.Click, RoutineKind.Sequence), availableKinds(floatingControls = true))
        availableKinds(floatingControls = true).forEach { assertTrue(it.descriptionFor(floatingControls = true).startsWith("Taps")) }
    }
}
