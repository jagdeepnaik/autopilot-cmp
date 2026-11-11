# 00 — AutoPilot Documentation Index

**Open this file first.** Files are numbered for sorting, not a requirement to run every step. Use 01–05 most often; 10–14 are one-time setup. Each guide links back here and to related tasks.

## 01–05 · Frequently used — Legion Windows laptop

| What you want to do | Open this file |
| --- | --- |
| **Build and run focused tests** | [01_BUILD_WINDOWS_ON_LEGION.md](01_BUILD_WINDOWS_ON_LEGION.md) |
| Copy the MSI to the Mac | [02_COPY_WINDOWS_INSTALLER.md](02_COPY_WINDOWS_INSTALLER.md) |
| Install the MSI and test AutoPilot | [03_INSTALL_AND_TEST_WINDOWS.md](03_INSTALL_AND_TEST_WINDOWS.md) |
| Debug the Windows app from the Mac | [04_DEBUG_WINDOWS_FROM_MAC.md](04_DEBUG_WINDOWS_FROM_MAC.md) |
| Diagnose or verify the installer DLL fix | [05_FIX_WINDOWS_INSTALLER.md](05_FIX_WINDOWS_INSTALLER.md) |

**Usual order: Build → optionally copy → install and test.** Use `ssh home-legion` from Mac Terminal. The checkout is `C:\Users\jagde\Projects\autopilot-cmp`. Each task guide says which computer/window to use. Setup is already complete; do not repeat it for each build.

## 06–09 · Alternative builds — home server

| Task | Guide |
| --- | --- |
| Start, stop, or monitor the Windows VM | [06_START_STOP_WINDOWS_BUILD_VM.md](06_START_STOP_WINDOWS_BUILD_VM.md) |
| Build Windows inside that VM | [07_BUILD_WINDOWS_IN_SERVER_VM.md](07_BUILD_WINDOWS_IN_SERVER_VM.md) |
| Build Linux on Debian | [08_BUILD_LINUX_ON_SERVER.md](08_BUILD_LINUX_ON_SERVER.md) |
| Download server-built installers | [09_COPY_SERVER_INSTALLERS.md](09_COPY_SERVER_INSTALLERS.md) |

These are separate machines and checkouts. Use the Legion guide for the laptop; use server guides only when choosing that route.

## 10–14 · One-time preparation — use only when needed

| Task | Guide |
| --- | --- |
| Configure Legion SSH and Windows PowerShell access | [10_SET_UP_WINDOWS_SSH.md](10_SET_UP_WINDOWS_SSH.md) |
| Configure protected keys and macOS Keychain | [11_SET_UP_PASSWORD_FREE_SSH.md](11_SET_UP_PASSWORD_FREE_SSH.md) |
| Clone AutoPilot for the first time | [12_CLONE_AUTOPILOT_ON_LEGION.md](12_CLONE_AUTOPILOT_ON_LEGION.md) |
| Register the interactive debug launcher | [13_SET_UP_WINDOWS_DEBUG_LAUNCHER.md](13_SET_UP_WINDOWS_DEBUG_LAUNCHER.md) |
| Install the server and Windows VM | [14_SET_UP_HOME_SERVER_AND_WINDOWS_VM.md](14_SET_UP_HOME_SERVER_AND_WINDOWS_VM.md) |

## 15–19 · Explanations and recorded evidence — occasional reference

- [SSH principles and key types](15_UNDERSTAND_SSH_AND_KEYS.md)
- [Windows build/debug details and recorded tests](16_WINDOWS_BUILD_DEBUG_REFERENCE.md)
- [Installer diagnosis and verification evidence](17_WINDOWS_INSTALLER_DIAGNOSIS.md)
- [Server tests and earlier build results](18_SERVER_BUILD_TESTS_AND_RESULTS.md)

Updated 11 November 2026. Instructions are AutoPilot-specific; general explanations are kept in reference guides. No build, installation, or connection is started by opening these files.

- [19 — How AutoPilot works](19_HOW_AUTOPILOT_WORKS.md)

## 20–23 · Shortcut pages

These compact pages group related tasks; the numbered task guides above contain the actual instructions.

- [20 — Windows tasks](20_WINDOWS_TASKS_QUICK_LINKS.md)
- [21 — Initial setup tasks](21_INITIAL_SETUP_QUICK_LINKS.md)
- [22 — Build tasks](22_BUILD_TASKS_QUICK_LINKS.md)
- [23 — Server setup tasks](23_SERVER_SETUP_QUICK_LINKS.md)

## 24–25 · Browser, alerts, and answer-bank updates

- [24 — Check extension versions and sounds](24_BROWSER_AND_ALERT_SETTINGS.md)
- [25 — Update a versioned answer bank](25_UPDATE_ANSWER_BANK.md)
