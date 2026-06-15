# Brainstorming Reconciliation — LogGuard AI PRD

**Date:** 2026-06-15
**Source:** `_bmad-output/brainstorming/brainstorming-session-2026-06-08-1400.md`
**Against:** `prd.md` + `addendum.md`
**Method:** Cross-reference every confirmed decision, resolved Black Hat item, Decision Tree fork, Spec Landmine, and MVP-confirmed Spec Reconciliation item against the PRD and addendum. Flag what is missing, absent, or incorrectly captured.

---

## 1. Spec Landmines — Status Check

The brainstorming session defines exactly 4 landmines that must be loud comments in the spec before coding.

| Landmine | Brainstorming Location | PRD/Addendum Coverage | Status |
|---|---|---|---|
| Checkpoint advances after full batch delivery, not after fetch | Black #7 / Theme 1 / Landmine table | FR-3, NFR-5, Addendum A5 | ✅ COVERED — FR-3, NFR-5, and A5 all document this explicitly with rationale |
| `throwing_method` keeps line number; `stack_trace_sequence` strips it | Black #5 / Theme 2 / Landmine table | FR-6 (consequence block), Addendum A1.1 | ✅ COVERED — FR-6 names the "OPPOSITE line-number policies by design" and the collapse risk; A1.1 gives full rationale |
| `vdab.authorization` excluded from `strip_tenant_data` — cloud LLM blocker | Black #8 / Landmine table | FR-20 landmine callout, §11.1, FR-21, Addendum A4 | ✅ COVERED — HIGH SEVERITY block in §11.1, explicit callout in FR-20, referenced in A4 |
| Won't-fix override is read-only — LogGuard never writes to SuppressionFile | Black #7/Tree #12 / Landmine table | FR-16, NFR-6, FR-12 consequence block | ✅ COVERED — FR-16 and NFR-6 state this as a hard rule; FR-12 reinforces for the override case |

**Landmine verdict: All 4 covered. No gap.**

---

## 2. Confirmed Black Hat Decisions — FR Coverage

| Item | Decision | PRD FR | Status |
|---|---|---|---|
| Black #1 — Occurrence-count escalation | Add `occurrence_count`; re-notify at 10×/100×/1000× reusing stored analysis | FR-10, FR-11, FR-25 | ✅ COVERED |
| Black #4 — Framework-frame fallback | `throwing_method` = topmost frame when no own-code frame | FR-7 | ✅ COVERED |
| Black #5 — Line-number asymmetry | Keep in throwing_method; strip in stack_trace_sequence | FR-6 | ✅ COVERED |
| Black #6 — Orphaned degradation alert + sticky banner | Consumer rule + reprint banner every cycle + recovery line | FR-32/33/34/35, NFR-2 | ✅ COVERED |
| Black #7 — Checkpoint-advance timing | Cursor advances only after full batch delivery | FR-3, NFR-5 | ✅ COVERED |
| Black #8 — strip_tenant_data allow/deny list | Explicit allow/deny per pattern; UUIDs kept | FR-21, Addendum A4 | ✅ COVERED |

**Black Hat verdict: All 6 resolved decisions have corresponding FRs. No gap.**

---

## 3. Decision Tree Forks — FR Coverage

