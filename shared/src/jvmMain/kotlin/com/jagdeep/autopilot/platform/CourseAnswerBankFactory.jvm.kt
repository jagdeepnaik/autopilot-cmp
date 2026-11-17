package com.jagdeep.autopilot.platform

import com.jagdeep.home.data.SqliteCourseAnswerBanks
import com.jagdeep.home.domain.CourseAnswerBankStore
import java.io.File
import kotlinx.coroutines.swing.Swing

internal actual fun createCourseAnswerBankStore(): CourseAnswerBankStore = SqliteCourseAnswerBanks(
    databaseFolder = File(System.getProperty("user.home"), ".autopilot/databases/qa"),
    bundledDatabase = { name -> object {}.javaClass.getResourceAsStream("/qa-routines/$name") },
    legacyFolder = File(System.getProperty("user.home"), ".autopilot/answer-banks"),
    chooseFile = { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Swing) {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Import answer bank", java.awt.FileDialog.LOAD)
        try {
            dialog.setFilenameFilter { _, name -> name.endsWith(".json", true) }
            dialog.isVisible = true
            dialog.file?.let { File(dialog.directory, it) }
        } finally { dialog.dispose() }
    } },
    showFolder = { folder -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { java.awt.Desktop.getDesktop().open(folder) } },
)
