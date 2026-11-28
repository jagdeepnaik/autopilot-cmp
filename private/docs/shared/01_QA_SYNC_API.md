# Q&A sync API contract

> **Generated copy, do not edit.** The source is the Cosmos–AutoPilot hub: `~/Projects/Agents/CosmosAutoPilotHub/docs/shared/01_QA_SYNC_API.md`. Edit it there; `tools/sync_shared_docs.py` regenerates this file.

Last checked: 28 November 2026. Owner: Cosmos (the server owns the contract; AutoPilot adapts to it).

AutoPilot desktop apps share their routine question-and-answer banks through Cosmos (`https://cosmos.naikio.in`).
Every app downloads the **approved** answers; answers an app learns go to Cosmos as **submissions**, which an admin
reviews on the Cosmos website before any app receives them. Only the Q&A banks sync: course progress, routines, run
history, accounts on course sites and anything typed stay on the computer.

On this page:

- [Overview](#overview)
- [Signing in](#signing-in)
- [Banks and entries](#banks-and-entries)
- [Downloading approved entries](#downloading-approved-entries)
- [Submitting learned answers](#submitting-learned-answers)
- [Reviewing submissions (Cosmos website)](#reviewing-submissions-cosmos-website)
- [Correcting the database (admin)](#correcting-the-database-admin)
- [Seeding the server](#seeding-the-server)
- [Errors](#errors)
- [Rules both sides follow](#rules-both-sides-follow)

## Overview

```text
AutoPilot (desktop) ──HTTPS──> cosmos.naikio.in /api ──> Cosmos backend (Express) ──> PostgreSQL
   local <routine>.db              ▲
   (bundled + approved + learned)  └── Cosmos website: admins review submissions
```

- All routes are under `/api` on `https://cosmos.naikio.in`. Requests and responses are JSON (`UTF-8`).
- Sync is **opt-in**: AutoPilot does nothing on the network until the user signs in to Cosmos in Settings.
- AutoPilot sends `X-AutoPilot-App-Version: <SemVer identity>` (for example `1.1.0+build.165.ga542753`) on every
  request. Cosmos records it with submissions; it never rejects a request because of it (yet).

## Signing in

Apps use their own token pair; the website keeps its cookie sign-in unchanged.

| Route | Body | Response `200` |
| --- | --- | --- |
| `POST /api/auth/app/login` | `{ "email", "password", "deviceName" }` | `AppSession` |
| `POST /api/auth/app/refresh` | `{ "refreshToken" }` | `AppSession` |
| `POST /api/auth/app/logout` | `{ "refreshToken" }` | `204`, no body |

```json
{
    "accessToken": "<JWT>",
    "accessTokenExpiresIn": 900,
    "refreshToken": "<opaque, 64 hex characters or more>",
    "refreshTokenExpiresAt": "2027-02-26T10:00:00.000Z",
    "user": { "id": "u_123", "name": "Jagdeep", "role": "seafarer" }
}
```

- **Access token:** sent as `Authorization: Bearer <accessToken>`; valid 15 minutes.
- **Refresh token:** valid 90 days, and 30 days unused. Each refresh returns a **new** refresh token and retires the
  old one. Presenting a retired token again revokes that whole session (reuse detection) and returns `401`, with one
  exception: the token retired by the **most recent** refresh is accepted again for 60 seconds, so a reply lost on a
  bad connection can be retried. That retry rotates once more from the newest token and retires both.
- **Storage:** AutoPilot keeps the refresh token only in platform secure storage, and the access token only in
  memory. Neither appears in logs, preferences, Room or exported files.
- **Failure:** wrong email or password returns `401 { "error": "Invalid email or password" }`, the same for an unknown
  account. Login and refresh are rate-limited (`429`).
- `deviceName` is free text up to 60 characters chosen by the app (for example `AutoPilot on MacBook`), shown to the
  account holder. It is not an identifier.

## Banks and entries

A **bank** is one routine's Q&A database. Keys are fixed:

| Bank key | AutoPilot file | Routine | Bank name a learned answer is saved under |
| --- | --- | --- | --- |
| `smy` | `smy.db` | Sagar Mein Yog | `sagarmeinyog-ver-1.0.json` |
| `olp` | `olp.db` | Ocean Learning Platform | `oceantg-ver-1.0.json` |
| `seagull` | `seagull.db` | Seagull CES | `ces_qa.db` |
| `marinesamraj` | `marinesamraj.db` | MarineSamraj | `marinesamraj-public-ver-1.0.json` |

An **entry** is one question with its options and its accepted answers. Its identity is
`(bank, bankName, module, questionKey)`, the same as the unique key of `qa_questions` in AutoPilot's files.

- **`bankName`** is the `bank_name` the question has in AutoPilot's file (one routine file can hold several, for
  example the `olp-apk-…` files inside `olp.db`).
- **`questionKey`** is AutoPilot's key for a question **and its set of options**: the SHA-256 (lowercase hex) of the
  normalised question, `U+001F`, then the normalised option texts sorted and joined with `U+001E`. So two questions
  with the same wording but different options are different entries.
- **`answerKey`** is AutoPilot's key for one accepted answer of that question (`qa_answers.answer_key`): the
  normalised answer text, for example `shipyard manager`. It is not a hash.
- **The text rules** (AutoPilot's `QuestionAnswerText`), used by both sides:
    - `normalize(t)`: replace U+00A0 with a space, collapse every run of whitespace to one space, trim, then lowercase
      (Unicode default lowercasing, not locale-specific).
    - `optionText(t)`: replace U+00A0 with a space, trim, then remove one leading display label matching
      `^\d+[.)]\s+` (for example `2. ` or `3) `, but not `2.1`).
    - `normalizedOption(t) = normalize(optionText(t))`.
    - `questionKey = sha256hex(normalize(question) + U+001F + sorted(normalizedOption(each option)).join(U+001E))`.
    - `answerKey(answer) = normalize(answer)`.
- Lowercasing is plain Unicode lowercasing (Kotlin `lowercase()`, JavaScript `toLowerCase()`), **not** case folding:
  the micro sign `µ` (U+00B5) stays `µ`. Python's `casefold()` changes it and must not be used.
- Cosmos compares keys byte for byte. It computes them only for admin corrections and problem checks below, with exactly
  these rules. Changing a rule is a breaking change to this contract.
- **Revision:** every change to an entry (approval, new answer, retirement) gives it a new `revision`, a number that
  only grows across the whole server. Apps remember the highest revision they have seen per bank.

`Entry`:

```json
{
    "id": "e_8f2c…",
    "bank": "smy",
    "bankName": "sagarmeinyog-ver-1.0.json",
    "module": "Module 3",
    "question": "What is the minimum …?",
    "questionKey": "8c9fb984…",
    "options": [
        { "position": 1, "text": "Two" },
        { "position": 2, "text": "Three" }
    ],
    "answers": [{ "text": "Three", "answerKey": "three" }],
    "source": "bundled",
    "status": "approved",
    "authoritative": false,
    "revision": 1042,
    "updatedAt": "2026-11-28T09:12:00.000Z"
}
```

- `source`: `bundled` (from AutoPilot's audited sources) or `learned` (approved from a submission).
- `status`: `approved`, or `retired` when an admin withdraws the entry. Apps delete retired entries they got from
  Cosmos; they never delete their own bundled or learned answers because of it.

## Downloading approved entries

| Route | Response `200` |
| --- | --- |
| `GET /api/qa/banks` | `{ "banks": [{ "bank": "smy", "revision": 1042, "entryCount": 812 }] }` |
| `GET /api/qa/banks/:bank/entries?after=<revision>&limit=<n>` | `{ "entries": [Entry], "nextAfter": 1042, "hasMore": false }` |

- Any signed-in account may download.
- `after` defaults to `0`; `limit` defaults to 500, at most 1000. Entries come in ascending `revision` order;
  call again with `after = nextAfter` while `hasMore` is `true`.
- An unknown bank returns `404`.
- AutoPilot stores downloaded entries in that routine's local database with `source = 'cosmos'` and their
  `revision`, and uses them exactly like bundled answers: exact matches only, never fuzzy.

## Submitting learned answers

`POST /api/qa/banks/:bank/submissions`, any signed-in account, at most 100 submissions per request:

```json
{
    "submissions": [
        {
            "clientId": "6f1d2a7e-…",
            "bankName": "sagarmeinyog-ver-1.0.json",
            "module": "Module 3",
            "question": "What is the minimum …?",
            "questionKey": "8c9fb984…",
            "options": [
                { "position": 1, "text": "Two" },
                { "position": 2, "text": "Three" }
            ],
            "answer": { "text": "Three", "answerKey": "three" },
            "learnedAt": "2026-11-28T08:55:10.000Z",
            "reviewedAt": "2026-11-28T08:56:00.000Z"
        }
    ]
}
```

Response `200`, one result per submission in the same order:

```json
{ "results": [{ "clientId": "6f1d2a7e-…", "status": "pending" }] }
```

| `status` | Meaning | What AutoPilot does |
| --- | --- | --- |
| `pending` | Stored for review | Marks it sent; keeps using its own local answer |
| `duplicate` | This account already sent this `clientId` | Marks it sent (retries are safe) |
| `already_known` | The same answer is already approved | Marks it sent |
| `conflict` | An approved entry has a different answer; stored for review with the conflict shown | Marks it sent |

- `clientId` is a UUID the app creates once per learned answer and keeps; it makes retries idempotent.
- Limits per field: `bankName` 200 characters, `module` 200, `question` 4,000, each option and answer 1,000, at most
  12 options; `questionKey` is 64 lowercase hex characters and `answerKey` 1 to 1,000 characters. Anything else returns `400` for the whole
  request.
- `already_known` and `conflict` compare `answerKey` with the approved entry's answers for the same identity.
- Only answers the user confirmed in AutoPilot (saved with "Save and continue" or reviewed after an attempt) are
  submitted. AutoPilot never submits a guess.

## Reviewing submissions (Cosmos website)

Admin only (`role = admin`), checked on the server for every request.

| Route | Purpose |
| --- | --- |
| `GET /api/qa/submissions?status=pending&bank=&page=&pageSize=` | Paginated list; `pageSize` at most 50 |
| `POST /api/qa/submissions/:id/approve` | Approves: creates the entry or adds the answer; bumps the revision |
| `POST /api/qa/submissions/:id/reject` | `{ "reason"?: string }` (up to 500 characters) |
| `POST /api/qa/entries/:id/retire` | Withdraws an approved entry; bumps the revision |

The list response:

```json
{
    "submissions": [
        {
            "id": "s_51…",
            "status": "pending",
            "bank": "smy",
            "bankName": "sagarmeinyog-ver-1.0.json",
            "module": "Module 3",
            "question": "What is the minimum …?",
            "options": [{ "position": 1, "text": "Two" }, { "position": 2, "text": "Three" }],
            "answer": { "text": "Three", "answerKey": "three" },
            "submitterName": "Jagdeep",
            "learnedAt": "2026-11-28T08:55:10.000Z",
            "submittedAt": "2026-11-28T09:01:00.000Z",
            "appVersion": "1.1.0+build.165.ga542753",
            "rejectionReason": null,
            "conflict": { "entryId": "e_8f2c…", "approvedAnswers": [{ "text": "Two", "answerKey": "two" }] }
        }
    ],
    "page": 1,
    "pageSize": 20,
    "total": 1
}
```

`conflict` is `null` unless an approved entry with the same identity has different answers. Approve, reject and retire
return `200` with the updated submission (or `{ "entry": { "id", "status", "revision" } }` for retire). A list item
never shows the submitter's email or tokens.

## Correcting the database (admin)

Admins browse every bank on the Cosmos website, find problems and correct entries in place. A correction is an ordinary
entry change: it gets a new `revision`, so every app picks it up on its next sync.

| Route | Purpose |
| --- | --- |
| `GET /api/qa/entries?bank=&q=&problem=&status=&page=&pageSize=` | Search entries (question text contains `q`, case-insensitive); `pageSize` at most 50 |
| `GET /api/qa/entries/:id` | One entry with its problems and its change history |
| `PATCH /api/qa/entries/:id` | `{ "answerPositions": [2], "note"?: string }`: the correct answers, by option position; at least one, each an existing option |
| `POST /api/qa/entries/:id/retire` · `POST /api/qa/entries/:id/restore` | Withdraw an entry, or bring it back |
| `GET /api/qa/problems?bank=&kind=&page=&pageSize=` | Entries with problems, newest first, with counts per kind |

Problem kinds, computed by Cosmos with the text rules above:

| `kind` | Meaning |
| --- | --- |
| `no_answer` | The entry has no answer |
| `answer_not_in_options` | An answer's `answerKey` is not `normalizedOption` of any option |
| `duplicate_options` | Two options have the same `normalizedOption` |
| `conflicting_answers` | Another entry with the same `questionKey` (in another bank name, module or bank) has a different set of answers |
| `pending_conflict` | A pending submission disagrees with this entry's approved answers |

- A correction stores each answer as the chosen option's `text` (with `optionText` applied) and `answerKey =
  normalize(that text)`, records who changed what and when (with the note), and marks the entry **authoritative**:
  `"authoritative": true` in `Entry`. Retire and restore are recorded the same way.
- **Authoritative entries win in the app.** When AutoPilot applies an entry with `"authoritative": true`, its answers
  replace every local answer for that `(bankName, module, questionKey)`, whatever their source (bundled, learned or
  earlier Cosmos rows). Entries without it keep today's rule: bundled and learned answers win.
- Approving a submission also makes the entry authoritative.
- **Disagreeing with a correction.** If a user confirms an answer that differs from an authoritative entry, the app
  uses it on that computer and still submits it; Cosmos answers `conflict` and the admin sees both answers in Q&A
  review, to keep the correction (reject) or change it (approve). Automatic saves never do this: only answers the
  user confirmed.

## Seeding the server

`npm run qa:import -- --bank <key> --file <path to the AutoPilot .db>` (Cosmos backend, admin machine only) imports a
bundled AutoPilot database as approved `bundled` entries. It is idempotent: an entry that already exists keeps its
revision unless its options or answers changed. It reads `qa_questions` (`id`, `bank_name`, `module`, `question_text`,
`question_key`), `qa_options` (`question_id`, `position`, `option_text`) and the `qa_answers` rows (`question_id`,
`answer_text`, `answer_key`) whose `source` is `'source'`; learned answers in a file are never imported. Entries an admin has corrected (`authoritative`) are
never changed by an import, so a re-import cannot bring back a corrected bundled answer.

## Errors

Errors are `{ "error": "<sentence a person can read>" }`, never a stack trace or SQL.

| Status | When |
| --- | --- |
| `400` | Malformed body or query (unknown fields rejected) |
| `401` | Missing, expired or revoked token; AutoPilot refreshes once, then asks the user to sign in again |
| `403` | Signed in but not allowed (review routes for non-admins) |
| `404` | Unknown bank, submission or entry |
| `409` | Approving a submission that is no longer pending |
| `429` | Rate limited; AutoPilot waits for `Retry-After` |
| `5xx` | Server trouble; AutoPilot retries later with back-off and keeps working offline |

## Rules both sides follow

- **Opt-in and offline-first.** AutoPilot works fully without Cosmos; sync failures never pause a course routine.
- **Never guess.** Cosmos serves only admin-approved answers; AutoPilot still pauses on any question it cannot
  match exactly.
- **Least data.** Submissions carry the question, options, chosen answer, module, times and app version. No page
  addresses, course-site accounts, screenshots or other page text.
- **Contract changes** are made here first, then in Cosmos (routes, validation, tests) and AutoPilot (client,
  tests) in the same piece of work.
