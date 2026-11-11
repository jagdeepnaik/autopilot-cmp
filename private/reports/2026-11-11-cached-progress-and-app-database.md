# Cached progress and consolidated persistence — 11 November 2026

## Behavior

Sagar confirmed video endings and complete module-video proofs are saved before navigation; observed assessment results are stored locally. The selected profile restores these records on restart. A saved test pass never implies video completion. Known module IDs take precedence over title matching; unknown or incompletely observed modules still require one course-page check.

Website history imports occur for an unverified profile, an explicit safe-boundary verification request, and final run verification. Per-module re-imports were removed. Progress processing and the end-of-run check are serialized so a pending save/final verification cannot be skipped. Normal shutdown waits for accepted progress writes. Existing profile confirmation remains for same-name account isolation.

## Persistence ownership

The original core AppRoomDatabase, original JVM Room builder, and shared builder configuration now own app usage and learning tables. Learning models, entities, DAO, and store are separate files. The standalone LearningProgressDatabase and its constructor were removed from runtime source; its old schema export remains as historical import documentation.

Transfer-history DAO/entity/repository and their now-obsolete test were removed after reference checks confirmed no production consumer. Routine JSON import/export uses separate feature contracts and remains intact.

The active database is ~/.autopilot/databases/autopilot.db. The previous course-progress.db is read consistently and imported transactionally into an empty destination before new writes. Source data is not deleted; existing destination history prevents stale re-import. No destructive Room fallback was introduced. Schema version remains 1 because the consolidated app file is newly created, rather than replacing the old learning file in place.

## Evidence

- Core persistence: 5 tests passed, including multiple-user durability, import-once behavior, preservation of newer destination records and DGS study details, invalid-import rollback, and absence of transfer tables in the consolidated schema.
- Home: 118 focused tests passed, covering cached restart, video/module skips, assessment results, no per-module progress navigation, safe refresh boundaries, storage failure, final save ordering, and DGS regressions.
- App shell/path: 14 tests passed. JVM app build succeeded.
- Actual Mac import verified: source and destination each contain 2 profiles and 165 records. The original source remains present; the consolidated database has no transfer-history table. Mac app rebuilt and restarted.
- No website assessment started or submitted. No new Windows/mobile validation is claimed for this batch. No commit/push performed.

Guide: private/docs/26_SAVED_COURSE_PROGRESS.md; index links updated.
