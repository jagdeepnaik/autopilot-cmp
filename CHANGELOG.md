# Changelog

Every app change adds a line under `## [Unreleased]`. `./dev version patch|minor|major` turns that section into the
release's `## [x.y.z]` section, and `./dev publish` uses it as the release notes on Compass downloads.

## [Unreleased]

- Every routine run is logged on this computer (steps, timings, video and page state; never page text or answers) and uploaded to Compass while signed in.
- Settings shows how many run logs wait to upload, and opens the logs folder.
- Settings → About offers a newer published version, with Install.
- Toolchain on the newest LTS releases: JDK 25 LTS (Microsoft build) for every build machine, Gradle 9.9.3, Ktor 3.6.2.
- `./dev publish` builds every installer and publishes it to Compass downloads; `./dev version` runs it.
