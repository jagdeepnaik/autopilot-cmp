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
    File(System.getProperty("user.home"), ".autopilot/sagarmeinyog.json"),
    ),
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
