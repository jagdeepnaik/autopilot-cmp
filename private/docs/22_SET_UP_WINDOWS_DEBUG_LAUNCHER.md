# 22 — Set Up the AutoPilot Windows Debug Launcher

[All guides](00_INDEX.md) · [Use the debugger](04_DEBUG_WINDOWS_FROM_MAC.md)

## 13. Why a Windows GUI needs an interactive desktop

A process launched directly by the SSH service may run outside the visible signed-in desktop. Successful command execution therefore does not prove that a GUI is visible.

We solved this using an on-demand scheduled task tied to the logged-in Windows account. It launches the debug script in that user's interactive session, with limited privileges and without storing an account password. The user must already be signed in. This is different from a background task configured to run whether or not a user is logged on. See [scheduled-task principals](https://learn.microsoft.com/en-us/powershell/module/scheduledtasks/new-scheduledtaskprincipal?view=win10-ps).

Windows Home does not supply the built-in Remote Desktop host used by Pro editions. No alternative remote screen-sharing tool was installed in this work. The scheduled task solves launching, not screen viewing. The course workflow can additionally require Chrome to be unlocked and in the foreground; an interactive task does not bypass that requirement.


## 14. How the debug launcher was created

Two small Windows scripts and one IDE configuration were added:

- [Register-DebugLauncher.ps1](../../private/scripts/windows/Register-DebugLauncher.ps1) registers or removes the task.
- [Start-Debug.ps1](../../private/scripts/windows/Start-Debug.ps1) runs the app with JVM debugging enabled and creates a transcript.
- [AutoPilot-Windows-SSH.run.xml](../../.run/AutoPilot-Windows-SSH.run.xml) tells Android Studio where to attach.

### Registration

**Windows — PowerShell in the checkout, also callable over SSH:**

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned `
  -File .\private\scripts\windows\Register-DebugLauncher.ps1
Start-ScheduledTask -TaskName 'AutoPilot-Debug'
```

The registration script derives the checkout path from its own location and uses the current Windows account. Its important settings are:

| Setting | Reason |
| --- | --- |
| Task name `AutoPilot-Debug` | A known, project-specific on-demand launcher |
| `LogonType Interactive` | Uses the signed-in user's desktop |
| `RunLevel Limited` | Avoids unnecessarily elevated app execution |
| No automatic trigger | Starts only when requested |
| `MultipleInstances IgnoreNew` | Avoids duplicate launches through this task |
| Four-hour execution limit | Prevents this development task running indefinitely |
| Battery execution allowed | Does not stop merely because mains power is disconnected |

Battery settings do not prevent sleep. The four-hour cap is a debug-launcher limit, not a course-duration feature. Re-register after moving the Windows checkout because the task stores the script's resolved path.

The task action invokes `powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File ...`. That exception is scoped to the task's PowerShell process, consistent with the user's approval.

### App launch and log

The debug script resolves the project root, creates `%USERPROFILE%\.autopilot\logs`, starts a timestamped PowerShell transcript, then runs:

```powershell
.\gradlew.bat --no-daemon --console=plain :composeApp:run --debug-jvm
```

It checks Gradle's exit code and stops the transcript in cleanup. A transcript records the PowerShell session; native-process output coverage can vary, so it is not guaranteed to contain every Gradle/compiler line. The script's “Waiting for the Mac debugger” message explains why the app may not be visible yet.
