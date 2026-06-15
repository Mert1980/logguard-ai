---
title: LogGuard AI
status: final
created: 2026-06-15
updated: 2026-06-15
---

# PRD: LogGuard AI

## 0. Document Purpose

This PRD is for VDAB backend developers and technical leads building and operating LogGuard AI. It defines what the system must do — behavioral contracts, user journeys, and testable acceptance criteria — not how to build it. Vocabulary is anchored in §3 Glossary; all FRs, UJs, and success metrics use those terms exactly. Introducing a synonym is a discipline violation.

Primary inputs: `specs/logguard-ai.allium` (behavioral spec) and `_bmad-output/brainstorming/brainstorming-session-2026-06-08-1400.md` (44 documented decisions). This PRD extracts and scopes their product-relevant content to the MVP. Implementation details, options-considered rationale, and the prompt template text live in `addendum.md`.

---

## 1. Vision

LogGuard AI monitors VDAB's 28 Spring Boot applications in OpenSearch, detects ERROR-level log entries, deduplicates them by behavioral fingerprint, analyzes unique errors with a local LLM for root cause hints, and surfaces the results in a developer's terminal — in time to prevent user-facing incidents rather than respond to them.

Today, VDAB backend developers discover production errors through user complaints or manual log inspection. By the time an error is visible, it often has a user story attached to it. LogGuard inverts this dynamic: errors surface to the developer first, with a specific root cause hint, before any user notices. The primary value proposition is not faster debugging — it is making many incidents invisible to end users.

Over time, LogGuard compounds in value. As recurring root causes are fixed and known-acceptable errors suppressed, the error signal gets quieter and higher quality. A quieter terminal means each remaining alert carries more weight. The system becomes more useful the longer it runs — a quality culture shift engine that operates as a side effect of developers doing their normal work.

---

## 2. Target User

### 2.1 Jobs To Be Done

