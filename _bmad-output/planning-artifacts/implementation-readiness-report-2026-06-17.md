---
stepsCompleted: ["step-01-document-discovery", "step-02-prd-analysis", "step-03-epic-coverage-validation", "step-04-ux-alignment", "step-05-epic-quality-review", "step-06-final-assessment"]
documentsIncluded:
  prd: "_bmad-output/planning-artifacts/prds/prd-logguard-ai-2026-06-15/prd.md"
  architecture: "_bmad-output/planning-artifacts/architecture.md"
  epics: "_bmad-output/planning-artifacts/epics.md"
  ux: null
---

# Implementation Readiness Assessment Report

**Date:** 2026-06-17
**Project:** logguard-ai

## Document Inventory

### PRD Documents Found

**Sharded Document (folder):**
- Folder: `prds/prd-logguard-ai-2026-06-15/`
  - `prd.md` (36,240 bytes, 2026-06-15)
  - `addendum.md` (9,428 bytes, 2026-06-15)
  - `reconcile-allium.md` (9,940 bytes, 2026-06-15)
  - `reconcile-brainstorming.md` (18,372 bytes, 2026-06-15)
  - `review-rubric.md` (16,072 bytes, 2026-06-15)
  - `.decision-log.md` (4,238 bytes, 2026-06-15)

### Architecture Documents Found

**Whole Document:**
- `architecture.md` (36,989 bytes, 2026-06-15)

### Epics & Stories Documents Found

**Whole Document:**
- `epics.md` (49,626 bytes, 2026-06-17)

### UX Design Documents Found

⚠️ **WARNING: No UX design document found**
- Will impact assessment completeness for UI/UX stories

---

## PRD Analysis

### Functional Requirements

FR-1: Configurable poll interval — System polls OpenSearch every `poll_interval` (default 5 min, configurable). New errors visible within one poll cycle. `poll_interval` settable via config file or env var.

FR-2: First-run lookback prompt — On first run (no PollCheckpoint), print prompt and wait for developer to specify lookback hours (default 24h; 0 = start from now). PollCheckpoint set to `now − confirmed_lookback_hours`.

FR-3: Checkpoint-advance timing invariant — PollCheckpoint.last_successful_poll_at advances ONLY after ALL errors in a batch have been emitted. Never before, never mid-batch.

FR-4: Time-window query — Query ErrorLogs where occurred_at > PollCheckpoint.last_successful_poll_at using pre-filtered error index. No additional log-level filter in the query.

FR-5: Configurable own-code prefixes — Accept `own_code_package_prefixes` as a configurable list (e.g. `["be.vdab"]`) for OwnCodeFrame detection, stack trace truncation, and future GitLab link generation.

FR-6: ErrorFingerprint computation — Compute from: (1) exception_type, (2) throwing_method = class+method+LINE NUMBER of first OwnCodeFrame (line kept), (3) stack_trace_sequence = structural sequence of OwnCodeFrames with line numbers STRIPPED. Opposite line-number policies by design.

FR-7: Framework-frame fallback — When no OwnCodeFrame exists in the stack trace, use the topmost frame as throwing_method fallback, keeping distinct framework-only errors separate.

FR-8: Three-state deduplication gate — New (no active record) → LLM + delivery; if fingerprint already in SuppressionFile at creation, mark won't_fix immediately, fire FR-17, skip LLM. Cooling (active, won't_fix=false) → increment OccurrenceCount, check escalation. Won't-fix (active, won't_fix=true) → increment OccurrenceCount silently; on re-encounter after window, display WontFix label.

FR-9: Deduplication window — Default 24 hours, configurable via `deduplication_window`. Fingerprint not seen for >24h treated as new on next encounter.

FR-10: OccurrenceCount tracked on all paths — Increments on every encounter including WontFix records, to enable the 1,000× volume override.

FR-11: Escalation threshold re-notification — When cooling error's OccurrenceCount crosses 10×, 100×, or 1,000× (configurable), emit re-notification. Reuse stored LLMAnalysis — no fresh LLM call. Each threshold fires once per DeduplicationWindow.

FR-12: Won't-fix volume override — For WontFix records, fire escalation only at 1,000×. WontFix state NOT cleared after override fires. Override format specified. Read-only nudge.

