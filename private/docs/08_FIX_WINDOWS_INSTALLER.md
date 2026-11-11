# 08 — Fix and Verify the AutoPilot Windows Installer

[All guides](00_INDEX.md) · [Normal installation](03_INSTALL_AND_TEST_WINDOWS.md) · [Diagnosis evidence](14_WINDOWS_INSTALLER_DIAGNOSIS.md)

## PART H — MSI UNDER SMART APP CONTROL — build, check, reinstall

Use this part when you need an installer that installs and runs on Legion with Smart App Control on: when an install fails with **Error 1723**, or the app won't open with **"Smart App Control blocked an app that may be unsafe"**. Part H repeats every command you need, so you don't have to jump to the other parts.

### H0. What went wrong and how it's fixed

| Item | Detail |
| --- | --- |
| Symptom | The MSI stops with *"Error 1723. A DLL required for this install to complete could not be run"*. The action named is `JpFindRelatedProducts`, and the install log shows status 1603. |
| Cause | Smart App Control (`VerifiedAndReputablePolicyState` = 1 on Legion) blocks `JpCaDll`. That is an unsigned helper DLL the Java packager (jpackage) puts in every MSI. Windows logs Code Integrity events 3077/3033. No DLL is missing. |
| Fix | After every MSI packaging task, `composeApp/build.gradle.kts` runs `private/scripts/windows/Remove-UnsignedMsiHelper.ps1`. The script removes `JpCaDll` and the two optional actions it backs. Commit `b0faba5`. |
| What you lose | Only the installer's *"folder is not empty"* warning on the install-folder page. Upgrades still work through the MSI's standard upgrade actions (`FindRelatedProducts` and `RemoveExistingProducts`). WiX's signed `WixUIValidatePath` check still validates the folder. |
| Safety | If `JpCaDll` backs any other action (for example after a JDK update), the script stops with an error and leaves the MSI unchanged. Running it twice is harmless. |
| Not changed | Smart App Control stays on. The installer and app are still unsigned, so SmartScreen can still warn on other PCs. Trusted signing remains the release step for other people's PCs. |

### H0b. "Smart App Control blocked an app that may be unsafe" when opening the app

| Item | Detail |
| --- | --- |
| Symptom | The MSI installs, but opening AutoPilot shows *"Smart App Control blocked an app that may be unsafe"*. Code Integrity event 3077 names `C:\Program Files\AutoPilot\AutoPilot.exe`. |
| Cause | jpackage generates a new, unsigned `AutoPilot.exe` launcher for every build. Smart App Control has never seen that file and has no reputation for it, so it blocks it. The bundled runtime's DLLs and `skiko-windows-x64.dll` are also unsigned, but they're identical to files shipped in many other apps, and Smart App Control allows them. |
| Fix | Gradle copies the packaging JDK's `javaw.exe`, signed by Microsoft, into the runtime image (`runtime\bin\javaw.exe`). After each MSI build, `private/scripts/windows/Use-SignedJavaLauncher.ps1` points the desktop and Start menu shortcuts at that `javaw.exe`, with the same main class and JVM options the generated launcher used, and the AutoPilot icon. |
| Trade-offs | Task Manager shows the process as *OpenJDK Platform binary* (`javaw.exe`). `C:\Program Files\AutoPilot\AutoPilot.exe` is still installed but blocked, so always open the app from its shortcut. A shortcut you create yourself by right-clicking `AutoPilot.exe` won't work. |
| Safety | The script stops, leaving the MSI unchanged, if `javaw.exe` isn't validly signed, if the MSI doesn't have exactly one `javaw.exe`, or if a shortcut points anywhere unexpected. Running it twice is harmless. |

To check an installed copy:

```powershell
(Get-AuthenticodeSignature 'C:\Program Files\AutoPilot\runtime\bin\javaw.exe').Status
(New-Object -ComObject WScript.Shell).CreateShortcut("$env:PUBLIC\Desktop\AutoPilot.lnk").TargetPath
```

Expected: `Valid`, then `C:\Program Files\AutoPilot\runtime\bin\javaw.exe`.

Full evidence: [Windows installer diagnosis](14_WINDOWS_INSTALLER_DIAGNOSIS.md).

### H1. Push the code from the Mac

**Mac Terminal**, in `/Users/jagdeep/Projects/AndroidStudio/AutoPilot`:

```sh
git status --short
git push origin main
```

Legion builds only pushed commits.

### H2. Connect and update Legion

