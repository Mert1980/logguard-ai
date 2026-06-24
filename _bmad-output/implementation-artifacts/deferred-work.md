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
