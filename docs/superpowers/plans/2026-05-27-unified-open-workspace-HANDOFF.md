# Unified Open-Workspace — Handoff for Next Session

**Date:** 2026-05-27
**Author:** previous-session agent
**Purpose:** Snapshot of work-in-progress across two repos so the next session can resume without re-reading the whole conversation history. Pairs with the design spec and the two implementation plans.

---

## TL;DR

- **Plan 1 (desktop wire, Rust) is COMPLETE and tagged.** 21 commits on `feature/unified-workspace-wire` in `spk-editor`, tag `workspace-wire-desktop-done`.
- **Plan 2 (mobile, Kotlin/Compose) is STARTED.** Phase A (test infrastructure) is complete and proven. Phases B–H remain.
- **The agent's self-test loop works:** Robolectric + Roborazzi render Compose composables to PNG files; the next session can `Read` those PNGs to visually verify UI changes without an emulator.

---

## Where things live

### Repos

Both repos live inside a single solution at `/home/spk/.spk/spk-editor/solutions/spk-solutions/`. Each has its own `.git`.

| Repo | Path | Role |
|---|---|---|
| `spk-editor` | `spk-solutions/spk-editor/` | Rust desktop (Plan 1 work) |
| `spk-editor-mobile` | `spk-solutions/spk-editor-mobile/` | Kotlin/Compose mobile (Plan 2 work + `:cli`) |

### Documents (all inside spk-editor-mobile)

- **Spec** — `docs/superpowers/specs/2026-05-27-unified-open-workspace-design.md` — the design contract (9 sections; sequenced consistency protocol; eventual-consistency metrics; wire shape; mobile UI layout; testing strategy).
- **Plan 1 (desktop)** — `docs/superpowers/plans/2026-05-27-unified-open-workspace-plan1-desktop.md` — Rust task list (DONE; reference only).
- **Plan 2 (mobile)** — `docs/superpowers/plans/2026-05-27-unified-open-workspace-plan2-mobile.md` — Kotlin task list (Phase A done, B–H to do). **This is the live document for the next session.**
- **This handoff** — current file.

---

## Branches and tags

| Repo | Branch | State | Commits | Tag |
|---|---|---|---|---|
| `spk-editor` | `feature/unified-workspace-wire` | merge-ready | 21 | `workspace-wire-desktop-done` |
| `spk-editor-mobile` | `feature/cli-workspace-smoke` | merge-ready | 2 (cli + Plan 2 doc) | — |
| `spk-editor-mobile` | `feature/unified-workspace-mobile` | in progress | 3 (handoff + Plan 2 deps + sanity test) | — |

`feature/unified-workspace-mobile` is branched off `feature/cli-workspace-smoke`, so the `:cli` `workspace.*` subcommands ride along with the mobile UI work. The next session continues on this branch.

---

## Plan 1 — desktop summary (DONE)

### Phases delivered

- **A** — `workspace_events` crate scaffold + atomic seq counter + `emit_sequenced` helper + `init` wired in `crates/zed/src/main.rs`.
- **B** — Wire-breaking renames: `SolutionSummary.window_open` → `open`; `solution_agent.close_session` → `solution_agent.delete_session`. Schema bumped 1 → 2.
- **C** — `workspace.snapshot` tool with both filters (`open` solutions only, sessions with `tab_order IS NOT NULL` only).
- **C-refactor** — `SolutionStore::open_solutions: HashSet<SolutionId>` runtime field with `mark_open` / `mark_closed` / `is_open`; window lifecycle hook in `event_sources.rs` keeps it in sync. Replaced the prior live-window enumeration so tests can flip the bit directly via `mark_open`.
- **D** — `workspace.list_solutions(open: Option<bool>)` for the closed-solutions picker.
- **E** — Four lifecycle tools: `workspace.open_solution`, `close_solution`, `open_session`, `close_session`. All idempotent at the store level.
- **F** — Sequenced delta emit hooks in `solutions` and `solution_agent` mutation paths. `WorkspaceEventCoordinator` relocated from `crates/workspace_events/` to `crates/editor_mcp/src/workspace_seq.rs` so both `solutions` and `solution_agent` can call into it without dep cycles. Throttled `workspace.session_metrics_changed` (non-sequenced) wired into token/activity updates.
- **G** — `RwLock<()>` on the coordinator: `emit_sequenced` takes the write side; `build_snapshot` takes the read side. Guarantees that the seq returned by `workspace.snapshot` reflects the exact state in the response.
- **H** — `close_solution` cancels in-flight `AcpThread`s. **Terminal kill is a documented gap** — there is no per-solution terminal registry in this codebase; logged via `log::warn!`. To close the gap, a future task would add a `SolutionId` tag on terminals + a "kill all terminals for solution X" API in `crates/terminal/`.
- **I** — `:cli` smoke subcommands for the 6 `workspace.*` tools.
- **J** — Full-lifecycle round-trip e2e test + tag `workspace-wire-desktop-done`.

