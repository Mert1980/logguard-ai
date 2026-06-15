# Reconciliation: logguard-ai.allium → PRD + Addendum
*Generated: 2026-06-15*

Source: `specs/logguard-ai.allium`
PRD: `prd.md` (v draft, 2026-06-15)
Addendum: `addendum.md`

---

## Method

Each allium section was checked against prd.md FRs, NFRs, Non-Goals, Out-of-Scope, and addendum sections.
"Gap" = a spec element with no corresponding coverage in either document.
"Incorrect capture" = a spec element that IS present but materially misrepresents the spec intent.

---

## Gaps Found

### GAP-1 — PollCheckpoint.degradation_started_at is a data-model field with a spec-level clearing rule; the PRD covers the banner behavior but not the clear-on-recovery contract

**Spec (allium lines 107–112):**
```
entity PollCheckpoint {
    ...
    degradation_started_at: Timestamp?    -- non-null while LogGuard is in degraded state
}
```
Rule `PollForErrors` (line 253): `ensures: checkpoint.degradation_started_at = null` on a successful poll.

**PRD coverage:**
- FR-33 covers `degradation_started_at` being *set* when DegradedState is entered.
- FR-35 covers printing the recovery line and processing the backlog.
- **Neither FR-33 nor FR-35 explicitly states that `degradation_started_at` must be cleared (set to null) on a successful poll.**

Without an explicit clearing contract, an implementation could print the banner indefinitely, or display a stale start time after recovery.

**Recommendation:** Add a consequence to FR-35: "Clear `PollCheckpoint.degradation_started_at` to null on the first successful poll after DegradedState."

---

### GAP-2 — ProcessNewError fingerprint derivation: suppressed-at-creation path creates a DeduplicationRecord with `won't_fix = true` before any cooling window has run; this "won't-fix from birth" sub-case is not captured as a distinct behavior

**Spec (allium lines 301–312, rule `ProcessNewError`):**
```
let is_suppressed = fingerprint_hash(fingerprint) in loaded_suppression_file
ensures: DeduplicationRecord.created(
    ...
    won't_fix: is_suppressed,
    ...
)
ensures: if not is_suppressed: ErrorReadyForAnalysis(error, fingerprint)
ensures: if is_suppressed: WontFixEncountered(fingerprint)
```

When a brand-new fingerprint (no active record) is first seen AND is already in the suppression file, it goes directly to `WontFixEncountered` — creating a record with `won't_fix = true` immediately, without ever entering the "new → analyse" path.

**PRD coverage:**
- FR-8 (three-state gate) describes "Won't-fix" as "active record, won't_fix=true" — implying `won't_fix` is only set when a record already exists.
- FR-17 (WontFix re-encounter label) is described as "after the DeduplicationWindow expires," but in this sub-case the window has just *started* — the label fires on the very first encounter.
- **No FR explicitly covers the case: fingerprint is new (no active record) AND already in suppression file → create record with won't_fix=true and print the label once.**

This is not a corner case: it fires every time LogGuard restarts or a fingerprint's 24h window expires and then the error recurs while the fingerprint is still in the suppression file.

**Recommendation:** Add a consequence to FR-8 or FR-17: "If the fingerprint has no active record AND is in the SuppressionFile, create the DeduplicationRecord with `won't_fix=true` and emit the WontFix label once. No LLM analysis."

---

### GAP-3 — Config field `max_delivery_attempts` is Post-MVP; it is NOT listed in NFR-3 (Configuration-First) or any config enumeration in the PRD

**Spec (allium lines 199–201):**
```
config {
    ...
    -- Post-MVP:
    max_delivery_attempts: Integer = 3
}
```

**PRD coverage:**
- §6.2 Out of Scope correctly excludes "notification retry, H2 persistence for failed notifications."
- NFR-3 lists MVP config fields explicitly: `poll_interval, deduplication_window, escalation_thresholds, own_code_package_prefixes, suppression_file_path, max_consecutive_poll_failures, LLM endpoint`.
- `max_delivery_attempts` is absent from NFR-3 — consistent, since it is Post-MVP.
- **However, the Post-MVP config field is not mentioned under §5 Non-Goals or §6.2 Out of Scope**, leaving a potential implementer confusion about whether it should be wired in for MVP or not.

**Recommendation:** Add `max_delivery_attempts` to §6.2 Out of Scope (or a Post-MVP config note in NFR-3) so implementers know the field exists but is intentionally deferred.

---

### GAP-4 — Rule `ReloadSuppressionFile` specifies that the reload runs *before* `PollForErrors` within the same cycle; the PRD captures hot-reload (FR-14) but does not capture the within-cycle ordering guarantee

**Spec (allium lines 224–237):**
```
rule ReloadSuppressionFile {
    when: _: PollCheckpoint.last_successful_poll_at + config.poll_interval <= now
    ensures: SuppressionFileReloaded()
    @guidance
        -- Read config.suppression_file_path before each poll cycle.
        -- ...
```
The rule fires on the same trigger as `PollForErrors`, and the `@guidance` is explicit: the loaded list must be current *before* errors are polled and processed.

