package com.jagdeep.settings.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.jagdeep.automation.HotKey
import com.jagdeep.automation.InputAccess
import com.jagdeep.settings.domain.SettingsViewModel
import com.jagdeep.shared.preferences.ColorPalette
import com.jagdeep.shared.preferences.ThemeMode
import com.jagdeep.ui.components.AppBarTop
import com.jagdeep.ui.components.LocalToastManager
import com.jagdeep.ui.components.LocalTopAppBarManager
import com.jagdeep.ui.components.TopAppBarState
import com.jagdeep.ui.navigation.RouteTopLevel
import com.jagdeep.ui.platform.isDesktopPlatform
import com.jagdeep.ui.themes.Palette
import com.jagdeep.ui.themes.previewColors

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    appVersion: String,
) {
    val state by viewModel.state.collectAsState()
    val topBarManager = LocalTopAppBarManager.current
    val toast = LocalToastManager.current
    SideEffect {
        topBarManager.value = TopAppBarState(RouteTopLevel.Settings) { AppBarTop("Settings", RouteTopLevel.Settings, onBack, {}, {}) }
    }
    LaunchedEffect(viewModel) {
        viewModel.refreshAccess()
        viewModel.messages.collect { toast.show(it) }
    }
    val preferences = state.preferences

    var setupOpen by remember { mutableStateOf(false) }
    var shortcutsOpen by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(false) }
    val desktop = isDesktopPlatform()
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopStart) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(vertical = 8.dp)) {
            Group("Automation", first = true)
            if (desktop) SwitchRow(
                title = "Keep the display on while running",
                supporting = state.displayWakeProblem ?: "Prevents idle display sleep during a run. Normal sleep resumes when it stops.",
                checked = preferences.keepDisplayOn,
                onChange = viewModel::updateKeepDisplayOn,
            )
            SwitchRow(
                title = "Assessment sounds",
                supporting = "Repeats every 30 seconds when an answer is needed. A different sound signals a failed assessment.",
                checked = preferences.assessmentSoundsEnabled,
                onChange = viewModel::updateAssessmentSounds,
            )

            Group("Safety")
            SwitchRow("Stop when I move the pointer", "For routines that click fixed screen positions.", preferences.stopWhenPointerMoves, viewModel::updateStopWhenPointerMoves)
            SwitchRow("Stop at a screen corner", "Move the pointer to any screen corner to stop the routine.", preferences.stopAtScreenCorner, viewModel::updateStopAtScreenCorner)

            if (state.routineFilesAvailable) {
                Group("Routine files")
                com.jagdeep.settings.ui.components.RoutineTransferControls(
                    state.transferableRoutines, state.transferringRoutines, viewModel::importRoutines, viewModel::exportRoutines,
                )
            }
            state.answerBankFolder?.let { folder ->
                ListItem(modifier=Modifier.clickable(role=Role.Button) { viewModel.openAnswerBankFolder() },colors=transparent(),
                    headlineContent={ Text("Answer-bank folder") },supportingContent={ Text("$folder\nChoose a named JSON file in each routine's options.") },
                    trailingContent={ Icon(Icons.Default.ChevronRight,null) })
            }

            if (state.access != InputAccess.Unsupported) {
                Group("Controls")
                ListItem(modifier = Modifier.clickable(role = Role.Button, onClickLabel = "Open keyboard shortcuts") { shortcutsOpen = true },
                    colors = transparent(), headlineContent = { Text("Keyboard shortcuts") },
                    supportingContent = { Text("${preferences.toggleHotKey.name} starts or stops a routine. Choose keys for other actions.") },
                    trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) })
            }

            if (desktop) {
                Group("Browser connection")
                ListItem(colors = transparent(), headlineContent = { Text("Chrome extension") },
                    supportingContent = { Text(if(state.browserConnected) "Connected to AutoPilot on this computer." else "Not connected. Set up the extension to use browser routines.") },
                    trailingContent = { TextButton(onClick = { setupOpen = true }) { Text("Set up extension") } })
            }

            if (desktop) {
                Group("Permissions")
                ListItem(colors = transparent(), headlineContent = { Text("Control other apps") },
                    supportingContent = { Text(when(state.access) {
                        InputAccess.Granted -> "Allowed. AutoPilot can send clicks to other apps."
                        InputAccess.NeedsPermission -> "Allow AutoPilot in your system’s Accessibility settings to click in other apps."
                        InputAccess.Unsupported -> "Direct clicking is unavailable on this desktop. Browser routines use the extension."
                    }) },
                    trailingContent = if(state.access == InputAccess.NeedsPermission) ({ TextButton(onClick = viewModel::requestAccess) { Text("Open settings") } }) else null)
            }

            Group("Advanced")
            ListItem(modifier = Modifier.clickable(role = Role.Button, onClickLabel = if(advancedOpen) "Hide developer options" else "Show developer options") { advancedOpen = !advancedOpen },
                colors = transparent(), headlineContent = { Text("Developer options") },
                supportingContent = { Text(if(preferences.developerMode) "Live activity logging is on." else "Diagnostics and live activity logging.") },
                trailingContent = { Icon(if(advancedOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null) })
            if (advancedOpen) SwitchRow("Developer mode", "Shows live activity details in Activity. Logs stay in memory for this session.", preferences.developerMode, viewModel::updateDeveloperMode)

            Group("Appearance")
            ThemeModeRow(preferences.themeMode, viewModel::updateThemeMode)
            ColorPaletteRow(preferences.colorPalette, viewModel::updateColorPalette)

            Group("About")
            ListItem(colors = transparent(), headlineContent = { Text("Version") }, trailingContent = { Text(appVersion) })
            ListItem(colors = transparent(), headlineContent = { Text("Privacy") }, supportingContent = {
                Text("Settings, routines and saved progress stay on this computer. Browser actions happen on the website you open. Only shortcut keys are monitored; typed text is not recorded.")
            })
        }
    }
    if (setupOpen) AlertDialog(onDismissRequest = { setupOpen = false }, title = { Text("Chrome extension setup") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("1. Choose Show extension folder below.")
            Text("2. Open chrome://extensions in Chrome and enable Developer mode.")
            Text("3. Choose Load unpacked and select that folder.")
            Text("After an update, reload AutoPilot Page Helper. Refresh a course page only when no timed assessment is active.", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { setupOpen = false }) { Text("Done") } },
        dismissButton = { TextButton(onClick = viewModel::showExtension) { Text("Show extension folder") } })
    if (shortcutsOpen) AlertDialog(onDismissRequest = { shortcutsOpen = false }, title = { Text("Keyboard shortcuts") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("On a Mac, you may need to hold fn with the function key.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            HotKeyRow("Start and stop", "Start the selected routine or stop the current run.", preferences.toggleHotKey, viewModel::updateToggleHotKey)
            HotKeyRow("Pick a point", "Record the pointer position while editing a routine.", preferences.captureHotKey, viewModel::updateCaptureHotKey)
            HotKeyRow("Stop scroll and click", "Stop a running scroll-and-click routine.", preferences.pageScrollHotKey, viewModel::updatePageScrollHotKey)
        }
    }, confirmButton = { TextButton(onClick = { shortcutsOpen = false }) { Text("Done") } })
}