### Test status

| Crate | Tests passing |
|---|---|
| `workspace_events` | 16 |
| `editor_mcp` | 17 |
| `solutions` | 144 |
| `solution_agent` | 199 |

`cargo check --workspace` is clean (pre-existing warnings in `agent_ui` are unrelated).

### Documented Plan 1 deviations

1. **`workspace.solution_opened` from `mark_open` ships `sessions: []`.** Reason: `solutions` crate cannot depend on `solution_agent` (cycle). When the desktop user opens a solution, the mobile client receives the notification with the solution but no sessions inside; it must call `workspace.snapshot` (already part of the resync flow) to fill them in. Mobile-initiated `workspace.open_solution` is unaffected — it goes through `lifecycle::open_solution_impl` which has access to both stores and can populate sessions. This is documented in the Plan 1 closing report.

2. **Terminal kill on `close_solution` is a no-op + `log::warn!`** (described above). Agents are killed; terminals survive. The user is informed via warning in the server log. Closing the gap is a follow-up task in the `terminal` crate.

3. **Internal `SolutionAgentStore::close_session` method name is decoupled from the wire tool name.** The wire was renamed (`close_session` → `delete_session`); the internal Rust API kept its old name. Aligning is a follow-up cosmetic cleanup, not load-bearing.

---

## Plan 2 — mobile progress so far

### Phase A (DONE)

| Task | Status | Files |
|---|---|---|
| A1 — Robolectric + Roborazzi deps | ✅ | `gradle/libs.versions.toml`, `app/build.gradle.kts` |
| A2 — Sanity test renders Compose to PNG | ✅ | `app/src/test/kotlin/.../RoborazziSanityTest.kt`, `app/src/test/snapshots/roborazzi/RoborazziSanityTest_*.png` |

### Critical adaptations discovered during Phase A

(Capture in next session's mental model — Plan 2's task text was written before these constraints were known.)

