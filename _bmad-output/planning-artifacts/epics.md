---
stepsCompleted: [1, 2, 3]
inputDocuments:
  - _bmad-output/planning-artifacts/prds/prd-logguard-ai-2026-06-15/prd.md
  - _bmad-output/planning-artifacts/architecture.md
  - _bmad-output/planning-artifacts/prds/prd-logguard-ai-2026-06-15/addendum.md
  - specs/logguard-ai.allium
  - _bmad-output/planning-artifacts/prds/prd-logguard-ai-2026-06-15/reconcile-brainstorming.md
  - _bmad-output/planning-artifacts/prds/prd-logguard-ai-2026-06-15/reconcile-allium.md
---

# LogGuard AI - Epic Breakdown

## Overview

This document provides the complete epic and story breakdown for LogGuard AI, decomposing the requirements from the PRD, Architecture, addendum, allium spec, and reconcile gap analyses into implementable stories.

## Requirements Inventory

### Functional Requirements

FR-1: Configurable poll interval — System polls OpenSearch every `poll_interval` (default 5 min, configurable). New errors visible within one poll cycle. Settable via config/env without code changes.

FR-2: First-run lookback prompt — On first run (no PollCheckpoint), print: "First run detected. Process last [N] hours of history? (default: 24h)". Accept hours; 0 = start from now. Sets PollCheckpoint.last_successful_poll_at to now − confirmed_lookback_hours.

FR-3: Checkpoint-advance timing invariant — PollCheckpoint.last_successful_poll_at advances ONLY after ALL errors in the batch have been emitted — never before, never mid-batch. On restart, same batch re-fetched; dedup absorbs duplicates.

FR-4: Time-window query — Query ErrorLogs where occurred_at > PollCheckpoint.last_successful_poll_at using the pre-filtered error index. No additional log-level filter required.

FR-5: Configurable own-code prefixes — Accept `own_code_package_prefixes` as a configurable list (e.g. ["be.vdab"]) for OwnCodeFrame detection, stack trace truncation, and post-MVP GitLab link generation.

FR-6: ErrorFingerprint computation — Compute from: `exception_type`; `throwing_method` (first OwnCodeFrame class + method + LINE NUMBER kept); `stack_trace_sequence` (OwnCodeFrames normalized, line numbers STRIPPED). Line-number asymmetry is by design: throwing_method KEEPS line number (distinguishes two bugs in same method); stack_trace_sequence STRIPS it (survives deploys that shift call-path lines).

FR-7: Framework-frame fallback — When no OwnCodeFrame exists (Spring startup, HikariCP, static 404s), use topmost frame as throwing_method fallback. Prevents distinct framework-only errors collapsing into one fingerprint.

