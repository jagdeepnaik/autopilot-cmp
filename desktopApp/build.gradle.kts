import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
}

// The desktop app shell: main() and the macOS, Windows and Linux packages. Everything the app
// runs, including its window and classpath resources, comes from :shared.

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
}

val desktopVersion = rootProject.extra["desktopVersion"] as String

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
            // JDK modules the packaged runtime must contain (from :desktopApp:suggestRuntimeModules).
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
                iconFile.set(rootProject.file("shared/src/jvmMain/resources/autopilot-icon.ico"))
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
val removeUnsignedMsiHelperScript = rootProject.file("private/scripts/windows/Remove-UnsignedMsiHelper.ps1")
val useSignedJavaLauncherScript = rootProject.file("private/scripts/windows/Use-SignedJavaLauncher.ps1")
val isWindowsBuild = System.getProperty("os.name").startsWith("Windows")

// The runtime image is what both the app folder and the MSI bundle. jlink copies the JDK's native
// files from its jmods, which are unsigned; Smart App Control can then refuse them (it blocked
// runtime\bin\server\jvm.dll on Legion). The JDK's installed bin folder holds the same files of the
// same build, signed by the vendor. So every DLL and EXE in the runtime's bin is replaced with its
// signed twin, and the signed javaw.exe is added for the shortcuts.
tasks.withType<AbstractJLinkTask>().configureEach {
    val jdk = javaHome.map { File(it) }
    val runtime = destinationDir
    val windows = isWindowsBuild
    doLast {
        if (!windows) return@doLast
        val jdkHome = jdk.get()
        val image = runtime.get().asFile
        fun javaVersion(home: File) = File(home, "release").readLines().firstOrNull { it.startsWith("JAVA_VERSION=") }
        check(javaVersion(jdkHome) == javaVersion(image)) {
            "The runtime image (${javaVersion(image)}) wasn't built from the packaging JDK (${javaVersion(jdkHome)})."
        }
        val jdkBin = File(jdkHome, "bin")
        val imageBin = File(image, "bin")
        var replaced = 0
        imageBin.walkTopDown()
            .filter { it.isFile && (it.extension.equals("dll", true) || it.extension.equals("exe", true)) }
            .forEach { file ->
                val signed = File(jdkBin, file.relativeTo(imageBin).path)
                if (signed.isFile) { signed.copyTo(file, overwrite = true); replaced++ }
            }
        val javaw = File(jdkBin, "javaw.exe")
        check(javaw.isFile) { "No javaw.exe in the packaging JDK at $jdkBin." }
        javaw.copyTo(File(imageBin, "javaw.exe"), overwrite = true)
        println("Runtime image: replaced $replaced native files with the JDK's signed copies and added javaw.exe.")
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
