# 30 — CMP Project Standard and Migration Guide

[All guides](00_INDEX.md) · [Git guide](19_GIT_GUIDE.md) · [One-command builds](01_ONE_COMMAND_BUILDS.md)

This is the standard layout for every Compose Multiplatform (CMP) project from now on, and the procedure for moving an older project to it. It follows the structure Android Studio's current CMP template generates (`shared` + `androidApp` + `desktopApp` + `iosApp`), extended with this template's `core:*` and `feature:*` modules and the `private/` folder. AutoPilot was migrated with this procedure on 13 November 2026; the recorded result is at the end.

**On this page:**
[1 Standard structure](#1-standard-project-structure) ·
[2 Module rules](#2-module-roles-and-rules) ·
[3 Package layout](#3-package-layout-inside-modules) ·
[4 Gradle setup](#4-gradle-setup) ·
[5 Private folder](#5-the-private-folder) ·
[6 Migration steps](#6-migration-procedure) ·
[7 Verification](#7-verification-checklist) ·
[8 Pitfalls](#8-pitfalls-found-during-the-autopilot-migration) ·
[9 Prompt](#9-reusable-prompt-for-claude) ·
[10 AutoPilot record](#10-recorded-migration--autopilot-13-november-2026)

---

## 1. Standard project structure

```text
<project>/
  settings.gradle.kts         # includes :shared, :androidApp, :desktopApp, :core:*, :feature:*
  build.gradle.kts            # plugins (apply false), values every module needs (rootProject.extra), boundary checks
  gradle.properties           # Gradle, Kotlin and Android flags
  gradle/libs.versions.toml   # the one version catalog
  gradle/wrapper/             # Gradle wrapper
  shared/                     # the shared app (KMP library, every target) and composition root
  androidApp/                 # Android application shell
  desktopApp/                 # desktop shell and MSI/DMG/DEB packaging
  iosApp/                     # Xcode project; links the Shared framework
  core/<name>/                # reusable technical modules (KMP libraries), product-neutral
  feature/<name>/             # one concrete feature each (KMP libraries)
  feature/shared/             # cross-feature contracts only
  .run/                       # shared Android Studio run/debug configurations
  .github/workflows/          # CI (only what the Mac can't build)
  AGENTS.md                   # instructions for coding agents (Part A global, Part B product)
  README.md                   # what the app is, how to run it, the module list
  private/                    # guides, scripts, plans, data, scratch (section 5)
```

### 1.1 Each module's source sets

| Module | Source sets |
| --- | --- |
| `shared` | `commonMain`, `androidMain`, `iosMain`, `jvmMain`, `commonTest`, `jvmTest` (plus `iosTest` / `androidHostTest` when needed) |
| `androidApp` | `main` (`kotlin/`, `res/`, `AndroidManifest.xml`) |
| `desktopApp` | `main` (`kotlin/`); `app-icons/` beside `src/` |
| `core:*`, `feature:*` | `commonMain` and whichever platform and test source sets the module needs |

## 2. Module roles and rules

| Module | Plugins | Owns | Never contains |
| --- | --- | --- | --- |
| `shared` | `kotlinMultiplatform`, `androidKotlinMultiplatformLibrary`, `composeMultiplatform`, `composeCompiler` (+ `serialization` if used) | `App()`, app-scoped wiring, navigation assembly, every `expect` and its `actual`s, the desktop window, classpath resources, Compose resources, the iOS framework `Shared` | Feature business logic |
| `androidApp` | `androidApplication`, `composeCompiler` (AGP built-in Kotlin) | `MainActivity`, `MainApplication`, manifest, launcher icons, application ID, versions, signing | App logic. It only calls `App()` and `AndroidPlatform.init(...)` |
| `desktopApp` | `kotlinJvm`, `composeMultiplatform`, `composeCompiler` (+ `composeHotReload`) | `main()` (one line: `runDesktopApp()`), `compose.desktop { application { … } }`, package names and upgrade IDs, icons, packaging hooks | App logic |
| `iosApp` | Xcode | Swift `@main` app, `ContentView` calling `MainViewControllerKt.MainViewController()`, the build phase running `:shared:embedAndSignAppleFrameworkForXcode` | Kotlin |
| `core:*` | `kotlinMultiplatform`, `androidKotlinMultiplatformLibrary` | One reusable technical concern; product-neutral names | Dependencies on `feature:*`, `shared` or another technical core module |
| `feature:*` | same as core (+ Compose) | One product feature: `data`, `domain`, `ui` | Dependencies on another concrete feature |

Dependency direction:

```text
androidApp / desktopApp / iosApp -> shared -> feature:* -> core:common / core:logger / independent core:*
                                                   \-> feature:shared
core:* -X-> feature:*, shared or an app shell
```

**Why the platform folders stay in `shared`, not in the shells:**

- Kotlin requires every `actual` to be in the same module, and the same package, as its `expect`.
- `App()` calls those factories.
- iOS has no Kotlin shell module of its own.
- Most of these classes are `internal`, and their tests rely on that.

The shells hold only what the operating system needs to start the app and package it.

## 3. Package layout inside modules

### 3.1 `shared` (the same packages in every source set)

```text
com.jagdeep.<app>/
  App.kt                      # root composable, used by every platform
  MainViewController.kt       # iosMain only: the iOS entry point
  di/                         # AppServices: app-scoped dependency wiring (the composition root)
  data/                       # app-level implementations (for example an in-memory log)
  platform/                   # one capability per file pair: Xxx.kt (expect) + Xxx.android.kt / Xxx.ios.kt / Xxx.jvm.kt
                              #   plus AndroidPlatform.kt (androidMain) and desktop data paths (jvmMain)
  presentation/navigation/    # AppNavHost, NavigationState, transitions, top-level routes
  presentation/shell/         # adaptive scaffold, desktop shell, sidebar (+ per-platform actuals)
  desktop/                    # jvmMain only: runDesktopApp(), title bar, toolbar, window controls, window state
```

Tests use the same packages. End-to-end browser or live tests go in `integration/`.

### 3.2 `feature:<name>`

```text
com.jagdeep.<feature>/
  data/            # repository implementations, mappers; no Compose
  domain/          # models, repository interfaces, use cases, ViewModels
  ui/screens/      # composable screens only
  ui/components/   # feature-private composables only
  ui/navigation/   # Navigation 3 entry provider(s)
  ui/util/         # optional: non-composable UI helpers
```

### 3.3 Entry points

| Platform | Shell file | Calls |
| --- | --- | --- |
| Android | `androidApp/.../MainActivity.kt` | `setContent { App() }` |
| Android | `androidApp/.../MainApplication.kt` | `AndroidPlatform.init(applicationContext)` (in `shared` `platform/`: stores the context, starts logging) |
| Desktop | `desktopApp/.../main.kt` | `fun main() = runDesktopApp()` (in `shared` `desktop/`) |
| iOS | `iosApp/iosApp/ContentView.swift` | `import Shared` → `MainViewControllerKt.MainViewController()` |

## 4. Gradle setup

### 4.1 Catalog plugins (`gradle/libs.versions.toml`)

```toml
androidApplication = { id = "com.android.application", version.ref = "agp" }
androidKotlinMultiplatformLibrary = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }
composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
composeCompiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
composeHotReload = { id = "org.jetbrains.compose.hot-reload", version.ref = "composeHotReload" }
kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlinJvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
```

Declare each with `apply false` in the root `build.gradle.kts`.

### 4.2 `gradle.properties`

- **Remove `android.builtInKotlin=false`.** `androidApp` uses AGP 9's built-in Kotlin (a `kotlin { compilerOptions { … } }` block, no Kotlin Android plugin). That flag existed only for the old module that combined KMP with `com.android.application`; with it set, `androidApp` fails with *"Unresolved reference 'compilerOptions'"*.
- `android.newDsl=false` may stay while other modules still need it. AutoPilot builds with it set.

### 4.3 `shared/build.gradle.kts` essentials

```kotlin
kotlin {
    androidLibrary {
        namespace = "com.jagdeep.<app>.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources { enable = true }   // package Compose resources into the Android app
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework { baseName = "Shared"; isStatic = true }
    }
    jvm()
    sourceSets { /* commonMain/androidMain/jvmMain/… dependencies, unchanged from the old module */ }
}
```

- The generated Compose `Res` class moves to package `<rootProjectName lower-case>.shared.generated.resources`. Update those imports.
- Values every module needs, such as a per-commit version, are computed once in the root build (`extra["desktopVersion"] = …`) and read with `rootProject.extra["desktopVersion"] as String`.

### 4.4 `androidApp/build.gradle.kts` essentials

```kotlin
plugins { alias(libs.plugins.androidApplication); alias(libs.plugins.composeCompiler) }
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_11 } }
dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.ui.tooling)
}
android {
    namespace = "com.jagdeep.<app>"          // keep the old one: R class and manifest class names stay valid
    defaultConfig { applicationId = …; versionCode = …; versionName = … }   // unchanged
    signingConfigs { … } ; buildTypes { … }; buildFeatures { compose = true }
}
```

### 4.5 `desktopApp/build.gradle.kts` essentials

```kotlin
plugins { alias(libs.plugins.kotlinJvm); alias(libs.plugins.composeMultiplatform); alias(libs.plugins.composeCompiler); alias(libs.plugins.composeHotReload) }
dependencies { implementation(project(":shared")); implementation(compose.desktop.currentOs); implementation(libs.kotlinx.coroutinesSwing) }
compose.desktop { application { mainClass = "com.jagdeep.<app>.MainKt"; nativeDistributions { … } } }
```

Keep `packageName`, `bundleID`, `upgradeUuid` and the icons unchanged, so installed apps update in place. Packaging hooks (for example AutoPilot's Smart App Control fixes) move here unchanged.

### 4.6 Task names after migration

| Old | New |
| --- | --- |
| `:composeApp:run`, `:composeApp:package<Release><Format>`, `:composeApp:createReleaseDistributable` | `:desktopApp:…` |
| `:composeApp:assembleDebug`, `:composeApp:installDebug`, `:composeApp:bundleRelease` | `:androidApp:…` |
| `:composeApp:jvmTest`, `:composeApp:embedAndSignAppleFrameworkForXcode`, `:composeApp:link…` | `:shared:…` |
| `composeApp/build/compose/binaries/…` | `desktopApp/build/compose/binaries/…` |

## 5. The private folder

```text
private/
  docs/          # tracked: numbered guides NN_TOPIC.md by how often they're used; 00_INDEX.md lists them
  scripts/       # tracked: build, install, debug, VM and Git helper scripts (dev.sh on the Mac, windows/ for Windows)
  reports/       # tracked: dated investigation and validation reports worth keeping (YYYY-MM-DD-topic.md)
  <data>/        # tracked: project data the app or build needs, one folder per kind (for example answer-banks/)
  plans/         # git-ignored: implementation plans, proposals, roadmaps, phase tracking
  signing/       # git-ignored: release keys and keystore.properties material; never commit
  secrets/       # git-ignored: .env files and other secrets; never commit
  misc/          # git-ignored: anything temporary or unimportant (backups, screenshots, captures, research, scratch)
  zynk-cmp-main/ # git-ignored: read-only reference copy of the template project
  Test/          # git-ignored: read-only reference copy of the current Android Studio CMP template
```

`.gitignore` block:

```gitignore
/private/misc/
/private/plans/
/private/secrets/
.env
.env.*
/private/signing/
keystore.properties
*.jks
*.keystore
/private/zynk-cmp-main/
/private/Test/
```

Rules:

- **When unsure, use `misc/`.** It is ignored as a whole.
- Never make the build, the app or a tracked guide depend on a file in `misc/`.
- Gradle and CI may run scripts from `private/scripts/`. That's why it's tracked: the build machines pull it from Git.
- Remote build machines build only from Git: commit and push on the Mac, pull on the machine, then build.

## 6. Migration procedure

Do it in this order, one machine at a time. Commit only when each verification passes.

### Step 0 — Prepare

```sh
git status --short          # must be clean; commit or stash first
git pull --ff-only
```

Copy the new Android Studio CMP template into `private/Test/` (ignored) as the reference.

### Step 1 — Record a baseline

The goal is "nothing broke", so measure the current project first. Note every failure: they are pre-existing, not caused by the migration.

```sh
./gradlew clean
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./gradlew --continue \
  jvmTest verifyFeatureModuleBoundaries \
  :composeApp:assembleDebug :composeApp:linkDebugFrameworkIosSimulatorArm64 :composeApp:createReleaseDistributable
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild -project iosApp/iosApp.xcodeproj \
  -scheme iosApp -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

Keep:

- the test totals (suites, tests, skipped, failures)
- a copy of the debug APK
- the desktop app's file list and launcher `.cfg`

Fix pre-existing compile errors on any target (often iOS) **in separate commits**. Then the migration diff contains only the migration.

### Step 2 — Rename `composeApp` to `shared` and move the shells

```sh
git mv composeApp shared
mkdir -p androidApp/src/main/kotlin/<pkg path> desktopApp/src/main/kotlin/<pkg path>
git mv shared/src/androidMain/AndroidManifest.xml androidApp/src/main/AndroidManifest.xml
git mv shared/src/androidMain/res androidApp/src/main/res
git mv shared/src/androidMain/kotlin/<pkg>/MainActivity.kt    androidApp/src/main/kotlin/<pkg>/
git mv shared/src/androidMain/kotlin/<pkg>/MainApplication.kt androidApp/src/main/kotlin/<pkg>/
git mv shared/app-icons desktopApp/app-icons                  # if present
git mv shared/src/jvmMain/kotlin/<pkg>/main.kt shared/src/jvmMain/kotlin/<pkg>/desktop/DesktopApp.kt
```

`git mv` keeps each file's history.

### Step 3 — Entry points

1. **Desktop:** rename `fun main()` in `DesktopApp.kt` to a public `fun runDesktopApp()`. Create `desktopApp/src/main/kotlin/<pkg>/main.kt` containing `fun main() = runDesktopApp()`.
2. **Android:** create `shared/src/androidMain/.../platform/AndroidPlatform.kt` (an object holding `context`, plus `init(context)` that also starts logging). Replace uses of `MainApplication.<context>` in `shared` with `AndroidPlatform.context`. `MainApplication.onCreate` calls `AndroidPlatform.init(applicationContext)`.
3. Make public only what a shell calls (`App`, `runDesktopApp`, `AndroidPlatform`). Everything else stays `internal`.

### Step 4 — Build files

1. **`settings.gradle.kts`:** replace `include(":composeApp")` with `include(":shared")`, `include(":androidApp")` and `include(":desktopApp")`.
2. **Catalog:** add `kotlinJvm`. **Root build:** add `alias(libs.plugins.kotlinJvm) apply false`, and move shared values (the per-commit version) into `extra`.
3. **`shared/build.gradle.kts`:** take the old module's `sourceSets` unchanged. Swap `androidApplication` for `androidKotlinMultiplatformLibrary` and `androidTarget {}` for `androidLibrary {}` (section 4.3). Name the iOS framework `Shared`. Remove `compose.desktop`, `android {}` and the signing setup.
4. **`androidApp/build.gradle.kts`:** move the old `android {}` block (namespace, application ID, versions, signing, packaging) here (section 4.4).
5. **`desktopApp/build.gradle.kts`:** move `compose.desktop {}` and any packaging hooks here. Fix icon paths (section 4.5).
6. **`gradle.properties`:** delete `android.builtInKotlin=false`.
7. **Compose resources:** update `import <old>.composeapp.generated.resources.…` to `<app>.shared.generated.resources.…`.

### Step 5 — iOS

```sh
sed -i '' 's/^import ComposeApp$/import Shared/' iosApp/iosApp/ContentView.swift
sed -i '' 's|:composeApp:embedAndSignAppleFrameworkForXcode|:shared:embedAndSignAppleFrameworkForXcode|' iosApp/iosApp.xcodeproj/project.pbxproj
```

### Step 6 — Group `shared` by role (section 3.1)

- Move each `expect` file and **all** its `actual`s together into `platform/`. Kotlin requires them to share a package.
- Move `AppServices` to `di/`, app-level implementations to `data/`, and desktop window files to `desktop/`. Tests mirror the packages.
- After `git mv`, change each moved file's `package` line. Then add imports wherever another package uses its declarations.
- Watch for names used **inside string templates** (`"${NAME}"`), **fully qualified references** (`com.example.app.Foo(...)`), and **existing explicit imports** of the old package. A text search that skips strings misses the first kind.
- Compile every target after the move. The compiler lists anything missed.

### Step 7 — Update every reference

```sh
grep -rn "composeApp\|ComposeApp\|composeapp" --exclude-dir=build --exclude-dir=.git --exclude-dir=.gradle .
```

Update:

- scripts (Gradle task names, output paths)
- CI workflows
- `.run` configurations (module `<Project>.composeApp.jvmMain` → `<Project>.shared.jvmMain`)
- the README module list
- guides
- comments that name file paths
- AGENTS.md

### Step 8 — Verify (section 7), then commit and push

Commit the restructure, the package grouping and unrelated fixes **separately**. Then push, and build and install on every desktop machine from Git, for example `./dev.sh run legion` and `run mac`.

## 7. Verification checklist

### 7.1 Migrate Android Studio and iOS run configurations

This project no longer has a `composeApp` module. The current modules are `androidApp`,
`desktopApp`, and `shared`. If Android Studio reports that it cannot find
`:composeApp:assembleDebug`, the run configuration is stale:

1. Sync the project from `settings.gradle.kts` at the repository root.
2. Select the shared **Android app** run configuration, which uses the `androidApp` module.
3. Delete any old configuration that contains `:composeApp:assembleDebug`.
4. Choose an emulator or connected Android device and run again.

For iOS, open `iosApp/iosApp.xcodeproj` in Xcode and select the shared `iosApp` scheme. The Mac
must use the full Xcode developer directory, not only Command Line Tools:

```sh
xcode-select -p
sudo xcode-select --switch /Applications/Xcode.app/Contents/Developer
sudo xcodebuild -runFirstLaunch
```

Verify with `xcodebuild -version`. If `xcode-select -p` still prints
`/Library/Developer/CommandLineTools`, iOS Gradle sync and the iOS Run button remain disabled.

The shared configurations added by this migration are `.run/Android-app.run.xml` and the shared
`iosApp` scheme at `iosApp/iosApp.xcodeproj/xcshareddata/xcschemes/iosApp.xcscheme`.

Live verification after the migration:

```sh
./gradlew :androidApp:assembleDebug --no-daemon --console=plain
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -configuration Debug \
  -sdk iphonesimulator \
  -derivedDataPath /tmp/autopilot-ios-derived \
  build
```

Both commands must finish with `BUILD SUCCESSFUL`. The Android verification confirms that
`androidApp` replaces the old `composeApp` module. The iOS verification confirms that the shared
`iosApp` scheme and Kotlin framework build phase are valid. The `DEVELOPER_DIR` prefix is useful for
testing; selecting Xcode with `xcode-select --switch` makes the same toolchain available to
Android Studio and Gradle without the prefix.

| Check | Command or method | Must match the baseline |
| --- | --- | --- |
| All JVM tests | `./gradlew jvmTest`, then sum `build/test-results/jvmTest/*.xml` | Same suites and test count; no new failures |
| Module boundaries | `./gradlew verifyFeatureModuleBoundaries` | Passes |
| Android | `./gradlew :androidApp:assembleDebug`, then `aapt2 dump badging` and the manifest | Same package, label, icon, `MainApplication`, `MainActivity`, versions; Compose resources present |
| iOS framework | `DEVELOPER_DIR=… ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64` | Links |
| iOS app | `xcodebuild … build` (Step 1 command) | BUILD SUCCEEDED |
| Desktop app | `./gradlew :desktopApp:createReleaseDistributable`, compare the file list and `.cfg` | Same files (the app jar becomes `shared` + `desktopApp`); same main class, JVM options, version, bundle ID |
| Desktop runtime | Start the built app for 15 s | Window opens, no exceptions |
| Windows | `dev.sh run legion` | MSI builds, updates in place, the app's JVM loads, 0 Smart App Control blocks |
| References | `grep -rn composeApp` | Nothing left |

## 8. Pitfalls found during the AutoPilot migration

| Pitfall | Symptom | Fix |
| --- | --- | --- |
| Stale build output from a renamed project folder | Android dexing fails: *"file … AutoClicker/… is located outside the root directory"* | `./gradlew clean` (no code change) |
| JVM-only APIs in common code | iOS: *"Unresolved reference 'putIfAbsent'"* | `if (key !in map) map[key] = value` |
| `Dispatchers.IO` in common code | iOS: *"Cannot access 'val IO' … internal"* | `import kotlinx.coroutines.IO` |
| `android.builtInKotlin=false` | `androidApp`: *"Unresolved reference 'compilerOptions'"* | Remove the flag |
| `expect`/`actual` split across packages | *"expect declaration has no actual"* | Move each set of files together |
| Names inside string templates and fully qualified references | Unresolved references in tests after a package move | Add the import, or fix the qualified name |
| Comma or period in a backtick test name | Kotlin/Native test compile: *"Name contains illegal characters"* | Avoid `,` `.` `:` `/` `<` `>` `[` `]` `;` in test names that also compile for iOS |
| `@Ignore("reason")` in common tests | Kotlin/Native test compile: *"Too many arguments for 'constructor(): Ignore'"* | `// Ignored: reason` above a bare `@Ignore` |
| `:shared` and `:feature:shared` | Both desktop jars start with `shared-jvm` | Harmless: Compose adds a hash suffix |
| Android context reached through `MainApplication` | `shared` can't see `androidApp` classes | Hold the context in `AndroidPlatform` in `shared` |
| `dev.sh` refuses to run | *"the Mac has uncommitted changes"* while `private/Test` is untracked | Ignore reference copies in `.gitignore` |

## 9. Reusable prompt for Claude

Paste this into Claude Code at the root of the project to migrate. Copy this guide and the new template into the project's `private/` first.

```text
Migrate this Compose Multiplatform project to the standard layout in
private/docs/30_CMP_PROJECT_STANDARD_AND_MIGRATION.md (copy it from AutoPilot if missing), using
the Android Studio CMP template in private/Test as the reference. The code must not break.

Follow the guide's section 6 in order:
1. Check git is clean. Record a baseline first: clean, all JVM tests, module boundaries, the Android
   debug APK, the iOS framework link and an xcodebuild simulator build (DEVELOPER_DIR set to Xcode),
   and the desktop distributable. Save test totals, the APK and the desktop file list. Report every
   pre-existing failure. Fix pre-existing compile errors in separate commits, and ask before changing
   behaviour.
2. git mv composeApp to shared; move MainActivity, MainApplication, the manifest and res to
   androidApp; app-icons to desktopApp; desktop main.kt to shared desktop/DesktopApp.kt as
   runDesktopApp(); add AndroidPlatform in shared platform/.
3. Write shared/androidApp/desktopApp build files as in section 4, move the version and packaging
   unchanged, keep application ID, bundle ID, MSI upgrade code and data locations, remove
   android.builtInKotlin=false, update the Compose Res imports and the iOS framework name.
4. Group shared by role as in section 3.1 (di, data, platform with expect+actuals together,
   presentation, desktop; tests mirrored, integration/ for end-to-end tests), with git mv and
   import fixes.
5. Update every composeApp reference: scripts, CI, .run, README, guides, AGENTS.md (section 7 grep).
6. Verify everything in section 7 against the baseline and show me the comparison table. Commit the
   restructure, the package grouping and any fixes as separate commits. Don't push until I say "push";
   then push and run the project's build/install script on each desktop machine.
Keep the private/ folder layout from section 5 and update AGENTS.md to match.
```

## 10. Recorded migration — AutoPilot, 13 November 2026

| Commit | Change |
| --- | --- |
| `b309cc0` | Pre-existing iOS fix: `putIfAbsent` in `feature:home` |
| `bfdebc7` | Pre-existing iOS fix: `kotlinx.coroutines.IO` import |
| `37c2b34` | `composeApp` → `shared` + `androidApp` + `desktopApp`; references updated |
| `3d39ddb` | `private/Test` ignored as a reference copy |
| `2745ebb` | Bundled answer bank refreshed (663 questions); its test updated |
| `a0d04ff` | `shared` grouped into `di`, `data`, `platform`, `presentation`, `desktop`; tests mirrored |

| Check | Baseline | After |
| --- | --- | --- |
| JVM tests | 113 suites, 568 tests, 13 skipped, 1 failure (answer-bank count) | 113 suites, 568 tests, 13 skipped, 0 failures |
| Module boundaries | Pass | Pass |
| Android debug APK | Same identity | `com.jagdeep.autopilot`, label, icon, `MainApplication`, `MainActivity`, 1.0.0 (1): unchanged |
| iOS | Didn't compile (2 errors, fixed separately) | Framework links; Xcode BUILD SUCCEEDED; iOS tests: 366 run, 11 skipped, 0 failures (after renaming 24 comma test names and 11 `@Ignore` reasons) |
| macOS app | 239 files, version 1.0.69 | Same files and launcher; window opens, no errors |
| Windows (Legion) | 1.0.61 installed | 1.0.71 built from Git, updated in place, JVM loaded, 0 Smart App Control blocks |