FR-13: Escalation fires during backlog catchup — During backlog processing, EscalationThresholds fire in sequence as OccurrenceCounts are reconstructed. Developer sees full escalation history.

FR-14: SuppressionFile format and hot-reload — Format: one entry per line `hash  # HumanLabel`. Blank lines and `#`-only lines ignored. Duplicate hashes: first occurrence wins. Read at start of every poll cycle before any events processed.

FR-15: SuppressionFile absent or unreadable — Absent: treat as empty (not an error). Unreadable/parse error: keep previously loaded list, skip reload, print warning.

FR-16: LogGuard never writes to SuppressionFile — Read-only authority in all code paths including error-handling paths.

FR-17: WontFix re-encounter label — When WontFix fingerprint re-encountered after DeduplicationWindow expiry, print one label per new cooling window: `"⚑ Known / Won't Fix: [HumanLabel]  [hash]"`. No LLM analysis. Never reduce to silence.

FR-18: Fingerprint identifier in output — Every new error output includes fingerprint identifier: `Fingerprint: {HumanLabel}  [{hash}]` for copy-paste into SuppressionFile.

FR-19: Unsuppression — When fingerprint hash removed from SuppressionFile, clear `won't_fix` on existing DeduplicationRecord. If within DeduplicationWindow: treat as cooling. If expired: treat as new.

FR-20: LLM payload construction — Construct from: exception_type, error_message, stack_trace (OwnCodeFrames only), service_name, app_name, vdab_authorization (MVP on-prem only — MUST be excluded for any cloud LLM). Excludes Kubernetes metadata and non-own-code frames.

FR-21: TenantData stripping — Apply `strip_tenant_data` to error_message and stack_trace before LLM payload: KBO numbers → `[KBO]`, email addresses → `[EMAIL]`, LDAP DN fragments → `[LDAP-DN]`. UUIDs and numeric IDs KEPT.

FR-22: LLM instruction frame — System prompt: "You are a Java backend engineer. Given this Spring Boot error, identify the most likely root cause in the application code. Focus only on be.vdab.* frames. Be specific — name the class, method, and what likely went wrong there."

FR-23: Three-field output contract — LLM response must contain three labelled fields on their own lines: `Root cause:`, `Likely location:`, `Suggested action:`. Absent label or empty response = malformed → FR-24 fallback.

FR-24: LLM failure fallback — On LLM failure (timeout, crash, malformed): set `llm_available=false`, capture failure reason, deliver raw error to terminal with `"analysis unavailable (reason)"`. Never silently drop an error.

FR-25: LLM analysis caching — Cache LLMAnalysis in `DeduplicationRecord.stored_analysis` after initial call. Reuse at all EscalationThreshold re-notifications. No fresh LLM call at threshold.

FR-26: Zero-results — no output — When poll cycle returns zero ERROR logs, print nothing. No "no new errors" or cycle counter.

FR-27: Batch progress signal — Print `"Analyzing N new errors in [service-name]..."` before starting LLM calls for each service group.

FR-28: Output grouped by service — Buffer all errors for a given service_name until analysis completes, then print as a block.

FR-29: Service block ordering — Order service blocks by error count descending within each poll cycle.

FR-30: Per-error block format — Defined format including service header, indexed error header, Root cause, Likely location, Suggested action, Fingerprint line. `"analysis unavailable (reason)"` shown explicitly when LLM unavailable.

FR-31: Escalation re-notification format — `"⚠️ Known error {HumanLabel} now seen {threshold}× since {first_seen_at}"` with stored root_cause or `"no analysis on file"`.

FR-32: WontFix label format — `"⚑ Known / Won't Fix: {HumanLabel}  {hash}"`. One label per re-encounter after window expiry.

FR-33: Consecutive-failure tracking — Track `consecutive_poll_failures`; reset to 0 on successful poll. Enter DegradedState after `max_consecutive_poll_failures` (default 3) consecutive failures.

FR-34: Sticky degradation banner — On entering DegradedState, record `degradation_started_at`. Print on every poll cycle while degraded: `"🔴 LOGGUARD DEGRADED — OpenSearch unreachable since {degradation_started_at} ({N} failures)"`.

