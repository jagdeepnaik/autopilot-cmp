# 10 — Copy AutoPilot Installers from the Home Server

[All guides](00_INDEX.md)

## 3. Download installers to the Mac

For the usual case, use [07 — Fetch build artifacts](07_COPY_WINDOWS_INSTALLER.md#one-command-fetch-every-build-artifact). It collects the Legion MSI and server DEBs in one command and verifies them. The manual commands below remain useful when you specifically need the server VM MSI.

Use a **new Mac Terminal window**, not a Windows or Debian SSH prompt.

### 3.1 Create the download folder

```sh
mkdir -p "$HOME/Downloads/Builds/AutoPilot"
```

### 3.2 Download the Windows MSI — choose one route

**Local network:**

```sh
scp 'home-server-build:C:/Build/autopilot-cmp/composeApp/build/compose/binaries/main-release/msi/*.msi' "$HOME/Downloads/Builds/AutoPilot/"
```

**Or Cloudflare:**

```sh
scp 'home-server-build-remote:C:/Build/autopilot-cmp/composeApp/build/compose/binaries/main-release/msi/*.msi' "$HOME/Downloads/Builds/AutoPilot/"
```

### 3.3 Download the Linux DEB — choose one route

**Local network:**

```sh
scp 'home-server:/srv/build/autopilot-cmp/composeApp/build/compose/binaries/main-release/deb/*.deb' "$HOME/Downloads/Builds/AutoPilot/"
```

**Or Cloudflare:**

```sh
scp 'home-server-remote:/srv/build/autopilot-cmp/composeApp/build/compose/binaries/main-release/deb/*.deb' "$HOME/Downloads/Builds/AutoPilot/"
```

Wait for each transfer to finish. Compare the Mac hash with the source hash of the same file:

```sh
shasum -a 256 "$HOME/Downloads/Builds/AutoPilot/"*.msi
```

For the Linux file, separately:

```sh
shasum -a 256 "$HOME/Downloads/Builds/AutoPilot/"*.deb
```

Test the MSI on the Windows laptop. Test the DEB on a suitable Linux desktop, not the production website server. A successful package build does not prove the entire desktop/browser workflow. Installers remain unsigned unless separate signing has been configured.
