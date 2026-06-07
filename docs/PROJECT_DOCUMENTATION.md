# LogGuard AI — Project Documentation

## Overview

LogGuard AI is an AI-driven error detection and analysis system that monitors OpenSearch logs. It periodically polls for ERROR-level log entries, deduplicates them, analyses them using a Gemini LLM, and delivers structured notifications to a Google Chat webhook — complete with LLM-generated insights and direct links to the relevant source code in GitLab.

---

## Scope

**Included in MVP:**
- Periodic polling of OpenSearch for ERROR-level log entries
- Fingerprint-based deduplication to suppress repeated alerts
- LLM analysis via Gemini
- Google Chat notifications with exponential retry on failure
- GitLab source code links for own-code stack frames

**Excluded from MVP:**
- Log ingestion (handled by production Spring Boot services writing to OpenSearch)
- WARN and other non-ERROR log levels
- Admin UI or management API (all configuration is via config files/environment variables)
- Authentication and user management

---

## Configuration

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `poll_interval` | Duration | 1 hour | How often OpenSearch is polled for new errors |
| `deduplication_window` | Duration | 24 hours | How long a seen fingerprint suppresses duplicate alerts |
| `max_delivery_attempts` | Integer | 3 | Maximum Google Chat delivery attempts before marking a notification as failed |
| `own_code_package_prefix` | String | — | Java package prefix (e.g. `com.vdab`) used to identify own-code stack frames and generate GitLab links |

---

## External Dependencies

| System | Purpose |
|--------|---------|
| **OpenSearch** | Source of ERROR-level log entries |
| **Gemini LLM** | Analyses errors and produces summaries, root causes, and fix suggestions |
| **Google Chat Webhook** | Destination for structured notifications |

---

## Data Model

### GitLabRepository
Maps an application name to its GitLab repository URL. Managed via config files. When no mapping exists for an app, GitLab links are omitted from the notification.

| Field | Type | Description |
|-------|------|-------------|
| `app_name` | String | Application name (matches `app_name` on log entries) |
| `repo_url` | String | Base URL of the GitLab repository |

---

### PollCheckpoint
A single persistent record tracking the timestamp of the last successful poll. The cursor is **never advanced** when OpenSearch is unreachable, so the next successful poll automatically covers any missed window.

| Field | Type | Description |
|-------|------|-------------|
| `last_successful_poll_at` | Timestamp | End of the last successfully processed poll window |

---

### ErrorLog
A single ERROR-level log entry retrieved from OpenSearch. Fields map to OpenSearch `_source.structured` and `_source.kubernetes`.

| Field | OpenSearch path | Description |
|-------|-----------------|-------------|
| `exception_type` | `structured.error.type` | Java exception class name |
| `error_message` | `structured.error.message` | Human-readable error message |
| `stack_trace` | `structured.error.stack_trace` | Full stack trace |
| `service_name` | `structured.service.name` | Originating service |
| `app_name` | `kubernetes.labels.appName` | Kubernetes app label |
| `team` | `kubernetes.namespace_labels.vdab_be_team` | Owning team |
| `environment` | `kubernetes.namespace_labels.vdab_be_environment` | Deployment environment |
| `severity` | `structured.log.level` | Log level (always `ERROR` in MVP) |
| `occurred_at` | `@timestamp` | When the error occurred |

---

### ErrorFingerprint
A deduplication key derived from an `ErrorLog`. Two errors with the same fingerprint — even for different tenants or KBO numbers — are treated as the same error and suppressed within the deduplication window.

| Field | Description |
|-------|-------------|
| `exception_type` | Java exception class name |
| `throwing_method` | `class.method` of the first own-code frame in the stack trace |
| `stack_trace_sequence` | Full stack trace with tenant-specific data (KBO numbers, IDs) stripped |

---

### DeduplicationRecord
Persists the fact that a fingerprint was seen, suppressing duplicate notifications for the configured window.

| Field | Type | Description |
|-------|------|-------------|
| `fingerprint` | ErrorFingerprint | The deduplication key |
| `first_seen_at` | Timestamp | When this error was first detected |
| `expires_at` | Timestamp | When this record stops suppressing duplicates (`first_seen_at + deduplication_window`) |

---

### LLMAnalysis
The result of Gemini analysing an error. When Gemini is unavailable, the summary/root-cause/fix fields are absent and the notification is sent with raw error details and an explanation of the unavailability.

| Field | Present when | Description |
|-------|-------------|-------------|
| `llm_available` | always | Whether Gemini was available |
| `summary` | LLM available | Short human-readable summary of the error |
| `root_cause` | LLM available | Likely root cause |
| `suggested_fix` | LLM available | Suggested remediation |
| `unavailability_reason` | LLM unavailable | Why Gemini could not be reached |

---

### GitLabLink
A resolved link to a specific line in a GitLab source file. Only generated for stack frames whose class name matches `own_code_package_prefix`.

| Field | Type | Description |
|-------|------|-------------|
| `class_name` | String | Fully qualified Java class name |
| `line_number` | Integer | Line number in the source file |
| `url` | String | Direct URL to that line in GitLab (targeting `main` branch) |

