# 07 — Copy the AutoPilot Windows Installer

[All guides](00_INDEX.md) · [Build first](02_BUILD_WINDOWS_ON_LEGION.md) · [Install and test](03_INSTALL_AND_TEST_WINDOWS.md)

## PART D — COPY INSTALLERS — fresh Mac Terminal

Skip this if you only want to install the locally built MSI on Legion. These commands run in a **new Mac Terminal**, outside the Windows SSH session.

### D1. Create AutoPilot's download folder

```sh
mkdir -p "$HOME/Downloads/Builds/autopilot-cmp"
```

### D2. Download the installer

```sh
scp 'home-legion:C:/Users/jagde/Projects/autopilot-cmp/composeApp/build/compose/binaries/main-release/msi/AutoPilot-*.msi' "$HOME/Downloads/Builds/autopilot-cmp/"
```

### D3. Check the downloaded hash

```sh
shasum -a 256 "$HOME/Downloads/Builds/autopilot-cmp/"AutoPilot-*.msi
```

Compare the same filename's hash with [the Windows build hash](02_BUILD_WINDOWS_ON_LEGION.md#c6-locate-the-autopilot-installer). If old versions are also present, identify the newly built version before sharing.

### D4. Optional: send a Mac-held installer back to Legion

Only needed if the desired MSI is on the Mac. This example uses version 1.0.0; change the version only if your actual filename differs.

```sh
scp "$HOME/Downloads/Builds/autopilot-cmp/AutoPilot-1.0.0.msi" home-legion:C:/Users/jagde/Downloads/
```