**Mac Terminal:**

```sh
ssh home-legion
```

**Windows SSH PowerShell:**

```powershell
Set-Location C:\Users\jagde\Projects\autopilot-cmp
git status --short
```

If this lists files, stop. Find out where the edits came from before going on. If they're the same as a commit already pushed, stash them as a backup instead of deleting them:

```powershell
git stash push -u -m 'backup before pull'
```

Then update:

```powershell
git pull --ff-only
git log -1 --oneline
```

The log must show `b0faba5` or a later commit.

### H3. Build the MSI

**Windows SSH PowerShell**, in the checkout:

```powershell
.\gradlew.bat --no-daemon --console=plain --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process' :composeApp:packageReleaseMsi
```

Wait for **BUILD SUCCESSFUL**. With a warm cache this takes about a minute. Removing the helper happens inside this task, so there's no extra step. For a release check with tests, use `.\private\scripts\windows\Build-Distribution.ps1` (C5) instead; it runs the same packaging task.

### H4. Check that the helper is gone

**Windows SSH PowerShell:**

```powershell
$m = (Get-ChildItem .\composeApp\build\compose\binaries\main-release\msi\*.msi).FullName
$db = (New-Object -ComObject WindowsInstaller.Installer).OpenDatabase($m, 0)
$v = $db.OpenView('SELECT `Name` FROM `Binary` WHERE `Name`=''JpCaDll'''); [void]$v.Execute()
if ($v.Fetch()) { 'NOT FIXED: JpCaDll still present' } else { 'OK: JpCaDll removed' }
[void]$v.Close(); $v = $null; $db = $null; [GC]::Collect()
Get-FileHash $m
```

Expected: `OK: JpCaDll removed`, followed by the SHA256. The H3 build output also shows `Removed JpCaDll …` and `Pointed 2 shortcuts at the signed runtime\bin\javaw.exe …`. If it says **NOT FIXED**, don't install. Check that the build ran from commit `b0faba5` or later. If it did, the helper removal failed during the build, so look in H3's output for its error.

