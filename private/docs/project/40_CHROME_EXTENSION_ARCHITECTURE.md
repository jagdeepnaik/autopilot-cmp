# 40 — How the Chrome Extension Works

This guide explains how AutoPilot controls Chrome, how the unpacked extension is installed and updated, how messages travel between Kotlin and JavaScript, and what each extension-related file does.

## On this page

- [What the extension is](#what-the-extension-is)
- [The complete control path](#the-complete-control-path)
- [The local bridge](#the-local-bridge)
- [How the extension is installed and updated](#how-the-extension-is-installed-and-updated)
- [The shared scroll-and-click channel](#the-shared-scroll-and-click-channel)
- [Sagar Mein Yog course automation](#sagar-mein-yog-course-automation)
- [DGS e-learning automation](#dgs-e-learning-automation)
- [How a course action is authorized](#how-a-course-action-is-authorized)
- [How snapshots and actions are correlated](#how-snapshots-and-actions-are-correlated)
- [Safety and privacy boundaries](#safety-and-privacy-boundaries)
- [Troubleshooting](#troubleshooting)
- [Code map](#code-map)

## What the extension is

AutoPilot is a desktop Kotlin/Compose application. Chrome cannot be controlled directly by a desktop application, and a normal web page cannot safely read or click every page element on another site. The unpacked Chrome extension is the small, local helper between the two:

```text
AutoPilot desktop app
        │
        │  HTTP long-polling on 127.0.0.1:47613
        ▼
Chrome extension service worker
        │
        │  chrome.tabs / chrome.runtime messages
        ▼
Content script in the active tab and its frames
        │
        ▼
The visible page, video player or course control
```

The extension does not contain the routine decision-making logic. Kotlin decides what should happen next. JavaScript inventories the page, performs the narrowly authorized action, and reports what actually happened.

There are three related channels in the same extension:

1. The shared scroll-and-click channel for generic pages.
2. The Sagar Mein Yog channel for course videos, quizzes, assessments and certificates.
3. The DGS E-Learning channel for the DGS e-book and video player.

## The complete control path

### 1. AutoPilot starts the local bridge

The desktop app creates a loopback-only HTTP bridge on port `47613`. The bridge exposes two endpoints:

```text
GET  http://127.0.0.1:47613/state?after=<version>
POST http://127.0.0.1:47613/event
```

`/state` is the command path. The extension asks for the latest JSON command and waits if nothing has changed. `/event` is the report path. The extension posts page state, clicks, snapshots and connection events back to Kotlin.

### 2. The app publishes a command

`PageController.publish()` creates a `HelperCommand` and serializes it as one JSON value. It contains the common state plus an optional command for one specialized channel:

- `autoRun`, `scrolling`, `pixelsPerSecond`, `watchVideos` and `clickText` for the generic helper.
- `courseAutomation` for Sagar Mein Yog.
- `dgs` for DGS E-Learning.

When no Chrome routine is running, `enabled` is false and the extension only remains connected enough to recover when a routine starts. When a routine stops, the app publishes a disabled command and clears the active session.

### 3. The service worker receives the command

`background.js` continuously long-polls `/state`. It stores the latest command in `command`, resets request counters when the app restarts, and passes the relevant part of the command to the content script through `chrome.runtime.sendMessage`.

The service worker also receives `tick` messages from content scripts. It decides whether the sender is the active tab, combines state from all frames, reports page changes, and returns instructions such as:

```text
scrolling: true/false
autoRun: true/false
watchVideos: true/false
clickText: one requested label or null
```

### 4. A content script inventories the page

Content scripts run in the page and its iframes. They can inspect the DOM available to that frame, find visible controls and videos, and perform only the action returned by the service worker.

The scripts never decide which course or assessment should be run. They report facts such as “this button exists”, “this video is playing”, “this quiz question has these options”, or “the certificate download button is visible”. Kotlin makes the next decision.

### 5. The result returns to Kotlin

The service worker posts an event to `/event`. `PageController.onMessage()` decodes it and forwards it to the appropriate runner:

- generic `page`, `found`, `textClicked` and `extensionVersion` events update the ordinary page helper;
- `courseChunk` events are reassembled into a `CourseSnapshot` and processed by `CourseAutomationSession` and `CourseProgressSession`;
- `courseAction` acknowledges whether a requested course action was accepted;
- DGS events are passed to `DgsSession` and `DgsStudyCoordinator`.

The Kotlin runner then publishes the next command. This repeats until the routine completes, pauses for the user, fails safely, or is stopped.

## The local bridge

The bridge is defined by [`LocalBridge.kt`](../../../core/localbridge/src/commonMain/kotlin/com/jagdeep/localbridge/LocalBridge.kt) and implemented for desktop by [`LocalBridge.jvm.kt`](../../../core/localbridge/src/jvmMain/kotlin/com/jagdeep/localbridge/LocalBridge.jvm.kt).

### Long polling

The bridge stores:

- the latest state JSON;
- a monotonically increasing state version;
- the time of the last extension request;
- the number of waiting polls.

When the extension requests `/state?after=12` and the current version is still `12`, the server waits up to 20 seconds. Publishing a new command increments the version and wakes the request immediately. This avoids fast polling while keeping a new click or assessment action responsive.

### Connection status

The app considers the extension connected while a poll is waiting or for a short grace period after an event. The UI therefore shows a connection that reflects recent extension activity, not a permanent socket that can become stale.

### Origin and request checks

The bridge binds to the loopback address, not the network interface. It accepts either:

- the configured local extension origin; or
- Chrome's extension request headers (`Sec-Fetch-Site: none` and `Sec-Fetch-Mode: cors`) when Chrome omits `Origin` for an extension host permission request.

Normal web pages cannot forge the required browser-controlled headers. Requests that fail the check receive `403`. Events are limited to 16 KiB at the HTTP boundary.

Android and iOS use `UnsupportedLocalBridge`; this Chrome desktop bridge is a JVM desktop capability.

## How the extension is installed and updated

The source files are bundled under [`shared/src/jvmMain/resources/chrome-extension`](../../../shared/src/jvmMain/resources/chrome-extension).

[`BrowserExtension.jvm.kt`](../../../shared/src/jvmMain/kotlin/com/jagdeep/autopilot/platform/BrowserExtension.jvm.kt) does the following:

1. Reads the bundled manifest version for the Settings connection status.
2. Copies the extension to the user's AutoPilot data directory.
3. Compares bytes before writing, so unchanged files are not rewritten.
4. Writes `manifest.json` last, so an interrupted update does not leave a new version marker with old code.
5. Runs the sync at desktop startup in the background.
6. Opens the folder when the user chooses the extension-folder action.

The manifest currently grants only the permissions needed by the extension:

- `alarms` keeps the service worker recoverable;
- `debugger` supports the controlled DGS/native-click path and the Sagar Mein Yog video-play click;
- `scripting` puts `course-automation.js` back into an open sagarmeinyog.com tab whose copy was cut off by an extension reload or update (only that file, only that site);
- `http://127.0.0.1:47613/*` permits the local bridge calls, and `https://sagarmeinyog.com/*` the course tab.

The content scripts use page matches, but each specialized script performs its own origin and frame checks before doing work. After an app update, reload **AutoPilot Page Helper** from `chrome://extensions` so Chrome loads the changed unpacked files.

## The shared scroll-and-click channel

### `content.js`

[`content.js`](../../../shared/src/jvmMain/resources/chrome-extension/content.js) is injected into every matching page and frame. It:

- finds the largest usable scroll container;
- reports a compact page signature made from hashes, dimensions and lengths rather than page text;
- reports whether the active frame is at the bottom;
- finds visible, enabled clickable controls;
- clicks a requested text label once per request;
- observes videos in view;
- starts ordinary videos when the routine's “watch videos” option is enabled;
- waits for a video to really finish before scrolling onward;
- avoids looping, silent autoplay decoration and live streams;
- never seeks a video or changes its duration;
- leaves a video paused when the user pauses it;
- resets its state when the helper is disabled.

The script sends a `tick` every 700 ms while active and less frequently while idle. It uses `IntersectionObserver` to remember videos that were reached, so a fast layout change cannot silently skip one.

### `background.js`

[`background.js`](../../../shared/src/jvmMain/resources/chrome-extension/background.js) is the Manifest V3 service worker for the shared channel. It:

- long-polls the app;
- tracks the active tab and fresh frame reports;
- selects the largest scrollable frame;
- combines video state across frames;
- reports page changes only when the compact state changes;
- sends one requested text click to the best matching frame;
- reports focus changes immediately;
- keeps the service worker alive with a timer and an alarm;
- loads the course and DGS background channels.

The service worker does not use a broad “click anything” command. A text click has a monotonically increasing `clickRequest`; the same request cannot be executed twice.

## Sagar Mein Yog course automation

The Sagar channel is split deliberately:

```text
course-automation.js            page inventory and DOM actions
course-automation-background.js authorization, chunking and reports
CourseAutomation.kt             decision/state machine
PageController.kt               bridge/session orchestration
CourseProgressSession.kt        saved progress and module sequencing
```

### `course-automation.js`: page inventory and actions

[`course-automation.js`](../../../shared/src/jvmMain/resources/chrome-extension/course-automation.js) runs on the Sagar site and inspects the visible dashboard/course page. It creates a random document id so actions are bound to the exact loaded document, not just a URL.

It inventories:

- course cards and their stable course ids;
- module and lesson headings;
- video elements, current position, duration, playback state and playback speed;
- expandable rows and visible buttons;
- assessment and quiz questions;
- options, selected state and enabled state;
- Next, Start, Submit, End Test, Retry and certificate controls;
- pass/fail result and score;
- the profile name and course/module context;
- the page's progress marker.

It also recognizes the quiz-learning screen. After a quiz answer is submitted, the page feedback is read to distinguish the selected answer from the displayed correct answer. Kotlin can then add a new question or correct an existing answer bank entry.

Actions are document-bound. Before clicking, the script checks the session, request, page identity, visibility and control state. It supports:

- DOM actions for normal course controls;
- actual video playback and playback speed changes without seeking;
- a browser-click fallback for controls that require a real Chrome click;
- a limited native-click fallback where the app receives a screen coordinate only after the page validates it.

### `course-automation-background.js`: authorization and transport

[`course-automation-background.js`](../../../shared/src/jvmMain/resources/chrome-extension/course-automation-background.js) is loaded into the service worker by `background.js`. It is the security gate for Sagar messages.

It only authorizes:

- the active, focused Chrome tab;
- the Sagar dashboard origin;
- a current `courseAutomation.session` from the app;
- a fresh top-frame dashboard/course report;
- a frame whose document id matches the action;
- an action whose request and document match the command currently published by Kotlin.

It removes assessment/profile/progress data from child frames and limits cross-origin frames to video state. It rejects oversized snapshots and sends accepted snapshots back as `courseChunk` events.

It also keeps the course tab working (1.16.0): every second during a run it sends `courseWake` to the course tab, so the page script reads the page even when Chrome slows a hidden or covered tab's timers; a tab that doesn't answer gets a new copy of the script (`chrome.scripting`, at most every 10 seconds), as does every open course tab after an install, update or reload. On the app's `reloadPageRequest` it reloads the course tab once, only if that tab is active, and replies `smyPageReloaded`. See [when a run stops moving](30_HOW_AUTOPILOT_WORKS.md#when-a-sagar-mein-yog-run-stops-moving).

Snapshots are split into 1000-character chunks because the local bridge limits event bodies to 16 KiB. A snapshot has a generated id, an ordered index and a total count. The Kotlin assembler refuses missing, duplicated, mixed-session or out-of-order chunks.

### `CourseAutomation.kt`: the decision maker

[`CourseAutomation.kt`](../../../feature/home/src/commonMain/kotlin/com/jagdeep/home/domain/CourseAutomation.kt) contains the course domain models and state machine. It decides:

- which course/module/lesson is next;
- whether videos must be watched before assessment;
- whether a quiz should be answered automatically;
- whether an answer comes from the bank or needs user review;
- whether automatic, review-approval or manual assessment mode applies;
- when to ask permission before starting or submitting an assessment;
- how to retry a failed assessment;
- when a passed module may be recorded;
- when certificate handling must pause for user confirmation;
- how lesson/module completion is handed to persistent progress.

The extension never makes these policy decisions. It only reports the page and carries out the current action.

### `PageController.kt`: orchestration

[`PageController.kt`](../../../feature/home/src/commonMain/kotlin/com/jagdeep/home/domain/PageController.kt) owns the process-scoped Chrome routine. It:

- creates the course session with the selected routine settings;
- publishes commands to the bridge;
- consumes course chunks and action acknowledgements;
- feeds snapshots into `CourseProgressSession` and `CourseAutomationSession`;
- updates live status and the running screen;
- handles user approvals, manual answers, retries and module actions;
- records progress and run history;
- stops and clears all active sessions safely;
- reloads the course tab once when the run stops moving outside a wait or an assessment (`CourseStallRecovery`).

The command is suppressed while a progress/profile confirmation or manual-answer decision blocks the course. This is why the page can remain visible in Chrome while the app waits for the user's choice.

## DGS e-learning automation

The DGS channel is separate from the Sagar course channel:

```text
dgs-elearning.js            DGS page/e-book/video inventory and playback
dgs-elearning-background.js authorization, reading reports and actions
dgs-browser-clicks.js       restricted browser/native click path
DgsSession.kt               navigation and reading state
DgsStudy.kt                saved study records and timing
```

### `dgs-elearning.js`

[`dgs-elearning.js`](../../../shared/src/jvmMain/resources/chrome-extension/dgs-elearning.js) recognizes the DGS home page, e-book page and YouTube embed frames. It reports course lists, e-book page identity, outline position, reading readiness, media state and playback speed. It can select a course, move to the next/previous page and play a real video without seeking.

Assessments in this channel remain manual. The script deliberately does not inventory or submit DGS assessments.

### `dgs-elearning-background.js`

[`dgs-elearning-background.js`](../../../shared/src/jvmMain/resources/chrome-extension/dgs-elearning-background.js) verifies the DGS origin, active tab, session, page, request and permitted action before returning a DGS command. It reports `dgsReading`, `dgsBrowserAction` and validated click events to the app.

### `dgs-browser-clicks.js`

[`dgs-browser-clicks.js`](../../../shared/src/jvmMain/resources/chrome-extension/dgs-browser-clicks.js) is a narrow fallback for DGS controls that do not respond reliably to DOM events. It does not expose a general remote mouse. It checks the current command, page, action, course identity, viewport and coordinates before asking the app to perform the click.

## How a course action is authorized

Every course action follows this sequence:

```text
1. Kotlin publishes session + request + document + action.
2. The active page reports a matching inventory.
3. The service worker verifies tab, origin, focus and session.
4. The page verifies document, page identity and visible control.
5. The page performs the single requested action.
6. The service worker reports accepted/refused.
7. Kotlin acknowledges it and chooses the next action.
```

An action is not repeated merely because the same button is still visible. The request id and document-bound action key prevent duplicate clicks while a page is rendering or a response is delayed.

For a certificate, the page reports pass and the visible certificate control. Depending on the routine setting, Kotlin either asks the user to download/confirm or sends the controlled download action. The certificate store then verifies the expected file before module completion is recorded.

## How snapshots and actions are correlated

There are several identities, each with a different purpose:

| Identity | Purpose |
| --- | --- |
| Bridge version | Tells the extension that a new app command is available. |
| Course session | Separates one running routine from an old or restarted routine. |
| Document id | Separates a freshly loaded page from a previous page at the same URL. |
| Snapshot id/index/total | Reassembles one large inventory safely. |
| Action request | Ensures one requested click/play/answer is acknowledged once. |
| Element id | Identifies a visible DOM control inside the current document snapshot. |

The Kotlin [`CourseSnapshotAssembler`](../../../feature/home/src/commonMain/kotlin/com/jagdeep/home/domain/CourseSnapshotAssembler.kt) drops incomplete or inconsistent snapshots. This prevents a partial inventory from triggering an assessment answer or a module transition.

## Safety and privacy boundaries

- The bridge listens only on `127.0.0.1:47613`.
- Requests are checked using browser-controlled origin/fetch headers.
- Course actions require the active, focused Chrome tab.
- Session, document, page and request identities must all match.
- Child frames cannot report assessment controls or profile/progress data.
- Cross-origin frames are limited to video state where needed.
- Generic page reports use hashes and measurements instead of sending page text to Kotlin.
- Events have a size limit and snapshots have a chunk limit.
- The extension does not receive passwords or general form contents.
- The app decides whether to answer, submit, pause, retry or ask the user.
- A stop command clears the active session and prevents stale JavaScript commands from continuing.

## Troubleshooting

### The app says the extension is disconnected

1. Confirm AutoPilot is running.
2. Open `chrome://extensions`.
3. Find **AutoPilot Page Helper** and click **Reload**.
4. Return to the course tab and keep it in the foreground.
5. Check Settings → Browser connection for the connected extension version.

If the extension folder is missing or stale, use the app's **Show extension folder** action. The desktop app rewrites changed files from its bundled copy at startup.

### The app waits for the course list

The course script requires the active, focused Sagar dashboard tab. Refresh the dashboard after reloading the extension. The app will not act on a background tab or a page outside the allowed dashboard path.

### The app is waiting at a quiz or certificate page

Keep the Chrome tab active and inspect the visible page. The app may be waiting for a user approval, an unknown answer, a manual certificate download confirmation, or a page control that did not pass the document/page/request checks. The live status and Activity log show which state is blocking the next command.

### The extension version is old

The version comes from [`manifest.json`](../../../shared/src/jvmMain/resources/chrome-extension/manifest.json). Restart AutoPilot so it syncs the bundled files, then click **Reload** in `chrome://extensions`.

## Code map

| File | Responsibility |
| --- | --- |
| [`manifest.json`](../../../shared/src/jvmMain/resources/chrome-extension/manifest.json) | Manifest V3 name, version, permissions, host permission and script registration. |
| [`background.js`](../../../shared/src/jvmMain/resources/chrome-extension/background.js) | Service worker, local polling, active-tab/frame aggregation and shared channel. |
| [`content.js`](../../../shared/src/jvmMain/resources/chrome-extension/content.js) | Generic scroll, video watching, page signatures and requested text clicks. |
| [`course-automation.js`](../../../shared/src/jvmMain/resources/chrome-extension/course-automation.js) | Sagar page inventory, video/quiz/assessment/certificate DOM actions. |
| [`course-automation-background.js`](../../../shared/src/jvmMain/resources/chrome-extension/course-automation-background.js) | Sagar authorization, session checks, chunk transport and action reports. |
| [`dgs-elearning.js`](../../../shared/src/jvmMain/resources/chrome-extension/dgs-elearning.js) | DGS course/e-book/video inventory and playback. |
| [`dgs-elearning-background.js`](../../../shared/src/jvmMain/resources/chrome-extension/dgs-elearning-background.js) | DGS command authorization and reports. |
| [`dgs-browser-clicks.js`](../../../shared/src/jvmMain/resources/chrome-extension/dgs-browser-clicks.js) | Restricted DGS browser/native click fallback. |
| [`LocalBridge.kt`](../../../core/localbridge/src/commonMain/kotlin/com/jagdeep/localbridge/LocalBridge.kt) | Platform-neutral bridge contract. |
| [`LocalBridge.jvm.kt`](../../../core/localbridge/src/jvmMain/kotlin/com/jagdeep/localbridge/LocalBridge.jvm.kt) | Loopback HTTP server and long-polling implementation. |
| [`BrowserExtension.jvm.kt`](../../../shared/src/jvmMain/kotlin/com/jagdeep/autopilot/platform/BrowserExtension.jvm.kt) | Bundled extension version, copying and update logic. |
| [`PageController.kt`](../../../feature/home/src/commonMain/kotlin/com/jagdeep/home/domain/PageController.kt) | Publishes commands, consumes events and owns the Chrome routine lifecycle. |
| [`CourseAutomation.kt`](../../../feature/home/src/commonMain/kotlin/com/jagdeep/home/domain/CourseAutomation.kt) | Sagar decision/state machine and course policies. |
| [`CourseSnapshotAssembler.kt`](../../../feature/home/src/commonMain/kotlin/com/jagdeep/home/domain/CourseSnapshotAssembler.kt) | Ordered, bounded snapshot chunk reassembly. |
| [`CourseProgressSession.kt`](../../../feature/home/src/commonMain/kotlin/com/jagdeep/home/domain/CourseProgressSession.kt) | Saved module/video/assessment/certificate progress and sequencing. |
| [`DgsStudy.kt`](../../../feature/home/src/commonMain/kotlin/com/jagdeep/home/domain/DgsStudy.kt) | DGS study timing and saved reading state. |

## Tests that protect this connection

The most relevant tests are:

- [`BrowserExtensionTest.kt`](../../../shared/src/jvmTest/kotlin/com/jagdeep/autopilot/platform/BrowserExtensionTest.kt) — bundled files, manifest host permission and sync behavior.
- [`JvmLocalBridgeTest.kt`](../../../core/localbridge/src/jvmTest/kotlin/com/jagdeep/localbridge/JvmLocalBridgeTest.kt) — loopback endpoints, long polling, request rejection and event size limits.
- [`CourseAutomationTest.kt`](../../../feature/home/src/commonTest/kotlin/com/jagdeep/home/domain/CourseAutomationTest.kt) — course decisions, answer handling and assessment states.
- [`PageControllerTest.kt`](../../../feature/home/src/commonTest/kotlin/com/jagdeep/home/domain/PageControllerTest.kt) — command/event protocol, approvals, progress and stale-action protection.
- [`CourseAutomationTest.kt` integration resources](../../../shared/src/jvmTest/resources/course-automation-background.test.cjs) — service-worker authorization and action correlation.
- [`LiveChromeExtensionTest.kt`](../../../shared/src/jvmTest/kotlin/com/jagdeep/autopilot/integration/LiveChromeExtensionTest.kt) — browser-level extension smoke tests.
- [`LiveCourseAutomationTest.kt`](../../../shared/src/jvmTest/kotlin/com/jagdeep/autopilot/integration/LiveCourseAutomationTest.kt) — live-style course inventory and action flow.

Run focused checks from the project root with:

```sh
./gradlew :core:localbridge:jvmTest :shared:jvmTest :feature:home:jvmTest
```

Live site tests should only be run when explicitly requested and require the user's signed-in Chrome session.