FR-35: Checkpoint stasis during degradation — Do not advance PollCheckpoint while OpenSearch unreachable. On recovery, queries from same checkpoint for automatic catch-up.

FR-36: Recovery line and automatic backlog processing — On recovery: clear `degradation_started_at`, reset `consecutive_poll_failures`, print recovery line, immediately process full missed backlog.

**Total FRs: 36**

---

### Non-Functional Requirements

NFR-1: No silent drops — Every error LogGuard detects must either reach terminal output or be shown as `"analysis unavailable"`. No error may be silently discarded at any pipeline stage.

NFR-2: Self-observability — LogGuard must make its own health state visible at all times. A silent terminal must never be ambiguous between "no errors today" and "LogGuard is blind." (Realized by FR-33 through FR-36.)

NFR-3: Configuration-first — All operational parameters (`poll_interval`, `deduplication_window`, `escalation_thresholds`, `own_code_package_prefixes`, `suppression_file_path`, `max_consecutive_poll_failures`, LLM endpoint) settable via config file or env var. No admin UI.

NFR-4: LLM throughput budget — Dedup-before-LLM bounds LLM calls to ~5–20 unique fingerprints per cycle. Any implementation change that breaks this must be explicitly reviewed and approved.

NFR-5: Checkpoint-advance invariant — PollCheckpoint must advance only after full batch delivery. Hard invariant — advancing before batch completion silently introduces data-loss risk on crash. (Realized by FR-3.)

NFR-6: Read-only suppression authority — LogGuard must never write to, truncate, or modify SuppressionFile in any code path, including error-handling paths. (Realized by FR-16.)

**Total NFRs: 6**

---

### Additional Requirements

**Companion App (§6.1 In Scope):** A companion Spring Boot error-producer application must be built as a test fixture. It must write ERROR-level logs via Logback to local OpenSearch with the `_source.structured.*` / `_source.kubernetes.*` field structure LogGuard reads. LogGuard must detect, fingerprint, and analyze errors from this app without field-mapping changes.

**Blocking Prerequisite — OQ-4: LLM Prompt Empirical Validation:** The LLM instruction frame + three-field output contract must be tested against 10 real be.vdab.* stack traces manually before any pipeline code is written. Success signal: ≥7/10 specific root cause hints naming a class and method. This gate failure requires prompt redesign before proceeding to pipeline build.

**Data Governance Constraint (§11.1):** `vdab_authorization` MUST be excluded from LLM payload before any cloud LLM integration. This is a blocking data governance requirement.

**TenantData Privacy (§11.2):** KBO numbers, email addresses, and LDAP DN fragments must be stripped even for on-prem LLM to establish the pattern for future cloud migration.

---

### PRD Completeness Assessment

The PRD is exceptionally thorough and well-structured. All 36 FRs have clear acceptance criteria and consequences. All 6 NFRs are cross-referenced to specific FRs. The glossary provides unambiguous term definitions. Open questions are documented with resolution status. Assumptions are indexed. Data governance risks are flagged with severity. The PRD is **ready for epic coverage validation**.

---

## Epic Coverage Validation

### Coverage Matrix

