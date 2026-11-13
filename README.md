# AutoPilot

AutoPilot clicks for you, on Chrome pages and in any other app. Save a routine that says where to click, how often, and when to stop, then start and stop it from any app with a hot key.

Built with Kotlin Multiplatform and Compose Multiplatform. macOS and Windows come first.

## Features

- **Click routine:** clicks wherever the pointer is, at a set interval, with the left, right or middle button.
- **Multi-point click:** a list of steps, each with its own spot, wait and button. Reorder them, pick spots on screen with F7, and run the list once, a number of times, or until you stop it.
- **Pick on screen:** hover over the target in the other app and press F7 to record each point.
- **Timing:** any interval from 10 ms up to 24 hours, with an optional random extra wait, and a start delay.
- **Stop conditions:** when you stop it, after a number of clicks, or after some time.
- **Only in one app:** pause automatically unless a chosen app, such as Google Chrome, is in front.
- **Activity:** what ran, how many clicks it made, and why it stopped.
- **One page for every routine:** the Routines page holds click routines, multi-point clicks, scroll and click routines and Sagar Mein Yog automation routines. Choose the kind with **New routine**. Only one routine runs at a time.
- **Desktop layout:** the current page icon/Back button, title and page actions live in the window toolbar beside the routine picker and Start/Stop controls. The page has no duplicate title row. Hover over the sidebar’s empty middle area for a horizontal resize cursor; click to toggle, or use keyboard focus and Enter. Its colours follow the app’s theme.
- **Share routines:** use **Settings → Routine files** to import files or export selected routines (all are selected by default) to a `.autopilot.json` file, and import a file someone sent you. Imported routines are added alongside yours, and anything the editor wouldn't accept is left out. Spots are screen positions, so check them on a different screen.
- **Developer mode:** a Settings switch that turns Activity into a live log of what the app does, including every message to and from Chrome. Copy it to report a problem.
- **Keep the display on:** an optional Settings switch that stops the display sleeping while something is running.

### Scroll and click

For long feeds, "load more" lists and paged documents, a scroll-and-click routine runs by itself: it scrolls to the bottom, clicks a spot you picked, waits for the page to change, and repeats. This is the one mode that clicks with no key press, and it's off until you start it.

With **Stop for videos** on, it stops at each video it reaches and plays it so you can watch, at the speed the page set, pressing the video's play button if it has one. When the video ends, it scrolls the rest of the way and clicks. If Chrome won't start a video by itself, or you pause one, it waits for you to press play. Looping and silent autoplaying videos are skipped, and it can't see players that don't use a standard video element.

1. On the Routines page, press **New routine** and choose **Scroll and click**.
2. Scroll to the bottom of the page, press **Pick steps on screen**, and press **F7** over each button to press there, in order. Each step has its own name, wait and button, like a multi-point click. A step can instead be a **button text**, such as "Back to Course": the extension finds that button wherever it is on the page and clicks it, waiting up to 30 seconds for it to appear. Set the number of rounds and save.
3. Select the routine and press **Start**. Chrome comes to the front by itself. Clicking pauses whenever another app is in front, so leave Chrome there.

After adding or reloading the extension, refresh any page that's already open.

It stops after the last round, or if a click leaves the page unchanged for 15 seconds. The usual stops below also work, and each run appears in Activity. The spot is a position on the screen, so keep the Chrome window where it was when you picked it.


### Running a routine

Starting a routine from Home, the desktop toolbar, or a hot key opens **Running routine**. This screen groups live status and approvals, current lesson/question, run progress, saved user progress and run configuration. The current module is prominent, attention states are explicit, and Stop stays fixed at the bottom while details scroll. Home remains the routine library. Back keeps the routine running; use **View running routine** on Home to return. After stopping or finishing, the running screen shows the result and a **Back to routines** action. Changing screens does not restart or stop a routine.

### Sagar Mein Yog automation

In the routine editor, **Timing > Wait after every click (seconds)** controls the pause before the next action. It defaults to **5 seconds**, including for routines saved before this option existed, and stays in effect across page navigation. Set 0 for no extra wait.

Completed videos are skipped. The routine opens the next unfinished video, waits for its end, returns to the course and continues through the chapters. An unfinished quiz does not make it replay already-watched videos. Chapter quizzes stay manual; module assessments are manual unless local answers are enabled below.