| Tree # | Fork | PRD/Addendum | Status |
|---|---|---|---|
| 1 — Zero results | Do nothing; human is dead man's switch | §5 Non-Goals (dead man's switch), UJ implicit | ⚠️ PARTIAL GAP — see §5.1 below |
| 2 — Three-path dedup gate | New/cooling/won't-fix | FR-8 | ✅ |
| 3 — Won't-fix re-encounter | Label once per cooling window | FR-17 | ✅ |
| 4 — LLM failure | Raw data + "analysis unavailable" | FR-24 | ✅ |
| 5 — Batch progress signal | "Analyzing N new errors..." before LLM calls | FR-26 | ✅ |
| 6 — Escalation, no analysis on file | Re-notify with "no analysis on file" | FR-11 consequence block | ✅ |
| 7 — Won't-fix 1,000× override | Notify once, state unchanged | FR-12 | ✅ |
| 8 — Stale checkpoint startup | Full backlog, full analysis | FR-2, FR-35 | ✅ |
| 9 — Backlog + thresholds cascade | Thresholds fire during catchup | FR-13 | ✅ |
| 10 — Won't-fix creation | Fingerprint hash + human label in output | FR-18 | ✅ |
| 11 — Suppression file hot-reload | Re-read at start of every poll cycle | FR-14 | ✅ |
| 12 — Override aftermath | Won't-fix state unchanged after override | FR-12 consequence block | ✅ |
| 13 — First-run lookback prompt | Ask developer; default 24h | FR-2 | ✅ |
| 14 — Output grouped by service | Buffer per service, print as block | FR-27 | ✅ |
| 15 — Service ordering by error count | Descending within each cycle | FR-28 | ✅ |
| 16 — Unsuppression | Remove won't-fix; resume dedup state | FR-19 | ✅ |
| 17 — Degradation recovery | Auto-process backlog immediately | FR-35 | ✅ |
| 18 — Suppression file corrupt | Last known good + visible warning | FR-15 | ✅ |

### 5.1 Tree #1 Gap — Zero-Results Behavior Not Explicitly Required

**Gap:** Tree #1 explicitly decides that zero poll results = do nothing, no output. The brainstorming distinguishes this from silence caused by degradation — the zero-results silence is intentional and expected, whereas degradation silence is false calm. The PRD covers the degradation case (FR-32/33) and the dead man's switch deferral (§5 Non-Goals), but does **not include an explicit FR** for the zero-results path: "On zero results, print nothing and wait for the next cycle." This creates a gap where an implementer has no requirement to reference.

**Severity:** Low — the degradation banner (FR-33) compensates by making OpenSearch failure visible. But the zero-results "do nothing" rule is a behavioral fork with no corresponding FR. An implementer might reasonably print "No new errors" every 5 minutes, degrading terminal UX by filling it with noise.

**Recommendation:** Add FR to §4.1 or §4.6: "When a poll returns zero ErrorLogs, LogGuard prints nothing and waits for the next cycle." One sentence closes the gap.

---

## 4. MVP Spec Reconciliation Items — §6.1 Coverage

From the brainstorming spec reconciliation table (2026-06-11):

| Confirmed MVP Item | §6.1 In Scope | Status |
|---|---|---|
| Local LLM (not Gemini) | Implicit via Gemini deferral + §6.2 | ✅ COVERED — §6.2 explicitly defers Gemini |
| Skip GitLab deep-links — class + line in plain text | §6.2 explicitly defers GitLab links; FR-18/FR-29 show fingerprint + location in plain text | ✅ COVERED |
| Terminal output (not Google Chat) | §6.2 defers Google Chat; §4.6 is terminal | ✅ COVERED |
| Fingerprint dedup (exception_type + first own-code frame + tenant-stripped trace, 24h window) | §6.1 item 5 | ✅ COVERED |
| Poll-checkpoint cursor with catch-up | §6.1 item 2 | ✅ COVERED |
| Poll interval 5 min default, easily configurable | FR-1 | ✅ COVERED |
| Consecutive-failure degradation alerting (terminal for MVP) | §6.1 last item, FR-32–35 | ✅ COVERED |

**Spec Reconciliation verdict: All MVP-confirmed items present in §6.1 or explicitly deferred in §6.2. No gap.**

---

## 5. White Hat Facts — FR Coverage

