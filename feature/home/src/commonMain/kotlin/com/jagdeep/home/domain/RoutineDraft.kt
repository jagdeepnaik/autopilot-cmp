package com.jagdeep.home.domain

import com.jagdeep.automation.PointerButton
import kotlin.math.roundToLong

enum class IntervalUnit(val label: String, val millis: Long) {
    Milliseconds("Milliseconds", 1),
    Seconds("Seconds", 1_000),
    Minutes("Minutes", 60_000),
}

enum class TargetMode { AtPointer, AtPoints }

enum class LimitMode { UntilStopped, Clicks, Minutes, Passes }

/**
 * One step in the list: its name, where it clicks, how long it waits before its click (after the
 * previous click) in a unit of the user's choosing, and which button.
 */
data class PointDraft(
    val x: String = "",
    val y: String = "",
    val delay: String = "1",
    val delayUnit: IntervalUnit = IntervalUnit.Seconds,
    val button: PointerButton = PointerButton.Left,
    val name: String = "",
    /** Scroll and click only: click the button with [text] on the page instead of a spot on the screen. */
    val byText: Boolean = false,
    val text: String = "",
) {
    /** The wait in milliseconds; decimals are allowed, so "1.5" seconds is 1500. Null when it isn't a number. */
    val delayMillis: Long?
        get() = delay.trim().replace(',', '.').toDoubleOrNull()
            ?.takeIf { it >= 0 && it.isFinite() }
            ?.let { (it * delayUnit.millis).roundToLong() }
}

internal const val DEFAULT_POINT_DELAY_MILLIS = 1_000L
private const val MAX_STEP_NAME_LENGTH = 40

/** Button labels the extension matches are at most 40 characters; a little room for typing. */
private const val MAX_BUTTON_TEXT_LENGTH = 60

/** The name a step gets until the user gives it one. */
fun defaultStepName(index: Int) = "Point ${index + 1}"

/** Shows a wait in the largest unit that reads cleanly: 120000 as 2 minutes, 1500 as 1.5 seconds. */
fun delayDraft(millis: Long): Pair<String, IntervalUnit> = when {
    millis > 0 && millis % 60_000 == 0L -> (millis / 60_000).toString() to IntervalUnit.Minutes
    millis > 0 && millis % 1_000 == 0L -> (millis / 1_000).toString() to IntervalUnit.Seconds
    millis >= 1_000 && millis % 100 == 0L -> "${millis / 1_000}.${(millis % 1_000) / 100}" to IntervalUnit.Seconds
    else -> millis.toString() to IntervalUnit.Milliseconds
}

/** What the editor shows: numbers stay text until saved, so half-typed values are never lost. */
data class RoutineDraft(
    val kind: RoutineKind = RoutineKind.Click,
    val name: String = "",
    val targetMode: TargetMode = TargetMode.AtPointer,
    val points: List<PointDraft> = emptyList(),
    val button: PointerButton = PointerButton.Left,
    val doubleClick: Boolean = false,
    val interval: String = "1",
    val intervalUnit: IntervalUnit = IntervalUnit.Seconds,
    val randomExtraMillis: String = "0",
    val limitMode: LimitMode = LimitMode.UntilStopped,
    val limitClicks: String = "100",
    val limitMinutes: String = "10",
    val limitPasses: String = "1",
    val onlyInApp: String? = null,
    val startDelaySeconds: String = "3",
    // Chrome routines. A scroll and click routine uses the first of [points] as its spot.
    val pixelsPerSecond: Int = DEFAULT_PIXELS_PER_SECOND,
    val rounds: String = DEFAULT_SCROLL_CLICK_ROUNDS.toString(),
    val watchVideos: Boolean = true,
    val requiredHours: String = "18",
    val creditedHours: String = "0",
    val waitAfterClickSeconds: String = "5",
    val playbackSpeed: Double = 1.0,
    val answerAssessments: Boolean = false,
    val assessmentMode: AssessmentMode = AssessmentMode.Review,
    val learningOrder: LearningOrder = LearningOrder.AssessmentAfterModule,
    val answerBankFile: String = com.jagdeep.shared.answers.DEFAULT_ANSWER_BANK_FILE,
    val answerQuizzes: Boolean = true,
    val autoDownloadCertificates: Boolean = false,
)

/** Field-level problems; a null field is fine. */
data class DraftErrors(
    val name: String? = null,
    val points: String? = null,
    val interval: String? = null,
    val randomExtra: String? = null,
    val limit: String? = null,
    val startDelay: String? = null,
    val rounds: String? = null,
    val waitAfterClick: String? = null,
    val playbackSpeed: String? = null,
    val answerBankFile: String? = null,
    val studyHours: String? = null,
) {
    val hasAny: Boolean get() = listOf(name, points, interval, randomExtra, limit, startDelay, rounds, waitAfterClick, playbackSpeed, answerBankFile, studyHours).any { it != null }
}

