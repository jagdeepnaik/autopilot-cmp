# 16 — AutoPilot Windows Build and Debug Reference

[All guides](00_INDEX.md)

# AutoPilot Build and Debug Reference — Recorded Evidence

These sections retain the original AutoPilot commands and evidence, including their original section numbers. The corresponding SSH principles and initial setup are in the [setup guide](00_INDEX.md).

## 12. Building Windows distribution packages

Windows packaging runs on Windows. The Mac can orchestrate it through SSH; it does not turn macOS into a Windows packaging environment.

The laptop has Git and a full JDK 21. The repository was cloned into the Windows checkout, and changes were synchronized only after checking for local edits. Use a matching revision on both computers; prefer a fast-forward pull rather than overwriting an unreviewed dirty checkout.

### Commands used successfully

**Windows — PowerShell in the checkout, locally or through section 11:**

```powershell
Set-Location (Join-Path $env:USERPROFILE 'Projects\autopilot-cmp')
.\gradlew.bat --no-daemon --console=plain `
  :core:automation:jvmTest `
  :feature:home:jvmTest --tests '*DgsStudy*' `
  :composeApp:jvmTest --tests '*DesktopDataPathsTest*' `
  :composeApp:packageReleaseMsi
if ($LASTEXITCODE -ne 0) { throw 'Tests or packaging failed.' }
```

The reusable helper is [Build-Distribution.ps1](../../tools/windows/Build-Distribution.ps1). It runs focused tests, checks failures, builds the MSI, and reports its location and hash. Its optional `-SkipTests` switch is not release-validation evidence.

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned `
  -File .\tools\windows\Build-Distribution.ps1
```

### The JDK problem we fixed