To patch an MSI built before the fix, without rebuilding:

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\private\scripts\windows\Remove-UnsignedMsiHelper.ps1 -Path .\composeApp\build\compose\binaries\main-release\msi
```

### H5. Updating an installed copy

Since commit `c11518c`, each commit builds a higher MSI version: `<major>.<minor>.<commit count>`, for example `1.0.22`. The major and minor parts come from `versionName` in `gradle/libs.versions.toml`. Running a newer MSI (H6) **upgrades the installed app in place**: the normal installer dialogs appear, the old version is removed and the new one installed. You don't need to uninstall.

What an update keeps and what it changes:

| Data | On update |
| --- | --- |
| `%USERPROFILE%\.autopilot` (settings, routines, history, course database, logs) | Kept. The installer never touches this folder. |
| Answer banks in `%USERPROFILE%\.autopilot\answer-banks` | Kept. A bank the app ships is added only if no file of that name exists, so new bundled banks appear and your saved answers stay. |
| Chrome extension in `%USERPROFILE%\.autopilot\extensions\chrome` | Replaced with the new version's files at the app's next start. Press reload on the extension card in `chrome://extensions`. |
| Window size and position | Kept (stored in the user registry). |
| `C:\Program Files\AutoPilot\` | Replaced with the new version. |

Close AutoPilot before updating, so the installer doesn't have to ask about files in use.

To see the installed and new versions:

```powershell
(Get-ItemProperty 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*' | Where-Object DisplayName -eq 'AutoPilot').DisplayVersion
Get-ChildItem C:\Users\jagde\Projects\autopilot-cmp\composeApp\build\compose\binaries\main-release\msi\*.msi | Select-Object Name
```

**When you still need to uninstall:** the installer opens repair/remove instead of updating when the new MSI has the **same** version as the installed one. That happens when you rebuild the same commit, or when the installed copy was built from local uncommitted changes. Commit, or uninstall first. Uninstalling also leaves `.autopilot` alone:

```powershell
$app = Get-ItemProperty 'HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*' | Where-Object DisplayName -eq 'AutoPilot'
if ($app) { msiexec.exe /x $app.PSChildName /norestart }
```

An older MSI installed over a newer one doesn't downgrade cleanly; uninstall first if you need to go back.

### H6. Install

**Option A — Legion desktop, with installer dialogs.** In PowerShell as Administrator:

```powershell
$msi = (Get-ChildItem C:\Users\jagde\Projects\autopilot-cmp\composeApp\build\compose\binaries\main-release\msi\*.msi | Sort-Object LastWriteTime | Select-Object -Last 1).FullName
$log = "$env:TEMP\AutoPilot-install.log"
msiexec.exe /i "$msi" /L*V "$log" /norestart
```

**Option B — silently, from the Windows SSH PowerShell:**

```powershell
$msi = (Get-ChildItem C:\Users\jagde\Projects\autopilot-cmp\composeApp\build\compose\binaries\main-release\msi\*.msi | Sort-Object LastWriteTime | Select-Object -Last 1).FullName
$log = "$env:TEMP\AutoPilot-install.log"
$start = Get-Date
$p = Start-Process msiexec.exe -ArgumentList "/i `"$msi`" /qn /norestart /L*V `"$log`"" -Wait -PassThru
"install exit: $($p.ExitCode)"
Select-String -Path $log -Pattern 'error status' | % { $_.Line.Trim() }
```

Expected: `install exit: 0` and `Installation success or error status: 0`. The app goes to `C:\Program Files\AutoPilot\`, with desktop and Start menu shortcuts.

### H7. Check for Smart App Control blocks

**Same window as H6:**

```powershell
Get-WinEvent -LogName 'Microsoft-Windows-CodeIntegrity/Operational' -MaxEvents 50 | Where-Object { $_.TimeCreated -gt $start -and $_.Id -in 3077,3033,3089 } | Format-List TimeCreated,Id,Message
```

No output means nothing was blocked. If you used Option A, first run `$start = (Get-Date).AddMinutes(-15)`.

To check whether Smart App Control is on (1 = on, 2 = evaluation, 0 = off):

```powershell
(Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Control\CI\Policy').VerifiedAndReputablePolicyState
```

### H8. Test the app

On the **Legion desktop**, open **AutoPilot** from Start and check the window, settings, and extension connection (E5).

A quick launch check over SSH (no visible window):

```powershell
$a = Start-Process 'C:\Program Files\AutoPilot\AutoPilot.exe' -PassThru
Start-Sleep 15; $a.Refresh(); "running after 15s: $(-not $a.HasExited)"
if (-not $a.HasExited) { Stop-Process -Id $a.Id }
```

Close that test copy before using the desktop app; two copies share the same data folder.

### H9. If it still fails

```powershell
Select-String -Path "$env:TEMP\AutoPilot-install.log" -Pattern 'Error 17','Return value 3' -Context 12,6
Get-WinEvent -FilterHashtable @{LogName='Application'; ProviderName='MsiInstaller'; StartTime=(Get-Date).AddHours(-1)} | Format-List TimeCreated,Id,Message
```

- **Error 1723 naming a `Jp…` action:** the MSI still has the helper. Repeat H4.
- **Error 1723 naming another action:** a different unsigned DLL is involved. Check the H7 events to find the file. Don't turn off Smart App Control.
- **"Already installed", or a repair/remove dialog instead of an update:** the new MSI has the same version as the installed copy. See H5.

### H10. Using the fix in another Compose app

Copy `private/scripts/windows/Remove-UnsignedMsiHelper.ps1` and `private/scripts/windows/Use-SignedJavaLauncher.ps1` into the other repository. Then add the Smart App Control block from AutoPilot's `composeApp/build.gradle.kts`: the `AbstractJLinkTask` and `AbstractJPackageTask` hooks, plus their imports from `org.jetbrains.compose.desktop.application.tasks`. Build and check with H3–H4 in that app's own folder.

### H11. Recorded result — 10 November 2026

| Check | Result |
| --- | --- |
| Commit built on Legion | `b0faba5` |
| `packageReleaseMsi` | BUILD SUCCESSFUL in 46 s |
| `JpCaDll` in the MSI | Removed |
| MSI SHA256 | `5BC92E2F5CD00F9FE6696115D22399239659B76217B90C757A387570BF9F71DF` |
| Uninstall of the earlier test copy | Exit 0 |
| Install (silent, Smart App Control on) | Exit 0, status 0 |
| Shortcuts | Desktop and Start menu created |
| Launch check | Still running after 15 s, 0 Code Integrity blocks |
| Desktop GUI check | Not done yet |
| Legion's earlier local edits | Same as commit `428cd8b`; saved as `stash@{0}` ("backup before pull: same as 428cd8b"). Delete with `git stash drop` once you no longer need it. |

---