internal const val MIN_INTERVAL_MILLIS = 10L
internal const val MAX_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
private const val MAX_NAME_LENGTH = 60
private const val MAX_RANDOM_EXTRA_MILLIS = 60L * 60 * 1000
private const val MAX_LIMIT_CLICKS = 1_000_000_000L
private const val MAX_LIMIT_MINUTES = 7L * 24 * 60
private const val MAX_LIMIT_PASSES = 1_000_000L
private const val MAX_START_DELAY_SECONDS = 60
private const val MAX_COORDINATE = 100_000

fun Routine.toDraft(): RoutineDraft = when (this) {
    is ClickRoutine -> toDraft()
    is ScrollClickRoutine -> RoutineDraft(
        kind = RoutineKind.ScrollClick,
        name = name,
        targetMode = TargetMode.AtPoints,
        points = steps.mapIndexed { index, step ->
            val (delay, unit) = delayDraft(step.delayMillis ?: 0)
            val text = step.text?.takeIf { it.isNotBlank() }
            PointDraft(
                if (text == null) step.x.toString() else "", if (text == null) step.y.toString() else "",
                delay, unit, step.button ?: PointerButton.Left, step.name ?: defaultStepName(index),
                byText = text != null, text = text.orEmpty(),
            )
        },
        pixelsPerSecond = pixelsPerSecond,
        rounds = rounds.toString(),
        watchVideos = watchVideos,
    )
    is DgsELearningRoutine -> RoutineDraft(kind=kind,name=name,requiredHours=requiredHours.toString(),creditedHours=creditedHours.toString(),pixelsPerSecond=pixelsPerSecond,playbackSpeed=playbackSpeed,waitAfterClickSeconds=(waitAfterClickMillis/1000).toString(),answerBankFile=com.jagdeep.shared.answers.resolvedAnswerBankFileName(answerBankFile) ?: answerBankFile)
    is CourseAutomationRoutine -> RoutineDraft(kind = RoutineKind.CourseAutomation, name = name, waitAfterClickSeconds = (waitAfterClickMillis / 1000).toString(), playbackSpeed = playbackSpeed, answerAssessments = answerAssessments, assessmentMode = assessmentMode, learningOrder = learningOrder, answerBankFile = com.jagdeep.shared.answers.resolvedAnswerBankFileName(answerBankFile) ?: answerBankFile, answerQuizzes = answerQuizzes, autoDownloadCertificates = autoDownloadCertificates)
}

fun ClickRoutine.toDraft(): RoutineDraft {
    // Shows the interval in the largest unit that keeps it a whole number.
    val unit = IntervalUnit.entries.last { intervalMillis % it.millis == 0L }
    return RoutineDraft(
        kind = kind,
        name = name,
        targetMode = if (target is ClickTarget.AtPoints) TargetMode.AtPoints else TargetMode.AtPointer,
        // A point saved without its own wait or button shows the routine's, which is what it used.
        points = (target as? ClickTarget.AtPoints)?.points.orEmpty().mapIndexed { index, point ->
            val (delay, unit) = delayDraft(point.delayMillis ?: intervalMillis)
            PointDraft(point.x.toString(), point.y.toString(), delay, unit, point.button ?: button, point.name ?: defaultStepName(index))
        },
        button = button,
        doubleClick = clicksPerAction == 2,
        interval = (intervalMillis / unit.millis).toString(),
        intervalUnit = unit,
        randomExtraMillis = randomExtraMillis.toString(),
        limitMode = when (limit) {
            RunLimit.UntilStopped -> LimitMode.UntilStopped
            is RunLimit.Clicks -> LimitMode.Clicks
            is RunLimit.Duration -> LimitMode.Minutes
            is RunLimit.Passes -> LimitMode.Passes
        },
        limitPasses = (limit as? RunLimit.Passes)?.count?.toString() ?: RoutineDraft().limitPasses,
        limitClicks = (limit as? RunLimit.Clicks)?.count?.toString() ?: RoutineDraft().limitClicks,
        limitMinutes = (limit as? RunLimit.Duration)?.let { (it.millis / 60_000).toString() } ?: RoutineDraft().limitMinutes,
        onlyInApp = onlyInApp,
        startDelaySeconds = startDelaySeconds.toString(),
    )
}

