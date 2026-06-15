# Addendum — LogGuard AI PRD

*Captured from brainstorming session and allium spec. Content that belongs in a downstream document (architecture, solution design) or earned a place in the product record but does not belong in the PRD itself: rejected-alternative rationale, options-considered matrices, mechanism decisions, and technical-how.*

---

## A1. Fingerprint Design Decisions

### A1.1 Line-Number Asymmetry (Black #5)

throwing_method keeps the line number; stack_trace_sequence strips it. These two fields have OPPOSITE policies by design.

**Why throwing_method keeps the line number:** Two different bugs in the same method at different lines must produce different fingerprints. Stripping the line from throwing_method would collapse them, masking the second bug.

**Why stack_trace_sequence strips line numbers:** Any code change anywhere in the call path shifts line numbers in every affected frame. If sequence kept line numbers, every deploy across 28 continuously-deployed apps would mint new fingerprints and trigger a re-notification storm.

**Net behavior:** Dedup on path *structure* + exact *throwing coordinate*. A deploy that shifts the throwing line itself re-notifies once — absorbed by occurrence escalation.

### A1.2 Framework-Frame Fallback (Black #4)

When no OwnCodeFrame exists (Spring startup, HikariCP, static-resource 404s), use the topmost frame as the fallback.

**Why:** Without fallback, `first_own_code_frame` returns empty and all framework-only errors collapse into a single fingerprint, suppressing subsequent occurrences after the first.

**Example:** `NoResourceFoundException: No static resource jolokia` → fingerprints on `org.springframework.web.servlet.resource.ResourceHttpRequestHandler.handleRequest:526`, keeping it distinct from other framework errors.

---

## A2. Deduplication Options Considered (Black #1)

Three options were evaluated for handling occurrence-blind deduplication:

| Option | Description | Decision |
|---|---|---|
| Ⓐ Count + Escalate | Add occurrence_count; re-notify at 10×/100×/1000× reusing stored analysis | **CHOSEN** |
| Ⓑ Frequency Window | Bucket-count within rolling windows; alert if rate exceeds threshold | Rejected — complexity without clear benefit over simple counting |
| Ⓒ Silent Suppression | Keep existing behavior; suppress all duplicates within window | Rejected — OccurrenceCount is the primary "this is now an incident" signal; discarding it is a safety gap |

**Key constraint:** Re-notification at escalation thresholds reuses stored_analysis — no fresh LLM call. This protects local LLM throughput budget.

---

## A3. LLM Prompt Template (Blue #3, Blue #4)

### Instruction Frame

```
You are a Java backend engineer. Given this Spring Boot error, identify the most likely root cause in the application code. Focus only on be.vdab.* frames. Be specific — name the class, method, and what likely went wrong there.
```

### Output Contract

```
Root cause:      [one paragraph: what went wrong and why, naming the specific be.vdab.* class and method]
Likely location: ClassName.method:line  (e.g. "LabelV2Config.getForwardingSource:21")
Suggested action:[one paragraph: concrete steps the developer should take to investigate and fix the issue]
```

### Payload Fields (MVP — local LLM)

```
exception_type:     {ErrorLog.exception_type}
error_message:      {strip_tenant_data(ErrorLog.error_message)}
stack_trace:        {strip_tenant_data(be.vdab.* frames only)}
service_name:       {ErrorLog.service_name}
app_name:           {ErrorLog.app_name}
vdab_authorization: {ErrorLog.vdab_authorization}   ← EXCLUDED post-MVP (see §11.1)
```

### Prompt Validation Requirement (Priority 1 — before pipeline build)

Run the instruction frame + output contract against 10 real be.vdab.* error payloads manually against the local LLM before writing pipeline code. Success signal: ≥7 of 10 return specific root cause hints naming a class and method, not generic observations. If this fails, the pipeline has no value regardless of how well the rest is built.

---

## A4. strip_tenant_data Allow/Deny List (Black #8)

Scope: applied to `ErrorLog.error_message` and `ErrorLog.stack_trace` only. vdab_authorization is a separate field handled by the data governance landmine (FR-20).

| Pattern | Action | Rationale |
|---|---|---|
| Belgian KBO numbers (`0XXX.XXX.XXX`, 10-digit with dots) | STRIP → `[KBO]` | Unambiguous business identifier, never technical |
| Email addresses | STRIP → `[EMAIL]` | Unambiguous PII, never a technical artifact |
| LDAP DN fragments in free text (`cn=...,ou=...`) | STRIP → `[LDAP-DN]` | If they appear in message or stack_trace text |
| UUIDs / GUIDs | **KEEP** | Almost certainly correlation IDs — stripping degrades root cause analysis |
| Numeric IDs, row IDs, port numbers | **KEEP** | Technical context, not privacy-sensitive |