@Composable
private fun Group(title: String, first: Boolean = false) {
    // Inset so section dividers never meet the navigation divider.
    if (!first) HorizontalDivider(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun SwitchRow(title: String, supporting: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        colors = transparent(),
        headlineContent = { Text(title) },
        supportingContent = { Text(supporting) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

@Composable
private fun HotKeyRow(title: String, supporting: String, selected: HotKey, onSelect: (HotKey) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.clickable(onClickLabel = "Choose key for $title", role = Role.DropdownList) { expanded = true },
            colors = transparent(),
            headlineContent = { Text(title) },
            supportingContent = { Text(supporting) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(selected.name)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, offset = DpOffset(16.dp, 0.dp)) {
            HotKey.entries.forEach { key ->
                DropdownMenuItem(
                    text = { Text(key.name) },
                    trailingIcon = if (key == selected) ({ Icon(Icons.Default.Check, contentDescription = "Selected") }) else null,
                    onClick = {
                        onSelect(key)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun transparent() = ListItemDefaults.colors(containerColor = Color.Transparent)

@Composable
private fun ColorPaletteRow(selected: ColorPalette, onSelect: (ColorPalette) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    Box {
        ListItem(
            modifier = Modifier.clickable(onClickLabel = "Choose colors", role = Role.DropdownList) { expanded = true },
            colors = transparent(),
            headlineContent = { Text("Colors") },
            supportingContent = { Text(selected.label) },
            leadingContent = { PaletteSwatch(selected, dark) },
            trailingContent = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, offset = DpOffset(16.dp, 0.dp)) {
            ColorPalette.entries.forEach { palette ->
                DropdownMenuItem(
                    text = { Text(palette.label) },
                    leadingIcon = { PaletteSwatch(palette, dark) },
                    trailingIcon = if (palette == selected) ({ Icon(Icons.Default.Check, contentDescription = "Selected") }) else null,
                    onClick = {
                        onSelect(palette)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Two halves: the palette's action colour and its live colour. */
@Composable
private fun PaletteSwatch(palette: ColorPalette, dark: Boolean) {
    val (action, live) = Palette.valueOf(palette.name).previewColors(dark)
    Row(Modifier.size(24.dp).clip(CircleShape)) {
        Box(Modifier.weight(1f).fillMaxHeight().background(action))
        Box(Modifier.weight(1f).fillMaxHeight().background(live))
    }
}

@Composable
private fun ThemeModeRow(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ListItem(
            modifier = Modifier.clickable(onClickLabel = "Choose theme", role = Role.DropdownList) { expanded = true },
            colors = transparent(),
            headlineContent = { Text("Theme") },
            supportingContent = { Text(selected.label) },
            leadingContent = { Icon(selected.icon(), contentDescription = null) },
            trailingContent = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, offset = DpOffset(16.dp, 0.dp)) {
            ThemeMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label) },
                    leadingIcon = { Icon(mode.icon(), contentDescription = null) },
                    trailingIcon = if (mode == selected) ({ Icon(Icons.Default.Check, contentDescription = "Selected") }) else null,
                    onClick = {
                        onSelect(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun ThemeMode.icon() = when (this) {
    ThemeMode.System -> Icons.Default.BrightnessAuto
    ThemeMode.Light -> Icons.Default.LightMode
    ThemeMode.Dark -> Icons.Default.DarkMode
}
