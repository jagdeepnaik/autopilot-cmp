# 06 — Update a Versioned Answer Bank

[All guides](00_INDEX.md) · [Browser and alerts](05_BROWSER_AND_ALERT_SETTINGS.md)

## Names and folders

Use **name-ver-1.0.json**, then a new version such as **name-ver-1.1.json** for the next release.

| Bank | Current name |
| --- | --- |
| Sagar Mein Yog | `sagarmeinyog-ver-1.0.json` |
| DGS reserved bank | `dgs-elearning-ver-1.0.json` |

Your updated project bank is in `private/answer-banks/sagarmeinyog-ver-1.0.json`. Its contents were preserved when renamed. The app's bundled default was refreshed from that file. DGS assessment automation remains reserved for later.

Installed bank folder:

- Mac/Linux: `~/.autopilot/answer-banks/`
- Windows: `%USERPROFILE%\.autopilot\answer-banks\`

Use **Open Q&A folder** inside routine options to open the actual folder; this avoids typing a path.

## Install another bank version

1. Prepare the new JSON file, for example `sagarmeinyog-ver-1.1.json`. Keep its existing question/options/answer schema.
2. Open the Q&A folder and place the file there, or use Import with that versioned name. Existing names are not overwritten.
3. In the routine options, choose the new file under **Choose saved file**.
4. Validate the file, then **Save** the routine.
5. Start the next run. An active assessment keeps its current answer snapshot.

Explicitly selected versioned filenames stay pinned. Former built-in names (`sagarmeinyog.json` and `dgs-elearning.json`) resolve to the versioned defaults so old saved routines continue working. Previous local files are retained; custom filenames remain supported for compatibility.

Approved corrections are saved to the selected bank. New version files are never selected automatically just because their version number is higher; choose the intended one in the routine options.

## Sagar Mein Yog answer format

Use plain text in both `options` and `answer`, without choice numbers. The website may display numbered or shuffled choices; the app compares full question text and the complete option set after removing display-order labels. Approved answers are saved without those labels, keeping meaningful numbers such as `2.1 liters`. The earlier unversioned bank is not a fallback.
