package com.jagdeep.autopilot

import com.jagdeep.home.data.DirectoryCourseAnswerBanks
import com.jagdeep.home.domain.CourseAnswerBankStore
import java.io.File
import kotlinx.coroutines.swing.Swing

internal actual fun createCourseAnswerBankStore(): CourseAnswerBankStore = DirectoryCourseAnswerBanks(
    folder = File(System.getProperty("user.home"), ".autopilot/answer-banks"),
    legacy = listOf(
    File("private/course-q&a/sagarmeinyog.json"),
    File("../private/course-q&a/sagarmeinyog.json"),
    ),
    bundled = BUNDLED_ANSWER_BANKS.associateWith { name -> { bundledAnswerBank(name) } },
    chooseFile = { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Swing) {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Import answer bank", java.awt.FileDialog.LOAD)
        try {
            dialog.setFilenameFilter { _, name -> name.endsWith(".json",true) }
            dialog.isVisible = true
            dialog.file?.let { File(dialog.directory,it) }
        } finally { dialog.dispose() }
    } },
    showFolder = { folder -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { java.awt.Desktop.getDesktop().open(folder) } },
)

/**
 * Answer banks shipped in the app (`resources/answer-banks`), read from the list the build writes.
 * Each is copied to the bank folder when no file of that name is there, so an update adds new banks
 * and never replaces a bank the user already has.
 */
internal val BUNDLED_ANSWER_BANKS: List<String> by lazy {
    object {}.javaClass.getResourceAsStream("/answer-banks/index.txt")?.bufferedReader()?.use { reader ->
        reader.readLines().map(String::trim).filter(String::isNotEmpty)
    }.orEmpty()
}

internal fun bundledAnswerBank(name: String): String? =
    object {}.javaClass.getResourceAsStream("/answer-banks/$name")?.bufferedReader()?.use { it.readText() }
