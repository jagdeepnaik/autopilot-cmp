package com.jagdeep.home.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Surface
import com.jagdeep.home.ui.util.waitLabel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.jagdeep.automation.HotKey
import com.jagdeep.automation.PointerButton
import com.jagdeep.home.domain.IntervalUnit
import com.jagdeep.home.domain.LimitMode
import com.jagdeep.home.domain.PointCapture
import com.jagdeep.home.domain.PointDraft
import com.jagdeep.home.domain.RoutineDraft
import com.jagdeep.home.domain.RoutineEditorUiState
import com.jagdeep.home.domain.RoutineEditorViewModel
import com.jagdeep.home.domain.RoutineKind
import com.jagdeep.home.domain.MAX_PIXELS_PER_SECOND
import com.jagdeep.home.domain.MIN_PIXELS_PER_SECOND
import com.jagdeep.home.domain.TargetMode
import com.jagdeep.home.domain.defaultStepName
import com.jagdeep.home.ui.navigation.RouteRoutineEditor
import com.jagdeep.ui.components.AppBarTop
import com.jagdeep.ui.components.LocalTopAppBarManager
import com.jagdeep.ui.components.TopAppBarState

/** Where and how often to click. The draft lives in the ViewModel until Save. */
@Composable
fun RoutineEditorScreen(viewModel: RoutineEditorViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val topBarManager = LocalTopAppBarManager.current
    val title = if (state.isNew) "New ${state.draft.kind.label.lowercase()}" else "Edit ${state.draft.kind.label.lowercase()}"
    SideEffect {
        topBarManager.value = TopAppBarState(null) { AppBarTop(title, RouteRoutineEditor(), onBack, {}, {}) }
    }
    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val draft = state.draft
    val errors = state.errors
    val update = viewModel::updateDraft
    Column(Modifier.fillMaxSize()) {
    Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopStart) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(vertical = 8.dp)) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { name -> update { it.copy(name = name) } },
                label = { Text("Name") },
                singleLine = true,
                isError = errors.name != null,
                supportingText = errors.name?.let { message -> { Text(message) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )

            when (draft.kind) {
                RoutineKind.Click -> ClickSections(state, viewModel)
                RoutineKind.Sequence -> SequenceSections(state, viewModel)
                RoutineKind.ScrollClick -> ScrollClickSections(state, viewModel)
                RoutineKind.DgsELearning -> {
                    Group("Study time")
                    NumberField("Required course hours",draft.requiredHours,errors.studyHours,{ value -> update { it.copy(requiredHours=value) } },Modifier.fillMaxWidth().padding(horizontal=16.dp))
                    NumberField("Hours already credited by DGS",draft.creditedHours,errors.studyHours,{ value -> update { it.copy(creditedHours=value) } },Modifier.fillMaxWidth().padding(horizontal=16.dp))
                    com.jagdeep.home.ui.components.CourseProgressHistory(state.progressAccounts.filter {it.key.startsWith("dgs:")},state.savedProgress,emptyList(),state.progressHistoryError,viewModel::loadProgressHistory)
                    Text("Use the credited hours for the account and course you will select. The first run saves this baseline per user and course. Later runs resume the saved total and baseline. DGS decides official credit.",Modifier.padding(16.dp),style=MaterialTheme.typography.bodySmall)
                    Group("Reading and videos")
                    ChoiceSetting("Scroll speed",draft.pixelsPerSecond,listOf(Triple(30,"Slow",null),Triple(60,"Normal",null),Triple(100,"Faster",null))) { speed -> update { it.copy(pixelsPerSecond=speed) } }
                    ChoiceSetting("Playback speed",draft.playbackSpeed,listOf(Triple(1.0,"1×","Default"),Triple(1.5,"1.5×",null),Triple(2.0,"2×","When the player supports it. Study hours still use real time."))) { speed -> update { it.copy(playbackSpeed=speed) } }
                    NumberField("Wait at page bottom (seconds)",draft.waitAfterClickSeconds,errors.waitAfterClick,{ value -> update { it.copy(waitAfterClickSeconds=value) } },Modifier.fillMaxWidth().padding(horizontal=16.dp))
                    Group("Assessment answer bank · reserved")
                    Text("Assessments are manual for now. You can prepare a separate JSON bank here for a future update.",Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall)
                    AnswerBankOptions(state,viewModel)
                    Group("Before starting")
                    Text("Sign in at https://dgsstcwlms.aduacademy.in/form/studenthome in a single Chrome tab. Open the course and E-BOOK yourself, then start the routine. Keep Chrome in front while reading. The routine slowly scrolls, waits for videos to finish, then uses Next. It reads forward from the page you opened. At the last page, it returns to the first page and repeats while study time remains. Each page gets at least 30 seconds. Login, facial verification, unavailable videos and unchanged navigation pause the routine for you. Stop at any time.",Modifier.padding(16.dp),style=MaterialTheme.typography.bodyMedium)
                }
                RoutineKind.CourseAutomation -> {
                    Group("Run preferences")
                    ChoiceSetting("Learning order",draft.learningOrder,listOf(
                        Triple(com.jagdeep.home.domain.LearningOrder.AssessmentAfterModule,"Assessment after each module","Finish a module's videos, then handle its unfinished assessment."),
                        Triple(com.jagdeep.home.domain.LearningOrder.VideosFirst,"Watch all videos first","Finish all videos, then ask for confirmation before the assessment pass."),
                    )) { order -> update { it.copy(learningOrder=order,answerAssessments=it.answerAssessments || order==com.jagdeep.home.domain.LearningOrder.VideosFirst) } }
                    ChoiceSetting("Playback speed",draft.playbackSpeed,listOf(
                        Triple(1.0,"1×","Normal speed · default for new routines"),Triple(1.5,"1.5×",null),Triple(2.0,"2×",null),
                    )) { speed -> update { it.copy(playbackSpeed=speed) } }
                    NumberField(
                        label = "Wait after every click (seconds)",
                        value = draft.waitAfterClickSeconds,
                        error = errors.waitAfterClick,
                        onChange = { value -> update { it.copy(waitAfterClickSeconds = value) } },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                    Group("Assessment")
                    ListItem(
                        headlineContent = { Text("Answer chapter quizzes") },
                        supportingContent = { Text("After the videos for a chapter finish, open and answer its quiz using the selected answer mode and bank.") },
                        trailingContent = { Switch(checked = draft.answerQuizzes, onCheckedChange = { enabled -> update { it.copy(answerQuizzes = enabled) } }) },
                    )
                    ListItem(
                        headlineContent = { Text("Handle module assessments") },
                        supportingContent = { Text("Skip assessments already passed in Your Progress. Unknown answers always pause for manual help.") },
                        trailingContent = { Switch(checked = draft.answerAssessments, onCheckedChange = { enabled -> update { it.copy(answerAssessments = enabled) } }) },
                    )
                    ListItem(
                        headlineContent = { Text("Download certificates automatically") },
                        supportingContent = { Text("After a pass, open and download the certificate, verify it in the profile folder, then continue. If anything stalls, ask you to download and confirm manually. Disabled by default.") },
                        trailingContent = { Switch(checked = draft.autoDownloadCertificates, onCheckedChange = { enabled -> update { it.copy(autoDownloadCertificates = enabled) } }) },
                    )
                    if (draft.answerAssessments) {
                        val modes = listOf(
                            Triple(com.jagdeep.home.domain.AssessmentMode.Manual, "Manual", "Start, answer, navigate questions and submit yourself in Chrome."),
                            Triple(com.jagdeep.home.domain.AssessmentMode.Review, "Review approvals", "Start and answer using saved matches. Ask in a popup before final submission."),
                            Triple(com.jagdeep.home.domain.AssessmentMode.Automatic, "Automatic", "Automatically start, answer and submit unfinished assessments. Videos first still asks once before the assessment pass. Unknown questions pause; failed retries need your choice."),
                        )
                        ChoiceSetting("Assessment mode",draft.assessmentMode,modes) { mode -> update { it.copy(assessmentMode=mode) } }
                    }
                    AnswerBankOptions(state,viewModel)
                    Group("Help and history")
                    Text("Sign in to Sagar Mein Yog in Chrome and leave the course tab in front while running. Choose Save to apply changes.", modifier = Modifier.padding(horizontal = 16.dp),style=MaterialTheme.typography.bodySmall)
                    com.jagdeep.home.ui.components.CourseAutomationGuide()
                    com.jagdeep.home.ui.components.CourseProgressHistory(
                        state.progressAccounts, state.savedProgress, state.savedProgressProfiles, state.progressHistoryError, viewModel::loadProgressHistory, viewModel::loadAllProgressHistory,
                    )
                }
            }

        }
    }
            Row(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = viewModel::save) { Text("Save") }
                TextButton(onClick = onBack) { Text("Cancel") }
            }
    }
}

