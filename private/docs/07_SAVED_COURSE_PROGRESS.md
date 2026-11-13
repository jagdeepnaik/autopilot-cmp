# 07 — Resume Saved Sagar Mein Yog Progress

[All guides](00_INDEX.md) · [Run settings and alerts](05_BROWSER_AND_ALERT_SETTINGS.md)

## Normal runs

1. Sign into Sagar Mein Yog in Chrome and start the saved routine.
2. Choose an existing profile or create one in the startup popup. The most recently used profile is shown first. Choose the account signed into Chrome; usernames are not required on assessment pages. Distinct users must keep distinct profiles.
3. The app loads completed lessons, completed module videos and assessment results from its local database. Modules with all required work finished are skipped.
4. Confirmed video endings and all-video module completion are saved before the next action. Actual assessment result pages are also saved. A passed assessment does not mark videos complete.

Old data may have completed lesson observations but no all-video module marker. The app must inspect that module once to establish the missing evidence; it then saves the marker for future runs. It does not invent historical video completion.

## Website verification — only when needed

- Every routine start after profile selection: read Your Progress and update that profile’s database. If an assessment is already active, defer this check until it is safe to leave the assessment.
- Running screen → **Verify website progress**: queue a fresh check at a safe course page.
- End of a run with new video completions or assessment results: perform a final check. If no new completions were saved, do not repeat the startup check.

The app checks history at startup; it does not re-read it after each module. **Last website verification** is separate from the time of local completion saves. Missing scores can be filled by a later verified history import. Keep All Time and clear filters for verification.

## Database and existing data

Active database: `~/.autopilot/databases/autopilot.db`, using the original core `AppRoomDatabase` and platform builder. Its learning records are grouped into models, entities, DAO, and store files under `core/persistence/room/learning`.

On the first open, the app imports existing accounts and learning history from `course-progress.db` into an empty destination in one transaction. Existing newer destination records are not overwritten. The old database and its files remain as a backup. DGS study/page/video records are preserved too. An invalid import pauses access rather than deleting the source or accepting a partial account import.

Unused transfer-history tables/classes were removed. Routine JSON import/export is a different feature and remains available.

## Assessment modes

- **Automatic:** the app starts, answers exact matches, advances, submits and confirms submission.
- **Review approvals:** the app starts, answers and advances; approve the final submission popup.
- **Manual:** start, answer, advance and submit yourself in Chrome. Results are still saved to the selected profile.

Unknown or ambiguous answers show a status message and repeat the enabled question alert every 30 seconds. Failed assessments require an explicit retry choice.