fun RoutineDraft.validate(): DraftErrors = when (kind) {
    RoutineKind.Click, RoutineKind.Sequence -> validateClick()
    RoutineKind.ScrollClick -> DraftErrors(
        name = nameError(),
        points = when {
            points.isEmpty() -> "Add at least one step to click at the bottom."
            points.any { it.byText && it.text.isBlank() } -> "Each button step needs the button's text."
            points.any { it.byText && it.text.trim().length > MAX_BUTTON_TEXT_LENGTH } -> "Keep each button's text to $MAX_BUTTON_TEXT_LENGTH characters or fewer."
            points.any { !it.byText && (it.x.coordinate() == null || it.y.coordinate() == null) } -> "Each step needs a whole-number X and Y."
            // A step can click straight away here: reaching the bottom already took a while.
            points.any { point -> point.delayMillis.let { it == null || it !in 0..MAX_INTERVAL_MILLIS } } -> "Each wait must be from 0 to 24 hours."
            points.any { it.name.trim().length > MAX_STEP_NAME_LENGTH } -> "Keep each step's name to $MAX_STEP_NAME_LENGTH characters or fewer."
            else -> null
        },
        rounds = rounds.trim().toIntOrNull().let {
            if (it == null || it !in MIN_SCROLL_CLICK_ROUNDS..MAX_SCROLL_CLICK_ROUNDS) "Use between $MIN_SCROLL_CLICK_ROUNDS and $MAX_SCROLL_CLICK_ROUNDS rounds." else null
        },
    )
    RoutineKind.DgsELearning -> DraftErrors(
        name=nameError(),
        studyHours=if(requiredHours.toDoubleOrNull()?.let { it.isFinite() && it>0 && it<=1000 }==true && creditedHours.toDoubleOrNull()?.let { it.isFinite() && it>=0 && it<=requiredHours.toDouble() }==true) null else "Enter required hours (up to 1000) and credited hours from 0 to the required hours.",
        playbackSpeed=if(playbackSpeed in setOf(1.0,1.5,2.0)) null else "Choose 1×, 1.5× or 2×.",
        waitAfterClick=if(waitAfterClickSeconds.toLongOrNull()?.let { it in 1..86400 }==true) null else "Enter whole seconds from 1 to 86400.",
        answerBankFile=if(com.jagdeep.shared.answers.resolvedAnswerBankFileName(answerBankFile)!=null) null else "Use a simple JSON file name.",
    )
    RoutineKind.CourseAutomation -> DraftErrors(
        answerBankFile = if (com.jagdeep.shared.answers.resolvedAnswerBankFileName(answerBankFile) == null) "Use a file name such as name-ver-1.0.json." else null,
        name = nameError(),
        playbackSpeed = if (playbackSpeed in setOf(1.0, 1.5, 2.0)) null else "Choose 1×, 1.5× or 2×.",
        waitAfterClick = if (waitAfterClickSeconds.trim().toLongOrNull()?.let { it in 0..86_400 } == true) null else "Enter whole seconds from 0 to 86400.",
    )
}

private fun RoutineDraft.nameError(): String? = when {
    name.isBlank() -> "Enter a name."
    name.trim().length > MAX_NAME_LENGTH -> "Use $MAX_NAME_LENGTH characters or fewer."
    else -> null
}

private fun RoutineDraft.validateClick(): DraftErrors {
    val intervalMillis = interval.trim().toLongOrNull()?.let { it * intervalUnit.millis }
    return DraftErrors(
        name = nameError(),
        points = when {
            targetMode != TargetMode.AtPoints -> null
            points.isEmpty() -> "Add at least one point."
            points.any { it.x.coordinate() == null || it.y.coordinate() == null } -> "Each point needs a whole-number X and Y."
            points.any { point -> point.delayMillis.let { it == null || it !in MIN_INTERVAL_MILLIS..MAX_INTERVAL_MILLIS } } ->
                "Each wait must be from $MIN_INTERVAL_MILLIS ms to 24 hours."
            points.any { it.name.trim().length > MAX_STEP_NAME_LENGTH } -> "Keep each step's name to $MAX_STEP_NAME_LENGTH characters or fewer."
            else -> null
        },
        interval = when {
            intervalMillis == null -> "Enter a whole number."
            intervalMillis < MIN_INTERVAL_MILLIS -> "Use at least $MIN_INTERVAL_MILLIS milliseconds."
            intervalMillis > MAX_INTERVAL_MILLIS -> "Use 24 hours or less."
            else -> null
        },
        randomExtra = when (randomExtraMillis.trim().toLongOrNull()) {
            null -> "Enter a whole number of milliseconds."
            !in 0..MAX_RANDOM_EXTRA_MILLIS -> "Use between 0 and $MAX_RANDOM_EXTRA_MILLIS."
            else -> null
        },
        limit = when (limitMode) {
            LimitMode.UntilStopped -> null
            LimitMode.Clicks -> limitClicks.trim().toLongOrNull().let { if (it == null || it !in 1..MAX_LIMIT_CLICKS) "Use between 1 and $MAX_LIMIT_CLICKS clicks." else null }
            LimitMode.Minutes -> limitMinutes.trim().toLongOrNull().let { if (it == null || it !in 1..MAX_LIMIT_MINUTES) "Use between 1 and $MAX_LIMIT_MINUTES minutes." else null }
            LimitMode.Passes -> when {
                targetMode != TargetMode.AtPoints -> "Passes apply to specific points. Choose another way to stop."
                limitPasses.trim().toLongOrNull().let { it == null || it !in 1..MAX_LIMIT_PASSES } -> "Use between 1 and $MAX_LIMIT_PASSES passes."
                else -> null
            }
        },
        startDelay = startDelaySeconds.trim().toIntOrNull().let { if (it == null || it !in 0..MAX_START_DELAY_SECONDS) "Use between 0 and $MAX_START_DELAY_SECONDS seconds." else null },
    )
}

