import org.jetbrains.compose.ComposeExtension
import org.jetbrains.compose.resources.ResourcesExtension

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeHotReload) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
    alias(libs.plugins.androidLint) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.room) apply false
}

/**
 * The app uses Semantic Versioning plus immutable build identity:
 *   <release>+build.<count>.g<short commit>
 *
 * Example: 1.1.0+build.104.g4614d49. The release version is shared by every platform; the build
 * number and commit identify the exact source used for a desktop build. Windows MSI cannot carry
 * SemVer build metadata, so its numeric package version is kept separately as <major>.<minor>.<build>.
 */
val releaseVersion: String = libs.versions.versionName.get()
val (major, minor, patch) = releaseVersion.split('.').map(String::toInt).also {
    check(it.size == 3) { "versionName must be MAJOR.MINOR.PATCH, got $releaseVersion" }
}
val buildNumber: Int = run {
    val commits = providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().toIntOrNull()
        ?: error("The desktop version needs the Git history: run the build inside a full Git checkout.")
    check(commits in 1..65535) { "Desktop build number $commits is out of range" }
    commits
}
val gitCommit: String = providers.exec {
    commandLine("git", "rev-parse", "--short=12", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().takeIf { it.matches(Regex("[0-9a-fA-F]{7,12}")) }
    ?: error("The desktop version needs the Git commit: run the build inside a full Git checkout.")
val desktopVersion = "$releaseVersion+build.$buildNumber.g$gitCommit"
val desktopPackageVersion = "$major.$minor.$buildNumber"
check(major in 0..255 && minor in 0..255 && buildNumber in 1..65535) {
    "Desktop package version $desktopPackageVersion is out of range"
}
extra["desktopVersion"] = desktopVersion
extra["desktopPackageVersion"] = desktopPackageVersion

/**
 * core:ui is an exact copy of the template, and its AppIcon imports the generated `Res` from the
 * package the template's root project name produces. Pinning that package here lets the copy stay
 * byte-identical while this project keeps its own name.
 */
project(":core:ui") {
    plugins.withId("org.jetbrains.compose") {
        val compose = extensions.getByType<ComposeExtension>() as ExtensionAware
        compose.extensions.configure<ResourcesExtension> {
            packageOfResClass = "zynk.core.ui.generated.resources"
        }
    }
}

/**
 * Feature modules may use core modules, but never a concrete sibling feature.
 * The app shell is the only composition root.
 */
tasks.register("verifyFeatureModuleBoundaries") {
    group = "verification"
    description = "Rejects dependencies between concrete feature modules (feature:shared is allowed)."
    notCompatibleWithConfigurationCache("Scans feature build scripts when the task executes.")
    doLast {
        val featureDependency = Regex("""project\("(:feature:[^"]+)"\)""")
        val violations = fileTree("feature") {
            include("**/build.gradle.kts")
        }.files.flatMap { file ->
            featureDependency.findAll(file.readText())
                .map { match -> match.groupValues[1] }
                .filter { dependency -> dependency != ":feature:shared" }
                .map { dependency -> "${file.relativeTo(projectDir)} depends on $dependency" }
                .toList()
        }
        check(violations.isEmpty()) {
            "Concrete feature modules must not depend on each other.\n${violations.joinToString("\n")}"
        }
    }
}
