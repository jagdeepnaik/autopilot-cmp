package com.jagdeep.autopilot.platform
import com.jagdeep.home.domain.CourseProgressRepository
import com.jagdeep.home.domain.NoCourseProgressRepository
internal actual fun createCourseProgressRepository(): CourseProgressRepository = NoCourseProgressRepository
