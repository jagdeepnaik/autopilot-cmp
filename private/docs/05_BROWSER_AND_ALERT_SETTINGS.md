# 05 — Check the Chrome Extension and Alert Sounds

[All guides](00_INDEX.md) · [Install and test](03_INSTALL_AND_TEST_WINDOWS.md)

## Chrome extension — after an app update

1. Open AutoPilot → Settings → Browser connection. Read **Latest bundled version** and **Loaded in Chrome**. The latest version is read from the app's manifest, not a fixed label.
2. AutoPilot updates its unpacked extension files at startup. If that fails, Settings and routine-start messages explain the folder problem.
3. In Chrome, open `chrome://extensions`. Find **AutoPilot Page Helper** and click its Reload button.
4. Refresh the course page to load the updated page scripts. Do this before starting an assessment, not during a timed attempt.
5. Return to Settings and confirm the versions match. Start the routine only after the mismatch warning clears. A legacy extension that cannot report its version displays **Not reported** rather than an invented loaded version.

Current update: **1.14.1**. Future builds show their own manifest version automatically. The app can update files but Chrome must reload them to activate the update.

## Sound check

Settings → **Alert sounds** is enabled by default. Use **Test sound** to play the real question reminder without opening an assessment. With sounds disabled, the test and automatic alerts stay silent. If silent, check Windows Volume mixer, AutoPilot/Java's app volume, and the selected output device. AutoPilot does not change system volume or mute settings.

| Pattern | Meaning | Repeat |
| --- | --- | --- |
| Two rising notes | A question needs manual help | Every 30 seconds while unanswered |
| Three descending notes | An assessment failed | Once per failed result |
| Four alternating notes | No routine progress | First after five minutes, then every five minutes |

## When inactivity warnings stop

A click, page/lesson transition, answer change, scrolling, or advancing video time resets the inactivity timer. Ordinary heartbeats, recreated controls, and changing countdown labels do not. Long videos continue without false alarms while their position advances. Planned click intervals and start countdowns are intentional waits.

The running screen and bottom status bar show the warning even with sound disabled. It clears when progress resumes or the run stops. An inactivity alert does not click anything or retry an assessment.

Windows verification on 11 November 2026: the owner heard the production question, failure, and four-note inactivity patterns. The five-minute timing and reset rules have deterministic tests.
