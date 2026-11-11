# 14 — Windows installer failure — Legion diagnosis

[All guides](00_INDEX.md)

## Fix in place (10 November 2026)

The build now removes the unsigned helper instead of waiting for signing. After every MSI packaging task, `composeApp/build.gradle.kts` runs `private/scripts/windows/Remove-UnsignedMsiHelper.ps1`, which deletes the `JpCaDll` binary and the two actions it backs:

- `JpFindRelatedProducts` refines the standard `FindRelatedProducts`. The MSI still runs that standard action and `RemoveExistingProducts` against its Upgrade table, so upgrades keep working.
- `JpCheckInstallDir` only warns, on the folder page, that the chosen folder is not empty. The signed WiX path check (`WixUIValidatePath`) still validates the folder.

The remaining WiX helper DLLs are signed by the WiX Toolset. The script stops and leaves the MSI unchanged if `JpCaDll` backs any other action, for example after a JDK update.

Verified on `home-legion` with Smart App Control on (`VerifiedAndReputablePolicyState` = 1):

- The patched 1.0.0 MSI installed silently with exit code 0 and `Installation success or error status: 0`. AutoPilot is now installed in `C:\Program Files\AutoPilot\`.
- No Code Integrity block events were logged during installation, or during a 15-second launch of the installed `AutoPilot.exe`.
- The app's interactive GUI hasn't been checked on the desktop yet.

Trusted signing (below) is still the right release step, because unsigned apps can still meet SmartScreen and Smart App Control reputation checks. It no longer blocks installation on Legion.

---

Recorded 10 November 2026. Investigation performed through the owner's requested SSH shortcut, `home-legion`. No builds or installation retries were run, and no Windows protection settings were changed.


### Second block: the app launcher (10 November 2026)

After the installer fix, version 1.0.24 installed but wouldn't open: *"Smart App Control blocked an app that may be unsafe"*. Code Integrity event 3077 recorded `explorer.exe` trying to start `C:\Program Files\AutoPilot\AutoPilot.exe`, under the same policy `{0283ac0f-fff1-49ae-ada1-8a933130cad6}`. Launching it over SSH gave *"An Application Control policy has blocked this file"*.

- `AutoPilot.exe` is jpackage's generated launcher. It's unsigned and new for every build, so it has no reputation.
- All bundled runtime DLLs are unsigned too (`jlink` copies the JDK's pre-signing files from its jmods), and so is `skiko-windows-x64.dll`. None of them was blocked: they're identical across every app built with the same JDK.
- The Microsoft JDK's `javaw.exe` is signed by Microsoft Corporation. Starting the installed app with it, or with a copy placed in the bundled runtime, ran with no blocks.

The fix: the build copies that signed `javaw.exe` into the runtime image, and `private/scripts/windows/Use-SignedJavaLauncher.ps1` points both MSI shortcuts at it. Verified on Legion with Smart App Control on: the install succeeded, the desktop shortcut targets the signed `runtime\bin\javaw.exe` with the AutoPilot icon, the app was running 15 seconds after opening the shortcut, and no Code Integrity blocks were logged.


### Third block: the runtime's `jvm.dll` (11 November 2026)

Version 1.0.50 installed, but opening it failed three times (13:16, 13:18 and 13:19). Each time Code Integrity events 3077/3033 recorded the bundled `runtime\bin\javaw.exe` loading `runtime\bin\server\jvm.dll`, under policy `{0283ac0f-fff1-49ae-ada1-8a933130cad6}`. The runtime's `jvm.dll` (14,046,720 bytes, SHA256 `27159508…D65A1E71`) was `NotSigned`. The packaging JDK's own `C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot\bin\server\jvm.dll` is `Valid` (Microsoft). Both report `JAVA_VERSION="21.0.13.0.2"`.

The install script's launch check had passed, because `javaw.exe` stays open while it shows the load error. Fix: the build swaps the runtime's native files for the JDK's signed copies, and the launch check requires `jvm.dll` to be loaded (see [08, H0c](08_FIX_WINDOWS_INSTALLER.md)).

## Confirmed cause

**Windows Smart App Control is blocking the unsigned Java packaging custom-action DLL.** The installer’s “required DLL could not be run” message is not evidence of a missing Visual C++ runtime in this case.

The evidence is linked to the owner's actual AutoPilot failures:

| Evidence | Result |
| --- | --- |
| Windows Application events | Error 1723 for `JpFindRelatedProducts`, entry `FindRelatedProductsEx` |
| Matching Code Integrity events | IDs 3077 and 3033 name the exact temporary MSI DLLs from those failures |
| Enforced policy ID | `{0283ac0f-fff1-49ae-ada1-8a933130cad6}` — VerifiedAndReputableDesktop / Smart App Control |
| Direct Windows loader diagnostic | Error **4551**: `An Application Control policy has blocked this file` |
| Embedded DLL | MSI Binary table entry `JpCaDll`; extracted for inspection only |
| Embedded DLL architecture | x64, matching the MSI's x64 template |
| Entry point | `FindRelatedProductsEx` exists |
| DLL imports | `ole32.dll`, `msi.dll`, `USER32.dll`, `SHLWAPI.dll`, `KERNEL32.dll` |
| DLL signature | NotSigned |
| MSI signature | NotSigned |
| Signing credentials found | No code-signing certificate returned from the current-user/machine personal certificate stores; external/cloud credentials were not configured or assumed |

Microsoft identifies the policy above as the Smart App Control enforcement policy: [inbox App Control policies](https://learn.microsoft.com/en-us/windows/security/application-security/application-control/app-control-for-business/operations/inbox-appcontrol-policies).

## Package examined

Only one AutoPilot MSI was found under the owner's Windows profile:

```text
C:\Users\jagde\Projects\autopilot-cmp\composeApp\build\compose\binaries\main-release\msi\AutoPilot-1.0.0.msi
```

- Size: 115,867,435 bytes.
- SHA256: `39047816A2EAFD63DCBD30EAE2B3A800B3532EE5801E8584BD4B883AE35FD2F9`.
- This matches the laptop-built artifact recorded during the original setup.
- Windows events at approximately 19:38–19:39 record failed installation attempts with status 1603 and the underlying custom-action error 1723.
- The source checkout contains prior uncommitted UI changes. They were preserved, not reset or overwritten.

The custom-action name/entry point in this MSI match the Windows failure events. No claim is made that every other drive was searched or that every independently built MSI has the same hash.

## Why a normal rebuild is not the demonstrated fix

Rebuilding the same unsigned helper does not address the enforced signing requirement. Signing only the outer MSI is also insufficient if an extracted native helper remains blocked. The upstream Java packaging ecosystem documents the same unsigned `wixhelper.dll` problem, including cases where the outer installer was signed: [Adoptium issue 3489](https://github.com/adoptium/temurin-build/issues/3489).

For a release compatible with Smart App Control, prepare a signing pipeline that covers installer helper DLLs and other required native binaries as well as the final installer. Audit native files actually loaded during installation and app use. Use an appropriate trusted code-signing certificate/service and verify the resulting signatures. A locally self-signed development certificate is not an equivalent trusted release credential for Smart App Control. See [Microsoft signing requirements](https://learn.microsoft.com/en-us/windows/apps/develop/smart-app-control/code-signing-for-smart-app-control).

This diagnosis does not claim that such signing has been configured or that the MSI now installs successfully.

## Selected remedy: trusted signing, protection stays enabled

The owner selected trusted release signing and confirmed that no signing certificate or service account is currently available. Keep Smart App Control enabled. No security-policy changes are authorized by this choice.

Before selecting a provider, confirm the registration country and whether the publisher is an individual or a company. Microsoft currently limits Artifact Signing public-trust eligibility by location and publisher type; it must not be assumed available everywhere. Other publicly trusted certificate providers may be suitable. See [Microsoft code-signing options and eligibility](https://learn.microsoft.com/windows/apps/package-and-deploy/code-signing-options).

The remaining sequence is:

1. Confirm publisher eligibility and choose a publicly trusted provider. The owner completes identity verification and approves any purchase directly.
2. Configure access to the provider without placing private keys, account passwords, or signing secrets in the repository or documentation.
3. Integrate signing of the Java installer helper before MSI construction, audit required native app binaries, then sign and timestamp the final MSI. Signing only the outer MSI does not resolve the observed inner-DLL block.
4. Verify signatures and repeat installation on `home-legion` with Smart App Control enabled, retaining the installer log and checking Code Integrity events. Test app launch after installation.

Status: diagnosis confirmed; signing credentials pending. Installation is unblocked by the helper removal above.

Microsoft states that there is no per-app Smart App Control exception and recommends correctly signed apps: [Smart App Control FAQ](https://support.microsoft.com/en-us/windows/security/threat-malware-protection/smart-app-control-frequently-asked-questions).

## Diagnostic files

Extracted helper copies on Legion:

```text
%TEMP%\autopilot-installer-diagnostics\JpCaDll.dll
%TEMP%\autopilot-installer-diagnostics\WixCA.dll
```

They were extracted read-only from the MSI database. The original MSI was not modified. The loader check attempted to load the helper without invoking its installer action and was blocked by policy. Local analysis copies were placed under `/tmp/autopilot-installer-diagnostics` on the Mac.

No routines, private application databases, website services, signing trust stores, or security policies were changed. Preserve the evidence until the selected fix is verified.

## Follow-up: missing dependencies and earlier successful runs

On 10 November 2026, a fresh check over `home-legion` found all five direct Windows DLL dependencies present and successfully loadable: `ole32.dll`, `msi.dll`, `user32.dll`, `shlwapi.dll`, and `kernel32.dll`. Loading the extracted `JpCaDll.dll` still returned error 4551. Code Integrity event 3077 independently recorded the extracted helper and MSI temporary helpers blocked under policy `{0283ac0f-fff1-49ae-ada1-8a933130cad6}`. This confirms a policy block; it is not evidence of a missing direct dependency. Additional issues after that block are not ruled out.

The earlier successful app run used the `AutoPilot-Debug` scheduled task, invoking `private/scripts/windows/Start-Debug.ps1`, rather than an installed MSI. That task still exists. Successful packaging generated an MSI but did not prove installation succeeded. The queried AutoPilot installer events from the past seven days contained failures with status 1603; no successful installation appeared in the returned records.

The Downloads folder contained only `ChromeSetup.exe` among MSI/EXE installers, and its signature was Valid. A known working unsigned installer has not yet been identified, so comparison with the owner's other unsigned apps remains pending. No security setting, installed application, or source file was changed for these checks.