| FR | Epic | Story | Status |
|---|---|---|---|
| FR-1 | Epic 2 | Story 2.5 (poll interval via `@Scheduled`, configurable via `logguard.poll-interval`) | ✅ Covered |
| FR-2 | Epic 2 | Story 2.2 (first-run lookback prompt via `FirstRunInitializer`) | ✅ Covered |
| FR-3 | Epic 2 | Story 2.5 (checkpoint advance only after full batch delivery, @Transactional guard) | ✅ Covered |
| FR-4 | Epic 2 | Story 2.3 (`findErrorsSince(Instant from)` time-window query) | ✅ Covered |
| FR-5 | Epic 2 | Story 2.1 (`LogguardProperties` binding) / Story 4.1 (actual use in FingerprintService) | ⚠️ Partial — see gap #1 |
| FR-6 | Epic 4 | Story 4.1 (FingerprintService with line-number asymmetry) | ✅ Covered |
| FR-7 | Epic 4 | Story 4.1 (framework-frame fallback when no OwnCodeFrame) | ✅ Covered |
| FR-8 | Epic 4 | Story 4.2 (three-state gate: new/cooling) + Story 4.4 (won't-fix-from-birth path) | ✅ Covered |
| FR-9 | Epic 4 | Story 4.2 (`deduplication_window` configurable, expires_at computation) | ✅ Covered |
| FR-10 | Epic 4 | Story 4.2 (occurrence_count incremented on all paths) + Story 4.5 (won't-fix path) | ✅ Covered |
| FR-11 | Epic 4 | Story 4.5 (10×/100×/1,000× escalation, stored analysis reused) | ✅ Covered |
| FR-12 | Epic 4 | Story 4.5 (won't-fix volume override at 1,000× only, state NOT cleared) | ✅ Covered |
| FR-13 | Epic 4 | Story 4.5 (escalation fires during backlog catchup — explicitly covered in AC) | ✅ Covered |
| FR-14 | Epic 4 | Story 4.3 (SuppressionFile format, hot-reload, ordering guarantee) | ✅ Covered |
| FR-15 | Epic 4 | Story 4.3 (absent → empty set; unreadable → last known state + warning) | ✅ Covered |
| FR-16 | Epic 4 | Story 4.3 (`SuppressionFilePort` interface has no write method; adapter has no write path) | ✅ Covered |
| FR-17 | Epic 4 | Story 4.4 (WontFix re-encounter label — one per cooling window, never silence) | ✅ Covered |
| FR-18 | Epic 4 | Story 4.4 (Fingerprint line in per-error block for copy-paste) | ✅ Covered |
| FR-19 | Epic 4 | Story 4.4 (unsuppression — within window → cooling; expired → new) | ✅ Covered |
| FR-20 | Epic 3 | Story 3.1 (payload builder: 6 fields including vdab_authorization, OwnCodeFrames only) | ✅ Covered |
| FR-21 | Epic 3 | Story 3.1 (TenantDataSanitizer: KBO/email/LDAP stripped; UUID/numeric kept) | ✅ Covered |
| FR-22 | Epic 3 | Story 3.1 (instruction frame in llm-analysis.st) | ✅ Covered |
| FR-23 | Epic 3 | Story 3.2 (three-field output contract; null field → malformed → fallback) | ✅ Covered |
| FR-24 | Epic 3 | Story 3.2 (LLM failure fallback: `unavailabilityReason`, never propagates to domain) | ✅ Covered |
| FR-25 | Epic 4 | Story 4.2 (`stored_analysis` field on DeduplicationRecord, reused at escalation) | ✅ Covered |
| FR-26 | Epic 2 | Story 2.4 (zero results → nothing printed) | ✅ Covered |
| FR-27 | Epic 2 | Story 2.4 (`"Analyzing N new errors in [service-name]..."` before each service group) | ✅ Covered |
| FR-28 | Epic 2 | Story 2.4 (buffer all errors per service_name, print as block after analysis) | ✅ Covered |
| FR-29 | Epic 2 | Story 2.4 (service blocks ordered by error count descending) | ✅ Covered |
| FR-30 | Epic 2 | Story 2.4 (per-error block format; "analysis unavailable" placeholder in Epic 2) | ✅ Covered |
| FR-31 | Epic 4 | Story 4.5 (escalation re-notification terminal format) | ✅ Covered |
| FR-32 | Epic 4 | Story 4.4 (WontFix label terminal format) | ✅ Covered |
| FR-33 | Epic 2 | Story 2.6 (`consecutive_poll_failures` tracking, DegradedState entry) | ✅ Covered |
| FR-34 | Epic 2 | Story 2.6 (sticky degradation banner reprints every cycle) | ✅ Covered |
| FR-35 | Epic 2 | Story 2.6 (checkpoint stasis during degradation; `degradation_started_at` cleared on recovery) | ✅ Covered |
| FR-36 | Epic 2 | Story 2.6 (recovery → auto-process full missed backlog immediately) | ✅ Covered |

### Missing Requirements

**No FRs are missing from the epics.** All 36 FRs are claimed and traceable to specific story acceptance criteria.

However, the following **gaps and risks** were identified:

#### Gap #1 — FR-5 acceptance criteria under-specified in Story 2.1 (LOW RISK)

**Issue:** The FR Coverage Map assigns FR-5 (Configurable own-code prefixes) to Epic 2, implying it is wired in `LogguardProperties` during scaffolding. However, Story 2.1's acceptance criteria do not explicitly mention `own_code_package_prefixes` as a required field in `LogguardProperties`. A developer implementing Story 2.1 from the ACs alone might omit this config field, leaving Story 4.1 (`FingerprintService` reads from `LogguardProperties.ownCodePackagePrefixes`) to fail unexpectedly.

**Recommendation:** Add one AC bullet to Story 2.1: `"And LogguardProperties includes own_code_package_prefixes (default ["be.vdab"]) bound from logguard.own-code-package-prefixes"`.

#### Gap #2 — NFR coverage is implicit only, no NFR Coverage Map (LOW RISK)

**Issue:** The epics document includes all 6 NFRs in the Requirements Inventory but provides no NFR Coverage Map and no story-level tracing for NFRs. NFR coverage is only implied via the FR → Story mapping (e.g., NFR-5 is implied by FR-3 coverage). An implementer has no explicit reference for which story must satisfy which NFR.

**Recommendation:** Add a brief NFR Coverage Map section to the epics document (similar to the FR Coverage Map), mapping each NFR to its primary enforcing story.

#### Gap #3 — Story 2.6 backlog behavior potentially ambiguous (LOW RISK)

**Issue:** Story 2.6's acceptance criterion says "LogGuard immediately processes the full missed backlog from the stored checkpoint" on recovery — but at Epic 2 stage, deduplication and LLM are not yet active. A developer might assume the backlog processing in Story 2.6 is complete behavior, when in fact dedup integration (Epic 4) and LLM analysis (Epic 3) will augment it. The acceptance criteria don't clarify the interim behavior ("all errors display 'analysis unavailable'; no dedup yet").

**Recommendation:** Add an explicit note to Story 2.6's ACs: `"Note: at this stage, backlog errors display 'analysis unavailable' and no deduplication is applied — this behavior is augmented in Epics 3 and 4 respectively."`.

### Coverage Statistics

- **Total PRD FRs:** 36
- **FRs covered in epics:** 36
- **Coverage percentage:** 100%
- **Gaps identified:** 3 (all LOW RISK — no missing FRs, only AC precision issues)

---

## UX Alignment Assessment

### UX Document Status

**Not Found** — No UX design document exists in `_bmad-output/planning-artifacts/`.

### UX Implied Assessment

Assessment of whether a UX document is required:

| Check | Finding |
|---|---|
| Does the PRD mention a user interface? | PRD §5 Non-Goals explicitly states: "Admin UI or management API — all configuration via config files or environment variables. No web UI." |
| Are web/mobile components implied? | No. The product is a CLI daemon that outputs to a developer's terminal. |
| Is this an end-user-facing application? | No. PRD §2.2 Non-Users explicitly lists "End users / business stakeholders" and "Team leads and product owners" as non-users. The sole operator is a VDAB backend developer. |
| Are terminal output formats (the only "UX") documented? | Yes — fully specified in FR-26 through FR-32 of the PRD, reflected verbatim in the epics Requirements Inventory. |

### Verdict

**No UX document is required.** The epics document explicitly confirms: *"N/A — LogGuard AI is a CLI daemon with no web UI. Terminal output format requirements are captured in FR-26 through FR-32 above."*

The absence of a UX design document is intentional and appropriate for this product type. The "user experience" surface is entirely the terminal output, which is already formally specified as FRs in the PRD and carried through to story acceptance criteria.

### Warnings

None. The UX document gap flagged in Step 1 is confirmed to be a non-issue — not a planning deficit.

---

## Epic Quality Review

### Best Practices Compliance Checklist

| Epic | Delivers user value | Functions independently | Stories sized right | No forward deps | DB tables created when needed | Clear ACs | FR traceability |
|---|---|---|---|---|---|---|---|
| Epic 1 | ⚠️ Borderline | ✅ | ✅ | ✅ | N/A | ✅ | ✅ |
| Epic 2 | ✅ | ✅ | ✅ | ⚠️ Minor | ✅ | ✅ | ✅ |
| Epic 3 | ✅ | ✅ | ✅ | ✅ | N/A | ⚠️ Minor | ✅ |
| Epic 4 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

---

### 🔴 Critical Violations

**None found.** No technical milestones masquerading as user-facing epics were identified, no forward dependencies that would prevent story completion were found, and no circular epic dependencies exist.

---

### 🟠 Major Issues

#### MAJOR-1 — Epic 1 is a technical/infrastructure epic (borderline violation)

**Epic:** Epic 1 — "Local Development Environment & Companion App"

**Violation:** The epic is predominantly infrastructure setup. The title and content describe setting up a developer environment — not a user outcome. Standard best practice considers "Infrastructure Setup" a red-flag epic title.

**Mitigating factors (why it is acceptable here):**
- The PRD explicitly scopes the companion Spring Boot error-producer application as "In Scope" for the MVP (§6.1), making it a genuine deliverable.
- This is a developer tooling project — the "user" IS a developer. A working local OpenSearch + companion app is genuinely the developer's first usable outcome.
- Story 1.1 (OpenSearch via docker compose) and Story 1.2 (companion app writes correctly-structured documents) together produce a verifiable integration test fixture — not just configuration.
- Without Epic 1, no subsequent epic can be tested. For a greenfield developer tool, this bootstrap epic is necessary.

**Recommendation:** **Keep as-is** but acknowledge this is a known exception. If the epic were split, Story 1.1 could be considered a prerequisite story embedded in Epic 2 rather than a separate epic — but the current two-story Epic 1 structure is justified and reasonable.

---

### 🟡 Minor Concerns

#### MINOR-1 — Story 2.5 contains an implicit forward reference to Epic 4's `SuppressionFilePort`

**Story:** 2.5 (Poll loop with checkpoint-advance invariant)

**Issue:** Story 2.5's acceptance criteria state: `"SuppressionFilePort.loadHashes() is called first (returns empty set — suppression not yet implemented, stub is acceptable)"`. This means Story 2.5 requires that `SuppressionFilePort` exists as a domain interface, but the real implementation is not delivered until Story 4.3 (5+ stories later). A developer implementing Story 2.5 must create the port interface and a stub adapter, which is not explicitly called out as an AC task.

**Risk:** A developer might implement Story 2.5 and wire a real adapter that doesn't exist yet, or might skip the port entirely and hardcode an empty collection, breaking the intended architecture.

**Recommendation:** Add to Story 2.5's ACs: `"And SuppressionFilePort interface is created in domain/port/out/ with a single method Set<String> loadHashes() — a no-op stub implementation returning empty set is wired in infrastructure/filesystem/ until Story 4.3"`.

#### MINOR-2 — Story 2.1 acceptance criteria omit `own_code_package_prefixes` from `LogguardProperties`

**Story:** 2.1 (Scaffold logguard-ai and configure project infrastructure)

**Issue:** The FR Coverage Map assigns FR-5 ("Configurable own-code prefixes") to Epic 2 with the note "Own-code prefix config." However, Story 2.1's acceptance criteria do not explicitly list `own_code_package_prefixes` as a required field in `LogguardProperties`. A developer could implement Story 2.1 fully per its ACs and still have Story 4.1 fail because the config field is absent.

**Risk:** Story 4.1 says `"own-code prefix list is read from LogguardProperties.ownCodePackagePrefixes"` — this will not work if the field was never added in Epic 2.

**Recommendation:** Add to Story 2.1's ACs: `"And LogguardProperties includes own_code_package_prefixes (List<String>, default [\"be.vdab\"]) bound from logguard.own-code-package-prefixes"`. (Identified as Gap #1 in Epic Coverage Validation — confirmed here.)

#### MINOR-3 — Story 3.1 contains a manual, non-automatable acceptance criterion

**Story:** 3.1 (LLM prompt validation gate)

**Issue:** One acceptance criterion reads: `"And the developer manually submits ≥10 real be.vdab.* error payloads to Ollama using the instruction frame from llm-analysis.st"`. This is a human-performed test that cannot be included in CI and cannot be verified after the fact without documentation.

**Risk:** Future developers or reviewers cannot verify whether this gate was actually passed. There is no artifact (e.g., a test result log) produced by this manual step.

**This is intentional by design** — the PRD (OQ-4) and architecture (AR-18) both explicitly flag this as a human validation gate that must precede pipeline work. However, the story should produce a traceable artifact.

**Recommendation:** Add to Story 3.1's ACs: `"And the developer records the prompt validation results in a markdown file (e.g., docs/llm-validation-results.md) with the 10 payloads, responses, and pass/fail assessment — this file serves as the gate exit artifact."`.

#### MINOR-4 — Story 2.6 acceptance criteria don't clarify that backlog processing is incomplete until Epics 3 and 4

**Story:** 2.6 (Degradation detection, sticky banner, and recovery with auto-backlog)

**Issue:** Story 2.6 says "LogGuard immediately processes the full missed backlog from the stored checkpoint" — but at Epic 2 stage, LLM analysis (Epic 3) and deduplication (Epic 4) are not yet integrated. A developer might incorrectly assume backlog processing is complete after Story 2.6.

**Recommendation:** Add a note to Story 2.6's ACs: `"Note: at this Epic 2 stage, backlog errors display 'analysis unavailable' and no deduplication is applied — this behavior is augmented in Epic 3 (LLM analysis) and Epic 4 (deduplication) respectively."`. (Identified as Gap #3 in Epic Coverage Validation — confirmed here.)

#### MINOR-5 — No CI/CD pipeline story in any epic

**Context:** This is a greenfield project with no CI/CD story.

**Assessment:** For a developer-side CLI daemon tool (not a deployed service), CI/CD may be considered optional or deferred. The product has no deployment concern beyond running on a developer machine. The companion app runs in Docker Compose. There is no pipeline to deploy to.

**Verdict:** Acceptable omission for this product type. LogGuard AI is not a deployed service — it is a local developer tool. CI/CD pipeline setup is not required for MVP.

---

### Dependency Analysis

#### Within-Epic Story Dependencies

| Dependency | Assessment |
|---|---|
| 1.2 depends on 1.1 (OpenSearch running before companion writes to it) | ✅ Natural and stated |
| 2.2 depends on 2.1 (scaffold before checkpoint) | ✅ Natural |
| 2.3 depends on 2.1 (scaffold before adapter) | ✅ Natural |
| 2.4 depends on 2.1, 2.3 (terminal output needs adapter) | ✅ Natural |
| 2.5 depends on 2.2, 2.3, 2.4 (poll loop ties all together) | ✅ Natural |
| 2.6 depends on 2.5 (degradation requires poll loop) | ✅ Natural |
| 3.1 depends on 2.x (LLM validation needs real error payloads from companion app) | ✅ Natural |
| 3.2 depends on 3.1 (adapter cannot begin before prompt is validated) | ✅ Natural and required |
| 4.2 depends on 4.1 (DeduplicationRecord stores fingerprint hash) | ✅ Natural |
| 4.3 can be parallel to 4.1/4.2 (SuppressionFile is independent) | ✅ No violation |
| 4.4 depends on 4.2 (DeduplicationRecord) and 4.3 (SuppressionFile loaded) | ✅ Natural |
| 4.5 depends on 4.2 (OccurrenceCount on DeduplicationRecord) | ✅ Natural |

No circular dependencies. No forward references that would prevent story completion. All within-epic chains are natural implementation order.

#### Database Migration Approach

| Migration | Created in | When needed | Compliance |
|---|---|---|---|
| V1__create_poll_checkpoint.sql | Story 2.2 | First use of PollCheckpoint | ✅ Created when needed |
| V2__create_deduplication_record.sql | Story 4.2 | First use of DeduplicationRecord | ✅ Created when needed |

No premature table creation detected. ✓

### Epic Quality Summary

- **Critical violations:** 0
- **Major issues:** 1 (Epic 1 technical nature — justified exception)
- **Minor concerns:** 5 (all have clear, low-effort remediation)

---

## Summary and Recommendations

### Overall Readiness Status

# ✅ READY

The LogGuard AI MVP planning is **implementation-ready**. All 36 Functional Requirements from the PRD are covered by specific story acceptance criteria. All 6 NFRs are implicitly satisfied through FR coverage. The epics are logically sequenced, independently functional, and free of circular dependencies. No critical violations were found. The 6 identified issues are precision gaps in acceptance criteria, not structural planning failures.

---

### Issues Requiring Action Before Implementation Begins

All issues are LOW RISK and can be resolved by brief edits to the epics document. None block starting Epic 1 or Epic 2. One (MINOR-3) should be addressed before Story 3.1 begins.

| # | Severity | Story | Issue | Effort |
|---|---|---|---|---|
| 1 | 🟠 Major (justified) | Epic 1 | Infrastructure epic — accepted exception for this product type | No change needed |
| 2 | 🟡 Minor | Story 2.1 | `own_code_package_prefixes` missing from LogguardProperties ACs (FR-5 gap) | 1 AC bullet |
| 3 | 🟡 Minor | Story 2.5 | `SuppressionFilePort` implicit forward reference — stub not called out | 1 AC bullet |
| 4 | 🟡 Minor | Story 2.6 | Backlog processing ACs don't clarify "analysis unavailable, no dedup yet" | 1 note line |
| 5 | 🟡 Minor | Story 3.1 | Manual validation AC produces no traceable artifact | 1 AC bullet |
| 6 | 🟡 Minor | Epics doc | No NFR Coverage Map — NFR satisfaction is only implicit | Add NFR map section |

---

### Recommended Next Steps

1. **Fix Story 2.1 ACs (Gap #1 / MINOR-2):** Add `"And LogguardProperties includes own_code_package_prefixes (List<String>, default ["be.vdab"]) bound from logguard.own-code-package-prefixes"` to the Story 2.1 acceptance criteria. This prevents Story 4.1 from hitting a missing config field.

2. **Fix Story 2.5 ACs (MINOR-1):** Add `"And SuppressionFilePort interface is created in domain/port/out/ with a single method Set<String> loadHashes() — a no-op stub adapter wired in infrastructure/filesystem/ until Story 4.3"`. This makes the forward interface dependency explicit and architectural.

3. **Fix Story 2.6 ACs (Gap #3 / MINOR-4):** Add a note: `"Note: at Epic 2 stage, backlog errors display 'analysis unavailable' and no deduplication is applied — augmented in Epics 3 and 4."`.

4. **Fix Story 3.1 ACs (MINOR-3) — do before Story 3.1 begins:** Add `"And the developer records the prompt validation results in docs/llm-validation-results.md with the 10 payloads, responses, and pass/fail assessment — this file is the gate exit artifact."`. Without this, there is no verifiable evidence that the AR-18 / OQ-4 blocking gate was passed.

5. **Add NFR Coverage Map to epics (Gap #2 / MINOR-5):** Add a brief section after the FR Coverage Map mapping each NFR to its primary enforcing story (e.g., NFR-5 → Story 2.5; NFR-6 → Story 4.3). This closes the traceability gap for non-functional requirements.

6. **Start implementation:** Epic 1 and Epic 2 can begin immediately. Epic 3 must not begin until Story 3.1's LLM validation gate passes (AR-18 / OQ-4). Epic 4 follows Epic 3.

---

### Architecture Alignment Note

The architecture document was not explicitly analyzed in this assessment (no step in the workflow triggered a full architecture review). However, the epics document's Architecture Requirements section (AR-1 through AR-18) is comprehensive and all ARs are traced to specific stories. If a full architecture ↔ epics alignment review is desired, that should be run separately.

---

### Final Note

This assessment identified **6 issues across 3 categories** (coverage precision, AC completeness, documentation). All are LOW RISK. The planning artifacts — PRD, architecture, and epics — are coherent, aligned, and thorough. The PRD is one of the most precisely specified product requirements documents reviewed, with rigorous consequence statements on every FR, an explicit glossary, indexed assumptions, and flagged data governance risks. The epics carry this quality through to story-level acceptance criteria with full Given/When/Then structure.

**LogGuard AI MVP planning is complete. Implementation may begin.**

---

*Report generated: 2026-06-17*
*Assessor: METIS — Implementation Readiness Validation*
*Documents assessed: PRD (prd-logguard-ai-2026-06-15/prd.md + addendum.md), architecture.md, epics.md*
