---
baseline_commit: 1a4e337
---

# Story 4.4: Won't-Fix Suppression Integration and Fingerprint Identifier in Terminal Output

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a developer,
I want suppressed fingerprints acknowledged with a visible label, every new-error block to end with the fingerprint hash for direct copy-paste into `suppression.txt`, and a hash I delete from the file to take effect again,
so that the won't-fix workflow needs no tooling beyond a text editor and a terminal.

## Acceptance Criteria

1. **Won't-fix-from-birth** (FR-8 exception, FR-17). **Given** an `ErrorLog` whose fingerprint has **no active `DeduplicationRecord`** AND whose `hash()` is in the suppression set loaded this cycle, **when** `PollService` processes it, **then** a `DeduplicationRecord` is created with `wontFix = true` and `occurrenceCount = 1` immediately — **no LLM call, no cooling period, not buffered for the service block** — **and** the terminal prints the WontFix label exactly once: `⚑ Known / Won't Fix: {HumanLabel}  {hash}`.
2. **Active won't-fix = silent increment** (FR-8 WON'T-FIX, FR-10). **Given** an `ErrorLog` whose fingerprint has an **active** (`expiresAt` in the future) record with `wontFix = true` and whose hash is **still** in the suppression set, **when** processed, **then** the occurrence count is incremented and **nothing is printed** (no label, no analysis). A duplicate of a won't-fix error later in the **same batch** therefore prints the label at most once.
3. **WontFix re-encounter after expiry** (FR-17). **Given** a `wontFix = true` record whose `expiresAt` has passed (so `findActiveByFingerprint` returns empty) and whose hash is still suppressed, **when** the fingerprint recurs, **then** it is handled by the won't-fix-from-birth path again: a fresh `wontFix = true` record (new window) replaces the expired row and the label prints once for the new window. **No LLM analysis** is performed.
4. **Unsuppression — within window** (FR-19). **Given** a fingerprint hash that has been **removed** from `suppression.txt` and whose record is **active** with `wontFix = true`, **when** that fingerprint is next encountered (the suppression set was reloaded at the start of this cycle, FR-14), **then** `wontFix` is cleared (the record transitions to cooling), the occurrence count is incremented, and **nothing is printed** (cooling = no fresh analysis, no display; escalation is Story 4.5).
5. **Unsuppression — expired** (FR-19). **Given** a removed hash whose record has **expired**, **when** the fingerprint recurs, **then** it is treated as a brand-new, non-suppressed error: analysed and displayed normally (this falls out of the expired record being invisible + the hash no longer being in the set — no special code path).
6. **Fingerprint line on every new-error block** (FR-18, FR-30). **Given** LogGuard prints a new (non-suppressed) error block, **when** the block is rendered, **then** its **final line** is `  Fingerprint: {HumanLabel}  [{hash}]`, present **whether or not** the LLM analysis succeeded (a failed analysis still shows the Fingerprint line — FR-18 says *every* new error output includes it). `{hash}` is `ErrorFingerprint.hash()`, suitable for direct copy-paste into `suppression.txt`.
7. **HumanLabel derivation** (FR-18 AC, FR-32). `{HumanLabel}` is derived from the fingerprint as `{ExcAcronym}@{SimpleThrowingClass}:{line}` — e.g. `NullPointerException` thrown at `be.vdab.label.LabelV2Config.getForwardingSource:21` → `NPE@LabelV2Config:21`. See Dev Notes "HumanLabel derivation (authoritative)" for the exact algorithm and edge handling. The **same** computed label is used for both the Fingerprint line (AC #6) and the WontFix label (AC #1/#3) — it is **not** read from the suppression-file comment (see Dev Notes "Why the label is computed, not read").
8. **Read-only authority preserved** (FR-16 / NFR-6). No code path added in this story writes to `suppression.txt`. `SuppressionFilePort` stays `Set<String> loadHashes()` (no new method); the adapter is untouched.
9. **No regressions.** The Story 2.6 degradation/recovery behaviour, the Story 4.2 cooling-path silent increment + analysis caching, the FR-26 zero-results silence, the FR-29 most-affected-first ordering, and the FR-25 "don't cache a failed analysis" rule all remain green. The `PollService` constructor signature is unchanged (no new dependencies or config).
10. **Tests.** `PollServiceTest` gains cases for: won't-fix-from-birth (record `wontFix=true`, count 1, **0** LLM calls, **1** WontFix label, **0** analysis prints, checkpoint still advances); same-batch won't-fix duplicate (label once, count 2); active-won't-fix silent increment; unsuppression within window (flag cleared, count incremented, nothing printed); unsuppression expired → analysed as new; and the Fingerprint line/HumanLabel are asserted (unit-test the derivation + the adapter formatting). A focused test covers `ErrorFingerprint.humanLabel()` and `TerminalOutputAdapter`'s Fingerprint line + WontFix label formatting.

## Tasks / Subtasks

- [x] **Task 1: HumanLabel derivation on the domain model** (AC: #7) — read Dev Notes "HumanLabel derivation (authoritative)"
  - [x] Add `String humanLabel()` to `ErrorFingerprint` (pure, no new fields). Derive `{SimpleExceptionName}@{SimpleClass}:{line}` from the existing `exceptionType` (FQCN) and `throwingMethod` (`SimpleClass.method:line`). Drop the method segment; keep class + line. Handle the framework-fallback / degenerate cases gracefully (no line → omit `:line`; throwingMethod equal to the exception type or `"unknown"` → fall back to just the exception name). **METIS decision: full simple name, not the acronym.**
  - [x] Cover it in a unit test (new `ErrorFingerprintTest`): own-code frame (`NullPointerException@LabelV2Config:21`), framework-fallback frame, native frame (no line), and two degenerate traces.
- [x] **Task 2: Terminal output — Fingerprint line + WontFix label** (AC: #1, #6) — read Dev Notes "Routing the output"
  - [x] Change `TerminalOutputPort.printAnalysis(int index, int total, ErrorLog error, LLMAnalysis analysis)` → add `String humanLabel, String hash`. Updated the Javadoc.
  - [x] In `TerminalOutputAdapter.printAnalysis`, after the three analysis lines (in BOTH the available and unavailable branches), print the final line `  Fingerprint: {humanLabel}  [{hash}]`, then `flush()`.
  - [x] Add `void printWontFixLabel(String humanLabel, String hash)` to `TerminalOutputPort`; implemented in `TerminalOutputAdapter` using the existing `WONT_FIX` (`⚑`) constant. Prints exactly: `⚑ Known / Won't Fix: {humanLabel}  {hash}` (leading blank `println()`, then the line, then `flush()`).
