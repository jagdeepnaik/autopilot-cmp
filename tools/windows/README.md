# Windows distribution and debugging from a Mac

AutoPilot's Windows MSI must be built on Windows. GitHub Actions can produce it without a laptop; the laptop is for testing the installed app, Chrome integration and Windows-specific behavior. Windows x64 is the current distribution target. MSI bundles a Java runtime; recipients do not need a separate JDK. The current package is unsigned: do not present it as code-signed or bypass Windows security warnings. Distribution signing requires a separately configured trusted Windows code-signing certificate.

## Prepare the laptop once

1. Install the full **JDK 21**, **Git**, and **Google Chrome** from their official sources. Reopen PowerShell and check `java -version` and `git --version`. Gradle and its toolchains are downloaded by the wrapper. No Android Studio/Android SDK is needed for the desktop build.
2. In the Windows user's ordinary PowerShell:

   ```powershell
   New-Item -ItemType Directory -Force "$env:USERPROFILE\Projects" | Out-Null
   Set-Location "$env:USERPROFILE\Projects"
   git clone https://github.com/jagdeepnaik/autopilot-cmp.git
   Set-Location autopilot-cmp
   powershell.exe -NoProfile -File .\tools\windows\Build-Distribution.ps1
   ```

   If PowerShell blocks locally reviewed scripts, use `Set-ExecutionPolicy -Scope Process RemoteSigned` in this window only. Do not disable machine-wide script policies. Do not use this to override an organization-managed policy. The build script runs native-automation unit tests, DGS persistence tests, folder migration tests, and creates an MSI plus its SHA-256 output. Full UI and live browser tests are separate.
3. Enable **OpenSSH Server** through Windows Settings → Optional features. Start the **OpenSSH SSH Server** service and set its startup type to Automatic. Windows may require administrator approval. Limit its firewall rule to your Mac's private-network address or subnet. Do not port-forward SSH or the debugger from your router. Use the same Windows account that will run AutoPilot. See [Microsoft's instructions](https://learn.microsoft.com/en-us/windows-server/administration/openssh/openssh_install_firstuse).
4. On Windows, note the output of `whoami`, `hostname` and `ipconfig`. On the Mac, test `ssh WINDOWS_USER@WINDOWS_LAN_IP`. Verify the host-key fingerprint on the Windows laptop before accepting the new host. Never send passwords/private keys in chat. SSH-key authentication can be set up afterwards.

## Build remotely from the Mac

Commit and push the intended code first. In the Windows checkout use `git pull --ff-only`; do not overwrite uncommitted laptop changes. Both machines must use the same commit for reliable breakpoints.

Example (replace the username, IP and path):

```bash
ssh WINDOWS_USER@WINDOWS_LAN_IP 'powershell.exe -NoProfile -File "C:\Users\WINDOWS_USER\Projects\autopilot-cmp\tools\windows\Build-Distribution.ps1"'
```

MSI output: `composeApp/build/compose/binaries/main-release/msi/`. Copy the exact produced MSI back with `scp`, or download the Windows artifact from GitHub Actions → Desktop packages. Building over SSH is supported; launching a GUI directly inside an SSH service session is not the desktop testing path.

## Debug the Windows GUI from Android Studio on the Mac

1. On the signed-in Windows desktop, close any installed/running AutoPilot copy to avoid port and database conflicts. Keep Chrome and AutoPilot in the same user session. Run once:

   ```powershell
   powershell.exe -NoProfile -File .\tools\windows\Register-DebugLauncher.ps1
   ```

   This registers an on-demand, non-elevated task for the interactive user. No password, automatic trigger, SSH setting or firewall rule is created. Re-register it if the checkout moves. If Windows restricts task registration, run `Start-Debug.ps1` manually in the desktop PowerShell instead.
2. From the Mac, start the task:

   ```bash
   ssh WINDOWS_USER@WINDOWS_LAN_IP 'powershell.exe -NoProfile -Command "Start-ScheduledTask -TaskName AutoPilot-Debug"'
   ```

   Windows must have this user signed in. Chrome automation additionally needs the desktop unlocked and Chrome in front. The task starts a suspended JVM; the app window appears after the debugger attaches. Debug logs are under `%USERPROFILE%\.autopilot\logs\`.
3. In a separate Mac terminal, keep this tunnel running:

   ```bash
   ssh -o ExitOnForwardFailure=yes -N -L 127.0.0.1:5005:127.0.0.1:5005 WINDOWS_USER@WINDOWS_LAN_IP
   ```

   Gradle's `--debug-jvm` uses localhost port 5005. Do not expose port 5005 or use a wildcard debugger address. See [Gradle's JavaExec documentation](https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/JavaExec.html).
4. Open this project in Android Studio on the Mac. Choose **AutoPilot Windows (SSH tunnel)** from the checked-in `.run` configuration and click Debug. If the IDE does not import it, add a Remote JVM Debug configuration with Attach, socket transport, `127.0.0.1`, port `5005`, and the composeApp JVM module classpath. Set Kotlin breakpoints and inspect the Windows process. See [JetBrains' remote-debug guide](https://www.jetbrains.com/help/idea/tutorial-remote-debug.html).
5. Use a Windows desktop-sharing client to see and interact with the app if needed; debugger attachment alone does not share the Windows screen. A whole-JVM breakpoint also stops extension polling and can pause the routine; expect to resume/restart it after stepping. Do not test timed assessments while stopping at breakpoints.
6. Close AutoPilot normally to flush progress. End the SSH tunnel when finished. Remove the optional launcher with `Register-DebugLauncher.ps1 -Remove`. Avoid stopping every Java process: other apps may use Java.

## First Windows acceptance check

Install the MSI in the signed-in desktop. Start it and check the window, saved settings, sound and display-awake controls. Install its extension from `%USERPROFILE%\.autopilot\extensions\chrome` and verify Chrome connected. Test a short ordinary course session, Stop, restart, and confirm cumulative progress. This Mac's private question banks and course database are not in Git: copy only the intended answer-bank file through your private connection if needed. Keep Windows and Mac databases separate during live testing.