/** Builds the routine from a draft that has passed [validate]. */
fun RoutineDraft.toRoutine(id: String): Routine = when (kind) {
    RoutineKind.Click, RoutineKind.Sequence -> toClickRoutine(id)
    RoutineKind.ScrollClick -> ScrollClickRoutine(
        id = id,
        name = name.trim(),
        points = points.mapIndexed { index, point ->
            val name = point.name.trim().ifEmpty { defaultStepName(index) }
            if (point.byText) {
                ClickPoint(0, 0, point.delayMillis, null, name, text = point.text.trim())
            } else {
                ClickPoint(point.x.trim().toInt(), point.y.trim().toInt(), point.delayMillis, point.button, name)
            }
        },
        rounds = rounds.trim().toInt(),
        pixelsPerSecond = pixelsPerSecond.coerceIn(MIN_PIXELS_PER_SECOND, MAX_PIXELS_PER_SECOND),
        watchVideos = watchVideos,
    )
    RoutineKind.DgsELearning -> DgsELearningRoutine(id,name.trim(),requiredHours.toDouble(),creditedHours.toDouble(),pixelsPerSecond.coerceIn(MIN_PIXELS_PER_SECOND,MAX_PIXELS_PER_SECOND),playbackSpeed,waitAfterClickSeconds.toLong()*1000,requireNotNull(com.jagdeep.shared.answers.resolvedAnswerBankFileName(answerBankFile)))
    RoutineKind.CourseAutomation -> CourseAutomationRoutine(id = id, name = name.trim(), waitAfterClickMillis = waitAfterClickSeconds.trim().toLong() * 1000, playbackSpeed = playbackSpeed, answerAssessments = answerAssessments, assessmentMode = assessmentMode, learningOrder = learningOrder, answerBankFile = requireNotNull(com.jagdeep.shared.answers.resolvedAnswerBankFileName(answerBankFile)), answerQuizzes = answerQuizzes, autoDownloadCertificates = autoDownloadCertificates)
}

private fun RoutineDraft.toClickRoutine(id: String): ClickRoutine = ClickRoutine(
    id = id,
    name = name.trim(),
    target = when (targetMode) {
        TargetMode.AtPointer -> ClickTarget.AtPointer
        TargetMode.AtPoints -> ClickTarget.AtPoints(
            points.mapIndexed { index, point ->
                // A step left without a name gets its default one back.
                val name = point.name.trim().ifEmpty { defaultStepName(index) }
                ClickPoint(point.x.trim().toInt(), point.y.trim().toInt(), point.delayMillis, point.button, name)
            },
        )
    },
    button = button,
    clicksPerAction = if (doubleClick) 2 else 1,
    intervalMillis = interval.trim().toLong() * intervalUnit.millis,
    randomExtraMillis = randomExtraMillis.trim().toLong(),
    limit = when (limitMode) {
        LimitMode.UntilStopped -> RunLimit.UntilStopped
        LimitMode.Clicks -> RunLimit.Clicks(limitClicks.trim().toLong())
        LimitMode.Minutes -> RunLimit.Duration(limitMinutes.trim().toLong() * 60_000)
        LimitMode.Passes -> RunLimit.Passes(limitPasses.trim().toLong())
    },
    onlyInApp = onlyInApp,
    startDelaySeconds = startDelaySeconds.trim().toInt(),
)

private fun String.coordinate(): Int? = trim().toIntOrNull()?.takeIf { it in -MAX_COORDINATE..MAX_COORDINATE }
