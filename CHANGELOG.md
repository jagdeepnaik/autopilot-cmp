# Changelog

Every app change adds a line under `## [Unreleased]`. `./dev version patch|minor|major` turns that section into the
release's `## [x.y.z]` section, and `./dev publish` uses it as the release notes on Cosmos downloads.

## [Unreleased]

- Publishing copies installers over a fresh SSH connection each time; a stale shared connection to Legion made every copy fail (the 1.4.0 MSI was added by hand).

## [1.4.0] - 2026-11-28

- The Cosmos admin signs in with a code from an authenticator app (or a recovery code) after the password; Settings asks for it, says when a code didn't work, and explains when two-step sign-in still needs setting up on the server.

## [1.3.1] - 2026-11-28

- Publishing copies the Windows MSI and Linux DEB back to the Mac (the copy helper was only defined for desktop commands, so 1.3.0 shipped macOS and Android only).

## [1.3.0] - 2026-11-28

- Publishing copies the Windows and Linux installers back again (the copy step could not be found, so both were left out of 1.2.2).
- The sync service is now called Cosmos and lives at `https://cosmos.naikio.in`; everyone signs in again once, and the first sync downloads the approved answers again.
- Signing in to Cosmos sends an anonymous ID for this computer (a hash; the computer's own identifier never leaves it), and a new computer waits for the admin's approval, with Try again.
- Settings explains when the admin removed this computer or disabled the account, and asks for a new password after signing in with a temporary one.
- Open Cosmos signs your default browser in to the Cosmos website with a one-time link.
- The admin manages Cosmos from Settings: Accounts (create with a machine limit and a temporary password shown once, edit, disable or enable, reset password) and Machines (approve or revoke computers, waiting ones first).
## [1.2.2] - 2026-11-28

- Windows builds use IPv4 for Gradle, JDK and dependency downloads, so a machine without working IPv6 no longer times out.
- Publishing retries copying a finished installer back to the Mac up to three times.
## [1.2.1] - 2026-11-28

- Copying a DEB back to the Mac reads its package name, version and architecture correctly on Debian 13.
- Release DEBs are built on Legion's x86-64 Debian VM (`./dev build legion-linux`), so Linux PCs get an x86-64 package and the home server only serves.
- Gradle waits up to two minutes to download itself, so a slow first download no longer stops the Windows build.

## [1.2.0] - 2026-11-28

- Every routine run is logged on this computer (steps, timings, video and page state; never page text or answers) and uploaded to Cosmos while signed in.
- Settings shows how many run logs wait to upload, and opens the logs folder.
- Settings → About offers a newer published version, with Install.
- Toolchain on the newest LTS releases: JDK 25 LTS (Microsoft build) for every build machine, Gradle 9.9.3, Ktor 3.6.2.
- `./dev version` reads the current version again (a quoting error stopped every release).
- `./dev publish` builds every installer and publishes it to Cosmos downloads; `./dev version` runs it.
