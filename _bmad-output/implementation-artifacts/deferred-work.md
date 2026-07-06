# Deferred Work

## Deferred from: code review of epics (2026-06-24)

Reviewed commit range `409e8f7..HEAD` (stories 2.4, 2.5, 3.1, 3.2). Items below are real but
not actionable now — either covered by a later epic/story or out of the current acceptance-criteria
scope. Each is tagged with the file(s) involved.

- **Duplicate analysis on crash/failure after partial print** — terminal output is side-effecting and
  is emitted before the checkpoint save; on any failure after some errors are printed (broken pipe, JVM
  crash, save failure), the transaction rolls back the checkpoint and the next cycle re-fetches and
  reprints already-shown errors. Intended to be absorbed by Epic 4 fingerprint dedup (FR-3). Files:
  `PollService.java`, `TransactionalPollUseCase.java`.
- **`throwingClass` can show a framework/JDK frame** — the FR-30 `@{ClassName}` header derives from the
  topmost `at` frame, which may be a JDK/library class (e.g. `NullPointerException@requireNonNull`).
  Proper throwing-class / HumanLabel derivation (first own-code frame) is Epic 4 `FingerprintService`.
  No test covers `throwingClass`. File: `TerminalOutputAdapter.java`.
- **Suppression reload result discarded + unguarded** — `PollService` calls `loadHashes()` for the
  FR-14 ordering contract but ignores the returned set, and there is no try/catch; once Story 4.3 makes
  the adapter actually read/parse the file, a throwing reload would abort the whole cycle instead of
  degrading to "last known state". Files: `PollService.java`, `SuppressionFileAdapter.java`.
- **KBO regex matches only the dotted form `0XXX.XXX.XXX`** — conforms to Story 3.1's stated KBO format,
  but undotted/space-separated KBO numbers would pass through unredacted. Hardening is beyond the current
  AC. File: `TenantDataSanitizer.java`.
- **Email regex false-positives on `word@word.word` diagnostic tokens** — can over-redact legitimate
  root-cause text (e.g. `bean@scope.bean`) to `[EMAIL]`, degrading the LLM payload. Fix is inherently
  ambiguous/risky; low impact. File: `TenantDataSanitizer.java`.
- **LLM called per-error, not per-unique-fingerprint (NFR-4)** — expected at this epic stage; Epic 4
  adds dedup-before-LLM to bound calls to ~5–20 unique fingerprints/cycle. File: `PollService.java`.
- **AR-18 prompt-validation gate not run** — deliberately waived for the hackathon; the live demo
  output serves as the lightweight empirical check (if root-cause lines come back generic, redesign the
  prompt in `llm-analysis.st`). Process item, not code.

## Deferred from: code review of Story 4.2 (2026-06-29, Sonnet 4.6)

Three Sonnet review lenses (Blind Hunter / Edge Case Hunter / Acceptance Auditor) over the 4.2 dedup gate.
Confirmed fixes were applied in-story (failed-analysis not cached; Phase-2 cache-miss now logs; converter
read hardened; across-cycle NFR-4 + failed-analysis tests added). The items below are real but deferred:

- **8-hex fingerprint hash = 32-bit collision space** — two distinct fingerprints sharing an 8-char hash
  would collide on the `UNIQUE(fingerprint_hash)` upsert, silently suppressing one distinct error for the
  window. Negligible at LogGuard's active-table scale (dozens–hundreds of rows; 24h expiry), and the hash
  width is an approved Story 4.1 design decision. Revisit only if widening the hash. Files:
  `ErrorFingerprint.java`, `DeduplicationRecordRepositoryAdapter.java`.
- **Concurrent poll cycles → UNIQUE-constraint race** — the read-then-insert upsert is not atomic; two
  overlapping cycles inserting the same new fingerprint would collide. Mitigated by `@Scheduled` fixedDelay
  (single-threaded, no overlap) — an implicit assumption, not enforced in code. If a custom multi-threaded
  `TaskScheduler` is ever introduced, switch to `MERGE`/upsert SQL. File: `DeduplicationRecordRepositoryAdapter.java`.
