# 26 — Resume Saved Sagar Mein Yog Progress

[All guides](00_INDEX.md) · [Run settings and alerts](24_BROWSER_AND_ALERT_SETTINGS.md)

## Normal runs

1. Sign into Sagar Mein Yog in Chrome and start the saved routine.
2. Confirm the same local profile for the displayed account. Distinct users must keep distinct profiles.
3. The app loads completed lessons, completed module videos and assessment results from its local database. Modules with all required work finished are skipped.
4. Confirmed video endings and all-video module completion are saved before the next action. Actual assessment result pages are also saved. A passed assessment does not mark videos complete.

Old data may have completed lesson observations but no all-video module marker. The app must inspect that module once to establish the missing evidence; it then saves the marker for future runs. It does not invent historical video completion.

## Website verification — only when needed

- First run for a profile without verified history: read Your Progress.
- Running screen → **Verify website progress**: queue a fresh check at a safe course page.
- End of a full run: perform one final check.

The app does not re-read history after each module or every restart. **Last website verification** is separate from the time of local completion saves. Missing scores can be filled by a later verified history import. Keep All Time and clear filters for verification.

## Database and existing data

Active database: `~/.autopilot/databases/autopilot.db`, using the original core `AppRoomDatabase` and platform builder. Its learning records are grouped into models, entities, DAO, and store files under `core/persistence/room/learning`.

On the first open, the app imports existing accounts and learning history from `course-progress.db` into an empty destination in one transaction. Existing newer destination records are not overwritten. The old database and its files remain as a backup. DGS study/page/video records are preserved too. An invalid import pauses access rather than deleting the source or accepting a partial account import.

Unused transfer-history tables/classes were removed. Routine JSON import/export is a different feature and remains available.
