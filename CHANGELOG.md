# Changelog

Every app change adds a line under `## [Unreleased]`. `./dev version patch|minor|major` turns that section into the
release's `## [x.y.z]` section, and `./dev publish` uses it as the release notes on Cosmos downloads.

## [Unreleased]

## [1.7.2] - 2026-11-28

- New app icon on every platform (macOS, Windows, Linux, Android and the in-app mark), drawn from one source, `desktopApp/app-icons/AutoPilot.svg`. Android 8 and later get an adaptive icon that follows the launcher's shape, and Android 13 and later can show it as a themed icon.

## [1.7.1] - 2026-11-28

- Android: each release now has its own build number (the same build Cosmos lists), so a newer APK always installs over the older one.
- Android: Settings no longer shows the desktop-only pointer safety switches and keyboard shortcuts, and its privacy note describes the phone.

## [1.7.0] - 2026-11-28

- Cosmos is the source of truth for every approved Q&A entry, not only admin corrections: a downloaded entry's answer replaces the bundled and learned answers for its question, and app updates or automatic saves never bring the old answer back. Answers for questions Cosmos doesn't have are kept (confirmed ones are still sent for review), and an answer you confirm that disagrees stays in use until the admin acts. Each routine file downloads every entry once more after this update so earlier downloads follow the new rule.
- Each sync now sends this computer's answers before downloading, so a confirmed answer that differs from Cosmos reaches the admin's review before Cosmos's answer replaces it.
- An answer you confirm that disagrees with a Cosmos admin correction is now submitted to Cosmos (as a conflict for the admin to review) while it stays in use on this computer until the admin changes the entry; such answers kept back by earlier versions are submitted once. Automatic saves still never override or upload against a correction.

## [1.6.1] - 2026-11-28

- Questions containing the micro sign `µ` (15 bundled OLP and Seagull questions) are found again: the bundled Q&A files are rebuilt with the app's own lowercasing instead of case folding, and routine Q&A files move to schema version 4, which repairs the stored keys in place (after a backup copy), merging any duplicate the app had created and keeping learned answers.

## [1.6.0] - 2026-11-28

- Cosmos admin corrections win: an entry Cosmos marks `authoritative` replaces the bundled, learned and earlier Cosmos answers for its question, and app updates, legacy imports and later downloads never bring the old answer back. Routine Q&A files move to schema version 3 (in place, after a backup copy).

## [1.5.1] - 2026-11-28

- Publishing builds the Linux DEB on the home server when Legion's Debian VM can't build or hand it over, so every release is hosted for all four platforms.

- Publishing fetches installers with a resumable download that picks up where a dropped connection stopped (Legion's link drops long transfers).

## [1.5.0] - 2026-11-28

- Removed: the authenticator-code step when the admin signs in to Cosmos (Cosmos no longer asks for one).
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
