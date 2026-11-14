# 00 — AutoPilot Documentation Index

**Open this file first.** The guides are numbered by how often you'll need them: 01–09 are everyday tasks, 10–13 are the home-server route, 14–19 are background reading and reference (including the Git guide), 20–24 and 29 are one-time machine setup, 25–28 are old shortcut pages, and 30 is the CMP project standard. Each guide links back here. Opening a guide doesn't start any build, installation or connection.

Updated 13 November 2026.

## Find it fast

In Android Studio, Cmd+Click a link in the editor to follow it. To click links in the preview, see the note at the top of the [Git guide](19_GIT_GUIDE.md).

| I want to… | Go to |
| --- | --- |
| Build, install and debug with one command | [01 · Quick reference](01_ONE_COMMAND_BUILDS.md#quick-reference) |
| Copy AutoPilot progress, settings and certificates from every machine | [01 · Sync machine data](01_ONE_COMMAND_BUILDS.md#sync-machine-data) |
| Go to the scripts folder | [01 · Go to the scripts folder](01_ONE_COMMAND_BUILDS.md#go-to-the-scripts-folder) |
| Start, stop or test the server VM | [12 · One line with the script](12_START_STOP_WINDOWS_BUILD_VM.md#one-line-with-the-script) · [by hand](12_START_STOP_WINDOWS_BUILD_VM.md#separately-by-hand) |
| Install or update on Legion over SSH | [03 · Part S](03_INSTALL_AND_TEST_WINDOWS.md#part-s--install-or-update-over-ssh--from-the-mac) |
| Fix "Error 1723" or "Smart App Control blocked an app" | [09 · Fix installer errors](09_FIX_WINDOWS_INSTALLER.md) |
| Fix "already installed" | [09 · H5 Updating an installed copy](09_FIX_WINDOWS_INSTALLER.md#h5-updating-an-installed-copy) |
| See why a one-command run stopped | [01 · When it stops](01_ONE_COMMAND_BUILDS.md#when-it-stops) |
| Migrate a project to the CMP standard, or check how a project must be laid out | [30 · CMP project standard and migration](30_CMP_PROJECT_STANDARD_AND_MIGRATION.md) |
| Debug the Windows app from the Mac | [04 · Debug from the Mac](04_DEBUG_WINDOWS_FROM_MAC.md) |
| Look up a Git command, or fix a Git problem | [19 · Cheat sheet](19_GIT_GUIDE.md#16-cheat-sheet) · [Recipes](19_GIT_GUIDE.md#14-recipes-for-common-situations) · [Undoing things](19_GIT_GUIDE.md#7-undoing-things) |
| A pull refused because of local changes | [19 · Recipe 14.7](19_GIT_GUIDE.md#147-a-build-machine-has-local-changes) |

## 01–09 · Everyday

| What you want to do | Guide |
| --- | --- |
| **Sync, build, install and debug on any machine with one command** (`private/scripts/desktop.sh`) | [01_ONE_COMMAND_BUILDS.md](01_ONE_COMMAND_BUILDS.md) |
| Build the Windows MSI on Legion, step by step | [02_BUILD_WINDOWS_ON_LEGION.md](02_BUILD_WINDOWS_ON_LEGION.md) |
| Install, update and test AutoPilot on Windows (desktop or over SSH) | [03_INSTALL_AND_TEST_WINDOWS.md](03_INSTALL_AND_TEST_WINDOWS.md) |
| Debug the Windows app from Android Studio on the Mac | [04_DEBUG_WINDOWS_FROM_MAC.md](04_DEBUG_WINDOWS_FROM_MAC.md) |
| Check the Chrome extension version and alert sounds after an update | [05_BROWSER_AND_ALERT_SETTINGS.md](05_BROWSER_AND_ALERT_SETTINGS.md) |
| Add or update a versioned answer bank | [06_UPDATE_ANSWER_BANK.md](06_UPDATE_ANSWER_BANK.md) |
| Resume saved Sagar Mein Yog progress | [07_SAVED_COURSE_PROGRESS.md](07_SAVED_COURSE_PROGRESS.md) |
| Copy and verify build files on the Mac | [01 · Fetch and verify build artifacts](01_ONE_COMMAND_BUILDS.md#fetch-and-verify-build-artifacts) |
| Fix installer errors: Error 1723, "Smart App Control blocked an app", "already installed" | [09_FIX_WINDOWS_INSTALLER.md](09_FIX_WINDOWS_INSTALLER.md) |

**Usual order:** run `private/scripts/desktop.sh run legion` (or `run mac`, `run server`, `run all`) from the Mac, then open the app on that machine and check it. `run all` includes the server Windows VM and both Mac-local VMs. Use 02–04 when you want to do one step by hand. Legion is `ssh home-legion`, checkout `C:\Users\jagde\Projects\autopilot-cmp`.

## 10–13 · Home server — Linux DEB and the Windows VM

| What you want to do | Guide |
| --- | --- |
| Build the Linux DEB on Debian | [10_BUILD_LINUX_ON_SERVER.md](10_BUILD_LINUX_ON_SERVER.md) |
| Copy server-built installers to the Mac, including the server VM MSI | [11_COPY_SERVER_INSTALLERS.md](11_COPY_SERVER_INSTALLERS.md) |
| Start, stop, test or monitor the server's Windows VM (one line: `private/scripts/server-vm.sh`) | [12_START_STOP_WINDOWS_BUILD_VM.md](12_START_STOP_WINDOWS_BUILD_VM.md) |
| Build the MSI inside that VM (alternative to Legion) | [13_BUILD_WINDOWS_IN_SERVER_VM.md](13_BUILD_WINDOWS_IN_SERVER_VM.md) |

Use `private/scripts/desktop.sh build server-vm-win` to build the server Windows VM MSI and `private/scripts/desktop.sh fetch all` to collect existing remote artifacts. `private/scripts/desktop.sh run all` deliberately leaves the server DEB on the server. The server is a headless website host: install and test the DEB on a Linux desktop, and MSIs on Legion.

## 14–19 · Background, reference and recorded evidence

| Topic | Guide |
| --- | --- |
| How AutoPilot works, from launch to the last click | [14_HOW_AUTOPILOT_WORKS.md](14_HOW_AUTOPILOT_WORKS.md) |
| Why the Windows installer and app were blocked, and how that was verified | [15_WINDOWS_INSTALLER_DIAGNOSIS.md](15_WINDOWS_INSTALLER_DIAGNOSIS.md) |
| Windows build and debug details, with recorded tests | [16_WINDOWS_BUILD_DEBUG_REFERENCE.md](16_WINDOWS_BUILD_DEBUG_REFERENCE.md) |
| Server tests and earlier build results | [17_SERVER_BUILD_TESTS_AND_RESULTS.md](17_SERVER_BUILD_TESTS_AND_RESULTS.md) |
| How SSH and its keys work | [18_UNDERSTAND_SSH_AND_KEYS.md](18_UNDERSTAND_SSH_AND_KEYS.md) |
| **Git: how it works, every important command, recipes and a cheat sheet** | [19_GIT_GUIDE.md](19_GIT_GUIDE.md) |

## 30 · Project standard

| What | Guide |
| --- | --- |
| **The standard for every CMP project:** structure, packages, Gradle setup, the `private/` folder, the migration procedure, and a reusable prompt | [30_CMP_PROJECT_STANDARD_AND_MIGRATION.md](30_CMP_PROJECT_STANDARD_AND_MIGRATION.md) |

## 20–24, 29 · One-time machine setup

Legion, the server, the server VM, and the Mac-local build VMs are already set up or documented. Use these only for a new machine, or to repair one.

| Task | Guide |
| --- | --- |
| Set up SSH and PowerShell access on Legion | [20_SET_UP_WINDOWS_SSH.md](20_SET_UP_WINDOWS_SSH.md) |
| Set up protected keys and the macOS Keychain | [21_SET_UP_PASSWORD_FREE_SSH.md](21_SET_UP_PASSWORD_FREE_SSH.md) |
| Clone AutoPilot on Legion for the first time | [22_CLONE_AUTOPILOT_ON_LEGION.md](22_CLONE_AUTOPILOT_ON_LEGION.md) |
| Register the `AutoPilot-Debug` launcher on Legion | [23_SET_UP_WINDOWS_DEBUG_LAUNCHER.md](23_SET_UP_WINDOWS_DEBUG_LAUNCHER.md) |
| Reach Legion through the home server over Cloudflare | [31_SETUP_LEGION_VIA_HOME_SERVER_CLOUDFLARE.md](31_SETUP_LEGION_VIA_HOME_SERVER_CLOUDFLARE.md) |
| Install the home server and its Windows VM | [24_SET_UP_HOME_SERVER_AND_WINDOWS_VM.md](24_SET_UP_HOME_SERVER_AND_WINDOWS_VM.md) |
| Set up Windows and Linux build VMs on this Mac | [29_SET_UP_MAC_LOCAL_BUILD_VMS.md](29_SET_UP_MAC_LOCAL_BUILD_VMS.md) |

## 25–28 · Old shortcut pages

Short pages left from an earlier layout. They only link to the guides above.

- [25 — Windows tasks](25_WINDOWS_TASKS_QUICK_LINKS.md)
- [26 — Initial setup tasks](26_INITIAL_SETUP_QUICK_LINKS.md)
- [27 — Build tasks](27_BUILD_TASKS_QUICK_LINKS.md)
- [28 — Server setup tasks](28_SERVER_SETUP_QUICK_LINKS.md)
