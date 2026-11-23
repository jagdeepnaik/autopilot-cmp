# 01 — CMP Project Standard and Migration Guide

[All guides](../00_INDEX.md) · [Git guide](02_GIT_GUIDE.md) · [One-command builds](../project/20_ONE_COMMAND_BUILDS.md)

This is the standard layout for every Compose Multiplatform (CMP) project, and the procedure for moving an older project to it. It follows the structure that Android Studio's current CMP template generates (`shared` + `androidApp` + `desktopApp` + `iosApp`). It extends that structure with this template's `core:*` and `feature:*` modules, its phone and desktop UI layout, a small fixed set of run configurations, and the `private/` folder.

AutoPilot is the reference implementation. It was migrated on 13 November 2026, and the structure below was read from its source on 23 November 2026. Where this guide shows AutoPilot names (`com.jagdeep.autopilot`, `AutoPilot`), substitute your project's.

**Copy this file into the project you're migrating, then paste the [prompt in section 12](#12-migration-prompt-for-the-agent) into the coding agent.**

**On this page:**
[1 Structure](#1-standard-project-structure) ·
[2 Module roles](#2-module-roles-and-rules) ·
[3 Packages](#3-package-layout-inside-modules) ·
[4 Core UI layout](#4-core-ui-layout-phone-and-desktop) ·
[5 Gradle](#5-gradle-setup) ·
[6 Private folder](#6-the-private-folder-and-dev-command) ·
[7 Run configurations](#7-run-configurations) ·
[8 Migration steps](#8-migration-procedure) ·
[9 Verification](#9-verification-checklist) ·
[10 Pitfalls](#10-pitfalls) ·
[11 AutoPilot record](#11-recorded-migration--autopilot) ·
[12 Prompt](#12-migration-prompt-for-the-agent)

---

## 1. Standard project structure

```text
<project>/
  settings.gradle.kts           # rootProject.name; includes :shared, :androidApp, :desktopApp, :core:*, :feature:*
  build.gradle.kts              # every plugin with apply false; shared values in extra; verifyFeatureModuleBoundaries
  gradle.properties             # Gradle, Kotlin and Android flags (no android.builtInKotlin=false)
  gradle/libs.versions.toml     # the one version catalog, including versionName/versionCode and the application ID
  gradle/wrapper/               # Gradle wrapper
  gradlew, gradlew.bat
  dev                           # executable root command: ./dev <action> <target> (section 6.2)
  AGENTS.md                     # agent instructions: Part A common, Part B product
  README.md                     # what the app is, how to run it, the module list
  .gitignore                    # template ignores + the private/ block (section 6.1)
  .run/                         # the shared run configurations, and nothing else (section 7)
  .github/workflows/            # CI, only for what the Mac can't build
  shared/                       # the app: KMP library, every target, composition root
  androidApp/                   # Android application shell
  desktopApp/                   # desktop shell and MSI/DMG/DEB packaging
  iosApp/                       # Xcode project linking the Shared framework
  core/<name>/                  # reusable technical modules, product-neutral
  feature/shared/               # cross-feature contracts only
  feature/<name>/               # one concrete feature each
  private/                      # guides, scripts, reports, data, plans, scratch (section 6)
```

### 1.1 Full tree, with the files each module must have

```text
shared/
  build.gradle.kts
  src/
    commonMain/kotlin/com/jagdeep/<app>/
      App.kt                              # root composable for every platform
      di/AppServices.kt                   # app-scoped services, created once
      data/                               # app-level implementations (for example MemoryLiveLog)
      platform/Xxx.kt                     # expect declarations, one capability per file
      presentation/navigation/            # AppNavHost, NavigationState (remember + saved state), AppTransitions
      presentation/shell/                 # DesktopShell, its model, sidebar pieces (common code, desktop use)
    commonMain/composeResources/          # drawables, strings (generated Res)
    androidMain/kotlin/com/jagdeep/<app>/
      platform/AndroidPlatform.kt         # holds the application Context; init(context)
      platform/Xxx.android.kt             # actuals
    iosMain/kotlin/com/jagdeep/<app>/
      MainViewController.kt               # fun MainViewController() = ComposeUIViewController { App() }
      platform/Xxx.ios.kt
    jvmMain/kotlin/com/jagdeep/<app>/
      desktop/DesktopApp.kt               # fun runDesktopApp(): the window, title bar and shell
      desktop/…                           # title bar, toolbar, window controls, window preferences
      platform/Xxx.jvm.kt, DesktopDataPaths.kt
    jvmMain/resources/                    # classpath resources (icons, bundled data)
    commonTest/, jvmTest/ (+ iosTest/, androidHostTest/ when needed)
androidApp/
  build.gradle.kts
  src/main/AndroidManifest.xml
  src/main/kotlin/com/jagdeep/<app>/MainActivity.kt      # enableEdgeToEdge(); setContent { App() }
  src/main/kotlin/com/jagdeep/<app>/MainApplication.kt   # AndroidPlatform.init(applicationContext)
  src/main/res/                                          # launcher icons, themes
desktopApp/
  build.gradle.kts
  src/main/kotlin/com/jagdeep/<app>/main.kt              # fun main() = runDesktopApp()
  app-icons/                                             # .icns, Linux PNG (the .ico may live in shared resources)
iosApp/
  iosApp.xcodeproj/                                      # build phase runs :shared:embedAndSignAppleFrameworkForXcode
  iosApp.xcodeproj/xcshareddata/xcschemes/iosApp.xcscheme  # shared scheme, tracked
  iosApp/iOSApp.swift, ContentView.swift                 # import Shared; MainViewControllerKt.MainViewController()
  Configuration/Config.xcconfig
core/<name>/
  build.gradle.kts
  src/commonMain/kotlin/com/jagdeep/<name>/…, src/<platform>Main/…, src/commonTest/…
feature/<name>/
  build.gradle.kts
  src/commonMain/kotlin/com/jagdeep/<name>/{data,domain,ui/screens,ui/components,ui/navigation,ui/util}
  src/commonTest/, src/jvmTest/ (+ jvmMain/ only when the feature has desktop-only code)
```

### 1.2 Source sets per module

| Module | Source sets |
| --- | --- |
| `shared` | `commonMain`, `androidMain`, `iosMain`, `jvmMain`, `commonTest`, `jvmTest` (plus `iosTest` or `androidHostTest` when needed) |
| `androidApp` | `main` (`kotlin/`, `res/`, `AndroidManifest.xml`) |
| `desktopApp` | `main` (`kotlin/`); `app-icons/` beside `src/` |
| `core:*` | `commonMain` plus each platform it implements (`androidMain`, `iosMain`, `jvmMain`); `commonTest`, `jvmTest`, `androidHostTest`, `androidDeviceTest` as needed |
| `feature:*` | `commonMain`, `commonTest`, `jvmTest`; `jvmMain` only for desktop-only code |

### 1.3 The module catalog

AutoPilot's modules. A new project keeps the template core modules even if it doesn't use them yet, and adds its own product core modules beside them.

| Module | Package | Concern | Kind |
| --- | --- | --- | --- |
| `core:common` | `com.jagdeep.common` (`platform/`, `time/`) | Platform name, clock | Template |
| `core:logger` | `com.jagdeep.logger` | Napier logging setup | Template |
| `core:ui` | `com.jagdeep.ui` (`components/`, `navigation/`, `platform/`, `themes/`, `util/`) | Design system, navigation infrastructure, adaptive scaffold (section 4) | Template |
| `core:persistence` | `com.jagdeep.persistence` (`preferences/`, `room/<domain>/`) | DataStore preference stores, the Room database, DAOs, entities, schemas | Template |
| `core:network` | `com.jagdeep.network` (`connectivity/`, `discovery/`, `speed/`, `util/`) | Connectivity, mDNS discovery | Template |
| `core:notifications` | `com.jagdeep.notifications` | System notifications | Template |
| `core:transfer` | `com.jagdeep.transfer` (`protocol/`, `runtime/`) | Socket transfer runtime | Template |
| `core:automation` | `com.jagdeep.automation` | Mouse, keyboard state, front app, Accessibility | Product |
| `core:localbridge` | `com.jagdeep.localbridge` | Loopback link to the browser extension | Product |
| `feature:shared` | `com.jagdeep.shared.<capability>` | Contracts used by two or more features | Template |
| `feature:home`, `feature:activity`, `feature:settings` | `com.jagdeep.<feature>` | One feature each | Template skeleton, product content |

## 2. Module roles and rules

| Module | Plugins | Owns | Never contains |
| --- | --- | --- | --- |
| `shared` | `kotlinMultiplatform`, `androidKotlinMultiplatformLibrary`, `composeMultiplatform`, `composeCompiler` (plus `serialization` if used) | `App()`, app-scoped wiring, navigation assembly, every `expect` and its `actual`s, the desktop window, classpath and Compose resources, the iOS framework `Shared` | Feature business logic |
| `androidApp` | `androidApplication`, `composeCompiler` (AGP built-in Kotlin) | `MainActivity`, `MainApplication`, manifest, launcher icons, application ID, versions, signing | App logic. It only calls `App()` and `AndroidPlatform.init(...)` |
| `desktopApp` | `kotlinJvm`, `composeMultiplatform`, `composeCompiler`, `composeHotReload` | `main()` (one line), `compose.desktop { application { … } }`, package name, bundle ID, MSI upgrade UUID, icons, packaging hooks | App logic |
| `iosApp` | Xcode | Swift `@main` app, `ContentView`, the framework build phase, the shared scheme | Kotlin |
| `core:*` | `kotlinMultiplatform`, `androidKotlinMultiplatformLibrary`, `androidLint` (plus Compose or Room plugins if needed) | One reusable technical concern, with product-neutral names | A dependency on `feature:*`, `shared`, an app shell, or another technical core module |
| `feature:*` | Same as core, plus Compose | One product feature: `data`, `domain`, `ui` | A dependency on another concrete feature |

Dependency direction:

```text
androidApp / desktopApp / iosApp -> shared -> feature:* -> core:common / core:logger / independent core:*
                                                   \-> feature:shared
independent core:* -> core:common and/or core:logger only
core:* -X-> feature:*, shared or an app shell
```

`verifyFeatureModuleBoundaries` (root build) fails the build when a concrete feature depends on another concrete feature.

**Why the platform folders stay in `shared`, not in the shells:**

- Kotlin requires every `actual` to be in the same module, and the same package, as its `expect`.
- `App()` calls those factories.
- iOS has no Kotlin shell module of its own.
- Most of these classes are `internal`, and their tests rely on that.

The shells hold only what the operating system needs to start and package the app. Make public only what a shell calls: `App`, `runDesktopApp`, `AndroidPlatform`, `MainViewController`.

## 3. Package layout inside modules

### 3.1 `shared` (the same packages in every source set)

```text
com.jagdeep.<app>/
  App.kt                      # root composable, used by every platform
  MainViewController.kt       # iosMain only: the iOS entry point
  di/                         # AppServices: app-scoped dependency wiring (the composition root)
  data/                       # app-level implementations
  platform/                   # one capability per file set: Xxx.kt (expect) + Xxx.android.kt / Xxx.ios.kt / Xxx.jvm.kt
                              #   plus AndroidPlatform.kt (androidMain) and DesktopDataPaths.kt (jvmMain)
  presentation/navigation/    # AppNavHost, rememberNavigationState, decorated stacks, transitions
  presentation/shell/         # DesktopShell, ShellModel, sidebar helpers (+ per-platform actuals)
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
  ui/navigation/   # Navigation 3 entry provider(s): <feature>EntryProvider / <Feature>NavEntries.kt
  ui/util/         # optional: non-composable UI helpers
```

An entry provider is an extension on `EntryProviderScope<NavKey>`. It receives its dependencies as parameters and creates the ViewModel; the screen gets only the ViewModel and callbacks:

```kotlin
fun EntryProviderScope<NavKey>.settingsEntryProvider(navigator: Navigator, preferencesStore: PreferencesStore<AppPreferences>, /* … */) {
    entry<RouteTopLevel.Settings> {
        val viewModel: SettingsViewModel = viewModel(factory = viewModelFactory {
            initializer { SettingsViewModel(preferencesStore, /* … */) }
        })
        SettingsScreen(viewModel, onBack = navigator::goBack)
    }
}
```

### 3.3 Entry points

| Platform | Shell file | Calls |
| --- | --- | --- |
| Android | `androidApp/.../MainActivity.kt` | `enableEdgeToEdge()`, then `setContent { App() }` |
| Android | `androidApp/.../MainApplication.kt` | `AndroidPlatform.init(applicationContext)`: stores the context and starts logging (in `shared` `platform/`) |
| Desktop | `desktopApp/.../main.kt` | `fun main() = runDesktopApp()` (in `shared` `desktop/`) |
| iOS | `iosApp/iosApp/ContentView.swift` | `import Shared`, then `MainViewControllerKt.MainViewController()` in a `UIViewControllerRepresentable` with `.ignoresSafeArea()` |

## 4. Core UI layout: phone and desktop

The app has one composable tree. `isDesktopPlatform()` (`core:ui` `platform/PlatformFormFactor.kt`, an `expect` that is true only on JVM) chooses the shell. Screens never branch on the platform for navigation; they set the top bar and render content.

### 4.1 What `core:ui` provides

| Package | File | Role |
| --- | --- | --- |
| `navigation` | `RouteTopLevel.kt` | `interface RouteTopLevel : NavKey` with `@Serializable data object` top-level destinations (`Home`, `Activity`, `Settings`, …) |
| | `RouteFlow.kt` | `interface RouteFlow : NavKey`: non-top-level destinations several features may open. The owning feature registers the entry; others navigate by key |
| | `SavedStateConfiguration.kt` | `topLevelSavedStateSerializer()`: registers every route's serializer for saved state |
| | `NavigationState.kt` | `startRoute`, the current `topLevelRoute`, one `NavBackStack` per top-level route; `stacksInUse` = start stack, plus the current one when it isn't the start |
| | `Navigator.kt` | `navigate(route)`: a top-level route switches tabs, anything else is pushed on the current stack. `goBack()`: pops, and at the root of a non-start route returns to the start route |
| | `TopLevelPager.kt` | Phones: the top-level routes as horizontal pages (section 4.3) |
| | `MainNavigationItem.kt` | `title` + `icon` for a navigation entry |
| `components` | `AdaptiveNavigationScaffold.kt` | The standard shell: desktop sidebar or phone bottom bar (sections 4.3–4.4) |
| | `DesktopNavigationSidebar.kt` | Material 3 wide navigation rail: 96 dp collapsed (icons, named for screen readers), 180 dp expanded (icon + label) |
| | `AppBarBottom.kt`, `AppBarTop.kt` | Bottom navigation bar; top app bar with a 48 dp navigation-icon slot (back button, or the app icon on the start screen) |
| | `TopAppBarManager.kt` | `TopAppBarState(ownerRoute, modifier, content)` and `LocalTopAppBarManager` (a `MutableStateFlow`). Each screen publishes its own top bar; the shell draws it |
| | `ToastManager.kt`, `SnackbarManager.kt` | `ToastHostState`, `LocalToastManager`, `ToastHost`, snackbars |
| | `AppIcon.kt`, `AppStartupSplash.kt`, `StartupProgressIndicator.kt`, `PlatformLazyListScrollbar.kt`, drop targets, list items | Reusable composables only |
| `platform` | `PlatformFormFactor.kt`, `DeviceDisplay.kt` | `isDesktopPlatform()`, display helpers (expect/actual) |
| `themes` | `AppTheme.kt`, `Palette.kt` + `Palette<Name>.kt`, `Typography.kt`, `Shape.kt`, `SystemBars.kt` | `AppTheme(darkTheme, palette)`; one file per palette; `Palette.Default` is product-neutral; system bar colours per platform |
| `util` | `MessageText.kt`, `ByteFormatting.kt` | Non-composable helpers |

### 4.2 `App()`: the composition order

`shared/.../App.kt` builds the UI in this order:

1. Get the app-scoped services once (`remember { AppServices.get() }`). They outlive the composition, so recreating an activity never stops work.
2. Create the UI state once: the top-app-bar `MutableStateFlow(TopAppBarState())`, `rememberToastHostState()`, the navigation items (`linkedMapOf<NavKey, MainNavigationItem>`), `rememberNavigationState(startRoute, topLevelRoutes)` and `Navigator(navigationState)`.
3. Read the saved theme and palette. Render nothing until they arrive (`?: return`), so the wrong theme never flashes.
4. `AppTheme(darkTheme, palette)` → `Surface(Modifier.fillMaxSize())` → `CompositionLocalProvider(LocalTopAppBarManager, LocalToastManager)`.
5. Build `AppNavHost(...)` with every dependency the entry providers need.
6. Desktop: wrap it in the desktop shell (section 4.4). Phone: wrap it in `AdaptiveNavigationScaffold(selectedRoute, navigationItems, onNavigate, topBar = { topAppBar.content() })` and pad the host with the scaffold's padding.
7. `ToastHost(toastHost)` last, above everything.

`App()` takes optional desktop-window parameters, with defaults so Android and iOS call plain `App()`: `onThemeApplied(dark, background, content)` (colours the title bar), `toolbarStartInset`, `toolbarDragArea`, `toolbarHeight`, `windowControls`, `windowIdentity` and `toolbarBlankAreaModifier`.

### 4.3 Phone layout (Android and iOS)

```text
┌──────────────────────────────┐
│ Top app bar (screen's own)    │  ← start screen: app icon in the nav slot; others: back
├──────────────────────────────┤
│                              │
│  TopLevelPager               │  ← one page per top-level route, swipe between them
│   └ NavDisplay per route     │  ← that route's own back stack
│                              │
├──────────────────────────────┤
│ Bottom navigation (AppBarBottom) │
└──────────────────────────────┘
```

- `AdaptiveNavigationScaffold` (non-desktop branch): a Material 3 `Scaffold` with `topBar` and `bottomBar = AppBarBottom(...)`.
- `AppNavHost` uses `TopLevelPager(navigationState, navigator, topLevelRoutes)`. Each page is a `NavDisplay` with `slideForwardTransition()` for pushes and `slideBackTransition()` for pops (300 ms).
- The neighbouring page follows the finger and the pager settles it. Swiping is enabled only at the root of the current route's stack, so a swipe never leaves a flow half-way.
- Each route keeps its own back stack. Back at the root of a non-start route returns to the start route.
- Neighbouring pages are composed while swiping, so only the current route's page may set the top app bar. That's what `TopAppBarState.ownerRoute` is for.
- Pager, list and saveable keys are primitives or strings (an index or a string id), never route objects, because Android stores them in a `Bundle`.
- Android: `enableEdgeToEdge()` and `SystemBars` themes. iOS: `.ignoresSafeArea()` in SwiftUI, with Compose handling insets.

### 4.4 Desktop layout (macOS, Windows, Linux)

**The template's desktop shell** is `AdaptiveNavigationScaffold`'s desktop branch, usable as-is by any project:

```text
┌──────────┬┬────────────────────────────────┐
│ Sidebar  ││ Top app bar                     │
│ (wide    ││─────────────────────────────────│
│  rail,   ││ Page (NavDisplay, fade)          │
│ toggle)  ││                                 │
└──────────┴┴────────────────────────────────┘
```

- `DesktopNavigationSidebar` (collapsible, starts expanded), then a `VerticalDivider` inset 24 dp top and bottom, then a `Scaffold` with the top bar, starting 16 dp after the divider.
- The sidebar collapses by itself when the window is narrower than 960 dp.
- Desktop doesn't swipe. `AppNavHost` shows a single `NavDisplay` over the decorated stacks, with `topLevelFadeTransition` (180 ms fade) between top-level routes.

**AutoPilot's richer desktop shell** is `shared` `presentation/shell/DesktopShell.kt`, laid out like Android Studio. A project that wants this layout copies the pattern into its own `shared` and keeps it there, because the toolbar content is product-specific:

```text
┌─[● ● ●] Page header ············ [routine ▾] [▶] [■] ─────┐  ← toolbar in the title-bar row (drag area)
├──┬──────────────────────────────────────────────────────────┤
│▣ │ ╭──────────────────────────────────────────────────────╮ │
│▣ │ │ Page panel: rounded 10 dp, colour `surface`          │ │  ← NavDisplay with fade
│  │ │                                                      │ │
│⚙ │ ╰──────────────────────────────────────────────────────╯ │
├──┴──────────────────────────────────────────────────────────┤
│ Status: what's happening now                Chrome · version │  ← status bar
└─────────────────────────────────────────────────────────────┘
```

- The frame is `surfaceContainer`, and the page panel is `surface` with 6 dp end padding.
- **Toolbar:** 28 dp tall, matching the standard Mac title bar, plus 8 dp of space below. It's 38 dp tall when macOS gives the window a tall title bar, and on Windows. It holds the current page's top bar, then the product controls (AutoPilot: routine picker, Start and Stop).
- **Tool strip:** the top-level pages, with Settings at its foot (`footerItems`). Narrow, each name shows as a tooltip to the right. Clicking the empty middle, or the sidebar symbol, expands it to show names.
- **Status bar:** live status on the left; connection state and the app version on the right.

**The desktop window** (`shared` `jvmMain` `desktop/`):

| File | Role |
| --- | --- |
| `DesktopApp.kt` | `runDesktopApp()`: sets `apple.awt.application.appearance=system`, opens `Window(title, state, undecorated = isWindows, icon)`, and passes the window parameters to `App()`. On close it saves the window state, shuts the services down, then exits |
| `DesktopTitleBar.kt` | macOS: a transparent title bar with full-size content, so the toolbar shares the row with the system window buttons. Windows: an undecorated, resizable window whose single toolbar row carries the caption controls. Linux: the system title bar |
| `MacWindowToolbar.kt` | Adds an empty compact `NSToolbar` through AppKit so macOS draws the taller (38 dp) title bar with centred window buttons. It checks every call first, so an older macOS keeps the standard bar |
| `WindowsWindowControls.kt`, `WindowsNativeCaption.kt` | Minimise, maximise and close drawn in the toolbar; hit-testing so Windows treats the maximise button and the empty toolbar as caption (snap layouts, drag) |
| `DesktopCaptionClicks.kt` | A double-click on empty toolbar space maximises or restores the window; buttons consume their own clicks |
| `DesktopWindowPreferences.kt` | Saves the window's size, position and placement in Java Preferences (400 ms debounce), not Room, and restores them at the next start |

### 4.5 UI rules every screen follows

- A screen takes a ViewModel plus callbacks, renders immutable state, and publishes its top bar through `LocalTopAppBarManager`.
- The start destination shows the app icon in the navigation-icon slot; every other screen shows a back button there, so titles line up.
- Material 3 components and the theme's tokens only; no hard-coded colours. Palettes keep text contrast at 4.5:1 or more in light and dark.
- Flat and calm: list rows, dividers and headings before cards; one obvious primary action per screen.
- Transitions are 300 ms or less; pushes slide forward and pops slide back.

## 5. Gradle setup

### 5.1 Catalog (`gradle/libs.versions.toml`)

```toml
[versions]
versionCode = "1"
versionName = "1.1.0"              # MAJOR.MINOR.PATCH, changed only by ./dev version …
androidApplicationId = "com.jagdeep.<app>"
android-minSdk = "26"
android-compileSdk = "37"
android-targetSdk = "37"
agp = "9.4.1"
kotlin = "2.4.21"
composeMultiplatform = "1.12.1"
composeHotReload = "1.2.0"

[plugins]
androidApplication = { id = "com.android.application", version.ref = "agp" }
androidKotlinMultiplatformLibrary = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }
androidLint = { id = "com.android.lint", version.ref = "agp" }
composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
composeCompiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
composeHotReload = { id = "org.jetbrains.compose.hot-reload", version.ref = "composeHotReload" }
kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlinJvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
```

The versions are AutoPilot's on 23 November 2026; take the current ones from the new Android Studio template.

### 5.2 `settings.gradle.kts`

```kotlin
rootProject.name = "<App>"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
// pluginManagement and dependencyResolutionManagement: google(), mavenCentral() (+ gradlePluginPortal() for plugins)
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }
include(":shared"); include(":androidApp"); include(":desktopApp")
include(":core:common"); include(":core:logger"); include(":core:ui"); include(":core:persistence") // + every other core module
include(":feature:shared"); include(":feature:home") // + every other feature
```

### 5.3 Root `build.gradle.kts`

- Declare every plugin with `apply false`: `androidApplication`, `androidLibrary`, `androidKotlinMultiplatformLibrary`, `androidLint`, `composeHotReload`, `composeMultiplatform`, `composeCompiler`, `kotlinMultiplatform`, `kotlinJvm`, `serialization`, `ksp`, `androidx.room`.
- Compute values that several modules need **once** and store them in `extra`. AutoPilot's desktop version:
  - `desktopVersion` = `1.1.0 (105 · a542753)` (release, Git commit count, short SHA), shown in the app
  - `desktopBuildIdentity` = `1.1.0+build.105.ga542753`, the SemVer identity
  - `desktopPackageVersion` = `1.1.105`, numeric for MSI/DMG/DEB, so each commit's MSI upgrades in place
- Register `verifyFeatureModuleBoundaries`.

### 5.4 `gradle.properties`

- **Remove `android.builtInKotlin=false`.** `androidApp` uses AGP 9's built-in Kotlin (a `kotlin { compilerOptions { … } }` block, no Kotlin Android plugin). With the flag set, `androidApp` fails with *"Unresolved reference 'compilerOptions'"*.
- Keep `org.gradle.configuration-cache=true`, `org.gradle.caching=true`, `android.useAndroidX=true`, `android.nonTransitiveRClass=true`.
- `android.newDsl=false` may stay while other modules still need it.

### 5.5 `shared/build.gradle.kts`

```kotlin
plugins { alias(libs.plugins.kotlinMultiplatform); alias(libs.plugins.androidKotlinMultiplatformLibrary)
          alias(libs.plugins.composeMultiplatform); alias(libs.plugins.composeCompiler); alias(libs.plugins.serialization) }
kotlin {
    androidLibrary {
        namespace = "com.jagdeep.<app>.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources { enable = true }   // package the Compose resources into the Android app
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { it.binaries.framework { baseName = "Shared"; isStatic = true } }
    jvm()
    sourceSets { /* commonMain/androidMain/iosMain/jvmMain/tests: the old module's dependencies, unchanged */ }
}
```

- The generated Compose `Res` class moves to package `<rootProjectName lower-case>.shared.generated.resources`. Update those imports.
- If `core:ui` is a byte-identical template copy whose `AppIcon` imports the template's `Res` package, pin that package in the root build (`packageOfResClass = "zynk.core.ui.generated.resources"`) instead of editing the copy.

### 5.6 `androidApp/build.gradle.kts`

```kotlin
plugins { alias(libs.plugins.androidApplication); alias(libs.plugins.composeCompiler) }
kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_11 } }
dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.ui.tooling.preview)
    debugImplementation(libs.ui.tooling)
}
android {
    namespace = "com.jagdeep.<app>"                  // keep the old one, so R and manifest class names stay valid
    compileSdk = …
    defaultConfig { applicationId = libs.versions.androidApplicationId.get(); minSdk = …; targetSdk = …
                    versionCode = libs.versions.versionCode.get().toInt(); versionName = libs.versions.versionName.get() }
    packaging { resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", /* … */) } }
    signingConfigs { /* "release" from the git-ignored keystore.properties, only if it exists */ }
    buildTypes { getByName("release") { signingConfigs.findByName("release")?.let { signingConfig = it } } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    buildFeatures { compose = true }
}
```

### 5.7 `desktopApp/build.gradle.kts`

```kotlin
plugins { alias(libs.plugins.kotlinJvm); alias(libs.plugins.composeMultiplatform)
          alias(libs.plugins.composeCompiler); alias(libs.plugins.composeHotReload) }
dependencies { implementation(project(":shared")); implementation(compose.desktop.currentOs); implementation(libs.kotlinx.coroutinesSwing) }
compose.desktop {
    application {
        mainClass = "com.jagdeep.<app>.MainKt"
        jvmArgs += "-D<app>.version=${rootProject.extra["desktopVersion"]}"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "<App>"; packageVersion = rootProject.extra["desktopPackageVersion"] as String
            modules(/* the JDK modules the app needs, for example "java.prefs" */)
            macOS { iconFile.set(project.file("app-icons/<App>.icns")); bundleID = "com.jagdeep.<app>" }
            windows { iconFile.set(…); shortcut = true; menuGroup = "<App>"; upgradeUuid = "<keep the existing UUID>" }
            linux { iconFile.set(project.file("app-icons/<app>-icon-linux-512.png")) }
        }
    }
}
// packaging hooks (for example AutoPilot's Windows Smart App Control fixes) go here, unchanged
```

Keep `packageName`, `bundleID`, `upgradeUuid` and the icons unchanged, so installed apps update in place.

### 5.8 A `core:*` or `feature:*` module

```kotlin
plugins { alias(libs.plugins.kotlinMultiplatform); alias(libs.plugins.androidKotlinMultiplatformLibrary); alias(libs.plugins.androidLint) }
kotlin {
    androidLibrary { namespace = "com.jagdeep.<name>"; compileSdk = …; minSdk = …; withHostTestBuilder {} }
    iosArm64 { binaries.framework { baseName = "<name>Kit" } }
    iosSimulatorArm64 { binaries.framework { baseName = "<name>Kit" } }
    jvm()
    sourceSets { commonMain { dependencies { implementation(project(":core:common")) } } }
}
```

### 5.9 Task names after migration

| Old | New |
| --- | --- |
| `:composeApp:run`, `:composeApp:hotRun`, `:composeApp:package<Release><Format>`, `:composeApp:createReleaseDistributable` | `:desktopApp:…` |
| `:composeApp:assembleDebug`, `:composeApp:installDebug`, `:composeApp:bundleRelease` | `:androidApp:…` |
| `:composeApp:jvmTest`, `:composeApp:embedAndSignAppleFrameworkForXcode`, `:composeApp:link…` | `:shared:…` |
| `composeApp/build/compose/binaries/…` | `desktopApp/build/compose/binaries/…` |

## 6. The private folder and `dev` command

### 6.1 Layout

```text
private/
  docs/          # tracked: 00_INDEX.md routes to common/ and project/
    common/      # 01–19: reusable CMP, Git, SSH, install and debug guides; this file is common/01
    project/     # 20 onward: this app's commands, machines, features and recorded results
  scripts/       # tracked: internal helpers called by ./dev, Gradle and CI (windows/ for PowerShell)
  reports/       # tracked: dated reports worth keeping (YYYY-MM-DD-topic.md)
  <data>/        # tracked: project data the app or build needs, one folder per kind (AutoPilot: qa/)
  plans/         # git-ignored: plans, proposals, roadmaps, phase tracking
  signing/       # git-ignored: release keys, keystore.properties material
  secrets/       # git-ignored: .env files and other secrets
  misc/          # git-ignored: anything temporary or unimportant
```

Reference copies of other projects, such as the Zynk template or a fresh Android Studio CMP template, are read-only. Keep them outside the repository, or in an ignored folder under `private/`.

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
```

The template's own ignores stay too: `*.iml`, `.kotlin`, `.gradle`, `**/build/`, `local.properties`, `.idea`, `xcuserdata`, `*.xcodeproj/*` with `!*.xcodeproj/project.pbxproj` and `!*.xcodeproj/xcshareddata/`. `.idea` is ignored, so shared run configurations must live in `.run/` (section 7).

Rules:

- **When unsure, use `misc/`.** It's ignored as a whole. Never make the build, the app or a tracked guide depend on a file in it.
- Guides are named `NN_TOPIC_NAME.md`. Common guides use 01–19, project guides 20 onward, and each folder has a `00_INDEX.md`. Link with GitHub heading anchors. When a guide moves, update every index, link, README, AGENTS.md and script message together.
- Remote build machines build only from Git: commit and push on the Mac, pull on the machine, then build. Never copy source to them.

### 6.2 The root `dev` command

Every project has an executable `dev` at its root: `./dev <action> <target> [options]`. It resolves paths from its own location, prints grouped help for `./dev` and `./dev --help`, and rejects unknown actions, targets and options with a useful message.

| Action | Meaning |
| --- | --- |
| `sync` | Check committed source, push, then pull and verify the same commit on a remote target |
| `build` | Sync, run focused tests, create the app or installer |
| `run` | Build, install and check the launch where supported |
| `fetch` | Collect build artifacts and verify SHA256 |
| `debug` | Start a debug run waiting on port 5005, and tunnel it to the Mac for a remote target; attach with `desktopApp [attach :5005]` |
| `git <action>` | Repository operations: status, check, fetch, pull, diff, history, branches, push-all |
| `version patch\|minor\|major` | Change the release version, commit, tag `v<version>` and push |

Target names (for example `mac`, `legion`, `all`) are project configuration; document them in the project command guide. `--remote` switches the network route only when asked, and `--no-tests` must be reported in results. AutoPilot's command reference: [20 · One-command builds](../project/20_ONE_COMMAND_BUILDS.md).

## 7. Run configurations

**A CMP project keeps exactly these run configurations, all checked in under `.run/`:**

| Name | File | Type | What it does |
| --- | --- | --- | --- |
| `androidApp` | `.run/androidApp.run.xml` | Android App, module `<Project>.androidApp` | Installs and starts the debug app on the chosen emulator or device |
| `iosApp` | `.run/iosApp.run.xml` | Xcode Application, scheme `iosApp` | Builds the Xcode project (which builds `Shared`) and runs it on the chosen simulator or device |
| `desktopApp` | `.run/desktopApp.run.xml` | Gradle `:desktopApp:run` | Runs the desktop app; Debug attaches automatically |
| `desktopApp [hot] 🔥` | `.run/desktopApp-hot.run.xml` | Gradle `:desktopApp:hotRun --mainClass "com.jagdeep.<app>.MainKt"` with Compose Hot Reload | Desktop with live UI reload |
| `desktopApp [attach :5005]` | `.run/desktopApp-attach-debugger.run.xml` | Remote JVM Debug, `127.0.0.1:5005`, module `<Project>.shared.jvmMain` | Only if the project has `./dev debug`: attaches to a debug run on the Mac, or to a remote one through `dev`'s SSH tunnel |

The first four match what the Android Studio CMP template creates, so every project looks the same. Also track the Xcode scheme `iosApp.xcodeproj/xcshareddata/xcschemes/iosApp.xcscheme`.

**Don't add more.**

- No per-machine copies: one attach configuration serves every machine, because `dev` forwards each remote port to the Mac's 5005.
- No duplicates in `.idea/runConfigurations/` or `.idea/workspace.xml`. `.idea` is ignored, so anything there is local and goes stale.
- No configurations for tests, packaging or scripts: run those from the Gradle tool window, the gutter, or `./dev`.
- Temporary configurations the IDE creates (from a gutter ▶) stay temporary; don't save them.

**Cleaning up an old project:**

1. Close the project in Android Studio, so it can't write `.idea/workspace.xml` back.
2. Back up `.run/`, `.idea/runConfigurations/` and `.idea/workspace.xml` to `private/misc/`.
3. `git rm` every old `.run/*.xml`; delete `.idea/runConfigurations/`. In `workspace.xml`, delete every named `<configuration>` inside `<component name="RunManager">` and its `<list>`; keep the `default="true"` templates.
4. Write the five files above, with the project's name and main class, and check each with `xmllint --noout`.
5. Update every guide, script message and README line that names an old configuration.
6. Reopen the project and run **File → Sync Project with Gradle Files**. Each configuration needs the synced model: `androidApp` finds its module only after the sync, and `iosApp` uses the synced `shared` module to build the framework.

**If iOS fails at once, or its Run button is greyed out:**

- The IDE log shows `:composeApp:embedAndSignAppleFrameworkForXcode`: the IDE still has the pre-migration model. Sync. If the sync doesn't start, close the project, move `~/Library/Caches/Google/AndroidStudio<version>/projects/<project>.<hash>/external_build_system` and `project-model-cache` to `private/misc/`, and reopen.
- `xcode-select -p` prints `/Library/Developer/CommandLineTools`: run `sudo xcode-select --switch /Applications/Xcode.app/Contents/Developer` and `sudo xcodebuild -runFirstLaunch`, then check `xcodebuild -version`.
- No simulator is selected in the device menu.

## 8. Migration procedure

Do it in this order. Commit only when each verification passes.

### Step 0 — Prepare

```sh
git status --short          # must be clean; commit or stash first
git pull --ff-only
```

Create a fresh Android Studio CMP project (New Project → Kotlin Multiplatform, with Android, iOS and Desktop) somewhere outside the repository, or in an ignored folder. Use it as the reference for file contents and plugin versions.

### Step 1 — Record a baseline

The goal is "nothing broke", so measure the current project first. Every failure here is pre-existing, not caused by the migration.

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
- the list of run configurations and Xcode schemes

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

1. **Desktop:** rename `fun main()` in `DesktopApp.kt` to a public `fun runDesktopApp()`. Create `desktopApp/src/main/kotlin/<pkg>/main.kt` with `fun main() = runDesktopApp()`.
2. **Android:** create `shared/src/androidMain/.../platform/AndroidPlatform.kt`, an object holding `context`, plus `init(context)` that also starts logging. Replace uses of `MainApplication.<context>` in `shared` with `AndroidPlatform.context`. `MainApplication.onCreate` calls `AndroidPlatform.init(applicationContext)`.
3. **iOS:** keep `MainViewController()` in `shared` `iosMain`.
4. Make public only what a shell calls. Everything else stays `internal`.

### Step 4 — Build files

1. **`settings.gradle.kts`:** replace `include(":composeApp")` with `:shared`, `:androidApp` and `:desktopApp` (section 5.2).
2. **Catalog:** add `kotlinJvm`. **Root build:** add `alias(libs.plugins.kotlinJvm) apply false`, and move shared values (the version) into `extra` (section 5.3).
3. **`shared/build.gradle.kts`:** keep the old module's `sourceSets` unchanged. Swap `androidApplication` for `androidKotlinMultiplatformLibrary`, and `androidTarget {}` for `androidLibrary {}`. Name the iOS framework `Shared`. Remove `compose.desktop`, `android {}` and the signing setup (section 5.5).
4. **`androidApp/build.gradle.kts`:** move the old `android {}` block here: namespace, application ID, versions, signing, packaging (section 5.6).
5. **`desktopApp/build.gradle.kts`:** move `compose.desktop {}` and any packaging hooks here, and fix the icon paths (section 5.7).
6. **`gradle.properties`:** delete `android.builtInKotlin=false`.
7. **Compose resources:** change `import <old>.composeapp.generated.resources.…` to `<app>.shared.generated.resources.…`.

### Step 5 — iOS

```sh
sed -i '' 's/^import ComposeApp$/import Shared/' iosApp/iosApp/ContentView.swift
sed -i '' 's|:composeApp:embedAndSignAppleFrameworkForXcode|:shared:embedAndSignAppleFrameworkForXcode|' iosApp/iosApp.xcodeproj/project.pbxproj
```

Make sure the `iosApp` scheme is shared (Xcode → Product → Scheme → Manage Schemes → Shared), so `xcshareddata/xcschemes/iosApp.xcscheme` is tracked.

### Step 6 — Group `shared` by role (section 3.1)

- Move each `expect` file and **all** its `actual`s together into `platform/`, because Kotlin requires them to share a package.
- Move `AppServices` to `di/`, app-level implementations to `data/`, navigation to `presentation/navigation/`, the shell to `presentation/shell/`, and the desktop window files to `desktop/`. Tests mirror the packages, with end-to-end tests in `integration/`.
- After each `git mv`, change the file's `package` line, then add imports wherever another package uses its declarations.
- Watch for names used **inside string templates** (`"${NAME}"`), **fully qualified references** (`com.example.app.Foo(...)`), and **existing explicit imports** of the old package. A text search that skips strings misses the first kind.
- Compile every target after the move. The compiler lists anything missed.

### Step 7 — Bring the UI layout to the standard (section 4)

- `App()` follows the composition order in section 4.2, and the phone branch uses `AdaptiveNavigationScaffold` + `TopLevelPager`.
- Desktop uses the `AdaptiveNavigationScaffold` desktop branch, or a product `DesktopShell` in `shared` `presentation/shell/` following section 4.4.
- Screens publish their top bar through `LocalTopAppBarManager`, and every route is registered in the saved-state serializer.
- Change only structure here; a visual redesign is a separate task.

### Step 8 — Run configurations (section 7)

Replace every old configuration with the standard five (four without `./dev debug`), and clean `.idea`.

### Step 9 — Update every reference

```sh
grep -rn "composeApp\|ComposeApp\|composeapp" --exclude-dir=build --exclude-dir=.git --exclude-dir=.gradle .
```

Update scripts (Gradle task names, output paths), CI workflows, `.run` files, the README module list and run table, guides, comments that name file paths, and AGENTS.md.

### Step 10 — Verify (section 9), then commit and push

Commit the restructure, the package grouping, the run configurations and any unrelated fixes **separately**. Push only when the owner says so. Then build and install on every desktop machine from Git, for example `./dev run legion` and `./dev run mac`.

## 9. Verification checklist

| Check | Command or method | Must match the baseline |
| --- | --- | --- |
| All JVM tests | `./gradlew jvmTest`, then sum `build/test-results/jvmTest/*.xml` | Same suites and test count; no new failures |
| Module boundaries | `./gradlew verifyFeatureModuleBoundaries` | Passes |
| Android | `./gradlew :androidApp:assembleDebug`, then `aapt2 dump badging` and the manifest | Same package, label, icon, `MainApplication`, `MainActivity`, versions; Compose resources present |
| iOS framework | `DEVELOPER_DIR=… ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64` | Links |
| iOS app | `xcodebuild … build` (the Step 1 command) | BUILD SUCCEEDED |
| iOS tests | `DEVELOPER_DIR=… ./gradlew iosSimulatorArm64Test` | Compile and pass |
| Desktop app | `./gradlew :desktopApp:createReleaseDistributable`, then compare the file list and `.cfg` | Same files (the app jar becomes `shared` + `desktopApp`); same main class, JVM options, version, bundle ID |
| Desktop runtime | Start the built app for 15 s | Window opens, no exceptions |
| Windows | `./dev run legion` (or the project's Windows target) | MSI builds, updates in place, the app's JVM loads |
| Run configurations | `ls .run`; Android Studio after a sync | Exactly the files in section 7; each one runs from its Play button |
| References | `grep -rn composeApp` | Nothing left |

## 10. Pitfalls

| Pitfall | Symptom | Fix |
| --- | --- | --- |
| Stale build output from a renamed project folder | Android dexing fails: *"file … is located outside the root directory"* | `./gradlew clean` |
| JVM-only APIs in common code | iOS: *"Unresolved reference 'putIfAbsent'"* | `if (key !in map) map[key] = value` |
| `Dispatchers.IO` in common code | iOS: *"Cannot access 'val IO' … internal"* | `import kotlinx.coroutines.IO` |
| `android.builtInKotlin=false` | `androidApp`: *"Unresolved reference 'compilerOptions'"* | Remove the flag |
| `expect`/`actual` split across packages | *"expect declaration has no actual"* | Move each set of files together |
| Names inside string templates and fully qualified references | Unresolved references in tests after a package move | Add the import, or fix the qualified name |
| Comma or period in a backtick test name | Kotlin/Native: *"Name contains illegal characters"* | Avoid `,` `.` `:` `/` `<` `>` `[` `]` `;` in test names that also compile for iOS |
| `@Ignore("reason")` in common tests | Kotlin/Native: *"Too many arguments for 'constructor(): Ignore'"* | `// Ignored: reason` above a bare `@Ignore` |
| `:shared` and `:feature:shared` | Both desktop jars start with `shared-jvm` | Harmless: Compose adds a hash suffix |
| Android context reached through `MainApplication` | `shared` can't see `androidApp` classes | Hold the context in `AndroidPlatform` in `shared` |
| The IDE never re-synced after the restructure | No module in the `androidApp` configuration; iOS runs `:composeApp:embedAndSign…` and fails in under a second | Sync; if it won't start, clear the project's model cache (section 7) |
| Android configuration pointing at `<Project>.androidApp.main` | No module selected, Play disabled | Use `<Project>.androidApp`, as the template does |
| Xcode not selected | iOS sync and Play disabled | `sudo xcode-select --switch /Applications/Xcode.app/Contents/Developer` |
| Run configurations piling up | Several entries per platform, some broken | Keep only the section 7 set in `.run/`; nothing in `.idea` |
| `./dev` refuses to run | *"uncommitted changes"* caused by an untracked reference copy | Keep reference copies outside the repository, or ignored |

## 11. Recorded migration — AutoPilot

This is historical evidence from 13 November 2026, not a current live check.

| Commit | Change |
| --- | --- |
| `b309cc0` | Pre-existing iOS fix: `putIfAbsent` in `feature:home` |
| `bfdebc7` | Pre-existing iOS fix: `kotlinx.coroutines.IO` import |
| `37c2b34` | `composeApp` → `shared` + `androidApp` + `desktopApp`; references updated |
| `3d39ddb` | Reference copy ignored |
| `2745ebb` | Bundled answer bank refreshed; its test updated |
| `a0d04ff` | `shared` grouped into `di`, `data`, `platform`, `presentation`, `desktop`; tests mirrored |

| Check | Baseline | After |
| --- | --- | --- |
| JVM tests | 113 suites, 568 tests, 13 skipped, 1 failure (answer-bank count) | 113 suites, 568 tests, 13 skipped, 0 failures |
| Module boundaries | Pass | Pass |
| Android debug APK | — | `com.jagdeep.autopilot`, label, icon, `MainApplication`, `MainActivity`, 1.0.0 (1): unchanged |
| iOS | Didn't compile (2 errors, fixed separately) | Framework links; Xcode BUILD SUCCEEDED; iOS tests 366 run, 11 skipped, 0 failures (after renaming 24 test names and 11 `@Ignore` reasons) |
| macOS app | 239 files, version 1.0.69 | Same files and launcher; window opens, no errors |
| Windows (Legion) | 1.0.61 installed | 1.0.71 built from Git, updated in place, JVM loaded, 0 Smart App Control blocks |

On 23 November 2026, the run configurations were reduced from nine (four in `.run/`, one in `.idea/runConfigurations/`, four more in `workspace.xml`) to the five in section 7.

## 12. Migration prompt for the agent

Copy this guide into the project's `private/docs/common/`, then paste the prompt below into the coding agent (Claude Code) at the project root. Replace the values in `<…>` first.

```text
Migrate this Compose Multiplatform project to the standard in
private/docs/common/01_CMP_PROJECT_STANDARD_AND_MIGRATION.md. Read the whole guide before
changing anything. Reference template: a fresh Android Studio CMP project at <path to template>
(read-only). The code must not break: this is a restructure, not a rewrite.

Project values: app name <App>, base package com.jagdeep.<app>, Android application ID
<application id>, macOS bundle ID <bundle id>, MSI upgrade UUID <uuid or "none yet">.
Keep every one of these, and every data location and storage key, unchanged.

Work in this order. Stop and report after each numbered step.

1. Baseline (guide section 8, steps 0–1). Check git is clean. Run clean, every JVM test, the
   module-boundary check, the Android debug APK, the iOS framework link, an xcodebuild simulator
   build (DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer) and the desktop
   distributable. Save the test totals, the APK, the desktop file list and .cfg, and the list of
   run configurations. Report every pre-existing failure. Fix pre-existing compile errors in
   separate commits, and ask me before changing any behaviour.

2. Modules (sections 1–2, steps 2–5). git mv composeApp to shared. Move MainActivity,
   MainApplication, the manifest and res to androidApp; app-icons to desktopApp; the desktop
   main.kt to shared desktop/DesktopApp.kt as a public runDesktopApp(). Add AndroidPlatform in
   shared platform/. Write the shared, androidApp and desktopApp build files as in section 5,
   moving the version, signing and packaging hooks unchanged. Remove android.builtInKotlin=false.
   Update the Compose Res imports, the iOS framework name (Shared), ContentView.swift and the
   Xcode build phase. Make sure the iosApp scheme is shared and tracked.

3. Packages (section 3, step 6). Group shared into di/, data/, platform/ (each expect with all its
   actuals), presentation/navigation/, presentation/shell/ and desktop/, with tests mirrored and
   end-to-end tests in integration/. Check every feature follows data/domain/ui/screens/
   ui/components/ui/navigation, and every core module stays product-neutral with no dependency on
   another technical core module. Use git mv; fix imports, string templates and fully qualified
   names.

4. UI layout (section 4, step 7). Make App() follow the composition order in 4.2. Phones:
   AdaptiveNavigationScaffold with the bottom bar, TopLevelPager with one NavDisplay per top-level
   route, slide forward/back transitions, swiping only at a stack's root, Bundle-safe keys.
   Desktop: the AdaptiveNavigationScaffold sidebar (or a product DesktopShell in
   presentation/shell/ following 4.4), a fade between top-level routes, and no swiping. The
   desktop window lives in shared desktop/ as in 4.4. Screens publish their top bar through
   LocalTopAppBarManager; the start screen shows the app icon in the navigation slot and every
   other screen a back button. Every route is registered for saved state. Reuse core:ui; don't
   copy its components into features. Change structure only, no visual redesign, and list
   anything that can't match without a behaviour change so I can decide.

5. Run configurations (section 7, step 8). Keep ONLY these, checked in under .run/:
   androidApp (module <Project>.androidApp), iosApp (scheme iosApp), desktopApp
   (:desktopApp:run), "desktopApp [hot] 🔥" (:desktopApp:hotRun --mainClass
   "com.jagdeep.<app>.MainKt"), and, only if the project has ./dev debug,
   "desktopApp [attach :5005]" (Remote JVM Debug, 127.0.0.1:5005, module <Project>.shared.jvmMain).
   Delete every other configuration: per-machine copies, duplicates, test, packaging and script
   configurations, anything in .idea/runConfigurations/, and the named configurations in
   .idea/workspace.xml's RunManager. Back them up to private/misc/ first, and ask me to close the
   project in Android Studio before editing .idea. Validate each file with xmllint, and update
   every guide and script message that names an old configuration.

6. Private folder and dev (section 6). Lay out private/ as in 6.1 (docs/common 01–19,
   docs/project 20+, each with 00_INDEX.md; scripts, reports, data, and ignored plans, signing,
   secrets, misc) and add the .gitignore block. If the project has build or install scripts,
   expose them through a root ./dev with the actions in 6.2. Move files with git mv; update
   indexes and links.

7. References (step 9). grep for composeApp/ComposeApp/composeapp and fix scripts, CI, .run,
   README (module list and run table), guides, comments and AGENTS.md. Update AGENTS.md Part A to
   this standard and keep Part B for product rules.

8. Verify (section 9). Run every check against the baseline and show me a comparison table:
   tests, boundaries, APK identity, iOS framework, xcodebuild, iOS tests, desktop file list and
   .cfg, a 15-second desktop launch, the run configuration list, and the composeApp grep. Then
   tell me to sync in Android Studio and press Play on androidApp, iosApp and desktopApp.

Commit the restructure, the package grouping, the UI-layout changes, the run configurations and
any fixes as separate commits. Don't push until I say "push". After the push, build and install
on each desktop machine from Git (never copy source to a build machine).
```
