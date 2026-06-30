---
baseline_commit: 484f404
---

# Story 4.5: Escalation Threshold Re-notifications with Won't-Fix Volume Override

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a developer,
I want to be re-notified when a known error recurs at significant volume (10×, 100×, 1,000×) reusing the stored LLM analysis — no fresh LLM call — and a won't-fix error to nudge me only when it reaches an unusually high volume,
so that a bug that has become an operational incident surfaces again with the context I already have, without burning the local LLM budget.

## Acceptance Criteria

1. **Cooling escalation re-notification** (FR-11, FR-31). **Given** a cooling `DeduplicationRecord` (`wontFix = false`) whose `occurrenceCount` crosses a configured threshold (default 10, 100, 1000), **when** `PollService` increments the count, **then** the escalation prints exactly:
   ```
   ⚠️ Known error {HumanLabel} now seen {threshold}× since {firstSeenAt}
      Root cause: {storedAnalysis.rootCause | "no analysis on file"}
   ```
   **and** `lastNotifiedThreshold` is updated to the fired threshold, **and** **no LLM call is made** (`storedAnalysis` is reused directly).
2. **Each threshold fires once per window** (FR-11). **Given** the same cooling record, **then** each threshold fires **exactly once per `DeduplicationWindow`** as the count climbs (10× fires, then 100×, then 1000×) — never twice. Tracked via `lastNotifiedThreshold`; a fresh window (new/recreated record) resets it to `null` (so the ladder restarts).
3. **Highest-not-yet-fired, one per increment** (FR-11, spec `CheckEscalationThreshold`). **When** the count is incremented, **then** the threshold fired is the **smallest** configured threshold that is **greater than** `lastNotifiedThreshold` (treating `null` as 0) **and** `≤ occurrenceCount`. Because each `ErrorLog` increments the count by exactly 1 and the default thresholds are far apart, at most one threshold is crossed per increment, so they fire in sequence.
4. **Won't-fix volume override** (FR-12). **Given** a won't-fix `DeduplicationRecord` (`wontFix = true`) whose `occurrenceCount` crosses the **1000** threshold, **when** `PollService` increments the count, **then** the override prints exactly:
   ```
   ⚠️ Won't-fix error {HumanLabel} now seen {threshold}× since {firstSeenAt} — volume is unusually high
   ```
   **and** `wontFix` is **NOT** cleared (read-only nudge — the developer must edit `suppression.txt` to change the decision), **and** no LLM call is made.
5. **Won't-fix fires only at 1000×** (FR-12). **Given** a won't-fix record, **then** the 10× and 100× thresholds do **NOT** print. (Per spec `CheckEscalationThreshold`/`DeliverEscalation`: `lastNotifiedThreshold` still advances silently through 10 and 100, but the line is delivered only when `wontFix = false` **OR** `threshold == 1000`.)
6. **Escalation fires during backlog catchup** (FR-13, FR-36). **Given** LogGuard is replaying a backlog (stale checkpoint or degradation recovery), **when** `occurrenceCount` crosses a threshold during replay, **then** the escalation fires in sequence as counts are reconstructed — **not** suppressed during catchup. (Falls out of per-`ErrorLog` increment + the once-per-window guard; **no special branch**.)
7. **Configurable thresholds, no fresh wiring beyond the constructor** (NFR-3). Thresholds come from `logguard.escalation-thresholds` (already bound on `LogguardProperties`, default `[10, 100, 1000]`, already present in `application.yml`). They are passed into `PollService` via its constructor (a **new** constructor parameter — wired in `DomainServiceConfig`). No new config key, no schema/migration change (`last_notified_threshold` column already exists, V2).
8. **All output routes through `TerminalOutputPort`** (AR-10, FR-31). Escalation lines are emitted via a new `TerminalOutputPort.printEscalation(...)` implemented only in `TerminalOutputAdapter`, using the pre-defined `ESCALATION` (`⚠️`) status constant — never an inline glyph (Story 2.4 AC). `PollService` makes no direct `System.out` call.
9. **No regressions.** The Story 4.2 cooling silent-increment + analysis caching (FR-25 "cache only successful analysis"), the Story 4.4 won't-fix-from-birth + `⚑` label + unsuppression + Fingerprint line, the Story 2.6 degradation/recovery, FR-26 zero-results silence, FR-29 ordering, and NFR-4 (analyse once per unique fingerprint) all remain green. `lastNotifiedThreshold` is never touched outside escalation; `wontFix` is never flipped by escalation.
10. **Tests.** `PollServiceTest` gains cases for: cooling crosses 10× (1 escalation, threshold value + reused analysis asserted, 0 LLM calls, `lastNotifiedThreshold=10`); each threshold fires once across increments (10×, then 100×; re-cross does not refire); won't-fix reaches 1000× (override prints once, `wontFix` stays true); won't-fix at 10×/100× prints nothing (but `lastNotifiedThreshold` advances); in-batch / backlog catchup crossing fires in sequence; a cooling record with `storedAnalysis == null` prints "no analysis on file". A focused `TerminalOutputAdapterTest` covers `printEscalation` formatting for both the cooling and won't-fix shapes (exact strings, `⚠️` constant, `× since`, the Root-cause line vs the "— volume is unusually high" suffix).