1. **Roborazzi version pinned to `1.43.1`.** `1.49.0` (the version named in Plan 2's text) does not exist on Maven Central. `1.43.1` is the latest stable; `1.44+` are alpha. **All Plan 2 task references to `1.49.0` should be read as `1.43.1`.**

2. **Roborazzi Gradle plugin is NOT applied** — incompatible with AGP 9 (references `TestedExtension` removed in AGP 8). The plugin entry was kept out of `libs.versions.toml` `[plugins]` and out of `app/build.gradle.kts` `plugins { ... }`. We use the **library APIs** (`captureRoboImage(...) { composable }` standalone form) inside JUnit tests instead.

3. **`captureRoboImage` API shape:** the form named in Plan 2 D3 (`composeRule.onRoot().captureRoboImage(filePath = ...)`) does NOT exist in `roborazzi-compose:1.43.1`. The actual API is:
   ```kotlin
   captureRoboImage(
       filePath = "src/test/snapshots/roborazzi/Name.png",
       roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record),
   ) {
       MaterialTheme { /* Composable */ }
   }
   ```
   It launches its own Activity internally — no `createComposeRule()` needed for snapshot tests. Plan 2 D3 / E1 snapshot tests should be rewritten in this shape.

4. **Compose BOM in `testImplementation` is required.** Added `testImplementation(platform(libs.compose.bom))` so `compose-ui-test-junit4` resolves its transitive Compose UI version. Without this you get "could not resolve" errors for `androidx.compose.ui:ui-test`.

5. **`roborazzi-core` must be explicit.** `RoborazziOptions` and `RoborazziTaskType` aren't on the compile classpath via `roborazzi-compose` alone. Added `testImplementation(libs.roborazzi.core)` and a corresponding `[libraries]` entry.

6. **JUnit Vintage Engine bridge.** Project tests run on JUnit Platform (`useJUnitPlatform()`). Robolectric's `@RunWith(RobolectricTestRunner::class)` is a JUnit 4 idiom — invisible to JUnit Platform by default, tests silently skipped. Fix: `testRuntimeOnly(libs.junit.vintage.engine)` (added in A2).

7. **`@OptIn(ExperimentalRoborazziApi::class)`** is required where `RoborazziTaskType` is used. Annotate at class level in snapshot tests.

8. **Record vs compare mode.** Without the Gradle plugin there's no automatic state machine. The sanity test pins `RoborazziTaskType.Record`. For Plan 2 D3 / E1 / H, set `Record` initially, eyeball the PNGs via `Read`, then flip to `Compare` for CI / regression mode. The mode can be driven by a JVM system property or env var if desired — defer until baseline goldens are stable.

### Phase B–H (TODO)

Sketch (full task text in `docs/superpowers/plans/2026-05-27-unified-open-workspace-plan2-mobile.md`):

| Phase | Tasks | Size | What |
|---|---|---|---|
| **B** | B1, B2, B3 | small | `windowOpen` → `open` DTO rename · `supportedWireSchemaVersion` 1 → 2 · add 11 `workspace.*` DTOs + serde round-trip tests |
| **C** | C1–C6 | large | `WorkspaceStore` data layer: skeleton → bulk loader → delta protocol (seq gap → resync) → metrics + closed-list → real wire adapter + notification dispatch → optimistic UI with rollback |
| **D** | D1, D2, D3 | medium | `WorkspaceScreen` skeleton → composables (sticky section header, session row with `⋮`, FAB, empty state) → Roborazzi goldens |
| **E** | E1 | small | `ClosedSolutionsPickerSheet` (ModalBottomSheet) + golden |
| **F** | F1 | small | Nav graph swap to `workspace/*` routes + `NavStateRepository.loadSavedRoute()` migration of legacy `solutions/{id}` / `solutions/{id}/sessions/{id}` paths |
| **G** | G1 | small | Delete `SolutionsListScreen`, `SolutionDetailScreen`, mobile `SolutionStore` + update consumers |
| **H** | H1 | small | Full JVM test run, Roborazzi compare-mode pass, optional emulator smoke, tag `workspace-mobile-done` |

### Recommended order on resume

1. **B1 + B2 + B3 together** — quick wire-side updates, no risk. Get them out of the way.
2. **C1 → C6 sequentially** — the meat of the work. Each task is TDD with concrete code in the plan. Watch for the C5 step where you must look at how `SolutionStore` / `SessionListStore` call `RemoteClient.callTool<T>(name, params)` and mirror it — don't invent a new API.
3. **D1 → D2 → D3** — UI composables; use `Read` on the Roborazzi-recorded PNGs to verify each commit.
4. **E1**, **F1**, **G1** — fold these in any order; they're small.
5. **H1** — acceptance + tag.

### Re-using the Roborazzi rig

The `RoborazziSanityTest` is the template. Each new snapshot test should:

- Be annotated with `@RunWith(RobolectricTestRunner::class)`, `@GraphicsMode(GraphicsMode.Mode.NATIVE)`, `@Config(sdk = [33], qualifiers = "w360dp-h640dp-xhdpi")`.
- Use `@OptIn(ExperimentalRoborazziApi::class)` if `RoborazziTaskType` is referenced.
- Call `captureRoboImage(filePath = "src/test/snapshots/roborazzi/<TestName>_<methodName>.png", roborazziOptions = ...) { /* Composable */ }`.
- Have golden PNG committed under `app/src/test/snapshots/roborazzi/`.

The next session's first move per UI task: **record the PNG with `RoborazziTaskType.Record`, then `Read` it to confirm the screen looks right, then commit both code and PNG together.**

---

## Open architectural notes (worth flagging again)

- **Terminal kill gap on `close_solution`** (Plan 1 H). Real product gap that the user explicitly asked for. Not part of Plan 2 — needs a separate Phase or a Plan 3 in the `terminal` crate.
- **`solution_opened` from desktop-initiated `mark_open` has empty `sessions`.** Mobile `WorkspaceStore.onSolutionOpened` already handles this — when `solution` is non-null but `sessions` is empty, the snapshot reflects the new solution with zero sessions; the next bulk-refetch (or a subsequent `session_opened` delta from `persist_tab_order`) fills in sessions. Document in `WorkspaceStore` KDoc so a future maintainer doesn't get confused.
- **Optimistic UI rollback** (Plan 2 C6) only fires on RPC ack failure. If the desktop accepts the RPC but the delta later contradicts the optimistic mutation (highly unusual), the snapshot self-corrects on the next bulk-refetch — but in the brief window the UI may show a stale optimistic state. Acceptable for v1; revisit if user reports diverge.

---

## How to resume in the next session

1. **First, scan this document.** It has the deltas the plan file doesn't know about.
2. **Then read `docs/superpowers/plans/2026-05-27-unified-open-workspace-plan2-mobile.md`** for the bite-sized task text.
3. **Verify branches:**
   ```bash
   git -C /home/spk/.spk/spk-editor/solutions/spk-solutions/spk-editor branch --show-current
   # expected: feature/unified-workspace-wire

   git -C /home/spk/.spk/spk-editor/solutions/spk-solutions/spk-editor-mobile branch --show-current
   # expected: feature/unified-workspace-mobile
   ```
4. **Verify desktop tests still green** (quick sanity):
   ```bash
   cd /home/spk/.spk/spk-editor/solutions/spk-solutions/spk-editor
   cargo test -p workspace_events 2>&1 | tail -3
   # expected: 16 passed
   ```
5. **Verify mobile rig still works:**
   ```bash
   cd /home/spk/.spk/spk-editor/solutions/spk-solutions/spk-editor-mobile
   ./gradlew :app:testDebugUnitTest --tests RoborazziSanityTest 2>&1 | tail -5
   # expected: PASS, PNG exists
   Read app/src/test/snapshots/roborazzi/RoborazziSanityTest_rig_renders_a_text_and_writes_png.png
   # expected: image shows "Roborazzi sanity OK" text on white surface
   ```
6. **Start Phase B**, then C, ... following the plan. Use the subagent-driven-development skill — dispatch a fresh implementer per task with full task text + the relevant adaptations from this handoff (especially the Roborazzi API shape for any UI snapshot task).
7. **Memory:** there's a long-standing memory note about "autonomous git in this project" — non-destructive commits and pushes don't need per-action approval. The user explicitly chose autonomous execution for this work.

---

## Files quick-reference

### spk-editor (Plan 1, complete)

Most relevant for the next session if Plan 2 reveals a wire-side gap:

- `crates/workspace_events/src/{coordinator.rs,snapshot.rs,list.rs,lifecycle.rs,shutdown.rs,dto.rs,mcp.rs,workspace_events.rs}` — the new crate.
- `crates/editor_mcp/src/workspace_seq.rs` — `WorkspaceEventCoordinator` (relocated home).
- `crates/solutions/src/store.rs` — `mark_open` / `mark_closed` / `delete_solution` emit hooks. `open_solutions: HashSet`.
- `crates/solutions/src/event_sources.rs` — window lifecycle subscription that calls `mark_open` / `mark_closed`.
- `crates/solution_agent/src/store.rs` — `close_session` (delete-semantics; emits `workspace.session_deleted`); `persist_tab_order` (emits open/closed deltas); state-change emit helper. `MetricsEmitter` field.
- `crates/solution_agent/src/metrics_emitter.rs` — throttler for `session_metrics_changed`.
- `crates/editor_mcp/src/tools/capabilities.rs:89` — `wire_schema_version: 2`.

### spk-editor-mobile (Plan 2, in progress)

- `core/src/main/kotlin/ru/sipaha/spkremote/core/RemoteDtos.kt` — `SolutionSummary` has `windowOpen` field today; B1 will rename to `open`; B3 will add `workspace.*` DTOs.
- `app/src/main/kotlin/ru/sipaha/spkremote/app/vm/` — VMs. `SolutionStore.kt` will be deleted (G1). New `WorkspaceStore.kt` + `WorkspaceClientImpl.kt` land in Phase C.
- `app/src/main/kotlin/ru/sipaha/spkremote/app/ui/solutions/{SolutionsListScreen,SolutionDetailScreen}.kt` — both will be deleted (G1) and folded into the new `app/ui/workspace/WorkspaceScreen.kt`.
- `app/src/main/kotlin/ru/sipaha/spkremote/app/ui/nav/AppNavGraph.kt` — F1 swaps `solutions/*` routes for `workspace/*`.
- `app/src/main/kotlin/ru/sipaha/spkremote/app/data/NavStateRepository.kt` — F1 adds saved-route migration.
- `gradle/libs.versions.toml` — Phase A's added entries.
- `app/build.gradle.kts` — Phase A's test deps.

---

## Quick context for a new agent reading this cold

You are continuing work on a feature called "Unified Open-Workspace" — a single mobile screen on Android that mirrors the desktop's open-solution tab bar. The mobile client (`spk-editor-mobile`) is the official remote control for a desktop coding tool similar to Claude Code (`spk-editor`). The wire protocol is custom JSON-RPC over WebSocket + TLS pinning + HMAC handshake, served by the desktop and consumed by the mobile.

The feature ships in two halves: a desktop wire change (Plan 1, **done**) and a mobile UI rebuild (Plan 2, **Phase A done**). Each half is its own implementation plan. The design spec is one document.

Your role: continue executing Plan 2 task-by-task using the subagent-driven-development skill. Each task in the plan is bite-sized (TDD where applicable). You dispatch a fresh subagent per task, then a spec reviewer + code-quality reviewer. The Roborazzi rig that's already wired lets you verify UI changes visually by `Read`ing the generated PNG files.