FR-8: Three-state deduplication gate —
  - NEW (no active record): create DeduplicationRecord, proceed to LLM analysis. EXCEPTION (won't-fix-from-birth): if fingerprint hash is already in SuppressionFile at creation time, create record with won't_fix=true immediately, fire WontFix label (FR-17), skip LLM analysis — no cooling period first.
  - COOLING (active record, won't_fix=false): increment OccurrenceCount, check EscalationThresholds.
  - WON'T-FIX (active record, won't_fix=true): increment OccurrenceCount silently; on re-encounter after window expires, display WontFix label.

FR-9: Deduplication window — Default 24 hours, configurable via `deduplication_window`. Fingerprint not seen for more than 24h is treated as new on next encounter.

FR-10: OccurrenceCount tracked on all paths — Increments on every encounter including WontFix records, to enable the 1,000× volume override (FR-12).

FR-11: Escalation threshold re-notification — When cooling error's OccurrenceCount crosses 10×, 100×, or 1,000× (configurable via `escalation_thresholds`), emit re-notification reusing stored LLMAnalysis — no fresh LLM call. Each threshold fires once per DeduplicationWindow (tracked via `last_notified_threshold`). Also fires during backlog catchup (FR-13).

FR-12: Won't-fix volume override — For WontFix records, fire escalation only at the 1,000× threshold. WontFix state is NOT cleared after override fires. Override is a read-only nudge.

FR-13: Escalation fires during backlog catchup — During backlog processing (stale checkpoint or degradation recovery), EscalationThresholds fire in sequence as OccurrenceCounts are reconstructed.

FR-14: SuppressionFile format and hot-reload — Format: one entry per line: `hash  # HumanLabel`. Hash is lookup key; everything after `#` is human comment. Blank lines and `#`-prefixed lines ignored. Duplicate hashes deduplicated (first wins). Read at start of every poll cycle BEFORE any ErrorDetected event is processed in that cycle — reload is sequential with poll, never concurrent.

FR-15: SuppressionFile absent or unreadable — Absent: treat as empty (not an error). Unreadable/parse error: keep previously loaded list in memory, skip reload, print: "⚠️ Suppression file unreadable — using last known state".

FR-16: LogGuard never writes to SuppressionFile — Read-only authority. Must never write, truncate, or modify this file in any code path including error-handling paths.

FR-17: WontFix re-encounter label — When WontFix fingerprint re-encountered after DeduplicationWindow expires, print one label per new cooling window: "⚑ Known / Won't Fix: [HumanLabel]  [hash]". No LLM analysis. Label is intentional — it distinguishes active suppression ("I see this; you told me not to care") from silence ("nothing happened"). Never reduce WontFix output to no output.

FR-18: Fingerprint identifier in output — Every new error output includes fingerprint for copy-paste into SuppressionFile. Output line: `Fingerprint: {HumanLabel}  [{hash}]`.

FR-19: Unsuppression — When fingerprint hash removed from SuppressionFile, clear `won't_fix` on existing DeduplicationRecord. If within window: treat as cooling. If window expired: treat as new.

FR-20: LLM payload construction — Construct from: `exception_type`, `error_message` (tenant-stripped), `stack_trace` (OwnCodeFrames only, truncated), `service_name`, `app_name`, `vdab_authorization`. Including `vdab_authorization` provides triggering LDAP identity context — narrows "unknown error" to specific user/system context; deliberate analytical value, not incidental data. [POST-MVP LANDMINE] Exclude `vdab_authorization` before any cloud LLM integration.

FR-21: TenantData stripping — Apply `strip_tenant_data` to `error_message` and `stack_trace` before LLM payload: Belgian KBO numbers → `[KBO]`; email addresses → `[EMAIL]`; LDAP DN fragments in free text → `[LDAP-DN]`. KEEP: UUIDs/GUIDs (correlation IDs), numeric IDs, port numbers.

FR-22: LLM instruction frame — System prompt: "You are a Java backend engineer. Given this Spring Boot error, identify the most likely root cause in the application code. Focus only on be.vdab.* frames. Be specific — name the class, method, and what likely went wrong there."

FR-23: Three-field output contract — LLM response must conform to three labelled fields on their own lines: `Root cause:`, `Likely location:`, `Suggested action:`. Malformed if any label absent or response empty → FR-24 fallback.

FR-24: LLM failure fallback — On failure (timeout, crash, malformed response): set `llm_available=false`, capture failure reason in `LLMAnalysis.unavailability_reason`, deliver raw error data to terminal with `"analysis unavailable ({unavailability_reason})"` marker. Never silently drop an error.

FR-25: LLM analysis caching — Cache LLMAnalysis in `DeduplicationRecord.stored_analysis` after initial call. Reuse at all EscalationThreshold re-notifications. No fresh LLM call at threshold.

FR-26: Zero-results — no output — When poll cycle returns zero ERROR logs, print nothing. Do not print "no new errors", a cycle counter, or any status line. Absence of output is not a health signal; DegradedState banner (FR-34) is the health signal.

FR-27: Batch progress signal — Print `"Analyzing N new errors in [service-name]..."` before starting LLM calls for each service group. Terminal latency reads as "working" not "frozen."

FR-28: Output grouped by service — Buffer all errors for a given `service_name` until analysis completes for that service, then print as a block.

FR-29: Service block ordering — Order service blocks by error count descending within each poll cycle. Most-affected service prints first.

FR-30: Per-error block format:
```
── {service-name} ({N} errors) ──────────────────
[{i}/{N}] {ExceptionType}@{ClassName}
  Root cause:       {root_cause | "analysis unavailable ({reason})"}
  Likely location:  {likely_location | "-"}
  Suggested action: {suggested_action | "-"}
  Fingerprint: {HumanLabel}  [{hash}]
```

FR-31: Escalation re-notification format:
```
⚠️ Known error {HumanLabel} now seen {threshold}× since {first_seen_at}
   Root cause: {stored_analysis.root_cause | "no analysis on file"}
```

FR-32: WontFix label format:
```
⚑ Known / Won't Fix: {HumanLabel}  {hash}
```
One label per re-encounter (after DeduplicationWindow expiry). No analysis.

FR-33: Consecutive-failure tracking — Track `consecutive_poll_failures`; reset to 0 on successful poll. Enter DegradedState after `max_consecutive_poll_failures` (default 3) consecutive failures.

FR-34: Sticky degradation banner — On entering DegradedState, record `degradation_started_at`. Print on every poll cycle while degraded:
```
🔴 LOGGUARD DEGRADED — OpenSearch unreachable since {degradation_started_at} ({N} failures)
```
Banner reprints each cycle — a stale banner scrolling off cannot create false calm.

FR-35: Checkpoint stasis during degradation + recovery — Do not advance PollCheckpoint while OpenSearch is unreachable. On first successful poll after DegradedState: clear `degradation_started_at` to null, reset `consecutive_poll_failures` to 0. Print:
```
🟢 LOGGUARD RECOVERED — polling resumed at {now}, catching up from checkpoint
```
Immediately process full missed backlog.

FR-36: Recovery backlog processing — On OpenSearch recovery, process full missed backlog (same behavior as FR-2 for stale checkpoints). EscalationThresholds fire in sequence during catchup (FR-13).

### NonFunctional Requirements

NFR-1: No silent drops — Every error LogGuard detects must either reach terminal output or be explicitly shown as `"analysis unavailable"`. No error may be silently discarded at any pipeline stage.

NFR-2: Self-observability — LogGuard must make its own health state visible in the terminal at all times. A silent terminal must never be ambiguous between "no errors today" and "LogGuard is blind." (Realized by FR-33 through FR-36.)

NFR-3: Configuration-first — All operational parameters (`poll_interval`, `deduplication_window`, `escalation_thresholds`, `own_code_package_prefixes`, `suppression_file_path`, `max_consecutive_poll_failures`, LLM endpoint) must be settable via config file or environment variable without code changes. No admin UI.

NFR-4: LLM throughput budget — Dedup-before-LLM bounds LLM calls to ~5–20 unique fingerprints per cycle. Any implementation change that breaks this (e.g. re-running LLM on every occurrence) must be explicitly reviewed and approved.

NFR-5: Checkpoint-advance invariant — PollCheckpoint must advance only after full batch delivery. This is a hard invariant — advancing cursor before batch completion silently introduces data-loss risk on crash.

NFR-6: Read-only suppression authority — LogGuard must never write to, truncate, or modify SuppressionFile in any code path, including error-handling paths.

### Additional Requirements

**Infrastructure Setup:**

AR-1: Both applications (logguard-ai and logguard-error-producer) scaffolded from Spring Initializr: Java 21, Spring Boot 4.1.0, Maven, group `be.vdab`.

AR-2: Spring milestones repository (`https://repo.spring.io/milestone`) added to pom.xml BEFORE any `mvn install` — day-one concern. Required for Spring AI 2.0.0-M4 resolution.

AR-3: Spring AI BOM (`spring-ai-bom:2.0.0-M4`) declared in pom.xml dependencyManagement. Starter: `spring-ai-starter-model-ollama:2.0.0-M4`.

AR-4: Docker Compose at repo root — `docker-compose.yml` with two services: `opensearch` (single-node, security disabled, healthcheck) and `logguard-error-producer` (depends_on opensearch healthy). LogGuard AI daemon runs on host.

AR-5: H2 file-based persistence at `jdbc:h2:file:./data/logguard`. The `./data/` directory is gitignored. PollCheckpoint must survive JVM restarts; in-memory H2 loses cursor on exit.

AR-6: Flyway schema management in `src/main/resources/db/migration/` — no auto-DDL. Two migration files: `V1__create_poll_checkpoint.sql`, `V2__create_deduplication_record.sql`.

AR-7: All JPA mappings use explicit `@Column(name="...")` and `@Table(name="...")`. Never rely on Hibernate auto-naming strategy. Column naming convention: `snake_case`. Table names: singular.

**Architectural Structure:**

AR-8: Hexagonal (ports and adapters) package structure: `domain/` (model/, port/in/, port/out/, service/) has zero Spring annotations. All Spring annotations live in `infrastructure/`. Domain compiles and unit-tests without a Spring context.

AR-9: `@Transactional` on `PollService.processBatch()` only — never on repository methods, adapters, or the scheduler. The transaction wraps: fetch from OpenSearch → process all errors → advance checkpoint.

AR-10: All user-facing terminal output routed through `TerminalOutputPort`. `System.out.println` and `System.err.println` prohibited in `domain/` and `infrastructure/` except inside `TerminalOutputAdapter`.

AR-11: All config bound via `LogguardProperties` (`@ConfigurationProperties(prefix = "logguard")`). `@Value("${...}")` is prohibited throughout the codebase.

AR-12: Poll scheduling via `@Scheduled(fixedDelayString = "${logguard.poll-interval:5m}")` with `initialDelay=0`. Virtual threads enabled: `spring.threads.virtual.enabled=true`. Blocking Ollama HTTP calls do not stall the scheduler.

AR-13: `LLMAnalysis` must be a Java record for `StructuredOutputConverter` binding. All domain value objects use Java 21 records. Domain entity classes with `@Entity` live in `infrastructure/persistence/` only.

AR-14: Prompt template at `src/main/resources/prompts/llm-analysis.st` — loaded via `ClassPathResource`. Never hardcoded in Java source.

AR-15: First-run lookback prompt implemented in `FirstRunInitializer.java` (ApplicationRunner) in `infrastructure/config/`, using Spring Shell's `Terminal`. Checks `PollCheckpointRepository.load()` on startup; if empty, prompts developer before scheduler fires.

**Companion Application:**

AR-16: Companion app (logguard-error-producer) uses `net.logstash.logback:logstash-logback-encoder:9.0` with `internetitem:logback-elasticsearch-appender` to produce documents matching `_source.structured.*` / `_source.kubernetes.*` field layout that LogGuard reads.

AR-17: Integration gate (first implementation story): companion app must produce documents that LogGuard can detect, fingerprint, and analyze without field-mapping changes. Verify by POSTing to `ErrorTriggerController` and confirming document appears in OpenSearch with correct fields.

**LLM Validation Gate:**

AR-18: BLOCKING PREREQUISITE before pipeline build — Prompt empirical validation must precede all pipeline implementation. Run instruction frame + output contract against ≥10 real be.vdab.* error payloads against the local Ollama instance manually. Success = ≥7/10 specific root cause hints naming a class and method. If gate fails, redesign prompt before proceeding. A running pipeline with hollow LLM output is a failure state, not a partial win.

### UX Design Requirements

N/A — LogGuard AI is a CLI daemon with no web UI. Terminal output format requirements are captured in FR-26 through FR-32 above.

### FR Coverage Map

| FR | Epic | What it delivers |
|---|---|---|
| FR-1 | Epic 2 | Configurable poll interval |
| FR-2 | Epic 2 | First-run lookback prompt |
| FR-3 | Epic 2 | Checkpoint-advance invariant |
| FR-4 | Epic 2 | Time-window OpenSearch query |
| FR-5 | Epic 2 | Own-code prefix config |
| FR-6 | Epic 4 | ErrorFingerprint computation with line-number asymmetry |
| FR-7 | Epic 4 | Framework-frame fallback |
| FR-8 | Epic 4 | Three-state dedup gate (incl. won't-fix-from-birth) |
| FR-9 | Epic 4 | Configurable deduplication window |
| FR-10 | Epic 4 | OccurrenceCount tracking on all paths |
| FR-11 | Epic 4 | Escalation threshold re-notifications |
| FR-12 | Epic 4 | Won't-fix volume override at 1,000× |
| FR-13 | Epic 4 | Escalation fires during backlog catchup |
| FR-14 | Epic 4 | SuppressionFile format + hot-reload + ordering guarantee |
| FR-15 | Epic 4 | SuppressionFile absent/unreadable handling |
| FR-16 | Epic 4 | Read-only suppression authority |
| FR-17 | Epic 4 | WontFix re-encounter label |
| FR-18 | Epic 4 | Fingerprint identifier in terminal output |
| FR-19 | Epic 4 | Unsuppression behavior |
| FR-20 | Epic 3 | LLM payload construction |
| FR-21 | Epic 3 | TenantData stripping |
| FR-22 | Epic 3 | LLM instruction frame |
| FR-23 | Epic 3 | Three-field output contract |
| FR-24 | Epic 3 | LLM failure fallback with `unavailability_reason` |
| FR-25 | Epic 4 | LLM analysis caching in DeduplicationRecord |
| FR-26 | Epic 2 | Zero-results — no output |
| FR-27 | Epic 2 | Batch progress signal |
| FR-28 | Epic 2 | Output grouped by service |
| FR-29 | Epic 2 | Service block ordering by error count |
| FR-30 | Epic 2 | Per-error block format (enhanced in E3 + E4) |
| FR-31 | Epic 4 | Escalation re-notification terminal format |
| FR-32 | Epic 4 | WontFix label terminal format |
| FR-33 | Epic 2 | Consecutive-failure tracking + DegradedState entry |
| FR-34 | Epic 2 | Sticky degradation banner |
| FR-35 | Epic 2 | Checkpoint stasis + recovery + `degradation_started_at` clearing |
| FR-36 | Epic 2 | Recovery backlog processing |

## Epic List

### Epic 1: Local Development Environment & Companion App

Developer can run `docker compose up` to start a local OpenSearch instance. The companion app (logguard-error-producer) is scaffolded, configured with Logback + `logstash-logback-encoder`, and automatically produces test errors written to OpenSearch with the correct `_source.structured.*` / `_source.kubernetes.*` field layout — verified by direct OpenSearch query. No LogGuard development in this epic.

**Architecture requirements covered:** Companion app scaffold (AR-1 partial), Docker Compose with OpenSearch + companion (AR-4), Logback appender field layout (AR-16), companion writes correct documents to OpenSearch (AR-17 partial — field verification)

---

### Epic 2: LogGuard App — First Working Version

The logguard-ai application is scaffolded and fully configured (Spring Initializr, Spring milestones repo, Spring AI BOM, H2 file-based persistence, Flyway migrations, hexagonal package structure, LogguardProperties, virtual threads, poll scheduler). LogGuard polls OpenSearch, reads test errors produced by the companion app — completing the integration gate — and displays them in the terminal grouped by service, ordered by error count. Handles first-run lookback prompt, checkpoint-advance invariant, backlog processing, zero-results silence, batch progress signals, and full degradation detection/recovery with sticky banner. All errors display `"analysis unavailable"` — LLM integration is Epic 3.

**Architecture requirements covered:** LogGuard scaffold (AR-1 partial), Spring milestones repo + Spring AI BOM (AR-2, AR-3), H2+Flyway (AR-5, AR-6, AR-7), hexagonal structure + conventions (AR-8–AR-12, AR-15), full integration gate completion (AR-17)
**FRs covered:** FR-1, FR-2, FR-3, FR-4, FR-5, FR-26, FR-27, FR-28, FR-29, FR-30, FR-33, FR-34, FR-35, FR-36

---

### Epic 3: LLM Setup & Integration

Developer sets up a local Ollama instance, validates the prompt against ≥10 real `be.vdab.*` error payloads (blocking gate — ≥7/10 must name a specific class and method before integration work begins), then wires the Ollama LLM adapter into LogGuard. New errors now display specific root cause, likely location, and suggested action instead of `"analysis unavailable"`. LLM failures never drop an error — raw data surfaces with the specific failure reason visible in the terminal.

**Architecture requirements covered:** LLMAnalysis Java record (AR-13), prompt template `llm-analysis.st` (AR-14), blocking validation gate (AR-18)
**FRs covered:** FR-20, FR-21, FR-22, FR-23, FR-24

---

### Epic 4: Fingerprint Deduplication & Won't-Fix Suppression

Terminal signal stays high-quality. Each unique error surfaces exactly once per 24-hour deduplication window. Volume escalation alerts flag recurring incidents at 10×/100×/1,000× thresholds, reusing stored LLM analysis — no extra LLM calls. Developers can permanently silence known-acceptable errors via the SuppressionFile; hot-reload takes effect within one poll cycle. The Fingerprint line appears in the per-error block for the first time here, enabling copy-paste suppression.

**FRs covered:** FR-6, FR-7, FR-8, FR-9, FR-10, FR-11, FR-12, FR-13, FR-14, FR-15, FR-16, FR-17, FR-18, FR-19, FR-25, FR-31, FR-32

---

## Epic 1: Local Development Environment & Companion App

Developer can run `docker compose up` to start a local OpenSearch instance. The companion app (logguard-error-producer) is scaffolded, configured with Logback + `logstash-logback-encoder`, and automatically produces test errors written to OpenSearch with the correct `_source.structured.*` / `_source.kubernetes.*` field layout — verified by direct OpenSearch query. No LogGuard development in this epic.

### Story 1.1: Run Local OpenSearch via Docker Compose

As a developer,
I want a local OpenSearch instance running via a single `docker compose up` command,
So that I have a target for the companion app to write test errors into and for LogGuard to poll.

**Acceptance Criteria:**

**Given** the repo root contains a `docker-compose.yml`
**When** the developer runs `docker compose up opensearch`
**Then** OpenSearch starts and is accessible at `http://localhost:9200`
**And** `curl http://localhost:9200` returns a JSON response confirming OpenSearch is running
**And** OpenSearch is configured as single-node with security disabled (no TLS or auth for local dev)
**And** the OpenSearch service has a healthcheck (`curl -s http://localhost:9200`) with a 10s interval

### Story 1.2: Companion App Produces Correctly-Structured Test Errors in OpenSearch

As a developer,
I want a companion Spring Boot app that writes ERROR-level log entries to the local OpenSearch instance with the exact field structure LogGuard expects,
So that I have a reliable, controlled test fixture for all LogGuard development without needing a production cluster.

**Acceptance Criteria:**

**Given** the companion app is added to `docker-compose.yml` with `depends_on: opensearch: condition: service_healthy`
**When** the developer runs `docker compose up` and sends a POST request to `ErrorTriggerController`
**Then** a document appears in the local OpenSearch index containing all required fields:
- `_source.structured.error.type` — exception class name
- `_source.structured.error.message` — error message
- `_source.structured.error.stack_trace` — full stack trace with ≥1 `be.vdab.*` frame
- `_source.structured.service.name` — service name
- `_source.kubernetes.labels.appName` — app name
- `_source.kubernetes.namespace_labels.vdab_be_team` — team label
- `_source.kubernetes.namespace_labels.vdab_be_environment` — environment label
- `_source.@timestamp` — ISO 8601 timestamp
- `_source.vdab.authorization` — LDAP DN string (e.g. `cn=TESTUSER,ou=users,ou=intern,O=VDAB`)

**And** the companion app is scaffolded from Spring Initializr: Java 21, Spring Boot 4.1.0, Web dependency, group `be.vdab`, artifact `logguard-error-producer`
**And** `net.logstash.logback:logstash-logback-encoder:9.0` and `internetitem:logback-elasticsearch-appender` are in pom.xml
**And** `logback-spring.xml` routes ERROR logs to OpenSearch via the `OPENSEARCH_URL` environment variable (defaulting to `http://localhost:9200`)
**And** `ErrorTriggerController` exposes at minimum one POST endpoint (e.g. `/trigger-error`) that throws a realistic `be.vdab.*` exception
**And** the companion app's `OPENSEARCH_URL` in docker-compose.yml is set to `http://opensearch:9200`

---

## Epic 2: LogGuard App — First Working Version

The logguard-ai application is scaffolded and fully configured. LogGuard polls OpenSearch, reads test errors produced by the companion app, and displays them in the terminal grouped by service, ordered by error count. Handles first-run lookback prompt, checkpoint-advance invariant, backlog processing, zero-results silence, batch progress signals, and full degradation detection/recovery with sticky banner. All errors display `"analysis unavailable"` — LLM integration is Epic 3.

### Story 2.1: Scaffold logguard-ai and configure project infrastructure

As a developer,
I want the logguard-ai application scaffolded with all infrastructure configured and the application starting cleanly,
So that the project is ready for feature development with a consistent hexagonal structure, working persistence layer, and all required dependencies in place.

**Acceptance Criteria:**

**Given** the developer has cloned the repo
**When** they run `mvn spring-boot:run` in the logguard-ai root
**Then** the application starts without errors
**And** H2 file-based persistence initializes at `./data/logguard`
**And** Flyway reports "Successfully applied 0 migrations" (migrations added in Story 2.2 and Story 4.x)
**And** `LogguardProperties` binds correctly from `application.yml` with all config fields present and defaulted
**And** the full hexagonal package skeleton exists: `domain/model/`, `domain/port/in/`, `domain/port/out/`, `domain/service/`, `infrastructure/opensearch/`, `infrastructure/llm/`, `infrastructure/persistence/`, `infrastructure/filesystem/`, `infrastructure/terminal/`, `infrastructure/config/`
**And** virtual threads are enabled (`spring.threads.virtual.enabled=true` in application.yml)
**And** the Spring milestones repository (`https://repo.spring.io/milestone`) is declared in pom.xml
**And** Spring AI BOM (`spring-ai-bom:2.0.0-M4`) is declared in `<dependencyManagement>`
**And** `opensearch-java` client dependency is present in pom.xml

### Story 2.2: PollCheckpoint persistence and first-run lookback prompt

As a developer,
I want LogGuard to remember where it left off between runs and ask me how far back to look on first startup,
So that I never lose errors on restart and I can control how much backlog to process when starting fresh.

**Acceptance Criteria:**

**Given** the database migration `V1__create_poll_checkpoint.sql` exists in `src/main/resources/db/migration/`
**When** LogGuard starts and `poll_checkpoint` table is empty (first run)
**Then** the terminal displays: `"First run detected. Process last [N] hours of history? (default: 24h)"`
**And** LogGuard waits for developer input before proceeding
**And** when the developer enters a number (e.g. `6`), `PollCheckpoint` is created with `last_successful_poll_at = now − 6 hours`
**And** when the developer presses Enter (no input), `PollCheckpoint` is created with `last_successful_poll_at = now − 24 hours`
**And** when the developer enters `0`, `PollCheckpoint` is created with `last_successful_poll_at = now`

**Given** a `PollCheckpoint` already exists
**When** LogGuard starts
**Then** no prompt is shown and the existing checkpoint is used as-is

**And** `poll_checkpoint` table uses explicit `@Table(name = "poll_checkpoint")` and `@Column(name = "...")` on all fields — no implicit Hibernate naming
**And** the domain `PollCheckpoint` record in `domain/model/` has zero JPA annotations
**And** `PollCheckpointRepositoryAdapter` in `infrastructure/persistence/` implements the `PollCheckpointRepository` port
**And** `FirstRunInitializer` in `infrastructure/config/` implements `ApplicationRunner` and uses Spring Shell's `Terminal` to prompt the developer

### Story 2.3: OpenSearch error polling adapter

As a developer,
I want LogGuard to retrieve ERROR-level log entries from OpenSearch for all documents newer than the last checkpoint,
So that every error that occurred since the last successful poll is available for processing.

**Acceptance Criteria:**

**Given** the companion app has written test errors to local OpenSearch (Epic 1 complete)
**And** `PollCheckpoint.last_successful_poll_at` is set to 1 hour ago
**When** `OpenSearchAdapter.findErrorsSince(Instant from)` is called
**Then** all test errors written after that timestamp are returned as `ErrorLog` records
**And** each `ErrorLog` record maps fields correctly: `exception_type`, `error_message`, `stack_trace`, `service_name`, `app_name`, `team`, `environment`, `occurred_at`, `vdab_authorization`
**And** if OpenSearch returns zero documents, an empty list is returned — no exception thrown

**And** `OpenSearchPort` interface in `domain/port/out/` declares `List<ErrorLog> findErrorsSince(Instant from)`
**And** the `ErrorLog` domain record in `domain/model/` has no Spring or JPA annotations
**And** the OpenSearch base URL and index pattern are read from `LogguardProperties.opensearch` — not hardcoded
**And** the `opensearch-java` client is used (not Spring Data OpenSearch)
**And** a unit test `OpenSearchAdapterTest` in `infrastructure/opensearch/` tests field mapping using a stubbed OpenSearch response

### Story 2.4: Terminal output — service-grouped, count-ordered, per-error block

As a developer,
I want errors displayed in the terminal grouped by service and ordered by error count descending,
So that I can immediately see which service is most affected before reading any error detail, and the terminal never feels frozen during analysis.

**Acceptance Criteria:**

**Given** LogGuard has retrieved a batch of `ErrorLog` records from OpenSearch
**When** the batch is delivered to the terminal
**Then** before analysis begins for a service, the terminal prints: `"Analyzing N new errors in [service-name]..."`
**And** all errors for a service are buffered and printed together as a block after analysis completes
**And** service blocks are ordered by error count descending within each poll cycle
**And** each error follows this exact format:
```
── {service-name} ({N} errors) ──────────────────
[{i}/{N}] {ExceptionType}@{ClassName}
  Root cause:       analysis unavailable
  Likely location:  -
  Suggested action: -
```
**And** when a poll cycle returns zero `ErrorLog` records, nothing is printed — no "no new errors" message, no cycle counter

**And** all terminal output routes through `TerminalOutputPort` — `System.out.println` is prohibited outside `TerminalOutputAdapter`
**And** `TerminalOutputPort` interface is in `domain/port/out/`
**And** `TerminalOutputAdapter` in `infrastructure/terminal/` defines status indicator constants (`DEGRADED`, `RECOVERED`, `WONT_FIX`, `ESCALATION`) — never inline string literals

### Story 2.5: Poll loop with checkpoint-advance invariant

As a developer,
I want LogGuard to automatically poll OpenSearch on a configurable interval and advance its checkpoint only after all errors in a batch have been displayed,
So that no errors are ever silently lost on a crash or restart, and the poll interval is adjustable without code changes.

**Acceptance Criteria:**

**Given** LogGuard is running with a valid `PollCheckpoint`
**When** a poll cycle fires
**Then** `SuppressionFilePort.loadHashes()` is called first (returns empty set — suppression not yet implemented, stub is acceptable)
**And** `OpenSearchPort.findErrorsSince(checkpoint.last_successful_poll_at)` is called next
**And** all retrieved errors are delivered to `TerminalOutputPort`
**And** `PollCheckpointRepository.save(advancedCheckpoint)` is called only after all errors have been delivered — never before, never mid-batch
**And** the poll interval defaults to 5 minutes and is configurable via `logguard.poll-interval` in `application.yml` or environment variable

**Given** LogGuard crashes mid-batch (before checkpoint is saved)
**When** it restarts
**Then** it re-fetches the same batch from the unchanged checkpoint (no errors silently lost)

**And** scheduling uses `@Scheduled(fixedDelayString = "${logguard.poll-interval:5m}")` with `initialDelay = 0` — no overlap between poll cycles regardless of batch size
**And** `@Transactional` is on `PollService.processBatch()` only — not on any repository method, adapter, or the scheduler

### Story 2.6: Degradation detection, sticky banner, and recovery with auto-backlog

As a developer,
I want LogGuard to clearly signal when OpenSearch is unreachable and automatically catch up on all missed errors when connectivity is restored,
So that a silent terminal never creates a false sense that production is healthy, and no errors are lost during an outage.

**Acceptance Criteria:**

**Given** consecutive OpenSearch poll failures reach `max_consecutive_poll_failures` (default 3)
**When** the threshold is crossed
**Then** `PollCheckpoint.degradation_started_at` is set to the current timestamp
**And** on every poll cycle while degraded, the banner reprints:
```
🔴 LOGGUARD DEGRADED — OpenSearch unreachable since {degradation_started_at} ({N} failures)
```
**And** `PollCheckpoint.last_successful_poll_at` does NOT advance while degraded
**And** `consecutive_poll_failures` increments on each failed cycle

**Given** LogGuard is degraded and OpenSearch becomes reachable
**When** the next poll cycle succeeds
**Then** the recovery line prints: `"🟢 LOGGUARD RECOVERED — polling resumed at {now}, catching up from checkpoint"`
**And** `PollCheckpoint.degradation_started_at` is explicitly cleared to null
**And** `consecutive_poll_failures` resets to 0
**And** LogGuard immediately processes the full missed backlog from the stored checkpoint

**Given** OpenSearch has 1 or 2 failures but fewer than `max_consecutive_poll_failures`
**When** a successful poll follows
**Then** `consecutive_poll_failures` resets to 0 and no degradation banner appears

**And** `max_consecutive_poll_failures` is configurable via `logguard.max-consecutive-poll-failures` in `application.yml`

---

## Epic 3: LLM Setup & Integration

Developer sets up a local Ollama instance, validates the prompt against ≥10 real `be.vdab.*` error payloads (blocking gate), then wires the Ollama LLM adapter into LogGuard. New errors now display specific root cause, likely location, and suggested action. LLM failures never drop an error — raw data surfaces with the specific failure reason.

### Story 3.1: LLM prompt validation gate — TenantDataSanitizer, payload builder, and manual prompt test

As a developer,
I want to validate that the local LLM produces specific root cause hints for `be.vdab.*` errors before writing any adapter integration code,
So that the pipeline is built on a proven prompt rather than discovered to be hollow after the full adapter is implemented.

**Acceptance Criteria:**

**Given** Ollama is installed locally and running a suitable model (e.g. `llama3`, `codellama`)
**When** the developer runs the manual validation procedure
**Then** `TenantDataSanitizer` exists in `domain/service/` as pure Java (no Spring annotations) and correctly:
- Strips Belgian KBO numbers (format `0XXX.XXX.XXX`) → `[KBO]`
- Strips email addresses → `[EMAIL]`
- Strips LDAP DN fragments in free text (`cn=..., ou=...`) → `[LDAP-DN]`
- Keeps UUIDs/GUIDs unchanged
- Keeps numeric IDs and port numbers unchanged

**And** a unit test `TenantDataSanitizerTest` covers each strip/keep rule with a concrete example
**And** the LLM payload builder assembles the 6-field payload (FR-20): `exception_type`, `strip_tenant_data(error_message)`, `stack_trace` (own-code frames only, tenant-stripped), `service_name`, `app_name`, `vdab_authorization`
**And** the prompt template is written and stored at `src/main/resources/prompts/llm-analysis.st` containing the instruction frame (FR-22) and output contract (FR-23)
**And** the `LLMAnalysis` Java record exists in `domain/model/` with fields: `llmAvailable`, `rootCause`, `likelyLocation`, `suggestedAction`, `unavailabilityReason` — matching `StructuredOutputConverter` binding conventions

**And** the developer manually submits ≥10 real `be.vdab.*` error payloads to Ollama using the instruction frame from `llm-analysis.st`
**And** ≥7 of 10 responses name a specific `be.vdab.*` class and method (not a generic observation such as "may be a configuration issue")
**And** if fewer than 7 pass: the prompt is redesigned and re-validated before Story 3.2 begins — this gate must not be bypassed

### Story 3.2: Ollama LLM adapter with structured output and failure fallback

As a developer,
I want LogGuard to call the local Ollama LLM for each new error and display specific root cause, likely location, and suggested action in the terminal — and when the LLM is unavailable, display the raw error with the specific failure reason instead of silently dropping it,
So that every error either surfaces with an analysis hint or explicitly tells me why analysis wasn't possible.

**Acceptance Criteria:**

**Given** Ollama is running with the validated model
**When** LogGuard processes a new error
**Then** `LlmAdapter.analyse(ErrorLog)` calls Ollama via `OllamaChatModel` + `StructuredOutputConverter` → `LLMAnalysis` record
**And** the per-error block in the terminal now shows actual LLM output:
```
── {service-name} ({N} errors) ──────────────────
[{i}/{N}] {ExceptionType}@{ClassName}
  Root cause:       {root_cause}
  Likely location:  {likely_location}
  Suggested action: {suggested_action}
```

**Given** the LLM is unavailable (Ollama not running, timeout, or malformed response)
**When** LogGuard processes an error
**Then** the error still appears in the terminal with the specific failure reason:
```
  Root cause:       analysis unavailable (connection refused)
  Likely location:  -
  Suggested action: -
```
**And** `LlmAdapter` catches all exceptions at its own boundary and returns `LLMAnalysis(llmAvailable=false, unavailabilityReason=e.getMessage())` — infrastructure exceptions never propagate into `domain/`

**Given** the LLM returns a response missing any of the three required labels (`Root cause:`, `Likely location:`, `Suggested action:`)
**When** `StructuredOutputConverter` binding produces a null field
**Then** the response is treated as malformed and the fallback triggers with `unavailabilityReason = "malformed response"`

**And** `spring-ai-starter-model-ollama:2.0.0-M4` is added to pom.xml
**And** `LlmPort` interface in `domain/port/out/` declares `LLMAnalysis analyse(ErrorLog error)` — this method never throws, always returns
**And** Ollama base URL, model name, and timeout are read from `LogguardProperties.ollama` — not hardcoded
**And** a unit test `LlmAdapterTest` in `infrastructure/llm/` verifies the failure fallback using a mocked `OllamaChatModel`

---

## Epic 4: Fingerprint Deduplication & Won't-Fix Suppression

Terminal signal stays high-quality. Each unique error surfaces exactly once per 24-hour deduplication window. Volume escalation alerts flag recurring incidents at 10×/100×/1,000× thresholds, reusing stored LLM analysis — no extra LLM calls. Developers can permanently silence known-acceptable errors via the SuppressionFile; hot-reload takes effect within one poll cycle. The Fingerprint line appears in the per-error block for the first time here, enabling copy-paste suppression.

### Story 4.1: ErrorFingerprint computation with OwnCodeFrame detection and line-number asymmetry

As a developer,
I want each error uniquely identified by its structural fingerprint rather than its raw content,
So that two occurrences of the same bug produce the same fingerprint regardless of deploy-shifted line numbers in the call path.

**Acceptance Criteria:**

**Given** an `ErrorLog` with a stack trace containing `be.vdab.*` frames
**When** `FingerprintService.compute(ErrorLog)` is called
**Then** `ErrorFingerprint.throwing_method` = class + method + LINE NUMBER of the first `be.vdab.*` frame (line number kept)
**And** `ErrorFingerprint.stack_trace_sequence` = structural sequence of all `be.vdab.*` frames with ALL line numbers stripped
**And** two `ErrorLog` records with the same exception type and call path but different line numbers in non-throwing frames produce the same `stack_trace_sequence` but may differ on `throwing_method`

**Given** an `ErrorLog` whose stack trace contains no `be.vdab.*` frames (Spring startup, HikariCP, static 404s)
**When** `FingerprintService.compute(ErrorLog)` is called
**Then** `throwing_method` = the topmost frame of the stack trace (framework-frame fallback)
**And** `stack_trace_sequence` = the topmost frame with line number stripped
**And** two distinct framework-only errors with different topmost frames produce different fingerprints

**And** `FingerprintService` lives in `domain/service/` with zero Spring annotations
**And** own-code prefix list is read from `LogguardProperties.ownCodePackagePrefixes` (e.g. `["be.vdab"]`)
**And** unit tests in `domain/service/FingerprintServiceTest` cover: normal own-code path, framework fallback, line-number asymmetry (same call path, shifted lines → same sequence but different throwing_method), and multi-prefix matching

### Story 4.2: DeduplicationRecord persistence, three-state gate, and LLM analysis caching

As a developer,
I want each unique error fingerprint to trigger LLM analysis only once per deduplication window with the result cached for reuse,
So that the local LLM is called at most once per unique bug per day regardless of how many times that bug occurs.

**Acceptance Criteria:**

**Given** an `ErrorLog` whose fingerprint has no active `DeduplicationRecord` (new error)
**When** `PollService` processes it
**Then** a new `DeduplicationRecord` is created with `occurrence_count = 1`, `won't_fix = false`, `expires_at = now + deduplication_window`
**And** the error proceeds to `LlmPort.analyse()` and the `LLMAnalysis` result is stored in `DeduplicationRecord.stored_analysis`
**And** the error is delivered to terminal output

**Given** an `ErrorLog` whose fingerprint has an active `DeduplicationRecord` with `won't_fix = false` (cooling)
**When** `PollService` processes it
**Then** `occurrence_count` is incremented
**And** no LLM call is made
**And** no terminal output is produced (duplicate suppressed)

**Given** an `ErrorLog` whose fingerprint's `DeduplicationRecord` has expired (older than `deduplication_window`)
**When** `PollService` processes it
**Then** the expired record is treated as absent and a new `DeduplicationRecord` is created (error treated as new)

**And** `V2__create_deduplication_record.sql` Flyway migration creates the `deduplication_record` table with explicit column names: `id`, `fingerprint_hash`, `exception_type`, `throwing_method`, `stack_trace_sequence`, `first_seen_at`, `expires_at`, `occurrence_count`, `last_notified_threshold`, `wont_fix`, `stored_analysis`
**And** `deduplication_window` is configurable via `logguard.deduplication-window` in `application.yml` (default 24h)
**And** the domain `DeduplicationRecord` record in `domain/model/` has zero JPA annotations
**And** `DeduplicationRecordRepositoryAdapter` in `infrastructure/persistence/` implements the `DeduplicationRecordRepository` port

### Story 4.3: SuppressionFile adapter with hot-reload, ordering guarantee, and read-only authority

As a developer,
I want to add fingerprint hashes to a plain text file to permanently silence known-acceptable errors, with changes taking effect within the next poll cycle without restarting LogGuard,
So that suppression is zero-infrastructure (text editor + copy-paste) and entirely developer-controlled.

**Acceptance Criteria:**

**Given** `suppression.txt` contains entries in format `hash  # HumanLabel`
**When** a new poll cycle begins
**Then** `SuppressionFilePort.loadHashes()` is called before `OpenSearchPort.findErrorsSince()` — reload completes before any error in the batch is processed
**And** `loadHashes()` returns the set of hash strings (everything before ` #` on each line)
**And** blank lines and lines starting with `#` are ignored
**And** if the same hash appears more than once, the first occurrence wins

**Given** `suppression.txt` does not exist
**When** `loadHashes()` is called
**Then** an empty set is returned — absent file is not an error condition

**Given** `suppression.txt` exists but is unreadable or contains a parse error
**When** `loadHashes()` is called
**Then** the previously loaded set is kept in memory unchanged
**And** the terminal prints: `"⚠️ Suppression file unreadable — using last known state"`
**And** LogGuard continues polling normally

**And** `SuppressionFilePort` interface in `domain/port/out/` declares only `Set<String> loadHashes()` — no write method exists on this interface under any circumstance
**And** `SuppressionFileAdapter` in `infrastructure/filesystem/` has no write, truncate, or modify path in any code branch including exception handlers
**And** the suppression file path is configurable via `logguard.suppression-file-path` in `application.yml`
**And** a unit test `SuppressionFileAdapterTest` covers: normal parse, blank lines ignored, absent file → empty set, unreadable → last known state + warning

### Story 4.4: Won't-Fix suppression integration and fingerprint identifier in terminal output

As a developer,
I want suppressed fingerprints acknowledged with a visible label and every new error output to include the fingerprint hash for direct copy-paste into the suppression file,
So that the won't-fix workflow requires no tooling beyond a text editor and a terminal.

**Acceptance Criteria:**

**Given** an `ErrorLog` whose fingerprint has no active `DeduplicationRecord` AND whose hash is already in the loaded suppression set (won't-fix-from-birth)
**When** `PollService` processes it
**Then** a `DeduplicationRecord` is created with `won't_fix = true` immediately — no LLM call, no cooling period
**And** the terminal prints the WontFix label once: `"⚑ Known / Won't Fix: [HumanLabel]  [hash]"`

**Given** an `ErrorLog` whose fingerprint has an active `DeduplicationRecord` with `won't_fix = true` and `expires_at` has passed
**When** `PollService` processes it
**Then** the terminal prints the WontFix label once per new cooling window
**And** no LLM analysis is performed

**Given** a fingerprint hash is removed from `suppression.txt`
**When** the next poll cycle begins
**Then** if the `DeduplicationRecord` is within its window: `won't_fix` is cleared and the record transitions to cooling
**And** if the `DeduplicationRecord` has expired: it is treated as new on next encounter

**Given** LogGuard processes a brand-new error (not suppressed)
**When** the per-error block is printed to the terminal
**Then** the block includes the Fingerprint line as the final line:
```
── {service-name} ({N} errors) ──────────────────
[{i}/{N}] {ExceptionType}@{ClassName}
  Root cause:       {root_cause}
  Likely location:  {likely_location}
  Suggested action: {suggested_action}
  Fingerprint: {HumanLabel}  [{hash}]
```
**And** `{HumanLabel}` is derived from exception type and throwing class/method (e.g. `NPE@LabelV2Config:21`)
**And** `{hash}` is the fingerprint hash suitable for direct copy-paste into `suppression.txt`

### Story 4.5: Escalation threshold re-notifications with won't-fix volume override

As a developer,
I want to be re-notified when a known error recurs at significant volume (10×, 100×, 1,000×) using the stored analysis — no fresh LLM call,
So that a bug that has become an operational incident surfaces again with the context I already have.

**Acceptance Criteria:**

**Given** a cooling `DeduplicationRecord` (`won't_fix = false`) whose `occurrence_count` crosses 10, 100, or 1,000
**When** `PollService` increments the count
**Then** the escalation re-notification prints to the terminal:
```
⚠️ Known error {HumanLabel} now seen {threshold}× since {first_seen_at}
   Root cause: {stored_analysis.root_cause | "no analysis on file"}
```
**And** `last_notified_threshold` is updated to the fired threshold
**And** no LLM call is made — `stored_analysis` is reused directly
**And** each threshold fires exactly once per `DeduplicationWindow` (10× fires once, then 100×, then 1,000×)

**Given** a won't-fix `DeduplicationRecord` (`won't_fix = true`) whose `occurrence_count` reaches 1,000
**When** `PollService` increments the count
**Then** the won't-fix volume override prints:
```
⚠️ Won't-fix error {HumanLabel} now seen 1,000× since {first_seen_at} — volume is unusually high
```
**And** `won't_fix` state is NOT cleared — the override is a read-only nudge
**And** the 10× and 100× thresholds do NOT fire for won't-fix records — only 1,000×

**Given** LogGuard is processing a backlog (stale checkpoint or degradation recovery)
**When** `occurrence_count` crosses a threshold during backlog replay
**Then** the escalation notification fires in sequence as counts are reconstructed — not suppressed during catchup

**And** escalation thresholds are configurable via `logguard.escalation-thresholds` in `application.yml` (default `[10, 100, 1000]`)
**And** escalation output routes through `TerminalOutputPort` — not directly to `System.out`
