# 12 — Build AutoPilot in the Server Windows VM

[All guides](00_INDEX.md) · [Preferred: build on Legion](02_BUILD_WINDOWS_ON_LEGION.md) · [Copy server installers](10_COPY_SERVER_INSTALLERS.md)

Alternative to Legion. Run Windows commands only in the VM PowerShell window. If the VM is stopped, [start it first](11_START_STOP_WINDOWS_BUILD_VM.md).

### 1.2 Connect to Windows

Open a **new Mac Terminal window**. Choose one:

**Local network:**

```sh
ssh home-server-build
```

**Or Cloudflare:**

```sh
ssh home-server-build-remote
```

Expected: a Windows PowerShell prompt starting with `PS C:\Users\builduser>`. If it opens Command Prompt instead, run this once in that window:

```text
powershell.exe -NoProfile
```

All remaining commands in section 1 run in this **Windows SSH window**.

### 1.3 Enter the app folder

```powershell
Set-Location C:\Build\autopilot-cmp
```

### 1.4 Check for local source changes

```powershell
 git status --short
```

No output means clean. If files are listed, stop before pulling; do not delete/reset them.

### 1.5 Download committed updates, only if the checkout is clean

```powershell
 git pull --ff-only
```

Wait for success. This fetches committed GitHub changes; it does not include uncommitted or unpushed edits from the Mac.

### 1.6 Build the MSI

**Required prompt: `PS C:\Build\autopilot-cmp>` — Windows PowerShell.** Use `gradlew.bat` and `packageReleaseMsi` here. Do not use `nice`, `ionice`, or `packageReleaseDeb` in this window.

Paste this **one complete line** and press Enter:

```powershell
.\gradlew.bat --no-daemon --console=plain --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process' :desktopApp:packageReleaseMsi
```

Wait until it says **BUILD SUCCESSFUL**. The first build can take several minutes while downloading tools/dependencies. Later builds reuse caches. Do not start another build in the meantime. If it says **BUILD FAILED**, preserve the error; do not distribute an old installer just because one exists in the folder.

### 1.7 Check the generated installer

```powershell
Get-ChildItem C:\Build\autopilot-cmp\desktopApp\build\compose\binaries\main-release\msi\*.msi
```

Then, separately:

```powershell
Get-ChildItem C:\Build\autopilot-cmp\desktopApp\build\compose\binaries\main-release\msi\*.msi | Get-FileHash -Algorithm SHA256
```
