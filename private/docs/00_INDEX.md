# 00 — AutoPilot Documentation Index

**Open this file first.** The guides are numbered by how often you'll need them: 01–08 are everyday tasks, 09–12 are the home-server route, 13–18 are background reading and reference (including the Git guide), and 19–27 are one-time setup and old shortcut pages. Each guide links back here. Opening a guide doesn't start any build, installation or connection.

Updated 11 November 2026.

## Find it fast

In Android Studio, Cmd+Click a link in the editor to follow it. To click links in the preview, see the note at the top of the [Git guide](18_GIT_GUIDE.md).

| I want to… | Go to |
| --- | --- |
| Build, install and debug with one command | [01 · Quick reference](01_ONE_COMMAND_BUILDS.md#quick-reference) |
| Go to the scripts folder | [01 · Go to the scripts folder](01_ONE_COMMAND_BUILDS.md#go-to-the-scripts-folder) |
| Start, stop or test the server VM | [11 · One line with the script](11_START_STOP_WINDOWS_BUILD_VM.md#one-line-with-the-script) · [by hand](11_START_STOP_WINDOWS_BUILD_VM.md#separately-by-hand) |
| Install or update on Legion over SSH | [03 · Part S](03_INSTALL_AND_TEST_WINDOWS.md#part-s--install-or-update-over-ssh--from-the-mac) |
| Fix "Error 1723" or "Smart App Control blocked an app" | [08 · Fix installer errors](08_FIX_WINDOWS_INSTALLER.md) |
| Fix "already installed" | [08 · H5 Updating an installed copy](08_FIX_WINDOWS_INSTALLER.md#h5-updating-an-installed-copy) |
| See why a one-command run stopped | [01 · When it stops](01_ONE_COMMAND_BUILDS.md#when-it-stops) |
| Debug the Windows app from the Mac | [04 · Debug from the Mac](04_DEBUG_WINDOWS_FROM_MAC.md) |
| Look up a Git command, or fix a Git problem | [18 · Cheat sheet](18_GIT_GUIDE.md#16-cheat-sheet) · [Recipes](18_GIT_GUIDE.md#14-recipes-for-common-situations) · [Undoing things](18_GIT_GUIDE.md#7-undoing-things) |
| A pull refused because of local changes | [18 · Recipe 14.7](18_GIT_GUIDE.md#147-a-build-machine-has-local-changes) |

## 01–08 · Everyday

| What you want to do | Guide |
| --- | --- |
| **Sync, build, install and debug on any machine with one command** (`private/scripts/desktop.sh`) | [01_ONE_COMMAND_BUILDS.md](01_ONE_COMMAND_BUILDS.md) |
| Build the Windows MSI on Legion, step by step | [02_BUILD_WINDOWS_ON_LEGION.md](02_BUILD_WINDOWS_ON_LEGION.md) |
| Install, update and test AutoPilot on Windows (desktop or over SSH) | [03_INSTALL_AND_TEST_WINDOWS.md](03_INSTALL_AND_TEST_WINDOWS.md) |
| Debug the Windows app from Android Studio on the Mac | [04_DEBUG_WINDOWS_FROM_MAC.md](04_DEBUG_WINDOWS_FROM_MAC.md) |
| Check the Chrome extension version and alert sounds after an update | [05_BROWSER_AND_ALERT_SETTINGS.md](05_BROWSER_AND_ALERT_SETTINGS.md) |
| Add or update a versioned answer bank | [06_UPDATE_ANSWER_BANK.md](06_UPDATE_ANSWER_BANK.md) |
| Copy all existing Legion and server build files to the Mac | [07 · Fetch all build artifacts](07_COPY_WINDOWS_INSTALLER.md#one-command-fetch-every-build-artifact) |
| Fix installer errors: Error 1723, "Smart App Control blocked an app", "already installed" | [08_FIX_WINDOWS_INSTALLER.md](08_FIX_WINDOWS_INSTALLER.md) |

**Usual order:** run `private/scripts/desktop.sh run legion` (or `run mac`, `run server`, `run all`) from the Mac, then open the app on that machine and check it. `run all` includes the server Windows VM and both Mac-local VMs. Use 02–04 when you want to do one step by hand. Legion is `ssh home-legion`, checkout `C:\Users\jagde\Projects\autopilot-cmp`.

## 09–12 · Home server — Linux DEB and the Windows VM

| What you want to do | Guide |
| --- | --- |
| Build the Linux DEB on Debian | [09_BUILD_LINUX_ON_SERVER.md](09_BUILD_LINUX_ON_SERVER.md) |
| Copy server-built installers to the Mac, including the server VM MSI | [10_COPY_SERVER_INSTALLERS.md](10_COPY_SERVER_INSTALLERS.md) |
| Start, stop, test or monitor the server's Windows VM (one line: `private/scripts/server-vm.sh`) | [11_START_STOP_WINDOWS_BUILD_VM.md](11_START_STOP_WINDOWS_BUILD_VM.md) |
| Build the MSI inside that VM (alternative to Legion) | [12_BUILD_WINDOWS_IN_SERVER_VM.md](12_BUILD_WINDOWS_IN_SERVER_VM.md) |

Use `private/scripts/desktop.sh build server-vm-win` to build the server Windows VM MSI and `private/scripts/desktop.sh fetch all` to collect existing remote artifacts. `private/scripts/desktop.sh run all` deliberately leaves the server DEB on the server. The server is a headless website host: install and test the DEB on a Linux desktop, and MSIs on Legion.

## 13–18 · Background, reference and recorded evidence

| Topic | Guide |
| --- | --- |
| How AutoPilot works, from launch to the last click | [13_HOW_AUTOPILOT_WORKS.md](13_HOW_AUTOPILOT_WORKS.md) |
| Why the Windows installer and app were blocked, and how that was verified | [14_WINDOWS_INSTALLER_DIAGNOSIS.md](14_WINDOWS_INSTALLER_DIAGNOSIS.md) |
| Windows build and debug details, with recorded tests | [15_WINDOWS_BUILD_DEBUG_REFERENCE.md](15_WINDOWS_BUILD_DEBUG_REFERENCE.md) |
| Server tests and earlier build results | [16_SERVER_BUILD_TESTS_AND_RESULTS.md](16_SERVER_BUILD_TESTS_AND_RESULTS.md) |
| How SSH and its keys work | [17_UNDERSTAND_SSH_AND_KEYS.md](17_UNDERSTAND_SSH_AND_KEYS.md) |
| **Git: how it works, every important command, recipes and a cheat sheet** | [18_GIT_GUIDE.md](18_GIT_GUIDE.md) |

## 19–23, 28 · One-time machine setup

Legion, the server, the server VM, and the Mac-local build VMs are already set up or documented. Use these only for a new machine, or to repair one.

| Task | Guide |
| --- | --- |
| Set up SSH and PowerShell access on Legion | [19_SET_UP_WINDOWS_SSH.md](19_SET_UP_WINDOWS_SSH.md) |
| Set up protected keys and the macOS Keychain | [20_SET_UP_PASSWORD_FREE_SSH.md](20_SET_UP_PASSWORD_FREE_SSH.md) |
| Clone AutoPilot on Legion for the first time | [21_CLONE_AUTOPILOT_ON_LEGION.md](21_CLONE_AUTOPILOT_ON_LEGION.md) |
| Register the `AutoPilot-Debug` launcher on Legion | [22_SET_UP_WINDOWS_DEBUG_LAUNCHER.md](22_SET_UP_WINDOWS_DEBUG_LAUNCHER.md) |
| Install the home server and its Windows VM | [23_SET_UP_HOME_SERVER_AND_WINDOWS_VM.md](23_SET_UP_HOME_SERVER_AND_WINDOWS_VM.md) |
| Set up Windows and Linux build VMs on this Mac | [28_SET_UP_MAC_LOCAL_BUILD_VMS.md](28_SET_UP_MAC_LOCAL_BUILD_VMS.md) |

## 24–27 · Old shortcut pages

Short pages left from an earlier layout. They only link to the guides above.

- [24 — Windows tasks](24_WINDOWS_TASKS_QUICK_LINKS.md)
- [25 — Initial setup tasks](25_INITIAL_SETUP_QUICK_LINKS.md)
- [26 — Build tasks](26_BUILD_TASKS_QUICK_LINKS.md)
- [27 — Server setup tasks](27_SERVER_SETUP_QUICK_LINKS.md)

## 26 · Saved course progress

- [26 — Resume saved Sagar Mein Yog progress](26_SAVED_COURSE_PROGRESS.md)
