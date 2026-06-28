# Code Review Session — 2026-06-24 (COMPLETED 2026-06-29)

**Status:** DONE. D1–D4 answered (see "DECISIONS MADE" below); D1/D2/D3 + P1–P6 applied as nine separate
commits (D4 kept as an accepted deviation, no code change). Test suite green (23 pass / 1 skipped — the
live OpenSearch test). Stories 2.4/2.5/3.1/3.2 moved to `done` in sprint-status.yaml; epic-3 → done.

## DECISIONS MADE (2026-06-28)
- **D1 → Subtract refresh safety-lag (Patch).** Advance checkpoint to `pollStart − refreshWindow`
  in `PollService.java`; re-scans the recent window each cycle, Epic 4 dedup absorbs the double-fetch.
- **D2 → Add `@Transactional(timeout=…)` (Patch).** Keep the AR-9-compliant boundary, bound it with an
  explicit tx timeout in `TransactionalPollUseCase.java`.
- **D3 → Add the four constants now (Patch).** Add `DEGRADED/RECOVERED/WONT_FIX/ESCALATION` to
  `TerminalOutputAdapter.java` to satisfy Story 2.4 AC literally.
- **D4 → Keep 2s (accepted deviation, Defer/dismiss).** Document the deviation from AR-12's 0;
  `PollService` empty-checkpoint guard makes it safe.

## Scope reviewed
- **Diff:** commit range `409e8f7..HEAD` (branch `refactoring`, HEAD `ab994cb`).
- **Commits:** `1f573d4` poll-loop wiring · `c9c0f74` Epic 3 · `28ee979` demo config · `ab994cb` audit gap-fixes.
- **Stories:** 2.4 (terminal output), 2.5 (poll loop + checkpoint/transactional boundary), 3.1
  (TenantDataSanitizer/payload/prompt/record), 3.2 (Ollama adapter + fallback).
- **Spec/AC source:** `_bmad-output/planning-artifacts/epics.md` (no per-story files exist).
- **Layers run:** Blind Hunter, Edge Case Hunter, Acceptance Auditor (all returned; none failed).
- **Tally:** 4 decision-needed · 6 patch · 7 defer · 2 dismissed.

## DECISION-NEEDED — answer these to resume (recommendation in **bold**)

### D1 — Checkpoint advance vs OpenSearch indexing lag (High) [PollService.java]
Checkpoint advances to `pollStart` captured *before* the query; an error timestamped before `pollStart`
but made searchable *after* the query runs (refresh latency) is queried `> pollStart` next cycle and
silently skipped — NOT covered by Epic 4 dedup (never fetched).
- Option A: Add a refresh safety-lag (advance to `pollStart − refreshWindow`). → Patch
- Option B: Advance to `max(occurredAt)` of the returned batch. → Patch
- Option C: **Accept, defer to Epic 4** (current behavior follows FR-3 "rely on dedup" intent). → Defer

### D2 — Long @Transactional boundary (Medium) [TransactionalPollUseCase.java]
AR-9 mandates the tx wrap query + all per-error LLM calls (≤120s each); no dedup yet, so a large/slow
batch can starve the Hikari pool / hit tx timeout.
- Option A: **Add `@Transactional(timeout=…)`, keep boundary** (stays AR-9-compliant). → Patch
- Option B: Narrow tx to checkpoint save only (deviates from AR-9). → Patch + deviation note
- Option C: Accept until Epic 4 dedup bounds per-cycle calls. → Defer

### D3 — Status-indicator constants missing (Medium) [TerminalOutputAdapter.java]
Story 2.4 AC requires `DEGRADED/RECOVERED/WONT_FIX/ESCALATION` constants; nothing emits them until
2.6/Epic 4.
- Option A: **Defer to 2.6/Epic 4** (avoid dead code now; documented AC gap). → Defer
- Option B: Add the four constants now to satisfy the AC literally. → Patch

### D4 — initialDelay=2s vs AR-12's 0 (Low) [PollScheduler.java]
Code uses 2s to let the first-run prompt finish; `PollService` already self-guards on missing checkpoint.
- Option A: **Keep 2s (accepted deviation)**. → Defer/dismiss
- Option B: Change to 0 per AR-12 (relies on the empty-checkpoint guard). → Patch

## PATCH — handle after D1–D4 (unambiguous fixes)
- **P1 (High)** `logguard.ollama.timeout` is a dead property — only `base-url`+`model` bridged to
  `spring.ai.ollama`; 120s never applied. Wire it (Story 3.2 AC). `application.yml`, `LlmAdapter.java`.
- **P2 (Med)** `ownCodeFrames`: `line::contains` matches a prefix anywhere (not just `at` frames); header
  line duplicated if it contains a prefix; blank/empty prefix unguarded. `LlmAdapter.java`.
- **P3 (Med)** Null `ErrorLog` fields render as literal `"null"` in the LLM payload and terminal header
  (`null`/`null@Class`); `dash()` guards only analysis fields. `LlmAdapter.java`, `TerminalOutputAdapter.java`.
- **P4 (High)** LDAP-DN regex misses spaces-after-comma and non-`cn=` prefixes (`uid=`, multi-valued RDN)
  → tenant DN leaks to LLM (FR-21, PII). `TenantDataSanitizer.java`.
- **P5 (Low)** `unavailabilityReason` rendering: blank ⇒ `analysis unavailable ()`; long/multi-line raw
  messages break the block layout. `TerminalOutputAdapter.java`.
- **P6 (Low)** `loadSystemPrompt` doesn't close the classpath `InputStream` (try-with-resources). `LlmAdapter.java`.

## DEFERRED (7) — persisted to `deferred-work.md`
crash-reprint dupes→Epic 4 · `throwingClass` framework-frame→Epic 4 FingerprintService · suppression
result discarded/unguarded→Story 4.3 · KBO undotted (out of AC) · email false-positives · NFR-4 per-error
LLM (Epic 4) · AR-18 waived.

## DISMISSED (2)
- "poll no-ops forever if no checkpoint" — false positive; `FirstRunInitializer` always persists a
  24h-default checkpoint even non-interactively.
- "scheduler overlap" — `fixedDelay` prevents overlap by default.

## Resume checklist
1. Answer D1–D4 (see options above).
2. Choose patch handling: apply all / walk through each. (No story file to "leave as action items" in —
   `epics.md` isn't a writable story file.)
3. Sprint-status sync: `story_key` was never set (target came from explicit args, not sprint tracking),
   so the skill's auto-sync is skipped — update 2.4/2.5/3.1/3.2 in `sprint-status.yaml` manually based on
   the outcome.
4. Tests: run with `JAVA_HOME=/c/Users/MDMIROK/.jdks/temurin-21.0.11` then `./mvnw test` (Bash tool;
   the `.cmd` wrapper is blocked by Group Policy).
