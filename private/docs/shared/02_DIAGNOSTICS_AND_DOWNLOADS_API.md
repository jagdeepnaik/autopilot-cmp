# Routine diagnostics and app downloads contract

> **Generated copy, do not edit.** The source is the Cosmos–AutoPilot hub: `~/Projects/Agents/CosmosAutoPilotHub/docs/shared/02_DIAGNOSTICS_AND_DOWNLOADS_API.md`. Edit it there; `tools/sync_shared_docs.py` regenerates this file.

Last checked: 28 November 2026. Owner: Cosmos (the server owns the contract; AutoPilot adapts to it).

Two more connections between AutoPilot and Cosmos, both using the app sign-in from
[01_QA_SYNC_API.md](01_QA_SYNC_API.md#signing-in):

- **Routine diagnostics:** AutoPilot writes a detailed log of every routine run to a local file and, when signed in,
  uploads finished runs to Cosmos, where admins read them to fix stuck routines (Chrome needing a refresh, a video
  that never plays).
- **Downloads:** every AutoPilot release is published to Cosmos, and signed-in accounts download the installers from
  the Cosmos website.

On this page:

- [Run logs on the computer](#run-logs-on-the-computer)
- [What a run log may contain](#what-a-run-log-may-contain)
- [Uploading run logs](#uploading-run-logs)
- [Reading run logs (Cosmos website)](#reading-run-logs-cosmos-website)
- [Downloads](#downloads)
- [Publishing a release](#publishing-a-release)

## Run logs on the computer

- One file per run: `~/.autopilot/logs/runs/<YYYY-MM-DD>/<runId>.jsonl` (`%USERPROFILE%\.autopilot\…` on Windows),
  one JSON object per line, UTF-8. `runId` is a UUID made when the run starts.
- Written as the run happens and flushed at least every second, so a run that hangs or crashes still leaves its log.
- Always on, whether or not the user is signed in to Cosmos. Local files older than 30 days are deleted at start-up.
- The first line is the run header, the last the run end; everything between is an event:

```json
{ "type": "run.start", "runId": "…", "at": "2026-11-28T10:00:00.000Z", "routineKind": "CourseAutomation", "routineName": "SMY", "appVersion": "1.1.0+build.170.gfadd6e3", "os": "macOS 26.1 arm64", "settings": { "assessmentMode": "Automatic" } }
{ "type": "event", "at": "2026-11-28T10:00:03.120Z", "seq": 14, "level": "info", "source": "video", "event": "video.state", "data": { "state": "paused", "currentTime": 0, "duration": 612.4, "readyState": 1, "networkState": 2, "errorCode": null, "muted": true } }
{ "type": "run.end", "at": "2026-11-28T10:42:10.000Z", "outcome": "StoppedByUser", "events": 2310, "durationMs": 2530000 }
```

- `source` is one of `routine`, `page`, `video`, `extension`, `bridge`, `answers`, `click`, `keys`, `app`, `sync`.
- `level` is `debug`, `info`, `warn` or `error`. Every routine step, every decision and its reason, every message to and
  from the extension, every video state change, every wait (with how long), every retry, stall and pause, and every
  caught error is an event.

## What a run log may contain

AutoPilot's privacy rules (its `AGENTS.md`) still apply to logs:

| Allowed | Never |
| --- | --- |
| Routine kind, name and settings; step names; decisions and their reasons; timings and counts | Page addresses (URLs), page titles or page text |
| Video and page **state** (playing, paused, times, ready and network state, error codes, element found or not) | Anything typed, passwords, cookies, tokens, course-site account names or emails |
| A target's label (up to 40 characters), change hashes, question **keys** (`questionKey`) | Question, option or answer **text**, screenshots |
| Extension and bridge connection state, message types and sizes | Personal details from Cosmos or the computer (user names, paths with user names) |
| Error class and a safe one-line message (no stack traces with paths) | |

AutoPilot strips these before writing, not only before uploading, and `data` values are numbers, booleans, `null` or
strings of at most 200 characters.

## Uploading run logs

`POST /api/diagnostics/runs`, any signed-in app account, after a run ends (and at start-up for runs not yet sent):

- Body: the run log file's lines, wrapped as JSON: `{ "runId", "header": <run.start object>, "events": [<event>…],
  "end": <run.end object or null if the app stopped mid-run> }`. Sent with `Content-Encoding: gzip`.
- At most 10 MB once inflated and 50,000 events per run; a longer run uploads its first 49,000 and last 1,000 events
  and sets `"truncated": true` in the body.
- **Shape rules** (anything else is `400` and nothing is stored): objects are strict (no unknown fields);
  `header.runId` equals `runId`; `seq` rises strictly within a run; `data` and `settings` are flat objects of at most
  50 keys, each key at most 64 characters; `event` at most 100 characters, `routineKind` 60, `routineName` 200,
  `appVersion` and `os` 100, `outcome` 60. Only this route accepts `Content-Encoding: gzip`; other routes answer
  `415` to a gzip body.
- Response `200 { "runId", "status": "stored" | "duplicate" }`; `runId` is unique per account, so a retry is safe.
- `400` for a malformed body, `413` when too large, `401` and `429` as in the Q&A contract.
- AutoPilot marks a run sent after `stored` or `duplicate`, keeps local files for 30 days either way, and never uploads
  while signed out. Upload failures never affect a routine.
- Cosmos keeps uploaded runs for 90 days, then deletes them.

## Reading run logs (Cosmos website)

Admin only, checked on the server.

| Route | Purpose |
| --- | --- |
| `GET /api/diagnostics/runs?routineKind=&outcome=&page=&pageSize=` | `{ "runs": [RunSummary], "page", "pageSize", "total" }`, newest first, `pageSize` at most 50 |
| `GET /api/diagnostics/runs/:id/events?level=&source=&after=&limit=` | `{ "events": [Event], "nextAfter", "hasMore" }`, in `seq` order, `limit` at most 1,000 |
| `GET /api/diagnostics/runs/:id/download` | The whole run as `<runId>.jsonl` |
| `DELETE /api/diagnostics/runs/:id` | Deletes a run; `204` |

Events come back with `"type": "event"`; `level` and `source` accept comma-separated lists, and `outcome=none` lists
runs that never sent a `run.end`. `RunSummary`: `{ "id", "runId", "routineKind", "routineName", "appVersion", "os", "startedAt", "endedAt", "outcome",
"durationMs", "eventCount", "warnCount", "errorCount", "truncated", "submitterName", "uploadedAt" }`. The page shows a
filterable list and, for one run, a timeline with level and source filters, highlighting long gaps (stalls).

## Downloads

Signed-in accounts only (website cookie or app token).

| Route | Response |
| --- | --- |
| `GET /api/downloads/autopilot` | `{ "latest": Release, "previous": [Release] }` (up to 2 previous releases) or `404` before the first release |
| `GET /api/downloads/autopilot/files/:version/:fileName` | The file, `Content-Disposition: attachment` |

**Update check** (public, no sign-in, so a signed-out app can still learn about updates):

| Route | Response `200` |
| --- | --- |
| `GET /api/app/:platform` | `{ "platform": "windows", "version": "1.1.0", "build": 170, "commit": "fadd6e3", "releasedAt": "…", "downloadUrl": "https://cosmos.naikio.in/downloads" }` |

- `:platform` is `windows`, `macos`, `linux` or `android`; `404` when the latest release has no file for it or nothing
  is published yet. It answers only these fields (never file paths or hashes), with `Cache-Control: public, max-age=300`.
- The app compares the server's `build` with its own build number (the commit count): if the server's is higher it
  shows "Update available: <version> (<build>)" with an **Install** button that opens `downloadUrl`. If the call fails,
  answers `404` or has no build number, it shows nothing.

`Release`:

```json
{
    "version": "1.1.0",
    "build": 170,
    "commit": "fadd6e3",
    "releasedAt": "2026-11-28T12:00:00.000Z",
    "notes": ["Android click routines", "Cosmos Q&A sync"],
    "files": [
        { "platform": "windows", "kind": "msi", "fileName": "AutoPilot-1.1.170.msi", "bytes": 136314880, "sha256": "e6b8…", "minimumOs": "Windows 10 64-bit" },
        { "platform": "macos", "kind": "dmg", "fileName": "AutoPilot-1.1.170.dmg", "bytes": 0, "sha256": "…", "minimumOs": "macOS 13, Apple silicon" },
        { "platform": "linux", "kind": "deb", "fileName": "autopilot_1.1.170_amd64.deb", "bytes": 0, "sha256": "…", "minimumOs": "Debian 12 / Ubuntu 22.04, x86-64" },
        { "platform": "android", "kind": "apk", "fileName": "AutoPilot-1.1.0-170.apk", "bytes": 0, "sha256": "…", "minimumOs": "Android 8.0" }
    ]
}
```

- `sha256` is the full 64-character lowercase hex digest and `bytes` the real size; a manifest that breaks either rule
  makes the list answer `503`.
- `platform` is `windows`, `macos`, `linux`, `android` or `ios`; a platform without a file in a release is left out
  (iOS has no installable file outside the App Store).
- The website shows the latest release first with each file's size, SHA-256 and minimum system, and how to install it
  (for Android: install over USB or allow the source; Play Protect may block apps that use Accessibility services).

## Publishing a release

- **Where files live on the server:** `/srv/sites/cosmos/downloads/autopilot/<version>/` with one `manifest.json`
  at `/srv/sites/cosmos/downloads/autopilot/manifest.json` (the shape of the `GET` response above). The app
  container mounts `/srv/sites/cosmos/downloads` read-only; Cosmos never writes there.
- **Who builds what:** the Mac builds the DMG and the Android APK; **Legion** builds the Windows MSI (never the home
  server, which only serves); a Linux VM (on Legion or the Mac, not the home server) builds the DEB. Every build comes
  from the pushed release commit.
- **How:** AutoPilot's `./dev publish` (run by `./dev version patch|minor|major` after it tags and pushes) builds or
  fetches every installer, checks each SHA-256, copies the new version folder to the server over SSH, then replaces
  `manifest.json` atomically (write a temporary file, then rename). It keeps the three newest version folders and
  deletes older ones. A platform that fails to build is reported and left out; the rest are still published.
