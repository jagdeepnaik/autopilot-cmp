import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.serialization)
}

// The app can't list its own resource folder from inside a jar, so the build writes the list.
val generateAnswerBankIndex by tasks.registering {
    val banks = layout.projectDirectory.dir("src/jvmMain/resources/answer-banks")
    val output = layout.buildDirectory.dir("generated/answer-bank-index")
    inputs.files(fileTree(banks) { include("*.json") }).withPropertyName("banks")
    outputs.dir(output)
    doLast {
        val names = banks.asFile.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") }.map { it.name }.sorted()
        output.get().file("answer-banks/index.txt").asFile.apply { parentFile.mkdirs(); writeText(names.joinToString("\n")) }
    }
}

// Every desktop build's version: <major>.<minor>.<commit count>, so each commit gets a higher one.
// Windows Installer only upgrades in place when the version rises (it compares the first three
// parts), so a new MSI replaces the installed app instead of stopping with "already installed".
// The DMG, DEB and the app's About screen use the same number. App data lives in ~/.autopilot,
// which no installer touches. Android and iOS keep versionName from the version catalog.
val desktopVersion: String = run {
    val (major, minor) = libs.versions.versionName.get().split('.').map(String::toInt)
    val commits = providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().toIntOrNull()
        ?: error("The desktop version needs the Git history: run the build inside a full Git checkout.")
    check(major in 0..255 && minor in 0..255 && commits in 1..65535) { "Desktop version $major.$minor.$commits is out of range" }
    "$major.$minor.$commits"
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            binaryOption("bundleId", "com.jagdeep.autopilot.composeapp")
        }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlin.stdlib)
            implementation(libs.components.resources)
            // Coroutines
            implementation(libs.kotlinx.coroutines.core)
            // Lifecycle
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            // Serialization
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.serialization.core)
            // Persistence
            implementation(libs.androidx.datastore)
            implementation(libs.androidx.datastore.preferences)
            // UI Preview
            implementation(libs.ui.tooling.preview)
            implementation(libs.material.icons.extended)

            // Local Core
            implementation(project(":core:automation"))
            implementation(project(":core:common"))
            implementation(project(":core:localbridge"))
            implementation(project(":core:logger"))
            implementation(project(":core:persistence"))
            implementation(project(":core:ui"))
            // Features
            implementation(project(":feature:activity"))
            implementation(project(":feature:home"))
            implementation(project(":feature:settings"))
            implementation(project(":feature:shared"))
        }

        androidMain.dependencies {
            implementation(libs.ui.tooling.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            // Windows title bar colours (DWM).
            implementation(libs.jna.platform)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmTest.dependencies {
            implementation(compose.desktop.uiTestJUnit4)
        }
        // answer-banks/index.txt: lists every bank in resources/answer-banks, so adding a bank file is enough.
        jvmMain { resources.srcDir(generateAnswerBankIndex) }
    }
}

compose.desktop {
    application {
        mainClass = "com.jagdeep.autopilot.MainKt"
        // The version the desktop app shows (About, status bar): the same one the MSI carries.
        jvmArgs += "-Dautopilot.version=$desktopVersion"

        buildTypes.release.proguard {
            // Matches the template: shrinking is off until the dependency graph is known to survive it.
            isEnabled.set(false)
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "AutoPilot"
            packageVersion = desktopVersion
            // JDK modules the packaged runtime must contain (from :composeApp:suggestRuntimeModules).
            // jdk.unsupported: sun.misc.Unsafe (libraries fail at launch without it).
            modules(
                "java.instrument",
                "java.prefs",
                // jdk.httpserver: the local link to the Chrome extension.
                "jdk.httpserver",
                "jdk.unsupported",
            )
            macOS {
                iconFile.set(project.file("app-icons/AutoPilot.icns"))
                bundleID = "com.jagdeep.autopilot"
            }
            windows {
                iconFile.set(project.file("src/jvmMain/resources/autopilot-icon.ico"))
                // Create both common Windows entry points during MSI installation.
                shortcut = true
                menuGroup = "AutoPilot"
                // Must remain stable across releases so MSI can upgrade the installed app.
                upgradeUuid = "bcdbe1a6-ef5a-4214-a3b6-021a08293966"
            }
            linux {
                iconFile.set(project.file("app-icons/autopilot-icon-linux-512.png"))
            }
        }
    }
}

