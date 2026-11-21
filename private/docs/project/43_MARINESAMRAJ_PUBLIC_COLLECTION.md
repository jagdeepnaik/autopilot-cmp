# 43 — MarineSamraj Public Collection

[Project guides](00_INDEX.md) · [One-command builds](20_ONE_COMMAND_BUILDS.md)

## Scope and live site structure

This independent routine collects publicly displayed MarineSamraj study references through
the existing Chrome extension and app controller. It does not sign in, change bookmarks,
submit answers, or open account/admin/review/notification areas.

On 22 November 2026, the public robots file allowed `/` and `/exam-prep/` and excluded
`/user/`, `/admin/`, `/notification/`, `/review-pipeline/` and `/support-the-creators/`.
The sitemap listed multiple exam-level Written/Orals entry pages. Exam entry pages lead to
subjects; subject tabs expose papers, topics, notes, diagrams and the question bank.
Questions can be non-link controls opening a split view; **Open full page** then exposes
the standalone question and its Next navigation. Notes may have separate content tabs.
The public home page inspected did not expose a separate terms link; that is not a claim
that public content is free of copyright or other conditions.

Sources: [robots](https://www.marinesamraj.com/robots.txt),
[sitemap](https://marinesamraj.com/sitemap.xml),
[public home](https://marinesamraj.com/home.html).

## Run and resume

1. Install the app and reload **AutoPilot Page Helper** to extension version `1.15.19` or
   newer. Refresh the MarineSamraj page after the reload.
2. Open a public exam-preparation page in Chrome. Select **MarineSamraj** in AutoPilot.
3. Set the per-run page limit (1–1000) and the action delay (0–60 seconds), save, and start.
   The default two-second delay gives the page time to respond before each click. The helper selects and binds one
   public collection tab when the run starts. Keep that collection tab selected
   in Chrome. This public read-only collector may continue while another application is
   in front; switching Chrome tabs pauses it. Stop remains available at any time.
4. Restart the same routine/bank to restore its queue and resume page. A page-limit stop
   means this batch ended, not that the entire website was extracted.

The routine waits for stable, loaded content; it reads the answer editor rather than page
headers, author widgets or footer text. It supports non-link question controls and Next.
Sitemap discovery adds the public exam levels. URL identities ignore tracking/history
parameters while retaining content-changing exam, tab and question identifiers.

## Storage and integrity

| Data | Location |
| --- | --- |
| Collected reference text | `~/.autopilot/databases/qa/marinesamraj.db` |
| Logical bank name | `marinesamraj-public-ver-1.0.json` (a bank identifier inside SQLite) |
| Crawl checkpoint | `~/.autopilot/databases/autopilot.db`, learning account `marinesamraj:public`, record kind `marinesamraj-crawl` |

The existing Q&A persistence layer stores the reference answer and selectable-option
compatibility marker. Each save must succeed before the next navigation is issued. Saving
a reference does not reload the entire bank into an assessment snapshot, avoiding that
snapshot's size limits as the reference collection grows. Database uniqueness handles
repeated question/option sets; a changed reference may represent a different set.

The checkpoint contains the visited URLs, remaining queue and resume page. Storage failure
pauses collection. Large readings use bounded, ordered local chunks; incomplete, reordered
or mixed chunks do not trigger navigation. Oversized content is reported instead of silently
truncating an answer. The local bridge's existing event-size limit and extension permissions
remain unchanged.

## Coverage limits

Diagram and attachment URLs within public content are retained alongside text. The routine
does **not yet download those asset files for offline use**. It does not infer missing
answers, bypass restrictions or call undocumented site APIs. A page with uncertain loading
state waits rather than falsely claiming it was collected.

Only public content actually reached through supported page navigation is covered. Hidden
or virtualized rows, changed selectors, site errors, optional overlays and genuinely empty
pages require live verification. Neither a batch limit nor an empty discovered queue proves
that all content on the website exists in the database. A full-site extraction count and
offline asset archive must be verified separately.

Runtime collection writes the local database. It does not automatically overwrite the
reviewed `private/qa/marinesamraj.db` or its packaged resource copy. Review and export
collected public data before updating the bundled bank.

## Validation

Focused domain tests cover loaded-state gating, save confirmation/failure, short answers,
document-bound controls, deduplication, canonical URLs, checkpoint restore and per-run limits.
The isolated Chrome fixture verifies delayed content, exact answer boundaries, a long answer,
one Next click and a changed document identity. Chunk tests cover full reassembly, limits,
ordering and mixed sessions. These tests do not establish full live-site extraction coverage.

Background tests cover same-origin SPA navigation, tab binding while another app is in
front, and rejection of other origins, private paths and an unselected bound tab.

```sh
./gradlew :feature:home:jvmTest --tests '*MarineSamraj*'
node --test private/scripts/tests/marinesamraj-background.test.cjs
AUTOPILOT_LIVE_CHROME_TEST='/Applications/Google Chrome.app/Contents/MacOS/Google Chrome' \
  ./gradlew :shared:jvmTest --tests '*MarineSamrajCollectorBrowserTest*' --tests '*BrowserExtensionTest*'
```