Choose **New routine > Sagar Mein Yog automation** to automate the Sagar Mein Yog dashboard, course list and video lesson pages. Open one of those pages in Chrome and start the routine. It opens unfinished courses and video lessons, waits for each standard video to actually end, then clicks **Next** or returns to the course and continues. Keep Chrome in front; if playback is blocked or paused, press play in Chrome. The usual Stop button, stop hotkey and screen-corner stop work.

The routine editor offers **Playback speed: 1×, 1.5× or 2×**. New routines default to 1×; existing routines keep their saved selection. Speed applies to each video while the routine is authorized, without seeking or faking completion; stopping restores the previous speed. Reload extension **1.8.3** and refresh course tabs after updating to enable speed control.

#### Learning order

In the Sagar Mein Yog routine editor, choose **Assessment after each module** to finish a module's videos and handle its unfinished assessment with the selected assessment mode. Passed assessments in saved progress are skipped, even when a newer attempt is unsubmitted. Existing routines keep this order.

Choose **Watch all videos first** to watch every available unfinished video without opening assessments or asking for answers. When videos finish, a confirmation popup offers **Begin assessments** or **Not now — stop routine**. Only explicit confirmation starts a second pass for unfinished assessments, using the selected assessment mode and skipping saved passes. Automatic mode also requires this confirmation. Completed videos remain skipped. The running screen prominently shows the current module. Routine options stay expanded inline, including learning order, playback speed, assessment mode and answer-bank controls. Save/Cancel stay visible below the scrolling form. Playback defaults to 1× for new routines; saved speed choices persist.

Progress is checked at startup and after newly completed learning/assessment work. Merely visiting or skipping a module that was already finished does not trigger another full progress check.

#### Assessments and saved progress (extension 1.9.8)

In the course routine editor, enable **Handle module assessments**, then choose **Manual**, **Review approvals** or **Automatic**. Manual leaves assessment starts, answers, question navigation and submission to you in Chrome. Review approvals starts and answers automatically, then asks in a popup before final submission. Automatic starts, answers and submits automatically. Missing or ambiguous bank answers always pause and trigger the enabled question alert every 30 seconds. Failed results require an explicitly chosen retry or manual takeover. Chapter quizzes remain manual.

The desktop app stores named answer banks in `~/.autopilot/answer-banks/` (inside the current user’s home folder on every desktop platform). The default file is `sagarmeinyog-ver-1.0.json`. A valid legacy bank is copied there once without overwriting an existing permanent file. Use **Settings → Routine files → Answer-bank folder**, or the direct **Open Q&A folder** button inside routine options, to open the folder. In the expanded routine options, name a JSON file, import it, choose an existing file, or validate and refresh the file list. Imports never overwrite an existing name. Each routine saves its selected filename, and approved corrections are written to that same file. Updated files are loaded on the next run; an active assessment keeps its existing answer snapshot. The file is a JSON object keyed by module title; each entry contains `question`, `options` and `answer` (null when unanswered). Unanswered entries are retained but never selected automatically. An unknown question, changed option set, or missing answer pauses the run, plays a short two-note sound through normal desktop audio output every 30 seconds while manual help is needed, and shows the question in the bottom status bar. Repeated inventories, recreated controls and option reordering do not cause additional alerts before the next reminder. If audio cannot start, the status also says Sound unavailable. If inventories stop arriving, the run shows Waiting for page updates and asks you to keep the course tab active in Chrome; existing unanswered-question warnings stay visible. The whole bank is validated: identical question/options entries are deduplicated, conflicting non-null answers reject the file, and option variants are matched separately by their complete option set. Captured unanswered entries with repeated option labels remain unanswerable without rejecting other valid entries. Missing/ambiguous questions, changed option sets, unknown page layouts, failed results and unconfirmed selections pause the routine. No fuzzy matching, AI calls or automatic retakes. File answers are user-supplied, not independently verified as correct. The live question pool contains questions missing from this file. Every question instance in the website's displayed total must be observed, matched and confirmed selected before requesting submission approval; without a displayed total, coverage of the entire module bank is required. Repeated question text at different positions is tracked separately.

