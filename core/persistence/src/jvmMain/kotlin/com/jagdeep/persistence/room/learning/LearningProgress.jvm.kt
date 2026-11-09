package com.jagdeep.persistence.room.learning

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import java.io.File

fun openLearningProgressStore(path: String): LearningProgressStore {
    File(path).absoluteFile.parentFile.mkdirs()
    return RoomLearningProgressStore(Room.databaseBuilder<LearningProgressDatabase>(name = path)
        .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build())
}
