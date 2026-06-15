# PRD Quality Review — LogGuard AI (2026-06-15)

## Overall verdict

This is a strong, decision-ready PRD for an internal developer tool. The thesis is clear and consistently argued, FRs are testable with machine-checkable consequences, and scope boundaries — both what is in and what is out — are stated with explicit rationale. The two risks worth addressing before handing to an architect are: (1) the SuppressionFile format is partially specified in prose but never given a complete, unambiguous grammar that a developer could implement from the PRD alone; and (2) the data-schema contract for ErrorLog fields is referenced throughout but never formally defined in the PRD — it is delegated to the Allium spec without a forward pointer that downstream consumers can locate.

---

## 1. Decision-readiness — **strong**

The PRD names real trade-offs and states them as decisions, not as "considerations." The asymmetric line-number policy in FR-6 (throwing_method retains line number; stack_trace_sequence strips it) is not hedged — it is stated as a consequence with the rationale baked into the consequence itself. The FR-17 note that "silence is never an acceptable health signal" and "never reduce WontFix output to no output" is a strong design decision, not a weasel clause.

The data governance landmine in §11.1 is correctly flagged with severity, a specific code path (FR-20), and a clear blocking condition for the post-MVP cloud LLM path. This is a PM decision surfaced honestly. The SuppressionFile read-only authority (FR-16 + NFR-6) is stated as a hard constraint, not a preference.

OQ-4 is the strongest open question in the PRD and it is correctly escalated to BLOCKING PREREQUISITE. OQ-1 through OQ-3 are genuinely open (no answer in the next sentence).

### Findings

- **medium** Missing config-defaults table (§4.1, §10, §4.7) — NFR-3 names all configurable parameters, but the authoritative list of defaults (poll_interval=5m, deduplication_window=24h, max_consecutive_poll_failures=3, escalation_thresholds=[10,100,1000]) is scattered across multiple FR bodies. A single config-parameter table (name, default, section, env-var name) would make it unambiguous for an architect. *Fix:* Add a Config Reference appendix or table in §10, listing each param with its default, its FR anchor, and whether it is file/env-var configurable.

---

## 2. Substance over theater — **strong**

There is no persona theater: the PRD has a single operator role (VDAB backend developer) and does not pad it with fictional archetypes. The Non-Users section (§2.2) exists to clarify what "single operator" means in practice — it does real work by preventing scope creep into manager dashboards or Ops-facing views.

The Vision (§1) is earned. It names a specific causal chain: errors surface to the developer before users, fingerprint deduplication silences noise, and the signal quality compounds over time. The phrase "a quality culture shift engine that operates as a side effect of developers doing their normal work" is specific to this product's architecture. It would not slot into a generic monitoring PRD.

NFRs are product-specific. NFR-4 (LLM throughput budget with a concrete 5–20 call/cycle bound) and NFR-5 (checkpoint-advance invariant stated as a hard invariant with a crash-safety rationale) are not boilerplate. NFR-1 ("no silent drops") is also product-specific — it reflects the design principle that absence of output must not mean absence of health.

### Findings

- **low** FR-26 rationale is stated as an implicit design principle in §4.6 preamble — "absence of output is not a health signal" — but also appears inside FR-26 consequences. This is the right approach. No change needed; noting for completeness.

---

## 3. Strategic coherence — **strong**

The PRD has a clear thesis: invert the incident discovery dynamic (developer first, user never). Every feature section serves this thesis:
- Polling + checkpoint: makes discovery automatic rather than on-demand.
- Fingerprint deduplication: protects LLM throughput and signal quality (noise would undermine the thesis).
- Escalation: surfaces the transition from "bug" to "operational incident" before the user does.
- Won't-fix suppression: increases per-alert signal by removing known-acceptable noise.
- Degradation detection: closes the false-calm loophole that would silently undermine the thesis.

The MVP scope (§6.1) is coherent. It is a problem-solving MVP: prove the thesis against real be.vdab.* errors with a local LLM before adding delivery channels. The explicit deferral of Google Chat is correctly prioritized: it is the "emotional upgrade" (quoted in §6.2 NOTE FOR PM) that would follow only after the core pipeline is validated.

Success metrics validate the thesis: SM-1 (LLM specificity) and SM-2 (incident prevention) measure whether the thesis holds. SM-C1 (specificity vs. hallucination counter-metric) is the right counter-metric for SM-1 — it prevents a Goodhart's Law failure of optimizing specificity by increasing confident-but-wrong outputs.

### Findings

- **low** SM-4 ("zero silent error drops") is correct as a pipeline reliability metric, but the measurement method is unstated. It is unclear whether this means developer self-observation, log-level output tracing, or a test harness. Since OQ-4 (prompt validation) is blocking, SM-4 can remain informal at this stage — but an architect will ask how this is instrumented. *Fix:* Add a one-sentence measurement note to SM-4, e.g. "Validated by replaying a known-error batch and confirming output completeness."

