# 11 — Start, Stop, Test and Monitor the Server's Windows VM

[All guides](00_INDEX.md) · [Build in the VM](12_BUILD_WINDOWS_IN_SERVER_VM.md) · [One-command builds](01_ONE_COMMAND_BUILDS.md)

**On this page:** [One line with the script](#one-line-with-the-script) · [Separately, by hand](#separately-by-hand) · [Recorded test](#recorded-test--11-november-2026) · [Detailed steps: monitor, Screen Sharing, troubleshooting](#11-windows-vm-connect-check-start-monitor-and-stop) · [Cancel, disconnect or shut down](#5-cancel-disconnect-or-shut-down--choose-the-right-action)

The VM is a Windows guest on the home server. It uses 8 GB of the server's 16 GB, so start it only for a build and stop it afterwards. Starting or stopping it never touches Debian or the websites. `desktop.sh vm` needs the VM running and stops with a hint if it isn't.

## One line with the script

From `private/scripts` (or as `private/scripts/server-vm.sh …` from the project root):

| Command | What happens |
| --- | --- |
| `./server-vm.sh status` | Shows the server's uptime and memory, whether the VM process is running, and whether Windows answers over SSH |
| `./server-vm.sh test` | Signs in to Windows and shows the user, uptime, Java, Git, the checkout's commit, local changes and free disk space |
| `./server-vm.sh start` | Starts the VM. You type an 8-character VNC console password for Screen Sharing. It then waits for Windows and runs `test` |
| `./server-vm.sh start --headless` | Same, but the console password is random and never shown, so there's no prompt. Use this for builds; the console can't be opened until the next normal `start` |
| `./server-vm.sh stop` | Refuses while a Gradle build runs in the VM. Otherwise it shuts Windows down cleanly and waits until the VM process has ended |
| add `--remote` | Go through Cloudflare (`home-server-remote`, `home-server-build-remote`) |

A typical VM build:

```sh
cd /Users/jagdeep/Projects/AndroidStudio/AutoPilot/private/scripts
./server-vm.sh start --headless && ./desktop.sh vm && ./server-vm.sh stop
```

The script never force-kills the VM. If Windows doesn't answer, or takes longer than 5 minutes to shut down (often because it's installing updates), the script stops and says to check it with Screen Sharing.

## Separately, by hand

**Check whether it's running** (Mac Terminal):

```sh
ssh home-server "pgrep -af '^/usr/bin/qemu-system-x86_64 -name windows-build '"
```

A line with a process number means it's running; no output means it's stopped.

**Start it**, then type the 8-character console password when asked:

```sh
ssh -t home-server /srv/vms/windows-build/start.sh
```

`VM already running.` means there's nothing to do. Windows takes about 20–60 seconds to boot, longer while installing updates.

**Test the connection to Windows:**

```sh
ssh home-server-build whoami
```

Expected: `jagdeep\builduser`. *Connection refused* or a timeout soon after starting means Windows is still booting: wait and try again.

**Stop it** after any build has finished. The VM's default shell is PowerShell, so this runs as is:

```sh
ssh home-server-build Stop-Computer -Force
```

