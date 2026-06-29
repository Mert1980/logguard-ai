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
