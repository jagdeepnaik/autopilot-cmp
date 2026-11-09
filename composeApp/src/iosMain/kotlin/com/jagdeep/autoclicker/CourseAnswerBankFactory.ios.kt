package com.jagdeep.autoclicker

import com.jagdeep.home.domain.CourseAnswerBankStore
import com.jagdeep.home.domain.NoCourseAnswerBankStore

internal actual fun createCourseAnswerBankStore(): CourseAnswerBankStore = NoCourseAnswerBankStore
