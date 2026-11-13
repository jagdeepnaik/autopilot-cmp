# 09 — Build AutoPilot for Linux on the Home Server

[All guides](00_INDEX.md) · [Copy the DEB](10_COPY_SERVER_INSTALLERS.md)

## 2. DEBIAN ONLY — build a DEB installer

### 2.1 Connect to Debian

Open a **new Mac Terminal window**. Choose one:

**Local network:**

```sh
ssh home-server
```

**Or Cloudflare:**

```sh
ssh home-server-remote
```

Expected: Debian prompt such as `jagdeep@jagdeep:~$`. All remaining commands in section 2 run in this **Debian SSH window**.

### 2.2 Enter the app folder

```bash
cd /srv/build/autopilot-cmp
```

### 2.3 Check for local source changes

```bash
git status --short
```

If files are listed, stop before pulling. Do not reset or remove them.

### 2.4 Download committed updates, only if the checkout is clean

```bash
git pull --ff-only
```

### 2.5 Build the DEB

**Required prompt: `jagdeep@jagdeep:/srv/build/autopilot-cmp$` — Debian.** If your prompt begins with `PS C:\`, you are still in Windows: open a new Mac Terminal window, run `ssh home-server` (or `ssh home-server-remote`), then enter `/srv/build/autopilot-cmp`. Do not run the Linux command in PowerShell. `nice` and `ionice` are Linux tools.

Paste this **one complete line**:

```bash
nice -n 10 ionice -c 2 -n 7 ./gradlew --no-daemon --console=plain --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process' :desktopApp:packageReleaseDeb
```

Wait for **BUILD SUCCESSFUL**. The command runs at reduced scheduling priority, with two workers and a 2 GB Gradle heap. It does not install the app or restart the website containers.

### 2.6 Check the generated installer

```bash
ls -lh desktopApp/build/compose/binaries/main-release/deb/*.deb
```

Then, separately:

```bash
sha256sum desktopApp/build/compose/binaries/main-release/deb/*.deb
```
