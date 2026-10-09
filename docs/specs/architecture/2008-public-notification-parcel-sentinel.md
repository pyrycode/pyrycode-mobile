# #2008 — Public notification parcel privacy sentinel

## Files read

- `app/src/test/java/de/pyryco/mobile/notifications/AttentionNotifierTest.kt`: `previewsArePrivateAndPublicVersionsContainOnlyFixedCopy` checks private previews, fixed public copy, public extras, serialized parcels and the ledger.
- `docs/knowledge/features/push-messaging-service.md`: notification privacy coverage includes built private/public notifications and content-free persistence.
- `docs/knowledge/features/development-verification-gates.md`: focused Robolectric tests run with `testDebugUnitTest`.

## Change

Replace only the serialized-parcel sentinel `/private` with the fixture's exact `/private/secret` path. Robolectric can serialize its local `/private/tmp/.../app/build/...` resource path, so the generic prefix is not evidence of preview leakage. Keep both encodings, all other sentinels and the public extras, fixed-copy, private-visibility and ledger assertions unchanged. No production code, state, exported symbols or consumer changes are needed. No in-flight feature branch overlaps the test. Written work is one changed test line plus this short plan, below every sizing boundary.

## Testing strategy

Run the unchanged privacy method first and inspect fresh JUnit XML for its executed count and assertion failure. After the sentinel correction, rerun that method and the complete `AttentionNotifierTest` class, recording executed/passed counts. Run lint, assemble and formatting checks, then merge main, push and run the final assemble and `scripts/pre-verify.py --gradle` gates. The dispatcher owns the full unit/shared suite required by the second acceptance criterion; its result remains pending at builder handoff.