@Composable
private fun <T> ChoiceSetting(title: String, selected: T, options: List<Triple<T,String,String?>>, onSelect: (T) -> Unit) {
    Text(title,Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.titleSmall)
    Column(Modifier.selectableGroup()) {
        options.forEach { (value,label,description) -> Choice(label,description,selected==value) { onSelect(value) } }
    }
}

@Composable
private fun AnswerBankOptions(state: RoutineEditorUiState, viewModel: RoutineEditorViewModel) {
    var namesOpen by remember { mutableStateOf(false) }
    Group("Answer bank")
    if (state.answerBankFolder != null) TextButton(onClick=viewModel::openAnswerBankFolder,enabled=!state.answerBankBusy,
        modifier=Modifier.padding(horizontal=8.dp)) {
        Icon(Icons.Default.FolderOpen,null,Modifier.size(18.dp))
        Text("Open Q&A folder",Modifier.padding(start=8.dp))
    }
        Column(Modifier.padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value=state.draft.answerBankFile,onValueChange={name->viewModel.updateDraft{it.copy(answerBankFile=name)}},
                label={Text("File name")},singleLine=true,isError=state.errors.answerBankFile!=null,
                supportingText={Text(state.errors.answerBankFile ?: "Use name-ver-1.0.json, for example sagarmeinyog-ver-1.0.json. Increase the version for updates; save the routine to apply it.")})
            state.answerBankFolder?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
            Text(if(state.draft.kind==RoutineKind.DgsELearning) "Reserved for future DGS assessment support. This routine does not read or answer assessments." else "Files are validated and loaded at the start of each run. Changing a file does not alter an active assessment.",style=MaterialTheme.typography.bodySmall)
            if(state.answerBankFolder!=null) {
                Box {
                    TextButton(onClick={namesOpen=true},enabled=!state.answerBankBusy) { Text("Choose saved file") }
                    DropdownMenu(expanded=namesOpen,onDismissRequest={namesOpen=false}) {
                        state.answerBankNames.forEach { name -> DropdownMenuItem(text={Text(name)},onClick={viewModel.updateDraft{it.copy(answerBankFile=name)};namesOpen=false}) }
                        if(state.answerBankNames.isEmpty()) DropdownMenuItem(text={Text("No JSON files yet")},onClick={},enabled=false)
                    }
                }
                TextButton(onClick=viewModel::importAnswerBank,enabled=!state.answerBankBusy) { Text("Import JSON with this name") }
                TextButton(onClick=viewModel::validateAnswerBank,enabled=!state.answerBankBusy) { Text("Validate and reload list") }
            }
            state.answerBankMessage?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
            if(state.answerBankBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
}

@Composable
private fun ClickSections(state: RoutineEditorUiState, viewModel: RoutineEditorViewModel) {
    val draft = state.draft
    val errors = state.errors
    val update = viewModel::updateDraft
    Group("What to click with")
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ButtonChoice(draft.button) { button -> update { it.copy(button = button) } }
    }
    DoubleClickRow(draft, update)

    Group("How often")
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        NumberField(
            label = "Every",
            value = draft.interval,
            error = errors.interval,
            onChange = { value -> update { it.copy(interval = value) } },
            modifier = Modifier.weight(1f),
        )
        IntervalUnitMenu(draft.intervalUnit, { unit -> update { it.copy(intervalUnit = unit) } }, Modifier.weight(1f))
    }
    RandomExtraField(draft, errors.randomExtra, "Adds up to this much to each wait. 0 keeps the timing exact.", update)

    Group("When to stop")
    LimitEditor(draft, errors.limit, update, offerPasses = false)

    WhereItMayRun(state, viewModel)
}

