package com.jagdeep.autopilot.platform
import com.jagdeep.home.domain.CourseProgressRepository
import com.jagdeep.home.data.RoomCourseProgressRepository
import com.jagdeep.persistence.room.learning.openLearningProgressStore
import java.io.File
internal actual fun createCourseProgressRepository(): CourseProgressRepository = RoomCourseProgressRepository(
    openLearningProgressStore(
        path = desktopDataPaths.file("databases","autopilot.db").absolutePath,
        previousProgressPath = desktopDataPaths.file("databases","course-progress.db").absolutePath,
    ))