---

## 4. Done-ness clarity — **adequate**

Most FRs have at least one verifiable consequence. The pattern is strong: FR-3 states "a crash after cursor advance but before batch emission cannot silently lose that batch" — this is testable (crash-inject after partial batch delivery, confirm re-fetch). FR-15 states exactly what to print on SuppressionFile parse error. FR-36 states exactly what to print on recovery.

However, two areas are thin:

**SuppressionFile format is never fully specified.** FR-14 through FR-18 describe the suppression mechanism in detail, but the SuppressionFile format is stated only as `hash  # HumanLabel` (FR-18 body). This leaves open: What is the separator — two spaces or a tab? Are blank lines allowed? Are comment-only lines (starting with `#`) allowed? What happens if the same hash appears twice? What is the maximum line length? An implementer cannot derive a parser from the PRD alone.

**ErrorLog field mapping is delegated without a pointer.** Multiple FRs reference `_source.structured.*` and `_source.kubernetes.*` field names, but the canonical field list — which fields exist, their types, and which FR reads which field — is deferred to `specs/logguard-ai.allium`. The PRD does not provide a forward pointer to that document's relevant section. An architect starting from only the PRD cannot determine the full data contract.

### Findings

- **high** SuppressionFile format underspecified (§4.4, FR-14 through FR-18) — The format `hash  # HumanLabel` is stated in FR-18 output, but the parse grammar is never defined: separator character, handling of blank lines, duplicate hashes, comment-only lines. An implementer cannot write a parser that the PRD would accept or reject as correct. *Fix:* Add a SuppressionFile Format subsection in §4.4 with an ABNF or 4-line prose spec: line format, separator, blank-line handling, duplicate-hash behavior (last-wins or first-wins or error).

- **high** ErrorLog field contract is externalized without a pointer (§4.1, §4.5, §4.2) — FRs 20, 22, and 6 reference `_source.structured.*` and `_source.kubernetes.*` field names (exception_type, throwing_method, stack_trace, vdab_authorization, service_name, app_name). The canonical mapping is not in the PRD and has no forward pointer. *Fix:* Add a one-paragraph ErrorLog Field Contract note in §4.1 that either lists the required fields inline or provides an explicit cross-reference to `specs/logguard-ai.allium` §section with the field table. Downstream consumers (architect, developer) must be able to source this without hunting.

- **medium** FR-23 output contract parsing behavior is unstated — The three-field output contract is defined (root_cause, likely_location, suggested_action), but the parsing strategy is not: structured JSON, regex extraction from free text, or XML? What constitutes a "malformed response" that triggers FR-24 fallback? *Fix:* Add one consequence line to FR-23: "LLM response is expected as structured output / JSON with these three fields; a response not conforming to this structure triggers FR-24 fallback."

- **low** FR-27 progress signal covers per-service batching but not the first-run backlog — UJ-1 involves a potentially large initial backlog spanning multiple services. FR-27 says "print `Analyzing N new errors in [service-name]...` before LLM calls for each service group" but it is ambiguous whether this fires per-service during first-run backlog processing or only during steady-state cycles. *Fix:* Add one clarifying consequence to FR-27: "Fires on every LLM invocation batch including first-run backlog and recovery backlog."

---

## 5. Scope honesty — **strong**

The Non-Goals section (§5) is specific and earns its place. Each deferral has a rationale: GitLab deep-links are blocked by a missing service registry; Gemini API is blocked by data governance; dead man's switch is a conscious MVP trade-off with an explicit human substitute named.

Inline `[ASSUMPTION]` tags are used correctly — they flag inferences that could prove wrong (ASSUMPTION-3: 5–20 unique fingerprints per cycle; ASSUMPTION-5: local LLM output quality). The Assumptions Index in §9 rounds-trips correctly to the inline tags.

The `[POST-MVP LANDMINE]` callout in FR-20 / §11.1 is the correct mechanism for a high-severity deferred risk. Naming it twice (inline at FR-20 and as a standalone §11.1 section) is the right redundancy for a blocking data governance item.

OQ-5 is correctly struck through and resolved with the Logback decision. This is good PRD hygiene — resolved questions are not silently removed; they are preserved with their resolution for audit purposes.

### Findings

- **medium** The companion Spring Boot error-producer app is in scope (§6.1) but has no FRs of its own — The PRD correctly scopes it as a test fixture, but its behavioral requirements (what it must produce, which field values are required, how to configure it) are only stated as ASSUMPTION-2. If the architect or developer treats this as an in-scope deliverable, they have no acceptance criteria. *Fix:* Either add a minimal FR set for the companion app (e.g. "FR-X: Companion app produces documents matching field layout in §4.1 ErrorLog contract note") or explicitly call it out as a separate deliverable outside this PRD's scope with a pointer to where its requirements live.

---

## 6. Downstream usability — **strong**