| White Hat Fact | PRD Coverage | Status |
|---|---|---|
| White #1 — Error index pre-filtering, no log-level filter needed | FR-4 | ✅ COVERED |
| White #2 — `structured.*` is the LLM payload (6–7 fields) | FR-20, Addendum A3 | ✅ COVERED |
| White #3 — `vdab.authorization` adds triggering identity context | FR-20, §11.1 | ✅ COVERED |
| White #4/#9 — Stack trace truncation: keep `be.vdab.*` frames only | FR-20, FR-21 | ✅ COVERED |
| White #5 — LLM operates stateless; zero per-app configuration | FR-22 (self-contained prompt), FR-5 (configurable prefixes) | ✅ COVERED |
| White #6 — GitLab URL construction skipped for MVP | §5/§6.2 | ✅ COVERED |
| White #7/#8 — GitLab URL post-MVP deferred | §5/§6.2 | ✅ COVERED |

**White Hat verdict: All facts captured. No gap.**

---

## 6. Terminal UX / Developer Experience — Tone and Voice

The brainstorming devotes Theme 3 entirely to terminal UX and includes a confirmed per-error block format. This section checks whether the qualitative UX design rationale (not just the format strings) made it into the PRD.

### 6.1 Per-Error Block Format — GAP (Minor)

**Brainstorming confirmed format (Theme 3):**
```
── orgbeheer-service ──────────────────────────────
[1/4] NPE@OrderService
  Root cause:      Null check missing before calling getForwardingSource()
  Likely location: LabelV2Config.getForwardingSource (LabelV2Config.java:21)
  Suggested action: Add null guard on source parameter before line 21
  Fingerprint: NPE@LabelV2Config:21  [a3f9c2b1]
```

**PRD FR-29 format:**
```
── {service-name} ({N} errors) ──────────────────
[{i}/{N}] {ExceptionType}@{ClassName}
  Root cause:       {root_cause | "analysis unavailable"}
  Likely location:  {likely_location | "-"}
  Suggested action: {suggested_action | "-"}
  Fingerprint: {HumanLabel}  [{hash}]
```

**Gap:** The brainstorming confirmed format does NOT include an error count in the service header (`{N} errors`). The PRD adds `({N} errors)` to the header — this is a small enhancement and consistent with FR-28's service-ordering-by-count intent. This is an additive improvement, not a contradiction.

**However**, FR-26's batch progress signal format reads: `"Analyzing N new errors in [service-name]..."` The brainstorming (Tree #5) says "Analyzing 3 new errors..." without the "in [service-name]" qualifier. The PRD variant is an improvement. No gap concern here.

**No material gaps in terminal UX format.**

### 6.2 Terminal UX Rationale — MISSING FROM PRD

**Gap:** The brainstorming's Theme 3 articulates *why* each UX decision was made in terms of developer psychology:
- Tree #5: "converts latency from 'is it frozen?' anxiety into 'it's working' confidence — a tiny UX detail with outsized psychological impact"
- Tree #14: "developer can immediately see 'orgbeheer has 4 errors this cycle' as a unit rather than mentally reconstructing"
- Tree #15: "The output itself performs triage. Before reading a single error, the developer already knows which app needs the most attention"
- Black #6: "a stale banner scrolling off cannot create false calm"

The PRD captures the behavioral *what* (FR-26–35) but does **not preserve the qualitative rationale** for the UX choices. This is partially addressed in §4.6's opening sentence ("Output must perform passive triage") and the FR-33 consequence block ("Banner reprints each cycle — a stale banner scrolling off cannot create false calm"), but the richer psychological framing from Theme 3 is lost.

**Severity:** Low for implementation correctness — the FRs are complete. Higher for future decision-making — if a developer asks "why does it group by service?" or "why does it reprint the banner?", the PRD's consequence blocks give a one-liner but not the full rationale.

**Recommendation:** Add a short narrative paragraph to §4.6 Terminal Output preamble capturing the passive-triage principle and the "is it frozen?" vs "it's working" signal design intent. This is the one area where the brainstorming's qualitative voice did not transfer to the PRD.

---

## 7. Red Hat and Yellow Hat — Strategic Framing

