# Changelog

Every app change adds a line under `## [Unreleased]`. `./dev version patch|minor|major` turns that section into the
release's `## [x.y.z]` section, and `./dev publish` uses it as the release notes on Compass downloads.

## [Unreleased]

## [1.2.1] - 2026-11-28

- Copying a DEB back to the Mac reads its package name, version and architecture correctly on Debian 13.
- Release DEBs are built on Legion's x86-64 Debian VM (`./dev build legion-linux`), so Linux PCs get an x86-64 package and the home server only serves.
- Gradle waits up to two minutes to download itself, so a slow first download no longer stops the Windows build.

## [1.2.0] - 2026-11-28

- Every routine run is logged on this computer (steps, timings, video and page state; never page text or answers) and uploaded to Compass while signed in.
- Settings shows how many run logs wait to upload, and opens the logs folder.
- Settings → About offers a newer published version, with Install.
- Toolchain on the newest LTS releases: JDK 25 LTS (Microsoft build) for every build machine, Gradle 9.9.3, Ktor 3.6.2.
- `./dev version` reads the current version again (a quoting error stopped every release).
- `./dev publish` builds every installer and publishes it to Compass downloads; `./dev version` runs it.