After updating, reload **AutoPilot Page Helper 1.9.8**, refresh course tabs before starting an attempt, restart the rebuilt desktop app and save the opt-in setting. Do not refresh an assessment already in progress. The shared extension retains scrolling, video watching and requested text clicks; retired highlighting and key-click paths are removed in version 1.9.9. The assessment collector supports ordinary radio groups/fieldsets and the observed Sagar Mein Yog `/test/<attempt>` layout, including its start modal, generic question text, question counter, End Test/Submit confirmation and explicit CLEARED/NOT CLEARED result pages. Failed assessments are shown clearly; a retry starts only after you explicitly choose it. Unexpected live layouts require inspection, not a blind click.

#### Per-user progress

At every Sagar routine start, choose an existing local profile or create one; the most recently used profile is shown first. Choose the account signed into Chrome. This choice persists for the run, so assessment pages without usernames do not block answering. If an assessment is already active, the initial website-history import waits until it is safe to leave. Sign-in and passwords stay in Chrome.

The routine restores the selected user's saved lesson, module-video and assessment completion before navigating courses. It saves confirmed video endings, all-video module completion, and observed passed/failed assessment results as they happen. Completed modules are skipped from the dashboard when their identity is known; a saved assessment pass alone never implies the videos are complete. Missing module completion evidence is checked once on the course page and then persisted. Existing cached completions are not replaced by stale incomplete page labels.

**Your Progress** is read at every routine start after choosing a profile, before any course actions, and its full results are saved to that profile. A manual **Verify website progress** request and a final check after new local completions can refresh it again; a run with no new completions does not repeat the startup check. It is not re-read after every module. Verification uses the maximum offered page size and requires complete unfiltered history. A manual verification waits for a safe course page rather than leaving a video or timed assessment. Locally observed results can be saved before their score appears in a later website-history verification.

**Saved progress by user** inside the course routine editor shows stored users, attempts, scores, timestamps and observed lessons. All learning data, including DGS study history, uses the original core `AppRoomDatabase` at `~/.autopilot/databases/autopilot.db`. Existing `course-progress.db` data is imported transactionally into the new database before any new learning writes; the original file remains untouched. The app uses the existing core builder/configuration, and no separate learning database is active. The unused transfer-history persistence was removed; routine-file import/export is retained. Save failures pause further course actions. No credentials are stored. Desktop/JVM validation is recorded; no mobile persistence support is claimed.

This routine uses separate extension scripts; the retired Web page helper and Course helper routines have been removed. Other routine types retain their behavior. While it runs, the extension reports semantic page elements (controls, headings, status context and videos, including hidden/disabled controls) to the local app. The app chooses the action and the extension executes it once against that document's element ID. Input values and full page HTML are excluded. Very large or unrecognized pages pause for manual handling. A course without a dashboard-return control finishes after its available videos.

After updating the app, use **Settings > Chrome extension > Show extension folder**, reload the extension in `chrome://extensions`, and refresh the course tabs so Chrome loads the new scripts. The signed-in live site still needs validation; automated coverage uses isolated local course pages in headless Chrome.

Set up the extension once from **Settings > Chrome extension**: press **Show extension folder**, open `chrome://extensions`, turn on Developer mode, choose **Load unpacked**, and select that folder. The extension talks only to the app on this computer.

Some pages ignore scripted clicks, or use a player the helper can't read, such as a canvas. On those you click as usual.

## Stopping a run

- Press **F6** in any app. On a Mac keyboard you may need to hold fn. It stops a scroll and click run too.
- Push the pointer into any **corner of a screen**.
- Move the pointer by hand, for a routine with specific points or a scroll and click run that has made its first click.
- A click that would land on AutoPilot's own focused window waits instead, so Stop stays reachable.

The hot keys and both pointer stops can be changed in Settings.

## Platform status

| Platform | Status | Notes |
| --- | --- | --- |
| macOS (Apple Silicon) | Tested | Needs Accessibility permission, which the app asks for. |
| Windows | Not tested yet | The code compiles, but it hasn't run on Windows. Apps running as administrator can't be clicked. |
| Linux | Not supported yet | The app opens but can't click. |
| Android, iOS | Not supported yet | The app opens and says it's desktop-only. |

Clicks go to a position on the screen, so the target must be visible. If a page scrolls or a window moves, a saved point keeps clicking the same spot on the screen.

