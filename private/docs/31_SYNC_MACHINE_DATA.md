# 31 — Sync AutoPilot Data from Every Machine

[Documentation index](00_INDEX.md) · [One-command builds](01_ONE_COMMAND_BUILDS.md)

**On this page:** [What it copies](#what-it-copies) · [Sync every machine](#sync-every-machine) · [Sync one machine](#sync-one-machine) · [Folder layout](#folder-layout) · [Machine paths](#machine-paths) · [Safety](#safety)

This guide copies each machine's local AutoPilot data to the Mac. It is separate from Git sync:
`sync` pushes and pulls project commits, while `sync-data` copies progress, settings and certificates.

## What it copies

For each machine, the command copies these two folders when they exist:

| Source on the machine | Saved under the Mac Sync folder | Contents |
| --- | --- | --- |
| `~/.autopilot` | `<machine>/.autopilot` | AutoPilot local settings, progress database and saved routine state. |
| `~/Downloads/AutoPilot` | `<machine>/Downloads/AutoPilot` | Downloaded course certificates and other AutoPilot downloads. |

The command does not copy the project checkout, Git metadata, passwords, SSH keys or the browser
profile. It does not change Git, build, install or debug anything.

This command intentionally does not check whether the Mac has uncommitted or unpushed source-code
changes. Use `private/scripts/desktop.sh sync ...` for source-code synchronization; use
`sync-data` whenever you only need to collect generated progress, settings or certificates.

## Sync every machine

Run this from the project root or any Mac Terminal folder:

```sh
private/scripts/desktop.sh sync-data all
```

The files are collected in:

```text
~/Downloads/AutoPilot/Sync/
```

The command includes:

```text
mac/
legion/
server/
server-vm-win/
mac-vm-win/
mac-vm-linux/
```

For the home server through Cloudflare, use:

```sh
private/scripts/desktop.sh --remote sync-data all
```

The `--remote` option changes only the home-server and server-VM connection to their Cloudflare
SSH aliases. Legion and the Mac-local VMs continue to use their normal SSH aliases.

## Sync one machine

Use the same action with one target when you do not need every machine:

```sh
private/scripts/desktop.sh sync-data mac
private/scripts/desktop.sh sync-data legion
private/scripts/desktop.sh sync-data server
private/scripts/desktop.sh sync-data server-vm-win
private/scripts/desktop.sh sync-data mac-vm-win
private/scripts/desktop.sh sync-data mac-vm-linux
```

To sync the Mac and its two local VMs only:

```sh
private/scripts/desktop.sh sync-data local-all
```

## Folder layout

After a successful collection, the Mac has this layout:

```text
~/Downloads/AutoPilot/Sync/
├── mac/
│   ├── .autopilot/
│   └── Downloads/AutoPilot/
├── legion/
│   ├── .autopilot/
│   └── Downloads/AutoPilot/
├── server/
│   ├── .autopilot/
│   └── Downloads/AutoPilot/
├── server-vm-win/
│   ├── .autopilot/
│   └── Downloads/AutoPilot/
├── mac-vm-win/
│   ├── .autopilot/
│   └── Downloads/AutoPilot/
└── mac-vm-linux/
    ├── .autopilot/
    └── Downloads/AutoPilot/
```

Missing folders are reported as skipped. This is expected on a headless server or a machine that
has not yet run AutoPilot or downloaded a certificate.

## Machine paths

The defaults match the current setup:

| Machine | SSH host | AutoPilot data home |
| --- | --- | --- |
| Mac | local | `/Users/jagdeep` |
| Legion | `home-legion` | `C:/Users/jagde` |
| Home server | `home-server` | `/home/jagdeep` |
| Server Windows VM | `home-server-build` | `C:/Users/builduser` |
| Mac Windows VM | `mac-windows-vm` | `C:/Users/Jagdeep` |
| Mac Linux VM | `mac-linux-vm` | `/home/jagdeep` |

If a VM uses another Windows or Linux account, override its data home for the command:

```sh
MAC_WINDOWS_HOME='C:\Users\OtherUser' private/scripts/desktop.sh sync-data mac-vm-win
MAC_LINUX_HOME='/home/otheruser' private/scripts/desktop.sh sync-data mac-vm-linux
```

The other configurable SSH variables are documented in [01 — One Command Builds](01_ONE_COMMAND_BUILDS.md#quick-reference).

## Safety

- Existing files in `~/Downloads/AutoPilot/Sync` are updated; the script never deletes old files.
- A missing source folder is skipped with a clear message instead of stopping the whole collection.
- The command uses the existing SSH connection configuration and never asks Git to modify a checkout.
- Certificate files may contain personal course information. Keep the Sync folder private and do not
  commit it to GitHub.