The SSH connection closes as Windows shuts down. Then repeat the first command until it prints nothing. Don't use Debian's `shutdown`, `reboot` or `poweroff`: they stop the whole server, websites included. For resource monitoring, Screen Sharing and troubleshooting, see [the detailed steps below](#11-windows-vm-connect-check-start-monitor-and-stop).

## Recorded test — 11 November 2026

| Command | Result |
| --- | --- |
| `server-vm.sh status` | Server up 4 days, 5.6 GB memory available; VM running; Windows SSH answering |
| `server-vm.sh test` | Signed in as `jagdeep\builduser`; Java 21.0.13, Git 2.56.0; checkout clean at `ac90a44`; 59.4 GB free |
| `server-vm.sh stop` | No build running; Windows shut down cleanly; VM process ended after about 225 s |
| `server-vm.sh start --headless` | VM started without a prompt; Windows answered SSH after about 20 s; connection test passed |

The interactive `start` (typing the console password) runs `start.sh` through `ssh -t` and wasn't run in this test.

## Detailed steps

### 1.1 Windows VM: connect, check, start, monitor and stop

**Choose only the action you need.** You do not perform every subsection each time. All operations below are manual commands for you to run; the assistant is not starting, stopping or monitoring anything.

| Your goal | Follow |
| --- | --- |
| Check whether the VM is running | A, then B |
| Start a stopped VM | A, B, then C |
| Connect to Windows after it boots | Section 1.2 |
| See VM resource usage | A, then D |
| Watch build progress | E |
| Shut down only Windows | F, then B to confirm |
| Disconnect SSH without stopping Windows | G |

#### A. Connect to the Debian host from your Mac

Open a **new Mac Terminal window**. Choose one route:

**Local network:**

```sh
ssh home-server
```

**Or Cloudflare:**

```sh
ssh home-server-remote
```

You should now see the Debian prompt `jagdeep@jagdeep:~$`. This is the **Debian SSH window**. Do not type the displayed prompt itself.

#### B. Check whether the Windows VM is running

In that **Debian SSH window**, run:

```bash
pgrep -af '^/usr/bin/qemu-system-x86_64 -name windows-build '
```

- A process number followed by the QEMU command means this VM is running. Go to **1.2** to connect; you do not need to start another copy.
- No output means this VM process is not running. Use C to start it.

To check its connection listeners, run this separately:

```bash
ss -ltn | grep -E '127\.0\.0\.1:(2222|5905)'
```

When running, expect `127.0.0.1:2222` (forwarding to Windows SSH) and `127.0.0.1:5905` (VM console). These ports alone do not prove Windows has finished booting or `sshd` is ready. If the process and listeners disagree, stop and send the output; do not force-kill processes or delete VM files.

#### C. Start the VM only if it is stopped

In the same **Debian SSH window**, run:

```bash
/srv/vms/windows-build/start.sh
```

When asked, choose the separate **8-character console password** privately. It is for optional VNC access, not the Windows/SSH password. Nothing appears as you type. Do not send it in chat.

Expected: the launcher reports startup and returns to the Debian prompt. `VM already running.` means another start is unnecessary. If it reports an error, stop and send it; do not rerun disk creation or erase TPM state.

Wait about 30–60 seconds for Windows to boot, longer if it is installing updates. Then open a **new Mac Terminal window** and follow **1.2**. If SSH initially refuses the connection, wait and retry after boot. You do not need to open VNC or sign into the Windows desktop for ordinary command-line builds.

The VM is not configured to start automatically when Debian boots; this launcher starts it on demand.

#### D. Monitor VM and host resource usage

Use your **Debian SSH window**, not Windows PowerShell. These commands only display information; they do not change or stop anything.

**Host memory:**

```bash
free -h
```

Look at `available`; memory in `buff/cache` can often be reclaimed. The Windows VM is configured for 8 GB, so it uses a substantial part of the host’s 16 GB while running.

**Host load:**

```bash
uptime
```

This displays uptime and load averages. A single snapshot is not a measurement of peak website demand.

**Windows disk space actually used:**

```bash
du -h /srv/vms/windows-build/windows11.qcow2
```

The QCOW2 virtual disk has a 100 GB guest capacity but its actual host usage grows as data is written.

**Live VM CPU/RAM display — only after B confirmed this VM is running:**

```bash
top -p "$(cat /srv/vms/windows-build/qemu.pid)"
```

Press **q** to exit `top`. This closes the monitor only; it does not stop the VM. CPU percentages may exceed 100% when several virtual CPUs are active. Do not use `top`’s kill command as a normal VM shutdown method.

You can close the monitoring window when finished. It does not need to stay open for the VM to run.

#### E. Monitor an application build

The Windows/Linux build commands later in this guide run in the foreground and print progress in the same Terminal window. Leave that build window open until **BUILD SUCCESSFUL** or **BUILD FAILED** appears. Do not start a second build because the first is temporarily quiet during downloads or compilation.

You may use a separate Debian SSH window for the resource checks in D while a build runs. The first-check logs listed in section 6 are historical logs, not automatically updated by every manual build command.

To cancel a build you started, press **Control-C once in that build window**, then wait for the prompt. Cancelling the build and shutting down the Windows VM are different actions.

#### F. Shut down only the Windows VM through SSH

First finish or cancel any running Windows build. Close/save other work in the VM. Do not shut it down during packaging, updates or an active transfer.

Open a **new Mac Terminal window** and connect to Windows using one route:

**Local network:**

```sh
ssh home-server-build
```

**Or Cloudflare:**

```sh
ssh home-server-build-remote
```

Confirm that the prompt is Windows PowerShell, such as `PS C:\Users\builduser>`. In that **Windows SSH window**, run:

```powershell
Stop-Computer
```

Expected: the Windows SSH connection closes as the guest shuts down. Allow Windows to finish; updates can make shutdown slower. This stops the Windows guest and releases its RAM, **not Debian or the websites**.

In your separate **Debian SSH window**, repeat B’s process/listener checks. When shutdown finishes, this VM’s QEMU process and its two listeners should disappear. If they stay present, do not delete files or use `kill -9`; send the result or use the VM console to inspect pending shutdown/update messages.

Do not run Debian `shutdown`, `reboot` or `poweroff` to stop only Windows. If Windows SSH is unavailable, stop here for help or use Windows Start → Power → Shut down through VNC; do not assume the guest can be force-stopped safely.

#### G. Disconnect SSH without shutting down the VM

In a connected Debian or Windows SSH window, run:

```text
exit
```

This returns to your Mac Terminal and leaves the VM running. If you manually opened a nested PowerShell, the first `exit` may return to Windows Command Prompt; another `exit` closes SSH. Do not close an SSH window that is currently running your foreground build.

If you have a separate `ssh -N ...` VNC tunnel window, **Control-C in that tunnel window** closes only the tunnel, not Windows. Closing the tunnel prevents Screen Sharing from reconnecting until you reopen it.

## 5. Cancel, disconnect or shut down — choose the right action

- **Cancel your foreground build:** Control-C once in the build’s own Terminal window; wait for the prompt. Do not distribute partial output.
- **Close SSH but keep Windows running:** `exit` in an idle SSH window; see **1.1 G**.
- **Shut down only the Windows VM:** follow **1.1 F**. Use `Stop-Computer` inside Windows PowerShell after finishing all work.
- **Stop watching resource usage:** press **q** in `top`; see **1.1 D**.
- **Close the VNC tunnel:** Control-C in its dedicated tunnel window; Windows keeps running.

Do not restart the Debian website server as a substitute for stopping the VM. No automatic monitoring is configured by this guide.