- Know when a production application is throwing errors without polling logs manually.
- Understand the likely root cause of an error without reading 120 lines of stack trace.
- Suppress known-acceptable errors so they stop generating noise.
- Recover from downtime (own or OpenSearch's) without losing error history.
- Distinguish clearly between "no errors today" and "LogGuard cannot reach OpenSearch."

### 2.2 Non-Users (MVP)

- **End users / business stakeholders** — invisible beneficiaries, not operators.
- **Team leads and product owners** — benefit indirectly from fewer incidents; no direct interaction.
- **Operations / infrastructure teams** — LogGuard is a developer-run process, not a managed service.

### 2.3 Key User Journeys

Since this is a CLI tool with a single operator role, UJs use the lighter narrative format.

**UJ-1. Developer starts LogGuard for the first time.**
Developer runs LogGuard on a machine with no prior checkpoint; is prompted for a lookback window (default 24 hours); LogGuard processes the full backlog, printing analysis grouped by service before the first live poll cycle begins.

**UJ-2. Developer sees a new production error surface in the terminal.**
Within one poll cycle of an error occurring in OpenSearch, the developer sees the error grouped under its service with a root cause hint, likely location, and suggested action — without having checked logs manually.

**UJ-3. Developer suppresses a known won't-fix error.**
Developer copies the fingerprint hash from the terminal output, adds it (with a human label) to the SuppressionFile; within the next poll cycle, LogGuard silently absorbs that fingerprint — no analysis, no output.

**UJ-4. Developer returns to terminal after OpenSearch downtime.**
Developer sees the degradation banner with the exact start time of the outage; on recovery, LogGuard automatically processes the full missed backlog and prints the recovery line — no manual intervention required.

---

## 3. Glossary

- **ErrorLog** — A single ERROR-level log entry retrieved from OpenSearch. Fields map to `_source.structured` and `_source.kubernetes`. The canonical input record for all LogGuard processing.
- **ErrorFingerprint** — A three-field deduplication key derived from an ErrorLog: `exception_type`, `throwing_method` (with line number), and `stack_trace_sequence` (normalized, line numbers stripped). Two ErrorLogs with the same ErrorFingerprint are treated as the same error.
- **DeduplicationRecord** — A persistent record tracking how many times a given ErrorFingerprint has been seen within the current DeduplicationWindow, and its current state (cooling or won't-fix).
- **DeduplicationWindow** — The 24-hour (configurable) period during which a fingerprint is suppressed as a duplicate. A fingerprint re-encountered after the window expires is treated as new.
- **PollCheckpoint** — A persistent cursor tracking the timestamp of the last successful poll. Advances only after a full batch is delivered. Enables catch-up after downtime without losing errors.
- **OccurrenceCount** — The total number of times a fingerprint has been seen, including suppressed duplicates. Used to trigger EscalationThreshold re-notifications.
- **EscalationThreshold** — An OccurrenceCount value (10, 100, 1000) that triggers a re-notification for a cooling or won't-fix error.
- **WontFix** — A DeduplicationRecord state indicating the fingerprint has been deliberately marked as acceptable. Set by the developer via SuppressionFile. LogGuard reads but never writes this state.
- **SuppressionFile** — A developer-owned text file mapping fingerprint hashes to human labels. Format per entry: `hash  # HumanLabel`. LogGuard reads it on every poll cycle but never writes to it.
- **LLMAnalysis** — The three-field output produced by the LLM for a new error: `root_cause` (one paragraph), `likely_location` (ClassName.method:line), `suggested_action` (one paragraph). May be absent if the LLM was unavailable at analysis time.
- **OwnCodeFrame** — A stack trace frame whose class name starts with a configured package prefix (e.g. `be.vdab`). The primary signal for fingerprinting and LLM payload truncation.
- **TenantData** — PII and business identifiers embedded in log content: Belgian KBO numbers, email addresses, and LDAP DN fragments in free text. Stripped from the LLM payload by `strip_tenant_data` before analysis.
- **DegradedState** — The operational state LogGuard enters after `max_consecutive_poll_failures` consecutive OpenSearch failures. Characterized by a sticky visual banner and no PollCheckpoint advancement.

---

## 4. Features

### 4.1 Periodic Error Polling

LogGuard queries OpenSearch on a configurable interval for ERROR-level logs since the last PollCheckpoint. The query is a time-window scan against an error-specific index — no log-level filter required. On first run, the developer is prompted for a lookback window before polling begins. `[ASSUMPTION: For MVP, OpenSearch runs locally in a Docker container accessible at a configured local endpoint. A companion Spring Boot error-producer application generates test errors that LogGuard reads from this container.]` `[ASSUMPTION: The documents written by the companion Spring Boot app match the expected _source.structured.* and _source.kubernetes.* field structure LogGuard reads — see ASSUMPTION-2.]`

**ErrorLog field contract:** LogGuard reads the following fields from each OpenSearch document. The canonical field-to-source mapping is defined in `specs/logguard-ai.allium` (entity ErrorLog). Summary: exception_type → `_source.structured.error.type`, error_message → `_source.structured.error.message`, stack_trace → `_source.structured.error.stack_trace`, service_name → `_source.structured.service.name`, app_name → `_source.kubernetes.labels.appName`, team → `_source.kubernetes.namespace_labels.vdab_be_team`, environment → `_source.kubernetes.namespace_labels.vdab_be_environment`, occurred_at → `_source.@timestamp`, vdab_authorization → `_source.vdab.authorization`.

#### FR-1: Configurable poll interval
The system polls OpenSearch every `poll_interval` (default 5 minutes, configurable). Realizes UJ-2.
**Consequences:**
- New errors are visible in the terminal within one poll cycle of occurring in OpenSearch.
- `poll_interval` is settable via config file or environment variable without code changes.

#### FR-2: First-run lookback prompt
On first run (no PollCheckpoint), print: `"First run detected. Process last [N] hours of history? (default: 24h)"` and wait for developer input. Accept a number of hours; 0 = start from now. Realizes UJ-1.
**Consequences:**
- PollCheckpoint.last_successful_poll_at is set to `now − confirmed_lookback_hours`.
- Developer controls how much backlog to process on startup.

#### FR-3: Checkpoint-advance timing invariant
PollCheckpoint.last_successful_poll_at advances ONLY after ALL errors in the batch have been emitted — never before, never mid-batch.
**Consequences:**
- A crash after cursor advance but before batch emission cannot silently lose that batch.
- On restart, the same batch is re-fetched; fingerprint deduplication absorbs duplicates.

#### FR-4: Time-window query
Query ErrorLogs where occurred_at > PollCheckpoint.last_successful_poll_at using the pre-filtered error index. No additional log-level filter in the query.
**Consequences:**
- Query complexity is minimal: a single time-window scan.

#### FR-5: Configurable own-code prefixes
Accept `own_code_package_prefixes` as a configurable list (e.g. `["be.vdab"]`), used for OwnCodeFrame detection, stack trace truncation, and (post-MVP) GitLab link generation.

**Out of Scope:**
- Querying WARN or other non-ERROR log levels (post-MVP).

---

### 4.2 Fingerprint Deduplication

Every incoming ErrorLog is fingerprinted before LLM analysis. The three-state gate (new / cooling / won't-fix) ensures only unique errors generate LLM calls and output — protecting both LLM throughput and terminal signal quality. `[ASSUMPTION: Fingerprint deduplication reduces ~1,400 raw errors/day to ~5–20 unique LLM calls per 5-minute cycle under normal operation.]`

#### FR-6: ErrorFingerprint computation
Compute ErrorFingerprint from each ErrorLog:
- **exception_type** — from ErrorLog.exception_type.
- **throwing_method** — class + method + LINE NUMBER of the first OwnCodeFrame. If no OwnCodeFrame exists, use the topmost stack trace frame (framework-frame fallback).
- **stack_trace_sequence** — structural sequence of OwnCodeFrames (or topmost frame if none), line numbers STRIPPED from ALL frames.

**Consequences:**
- Two ErrorLogs with the same fingerprint are suppressed within the DeduplicationWindow.
- `throwing_method` and `stack_trace_sequence` have OPPOSITE line-number policies by design: `throwing_method` keeps the line number (distinguishes two bugs in the same method); `stack_trace_sequence` strips it (survives deploys that shift lines elsewhere in the call path). Normalizing both the same way collapses distinct bugs into one fingerprint.

**Out of Scope:**
- GitLab URL construction from stack trace coordinates (post-MVP; requires service registry).

#### FR-7: Framework-frame fallback
When no OwnCodeFrame exists in the stack trace (Spring startup, HikariCP, static-resource 404s, etc.), use the topmost frame as the `throwing_method` fallback.
**Consequences:**
- Distinct framework-only errors produce distinct fingerprints and do not collapse into one.

#### FR-8: Three-state deduplication gate
Each incoming ErrorLog routes to one of three paths based on the active DeduplicationRecord state:
- **New** (no active record): create DeduplicationRecord, proceed to LLM analysis and delivery. Exception: if the fingerprint hash is already in the SuppressionFile at creation time, create the record with `won't_fix=true` immediately, fire the WontFix label (FR-17), and skip LLM analysis — no cooling period first.
- **Cooling** (active record, `won't_fix=false`): increment OccurrenceCount, check EscalationThresholds.
- **Won't-fix** (active record, `won't_fix=true`): increment OccurrenceCount silently; on re-encounter after window expires, display a single WontFix label.
**Consequences:**
- At most one LLM call per unique fingerprint per DeduplicationWindow.
- A fingerprint suppressed before its first analysis never triggers an LLM call.

#### FR-9: Deduplication window
Default DeduplicationWindow is 24 hours, configurable via `deduplication_window`.
**Consequences:**
- A fingerprint not seen for more than 24 hours is treated as new on next encounter.

#### FR-10: OccurrenceCount tracked on all paths
OccurrenceCount increments on every encounter including WontFix records, to enable the 1,000× volume override (FR-12).

---

### 4.3 Escalation Notifications

High-volume recurrence of a known error is a distinct signal — an error that was a minor bug may have become an operational incident. Escalation re-notifications surface this signal without generating fresh LLM calls, protecting local LLM throughput. Realizes UJ-2 (recurrence awareness).

#### FR-11: Escalation threshold re-notification
When a cooling error's OccurrenceCount crosses 10×, 100×, or 1,000× (configurable via `escalation_thresholds`), emit a re-notification. Reuse stored LLMAnalysis — no fresh LLM call.
**Consequences:**
- Each threshold fires once per DeduplicationWindow (tracked via `last_notified_threshold`).
- Format: `"⚠️ Known error [HumanLabel] now seen [threshold]× since [first_seen_at]"` with stored root_cause, or `"no analysis on file"` if absent.

#### FR-12: Won't-fix volume override
For WontFix records, fire escalation only at the 1,000× threshold. WontFix state is NOT cleared after the override fires.
**Consequences:**
- Override format: `"⚠️ Won't-fix error [HumanLabel] now seen 1,000× since [first_seen_at] — volume is unusually high."`
- Override is a read-only nudge; developer must edit SuppressionFile to change their won't-fix decision.

#### FR-13: Escalation fires during backlog catchup
During backlog processing (stale checkpoint or recovery from DegradedState), EscalationThresholds fire in sequence as OccurrenceCounts are reconstructed. Realizes UJ-4.
**Consequences:**
- Developer sees the full escalation history for the downtime period, not a sanitized summary.

---

### 4.4 Won't-Fix Suppression

Developers can permanently silence known-acceptable errors. The suppression mechanism is zero-infrastructure (a text file and copy-paste) and developer-owned — LogGuard reads but never writes to it. Realizes UJ-3.

#### FR-14: SuppressionFile format and hot-reload
**Format:** one entry per line — `hash  # HumanLabel`. The hash is the lookup key; everything after `#` is a human-readable comment and does not affect matching. Blank lines and lines starting with `#` are ignored. Duplicate hashes are deduplicated on load (first occurrence wins).

Read SuppressionFile at the start of every poll cycle, before any ErrorDetected events are processed in that cycle. A suppression entry added while LogGuard is running takes effect within one poll interval without restart.

#### FR-15: SuppressionFile absent or unreadable
- If absent: treat as empty — not an error condition.
- If unreadable or parse error: keep the previously loaded list in memory, skip the reload, print: `"⚠️ Suppression file unreadable — using last known state"`.
**Consequences:**
- File corruption does not change suppression behaviour; previously-suppressed entries remain suppressed.

#### FR-16: LogGuard never writes to SuppressionFile
LogGuard has read-only authority over SuppressionFile. It must never write, truncate, or modify this file in any code path, including error-handling paths.
**Consequences:**
- Developer's WontFix decisions cannot be overridden by automated system behaviour.

#### FR-17: WontFix re-encounter label
When a WontFix fingerprint is re-encountered after the DeduplicationWindow expires, print one label per new cooling window: `"⚑ Known / Won't Fix: [HumanLabel]  [hash]"`. No LLM analysis.
**Consequences:**
- Developer is informed that a suppressed error resurfaced without being nagged on every occurrence.
- The label is an explicit acknowledgement — "I see this; you told me not to care about it" — categorically different from silence. Silence reads as "nothing happened." Never reduce WontFix output to no output; the distinction is the feature.

#### FR-18: Fingerprint identifier in output
Every new error output includes the fingerprint identifier for copy-paste into SuppressionFile. Realizes UJ-3.
**Consequences:**
- Output line: `Fingerprint: {HumanLabel}  [{hash}]`
- Suppression requires no tooling beyond a text editor.

#### FR-19: Unsuppression
When a fingerprint hash is removed from SuppressionFile, clear `won't_fix` on the existing DeduplicationRecord. If within DeduplicationWindow: treat as cooling. If window has expired: treat as new.
**Consequences:**
- No special-case code path required; removal from file drives the state transition.

---

### 4.5 LLM Root Cause Analysis

The LLM analysis hint is the core value of LogGuard — it must name a specific class, method, and line, not produce generic observations. All other pipeline components exist to make this output reachable. `[ASSUMPTION: The local LLM is running and accessible at a configured endpoint when LogGuard starts. LogGuard does not manage LLM lifecycle.]` `[ASSUMPTION: A local LLM can produce specific root cause hints on be.vdab.* stack traces at MVP quality — to be empirically validated before pipeline build (see §8, OQ-4).]`

#### FR-20: LLM payload construction
Construct the LLM payload from each new ErrorLog: exception_type, error_message, stack_trace (OwnCodeFrames only, truncated), service_name, app_name, vdab_authorization.
**Consequences:**
- Payload excludes Kubernetes metadata, raw syslog messages, and non-own-code stack frames.
- Token count is reduced and LLM focus is narrowed to application-specific frames.
- Including vdab_authorization provides triggering identity context — changing "unknown trigger" into "user MASTERBDB triggered a missing configuration path." This is deliberate analytical value, not incidental data inclusion.

**[POST-MVP LANDMINE]** vdab_authorization is included in the MVP payload because the local LLM keeps data on-prem. This field contains VDAB LDAP identities. It MUST be excluded before any cloud LLM integration. See §11.1 Data Governance.

#### FR-21: TenantData stripping
Apply `strip_tenant_data` to `error_message` and `stack_trace` before including in the LLM payload:
- Belgian KBO numbers (0XXX.XXX.XXX) → `[KBO]`
- Email addresses → `[EMAIL]`
- LDAP DN fragments in free text (cn=..., ou=...) → `[LDAP-DN]`
- UUIDs/GUIDs → **KEEP** (correlation IDs; critical for root cause analysis)
- Numeric IDs, port numbers → **KEEP**

**Consequences:**
- TenantData does not reach the LLM even on-prem.
- `vdab_authorization` is a separate field outside `strip_tenant_data` scope — handled by FR-20 landmine.

#### FR-22: LLM instruction frame
System prompt: `"You are a Java backend engineer. Given this Spring Boot error, identify the most likely root cause in the application code. Focus only on be.vdab.* frames. Be specific — name the class, method, and what likely went wrong there."`
**Consequences:**
- LLM cannot produce a generic observation without naming an OwnCodeFrame.

#### FR-23: Three-field output contract
LLM response must conform to three labelled fields in plain text: `Root cause:`, `Likely location:`, `Suggested action:` — each on its own line, each followed by the value. A response is malformed (triggering FR-24 fallback) if any of the three labels is absent or the response is empty.
**Consequences:**
- Output is consistently parseable by label-matching.
- Fixed structure prevents verbose hedging; the LLM must be specific or fail visibly.

#### FR-24: LLM failure fallback
On LLM failure (timeout, crash, malformed response): set `llm_available=false` and capture the failure reason in `LLMAnalysis.unavailability_reason`, deliver raw error data to terminal with `"analysis unavailable (reason)"` marker. Never silently drop an error.
**Consequences:**
- Developer sees every error LogGuard detects, even without a root cause hint.

#### FR-25: LLM analysis caching
Cache LLMAnalysis in `DeduplicationRecord.stored_analysis` after the initial call. Reuse at all EscalationThreshold re-notifications. No fresh LLM call at threshold.
**Consequences:**
- Local LLM throughput is bounded by unique new fingerprints per cycle, not total occurrence volume.

---

### 4.6 Terminal Output

The terminal is the MVP delivery surface. Output must perform passive triage — developer knows which service needs attention before reading a single error — and make copy-paste suppression frictionless. Realizes UJ-2, UJ-3.

Three design principles govern every output decision: (1) latency must read as "working" not "frozen" — a progress signal before LLM calls converts wait time from anxiety into confidence; (2) service grouping and error-count ordering are triage, not formatting — they communicate priority before any word is read; (3) a stale or absent banner must never create false calm — silence is never an acceptable health signal.

#### FR-26: Zero-results — no output
When a poll cycle returns zero ERROR logs, print nothing. Do not print "no new errors," a cycle counter, or any status line.
**Consequences:**
- Absence of output is not a health signal; DegradedState banner (FR-33) is the health signal. Adding "all quiet" noise every 5 minutes degrades signal quality.

#### FR-27: Batch progress signal
Print `"Analyzing N new errors in [service-name]..."` before starting LLM calls for each service group.
**Consequences:**
- Terminal latency reads as "working" not "frozen."

#### FR-28: Output grouped by service
Buffer all errors for a given `service_name` until analysis completes for that service, then print as a block.
**Consequences:**
- Developer reads all errors from one service as a coherent unit.

#### FR-29: Service block ordering
Order service blocks by error count descending within each poll cycle.
**Consequences:**
- Most-affected service prints first; passive triage without reading error details.

#### FR-30: Per-error block format

```
── {service-name} ({N} errors) ──────────────────
[{i}/{N}] {ExceptionType}@{ClassName}
  Root cause:       {root_cause | "analysis unavailable ({reason})"}
  Likely location:  {likely_location | "-"}
  Suggested action: {suggested_action | "-"}
  Fingerprint: {HumanLabel}  [{hash}]
```

**Consequences:**
- Fingerprint line enables direct copy-paste into SuppressionFile (UJ-3).
- `"analysis unavailable"` is explicit with the failure reason; error is never invisible.

#### FR-31: Escalation re-notification format

```
⚠️ Known error {HumanLabel} now seen {threshold}× since {first_seen_at}
   Root cause: {stored_analysis.root_cause | "no analysis on file"}
```

#### FR-32: WontFix label format

```
⚑ Known / Won't Fix: {HumanLabel}  {hash}
```

One label per re-encounter (after DeduplicationWindow expiry). No analysis.

---

### 4.7 Degradation Detection

LogGuard must never create the false impression that a silent terminal means production is healthy. When it cannot reach OpenSearch, it must break loudly and visibly. Realizes UJ-4.

#### FR-33: Consecutive-failure tracking
Track `consecutive_poll_failures`; reset to 0 on successful poll. Enter DegradedState after `max_consecutive_poll_failures` (default 3) consecutive failures.

#### FR-34: Sticky degradation banner
On entering DegradedState, record `degradation_started_at`. Print on every poll cycle while degraded:
```
🔴 LOGGUARD DEGRADED — OpenSearch unreachable since {degradation_started_at} ({N} failures)
```
**Consequences:**
- Banner reprints each cycle — a stale banner scrolling off cannot create false calm.

#### FR-35: Checkpoint stasis during degradation
Do not advance PollCheckpoint.last_successful_poll_at while OpenSearch is unreachable.
**Consequences:**
- On recovery, LogGuard queries from the same checkpoint and catches up automatically.

#### FR-36: Recovery line and automatic backlog processing
On OpenSearch recovery, clear `degradation_started_at` to null and reset `consecutive_poll_failures` to 0. Print:
```
🟢 LOGGUARD RECOVERED — polling resumed at {now}, catching up from checkpoint
```
Immediately process the full missed backlog (same behaviour as FR-2 for stale checkpoints). Realizes UJ-4.
**Consequences:**
- `degradation_started_at` being null is the canonical "healthy" state; clearing it on recovery prevents a stale banner from reprinting after recovery.

---

## 5. Non-Goals (Explicit)

- **Admin UI or management API** — all configuration via config files or environment variables. No web UI.
- **Authentication and user management** — LogGuard is a single-operator developer tool.
- **Log ingestion in production** — owned by production Spring Boot services writing to OpenSearch; LogGuard never writes to the index. For MVP validation, a companion Spring Boot error-producer app is in scope as a test fixture (see §6.1).
- **WARN and other non-ERROR log levels** — deferred post-MVP; will share DeduplicationWindow when implemented.
- **GitLab source deep-links** — deferred post-MVP; requires a per-app service registry config (repo URL + Maven module) that does not exist.
- **Gemini API / cloud LLM integration** — deferred post-MVP; blocked by `vdab.authorization` data governance requirement (§11.1).
- **Google Chat delivery and team-based alert routing** — deferred post-MVP; first post-MVP milestone.
- **Dead man's switch** — "zero errors across 28 apps for N hours" as a degradation signal is deferred post-MVP. For MVP, the human watching the terminal is the dead man's switch.
- **Cross-error pattern analysis, LLM urgency scoring, known-error knowledge base, cross-app temporal correlation** — post-MVP creative directions from brainstorming; out of scope for this release.

---

## 6. MVP Scope

### 6.1 In Scope

- Local OpenSearch instance running in a Docker container as the MVP development and test target
- Companion Spring Boot error-producer application that writes ERROR-level logs to the local OpenSearch container via Logback, producing documents that match the expected `_source.structured.*` / `_source.kubernetes.*` field structure LogGuard reads. **Acceptance:** LogGuard must be able to detect, fingerprint, and analyze errors produced by this app without any field-mapping changes — it is the primary integration test fixture for the MVP pipeline.
- Periodic error polling from OpenSearch (time-window scan, 5-min default interval)
- PollCheckpoint cursor with catch-up on restart or recovery
- First-run lookback prompt (default 24h)
- ErrorFingerprint computation: own-code-frame fallback (FR-7) + line-number asymmetry (FR-6)
- Three-state deduplication gate: new / cooling / won't-fix
- Escalation re-notifications at 10×, 100×, 1,000× (reusing stored LLMAnalysis)
- Won't-fix volume override at 1,000× (read-only nudge)
- SuppressionFile: format, hot-reload, absent/unreadable handling, read-only authority
- LLM payload construction: own-code frames only, `strip_tenant_data`, `vdab_authorization` (on-prem/local LLM only)
- LLM instruction frame + three-field output contract
- LLM failure fallback (raw data + "analysis unavailable")
- LLM analysis caching at DeduplicationRecord (reused at escalation thresholds)
- Terminal output: service grouping, error count ordering, per-error block format, progress signal
- Degradation detection: sticky banner, checkpoint stasis, recovery line + auto-backlog
- All operational config via config files or environment variables

### 6.2 Out of Scope for MVP

- Google Chat delivery, notification retry, H2 persistence for failed notifications `[NOTE FOR PM: the emotional upgrade from MVP — high developer demand once the terminal pipeline is validated]`
- Team-based alert routing via `kubernetes.namespace_labels.vdab_be_team`
- Gemini API integration — blocked by `vdab.authorization` data governance requirement
- GitLab source deep-links — blocked by missing service registry config per app
- WARN-level monitoring tier
- Cross-error pattern analysis, LLM urgency scoring
- Dead man's switch / zero-error anomaly detection
- Known-error knowledge base
- `max_delivery_attempts` config field (post-MVP; governs Google Chat notification retry — not applicable until Google Chat delivery is in scope)

---

## 7. Success Metrics

**Primary**

- **SM-1: LLM output specificity** — ≥70% of root cause hints name a specific be.vdab.* class and method (not a generic observation such as "may be a configuration issue"). Measured: developer manually samples 20 error analyses after the first operational week. Validates FR-22, FR-23. Counter-metric: SM-C1.

- **SM-2: Incident prevention** — at least one production error is caught and fixed by the developer before any user complaint in the first month. Validates UJ-2. Developer self-report is sufficient.

**Secondary**

- **SM-3: Suppression adoption** — at least 5 distinct fingerprints added to SuppressionFile within the first two weeks. Indicates the won't-fix workflow is usable in practice. Validates FR-14 through FR-19.

- **SM-4: Pipeline reliability** — zero silent error drops observed in the first month. Validates FR-3, FR-24, FR-35.

**Counter-metrics (do not optimize)**

- **SM-C1: Specificity vs. hallucination rate** — increasing LLM specificity at the cost of plausible-but-wrong class names is worse than "analysis unavailable." Do not tune the prompt to maximize specificity if it also increases confident-but-wrong outputs. Counterbalances SM-1.

- **SM-C2: Alert volume** — a drop in terminal output volume is only a success if it correlates with lower production error rates, not with missed fingerprints or over-aggressive suppression. Counterbalances SM-3.

---

## 8. Open Questions

1. **OQ-1: Local LLM hardware** — What machine and model will run the local LLM for MVP? This determines throughput during large backlog processing and the practical ~5–20 unique-per-cycle assumption in NFR-4. Needs answer before pipeline build begins.

2. **OQ-2: SuppressionFile ownership** — Will the file be version-controlled and shared across the team (one shared suppression file) or per-developer instance? Team-shared avoids duplicate won't-fix decisions; per-developer instance is simpler for MVP.

3. **OQ-3: `own_code_package_prefixes` scope** — Is `["be.vdab"]` the correct and complete prefix list? Are there subsidiary packages (e.g. `com.vdab`, third-party forks) that should be included?

4. **OQ-4: LLM prompt empirical validation — BLOCKING PREREQUISITE** — The instruction frame + three-field output contract must be tested against real be.vdab.* stack traces before any pipeline code is written. A running pipeline with hollow LLM output is a failure state, not a partial win (Red #1 from brainstorming). Validation: run 10 real error payloads manually against the local LLM; require ≥7 specific root cause hints naming a class and method before proceeding to pipeline build. If this gate fails, the prompt must be redesigned first.

5. ~~**OQ-5: Companion Spring Boot app — log writing mechanism**~~ — **Resolved: Logback.** The companion app uses Logback with an OpenSearch-compatible appender. The appender must be configured to produce documents with the `_source.structured.*` and `_source.kubernetes.*` field layout LogGuard reads — see ASSUMPTION-2.

---

## 9. Assumptions Index

- **[ASSUMPTION-1]** (§4.1) — For MVP, OpenSearch runs locally in a Docker container accessible at a configured local endpoint. A companion Spring Boot error-producer application generates test errors in the expected document structure. In production, this dependency is fulfilled by the existing Kubernetes-hosted OpenSearch cluster.
- **[ASSUMPTION-2]** (§4.1) — The companion Spring Boot app uses Logback with an OpenSearch-compatible appender (e.g. `logstash-logback-encoder`) configured to produce documents matching the `_source.structured.*` and `_source.kubernetes.*` field layout that LogGuard reads. The appender configuration — not LogGuard — is responsible for replicating the production field structure. The MVP index name is configurable; the production index pattern `logstash-app-openshift-application-springboot_error_*` is the long-term target.
- **[ASSUMPTION-3]** (§4.2) — Fingerprint deduplication reduces ~1,400 raw errors/day to ~5–20 unique LLM calls per 5-minute cycle. If unique fingerprint volume significantly exceeds this estimate, LLM throughput analysis is required before production use.
- **[ASSUMPTION-4]** (§4.5) — The local LLM is already running and accessible at a configured endpoint when LogGuard starts. LogGuard does not manage LLM lifecycle.
- **[ASSUMPTION-5]** (§4.5) — A local LLM produces specific root cause hints on be.vdab.* stack traces at MVP quality (≥70% specific per SM-1). Primary open hypothesis; empirical validation required first (OQ-4).

---

## 10. Cross-Cutting NFRs

- **NFR-1: No silent drops.** Every error LogGuard detects must either reach terminal output or be explicitly shown as `"analysis unavailable"`. No error may be silently discarded at any pipeline stage.

- **NFR-2: Self-observability.** LogGuard must make its own health state visible in the terminal at all times. A silent terminal must never be ambiguous between "no errors today" and "LogGuard is blind." (Realized by FR-33 through FR-36.)

- **NFR-3: Configuration-first.** All operational parameters (`poll_interval`, `deduplication_window`, `escalation_thresholds`, `own_code_package_prefixes`, `suppression_file_path`, `max_consecutive_poll_failures`, LLM endpoint) must be settable via config file or environment variable without code changes. No admin UI.

- **NFR-4: LLM throughput budget.** Dedup-before-LLM bounds LLM calls to ~5–20 unique fingerprints per cycle — not the raw volume. Any implementation change that breaks this (e.g. re-running LLM on every occurrence) must be explicitly reviewed and approved.

- **NFR-5: Checkpoint-advance invariant.** PollCheckpoint must advance only after full batch delivery. This is a hard invariant — advancing the cursor before batch completion silently introduces data-loss risk on crash. (Realized by FR-3.)

- **NFR-6: Read-only suppression authority.** LogGuard must never write to, truncate, or modify SuppressionFile in any code path, including error-handling paths. (Realized by FR-16.)

---

## 11. Constraints and Guardrails

### 11.1 Data Governance — vdab.authorization (HIGH SEVERITY)

**[POST-MVP LANDMINE]** `ErrorLog.vdab_authorization` contains VDAB LDAP identities (e.g. `cn=MASTERBDB,ou=users,ou=intern,O=VDAB`).

For MVP with local LLM, this field is included in the LLM payload and stays on-prem — acceptable.

Before any cloud LLM integration (Gemini API, post-MVP), this field **MUST** be explicitly excluded from the LLM payload construction step (FR-20). It is not in scope of `strip_tenant_data` (which operates on `message` and `stack_trace` text content). Failure to exclude it will silently ship internal VDAB user identities to a cloud provider.

This is not an implementation detail — it is a blocking data governance requirement for any cloud LLM migration.

### 11.2 Privacy — TenantData in Log Content

Error messages and stack traces may contain Belgian KBO numbers (business identifiers), email addresses, and LDAP DN fragments. These must be stripped before the LLM payload is constructed (FR-21) — even for the on-prem local LLM — to establish the data handling pattern required when the system transitions to Gemini.

UUIDs and numeric IDs are explicitly NOT stripped: they are technical correlation identifiers, not privacy-sensitive, and are often critical for root cause analysis.