The Red and Yellow Hat outputs are largely strategic framing (value proposition, emotional calibration) rather than behavioral requirements. The PRD captures their outputs selectively:

| Hat Item | PRD Capture | Gap? |
|---|---|---|
| Red #1 — LLM output is the product; hollow output = failure state | SM-1 (≥70% specificity), SM-C1 (hallucination counter-metric), OQ-4 | ✅ Well captured via success metrics and open questions |
| Red #2 — Local LLM capability doubt | OQ-4, ASSUMPTION-5 | ✅ Captured as open hypothesis with validation gate |
| Red #3 — Local LLM is scaffold; Gemini is real target | §6.2 Gemini deferral, §11.1 | ✅ Captured |
| Yellow #1–5 — Quality culture shift engine framing | §1 Vision (last two paragraphs) | ✅ Captured in Vision |

**Red/Yellow verdict: Strategic framing well-represented in Vision and success metrics. No gap.**

---

## 8. Green Hat — Post-MVP Backlog Completeness

Green Hat items are confirmed post-MVP. The brainstorming explicitly tags Green #5 (Won't-fix / intentional suppression) as "MVP candidate."

| Green Item | MVP candidate? | §6.2/§5 coverage | Status |
|---|---|---|---|
| Green #1 — Warning-level tier | No | §5 + §6.2 | ✅ Deferred |
| Green #2 — Cross-error pattern analysis | No | §6.2 | ✅ Deferred |
| Green #3 — LLM urgency scoring | No | §6.2 | ✅ Deferred |
| Green #4 — Team-based alert routing | No | §6.2 | ✅ Deferred |
| Green #5 — Won't-fix / intentional suppression | **MVP candidate** | §4.4 (full feature section) | ✅ Confirmed in MVP scope |
| Green #6 — Known-error knowledge base | No | §6.2 | ✅ Deferred |
| Green #7 — Cross-app temporal correlation | No | §6.2 | ✅ Deferred |

**Green Hat verdict: All items correctly scoped. No gap.**

---

## 9. Blue Hat — Prompt Design Risk

Blue Hat identifies prompt design as the highest remaining uncertainty. The PRD captures this via:
- OQ-4 (must validate before pipeline build)
- ASSUMPTION-5 (empirical validation required)
- SM-1 (≥70% specificity threshold)
- Addendum A3 (full prompt template and validation requirement)
- Addendum A7 (Priority 1 = validate LLM before building)

**One gap exists:** Blue #2 states there are two unsolved prompt dimensions — (1) the instruction frame and (2) the output contract. The PRD resolves both (FR-22, FR-23, Addendum A3). However, **Blue #1's framing — "prompt design is not a dev task to do last — it is the primary MVP risk to validate first" — appears in Addendum A7 as implementation order, but is not expressed as a PRD-level constraint or gate**. An implementer reading only the PRD (not the addendum) sees OQ-4 as an open question but not as a blocking prerequisite.

**Severity:** Medium. If an implementer reads only the PRD, they may begin pipeline build before the prompt validation. OQ-4 is worded as a question, not a gate.

**Recommendation:** Strengthen OQ-4 in the PRD from a question to a blocking prerequisite: "Prompt empirical validation MUST precede pipeline build. See §8 OQ-4 and Addendum A3." Or promote the Addendum A7 Priority 1 note into §6.1 as a build sequencing constraint.

---

## 10. Summary of Gaps Found

### Gap 1 — Zero-Results Behavior Has No FR (Tree #1)
**Location in brainstorming:** Tree #1, Theme 1 pattern note
**Missing from PRD:** No FR states "on zero results, print nothing and wait for next cycle"
**Risk:** Implementer may add "No new errors" polling noise, degrading terminal UX
**Severity:** Low
**Fix:** One-sentence FR in §4.1 or §4.6