## Tasks / Subtasks

- [x] **Task 1: `DeduplicationRecord` — record the fired threshold** (AC: #1, #2) — copy-wither, mirror `incrementOccurrence()`/`clearWontFix()`
  - [x] Added `DeduplicationRecord withLastNotifiedThreshold(int threshold)` returning a copy with `lastNotifiedThreshold = threshold` (all other fields unchanged). Asserted via the PollService escalation cases (record state after firing).
- [x] **Task 2: Terminal output — escalation line** (AC: #1, #4, #8) — read Dev Notes "Routing the output (authoritative strings)"
  - [x] Added `void printEscalation(String humanLabel, int threshold, Instant firstSeen, LLMAnalysis stored, boolean wontFix)` to `TerminalOutputPort` (Javadoc citing FR-31/FR-11/FR-12), with the `boolean wontFix` discriminator appended to the architecture sketch.
  - [x] Implemented in `TerminalOutputAdapter` using the `ESCALATION` (`⚠️`) constant, mirroring the `printDegraded`/`printRecovery` style. Cooling = two lines (`⚠️ Known error …` + `   Root cause: …`); won't-fix = one line (`⚠️ Won't-fix error … — volume is unusually high`). `storedRootCause()` helper renders null/blank/unavailable as `"no analysis on file"`.
  - [x] Updated both `TerminalOutputPort` fakes (`PollServiceTest.RecordingTerminal` records calls; `SuppressionFileAdapterTest.RecordingTerminal` no-op).
- [x] **Task 3: `PollService` — threshold check on every increment** (AC: #1–#6, #9) — read Dev Notes "Escalation logic (authoritative)"
  - [x] Added the `List<Integer> escalationThresholds` constructor parameter (after `maxConsecutivePollFailures`); stored a sorted-ascending immutable copy, tolerating null (→ empty = disabled).
  - [x] Added `incrementAndEscalate(DeduplicationRecord)`: increment, find the smallest threshold `> (lastNotifiedThreshold ?? 0)` with `count >= t`, set `lastNotifiedThreshold = t` and deliver only when `!wontFix || t == WONT_FIX_OVERRIDE_THRESHOLD` (=1000); always `save` (threshold advance persists even when not delivered).
  - [x] Rewired `gateAndCollectNew`'s active-present branch through the helper (unsuppression → `incrementAndEscalate(rec.clearWontFix())`; cooling/still-won't-fix → `incrementAndEscalate(rec)`); new-record / won't-fix-from-birth branches unchanged.
  - [x] Updated the class Javadoc (escalation paragraph now reflects it is wired).
- [x] **Task 4: Wiring** (AC: #7) — `DomainServiceConfig` only
  - [x] Passed `properties.escalationThresholds()` into the `PollService` bean constructor. No `application.yml`/`LogguardProperties`/migration change.
- [x] **Task 5: Tests** (AC: #10) — mirror the existing `PollServiceTest` fake style
  - [x] Updated the `PollService` construction (`ESCALATION_THRESHOLDS = List.of(10, 100, 1000)`); added a `printEscalation` counter + capture to `RecordingTerminal`; added the no-op override to `SuppressionFileAdapterTest.RecordingTerminal`.
  - [x] Added a `seed(...)` test helper building `DeduplicationRecord` via its canonical constructor for arbitrary count/lastNotified/wontFix/analysis.
  - [x] Added the AC #10 cases: cooling 10× (reuses analysis, 0 LLM, `lastNotifiedThreshold=10`); no-refire below next rung; cooling 100× after 10×; cooling null-analysis passes null (adapter → "no analysis on file"); won't-fix 1000× override (flag stays true); won't-fix 10× silent but threshold advances; in-batch sequence crossing during catchup.
  - [x] Added `TerminalOutputAdapterTest` cases for the cooling two-line block, the null-stored "no analysis on file" fallback, and the won't-fix one-line override.
  - [x] Built/ran via Bash with JDK 21. **Result: 82 run / 0 failures / 1 skipped** (baseline 72/1 → +10 new tests, no regressions).

### Review Findings

Code review 2026-07-01 (Opus 4.8; Blind Hunter / Edge Case Hunter / Acceptance Auditor — all 3 layers ran, none failed). Acceptance Auditor: **all 10 ACs PASS**, four approved deviations correctly applied. 6 findings dismissed as noise (Integer==int unbox is JLS-safe; `incrementOccurrence()` preserves `lastNotifiedThreshold`; in-batch sequencing handled by autoflush+test; `storedRootCause` `!llmAvailable` guard is correct given FR-25; decoupled-but-sufficient test coverage; won't-fix-rung-loss is spec-by-design).

- [x] [Review][Patch] Threshold selector uses `.min()` — a count multiple rungs above `lastNotifiedThreshold` at increment time fired the LOWEST stale rung and printed a misleading count, deferring higher rungs [PollService.java:258-261]. **METIS chose (B) harden.** **FIXED**: `.min(...)` → `.max(Comparator.naturalOrder())` — fires the LARGEST crossed unfired rung and advances `lastNotifiedThreshold` to it (identical in the +1 path; a multi-rung jump now reports the true milestone, e.g. 1000× not 10×). +2 covering tests (`coolingCountJumpedManyRungsFiresHighestReachedMilestone`, `wontFixCountJumpedToOverrideFiresImmediately`). Deliberate, intent-faithful deviation from the spec's literal `min`. Suite 84 pass / 1 skipped.
- [x] [Review][Defer] No validation of non-positive / nonsensical `escalation-thresholds` entries [PollService.java:99-101] — a `0` or negative threshold is silently inert (never fires) rather than rejected. Benign (operator-controlled config, no wrong state); worth a config-validation note only. — deferred, minor hardening

## Dev Notes

### Scope boundary (read first)
- **IN:** the escalation re-notification at configured thresholds for cooling records (FR-11/FR-31); the won't-fix 1000× volume override (FR-12); firing during backlog catchup (FR-13 — falls out, no special code); `lastNotifiedThreshold` advancement + once-per-window guard; `printEscalation` on the port; wiring `escalation-thresholds` into `PollService`.
- **NOT IN:** any new config key (`escalation-thresholds` already exists), schema/migration (`last_notified_threshold` column exists, V2), `LogguardProperties` change (field already bound), or any change to the suppression adapter/port (Story 4.3 — frozen), the fingerprint/dedup model beyond the one wither, or the LLM path. **No fresh LLM call ever happens on the escalation path** (NFR-4/FR-25 — reuse `storedAnalysis`).
- **This story DOES change the `PollService` constructor** (adds `escalationThresholds`) — unlike Story 4.4. That ripples to `DomainServiceConfig` and the `PollServiceTest` construction.

### Escalation logic (authoritative)
Per `specs/logguard-ai.allium#CheckEscalationThreshold` + `#DeliverEscalation` and FR-11/FR-12. Replace each `dedupRepository.save(rec.incrementOccurrence())` in `gateAndCollectNew`'s active branch with a call to this helper:

```
private void incrementAndEscalate(DeduplicationRecord rec):
    DeduplicationRecord updated = rec.incrementOccurrence()
    int last = updated.lastNotifiedThreshold() == null ? 0 : updated.lastNotifiedThreshold()
    Integer fired = escalationThresholds.stream()              // sorted ascending, immutable
            .filter(t -> t > last && updated.occurrenceCount() >= t)
            .min(naturalOrder())
            .orElse(null)
    if (fired != null):
        updated = updated.withLastNotifiedThreshold(fired)     // advance ALWAYS (even won't-fix 10×/100×)
        if (!updated.wontFix() || fired == WONT_FIX_OVERRIDE_THRESHOLD):   // 1000
            terminalOutput.printEscalation(updated.fingerprint().humanLabel(), fired,
                    updated.firstSeenAt(), updated.storedAnalysis(), updated.wontFix())
    dedupRepository.save(updated)
```

Gate active-present branch becomes:
```
if (active.isPresent()):
    rec = active.get()
    if (rec.wontFix() && !suppressed.contains(hash)):
        incrementAndEscalate(rec.clearWontFix())   // FR-19 unsuppression → cooling → cooling thresholds
    else:
        incrementAndEscalate(rec)                   // cooling OR still-won't-fix (override at 1000)
// new-record + won't-fix-from-birth branches UNCHANGED (createNew count=1 crosses nothing)
```

Why one threshold per increment is correct: each `ErrorLog` bumps the count by exactly 1, and the `fired = min(t > last AND count >= t)` rule picks the next-unfired threshold, so the ladder advances 10 → 100 → 1000 across occurrences with no double-fire. The once-per-window guard is `lastNotifiedThreshold` itself, which persists on the record and resets to `null` only when the record is recreated (new window). **Defensive note:** if a single increment ever jumped multiple thresholds (not possible with +1 increments and the default ladder), this fires only the smallest unfired one that cycle; the next occurrence fires the next. Acceptable and matches the spec's `min`.

Why won't-fix advances silently through 10×/100×: the spec's `CheckEscalationThreshold` fires for **both** states (advances `lastNotifiedThreshold`); only `DeliverEscalation` filters delivery to `wontFix = false OR threshold = 1000`. So a won't-fix record's `lastNotifiedThreshold` climbs 10 → 100 → 1000 with only the 1000 line printed. Consequence (intended): if such a record is later unsuppressed (`clearWontFix`) past 100, the cooling 10×/100× lines won't retro-fire — those milestones already passed. Keep this behaviour; do not special-case it.

Why the override is tied to the literal **1000** (not "the max configured threshold"): the spec is explicit — `requires record.won't_fix = false OR threshold = 1000`, and FR-12 / the AC message hard-code "1,000×". Define `private static final int WONT_FIX_OVERRIDE_THRESHOLD = 1000;`. Caveat to note in Completion Notes: if `escalation-thresholds` is configured **without** 1000, the won't-fix override never fires — acceptable, the default includes 1000.

[Source: epics.md#FR-11, #FR-12, #FR-13, #Story 4.5; specs/logguard-ai.allium#CheckEscalationThreshold (lines 374-382), #DeliverEscalation (lines 387-403); architecture.md#Data Flow lines 568-569.]

### Routing the output (authoritative strings)
All user-facing strings go through `TerminalOutputPort`; only `TerminalOutputAdapter` may touch `System.out` (AR-10). Use the pre-defined `ESCALATION` (`⚠️`) constant — Story 2.4 forbids inline glyph literals (it is currently the only still-unused status constant; this story lights it up — `printSuppressionUnreadable` already reuses it, which is fine). Print style mirrors `printDegraded`: leading blank `println()`, message line(s), `flush()`.

- **Cooling re-notification** (FR-31 — two lines, 3-space indent on the second like the spec):
  ```
  ⚠️ Known error {HumanLabel} now seen {threshold}× since {firstSeenAt}
     Root cause: {storedAnalysis.rootCause | "no analysis on file"}
  ```
  `storedAnalysis` is reused (FR-25), never re-fetched. Null/blank/`!llmAvailable` root cause → the literal `no analysis on file` (a failed initial analysis was never cached, so `storedAnalysis` can be null even for a real cooling record).
- **Won't-fix volume override** (FR-12 / Story 4.5 AC — one line, no Root-cause line):
  ```
  ⚠️ Won't-fix error {HumanLabel} now seen {threshold}× since {firstSeenAt} — volume is unusually high
  ```
  Uses the same `threshold` int (renders `1000×`). The `× ` is the multiplication sign `×`, inline text (not a status constant). `{firstSeenAt}` is the `Instant` printed verbatim, exactly like `printDegraded` renders `degradationStartedAt` — do not reformat it.

Spec vs epics note (resolve in the epics' favour): `specs/logguard-ai.allium#DeliverEscalation` writes the override as two lines (`"   [Your won't-fix decision stands — volume is unusually high]"`); the binding Story 4.5 AC (epics.md line 681) is the single-line `… — volume is unusually high` form. Implement the **epics** single-line form; note the deviation in Completion Notes.

[Source: epics.md#FR-31, #FR-32 (style), #Story 4.5 ACs; architecture.md#Port Interface Contracts line 550, #AR-10.]

### HumanLabel rendering — carry the Story 4.4 decision
`{HumanLabel}` is `ErrorFingerprint.humanLabel()` — the **full simple exception name** form (`NullPointerException@LabelV2Config:21`), per METIS's Story 4.4 decision (NOT the `NPE` acronym). It is already implemented and hardened (degenerate-guard + `UnknownError` fallback). Reuse it verbatim; do not reintroduce an acronym.

### Files being modified — current state & what to preserve
- **`PollService.java`** (UPDATE) — the two-phase gate (4.2) now consuming the suppression set (4.4). `gateAndCollectNew` increments cooling/won't-fix records via `incrementOccurrence()` and is where escalation now hooks in. Preserve: degradation/recovery (2.6), checkpoint-advance-minus-refresh-lag (2.5 D1), FR-29 ordering, FR-25 cache-only-success + re-fetch-before-cache in `analyseServiceGroup`, the NFR-4 two-phase guarantee, the 4.4 won't-fix-from-birth `printWontFixLabel` + unsuppression `clearWontFix()`. The escalation prints in **phase 1** (the gate loop), interleaved with the `⚑` labels and BEFORE the phase-2 service analysis blocks — consistent with the architecture data-flow and the 4.4 label placement.
- **`DeduplicationRecord.java`** (UPDATE) — record with `lastNotifiedThreshold` (Integer, currently always `null`), `wontFix`, `storedAnalysis`, `occurrenceCount`. `incrementOccurrence()`/`withStoredAnalysis()`/`clearWontFix()` show the copy-wither idiom for `withLastNotifiedThreshold(int)`. The canonical 7-arg constructor is public (tests use it to seed arbitrary counts).
- **`TerminalOutputPort.java`** (UPDATE) — add `printEscalation(...)`. Ripples to the two `RecordingTerminal` fakes.
- **`TerminalOutputAdapter.java`** (UPDATE) — has `ESCALATION = "⚠️"` and the `printDegraded`/`printRecovery`/`printSuppressionUnreadable` style. Add `printEscalation`. Do not disturb `printAnalysis` (4.4 Fingerprint line) or `header(...)`.
- **`DomainServiceConfig.java`** (UPDATE) — the `pollService(...)` `@Bean`. Add `properties.escalationThresholds()` to the constructor call.
- **`LogguardProperties.java`** (NO CHANGE) — `escalationThresholds` (`List<Integer>`, `@DefaultValue({"10","100","1000"})`) is already bound.
- **`application.yml`** (NO CHANGE) — `logguard.escalation-thresholds: [10, 100, 1000]` already present.

### Read-only authority & no-LLM guarantees (carry-forward)
This story neither writes `suppression.txt` (FR-16/NFR-6 — no touch-point added) nor calls the LLM on the escalation path (FR-25/NFR-4 — `storedAnalysis` reused). Both are structural: the escalation helper only reads the record and calls `printEscalation`.

### Testing standards
- `PollServiceTest` is pure (no Spring): hand-written fakes, real `FingerprintService(List.of("be.vdab"))`, `assertEquals` counters. Seed near-threshold records via the **canonical `DeduplicationRecord` constructor** (count/lastNotified/wontFix/analysis all settable) — `createNew` only gives count=1. To target a fingerprint, build the `ErrorLog` with a `be.vdab.*` frame and `fingerprintService.compute(error).hash()` (existing pattern). The existing default-threshold tests stay green because small counts (≤3) never cross 10.
- `TerminalOutputAdapterTest` captures `System.out` via a swapped `ByteArrayOutputStream`-backed `PrintStream` (UTF-8) in try/finally — the Story 4.4 test shows the exact harness. Assert the `⚠️` lines and the `× since` / `— volume is unusually high` / `Root cause:` substrings.
- Build/run via Bash with JDK 21: `export JAVA_HOME="$HOME/.jdks/temurin-21.0.11"; mvn -B -ntp test`. The `mvnw.cmd` wrapper is blocked and the SDKMAN default JDK is not 21, so the explicit `JAVA_HOME` export is required (Story 4.3/4.4 learning). Baseline before this story: **72 pass / 1 skipped**.

### Project Structure Notes
- UPDATE: `domain/model/DeduplicationRecord.java`, `domain/port/out/TerminalOutputPort.java`, `domain/service/PollService.java`, `infrastructure/terminal/TerminalOutputAdapter.java`, `infrastructure/config/DomainServiceConfig.java`, `src/test/.../domain/service/PollServiceTest.java`, `src/test/.../infrastructure/filesystem/SuppressionFileAdapterTest.java` (fake conformance), `src/test/.../infrastructure/terminal/TerminalOutputAdapterTest.java`.
- NO new production files, NO migration, NO `application.yml`/`LogguardProperties` change. `SuppressionFilePort`/`SuppressionFileAdapter`, `ErrorFingerprint`, `FingerprintService`, `LlmPort` unchanged.

### Previous Story Intelligence (Stories 4.4, 4.3, 4.2)
- **4.4 (just done, reviewed, committed `484f404`):** the gate now consumes the suppression set. `gateAndCollectNew` has three increment call-sites (unsuppression `clearWontFix().incrementOccurrence()`, cooling `incrementOccurrence()`, still-won't-fix `incrementOccurrence()`) — these are exactly the sites that now route through `incrementAndEscalate`. `ErrorFingerprint.humanLabel()` is implemented (full simple name). `printWontFixLabel` + the `Fingerprint:` line are in the adapter. A code-review patch hardened `humanLabel()` against malformed exception types. **Deferred-from-4.4 (relevant here):** the won't-fix same-batch label leans on transactional autoflush for read-after-write; escalation's same-batch counting leans on the same mechanism — it is correct under the single `@Transactional` poll cycle (`TransactionalPollUseCase`). Do not try to "fix" it in this story; just be aware same-batch increments depend on `findActiveByFingerprint` seeing the just-saved row.
- **4.2:** `findActiveByFingerprint` filters expired rows; `createNew(...,wontFix)` sets `lastNotifiedThreshold=null`; "cache only successful analysis" means `storedAnalysis` can legitimately be `null` on a cooling record (→ escalation prints "no analysis on file"). `FakeDeduplicationRepository.seedActive(...)`/`.only()`/`.byHash` exist for tests; the fake does NOT filter expiry (models expiry as absence).
- **4.4/2.6/2.4:** `ESCALATION` (`⚠️`) is the last defined-but-(almost-)unused status constant; this story is its primary emitter. Mirror the `printDegraded`/`printRecovery` print style exactly.

### Git Intelligence
- Baseline HEAD `484f404` "Implement won't-fix suppression integration and Fingerprint output line (Story 4.4)". Established patterns to mirror: pure-domain copy-wither methods on `DeduplicationRecord`; status-constant usage (never inline glyphs) in `TerminalOutputAdapter`; hermetic fake-based `PollServiceTest`; `@Bean` wiring in `DomainServiceConfig` reading from `LogguardProperties`.

### References
- [Source: epics.md#Story 4.5] — acceptance criteria (cooling escalation, won't-fix override, backlog catchup, config, port routing)
- [Source: epics.md#FR-11] cooling threshold re-notification · [#FR-12] won't-fix 1000× override (read-only nudge) · [#FR-13] fires during backlog catchup · [#FR-10] count on all paths · [#FR-25] cache + reuse analysis, no fresh LLM · [#FR-31] escalation format · [#NFR-3] config-first · [#NFR-4] LLM throughput budget
- [Source: specs/logguard-ai.allium#CheckEscalationThreshold (374-382), #DeliverEscalation (387-403), #ProcessCoolingError (334-341), #ProcessWontFixError (346-355)] — the authoritative threshold + delivery rules
- [Source: architecture.md#Port Interface Contracts line 550] `printEscalation(humanLabel, threshold, firstSeen, stored)` · [#Data Flow lines 568-569] gate branches calling `printEscalation()` · [#AR-10] all output via `TerminalOutputPort` · [#Key Configuration line 590] `escalation-thresholds: [10, 100, 1000]`
- [Source: src/main/java/.../domain/service/PollService.java] — `gateAndCollectNew` increment sites being extended
- [Source: src/main/java/.../infrastructure/config/LogguardProperties.java] — `escalationThresholds` already bound
- [Source: src/main/java/.../infrastructure/config/DomainServiceConfig.java] — the `PollService` bean to rewire

### Questions / Decisions for METIS (non-blocking — defaults chosen so dev can proceed)
1. **Threshold rendering.** Default = print the raw integer (`1000×`, no thousands separator) for both shapes — locale-independent and matches the cooling `{threshold}×` template. The spec/epics examples show `1,000×` cosmetically. Confirm raw, or request comma-grouping.
2. **Won't-fix override threshold = literal 1000.** Per the spec (`threshold = 1000`) and FR-12, the override is tied to the literal 1000, not "the largest configured threshold". If `escalation-thresholds` is set without 1000, the won't-fix override never fires. Confirm (default implemented = literal 1000), or request "largest configured threshold" semantics.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Opus 4.8, 1M context) — dev-story workflow.

### Debug Log References

- Build/test: `export JAVA_HOME="$HOME/.jdks/temurin-21.0.11"; mvn -B -ntp test` → BUILD SUCCESS, **82 run / 0 failures / 1 skipped** (baseline 72/1 → +10 new tests, no regressions).
- One compile fix mid-implementation: the threshold-selection lambda captured a reassigned `updated` local → split into an effectively-final `incremented` + a separate `toSave`.

### Completion Notes List

- **Implemented FR-11 / FR-12 / FR-13 / FR-31.** Every active-record increment in `gateAndCollectNew` now routes through `PollService.incrementAndEscalate(...)`:
  - **Cooling** (`wontFix=false`): on crossing the smallest configured threshold above `lastNotifiedThreshold` that the count has reached, reprints `⚠️ Known error {label} now seen {threshold}× since {firstSeenAt}` + `   Root cause: {stored | "no analysis on file"}`, reusing the cached analysis — **no fresh LLM call** (FR-25/NFR-4). `lastNotifiedThreshold` advances so each rung fires once per window.
  - **Won't-fix** (`wontFix=true`): the volume override fires **only at 1000×** (`WONT_FIX_OVERRIDE_THRESHOLD = 1000`, literal per the spec + METIS decision) — one line `⚠️ Won't-fix error {label} now seen {threshold}× since {firstSeenAt} — volume is unusually high`. `wontFix` is **not** cleared (read-only nudge). 10×/100× advance `lastNotifiedThreshold` silently but print nothing (spec: deliver only when `!wontFix || threshold==1000`).
  - **Backlog catchup** (FR-13): no special branch — each `ErrorLog` increments by 1, so thresholds fire in sequence as counts reconstruct (covered by an in-batch test crossing 10× across 10 occurrences).
- **METIS decisions applied:** threshold rendered as a **raw integer** (`1000×`, no comma); won't-fix override tied to the **literal 1000** (if `escalation-thresholds` ever omits 1000 the won't-fix override never fires — default includes it).
- **Spec-vs-epics deviation (as planned):** the won't-fix override is the epics' **single-line** form (`… — volume is unusually high`), not the spec's two-line `[Your won't-fix decision stands …]`. The epics Story 4.5 AC is the binding requirement.
- **Output routing (AR-10 / Story 2.4):** the `⚠️` glyph is the pre-defined `ESCALATION` constant (previously the last defined-but-unused status constant), never inline. `PollService` makes no direct `System.out` call.
- **No fresh wiring beyond the constructor:** `escalation-thresholds` was already bound on `LogguardProperties` and present in `application.yml`; only `PollService`'s constructor (new `List<Integer>` param) and the `DomainServiceConfig` bean call changed. **No schema/migration/config/`LogguardProperties` change.**
- **Carry-forwards preserved:** 4.2 cache-only-success (so a cooling record can have `storedAnalysis == null` → "no analysis on file"), 4.4 won't-fix-from-birth/`⚑`/unsuppression/Fingerprint line, 2.6 degradation/recovery, FR-26/FR-29/NFR-4. Escalation prints in phase 1 (the gate), interleaved with `⚑` labels and before phase-2 service blocks (matches the architecture data flow).

### File List

- `src/main/java/be/vdab/logguard/domain/model/DeduplicationRecord.java` (UPDATE — `withLastNotifiedThreshold()`)
- `src/main/java/be/vdab/logguard/domain/port/out/TerminalOutputPort.java` (UPDATE — `printEscalation(...)`)
- `src/main/java/be/vdab/logguard/infrastructure/terminal/TerminalOutputAdapter.java` (UPDATE — `printEscalation` + `storedRootCause` helper)
- `src/main/java/be/vdab/logguard/domain/service/PollService.java` (UPDATE — `escalationThresholds` ctor param, `WONT_FIX_OVERRIDE_THRESHOLD`, `incrementAndEscalate`, gate rewire, class Javadoc)
- `src/main/java/be/vdab/logguard/infrastructure/config/DomainServiceConfig.java` (UPDATE — pass `escalationThresholds()`)
- `src/test/java/be/vdab/logguard/domain/service/PollServiceTest.java` (UPDATE — ctor arg, `RecordingTerminal` escalation capture, `seed(...)` helper, 7 escalation cases)
- `src/test/java/be/vdab/logguard/infrastructure/terminal/TerminalOutputAdapterTest.java` (UPDATE — 3 `printEscalation` formatting cases)
- `src/test/java/be/vdab/logguard/infrastructure/filesystem/SuppressionFileAdapterTest.java` (UPDATE — `RecordingTerminal` port conformance)

## Change Log

| Date | Change |
|---|---|
| 2026-07-01 | Story 4.5 drafted via create-story context engine (cooling escalation re-notifications at configurable thresholds reusing stored analysis; won't-fix 1000× volume override read-only nudge; backlog-catchup firing; `printEscalation` port method; `escalation-thresholds` wired into `PollService`). Authoritative threshold/delivery algorithm taken from spec `CheckEscalationThreshold`/`DeliverEscalation`; resolved spec-vs-epics override wording (epics single-line wins) and literal-1000 override threshold. No schema/config/`LogguardProperties` change (field + YAML key already present). Status → ready-for-dev. |
| 2026-07-01 | Implemented via dev-story. Added `incrementAndEscalate` to `PollService` (next-unfired-threshold rule, deliver when `!wontFix || t==1000`, reuse `storedAnalysis` — no LLM call), `printEscalation` on the port/adapter (`⚠️ ESCALATION` constant; cooling 2-line + won't-fix 1-line override), `DeduplicationRecord.withLastNotifiedThreshold()`, and the `escalationThresholds` constructor param wired via `DomainServiceConfig`. METIS decisions: raw-integer threshold rendering; literal-1000 override. No schema/config/`LogguardProperties` change. Tests: 82 run / 0 failures / 1 skipped (+10). Status → review. |
| 2026-07-01 | Code review (Opus 4.8; Blind Hunter / Edge Case Hunter / Acceptance Auditor). All 10 ACs PASS. 1 decision resolved → patch: threshold selector `.min()` → `.max()` so a multi-rung count jump reports the true milestone (METIS chose harden), +2 tests. 1 item deferred (no non-positive-threshold config validation → deferred-work.md), 6 dismissed. Tests: 84 run / 0 failures / 1 skipped. Status → done. |
