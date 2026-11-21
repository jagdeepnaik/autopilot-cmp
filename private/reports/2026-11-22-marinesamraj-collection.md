# MarineSamraj collection continuation — 22 November 2026

## Live inspection

Inspected public robots.txt, sitemap.xml, the public home page, exam entry, subject papers,
a question split view, its full answer page, a question bank and a study note. Robots permits
the exam-prep area and excludes user/admin/notification/review/support areas. No separate
terms link was found on the inspected public home page. No authentication was bypassed.

The site renders content asynchronously. Subject and question pages initially contain only
headers. Question cards can be non-link controls. Full questions expose an Answer region
whose .ql-editor excludes author/footer UI; diagrams are embedded in that content. Notes
also use a rich-text editor. The public sitemap contains Written/Orals entry points for
multiple exam levels.

## Changes and ownership

- Home feature: MarineSamraj session, URL policy, save-confirmed navigation, non-link
  controls, ordered message assembly, persisted crawl checkpoint and reference-write API.
- Existing SQLite repository implementation: reference saves write directly through core
  persistence without materializing a bounded assessment snapshot.
- Shared extension: MarineSamraj collector/background only, plus manifest version 1.15.19.
  No extension permission or core bridge-size limit was expanded.
- MarineSamraj-specific UI copy and guide/index updates.
- The extension permission regression expectation was stale relative to the unchanged
  shipped manifest; it now expects the existing loopback and SMY permissions explicitly.

No Room schema or other routine implementation was changed.

## Verification

- 13 MarineSamraj session/canonicalization/checkpoint tests passed.
- 3 bounded chunk-assembly tests passed.
- SQLite reference persistence test passed: long collection beyond assessment snapshot
  limits remains writable, saved text is preserved and duplicate writes remain idempotent.
- Isolated real Chrome DOM test passed with delayed content, hidden stale headings, a long
  answer, exactly one Next action and navigation to the next document.
- Extension regression tests passed after correcting the existing permission expectation.
- Five background message/tab authorization tests passed. The affected common code also
  compiled for the iOS Arm64 simulator.
- Mac release distributable built and the test version was installed locally.

The complete home suite ran 313 tests with 8 failures and 11 skips before the extra SQLite
test was added. An isolated temporary checkout of unchanged commit aa4dd85 reproduced the
same 8 failures across CourseProgressTest, PageControllerTest and CourseProgressUiTest
(60 selected tests, 8 failures, 11 skips). These failures predate this change and remain
unresolved; they were not disabled or reported as a passing suite.

## Live collection status and limits

The Mac extension was reloaded. Existing pages reported Extension context invalidated
from their previous script, and were refreshed. No fresh collector error was seen afterward;
remaining inspected console errors came from a different extension's React debugging hook.

The initial live run had not received a MarineSamraj reading and had saved zero references.
DOM diagnostics identified an overly strict foreground/global-window selection check and
Chrome's cached sender URL during SPA navigation. Version 1.15.18 selects and binds one
public tab, checks selection inside that tab's own window, and accepts same-origin SPA
readings after validating their public path. Live persistence is awaiting verification
after the owner reloads the helper. Full-site extraction is not yet claimed.
Diagram/attachment URLs are preserved, not downloaded as offline files.

Version 1.15.19 also carries a routine-level configurable delay before each page or
question-control action. The delay defaults to two seconds and permits zero to sixty seconds.
Runtime data has not been copied into the reviewed or packaged source databases.

Temporary baseline checkout: /private/tmp/autopilot-marine-baseline.xUTh3u. It is diagnostic
scratch outside tracked project content and may be removed after inspection.