**PRD coverage:**
- FR-14: "Read SuppressionFile at the start of every poll cycle." — This is present.
- **The ordering constraint — reload completes before any `ErrorDetected` event is processed in the same cycle — is implied by "start of every poll cycle" but is not stated as a consequence or explicit constraint.**

If reload and poll are implemented concurrently or if "start of cycle" is interpreted loosely, a suppression entry added between cycles could be missed for the batch it was meant to suppress.

**Recommendation:** Add a consequence to FR-14: "SuppressionFile reload completes before the first ErrorDetected event is processed in the same poll cycle. These two operations are sequential, not concurrent."

---

### GAP-5 — The spec's `LLM` external entity has `unavailability_reason: String?` field; the PRD captures the fallback behavior (FR-24) but the `unavailability_reason` propagation path through `LLMAnalysis.unavailability_reason` into terminal output is not specified

**Spec (allium lines 31–34, 71–79):**
```
external entity LLM {
    available: Boolean
    unavailability_reason: String?
}

value LLMAnalysis {
    llm_available: Boolean
    ...
    unavailability_reason: String?
}
```
Rule `AnalyseError` (line 415–418) explicitly passes `LLM.unavailability_reason ?? "unknown"` into `LLMAnalysis.unavailability_reason`.

**PRD coverage:**
- FR-24: "On LLM failure … deliver raw error data to terminal with `'analysis unavailable'` marker."
- FR-29 (per-error block format): shows `"analysis unavailable"` as the fallback string for root_cause.
- **Neither FR-24 nor FR-29 specifies that `LLMAnalysis.unavailability_reason` should be surfaced in the terminal output (e.g. as a parenthetical after "analysis unavailable").**

The spec intent is that the developer can see *why* the LLM was unavailable (timeout? crash? misconfiguration?) directly in the terminal, not just that it was unavailable.

**Recommendation:** Add a consequence to FR-24 or FR-29: "When `llm_available = false`, append `LLMAnalysis.unavailability_reason` to the 'analysis unavailable' output, e.g. `'analysis unavailable (LLM.unavailability_reason)'`."

---

## Post-MVP Items — Coverage Check

| Post-MVP item (allium) | Covered in Non-Goals / Out of Scope? |
|---|---|
| Gemini API | Yes — §5 Non-Goals + §6.2 |
| Google Chat delivery (DeliverNotification, NotificationDeliverySucceeded/Failed, RetryPersistedNotifications) | Yes — §5 Non-Goals + §6.2 |
| Team-based routing via `kubernetes.namespace_labels.vdab_be_team` | Yes — §6.2 |
| GitLab source links (GitLabLink value type, GitLabRepository entity) | Yes — §5 Non-Goals + §6.2 |
| WARN-level monitoring | Yes — §5 Non-Goals + §6.2 |
| `max_delivery_attempts` config field | **Not explicitly listed** — see GAP-3 |
| `Notification` entity (status state machine) | Implied by "notification retry" in §6.2 but entity/state machine not named |

The `Notification` entity state machine (`pending → delivered → failed → pending`, terminal: `delivered`) exists in the spec but is not referenced anywhere in the PRD or addendum (not even as a deferred design note). This is an implicit gap for the post-MVP architecture document, but since the state machine is purely post-MVP, it is not a PRD gap per se — flagged here for awareness.

---

## Landmine Comments — Coverage Check

| Allium landmine | PRD coverage |
|---|---|
| `[POST-MVP LANDMINE]` on LLM entity (line 29–30): exclude `vdab_authorization` from payload before Gemini | Yes — FR-20 landmine callout + §11.1 Data Governance |
| `[POST-MVP LANDMINE]` on `ErrorLog.vdab_authorization` (lines 131–135): same | Yes — FR-20 + §11.1 |
| `[DESIGN INVARIANT]` on `ErrorFingerprint`: opposite line-number policies | Yes — FR-6 consequences |
| `[DESIGN INVARIANT]` on `DeduplicationRecord`: LogGuard NEVER writes to suppression file | Yes — FR-16 + NFR-6 |
| `[DESIGN INVARIANT]` on `PollForErrors`: advance cursor ONLY after full batch emitted | Yes — FR-3 + NFR-5 |

All five landmine/invariant comments have corresponding PRD coverage. No landmine is uncaptured.

---

## Summary Table

| ID | Severity | Element | Gap type |
|---|---|---|---|
| GAP-1 | Medium | `PollCheckpoint.degradation_started_at` clear-on-recovery | Missing consequence in FR-35 |
| GAP-2 | High | `ProcessNewError` won't-fix-from-birth sub-case | Incorrect / incomplete capture in FR-8 / FR-17 |
| GAP-3 | Low | `max_delivery_attempts` post-MVP config field | Not mentioned in Out of Scope / NFR-3 |
| GAP-4 | Medium | `ReloadSuppressionFile` ordering guarantee (before PollForErrors in same cycle) | Implied but not stated as consequence in FR-14 |
| GAP-5 | Low | `LLMAnalysis.unavailability_reason` propagation to terminal output | Missing from FR-24 / FR-29 output contract |
