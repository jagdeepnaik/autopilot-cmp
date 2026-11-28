# Cosmos Q&A sync — AutoPilot side

Date: 28 November 2026. Contract: [Q&A sync API](../docs/shared/01_QA_SYNC_API.md) (owned by the Cosmos hub).
Validation here is against fakes only: the Cosmos server wasn't deployed, so no request reached a real server.

## What was built

| Module | Files | Role |
| --- | --- | --- |
| `core:persistence` (jvmMain) | `qa/QuestionAnswerSchema.jvm.kt`, `qa/SqliteQuestionAnswerStore.jvm.kt` | Routine file schema v2, in-place upgrade, remote apply/retire, pending uploads with stable `client_id` |
| `core:persistence` (jvmMain) | `preferences/SystemSecurePreferencesStore.jvm.kt` | `SecurePreferencesStore<T>` on macOS Keychain (`security`, value on stdin), Windows DPAPI (JNA), Linux `secret-tool`; no fallback |
| `feature:shared` | `cosmos/CosmosSync.kt` | Contract Settings uses: state, sign in/out, sync now, answers changed |
| `feature:home` (commonMain) | `domain/cosmos/CosmosApi.kt`, `domain/cosmos/CosmosSyncEngine.kt` | Wire models, token handling (401 → refresh once → sign-in), back-off, `Retry-After`, paging, batching |
| `feature:home` (jvmMain) | `data/cosmos/KtorCosmosApi.kt`, `data/cosmos/SqliteCosmosQaStore.kt`, `data/SqliteCourseAnswerBanks.kt` | Ktor HTTPS client; bank → routine file mapping; signal after confirmed saves |
| `feature:settings` | `SettingsViewModel.kt`, `ui/components/CosmosSyncSection.kt`, `ui/util/CosmosSyncText.kt`, `SettingsScreen.kt` | Cosmos sync rows: sign-in form, status, pending count, Sync now, Sign out |
| `shared` | `platform/CosmosSyncFactory*.kt`, `AppVersion.jvm.kt`, `di/AppServices.kt`, `App.kt`, `AppNavHost.kt`, `build.gradle.kts` | Wiring, CIO engine, `cosmos/cosmos.properties` generated from `cosmosUrl` |

## Database upgrade

Shipped routine files limit `qa_answers.source` to `('source','learned')` with a CHECK constraint. On first open
the app writes `<file>.before-v2.bak` (`VACUUM INTO`, never overwriting an existing backup), then in one
`BEGIN IMMEDIATE` transaction adds `compass_entry_id`, `compass_revision`, `compass_created` to `qa_questions`,
rebuilds `qa_answers` with the wider CHECK plus `compass_revision`, `client_id`, `submitted_at`, `submit_status`, `confirmed_at`
(row count checked before the old table is dropped), creates `sync_state` and sets `user_version = 2`.
A fresh copy of a bundle is upgraded without a backup. `build_qa_database.py` now writes the v2 schema; the
bundled `.db` files were **not** rebuilt (they upgrade on first open).

## Contract revision (same day)

The contract was revised after the first pass: identity is now `(bank, bankName, module, questionKey)` with the
files' own keys, options are `{position, text}`, answers `{text, answerKey}`, and the previous refresh token is
accepted for 60 seconds. The client now:

- stores entries under their `bankName` and `questionKey`, and skips an entry whose `questionKey` doesn't equal the
  key the file would compute for its question and options (so exact lookups keep working);
- submits `bankName`, `questionKey` and `answerKey` from the file;
- retries a refresh at most once with the same token (a lost reply), never a third time;
- uploads only answers with `confirmed_at` set ("Save and continue" or the post-attempt review). Practice-quiz
  feedback, legacy JSON imports and answers learned before this change have none, so they are not uploaded.

### Resolved: `answerKey` is normalised text

`qa_answers.answer_key` is the normalised answer text, not a hash. The contract now says so (1–1,000 characters;
`questionKey` stays 64 lowercase hex), and `CosmosSubmission.keysFitContract()` checks exactly that. A submission
outside those limits stays pending on the computer instead of being given up.

### Other notes

- The bundled keys were computed by the Python builder (`casefold()`), the app uses `lowercase()`; for the rare
  text where they differ, an entry's key won't match the app's recomputation and is skipped rather than stored.

## Not verified

- No request to a real Cosmos server (not deployed); TLS through the packaged jlink runtime is untested.
- Windows DPAPI and Linux Secret Service backends compile but were not run (no Windows/Linux run here). The macOS
  Keychain backend passed its contract test on this Mac.
