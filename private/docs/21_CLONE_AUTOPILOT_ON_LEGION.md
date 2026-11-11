# 21 — Clone AutoPilot on Legion for the First Time

[All guides](00_INDEX.md) · [SSH setup](19_SET_UP_WINDOWS_SSH.md) · [Build after cloning](02_BUILD_WINDOWS_ON_LEGION.md)

## PART B — NEW PROJECT PREPARATION — once per app

For AutoPilot, this means the first checkout only. **Skip this entire part on the configured Legion: the checkout already exists.** Never clone over an existing folder.

**Mac Terminal:**

```sh
ssh home-legion
```

If the connected prompt does not start with `PS`, enter:

```text
powershell.exe -NoProfile
```

**Windows SSH PowerShell — first clone only:**

```powershell
$ErrorActionPreference = 'Stop'
if (Test-Path 'C:\Users\jagde\Projects\autopilot-cmp') { throw 'AutoPilot already exists. Stop and use Part C.' }
New-Item -ItemType Directory -Force 'C:\Users\jagde\Projects' | Out-Null
git clone https://github.com/jagdeepnaik/autopilot-cmp.git C:\Users\jagde\Projects\autopilot-cmp
if ($LASTEXITCODE -ne 0) { throw 'Clone failed. Stop here.' }
```

Continue with [C2: open AutoPilot](02_BUILD_WINDOWS_ON_LEGION.md#c2-open-autopilot) in the same window.