The Glossary (§3) is comprehensive and the domain nouns are used consistently throughout. The line-number asymmetry policy in FR-6 references both "throwing_method" and "stack_trace_sequence" using the exact Glossary terms — no synonyms in sight.

FR/UJ/SM IDs are contiguous with no gaps (FR-1 through FR-36, UJ-1 through UJ-4, SM-1 through SM-4, SM-C1 through SM-C2). Cross-references resolve: FR-3 realizes NFR-5, FR-16 realizes NFR-6, escalation FRs cite UJ-2.

UJs are short and narrative-form, appropriate for a single-operator CLI tool (see §7, Shape Fit). Each UJ names "Developer" as the protagonist and describes a complete scenario end-to-end including what they see and what they did to get there.

An architect can extract the full state machine from §4.2 (three-state deduplication gate FR-8), the persistence contract from FR-3 and FR-35, and the output contract from §4.6 without needing to read the full PRD linearly.

### Findings

- **medium** NFR-2 cross-reference to FRs is incorrect — NFR-2 says "Realized by FR-32 through FR-35" but FR-32 is WontFix label format; the correct degradation FRs are FR-33 through FR-36. FR-32 is a terminal output format for won't-fix, not a health signal. *Fix:* Change NFR-2 cross-reference to "Realized by FR-33 through FR-36."

- **low** §2.3 UJ format note ("lighter narrative format") references the rationale for abbreviated UJs — this is helpful context for reviewers but could be misread as an apology. No change required; noting for awareness.

---

## 7. Shape fit — **strong**

This PRD correctly reads its own shape. §2.3 opens with: "Since this is a CLI tool with a single operator role, UJs use the lighter narrative format." The UJs are scenario descriptions, not full user-story arcs with wireframe annotations. They are load-bearing (each maps to one or more FRs) but appropriately lightweight for a terminal tool.

The single-operator model is maintained throughout: no multi-tenant features, no role matrix, no admin vs. read-only separation. The SuppressionFile ownership question (OQ-2) is the only place where "single operator" becomes ambiguous — a team-shared suppression file would introduce a collaboration surface that the rest of the PRD does not account for. This tension is correctly flagged as an open question.

The PRD does not over-formalize. There are no UX wireframes, no information architecture diagrams, no persona empathy maps. For a CLI whose only output surface is a terminal, this is correct.

### Findings

- **low** OQ-2 (SuppressionFile team-sharing) could become a shape-breaking decision if resolved in favor of team-shared — A shared suppression file implies version control, merge conflicts, and possibly a format migration when the team adopts it. If this resolves to "team-shared," several FRs (FR-14 through FR-19) and ASSUMPTION-4 (local LLM lifecycle) would need to be revisited for concurrent-write behavior. *Fix:* Add a note to OQ-2: "If resolved as team-shared, revisit FR-14 (hot-reload on concurrent writes), FR-15 (unreadable handling during git operations), and §2.3 UJ-3 (assumes solo copy-paste workflow)."

---

## Mechanical notes

**Glossary drift:** None detected. Domain nouns are used with consistent capitalization and exact Glossary spelling throughout (ErrorLog, ErrorFingerprint, DeduplicationRecord, DeduplicationWindow, PollCheckpoint, OccurrenceCount, EscalationThreshold, WontFix, SuppressionFile, LLMAnalysis, OwnCodeFrame, TenantData, DegradedState).

**ID continuity:** FR-1 through FR-36: contiguous, no gaps. UJ-1 through UJ-4: contiguous. SM-1 through SM-4 with SM-C1 and SM-C2: contiguous. OQ-1 through OQ-5 (with OQ-5 struck through and resolved): contiguous. ASSUMPTION-1 through ASSUMPTION-5: contiguous.

**Assumptions Index roundtrip:** Five inline `[ASSUMPTION]` tags appear in §4.1 and §4.5. §9 Assumptions Index lists ASSUMPTION-1 through ASSUMPTION-5. All five round-trip correctly. No inline assumption is unindexed and no index entry is missing an inline counterpart.

**NFR cross-reference error (mechanically noted above in §6):** NFR-2 cites "FR-32 through FR-35" but should cite "FR-33 through FR-36."

**Addendum.md reference:** §0 states that "implementation details, options-considered rationale, and the prompt template text live in addendum.md." This file is not present in the directory. If it is intended as a PRD artifact, it should be created or the reference should be noted as a pending deliverable. Downstream consumers (developer, architect) will look for it.

**OQ-5 resolved:** Struck through correctly. Logback resolution is stated inline and cross-referenced as ASSUMPTION-2. Good hygiene.

**FR-20 has a duplicate "Consequences:" block:** Two separate "Consequences:" headings appear under FR-20 — one covering payload exclusions and one covering vdab_authorization. This is a structural artifact; the second block should either be merged with the first or labeled as a separate design note. Minor readability issue only.
