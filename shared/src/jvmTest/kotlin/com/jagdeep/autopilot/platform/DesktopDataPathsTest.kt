package com.jagdeep.autopilot.platform

import java.nio.file.Files
import java.io.IOException
import kotlin.test.*

class DesktopDataPathsTest {
    @Test fun `moving settings database sidecars and extension preserves data and is repeatable`() {
        val root=Files.createTempDirectory("autopilot-layout").toFile()
        try {
            root.resolve("app_preferences.preferences_pb").writeText("settings")
            root.resolve("course-progress.db").writeText("database")
            root.resolve("course-progress.db-wal").writeText("journal")
            root.resolve("chrome-extension").mkdir();root.resolve("chrome-extension/manifest.json").writeText("manifest")
            val paths=DesktopDataPaths(root)
            assertEquals("settings",paths.file("settings","app_preferences.preferences_pb").readText())
            assertEquals("database",paths.file("databases","course-progress.db").readText())
            assertEquals("journal",root.resolve("databases/course-progress.db-wal").readText())
            assertEquals("manifest",paths.chromeExtension().resolve("manifest.json").readText())
            assertEquals("settings",paths.file("settings","app_preferences.preferences_pb").readText())
            assertFalse(root.resolve("course-progress.db").exists());assertFalse(root.resolve("chrome-extension").exists())
        } finally {root.deleteRecursively()}
    }
    @Test fun `conflicting destination never overwrites either copy`() {
        val root=Files.createTempDirectory("autopilot-conflict").toFile()
        try {
            root.resolve("routines.preferences_pb").writeText("original")
            root.resolve("routines").mkdir();root.resolve("routines/routines.preferences_pb").writeText("new")
            assertFailsWith<IOException>{DesktopDataPaths(root).file("routines","routines.preferences_pb")}
            assertEquals("original",root.resolve("routines.preferences_pb").readText())
            assertEquals("new",root.resolve("routines/routines.preferences_pb").readText())
        }finally {root.deleteRecursively()}
    }
}