URL format: `{repo_url}/-/blob/main/{class/to/path.java}#L{line_number}`

---

### Notification
A notification ready to be sent to Google Chat. Retried across poll cycles until delivered or exhausted. Persisted after `max_delivery_attempts` so it survives a service restart.

| Field | Type | Description |
|-------|------|-------------|
| `error_log` | ErrorLog | The original error |
| `analysis` | LLMAnalysis | LLM analysis result |
| `gitlab_links` | List\<GitLabLink\> | Source code links for own-code frames |
| `status` | `pending` \| `delivered` \| `failed` | Delivery status |
| `attempt_count` | Integer | Number of delivery attempts made |
| `failure_reason` | String? | Last failure reason (if any) |
| `persisted_at` | Timestamp? | When the notification was persisted for restart recovery |

**Status transitions:**

```
pending ──► delivered  (terminal)
   │
   └──► failed ──► pending  (reset at start of next poll cycle)
```

---

## Processing Pipeline

The system processes errors through the following sequence of rules:

### 1. Poll for Errors (`PollForErrors`)

Every `poll_interval`, LogGuard queries OpenSearch for all ERROR-level entries with `occurred_at > last_successful_poll_at`. Each new entry triggers an `ErrorDetected` event. The checkpoint cursor is advanced **only after** all events have been emitted, so a crash mid-poll does not silently drop entries.

If OpenSearch is unreachable, the cursor is not advanced and an `OpenSearchPollFailed` event is recorded. The next poll automatically retries from the same checkpoint, catching up on all errors logged during the outage.

---

### 2. Deduplicate (`ProcessNewError`)

On `ErrorDetected`, a fingerprint is computed:
- **exception_type** — the exception class
- **throwing_method** — the first stack frame whose class starts with `own_code_package_prefix`
- **stack_trace_sequence** — the full stack trace with KBO numbers and per-tenant identifiers stripped

If an unexpired `DeduplicationRecord` exists for this fingerprint, the error is **silently discarded** — no notification is produced. Otherwise a new `DeduplicationRecord` is created and an `ErrorReadyForAnalysis` event is emitted.

---

### 3. Analyse (`AnalyseError`)

On `ErrorReadyForAnalysis`, LogGuard calls Gemini with the error details. If Gemini is available, the response populates `summary`, `root_cause`, and `suggested_fix`. If Gemini is unavailable, an `LLMAnalysis` is constructed with `llm_available = false` and the unavailability reason, so the notification still ships.

In parallel, GitLab links are generated for every own-code stack frame:
- Own-code frames are those whose class name starts with `own_code_package_prefix`
- The class name is converted to a file path (e.g. `com.vdab.MyClass` → `src/main/java/com/vdab/MyClass.java`)
- Links target the `main` branch of the mapped repository

A `Notification` record is created with `status = pending`.

---

### 4. Deliver (`DeliverNotification`)

When a `Notification` enters `pending` status, LogGuard attempts to POST it to the Google Chat webhook.

- **Success** → status set to `delivered` (terminal)
- **Failure, attempts remaining** → `attempt_count` incremented; retry with exponential backoff (e.g. 10 s → 20 s → 40 s)
- **Failure, attempts exhausted** → status set to `failed`, `persisted_at` recorded so the notification survives a service restart

---

### 5. Retry Failed Notifications (`RetryPersistedNotifications`)

At the start of every poll cycle, any persisted `failed` notifications are reset to `pending` with `attempt_count = 0`, queuing them for re-delivery alongside newly detected errors.

---

## Error Handling Summary

| Scenario | Behaviour |
|----------|-----------|
| OpenSearch unreachable | Checkpoint not advanced; next poll covers the missed window |
| Duplicate error (same fingerprint, within window) | Silently discarded; no notification sent |
| Gemini unavailable | Notification sent with raw error details and unavailability note |
| Google Chat delivery failure | Exponential backoff up to `max_delivery_attempts`; then persisted for next-cycle retry |
| Service restart with undelivered notifications | Persisted failed notifications are picked up and retried on the next poll cycle |

---

## Open Questions

These questions remain unresolved and may affect the final implementation:

1. **Auto GitLab discovery** — When no `GitLabRepository` mapping exists for an `app_name`, should LogGuard fall back to querying the GitLab API to find the repository automatically?

2. **Own-code prefix as a list** — Is a single `own_code_package_prefix` string sufficient, or is a list needed to cover multiple packages in a multi-module project?

3. **GitLab branch for source links** — Should links always target `main`, or be configurable per repository, or derived from the `environment` field of the log entry?

4. **Failed notification persistence store** — Embedded database (e.g. H2), file system, or in-memory? In-memory storage loses undelivered notifications on service restart.

5. **Consecutive OpenSearch failure alerting (post-MVP)** — After N consecutive failed poll cycles, send a degradation warning to Google Chat. What is the right threshold for N?

6. **Log level scope (post-MVP)** — MVP covers `ERROR` only. Should `WARN` or other levels be added? If so, do they share the same deduplication window and notification channel?
