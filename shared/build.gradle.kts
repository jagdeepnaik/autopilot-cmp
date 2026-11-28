plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.serialization)
}

// The shared app: everything the Android, iOS and desktop apps run. androidApp and desktopApp hold
// only their platform entry points and packaging; the iOS app links the "Shared" framework.

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

// cosmos/cosmos.properties: the Cosmos Q&A sync address, from the version catalog's cosmosUrl
// (or -PcosmosUrl=…), so the address is set in exactly one place.
val cosmosUrl = providers.gradleProperty("cosmosUrl").orElse(libs.versions.cosmosUrl.get())
val generateCosmosConfig by tasks.registering {
    val url = cosmosUrl
    val output = layout.buildDirectory.dir("generated/cosmos-config")
    inputs.property("cosmosUrl", url)
    outputs.dir(output)
    doLast {
        output.get().file("cosmos/cosmos.properties").asFile.apply { parentFile.mkdirs(); writeText("url=${url.get()}\n") }
    }
}

val desktopVersion = rootProject.extra["desktopVersion"] as String
val desktopBuildIdentity = rootProject.extra["desktopBuildIdentity"] as String

// AndroidBuildInfo.kt: the Android app's build identity (for the update check's build number) and the
// Cosmos address. Desktop reads both from launcher properties and resources instead.
val generateAndroidBuildInfo by tasks.registering {
    val identity = desktopBuildIdentity
    val url = cosmosUrl
    val output = layout.buildDirectory.dir("generated/android-build-info")
    inputs.property("identity", identity)
    inputs.property("cosmosUrl", url)
    outputs.dir(output)
    doLast {
        output.get().file("com/jagdeep/autopilot/platform/AndroidBuildInfo.kt").asFile.apply {
            parentFile.mkdirs()
            writeText("package com.jagdeep.autopilot.platform\n\ninternal object AndroidBuildInfo {\n    const val BUILD_IDENTITY = \"$identity\"\n    const val COSMOS_URL = \"${url.get()}\"\n}\n")
        }
    }
}

kotlin {
    androidLibrary {
        namespace = "com.jagdeep.autopilot.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        // Package the Compose resources (the app logo) into the Android app.
        androidResources {
            enable = true
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
            binaryOption("bundleId", "com.jagdeep.autopilot.shared")
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
            // Cosmos Q&A sync: the HTTPS engine for feature:home's client.
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmTest.dependencies {
            implementation(compose.desktop.uiTestJUnit4)
            // BundledQaKeysTest reads the packaged routine Q&A files.
            implementation(libs.androidx.sqlite.bundled)
        }
        // answer-banks/index.txt: lists every bank in resources/answer-banks, so adding a bank file is enough.
        jvmMain { resources.srcDir(generateAnswerBankIndex); resources.srcDir(generateCosmosConfig) }
        androidMain { kotlin.srcDir(generateAndroidBuildInfo) }
    }
}

// Tests see both the compact display version and complete SemVer build identity passed to launchers.
tasks.withType<Test>().matching { it.name == "jvmTest" }.configureEach {
    systemProperty("autopilot.version", desktopVersion)
    systemProperty("autopilot.buildIdentity", desktopBuildIdentity)
}