/** A list of steps, each one sentence long until opened for editing. */
@Composable
private fun SequenceSections(state: RoutineEditorUiState, viewModel: RoutineEditorViewModel) {
    val draft = state.draft
    val errors = state.errors
    val update = viewModel::updateDraft
    Group("Steps")
    StepsEditor(
        state, viewModel,
        intro = "They run top to bottom. Each waits after the click before it; the first starts straight after the start delay.",
        firstStepWait = { wait -> "on repeats, waits $wait after the last step" },
        firstStepHint = "Used when the list repeats",
    )

    Group("Repeat")
    RepeatEditor(draft, errors.limit, update)

    Group("Every step")
    DoubleClickRow(draft, update)
    RandomExtraField(draft, errors.randomExtra, "Adds up to this much to each step's wait, so the timing varies. 0 keeps it exact.", update)

    WhereItMayRun(state, viewModel)
}

/**
 * The list of steps, each one sentence long until opened for editing, with buttons to pick more on
 * screen or add one by hand. Shared by multi-point click and scroll and click.
 */
@Composable
private fun StepsEditor(
    state: RoutineEditorUiState,
    viewModel: RoutineEditorViewModel,
    intro: String,
    /** How the first step's wait reads in its row. */
    firstStepWait: (String) -> String,
    /** The hint under the first step's Wait field. */
    firstStepHint: String,
    allowText: Boolean = false,
) {
    val draft = state.draft
    val errors = state.errors
    // Which step is open for editing: only a position in the list, so ordinary UI state.
    var openStep by remember { mutableStateOf<Int?>(null) }
    Text(
        intro,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    draft.points.forEachIndexed { index, step ->
        StepRow(
            index = index,
            count = draft.points.size,
            step = step,
            isOpen = openStep == index,
            firstStepWait = firstStepWait,
            firstStepHint = firstStepHint,
            allowText = allowText,
            onToggle = { openStep = if (openStep == index) null else index },
            onChange = { viewModel.updatePoint(index, it) },
            onMove = { by ->
                viewModel.movePoint(index, by)
                if (openStep == index) openStep = index + by
            },
            onPickAgain = { viewModel.startCapture(replacing = index) },
            onRemove = {
                openStep = null
                viewModel.removePoint(index)
            },
        )
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        errors.points?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val capture = state.capture
        if (capture != null) {
            CaptureStatus(capture, state.captureHotKey, single = capture.replacing != null, onDone = viewModel::finishCapture)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { viewModel.startCapture() }) {
                    Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(if (draft.points.isEmpty()) "Pick steps on screen" else "Pick more on screen", Modifier.padding(start = 8.dp))
                }
                OutlinedButton(onClick = {
                    viewModel.addEmptyPoint()
                    openStep = draft.points.size
                }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Add step", Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun StepRow(
    index: Int,
    count: Int,
    step: PointDraft,
    isOpen: Boolean,
    firstStepWait: (String) -> String,
    firstStepHint: String,
    /** Scroll and click: a step may click a button by its text instead of a spot. */
    allowText: Boolean,
    onToggle: () -> Unit,
    onChange: (PointDraft) -> Unit,
    onMove: (Int) -> Unit,
    onPickAgain: () -> Unit,
    onRemove: () -> Unit,
) {
    val number = index + 1
    val colors = MaterialTheme.colorScheme
    val where = if (step.x.isBlank() || step.y.isBlank()) "spot not set" else "${step.x}, ${step.y}"
    val wait = step.delayMillis?.let(::waitLabel) ?: "wait not set"
    val waitText = if (index == 0) firstStepWait(wait) else "waits $wait"
    val title = step.name.trim().ifEmpty { defaultStepName(index) }
    val action = if (allowText && step.byText) {
        if (step.text.isBlank()) "Button text not set" else "Clicks \"${step.text.trim()}\""
    } else {
        "${step.button.name} click at $where"
    }
    ListItem(
        modifier = Modifier.clickable(onClickLabel = if (isOpen) "Close $title" else "Edit $title", onClick = onToggle),
        colors = ListItemDefaults.colors(containerColor = if (isOpen) colors.surfaceContainerHigh else Color.Transparent),
        leadingContent = {
            Surface(Modifier.size(32.dp), shape = CircleShape, color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
                Box(contentAlignment = Alignment.Center) { Text("$number", style = MaterialTheme.typography.labelLarge) }
            }
        },
        headlineContent = { Text(title) },
        supportingContent = { Text("$action · $waitText") },
        trailingContent = {
            Row {
                IconButton(onClick = { onMove(-1) }, enabled = index > 0) { Icon(Icons.Default.ArrowUpward, contentDescription = "Move step $number up") }
                IconButton(onClick = { onMove(1) }, enabled = index < count - 1) { Icon(Icons.Default.ArrowDownward, contentDescription = "Move step $number down") }
                Icon(if (isOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.padding(12.dp))
            }
        },
    )
    if (isOpen) {
        Column(
            Modifier.background(colors.surfaceContainerHigh).padding(start = 72.dp, end = 16.dp, bottom = 12.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = step.name,
                onValueChange = { onChange(step.copy(name = it)) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                NumberField(
                    "Wait", step.delay, null,
                    supporting = if (index == 0) firstStepHint else "After the step above",
                    onChange = { onChange(step.copy(delay = it)) },
                    modifier = Modifier.weight(1f),
                )
                IntervalUnitMenu(step.delayUnit, { unit -> onChange(step.copy(delayUnit = unit)) }, Modifier.weight(1f))
            }
            if (allowText) {
                SingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                    listOf(false to "Spot on screen", true to "Button text").forEachIndexed { i, (byText, label) ->
                        SegmentedButton(
                            selected = step.byText == byText,
                            onClick = { onChange(step.copy(byText = byText)) },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                            label = { Text(label) },
                        )
                    }
                }
            }
            if (allowText && step.byText) {
                OutlinedTextField(
                    value = step.text,
                    onValueChange = { onChange(step.copy(text = it)) },
                    label = { Text("Button text") },
                    singleLine = true,
                    supportingText = { Text("The label on the button or link, such as Back to Course. It waits up to 30 seconds for it to appear.") },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NumberField("X", step.x, null, onChange = { onChange(step.copy(x = it)) }, modifier = Modifier.weight(1f))
                    NumberField("Y", step.y, null, onChange = { onChange(step.copy(y = it)) }, modifier = Modifier.weight(1f))
                }
                ButtonChoice(step.button) { onChange(step.copy(button = it)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!(allowText && step.byText)) {
                    TextButton(onClick = onPickAgain) {
                        Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("Pick again", Modifier.padding(start = 8.dp))
                    }
                }
                TextButton(onClick = onRemove) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp), tint = colors.error)
                    Text("Remove step", Modifier.padding(start = 8.dp), color = colors.error)
                }
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 72.dp, end = 16.dp))
}

/** Once, a number of times, until stopped, or for a while: how a list of steps repeats. */
@Composable
private fun RepeatEditor(draft: RoutineDraft, error: String?, update: ((RoutineDraft) -> RoutineDraft) -> Unit) {
    val once = draft.limitMode == LimitMode.Passes && draft.limitPasses.trim() == "1"
    val times = draft.limitMode == LimitMode.Passes && !once
    Column(Modifier.selectableGroup()) {
        Choice("Once", "Runs the steps once, top to bottom.", once) { update { it.copy(limitMode = LimitMode.Passes, limitPasses = "1") } }
        Choice("A number of times", null, times) {
            update { it.copy(limitMode = LimitMode.Passes, limitPasses = if (it.limitPasses.trim() == "1") "2" else it.limitPasses) }
        }
        if (times) {
            NumberField(
                "Times", draft.limitPasses, error,
                onChange = { value -> update { it.copy(limitPasses = value) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        Choice("Until I stop it", null, draft.limitMode == LimitMode.UntilStopped) { update { it.copy(limitMode = LimitMode.UntilStopped) } }
        Choice("For a set time", null, draft.limitMode == LimitMode.Minutes) { update { it.copy(limitMode = LimitMode.Minutes) } }
        if (draft.limitMode == LimitMode.Minutes) {
            NumberField(
                "Minutes", draft.limitMinutes, error,
                onChange = { value -> update { it.copy(limitMinutes = value) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        // Kept for a routine saved with a click count before this list had its own choices.
        if (draft.limitMode == LimitMode.Clicks) {
            Choice("After a number of clicks", null, true) {}
            NumberField(
                "Clicks", draft.limitClicks, error,
                onChange = { value -> update { it.copy(limitClicks = value) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun ButtonChoice(selected: PointerButton, onSelect: (PointerButton) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
        PointerButton.entries.forEachIndexed { index, button ->
            SegmentedButton(
                selected = selected == button,
                onClick = { onSelect(button) },
                shape = SegmentedButtonDefaults.itemShape(index, PointerButton.entries.size),
                label = { Text(button.name) },
            )
        }
    }
}

@Composable
private fun DoubleClickRow(draft: RoutineDraft, update: ((RoutineDraft) -> RoutineDraft) -> Unit) {
    ListItem(
        modifier = Modifier.toggleable(value = draft.doubleClick, role = Role.Switch, onValueChange = { value -> update { it.copy(doubleClick = value) } }),
        colors = transparent(),
        headlineContent = { Text("Double click") },
        trailingContent = { Switch(checked = draft.doubleClick, onCheckedChange = null) },
    )
}

@Composable
private fun RandomExtraField(draft: RoutineDraft, error: String?, supporting: String, update: ((RoutineDraft) -> RoutineDraft) -> Unit) {
    NumberField(
        label = "Random extra wait (ms)",
        value = draft.randomExtraMillis,
        error = error,
        supporting = supporting,
        onChange = { value -> update { it.copy(randomExtraMillis = value) } },
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

@Composable
private fun WhereItMayRun(state: RoutineEditorUiState, viewModel: RoutineEditorViewModel) {
    val update = viewModel::updateDraft
    Group("Where it may run")
    AppMenu(state.draft.onlyInApp, state.runningApps, onOpen = viewModel::refreshApps, onSelect = { app -> update { it.copy(onlyInApp = app) } })
    NumberField(
        label = "Start delay (seconds)",
        value = state.draft.startDelaySeconds,
        error = state.errors.startDelay,
        supporting = "Time to get to the right window after pressing Start.",
        onChange = { value -> update { it.copy(startDelaySeconds = value) } },
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

@Composable
private fun ScrollClickSections(state: RoutineEditorUiState, viewModel: RoutineEditorViewModel) {
    val draft = state.draft
    val update = viewModel::updateDraft
    Group("Steps at the bottom of each page")
    StepsEditor(
        state, viewModel,
        intro = "Each step clicks a spot on the screen, or a button found by its text wherever it is on the page. For spots, scroll to the bottom first and pick each one in order, and keep the Chrome window where it is.",
        firstStepWait = { wait -> "waits $wait once the page settles at the bottom" },
        firstStepHint = "After the page settles at the bottom",
        allowText = true,
    )

    Group("Scrolling")
    SpeedSlider(draft.pixelsPerSecond) { value -> update { it.copy(pixelsPerSecond = value) } }

    Group("Videos")
    ListItem(
        modifier = Modifier.toggleable(value = draft.watchVideos, role = Role.Switch, onValueChange = { value -> update { it.copy(watchVideos = value) } }),
        colors = transparent(),
        headlineContent = { Text("Stop for videos") },
        supportingContent = { Text("Plays each video it reaches, pressing its play button if it has one, and carries on when it ends.") },
        trailingContent = { Switch(checked = draft.watchVideos, onCheckedChange = null) },
    )

    Group("When to stop")
    NumberField(
        label = "Rounds",
        value = draft.rounds,
        error = state.errors.rounds,
        supporting = "Stops after this many clicks, or sooner if a click doesn't change the page.",
        onChange = { value -> update { it.copy(rounds = value) } },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

@Composable
private fun SpeedSlider(value: Int, onChange: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("Speed: $value pixels a second", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = MIN_PIXELS_PER_SECOND.toFloat()..MAX_PIXELS_PER_SECOND.toFloat(),
        )
    }
}

@Composable
private fun CaptureStatus(capture: PointCapture, hotKey: HotKey, single: Boolean, onDone: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (single) {
                "Hold the pointer over the spot and press ${hotKey.name}."
            } else {
                "Go to the other app, hold the pointer over each target in turn, and press ${hotKey.name} for each. They're added as steps."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "On a Mac keyboard you may need to hold fn. " +
                (capture.pointer?.let { "Pointer is at ${it.x}, ${it.y}." } ?: "Pointer position is unavailable."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onDone) { Text(if (capture.added == 0) "Stop picking" else "Done (${capture.added} added)") }
    }
}

@Composable
private fun LimitEditor(draft: RoutineDraft, error: String?, update: ((RoutineDraft) -> RoutineDraft) -> Unit, offerPasses: Boolean) {
    Column(Modifier.selectableGroup()) {
        Choice("When I stop it", null, draft.limitMode == LimitMode.UntilStopped) { update { it.copy(limitMode = LimitMode.UntilStopped) } }
        Choice("After a number of clicks", null, draft.limitMode == LimitMode.Clicks) { update { it.copy(limitMode = LimitMode.Clicks) } }
        if (draft.limitMode == LimitMode.Clicks) {
            NumberField(
                "Clicks", draft.limitClicks, error,
                onChange = { value -> update { it.copy(limitClicks = value) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        Choice("After some time", null, draft.limitMode == LimitMode.Minutes) { update { it.copy(limitMode = LimitMode.Minutes) } }
        if (draft.limitMode == LimitMode.Minutes) {
            NumberField(
                "Minutes", draft.limitMinutes, error,
                onChange = { value -> update { it.copy(limitMinutes = value) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        if (offerPasses || draft.limitMode == LimitMode.Passes) {
            Choice("After a number of passes", "A pass clicks every point once, top to bottom. 1 runs them once.", draft.limitMode == LimitMode.Passes) {
                update { it.copy(limitMode = LimitMode.Passes) }
            }
            if (draft.limitMode == LimitMode.Passes) {
                NumberField(
                    "Passes", draft.limitPasses, error,
                    onChange = { value -> update { it.copy(limitPasses = value) } },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun IntervalUnitMenu(selected: IntervalUnit, onSelect: (IntervalUnit) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier.padding(top = 8.dp)) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected.label, Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            IntervalUnit.entries.forEach { unit ->
                DropdownMenuItem(
                    text = { Text(unit.label) },
                    trailingIcon = if (unit == selected) ({ Icon(Icons.Default.Check, contentDescription = "Selected") }) else null,
                    onClick = {
                        onSelect(unit)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** The list is read when opened, so an app started after this screen appears too. */
@Composable
private fun AppMenu(selected: String?, apps: List<String>, onOpen: () -> Unit, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // A saved choice stays selectable even while that app isn't running.
    val choices = (listOfNotNull(selected) + apps).distinct()
    Box {
        ListItem(
            modifier = Modifier.clickable(onClickLabel = "Choose app", role = Role.DropdownList) {
                onOpen()
                expanded = true
            },
            colors = transparent(),
            headlineContent = { Text("Only click while this app is in front") },
            supportingContent = { Text(selected ?: "Any app") },
            trailingContent = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, offset = DpOffset(16.dp, 0.dp)) {
            DropdownMenuItem(
                text = { Text("Any app") },
                trailingIcon = if (selected == null) ({ Icon(Icons.Default.Check, contentDescription = "Selected") }) else null,
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            choices.forEach { app ->
                DropdownMenuItem(
                    text = { Text(app) },
                    trailingIcon = if (app == selected) ({ Icon(Icons.Default.Check, contentDescription = "Selected") }) else null,
                    onClick = {
                        onSelect(app)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun Choice(title: String, supporting: String?, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        colors = transparent(),
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { text -> { Text(text) } },
    )
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    error: String?,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = (error ?: supporting)?.let { text -> { Text(text) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun Group(title: String) {
    // Inset so section dividers never meet the navigation divider.
    HorizontalDivider(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun transparent() = ListItemDefaults.colors(containerColor = Color.Transparent)
