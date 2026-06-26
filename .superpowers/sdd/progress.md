# Phase 6 — Kotlin mobile delta client + rebrand (spk-editor-mobile)

Plan: ../../sawe/docs/superpowers/plans/2026-06-26-phase6-mobile-delta-client.md
Spec: ../../sawe/docs/superpowers/specs/2026-06-26-mobile-delta-sync-design.md §Client
Branch: phase6-mobile-delta-sync (off main). Commit-per-task. NEVER push. NO Co-Authored-By.
BASE (phase start): main @ 40b3b7d

Execution: subagent-driven-development (TDD implementer + per-task opus review + whole-phase opus review).

Task order (recommended, pending user confirm rebrand-first vs delta-first):
- Task R : app-identity rebrand → "Sawe Mobile" / ru.sipaha.sawe.* + legacy scheme выпил — mechanical — complete (commits 70564fb..aea056b, opus review Spec ✅ no Crit/Imp; 3 Minor stale-doc nits fixed in aea056b). Build: :core:test 348 green, :app:assembleDebug SUCCESSFUL, PairingUrlTest 22 (2 legacy cases deleted), HMAC vectors green. spkremote dir = empty git-mv cruft (untracked).
- Task 1 : :core delta DTOs (GetSessionChangesResult, epoch/currentSeq on GetSessionResult) + RemoteClient.getSessionChanges — TDD — complete (commit ad518dd, opus review Spec ✅ CLEAN no findings; cross-checked field names vs server struct). 5 tests, :core:test 353/353. absent-vs-empty proven via explicitNulls=false + test 3.
- Task 2 : pure delta applier applySessionDelta in :core — TDD — complete (commit 624d08e, opus review Spec ✅ CLEAN). 9 tests incl. drop-by-count case 7. SessionDeltaState holder added. Total fn (coerceIn safety net, reset = documented caller precondition). MINORS for final triage: (1) theoretical Int overflow in totalCount subtraction (unreachable, small counts); (2) step comments restate KDoc order (borderline vs no-summary-comment rule).
- Task 3 : CachedSessionHistory stores (epoch, lastSeq), schemaVersion 2 — complete (commits cb798e3 + fix 610b095, opus re-review Spec ✅ clean). Robolectric Keystore-shadow gap → schema gate proven via 7 pure JUnit serialization round-trip tests (encodeDefaults=false legacy-key-absent bug found+fixed: default sentinel 1 + writeNow stamps CACHE_SCHEMA_VERSION=2). :app:testDebugUnitTest 31 green. ACCEPTED GAP (manual-verify): EncryptedSharedPreferences file-IO + eviction side-effect not automated (platform constraint, brief-permitted).
- Task 4 : [MERGED 4+5] rewrite SessionDetailStore read path — cache-first delta open + SINGLE-WRITER applier publish + push→debounced poll triggers + delete resumeSession(after_index)/fetchAndReplaceEntry/heal/resync + sweep server-protocol legacy merge code. opus impl (heavy integration w/ concurrency invariants) — PENDING. (Split into 4+5 abandoned: interim dual-writer = the exact bug being fixed.)
- Final  : whole-phase opus review + device-verify handoff — PENDING