### Gap 2 — Terminal UX Rationale Not Preserved in §4.6 (Theme 3)
**Location in brainstorming:** Theme 3 — Tree #5, #14, #15, Black #6 rationale
**Missing from PRD:** §4.6 preamble captures "passive triage" but drops the "is it frozen?" vs "it's working" psychological framing and the "stale banner = false calm" motivation
**Risk:** Future changes to terminal format made without understanding the design intent
**Severity:** Low for implementation; medium for long-term maintainability
**Fix:** 2–3 sentence narrative paragraph in §4.6 preamble

### Gap 3 — Prompt Validation Is a Gate, Not a Question (Blue #1)
**Location in brainstorming:** Blue #1 ("primary MVP risk to validate first"), Priority 1 in roadmap
**Missing from PRD:** OQ-4 is worded as an open question; addendum A7 has Priority 1 ordering, but neither the PRD body nor §6.1 expresses this as a blocking prerequisite
**Risk:** Implementer begins pipeline build before prompt is validated; pipeline ships with hollow output
**Severity:** Medium — this is the single biggest MVP failure mode identified in brainstorming
**Fix:** Change OQ-4 wording to "BLOCKING prerequisite"; or add a §6.1 build-sequencing note

### Gap 4 — `vdab.authorization` White Hat Value Not Tied to FR-20 in LLM Payload
**Location in brainstorming:** White #3 — "Including auth context changes 'unknown error' into 'user MASTERBDB triggered a missing configuration path'"
**Missing from PRD:** FR-20 includes `vdab_authorization` in the payload but explains it only via its landmine risk (§11.1). The *positive reason* for including it — it changes "unknown error" into contextual root cause — is never stated. The data governance warning dominates; the analytical value is invisible.
**Risk:** A developer who only reads the landmine warning may exclude this field "to be safe" without understanding what they lose
**Severity:** Low-medium
**Fix:** Add one-sentence consequence to FR-20: "vdab_authorization provides the triggering LDAP identity, which narrows 'unknown error' to a specific user or system context and is kept because the local LLM is on-prem."

### Gap 5 — WontFix Label After Window Expiry: Visible Confirmation Rationale Not in FR-17
**Location in brainstorming:** Tree #2 and Tree #3 — "The label is the system speaking: 'I see this error. I remember you told me not to care about it.' That's categorically different from silence."
**Missing from PRD:** FR-17 specifies what to print but omits the design rationale — that the label is an intentional distinction from silence, confirming suppression is active (not a missed error)
**Risk:** An implementer may simplify FR-17 to "no output for won't-fix" to reduce noise, not realizing silence is the exact anti-pattern this design opposes
**Severity:** Low
**Fix:** Add consequence sentence to FR-17: "The label distinguishes active suppression ('I see this — you told me not to care') from silence ('I don't see anything'), which is the original risk this path was designed to close."

---

## 11. Items Confirmed Well-Covered (no gaps)

For completeness: the following areas were scrutinized and found fully captured.

- All 4 Spec Landmines — FR-3, FR-6, FR-16, §11.1 ✅
- All 6 resolved Black Hat decisions — FR-3, FR-7, FR-6, FR-32–35, FR-21 ✅
- 17 of 18 Decision Tree forks — all except Tree #1 zero-results ✅
- All 7 MVP Spec Reconciliation items — §6.1/§6.2 ✅
- All 7 White Hat facts — FR-1 through FR-7, FR-20–21 ✅
- Red Hat strategic framing — SM-1, SM-C1, OQ-4, ASSUMPTION-5 ✅
- Yellow Hat value proposition — §1 Vision ✅
- Green Hat post-MVP backlog — §5/§6.2 ✅
- strip_tenant_data allow/deny list — FR-21, Addendum A4 ✅
- Per-error block format — FR-29 (minor enhancement noted, not a gap) ✅
- Three-field LLM output contract — FR-23, Addendum A3 ✅
- LLM instruction frame — FR-22, Addendum A3 ✅