- [x] **Task 3: DeduplicationRecord — clear-won't-fix transition** (AC: #4)
  - [x] Add `DeduplicationRecord clearWontFix()` returning a copy with `wontFix=false` (all other fields unchanged), mirroring `incrementOccurrence()` / `withStoredAnalysis()`. (Unsuppression calls `clearWontFix().incrementOccurrence()`.)
- [x] **Task 4: Consume the suppression set in the dedup gate** (AC: #1–#5, #9) — read Dev Notes "Gate logic (authoritative)"
  - [x] In `PollService.poll()`, captured the result: `Set<String> suppressed = suppressionFilePort.loadHashes();` and passed it into `gateAndCollectNew`.
  - [x] Rewrote `gateAndCollectNew` per Dev Notes: **active present** → if `wontFix && !suppressed.contains(hash)` → `clearWontFix().incrementOccurrence()`; else `incrementOccurrence()`. **Active absent + new this batch** → if `suppressed.contains(hash)` → create `wontFix=true`, `printWontFixLabel(...)`, not added to `newByHash`; else create `wontFix=false` and add (unchanged).
  - [x] In `analyseServiceGroup`, pass `fingerprint.humanLabel()` and `fingerprint.hash()` to the updated `printAnalysis`.
  - [x] No new `DeduplicationRecordRepository` method, no constructor param, no escalation logic. Updated the class Javadoc (the "NOT yet wired … Story 4.3/4.4" paragraph) to reflect that suppression is now consumed.
- [x] **Task 5: Tests** (AC: #10) — mirror the existing `PollServiceTest` fake style
  - [x] Updated `RecordingTerminal`: new `printAnalysis` signature (captures last `humanLabel`/`hash`), added a `printWontFixLabel` counter (+ capture last label/hash).
  - [x] Updated `FakeSuppression` with a mutable `Set<String> hashes` field.
  - [x] Added the AC #10 cases (won't-fix-from-birth, same-batch duplicate, active-won't-fix silent increment, unsuppression within window, unsuppression expired → analysed as new, Fingerprint-line propagation). Seeds the suppression set with the real `FingerprintService` hash.
  - [x] Added a `TerminalOutputAdapterTest` formatting test (captures `System.out`) asserting the Fingerprint line `[hash]` brackets and the bracket-free `⚑ Known / Won't Fix:` label.
  - [x] Built/ran via Bash with JDK 21. **Result: 70 run / 0 failures / 1 skipped** (baseline 56 pass / 1 skipped → +14 new tests, no regressions).

### Review Findings

Code review 2026-06-30 (Opus 4.8; Blind Hunter / Edge Case Hunter / Acceptance Auditor — all 3 layers ran, none failed). Acceptance Auditor: **all 10 ACs PASS**, three approved deviations correctly applied. 3 findings dismissed as noise.

- [x] [Review][Patch] Harden `humanLabel()` degenerate guard against a whitespace-padded or trailing-dot `exceptionType` — strip both sides of the `equals` and fall back to `UnknownError` on an empty simple name (else a padded FQCN type yields a garbage `@package` suffix, e.g. `FooException@com`, or an empty label) [ErrorFingerprint.java:88-98] — **FIXED**: compares against the stripped `exceptionType`; `simpleExceptionName()` returns `UnknownError` on empty; +2 covering `ErrorFingerprintTest` cases. Suite 72 pass / 1 skipped.
- [x] [Review][Defer] Won't-fix-from-birth same-batch label relies on transactional autoflush (read-after-write) rather than a local seen-set; correct under the single `@Transactional` poll cycle, but the label path lacks the belt-and-suspenders `newByHash` guard the non-suppressed path has [PollService.java:209-213] — deferred, architectural-assumption note
- [x] [Review][Defer] AC#3 (expired won't-fix re-encounter) is covered only via the unseeded-record proxy; the fake repo deliberately ignores expiry, so no test seeds an actually-expired `wontFix=true` row and asserts the new-window label [PollServiceTest.java] — deferred, test-fidelity (expiry filtering is owned by Story 4.2 adapter tests)

## Dev Notes

### Scope boundary (read first)
- **IN:** consume the suppression set (finally — it has been loaded-and-discarded since Story 2.5); won't-fix-from-birth record creation + `⚑` label (FR-8 exception / FR-17 / FR-32); the per-error `Fingerprint:` line (FR-18 / FR-30); on-encounter unsuppression (FR-19); the computed HumanLabel (FR-18 AC).
- **NOT IN — Story 4.5:** escalation re-notifications at 10×/100×/1,000× (FR-11), the won't-fix 1,000× volume override (FR-12), backlog-catchup escalation (FR-13), `printEscalation(...)`, `escalation-thresholds` config, and `lastNotifiedThreshold` updates. The cooling and won't-fix paths in this story **only increment the count silently** — they do not check thresholds. Leave `lastNotifiedThreshold` untouched.
- **NOT IN:** any change to `SuppressionFileAdapter`/`SuppressionFilePort` (Story 4.3 — frozen, read-only), the schema/migration (the `wont_fix` column already exists, V2), `application.yml`, `LogguardProperties`, or `DomainServiceConfig` (no new bean wiring — `PollService` already has every dependency it needs).

### Gate logic (authoritative)
Replace the body of `gateAndCollectNew(List<ErrorLog> errors)` — now also taking `Set<String> suppressed` — with this per-error decision (preserving the existing `LinkedHashMap<String,ErrorLog> newByHash` first-seen-order accumulation):

```
fp = fingerprintService.compute(error)
active = dedupRepository.findActiveByFingerprint(fp)
if (active.isPresent()):
    rec = active.get()
    if (rec.wontFix() && !suppressed.contains(fp.hash())):
        dedupRepository.save(rec.clearWontFix().incrementOccurrence())   // FR-19 unsuppression, silent
    else:
        dedupRepository.save(rec.incrementOccurrence())                  // cooling OR still-won't-fix, silent
else if (!newByHash.containsKey(fp.hash())):
    if (suppressed.contains(fp.hash())):
        dedupRepository.save(DeduplicationRecord.createNew(fp, now, deduplicationWindow, true))  // won't-fix-from-birth
        terminalOutput.printWontFixLabel(fp.humanLabel(), fp.hash())     // FR-17/FR-32 — once per new window
        // NOT added to newByHash → no LLM, not displayed in a service block
    else:
        dedupRepository.save(DeduplicationRecord.createNew(fp, now, deduplicationWindow, false))
        newByHash.put(fp.hash(), error)                                  // unchanged — analysed in phase 2
return List.copyOf(newByHash.values())
```
Why this is complete without an eager unsuppression sweep: AC #4 says unsuppression takes effect "when the fingerprint is next encountered". The set is reloaded at cycle start (FR-14), so the very next occurrence sees the cleared state. A won't-fix error that has **stopped occurring** keeps its stale `wontFix=true` flag until it expires — but it produces no output and is never matched, so the staleness is unobservable. This is why the architecture's `DeduplicationRecordRepository` contract deliberately has **no bulk "find all won't-fix" query** — unsuppression is event-driven, like every other state transition in the gate. Do not add such a method.
Why the expired-won't-fix re-encounter (AC #3) needs no special branch: `findActiveByFingerprint` already filters expired rows (Story 4.2), so an expired won't-fix record is invisible → the error is "active absent" → the suppression check recreates it as won't-fix-from-birth, `save` upserts onto the same `fingerprint_hash` row (replacing the expired one), and the label prints for the new window.
[Source: architecture.md#Data Flow lines 563-569; epics.md#FR-8, #Story 4.4 ACs; specs/logguard-ai.allium#ProcessWontFixError/#HandleWontFixEncountered.]

### Why the label is computed, not read
The spec's `HandleWontFixEncountered` @guidance says "the HumanLabel … come[s] from the suppression file entry for this fingerprint." That is **not implementable against the Story 4.3 port**, which is frozen to `Set<String> loadHashes()` (AC #7 of 4.3) — the `# HumanLabel` comments are parsed away and never returned. Reopening that port to return hash→label would widen a deliberately-minimal read-only contract. Instead, derive the HumanLabel **from the fingerprint** (Task 1) — the Fingerprint output line (FR-18) already requires a computed label for brand-new errors, so a single computed `humanLabel()` serves both the `Fingerprint:` line and the `⚑` label. The hash is the real key; the label is cosmetic, so a computed label that the developer would recognise is fully sufficient. **This is a deliberate deviation from the spec @guidance, justified by the frozen port** — note it in the Completion Notes.

### HumanLabel derivation (authoritative)
Target format (the canonical example in FR-18/FR-30 and the spec output mock): `NPE@LabelV2Config:21`.
- **Exception acronym:** simple name of `exceptionType` (substring after the last `.`), then the capital letters in order. `NullPointerException` → `NPE`; `IllegalStateException` → `ISE`; `IOException` → `IOE`. If the simple name has fewer than 2 capitals (e.g. `Throwable`), fall back to the full simple name.
- **Class + line:** from `throwingMethod` (`SimpleClass.method:line`), take the segment before the **first** `.` as the class and the segment after the **last** `:` as the line. Emit `{class}:{line}`, or just `{class}` when there is no line.
- **Compose:** `{acronym}@{class}:{line}`. Degenerate guard: when `throwingMethod` is blank, equals the exception type, or is the literal `"unknown"` (the `FingerprintService` fallback for a null/unparseable trace), emit just `{acronym}` (no `@…`).
- ⚠️ **Open decision for METIS — see Questions below.** The capital-letter acronym is the only deterministic rule that reproduces the documented `NPE`; if you'd rather not abbreviate, the fallback is the full simple exception name (`NullPointerException@LabelV2Config:21`). Default implemented = the acronym rule.
[Source: epics.md#FR-18, #FR-30, #Story 4.4 AC ("e.g. NPE@LabelV2Config:21"); specs/logguard-ai.allium line 457.]

### Routing the output
All user-facing strings go through `TerminalOutputPort`; only `TerminalOutputAdapter` may touch `System.out`/`System.err` (AR-10). Reuse the pre-defined `WONT_FIX` (`⚑`) status constant — Story 2.4 forbids inline glyph literals (the constant is currently unused, defined for exactly this). The exact strings:
- Fingerprint line (FR-30, final line of the block, 2-space indent like the analysis lines): `  Fingerprint: {HumanLabel}  [{hash}]` — note the **two spaces** before `[` and the **square brackets** around the hash.
- WontFix label (FR-32, standalone, no brackets): `⚑ Known / Won't Fix: {HumanLabel}  {hash}` — two spaces before the hash, **no** brackets (matches the suppression-file line format `hash  # HumanLabel`, deliberately bracket-free).
[Source: epics.md#FR-30, #FR-32; architecture.md#Port Interface Contracts lines 547-553, #AR-10.]

### Files being modified — current state & what to preserve
- **`PollService.java`** (UPDATE) — the two-phase gate from Story 4.2. `poll()` calls `suppressionFilePort.loadHashes()` at line ~96 for the FR-14 ordering and **discards** the result; `gateAndCollectNew` creates records with a hard-coded `false`; `analyseServiceGroup` calls the 4-arg `printAnalysis`. Preserve: the degradation/recovery flow (2.6), the checkpoint-advance-minus-refresh-lag (2.5 D1), the FR-29 ordering, the FR-25 "cache only successful analysis" + re-fetch-before-cache logic, and the two-phase "analyse once per unique fingerprint" (NFR-4) guarantee. The constructor stays as-is.
- **`TerminalOutputAdapter.java`** (UPDATE) — already defines `WONT_FIX`/`ESCALATION` constants and the `printDegraded`/`printRecovery`/`printSuppressionUnreadable` print style (blank line → message → flush). `printAnalysis` currently prints `[i/N] header`, three analysis lines (or the unavailable variant). Add the Fingerprint line to both branches; add `printWontFixLabel`. Do not disturb `header(...)`/`throwingClass(...)`.
- **`TerminalOutputPort.java`** (UPDATE) — interface; the two signature changes ripple to `RecordingTerminal` in `PollServiceTest`.
- **`DeduplicationRecord.java`** (UPDATE) — add `clearWontFix()`. `createNew(fp, now, window, wontFix)` already takes the flag; `incrementOccurrence()`/`withStoredAnalysis()` show the copy-wither idiom to mirror.
- **`ErrorFingerprint.java`** (UPDATE) — add `humanLabel()`; `hash()` already exists and is the copy-paste key.

### Read-only authority (FR-16 / NFR-6)
This story consumes the suppression set but must not write the file. The only suppression touch-point remains `SuppressionFilePort.loadHashes()` (read). Do not add any write/modify path anywhere. No assertion needed beyond the structural one (the port has no write method).

### Testing standards
- `PollServiceTest` is pure (no Spring): hand-written fakes, real `FingerprintService(List.of("be.vdab"))`, `assertEquals`-style counters. To target a specific fingerprint, build the `ErrorLog` with a `be.vdab.*` stack frame and compute `fingerprintService.compute(error).hash()` to seed `FakeSuppression` / `FakeDeduplicationRepository` (the existing `allDuplicatesCyclePrintsNothing…` test shows the pattern).
- The `TerminalOutputAdapter` formatting test captures `System.out` by swapping in a `ByteArrayOutputStream`-backed `PrintStream` in a try/finally (restore the original). Assert the exact Fingerprint line and `⚑` label substrings.
- Build/run via Bash with JDK 21: `export JAVA_HOME="$HOME/.jdks/temurin-21.0.11"; mvn -B -ntp test`. The `mvnw.cmd` wrapper is blocked; the SDKMAN default JDK is **not** 21 in this shell, so the explicit `JAVA_HOME` export is required (learned in Story 4.3). Baseline before this story: 56 pass / 1 skipped.

### Project Structure Notes
- UPDATE: `domain/model/ErrorFingerprint.java`, `domain/model/DeduplicationRecord.java`, `domain/port/out/TerminalOutputPort.java`, `domain/service/PollService.java`, `infrastructure/terminal/TerminalOutputAdapter.java`, `src/test/java/.../domain/service/PollServiceTest.java`.
- NEW (test): a formatting test for `TerminalOutputAdapter` and/or a `humanLabel()` test (extend `FingerprintServiceTest` or add `ErrorFingerprintTest`).
- No NEW production files, no migration, no `application.yml`/`LogguardProperties`/`DomainServiceConfig` change. `SuppressionFilePort`/`SuppressionFileAdapter` unchanged.

### Previous Story Intelligence (Stories 4.3, 4.2, 4.1, 2.6)
- **4.3 (just done, reviewed):** `SuppressionFileAdapter.loadHashes()` now returns an immutable `Set.copyOf` snapshot of the hashes (comments stripped), empty on absent/unreadable, never throws. **Deferred-to-4.4 item (resolve or consciously accept here):** the *absent-file branch wipes `lastKnown` to empty while the unreadable branch keeps it* — an editor that saves via delete-then-recreate can momentarily present an absent file, and a poll landing in that window would see an empty suppression set for that cycle, briefly un-suppressing won't-fix errors (they'd be analysed as new). Now that 4.4 consumes the set, decide: (a) accept (transient, self-corrects next cycle — the re-created file is read next poll; the only cost is one cycle of spurious analysis), or (b) harden in 4.3's adapter (out of this story's "no 4.3 change" scope — would need a follow-up). **Recommendation: accept for the hackathon and note it**; the window is sub-second and the next cycle re-suppresses. See `deferred-work.md` "Deferred from: code review of Story 4.3".
- **4.2:** the two-phase gate, `findActiveByFingerprint` expiry-filtering, `createNew(...,wontFix)`, and "cache only successful analysis" are committed and stable. `FakeDeduplicationRepository.seedActive(...)` and `.only()` exist for tests.
- **4.1:** `FingerprintService` parses frames via a shared FQCN regex; `throwingMethod` = `SimpleClass.method:line` (line KEPT), `hash()` = SHA-256→first 8 hex. `humanLabel()` reuses these fields — no re-parsing of the stack trace needed.
- **2.4/2.6:** `WONT_FIX`/`ESCALATION` constants are defined-but-unused in `TerminalOutputAdapter` precisely for Epic 4; this story lights up `WONT_FIX`. `ESCALATION` stays unused until 4.5.

### Git Intelligence
- Baseline HEAD `1a4e337` "Apply code-review fixes for Story 4.3" (4.3 done, reviewed). Recent commits show the established patterns to mirror: pure-domain value objects with copy-wither methods (`DeduplicationRecord`), `@Component` adapters implementing `domain/port/out` interfaces, status-constant usage in `TerminalOutputAdapter`, and hermetic fake-based `PollServiceTest` cases.

### References
- [Source: epics.md#Story 4.4] — acceptance criteria
- [Source: epics.md#FR-8] — three-state gate incl. won't-fix-from-birth exception
- [Source: epics.md#FR-10] — occurrence count on all paths · [#FR-16] read-only · [#FR-17] WontFix re-encounter label · [#FR-18] Fingerprint identifier · [#FR-19] unsuppression
- [Source: epics.md#FR-30] per-error block format (Fingerprint line) · [#FR-32] WontFix label format
- [Source: architecture.md#Port Interface Contracts (lines 535-553)] — `printAnalysis(error, analysis, fingerprint, hash)`, `printWontFixLabel(humanLabel, hash)`, `DeduplicationRecordRepository` (no bulk query)
- [Source: architecture.md#Data Flow (lines 558-572)] — per-error gate branches incl. `[NEW — hash in suppression] → save(wont_fix=true) → printWontFixLabel()`
- [Source: architecture.md#AR-10] — all terminal output via `TerminalOutputPort`
- [Source: specs/logguard-ai.allium#ProcessWontFixError, #HandleWontFixEncountered, #FormatErrorOutput (line 457)] — won't-fix rules, label source, Fingerprint mock
- [Source: src/main/java/.../domain/service/PollService.java] — the gate being extended
- [Source: src/main/java/.../infrastructure/terminal/TerminalOutputAdapter.java] — print style + status constants

### Questions / Decisions for METIS (non-blocking — defaults chosen so dev can proceed)
1. **HumanLabel abbreviation.** Default = capital-letter acronym (`NullPointerException` → `NPE`, matching the documented example). Alternative = full simple name (`NullPointerException@LabelV2Config:21`). Confirm or override.
2. **Atomic-save un-suppression window** (deferred from 4.3). Default = accept the sub-second transient (analyse-as-new for one cycle, re-suppresses next cycle). Alternative = schedule a 4.3 adapter hardening follow-up. Confirm.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Opus 4.8, 1M context) — dev-story workflow.

### Debug Log References

- Build/test: `export JAVA_HOME="$HOME/.jdks/temurin-21.0.11"; mvn -B -ntp test` → BUILD SUCCESS, **70 run / 0 failures / 1 skipped** (baseline 56 pass / 1 skipped; +14 new tests, no regressions).

### Completion Notes List

- **Implemented FR-8 (won't-fix-from-birth) / FR-10 / FR-17 / FR-18 / FR-19 / FR-30 / FR-32.** The suppression set reloaded each cycle (FR-14, loaded-and-discarded since Story 2.5) is now consumed by `PollService.gateAndCollectNew(errors, suppressed)`.
  - **Won't-fix-from-birth:** a brand-new fingerprint whose hash is suppressed → `DeduplicationRecord` created `wontFix=true`, `occurrenceCount=1`, no LLM, not buffered for a service block; `⚑` label prints once per window.
  - **Active won't-fix still suppressed / cooling:** silent `incrementOccurrence()` (no label, no analysis). Same-batch duplicate of a won't-fix error therefore labels at most once.
  - **Unsuppression within window (FR-19):** active `wontFix=true` whose hash left the file → `clearWontFix().incrementOccurrence()`, silent (cooling = no fresh analysis/display; escalation is Story 4.5).
  - **Unsuppression expired / expired-won't-fix re-encounter:** no special branch — `findActiveByFingerprint` already filters expired rows (4.2), so the error is "active absent" and either recreated won't-fix-from-birth (still suppressed) or analysed as brand-new (removed).
- **HumanLabel (Task 1) — METIS decision applied: FULL simple exception name, NOT the acronym.** `ErrorFingerprint.humanLabel()` renders `{SimpleExceptionName}@{SimpleClass}:{line}` (e.g. `NullPointerException@LabelV2Config:21`). Degenerate fingerprints (blank `throwingMethod`, `throwingMethod` == exceptionType, or the `"unknown"` fallback) collapse to just the exception name; blank exceptionType → `UnknownError`.
- **Deliberate spec deviation (carried from the story Dev Notes):** the HumanLabel is **computed from the fingerprint**, not read from the suppression-file `# HumanLabel` comment. The Story 4.3 port is frozen to `Set<String> loadHashes()` (comments parsed away); reopening it to return hash→label would widen a deliberately-minimal read-only contract. The hash is the real key; the computed label is a recognisable cue and serves both the `Fingerprint:` line and the `⚑` label.
- **Atomic-save un-suppression window — METIS decision: ACCEPTED (no 4.3 change).** An editor that saves `suppression.txt` via delete-then-recreate can momentarily present an absent file; a poll landing in that sub-second window sees an empty set and analyses a won't-fix error as new for that one cycle. It self-corrects on the next poll when the re-created file is read. Out of this story's "no 4.3 change" scope; not hardened. (Remains noted in `deferred-work.md` under the Story 4.3 code-review heading.)
- **Output routing (AR-10 / Story 2.4):** the `⚑` glyph comes from the pre-defined `WONT_FIX` constant (previously defined-but-unused), never an inline literal. `Fingerprint:` line = `  Fingerprint: {label}  [{hash}]` (bracketed, both analysis branches); `⚑` label = `⚑ Known / Won't Fix: {label}  {hash}` (no brackets, matching the file line format).
- **Read-only authority (FR-16/NFR-6):** no write path added; `SuppressionFilePort`/`SuppressionFileAdapter` untouched. **No constructor/schema/config/wiring change** — `PollService` already had every dependency.

### File List

- `src/main/java/be/vdab/logguard/domain/model/ErrorFingerprint.java` (UPDATE — `humanLabel()` + private helpers)
- `src/main/java/be/vdab/logguard/domain/model/DeduplicationRecord.java` (UPDATE — `clearWontFix()`)
- `src/main/java/be/vdab/logguard/domain/port/out/TerminalOutputPort.java` (UPDATE — `printAnalysis` signature + `printWontFixLabel`)
- `src/main/java/be/vdab/logguard/infrastructure/terminal/TerminalOutputAdapter.java` (UPDATE — Fingerprint line in both branches + `printWontFixLabel`)
- `src/main/java/be/vdab/logguard/domain/service/PollService.java` (UPDATE — consume suppression set; gate rewrite; class Javadoc)
- `src/test/java/be/vdab/logguard/domain/model/ErrorFingerprintTest.java` (NEW — `humanLabel()` cases)
- `src/test/java/be/vdab/logguard/domain/service/PollServiceTest.java` (UPDATE — fakes + 6 Story 4.4 cases)
- `src/test/java/be/vdab/logguard/infrastructure/terminal/TerminalOutputAdapterTest.java` (NEW — Fingerprint line + `⚑` label formatting)
- `src/test/java/be/vdab/logguard/infrastructure/filesystem/SuppressionFileAdapterTest.java` (UPDATE — `RecordingTerminal` conforms to the new `TerminalOutputPort`)

## Change Log

| Date | Change |
|---|---|
| 2026-06-30 | Story 4.4 drafted via create-story context engine (consume suppression set: won't-fix-from-birth + ⚑ label, Fingerprint output line, on-encounter unsuppression, computed HumanLabel). Resolved key designs against architecture port contracts (computed label vs frozen 4.3 port; event-driven unsuppression — no bulk query). Status → ready-for-dev. |
| 2026-06-30 | Implemented via dev-story. Consumed the suppression set in the dedup gate (won't-fix-from-birth, active-won't-fix silent increment, FR-19 unsuppression via `clearWontFix()`); added computed `ErrorFingerprint.humanLabel()` (METIS decision: full simple exception name); added the per-error `Fingerprint:` line (both analysis branches) + the `⚑ Known / Won't Fix` label via the `WONT_FIX` constant. Atomic-save window accepted per METIS (no 4.3 change). No constructor/schema/config/wiring change. Tests: 70 run / 0 failures / 1 skipped (+14). Status → review. |
| 2026-06-30 | Code review (Opus 4.8; Blind Hunter / Edge Case Hunter / Acceptance Auditor). All 10 ACs PASS. 1 patch applied (`humanLabel()` degenerate-guard hardening: stripped-`exceptionType` comparison + `UnknownError` empty-name fallback, +2 tests), 2 items deferred (won't-fix same-batch label autoflush dependency; AC#3 seeded-expired test gap — both in `deferred-work.md`), 3 dismissed. Tests: 72 run / 0 failures / 1 skipped. Status → done. |