**UUID ambiguity rationale:** UUIDs can be both tenant identifiers and technical correlation IDs. The policy keeps UUIDs because their technical value (correlation, request tracking) exceeds their privacy risk in an on-prem local LLM context. Post-MVP with Gemini, this policy should be re-evaluated.

---

## A5. Checkpoint Advance Timing (Black #7)

The PollCheckpoint advance-after-delivery timing invariant was analyzed as a potential crash data-loss risk.

**Risk neutralized by design:** Because the cursor advances ONLY after full batch delivery:
- A crash mid-batch does not advance the cursor.
- On restart, the same batch is re-fetched.
- Fingerprint deduplication absorbs most duplicates from the re-fetch.
- Rare duplicate terminal line is the accepted cost.

**Alternative evaluated:** Advance-on-fetch (mark the batch as "claimed" before analysis). Rejected — any crash between fetch and delivery silently loses the batch, and there is no recovery mechanism.

---

## A6. Degradation Alert Design (Black #6)

**Original gap:** OpenSearchDegradationDetected and OpenSearchPollFailed events were defined in the allium spec but had no consumer rule — they fired into the void.

**Fix applied:** Added consumer rule that prints the degraded banner immediately. The key insight: a one-shot print recreates the original "quiet = calm" trap. The fix requires reprinting on every poll cycle while degraded.

**Deferred:** Dead man's switch (treat "zero errors for N hours across 28 apps" as its own degradation signal). Judged over-engineering for MVP where a human watches the terminal. Logged as a known gap.

---

## A7. Implementation Roadmap (from brainstorming session)

The brainstorming session confirmed this priority ordering:

### Priority 1 — Validate LLM Quality Before Building
1. Build structured.* payload extractor (6 fields, discard Kubernetes/syslog metadata)
2. Implement stack trace truncation (keep be.vdab.* frames only)
3. Implement strip_tenant_data allow/deny list (KBO, email → strip; UUID → keep)
4. **Run prompt validation in isolation** — 10 real error payloads against local LLM manually
5. Success signal: ≥7/10 specific root cause hints

### Priority 2 — Core Pipeline Loop
1. Poll loop with 5-min interval + time-window scan on error index
2. Checkpoint cursor (advance only after full batch delivery)
3. First-run prompt with 24h default
4. Stale checkpoint backlog processing with progress signal
5. Consecutive-failure degradation banner + recovery line
6. LLM failure fallback (raw data + "analysis unavailable")

### Priority 3 — Deduplication System
1. DeduplicationRecord: occurrence_count, last_notified_threshold, won't_fix fields
2. ErrorFingerprint: own-code-frame fallback; line number asymmetry
3. Escalation thresholds: 10×/100×/1000× reusing stored analysis
4. WontFix path: label once per re-encounter, 1000× override as read-only nudge
5. SuppressionFile: hash + label format, hot-reload, corruption fallback

### Priority 4 — Terminal Output
1. Group by service_name, ordered by error count descending
2. Per-error three-field block format
3. Status indicators: progress, degradation, recovery, won't-fix label, override warning

---

## A8. Decision Tree Fork Summary (all 18 forks)

These forks were decided in brainstorming and are captured in the allium spec. Listed here as a reference map.

| # | Fork | Decision |
|---|---|---|
| 1 | Zero results | Do nothing — human is dead man's switch for MVP |
| 2 | Dedup gate | Three paths: new / cooling / won't-fix |
| 3 | Won't-fix re-encounter | Label once per cooling window |
| 4 | LLM failure | Deliver raw data — "analysis unavailable" |
| 5 | Batch processing | Sequential with progress signal |
| 6 | Escalation, no analysis | Re-notify with "no analysis on file" |
| 7 | Won't-fix 1,000× override | Notify once — state unchanged |
| 8 | Stale checkpoint startup | Full backlog, full analysis |
| 9 | Backlog + thresholds | Thresholds fire during catchup |
| 10 | Won't-fix creation | SuppressionFile: hash + human label |
| 11 | SuppressionFile reload | Hot-reload every poll cycle |
| 12 | Override aftermath | WontFix state remains |
| 13 | First run | Ask developer for lookback window |
| 14 | Output grouping | Grouped by service_name |
| 15 | Service ordering | By error count descending |
| 16 | Unsuppression | Resume from dedup state |
| 17 | Degradation recovery | Auto-process backlog immediately |
| 18 | SuppressionFile corrupt | Last known good state + warning |
