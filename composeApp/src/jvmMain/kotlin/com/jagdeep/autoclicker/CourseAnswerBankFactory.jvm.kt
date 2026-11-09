package com.jagdeep.autoclicker

import com.jagdeep.home.data.FileCourseAnswerBankStore
import com.jagdeep.home.domain.CourseAnswerBankStore
import java.io.File

internal actual fun createCourseAnswerBankStore(): CourseAnswerBankStore = FileCourseAnswerBankStore(listOf(
    File("private/course-q&a/sagarmeinyog.json"),
    File("../private/course-q&a/sagarmeinyog.json"),
    File(System.getProperty("user.home"), ".autoclicker/sagarmeinyog.json"),
))
