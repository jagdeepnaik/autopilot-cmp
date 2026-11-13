package com.jagdeep.autopilot.presentation.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Empty sidebar space changes the cursor, never draws another icon. Click toggles width. */
@Composable
internal fun SidebarToggleArea(expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val label = if (expanded) "Collapse sidebar" else "Expand sidebar"
    Box(modifier.fillMaxWidth().testTag("Sidebar toggle area")
        .clickable(indication = null, interactionSource = null, role = Role.Button, onClick = onToggle)
        .pointerHoverIcon(sidebarResizePointerIcon(), overrideDescendants = true)
        .semantics { contentDescription = label })
}

internal expect fun sidebarResizePointerIcon(): PointerIcon