- **`deleteExpired` needs a transaction + has no scheduler** — Spring Data derived `deleteByExpiresAtBefore`
  throws `TransactionRequiredException` outside a transaction; the adapter method has no `@Transactional`
  and no call site yet (housekeeping is out of 4.2 scope). When a cleanup job is wired (4.x), make the call
  transactional. Without it, expired rows accumulate. Files: `DeduplicationRecordRepositoryAdapter.java`,
  `DeduplicationRecordJpaRepository.java`.
- **`occurrence_count` is `int`/INTEGER (32-bit)** — silently wraps at ~2.1e9. Implausible per-fingerprint
  volume in 24h, but `long`/BIGINT is free insurance for the 1,000× volume-override context (Story 4.5).
  Files: `DeduplicationRecord.java`, `DeduplicationRecordEntity.java`, `V2__…sql`.
- **`expires_at == now` boundary** — `isAfter` is strict, so a row exactly at its expiry instant is both
  invisible to the gate and skipped by `deleteByExpiresAtBefore` (strict `<`) — a harmless ghost row until
  the next cleanup. Closed-open interval is intentional; note only. File: `DeduplicationRecordRepositoryAdapter.java`.
- **Null `service.name` conflated with a literal `"unknown"` service** — both group under the `"unknown"`
  key in terminal output. Cosmetic; no data loss. File: `PollService.java`.

## Deferred from: code review of Story 4.3 (2026-06-30, Opus 4.8)

Adversarial review (Blind Hunter / Edge Case Hunter / Acceptance Auditor) over the SuppressionFile adapter.
All ACs #1–#10 passed. Two patches were applied in-story (defensive-copy on the success path; stderr cause
on read failure). One item deferred by decision:

