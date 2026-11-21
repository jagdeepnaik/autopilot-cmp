# 11 — Copy AutoPilot Installers from the Home Server

[All guides](00_INDEX.md)

## 3. Download installers to the Mac

For the usual case, use [01 — Fetch and verify build artifacts](01_ONE_COMMAND_BUILDS.md#fetch-and-verify-build-artifacts). It collects the Legion MSI and server DEBs in one command and verifies them. The manual commands below remain useful when you specifically need the server VM MSI.

Use a **new Mac Terminal window**, not a Windows or Debian SSH prompt.

### 3.1 Create the download folder

```sh
mkdir -p "$HOME/Downloads/AutoPilot/Builds"
```

### 3.2 Download the Windows MSI — choose one route

**Local network:**

```sh
scp -o ProxyJump=home-server -P 2222 'builduser@127.0.0.1:C:/Build/autopilot-cmp/desktopApp/build/compose/binaries/main-release/msi/*.msi' "$HOME/Downloads/AutoPilot/Builds/"
```

**Or Cloudflare:**

```sh
scp -o ProxyJump=home-server-remote -P 2222 'builduser@127.0.0.1:C:/Build/autopilot-cmp/desktopApp/build/compose/binaries/main-release/msi/*.msi' "$HOME/Downloads/AutoPilot/Builds/"
```

### 3.3 Download the Linux DEB — choose one route

**Local network:**

```sh
scp 'home-server:/srv/build/autopilot-cmp/desktopApp/build/compose/binaries/main-release/deb/*.deb' "$HOME/Downloads/AutoPilot/Builds/"
```

**Or Cloudflare:**

```sh
scp 'home-server-remote:/srv/build/autopilot-cmp/desktopApp/build/compose/binaries/main-release/deb/*.deb' "$HOME/Downloads/AutoPilot/Builds/"
```

Wait for each transfer to finish. Compare the Mac hash with the source hash of the same file:

```sh
shasum -a 256 "$HOME/Downloads/AutoPilot/Builds/"*.msi
```

For the Linux file, separately:

```sh
shasum -a 256 "$HOME/Downloads/AutoPilot/Builds/"*.deb
```

Test the MSI on the Windows laptop. Test the DEB on a suitable Linux desktop, not the production website server. A successful package build does not prove the entire desktop/browser workflow. Installers remain unsigned unless separate signing has been configured.