Gradle daemon criteria originally required vendor AZUL even though the laptop already had a compatible Microsoft JDK 21. Gradle attempted an Azul download that did not progress. We removed only the vendor restriction, retaining Java 21. This was committed as `3775d91` and verified by the successful build. Daemon criteria can take precedence over a simple `JAVA_HOME` selection; see [Gradle daemon JVM criteria](https://docs.gradle.org/current/userguide/gradle_daemon.html).

### Distribution result

The release MSI includes a Java runtime, so recipients do not need a separate Java installation. Native packages must be produced for the target platform. See [Compose native distributions](https://kotlinlang.org/docs/multiplatform/compose-native-distribution.html).

The laptop produced:

```text
C:\Users\jagde\Projects\autopilot-cmp\composeApp\build\compose\binaries\main-release\msi\AutoPilot-1.0.0.msi
SHA256: 39047816A2EAFD63DCBD30EAE2B3A800B3532EE5801E8584BD4B883AE35FD2F9
```

A separate successful Windows CI run produced the Mac download:

```text
composeApp/build/windows-distribution/AutoPilot-1.0.0.msi
SHA256: 24618df745a798bed5719c4f56370b95b090a958764db4b32ce0b2efede8555f
```

These are different builds; different hashes do not establish corruption between unrelated builds. Compare the hash of the **same artifact** before and after copying. A hash verifies integrity, not publisher identity. Both installers are unsigned; Windows signing and a public release were not configured.

The attempted laptop-to-Mac MSI copy was interrupted and its partial local file was removed. The CI download is the complete Mac-side artifact. Do not distribute a partial transfer.


## 15. How JVM remote debugging works

AutoPilot Desktop is a JVM application. The JVM supports the Java Debug Wire Protocol, or JDWP. A debugger uses that protocol to set breakpoints, suspend/resume threads, inspect stack frames and variables, and evaluate expressions.

The existing Gradle JavaExec run task supports `--debug-jvm`. This starts the **application JVM** in debug mode, normally suspended and listening on port 5005 until a debugger attaches. It does not require a custom debugging server written for AutoPilot. See [Gradle JavaExec](https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/JavaExec.html).

The equivalent conceptual JVM agent options are:

```text
-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:5005
```

This illustrates the mechanism; the project script uses Gradle's option rather than manually adding this string.

- `dt_socket`: debugger communication over a socket.
- `server=y`: the JVM waits for a debugger connection.
- `suspend=y`: pause application startup until attachment/resume.
- `address=127.0.0.1:5005`: local-only endpoint.

The live Windows listener was verified as `127.0.0.1:5005`. Do not change it to `*:5005` for convenience. JDWP gives powerful control over the process and should not be exposed on the LAN. See [Oracle JVM debugging connection options](https://docs.oracle.com/en/java/javase/21/docs/specs/jpda/conninv.html).

`-Dorg.gradle.debug=true` would debug Gradle's own JVM, a different target. The distributed release MSI does not include the development run's enabled debug agent. The JVM debugger is also separate from Chrome's debugger permission and the extension bridge on port 47613.


## 16. How the SSH debugger tunnel works

```text
Android Studio / jdb on Mac
         |
         | connects to Mac 127.0.0.1:5005
         v
Mac SSH local forwarding listener
         |
         | encrypted SSH transport over LAN TCP 22
         v
Windows sshd
         |
         | connects locally to Windows 127.0.0.1:5005
         v
AutoPilot JVM debug agent
```

The two `127.0.0.1` addresses refer to different computers. The first is the Mac's local endpoint. The destination is reached from Windows. Only the encrypted SSH connection crosses Wi-Fi; no Windows inbound firewall opening for 5005 is needed.

### Add a forward to the existing authenticated master

**Mac — Terminal; command used in the test:**

```sh
ssh -S /tmp/autopilot-windows-ssh \
  -o BatchMode=yes \
  -o StrictHostKeyChecking=yes \
  -o UserKnownHostsFile=/tmp/autopilot-windows-hostkey \
  -O forward \
  -L 127.0.0.1:5005:127.0.0.1:5005 \
  jagde@192.168.1.6
```

### Alternative: start a dedicated tunnel

Using the configured SSH alias, a fresh dedicated tunnel is:

```sh
ssh -N -o ExitOnForwardFailure=yes \
  -L 127.0.0.1:5005:127.0.0.1:5005 \
  home-legion
```

Use one method, not both on the same local port. `ExitOnForwardFailure` catches an initial bind failure; it does not promise that the remote app will remain available. Leave the dedicated tunnel terminal open. Do not forward the app's Chrome bridge just to attach the JVM debugger.


## 17. Attaching Android Studio or jdb

### Android Studio on the Mac

1. Open the AutoPilot project at the same Git revision as Windows.
2. Start the Windows `AutoPilot-Debug` scheduled task.
3. Establish the SSH forward from section 16.
4. Select **AutoPilot Windows (SSH tunnel)** in the run configurations.
5. Click **Debug**.
6. Set a breakpoint in the shared/JVM code and reproduce the behavior on Windows.
7. Resume paused threads before detaching when you want the app to continue.

The stored configuration uses remote JVM attach mode, socket transport, host `127.0.0.1`, port `5005`, and module `AutoPilot.composeApp.jvmMain`. It connects to the tunnel, not directly to the laptop's LAN address. See [JetBrains remote debugging](https://www.jetbrains.com/help/idea/tutorial-remote-debug.html).

Source files remain in both checkouts. SSH does not synchronize edits or automatically hot-reload them. Commit/pull the intended changes and restart the debug app when required. Kotlin-generated wrappers and optimized code can affect breakpoint placement. The configuration was created; the attachment actually demonstrated in this session used `jdb`, not the Android Studio UI.

### The actual command-line debugger test

**Mac — Terminal with a JDK providing jdb:**

```sh
jdb -attach 127.0.0.1:5005
```

**Inside jdb, not a shell:**

```text
stop in com.jagdeep.autopilot.MainKt.main(java.lang.String[])
cont
stop in com.jagdeep.autopilot.MainKt.main()
cont
where
clear com.jagdeep.autopilot.MainKt.main()
clear com.jagdeep.autopilot.MainKt.main(java.lang.String[])
cont
exit
```

The wrapper breakpoint was followed by the actual Kotlin entry point at `main.kt:33`. `where` showed the stack with `MainKt.main`. We cleared the breakpoints, resumed, and detached; the Windows app continued responding. Mac jdb 27 successfully attached to Windows JVM 21 in this test, although using compatible JDK tooling remains preferable.

Only one debugger should attach at a time. Disconnect jdb before attaching Android Studio. A breakpoint can pause the entire JVM, including extension communication and timers. Do not stop a live timed assessment at a breakpoint.


## 18. What was tested successfully

| Check | Evidence and limits |
| --- | --- |
| Server identity | Windows and Mac Ed25519 fingerprints matched |
| Remote authentication | Initially password + shared master; subsequently fresh protected-key-only login verified with password fallback disabled |
| Remote PowerShell | Encoded scripts ran on Windows through SSH |
| JDK compatibility | Microsoft JDK 21 built successfully after vendor restriction fix |
| Focused tests and MSI | Laptop build succeeded in about 2m55s, 80 tasks |
| Windows CI | Runs `42754615264` and `42755035632` succeeded |
| Interactive GUI | Scheduled task launched app; user confirmed visible window |
| Debugger transport | Windows loopback 5005, Mac loopback forward, successful jdb attachment |
| Source debugging | Breakpoint reached Kotlin `main.kt:33`, stack inspected, app resumed |
| Subsequent UI validation | 11 focused app-shell/caption tests passed on both Mac and Windows; updated Windows JVM relaunched and local extension bridge verified |
| Local extension bridge | Windows loopback port 47613 was listening after resume |

The tests covered core automation tests, selected DGS persistence/database tests, and desktop data-path tests. They were not the entire project test suite or a complete Windows browser/course end-to-end acceptance run. No private question bank or personal database was copied to Windows during this setup.

The historical Windows app PID was `20288`, in session 1; the Mac SSH listener PID was `41136`. PIDs change on restart and must not be reused as future stop targets.

[Successful CI run](https://github.com/jagdeepnaik/autopilot-cmp/actions/runs/42755035632). Relevant commits: `ebb0e5b` added helpers/configuration, `3775d91` fixed JDK selection, and `9a4a180` scoped RemoteSigned to the debug process.


## 19. Logs, installers, and project files

| Location | Purpose |
| --- | --- |
| `tools/windows/Build-Distribution.ps1` | Focused tests and Windows MSI build |
| `tools/windows/Register-DebugLauncher.ps1` | Register/remove interactive debug task |
| `tools/windows/Start-Debug.ps1` | Debug run and PowerShell transcript |
| `.run/AutoPilot-Windows-SSH.run.xml` | Mac IDE attach configuration |
| `.github/workflows/desktop-packages.yml` | Manually requested platform packaging CI |
| Windows `%USERPROFILE%\.autopilot\logs\debug-*.log` | Debug-launch transcripts |
| Windows `composeApp\build\compose\binaries\main-release\msi\` | Laptop-generated MSI |
| Mac `composeApp/build/windows-distribution/` | Downloaded CI MSI |
| Mac `/tmp/autopilot-windows-hostkey` | Current temporary verified server public key |
| Mac `/tmp/autopilot-windows-ssh` | Current temporary connection-sharing socket |
| `private/docs/20_WINDOWS_TASKS_QUICK_LINKS.md` | Build, copy, install, and debugging workflow |
| `private/docs/21_INITIAL_SETUP_QUICK_LINKS.md` | One-time SSH and machine setup |

For a local file hash, use `Get-FileHash -Algorithm SHA256 <path>` on Windows or `shasum -a 256 <path>` on the Mac. Do not infer that a file is signed merely because a hash was printed.


## 20. Daily workflow

### Build a distribution

1. Connect both laptops to the trusted private network; keep them awake.
2. Check the Windows IP and SSH service if anything changed.
3. Use `ssh home-legion whoami` to verify key-based access; the old password master is no longer required.
4. Check both Git revisions and local edits; synchronize deliberately.
5. Run `Build-Distribution.ps1` through the process-scoped command.
6. Require successful tests and packaging; inspect the installer path and hash.
7. Transfer the completed artifact and compare its hash, or download the successful CI artifact.
8. Test the installer on Windows before distributing it. Signing is a separate release step.

### Debug a Windows issue from the Mac

1. Sign in to the Windows desktop.
2. Ensure both checkouts have matching source.
3. Register the debug task once, or again after moving the checkout.
4. Start `AutoPilot-Debug` through SSH.
5. Add the local 5005 forward.
6. Attach the Mac debugger and resume startup.
7. Reproduce the issue on the Windows laptop and inspect the paused code.
8. Resume and detach cleanly; close the development app normally when finished.

Do not launch another debug instance if an earlier one is still listening. Routine data belongs to the Windows user profile used by the task; it is not automatically the Mac's data.


## 21. Troubleshooting

| Symptom | Checks and response |
| --- | --- |
| SSH timeout | Check laptop awake, current IP, same network, Private profile, firewall rule, and `sshd` service |
| Connection refused | Confirm `sshd` is running and listening; this differs from a firewall timeout |
| Password rejected | Use Windows account password, not Hello PIN; confirm `jagde` account |
| Public-key login rejected | Check username, selected key, correct ordinary/admin file, ACLs, and agent; test a fresh connection |
| No password prompt in an automated command | Expected with BatchMode; reconnect interactively if no master/key is available |
| Host-key warning | Verify directly on the laptop; do not disable checking or blindly replace trust |
| Control socket exists but commands fail | Run `-O check`; a path alone does not prove a live connection. Remove only a confirmed stale socket |
| `ssh-keygen` appears stuck | Use the explicit Windows executable path shown in section 7 |
| PowerShell script execution blocked | Use the approved process-only RemoteSigned invocation; inspect `Get-ExecutionPolicy -List` for overriding policy |
| Encoded command looks unreadable | Expected Base64 transport; decode locally for inspection, never treat it as secret storage |
| CLIXML output | Check actual error/exit status; formatting alone does not prove failure |
| Gradle attempts an Azul download | Check revision includes the vendor-criteria fix; verify full JDK 21 installation |
| Debug app window absent | JVM may be suspended pending attach; also check signed-in desktop, task principal, and task log |
| Debug task will not launch another instance | `IgnoreNew` may be protecting an existing run; inspect its state |
| Debug session ends after hours | Launcher has a four-hour execution cap; battery settings do not prevent sleep |
| Cannot bind Mac port 5005 | Existing tunnel/other process may own it; inspect before adding a duplicate |
| Debugger attaches but source lines disagree | Align Git revisions and rebuild; check Kotlin wrapper versus real method |
| Course communication freezes at breakpoint | Suspending the JVM also pauses its bridge; resume execution |
| Installer copy much smaller than source | Interrupted transfer; discard partial artifact and copy again with hash verification |

**Windows — useful inspection commands:**

```powershell
Get-Service sshd
Get-ScheduledTask -TaskName 'AutoPilot-Debug' |
  Select-Object TaskName, State
Get-ScheduledTaskInfo -TaskName 'AutoPilot-Debug'
Get-NetTCPConnection -State Listen |
  Where-Object { $_.LocalPort -in 22, 5005, 47613 } |
  Select-Object LocalAddress, LocalPort, OwningProcess
Get-CimInstance Win32_ComputerSystem | Select-Object UserName
Get-ExecutionPolicy -List
```

If a shared SSH connection fails or hangs while the laptop is reachable, bypass it to distinguish connection-sharing trouble from authentication:

```sh
ssh -o ControlPath=none -o ControlMaster=no -o BatchMode=yes autopilot-windows whoami
```

This succeeded during the follow-up test after the previous master broke. Do not regenerate keys simply because a control socket has failed.

Task result `267009` (`0x41301`) can indicate a task is currently running rather than a completed failure. Inspect state and logs together. Some Windows Home installations lack `quser`; do not depend on it for this workflow.

**Mac — inspect the forward:**

```sh
lsof -nP -iTCP:5005 -sTCP:LISTEN
ssh -S /tmp/autopilot-windows-ssh -O check jagde@192.168.1.6
```

Do not terminate every Java process to clear a port. Identify the owning process and close the intended app normally where possible.


## 22. Revoking access and cleaning up

### Close only the debugger forward

**Mac — existing master:**

```sh
ssh -S /tmp/autopilot-windows-ssh -O cancel \
  -L 127.0.0.1:5005:127.0.0.1:5005 jagde@192.168.1.6
```

For a dedicated foreground tunnel, use Control-C in its terminal. Ensure no active debugging operation depends on it.

### Close the shared SSH connection

```sh
ssh -S /tmp/autopilot-windows-ssh -O exit jagde@192.168.1.6
```

This can disrupt other commands using the master. Finish builds and transfers first.

### Remove the debug launcher

**Windows — PowerShell in the checkout:**

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned `
  -File .\tools\windows\Register-DebugLauncher.ps1 -Remove
```

Close the app normally first. Force-stopping a task/process can lose the last unsaved checkpoint; removing the registration is not a substitute for orderly shutdown.

### Revoke the dedicated user key

Remove only that public-key line from the applicable Windows authorization file, preserving other keys. Close existing authenticated connections too: removing a key prevents future authentication but does not necessarily terminate already-authenticated sessions.

On the Mac, `ssh-add -d ~/.ssh/autopilot_windows_ed25519` removes that identity from the agent. Review its Keychain entry separately if you want to remove the saved passphrase. Delete private key files only after deciding on backup and recovery needs. Never delete all of `~/.ssh` as cleanup.

If the server is no longer needed, an administrator can stop `sshd` and change its startup behavior after checking that no other workflow depends on it. Removing a known-hosts entry changes client trust; it does not revoke a user key on Windows.
