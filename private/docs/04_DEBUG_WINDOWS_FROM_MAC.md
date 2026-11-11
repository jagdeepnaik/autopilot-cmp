# 04 — Debug AutoPilot on Windows from the Mac

[All guides](00_INDEX.md) · [One-time launcher setup](22_SET_UP_WINDOWS_DEBUG_LAUNCHER.md)

## PART F — REMOTE DEBUGGING — selected app only

This section debugs **AutoPilot**. Keep other Java apps running normally; do not kill all Java processes.

### F1. Launch AutoPilot with the debugger waiting

Sign into the Legion desktop. In **Windows SSH PowerShell**:

```powershell
Start-ScheduledTask -TaskName 'AutoPilot-Debug'
```

This uses the configured interactive launcher on the signed-in desktop. If the task is missing, follow [initial debug launcher setup](22_SET_UP_WINDOWS_DEBUG_LAUNCHER.md#14-how-the-debug-launcher-was-created). If AutoPilot is already running or port 5005 is occupied, close the intended previous debug session first; do not launch duplicates.

### F2. Open the debugger tunnel

**Fresh Mac Terminal:**

```sh
ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:5005:127.0.0.1:5005 home-legion
```

Leave this window open. No output is expected while the tunnel is active; it is not stuck. Do not expose port 5005 publicly.

### F3. Attach Android Studio

On the Mac, open `/Users/jagdeep/Projects/AndroidStudio/AutoPilot` at the same revision as Legion. Select **AutoPilot Windows (SSH tunnel)** and click **Debug**, then resume startup if paused. The checked-in configuration attaches to `127.0.0.1:5005` with AutoPilot's JVM source mapping.

Reproduce the issue on the Legion desktop and inspect breakpoints on the Mac. Pausing the JVM pauses AutoPilot's extension connection too. Debug execution does not run or validate the MSI installer.

## PART G — STOP OR DISCONNECT

- Close AutoPilot normally after stopping its routine.
- Resume/detach the debugger, then press Control-C in the Mac tunnel window.
- Type `exit` in an idle Windows SSH session. If PowerShell was opened inside Command Prompt, type `exit` again to return to the Mac.
- To cancel a foreground build, press Control-C once in that build window and wait for the prompt. Do not terminate unrelated Java processes.

These are manual instructions. Editing this guide does not start a connection, build, installation, or monitoring task.
