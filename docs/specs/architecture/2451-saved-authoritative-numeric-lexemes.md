# #2451 — preserve opaque saved JSON numbers

## Confirmed defect and scope

The #2449 safety run on0a8f85137b executed338JVM methods:337passed/1failed/0errors/skips, native0. Its new unknown-number/immutability test failed saved-value equality. The pinned unmodified kotlinx JSON1.8.1 library alone reproduces parsed [-0,1e-400,1.234567890123456789] encoding as [0,0.0,1.2345678901234567]; FileConversationCache uses the same JsonElement serializer. Retain full failure/XML/source (per-fact-green-0a8f, manifestf4ec7a7575c857f5a9d09caa985d32286694e2b4dbb3421d0d241ee1593e8196) and library-only probe/source/jar hashes(numeric-encoding-diagnosis). This semantic defect is separate from2449's timing/heap work. Protected child2451 is held and blocks2449/2447/2437/2334; actualparent2449 verified. No parent scope grows.

## Bounded mechanism

Change only authoritative saved-items encoding in SavedAuthoritativeThread. Before serializing each fact, validate its entire JSON tree with the existing strict validThreadJson predicate. Reuse the iterative frozen-tree walker with an explicit encoding mode that wraps only non-string, non-null, non-boolean numeric tokens in JsonUnquotedLiteral. The guard first proves every such token matches the existing JSON number grammar; the encoder never gets unvalidated raw text. Preserve strings and all structural JSON containers through ordinary serializers. Serialize one transformed fact at a time using the same list/element descriptors, avoiding another full collection copy. Do not change MobileJson globally, read decoding, schema/version, known routing type checks, ownership/memo or repository/generation/reader logic. Keep ordinary encoding validation before persistence and all mutation/unknown-null checks.

## Test first

The retained failing real disk round trip precedes this production repair. Strengthen its diagnostic order to compare raw fields before redacted Saved equality. Add focused disk cases for huge integers, large positive/negative exponents, signed zero, high precision and nested number-like strings; require exact unknown raw values after fresh disk decode/reopen. Reject NaN/Infinity, invalid lexical tokens and structure-injection strings presented as unquoted numbers; failed writes retain previous bytes. Known id/rev/order strings or out-of-range values remain invalid. Execute these cases before the production encoding change and retain their red outcomes, then rerun affected cache/decoder checks and unchanged six native methods. Independent complete configured Linux gates, actual documentation and reviewed merge into feature/2449 remain required; no closure accepts the parent stack.

## Security review

**Verdict: PASS** — builder self-review of the proposed bounded mechanism,2026-10-22. JsonUnquotedLiteral is intentionally limited to a token already validated by the complete existing JSON tree grammar. Null/booleans remain normal typed primitives; strings never enter this numeric path, so quotes/braces/commas cannot be injected. All keys/containers and string escaping stay under the normal encoder. No floating-point conversion, global trust cache, owner/schema/lifecycle change, endpoint, credential or raw-user logging. Independent review must verify the guard dominates every raw-token construction, malformed inputs cannot write, and all raw lexemes/unknown nulls/known-field restrictions remain exact. Existing source alias defenses remain binding.

## Handoff

Record final accepted-source evidence in authoritative-reader-paging.md. Preserve the prior337/338 result and all timing/OOM/count failures. Parent2449 still needs its own performance acceptance, then2447/2437/2334 each need current acceptance, documentation and actual merge/Done. Intended childbasefeature/2449/headfeature/2451; normal review is mandatory.

## Confirmed focused red

Before the production encoding change, a7f5c0562c selected/executed3 focused methods:1passed/2failed/0errors/skips. Both exact raw comparisons showed -0 becoming0; the original nested-array case also showed1e-400→0.0 and decimal rounding. The malformed-number/injection/known-field/previous-bytes probe passed unchanged. Fresh XML/log/source/plan plus standalone pinned1.8.1 probe/JAR hashes/serializer bytecode are retained in logs/evidence/2451/manual-20261022/red-a7f5, manifestb51808de532f37f6519bd6f8dcd9efbdb9f73ce29db703f65de48007f04b1038. The fix adds a saved-fact-only encoding serializer and a numeric mode on the existing iterative walker; both complete-fact validation and a direct primitive validation dominate each JsonUnquotedLiteral construction. Current repaired checks and independent acceptance remain pending.