// Windows Smart App Control blocks two unsigned files jpackage produces:
// - its installer helper DLL, so the MSI fails with Error 1723;
// - the generated AutoPilot.exe, which is new for every build, so the app "may be unsafe".
// The bundled runtime's DLLs are the JDK's own files, which Smart App Control already knows. So the
// runtime image gets the JDK's vendor-signed javaw.exe, and each MSI is then patched: the helper DLL is
// removed and the shortcuts start the app through that javaw.exe. Each script explains what it
// changes and refuses an MSI it doesn't recognise.
val removeUnsignedMsiHelperScript = rootProject.file("tools/windows/Remove-UnsignedMsiHelper.ps1")
val useSignedJavaLauncherScript = rootProject.file("tools/windows/Use-SignedJavaLauncher.ps1")
val isWindowsBuild = System.getProperty("os.name").startsWith("Windows")

// The runtime image is what both the app folder and the MSI bundle, so javaw.exe goes in there.
tasks.withType<AbstractJLinkTask>().configureEach {
    val javaw = javaHome.map { File(it, "bin/javaw.exe") }
    val runtime = destinationDir
    val windows = isWindowsBuild
    doLast {
        if (!windows) return@doLast
        val source = javaw.get()
        check(source.isFile) { "No javaw.exe in the packaging JDK at ${source.parent}." }
        source.copyTo(runtime.get().file("bin/javaw.exe").asFile, overwrite = true)
    }
}

tasks.withType<AbstractJPackageTask>()
    .matching { it.targetFormat == TargetFormat.Msi }
    .configureEach {
        val msiDir = destinationDir
        val mainClass = launcherMainClass
        val jvmArgs = launcherJvmArgs
        val runtime = runtimeImage
        val launcherName = packageName
        val launcherFile = layout.buildDirectory.file("compose/tmp/$name/launcher.txt")
        val removeHelper = removeUnsignedMsiHelperScript
        val signedLauncher = useSignedJavaLauncherScript
        doLast {
            fun powershell(script: File, vararg args: String) {
                val process = ProcessBuilder(
                    listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "RemoteSigned", "-File", script.absolutePath) + args,
                ).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText().trim()
                if (output.isNotEmpty()) println(output)
                check(process.waitFor() == 0) { "${script.name} failed; the MSI is not ready to install." }
            }
            val msi = msiDir.get().asFile.absolutePath
            powershell(removeHelper, "-Path", msi)
            // What jpackage's launcher would run: main class first, then one JVM option per line.
            val launcher = launcherFile.get().asFile
            launcher.parentFile.mkdirs()
            // jpackage adds these two to its launcher itself, so they're not in launcherJvmArgs.
            val packagerArgs = listOf("-Dcompose.application.resources.dir=\$APPDIR\\resources", "-Dskiko.library.path=\$APPDIR")
            launcher.writeText((listOf(mainClass.get()) + jvmArgs.get() + packagerArgs).joinToString("\n"))
            powershell(
                signedLauncher, "-Path", msi, "-Launcher", launcher.absolutePath,
                "-Javaw", runtime.get().file("bin/javaw.exe").asFile.absolutePath,
                "-LauncherName", "${launcherName.get()}.exe",
            )
        }
    }

// Tests see the same version property the desktop launchers pass.
tasks.withType<Test>().matching { it.name == "jvmTest" }.configureEach {
    systemProperty("autopilot.version", desktopVersion)
}

// Release signing comes from the git-ignored keystore.properties at the project root.
// Without it (for example on CI) the release APK is built unsigned.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.jagdeep.autopilot"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = libs.versions.androidApplicationId.get()
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = libs.versions.versionCode.get().toInt()
        versionName = libs.versions.versionName.get()
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/NOTICE",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE.txt"
            )
        }
    }
    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("release") {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    debugImplementation(libs.ui.tooling)
}
