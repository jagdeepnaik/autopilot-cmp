# 07 — Fetch AutoPilot Build Artifacts to the Mac

[All guides](00_INDEX.md) · [One-command builds](01_ONE_COMMAND_BUILDS.md#quick-reference) · [Linux server build](09_BUILD_LINUX_ON_SERVER.md) · [Legion build](02_BUILD_WINDOWS_ON_LEGION.md)

## One command: fetch every build artifact

From the project root on the Mac, run:

```sh
private/scripts/desktop.sh fetch all
```

That is the only command needed to collect the existing remote release files. It copies:

- every Windows MSI from Legion (`home-legion`);
- every Linux DEB from the home server (`home-server`).

The files go to `~/Downloads/Builds/AutoPilot`. The script checks the SHA256 hash of every copied file against the machine it came from, then reports each verified file.

It does **not** push or pull Git, build a new installer, install an app, or remove old files. Build first when needed with `private/scripts/desktop.sh build legion` and `private/scripts/desktop.sh build server`; use this command afterwards whenever you want copies on the Mac.

For the Cloudflare connection to the home server, use:

```sh
private/scripts/desktop.sh --remote fetch all
```

Legion remains on its normal local SSH connection. If its MSI folder or the server's DEB folder is empty, the command stops and tells you which machine must build first.

## Use the files

- Install an MSI on Legion with [03 — Install and test](03_INSTALL_AND_TEST_WINDOWS.md).
- Install a DEB on a Linux desktop, not on the production home server:

  ```sh
  sudo apt install ./autopilot_<version>_amd64.deb
  ```

## By hand, if the one-command fetch is unavailable

These are fallbacks only. The script above is safer because it verifies every transferred file.

```sh
mkdir -p "$HOME/Downloads/Builds/AutoPilot"
scp 'home-legion:C:/Users/jagde/Projects/autopilot-cmp/composeApp/build/compose/binaries/main-release/msi/*.msi' "$HOME/Downloads/Builds/AutoPilot/"
scp 'home-server:/srv/build/autopilot-cmp/composeApp/build/compose/binaries/main-release/deb/*.deb' "$HOME/Downloads/Builds/AutoPilot/"
```
