# 06 — Start, Stop, and Monitor the Windows Build VM

[All guides](00_INDEX.md) · [Build in the VM](07_BUILD_WINDOWS_IN_SERVER_VM.md)

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
