package com.jagdeep.autopilot
import com.jagdeep.home.domain.CourseProgressRepository
import com.jagdeep.home.data.RoomCourseProgressRepository
import com.jagdeep.persistence.room.learning.openLearningProgressStore
import java.io.File
internal actual fun createCourseProgressRepository(): CourseProgressRepository = RoomCourseProgressRepository(
    openLearningProgressStore(desktopDataPaths.file("databases","course-progress.db").absolutePath))
