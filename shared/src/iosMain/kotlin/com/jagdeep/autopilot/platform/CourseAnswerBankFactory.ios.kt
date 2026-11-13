package com.jagdeep.autopilot.platform

import com.jagdeep.home.domain.CourseAnswerBankStore
import com.jagdeep.home.domain.NoCourseAnswerBankStore

internal actual fun createCourseAnswerBankStore(): CourseAnswerBankStore = NoCourseAnswerBankStore