On macOS, a run from the IDE or a terminal uses that app's Accessibility permission, while a packaged app asks for its own.

## Requirements

- JDK 21 (Gradle provisions it; Android Studio's bundled JDK can launch Gradle)
- Android Studio with the Kotlin Multiplatform plugin, Android SDK 37
- Xcode 16.2 or later for iOS

## Build and run

Use `.\gradlew.bat` on Windows.

| Target | Command |
| --- | --- |
| Desktop | `./gradlew :desktopApp:run` |
| Android (debug) | `./gradlew :androidApp:installDebug` |
| iOS | Open [`iosApp`](./iosApp) in Xcode and run |

## Tests

```shell
./gradlew jvmTest verifyFeatureModuleBoundaries
```

Three extras are opt-in:

- `UI_TEST_SHOT_DIR=/some/dir` saves what each UI test rendered.
- `AUTOPILOT_LIVE_INPUT_TEST=1 ./gradlew :core:automation:jvmTest --tests '*LiveInputTest*' --rerun` sends real clicks and a key press to its own window. It moves your pointer, so keep your hands off while it runs.
- `AUTOPILOT_LIVE_CHROME_TEST="<path to Chrome>" ./gradlew :shared:jvmTest --tests '*LiveChromeExtensionTest*' --rerun` runs the extension's scripts in headless Chrome with a throwaway profile.

For the new routine, use `AUTOPILOT_LIVE_CHROME_TEST="<path to Chrome>" ./gradlew :shared:jvmTest --tests '*LiveCourseAutomationTest*' --rerun`. Its protocol tests also run with `node --test shared/src/jvmTest/resources/course-automation-background.test.cjs`.

## Release builds

Version name and code live in [`gradle/libs.versions.toml`](./gradle/libs.versions.toml).

| Platform | Built on | Command | Output |
| --- | --- | --- | --- |
| macOS DMG (Apple Silicon) | Mac | `./gradlew :desktopApp:packageReleaseDmg` | `desktopApp/build/compose/binaries/main-release/dmg/` |
| Windows MSI | GitHub Actions | `gh workflow run desktop-packages.yml` | Run artifacts |
| Linux DEB, macOS DMG (Intel) | GitHub Actions | `gh workflow run desktop-packages.yml -f platforms=all` | Run artifacts |

The installers are not code-signed yet, so macOS Gatekeeper and Windows SmartScreen warn on first launch.

## DGS E-Learning

Create a **DGS E-Learning** routine and enter the course’s required hours and the hours already credited by DGS. Sign in at https://dgsstcwlms.aduacademy.in/form/studenthome in a single Chrome tab, then start the routine. Choose an available course in the app popup and press Open course. Unavailable courses are hidden. The app opens the selected e-book and reads forward from the page restored by DGS. You can also start with an e-book already open. Keep Chrome in front while reading.

The routine slowly scrolls each page, watches encountered videos to their actual end, then uses Next. It starts reading forward from the page you opened. Short pages receive at least 30 seconds of reading time. When all pages are read but time remains, it returns to the first outline page and repeats forward. Study time is saved locally under the DGS display name and stable course ID, alongside the course name. Restarting a routine or the app resumes the cumulative total. The required hours and externally credited baseline are saved on the first run; later routine defaults do not overwrite them. Local active time remains an estimate, separate from DGS’s official credit. Login, verification, unsupported videos, and unchanged navigation pause for manual help. Playback defaults to 1×; faster rates are attempted only through the player, with the actual rate shown in Running routine.

DGS assessments remain manual. `dgs-elearning-ver-1.0.json` is the reserved bank name in `~/.autopilot/answer-banks/`; routine options include import and Open Q&A folder. Extension **1.14.0** adds Chrome-native input for the selected available course link on DGS Home and for e-book Next and first-page controls. It requires explicit approval of Chrome’s broader `debugger` permission before installation. The implementation attaches only to the bound, foreground DGS tab for an exact pending navigation request, verifies the page and current control position, then detaches immediately. It does not use this permission for page evaluation or network capture. An existing debugger/DevTools connection pauses navigation rather than being disconnected. After approving and reloading an update, refresh the DGS tab and reopen the e-book if DGS returns to its home page.

## Project structure

```text
shared/            Shared app: App.kt, AppServices, navigation host, platform actuals, desktop window, bundled extension
androidApp/        Android app shell: MainActivity, MainApplication, manifest, launcher icons, signing
desktopApp/        Desktop app shell: main(), MSI/DMG/DEB packaging, app icons, Smart App Control fixes
iosApp/            Xcode project; links the Shared framework built by :shared
core/automation    Clicks, global hot keys, front app, input permission (macOS, Windows)
core/localbridge   Loopback link to a trusted local client, such as the Chrome extension
core/common        Platform and time helpers (template)
core/logger        Logging (template)
core/ui            Design system: theme and palettes, shared components, navigation (template)
core/persistence   Preference storage and Room (template)
core/network       Template module, not used by this app
core/notifications Template module, not used by this app
core/transfer      Template module, not used by this app
feature/home       Routines page: every kind of routine, the editor, and their runners
feature/activity   Run history
feature/settings   Hot keys, safety, permission, appearance
feature/shared     Contracts shared by more than one feature
iosApp/            iOS entry point (Xcode project)
```

Features depend only on core modules and `feature:shared`, never on each other. [How it works](private/docs/14_HOW_AUTOPILOT_WORKS.md) explains every step, including how the app talks to Chrome. Coding and architecture conventions are in [AGENTS.md](./AGENTS.md).

## AutoPilot identity

The application namespace is `com.jagdeep.autopilot`; desktop data and the unpacked extension live in `~/.autopilot/`. Routine exports use `.autopilot.json` and the `autopilot-routines` format. This identity is separate from earlier installations, including mobile app sandboxes and OS permissions.

## Saved DGS progress and desktop files

DGS progress is recorded automatically using the signed-in DGS name and selected course. Routine options → Saved progress by user shows course totals, session count, first/last study times, pages read and video completion. Running routine shows cumulative and current-run time separately. Checkpoints save every five seconds and when pages change; Stop and normal app shutdown flush the final checkpoint. A forced process kill may lose up to five seconds. Inactive Chrome, stale readings, verification and buffering do not add study time. A changed account/course or storage failure pauses the routine. Assessments remain manual.

Desktop files are grouped under `~/.autopilot/`:

```text
settings/           appearance, hotkeys and other preferences
routines/           saved routines
history/            general run history
databases/          per-user learning progress database and journals
answer-banks/       named question banks
extensions/chrome/ unpacked AutoPilot Page Helper
```

Existing files are moved before their stores open. Conflicting destinations are never overwritten. After moving the extension folder, load the folder shown above through Chrome’s Load unpacked option; the extension ID and permissions stay unchanged. No website passwords are stored.

Windows MSI builds and Mac-to-Windows debugging: [setup and commands](private/scripts/windows/README.md).

### Extension version and alert checks

Settings → Browser connection always shows the version bundled with this app and the version actually reported by Chrome. Startup updates the unpacked extension files. Chrome still needs a manual extension reload and page refresh to load changed scripts. Browser routines refuse to start with an unverified/mismatched version or a folder-update failure and explain the next step.

Settings → Alert sounds is enabled by default and includes Test sound. Question reminders use two notes every 30 seconds; failed assessments use three descending notes. A routine with no progress for five minutes uses a distinct four-note alert, repeating every five minutes until progress resumes or the routine stops. Warnings remain visible with sound disabled. Advancing video time, scrolling, lesson navigation and answers count as progress; elapsed-time labels and unchanged heartbeats do not. Scheduled click waits and countdowns are intentional waits, not stalls. No volume or Windows audio settings are changed automatically.

### Versioned answer-bank files

Use `name-ver-1.0.json`, increasing the version for subsequent bank releases. The defaults are `sagarmeinyog-ver-1.0.json` and the reserved `dgs-elearning-ver-1.0.json`. Project sources live in `private/answer-banks/`; installed copies live in `~/.autopilot/answer-banks/`. Built-in former default names resolve to the versioned default so saved routines continue to work. Old local files are preserved; explicitly selected versioned filenames remain pinned. Add a new version and choose it in routine options rather than overwriting an existing user bank.

Sagar Mein Yog uses plain-text `options` and `answer` values in its versioned JSON bank. Display-order prefixes from the website are removed only for matching and before approved answers are saved; meaningful numeric values such as `2.1 liters` are preserved. The earlier unversioned bank is not used as a fallback.
