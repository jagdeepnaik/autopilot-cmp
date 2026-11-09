package com.jagdeep.autoclicker
import com.jagdeep.home.domain.CourseProgressRepository
import com.jagdeep.home.data.RoomCourseProgressRepository
import com.jagdeep.persistence.room.learning.openLearningProgressStore
import java.io.File
internal actual fun createCourseProgressRepository(): CourseProgressRepository = RoomCourseProgressRepository(
    openLearningProgressStore(File(System.getProperty("user.home"), ".autoclicker/course-progress.db").absolutePath))