- **Absent-file wipes `lastKnown` while unreadable keeps it — atomic-save risk** — AC #5 (absent → empty set)
  and AC #6 (unreadable → last-known) are both spec-correct, but many editors save via delete-then-recreate.
  A poll cycle landing in that brief window hits `!Files.exists` and wipes all suppressions for that cycle.
  No live impact today because `PollService` discards the returned set; once **Story 4.4** consumes it, a
  hot-edit-via-atomic-replace could momentarily un-suppress every won't-fix error. Revisit when wiring the
  set into the dedup gate — e.g. keep last-known on transient absence, or detect the rename window.
  File: `SuppressionFileAdapter.java` (lines 48-50).
  **UPDATE (Story 4.4, 2026-06-30): set now consumed by the dedup gate. METIS consciously ACCEPTED this
  transient** — the window is sub-second and self-corrects on the next poll (the re-created file is read,
  re-suppressing the affected hashes; the only cost is one cycle of spurious analyse-as-new). No 4.3 adapter
  change was made (out of Story 4.4's "no 4.3 change" scope). Still revisitable if the spurious-analysis
  cycle ever proves noticeable in practice.

## Deferred from: code review of 4-4-wont-fix-suppression-integration (2026-06-30, Opus 4.8)

Adversarial review (Blind Hunter / Edge Case Hunter / Acceptance Auditor). All 10 ACs passed; one patch applied
in-story (humanLabel degenerate-guard hardening). Two items deferred by decision:

- **Won't-fix-from-birth same-batch label relies on transactional autoflush, not a local seen-set** — the
  non-suppressed new-error path is guarded by BOTH `newByHash` and the repo, but the suppressed-from-birth
  path adds nothing to `newByHash`, so a second occurrence of the same suppressed hash in one batch is kept
  from re-creating + re-labelling ONLY by `findActiveByFingerprint` seeing the row just `save`d earlier in the
  loop. That holds today because the whole poll is one `@Transactional` cycle (`TransactionalPollUseCase`) and
  Hibernate autoflushes before the query. If the gate is ever moved out of a single autoflushing transaction
  (or the repo becomes non-flushing), the `⚑` label could print N times. Note: same-batch duplicate *counting*
  already depends on this autoflush for BOTH paths (pre-existing, Story 4.2). Fix when relevant: track
  suppressed-from-birth hashes in a local `Set<String>` guarding the create+label. File: `PollService.java`
  (lines ~209-213).
- **AC#3 (expired won't-fix re-encounter) covered only via an unseeded-record proxy** — `FakeDeduplicationRepository`
  deliberately ignores `expiresAt` (so existing seeded-active tests with past timestamps still read as active),
  which means no `PollServiceTest` seeds an actually-expired `wontFix=true` row and asserts it is recreated
  won't-fix-from-birth with a fresh-window label. AC#3's precondition ("`findActiveByFingerprint` returns
  empty") is exercised by the from-birth + unsuppression-expired tests, and the real expiry filtering is owned
  by the Story 4.2 adapter tests, so behaviour is covered — only the single end-to-end expired-then-relabel
  unit test is missing. Adding it cleanly would require teaching the fake to filter expiry. File:
  `PollServiceTest.java`.

## Deferred from: code review of 4-5-escalation-threshold-re-notifications (2026-07-01, Opus 4.8)

Adversarial review (Blind Hunter / Edge Case Hunter / Acceptance Auditor). All 10 ACs passed. One decision item
handled separately (threshold-selection `.min()` vs largest-rung). One item deferred:

- **No validation of non-positive / nonsensical `escalation-thresholds` entries** — a `0` or negative entry in
  `logguard.escalation-thresholds` is silently inert (the `threshold > lastNotified` filter with `lastNotified`
  starting at 0 means a `0` never fires, and a negative never satisfies `> 0`), rather than being rejected at
  startup. Duplicates are behaviourally harmless (`.sorted()` keeps both; the `>` + `lastNotified` advance
  dedupe). Benign — operator-controlled config, no wrong state or crash — but a startup validation that rejects
  non-positive thresholds would fail fast on misconfiguration. File: `PollService.java` (constructor, ~99-101)
  or `LogguardProperties`.

## Deferred from: code review of 4-1-errorfingerprint-computation (2026-07-01, Opus 4.8)

Adversarial review (Blind Hunter / Edge Case Hunter / Acceptance Auditor). All 11 ACs passed on existing tests.
One patch applied in-story (module-prefix regex fix in `FingerprintService`). Two items deferred:

- **`LlmAdapter` has the same module-prefix regex limitation** — its `FRAME` pattern
  (`^\s*at\s+([\w$.]+)\.[\w$<>]+\(`, LlmAdapter.java ~108) excludes `/`, so on a Java 9+ JVM it drops
  module-prefixed frames (`java.base/…`) and would miss modularized `be.vdab` own-code frames when building
  the LLM payload's own-code section. Lower impact than the fingerprint case (it only trims the payload, not
  the dedup identity) and out of Story 4.1 scope (Story 3.2 is `done`). Same one-line fix: optional
  `(?:[\w$.]+/)?` module prefix. File: `LlmAdapter.java`.
- **`Caused by:` / suppressed sections flattened in `FingerprintService`** — `parseFrames` matches every `at`
  line across the whole trace, so a wrapped exception's `throwingMethod` anchors on the OUTER wrapper's first
  own-code frame and `stackTraceSequence` concatenates own-code frames from both the wrapper and the cause.
  The real bug is usually the deepest `Caused by:`. This is a DOCUMENTED MVP decision (Story 4.1 Dev Notes
  "Stack trace format": "treat the whole string uniformly … no nested-cause special handling required by the
  AC"), recorded here for post-MVP: anchor the throw site on the deepest `Caused by:` own-code frame. File:
  `FingerprintService.java` (~147-159).
