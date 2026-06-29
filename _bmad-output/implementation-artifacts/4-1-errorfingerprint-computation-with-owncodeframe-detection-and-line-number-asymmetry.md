---
baseline_commit: b5453e30c90907f8199f3723418f27217deea342
---

# Story 4.1: ErrorFingerprint Computation with OwnCodeFrame Detection and Line-Number Asymmetry

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a developer,
I want each error uniquely identified by its structural fingerprint rather than its raw content,
so that two occurrences of the same bug produce the same fingerprint regardless of deploy-shifted line numbers in the call path.

## Acceptance Criteria

1. **Given** an `ErrorLog` with a stack trace containing `be.vdab.*` frames, **when** `FingerprintService.compute(ErrorLog)` is called, **then** `ErrorFingerprint.throwingMethod` = simple class name + method + LINE NUMBER of the **first** `be.vdab.*` frame (line number KEPT) — e.g. `LabelV2Config.getForwardingSource:21`.
2. `ErrorFingerprint.stackTraceSequence` = the structural sequence of **all** `be.vdab.*` frames with ALL line numbers STRIPPED.
3. Two `ErrorLog` records with the same exception type and call path but different line numbers in **non-throwing** frames produce the **same** `stackTraceSequence` but **may differ** on `throwingMethod` (if the throwing frame's own line shifted).
4. **Given** an `ErrorLog` whose stack trace contains **no** `be.vdab.*` frames (Spring startup, HikariCP, static 404s), **when** `compute` is called, **then** `throwingMethod` = the **topmost** frame of the stack trace (framework-frame fallback) and `stackTraceSequence` = the topmost frame with its line number stripped.
5. Two distinct framework-only errors with different topmost frames produce **different** fingerprints.
6. `FingerprintService` lives in `domain/service/` with **zero Spring annotations**.
7. The own-code prefix list is read from `LogguardProperties.ownCodePackagePrefixes` (e.g. `["be.vdab"]`) — passed in, not hardcoded; multi-prefix matching is supported.
8. Unit tests in `domain/service/FingerprintServiceTest` cover: normal own-code path, framework fallback, line-number asymmetry (same call path, shifted lines → same `stackTraceSequence` but different `throwingMethod`), and multi-prefix matching.

### Additional contract (foundation for Stories 4.2–4.4 — build it now, don't defer)

9. `ErrorFingerprint` is a Java `record` in `domain/model/` with **zero JPA/Spring annotations**, components: `String exceptionType`, `String throwingMethod`, `String stackTraceSequence`.
10. `ErrorFingerprint` exposes a stable `hash()` — a deterministic short hash over its three components (see Dev Notes "Hash algorithm"). Same three components → same hash across JVM restarts. This is the lookup key used by `DeduplicationRecordRepository.findActiveByFingerprint(...)` (Story 4.2) and matched against the suppression file (Story 4.3).
11. A `null` or blank `stackTrace` does not throw: `throwingMethod` falls back to the `exceptionType` (or `"unknown"` if that too is blank) and `stackTraceSequence` is the empty string — the method always returns a non-null `ErrorFingerprint` (mirrors the no-throw discipline of `TenantDataSanitizer` / `LlmAdapter`).

## Tasks / Subtasks

- [x] **Task 1: `ErrorFingerprint` domain record** (AC: #9, #10)
  - [x] Create `be.vdab.logguard.domain.model.ErrorFingerprint` as a Java `record` with components `String exceptionType`, `String throwingMethod`, `String stackTraceSequence`. **Zero Spring/JPA annotations** (pure domain — alongside `ErrorLog`, `LLMAnalysis`, `PollCheckpoint`).
  - [x] Add an instance method `String hash()` that returns the short stable hash defined in Dev Notes "Hash algorithm" (SHA-256 over a canonical `exceptionType + '\n' + throwingMethod + '\n' + stackTraceSequence`, lowercase hex, first 8 chars). Keep the hashing self-contained in this record (use `java.security.MessageDigest` — no new dependency); do **not** introduce a Spring bean for it.
  - [x] Do **not** add a `humanLabel`/`@{ClassName}` field here — the FR-30/FR-18 HumanLabel (`NPE@LabelV2Config:21`) is assembled in Story 4.4 from `exceptionType` + `throwingMethod`. This story only supplies the raw components.
- [x] **Task 2: Frame parsing helper** (AC: #1, #2, #4, #7) — read Dev Notes "Frame parsing" first; reuse the proven regex shape from `LlmAdapter`
  - [x] Inside `FingerprintService`, define a `private static final Pattern FRAME` that captures **FQCN (group 1)**, **method (group 2)**, and the **parenthesised source location (group 3)** from a frame line such as `\tat be.vdab.x.LabelService.forwardingSourceFor(LabelService.java:27)`. Extend the `LlmAdapter` pattern (`^\s*at\s+([\w$.]+)\.[\w$<>]+\(`) to also capture method + location — see Dev Notes for the exact regex.
  - [x] Split the stack trace with `stackTrace.split("\\R")` (handles `\n`/`\r\n`), skip the line-0 exception header for frame scanning (line 0 is `ExceptionType: message`, not an `at` frame).
  - [x] Own-code test on a parsed FQCN: `prefixes.stream().anyMatch(p -> fqcn.equals(p) || fqcn.startsWith(p + "."))` — exact reuse of the `LlmAdapter` rule (prevents a prefix appearing in a message from false-matching, and supports multiple prefixes). Filter out null/blank prefixes first, exactly as `LlmAdapter.ownCodeFrames` does.
- [x] **Task 3: `throwingMethod` computation (line number KEPT)** (AC: #1, #4)
  - [x] Find the **first** own-code frame (first `at` frame, scanning top→down, whose FQCN matches a prefix). Compute `throwingMethod = simpleClassName(fqcn) + "." + method + ":" + lineNumber`, e.g. `LabelService.forwardingSourceFor:27`. `simpleClassName` = substring after the last `.` of the FQCN (mirror `TerminalOutputAdapter.throwingClass`’s `lastIndexOf('.')` logic).
  - [x] Parse the line number from group 3 (`LabelService.java:27`) as the integer after the last `:`. If a frame has no line number (native method `(Native Method)`, or `(Unknown Source)`), keep the method without a `:line` suffix rather than failing.
  - [x] **Framework fallback (AC #4):** if no own-code frame exists, use the **topmost** `at` frame (first frame after the header) for `throwingMethod`, with the same `Class.method:line` shape. If there are no `at` frames at all (degenerate trace), fall back to `exceptionType` (AC #11).
- [x] **Task 4: `stackTraceSequence` computation (line numbers STRIPPED)** (AC: #2, #3, #4, #5)
  - [x] Collect **all** own-code frames (top→down, preserving order), normalise each to a line-number-free token `{fqcn}.{method}` (e.g. `be.vdab.x.LabelService.forwardingSourceFor`), and join with `\n` (one frame per line). **Strip line numbers from every frame** — this is the half of the asymmetry that must survive deploys.
  - [x] **Framework fallback (AC #4/#5):** if no own-code frame exists, the sequence is the single **topmost** frame normalised the same way (line stripped). Distinct framework topmost frames → distinct sequences → distinct fingerprints.
  - [x] **CRITICAL — opposite line-number policies:** `throwingMethod` KEEPS the line number; `stackTraceSequence` STRIPS it. Do **not** route both through one normaliser. See Dev Notes "Line-number asymmetry — the whole point of this story".
- [x] **Task 5: `FingerprintService.compute(ErrorLog)`** (AC: #1–#7, #11)
  - [x] Create `be.vdab.logguard.domain.service.FingerprintService` — **pure Java, no Spring annotations** (mirror `TenantDataSanitizer`). It needs the prefixes; accept them by constructor (`FingerprintService(List<String> ownCodePackagePrefixes)`) so the domain stays Spring-free. The Spring wiring (a `@Bean` or `@Configuration` that does `new FingerprintService(props.ownCodePackagePrefixes())`) is **out of scope** for this story — no `PollService` integration here (that is Story 4.2). If you must place the bean to keep the context starting, put it in `infrastructure/config/` and note it; do not annotate the domain class.
  - [x] `public ErrorFingerprint compute(ErrorLog error)` — assemble `exceptionType = error.exceptionType()`, `throwingMethod` (Task 3), `stackTraceSequence` (Task 4). Never throws; null/blank `stackTrace` → AC #11 behaviour.
- [x] **Task 6: Unit tests `FingerprintServiceTest`** (AC: #8, plus #3/#5/#10/#11)
  - [x] In `src/test/java/be/vdab/logguard/domain/service/FingerprintServiceTest.java` — pure JUnit 5, no Spring context (mirror `TenantDataSanitizerTest`: `new FingerprintService(List.of("be.vdab"))`, static `assertEquals`/`assertTrue`).
  - [x] **Normal own-code path:** a trace with a `be.vdab.*` throwing frame → `throwingMethod` carries `Class.method:line`; `stackTraceSequence` lists the own-code frames with no line numbers.
  - [x] **Line-number asymmetry (AC #3):** two traces, identical call path and identical throwing-frame line, but a **non-throwing** own-code frame shifted by N lines → **equal** `stackTraceSequence` AND **equal** `hash()`. Then a second pair where the **throwing** frame's own line differs → **equal** `stackTraceSequence` but **different** `throwingMethod` (and different `hash()`).
  - [x] **Framework fallback (AC #4/#5):** a trace with zero `be.vdab.*` frames (e.g. Spring/Hikari/JDK only) → `throwingMethod`/`stackTraceSequence` from the topmost frame; and two different framework-only traces → different `hash()`.
  - [x] **Multi-prefix matching (AC #7):** construct with `List.of("be.vdab", "com.acme")` and assert a `com.acme.*` frame is treated as own-code.
  - [x] **Hash stability (AC #10):** same components → same `hash()`; different components → different `hash()`; `hash()` is 8 lowercase hex chars.
  - [x] **Null/blank safety (AC #11):** `null` stackTrace and blank stackTrace both return a non-null fingerprint with `stackTraceSequence == ""` and `throwingMethod` falling back to the exception type.

## Dev Notes

### Scope boundary (read first)
- This story delivers **only** the `ErrorFingerprint` record + `FingerprintService` (pure domain) + its unit tests. It does **NOT** touch `PollService`, the dedup gate, persistence, the suppression file, terminal output, or escalation — those are Stories 4.2 (`DeduplicationRecord` + three-state gate + LLM caching), 4.3 (suppression file), 4.4 (won't-fix + Fingerprint line in output), 4.5 (escalation).
- Do **not** wire `FingerprintService` into the poll loop in this story. It is exercised directly by its unit test, exactly as `TenantDataSanitizer` was in Story 3.1. Keeping the bean wiring out avoids touching the green `PollService` path before Story 4.2 needs it.
- The deferred-work note "`throwingClass` can show a framework/JDK frame" (`TerminalOutputAdapter`) is **resolved by 4.4**, not here — do not edit `TerminalOutputAdapter` in this story. You are building the engine 4.4 will consume.

### Line-number asymmetry — the whole point of this story (FR-6)
The two output fields have **opposite** line-number policies. This is the single most important invariant; getting it wrong silently collapses distinct bugs or fragments one bug into many.

| Field | Line numbers | Why | Example |
|---|---|---|---|
| `throwingMethod` | **KEPT** | Two different bugs in the *same* method throw at *different* lines — keeping the line distinguishes them. | `LabelV2Config.getForwardingSource:21` |
| `stackTraceSequence` | **STRIPPED** | A deploy that adds/removes code *elsewhere* shifts call-path line numbers without changing the bug — stripping lets the fingerprint survive the deploy. | `be.vdab.x.A.foo\nbe.vdab.x.B.bar` |

[Source: epics.md#FR-6; specs/logguard-ai.allium#value ErrorFingerprint lines 49–64 — `WARNING: throwing_method and stack_trace_sequence have OPPOSITE line-number policies. Do NOT normalise both the same way`.]

### ErrorFingerprint contract (authoritative)
`value ErrorFingerprint` in the allium spec has exactly three fields — `exception_type`, `throwing_method`, `stack_trace_sequence`. No `hash` field in the spec; the spec models the hash as a function `fingerprint_hash(fingerprint)`. We realise it as the `hash()` **method** on the record (AC #10) so callers (Story 4.2 repository lookup, Story 4.3 suppression match, Story 4.4 output) have one canonical source. Do not store the hash as a 4th component.
[Source: specs/logguard-ai.allium lines 49–64, 301–302; architecture.md line 564 `FingerprintService.compute(error) → ErrorFingerprint`; architecture.md lines 263–278 (domain value object = Java record, lives in `domain/model/`, zero JPA).]

### Hash algorithm (author decision — spec leaves it abstract)
The spec/architecture do **not** pin the algorithm; they only show an 8-hex-char example hash `a3f9c2b1` in the suppression-file format (`a3f9c2b1  # NPE@LabelV2Config:21`). Implement:
- Canonical input string = `exceptionType + "\n" + throwingMethod + "\n" + stackTraceSequence` (null components treated as empty string).
- `MessageDigest.getInstance("SHA-256")` over the UTF-8 bytes; render lowercase hex; take the **first 8 characters**.
- 8 hex chars (32 bits) is ample for the ~5–20 unique fingerprints per cycle (NFR-4) and matches the spec's example width and copy-paste ergonomics.
This is the one open design point in the story — see the question at the end. If the team prefers full 64-char hex (no truncation), that is a one-line change, but downstream output formats (FR-18/FR-32, Story 4.4) assume the short form.
[Source: specs/logguard-ai.allium lines 230–231, 455–461 (suppression format `hash  # HumanLabel`, example `a3f9c2b1`); epics.md#FR-18.]

### Frame parsing — reuse the proven shape from `LlmAdapter` (do NOT reinvent)
Story 3.2 already solved own-code frame selection correctly (parse the FQCN, don't substring-match). Mirror it, extending the capture to method + location.

Existing (LlmAdapter.java:108–136) — captures FQCN only:
```java
private static final Pattern FRAME = Pattern.compile("^\\s*at\\s+([\\w$.]+)\\.[\\w$<>]+\\(");
// own-code test:
prefixes.stream().anyMatch(p -> fqcn.equals(p) || fqcn.startsWith(p + "."));
// split:
String[] lines = stackTrace.split("\\R");   // line 0 = exception header, scan frames from index 1
```
For this story, extend to also capture method + source location:
```java
// group 1 = FQCN, group 2 = method, group 3 = "File.java:27" | "Native Method" | "Unknown Source"
private static final Pattern FRAME =
        Pattern.compile("^\\s*at\\s+([\\w$.]+)\\.([\\w$<>]+)\\(([^)]*)\\)");
```
Line number = if group 3 contains a `:`, the integer after the **last** `:`; else none (native/unknown).
Simple class name = `fqcn.substring(fqcn.lastIndexOf('.') + 1)` (handles default-package FQCNs where `lastIndexOf` returns -1 → whole string), mirroring `TerminalOutputAdapter.throwingClass` (lines 103–114).
[Source: `src/main/java/be/vdab/logguard/infrastructure/llm/LlmAdapter.java:108–136`; `src/main/java/be/vdab/logguard/infrastructure/terminal/TerminalOutputAdapter.java:19–20,103–114`.]

### Stack trace format (concrete, verified live in Stories 1.2/2.3)
The companion writes standard Logback/Java throwable output. Header line then tab-indented `at` frames:
```
java.lang.NullPointerException: Cannot invoke "String.toUpperCase()" because "resolved" is null
	at be.vdab.logguard.producer.service.LabelService.forwardingSourceFor(LabelService.java:27)
	at be.vdab.logguard.producer.web.LabelController.get(LabelController.java:14)
	at org.springframework.web.method.support.InvocableHandlerMethod.doInvoke(InvocableHandlerMethod.java:255)
	... (framework frames)
```
Frame line anatomy: `\tat ` + FQCN (`be.vdab.logguard.producer.service.LabelService`) + `.` + method (`forwardingSourceFor`) + `(` + location (`LabelService.java:27`) + `)`. A "Caused by:" section may appear; for MVP treat the whole string uniformly — the first own-code `at` frame top-to-bottom is the throwing frame. (No nested-cause special handling required by the AC.)
[Source: `_bmad-output/implementation-artifacts/2-3-opensearch-error-polling-adapter.md:88–98` live `_source`; `src/test/java/be/vdab/logguard/infrastructure/opensearch/OpenSearchAdapterTest.java:30`.]

### Field-source mapping
`compute` consumes only two `ErrorLog` fields:
| Used | `ErrorLog` component | Notes |
|---|---|---|
| exception type | `error.exceptionType()` | copied verbatim into `ErrorFingerprint.exceptionType` |
| stack trace | `error.stackTrace()` | parsed for frames; may be null/blank → AC #11 |
`ErrorLog` is `record ErrorLog(String exceptionType, String errorMessage, String stackTrace, String serviceName, String appName, String team, String environment, Instant occurredAt, String vdabAuthorization)`.
[Source: `src/main/java/be/vdab/logguard/domain/model/ErrorLog.java:19–30`.]

### Hexagonal / enforcement rules (reviewed in every story)
- `domain/` is **Spring-free**: no `@Component`/`@Service`/`@Autowired`/`@Transactional`/`@Entity`. `FingerprintService` is a plain class; `ErrorFingerprint` is a plain record. (Architecture: "If a domain test requires `@SpringBootTest`, the domain layer has a dependency leak.")
- Config flows in via constructor param (a `List<String>`), never `@Value`, never reading `LogguardProperties` from inside `domain/`.
- Java 21 records for all domain value objects.
- Test suffix `Test` for pure unit tests; no Spring context.
[Source: architecture.md lines 263–278, 321–329, 402–407.]

### Testing standards
- `FingerprintServiceTest` is pure (mirror `TenantDataSanitizerTest`: `private final FingerprintService service = new FingerprintService(List.of("be.vdab"));`, JUnit 5 `@Test`, static `org.junit.jupiter.api.Assertions.*`). No Docker, no Ollama, no OpenSearch — fully hermetic so `mvn test` stays green offline.
- Build/test via Bash with JDK 21 (`source "$HOME/.sdkman/bin/sdkman-init.sh"` first; native `mvn`, NOT the blocked `mvnw.cmd`). Command: `mvn -B -ntp test`.
- The asymmetry test (AC #3) is the keystone — assert both the equal-sequence and the differing-throwing-method halves explicitly, with comments naming the line shift, so a future refactor can't silently break the invariant.

### Project Structure Notes
- NEW: `src/main/java/be/vdab/logguard/domain/model/ErrorFingerprint.java`; `src/main/java/be/vdab/logguard/domain/service/FingerprintService.java`; `src/test/java/be/vdab/logguard/domain/service/FingerprintServiceTest.java`.
- Packages already exist (Story 2.1 `package-info.java`). `domain/service/package-info.java` already names `FingerprintService` as planned for this location. Add classes alongside the package-info; keep it.
- No `pom.xml` change (SHA-256 via JDK `java.security.MessageDigest`). No `application.yml` change (`logguard.own-code-package-prefixes: [be.vdab]` already present at `application.yml:41–42`). No new dependency.

### References
- [Source: epics.md#Story 4.1] — acceptance criteria
- [Source: epics.md#FR-6, #FR-7] — fingerprint computation, line-number asymmetry, framework-frame fallback
- [Source: specs/logguard-ai.allium#value ErrorFingerprint lines 49–64] — field contract + the OPPOSITE-line-number-policy warning
- [Source: specs/logguard-ai.allium lines 305–322] — `own_code_package_prefixes`, `first_own_code_frame`, `normalize_stack_trace`, framework fallback
- [Source: specs/logguard-ai.allium lines 230–231, 455–461] — hash + HumanLabel suppression-file format (`a3f9c2b1  # NPE@LabelV2Config:21`)
- [Source: architecture.md lines 263–278] — domain value object = Java record in `domain/model/`, zero JPA; naming `{Name}Service`
- [Source: architecture.md lines 321–329, 402–407] — test conventions; Spring-free domain enforcement
- [Source: architecture.md line 564] — `FingerprintService.compute(error) → ErrorFingerprint`

### Previous Story Intelligence (Stories 3.2, 3.1, 2.x)
- **Story 3.2 own-code parsing (REUSE):** `LlmAdapter.ownCodeFrames()` (lines 108–136) is the corrected own-code frame selector — FQCN parsed via regex group 1, prefix test `fqcn.equals(p) || fqcn.startsWith(p + ".")`, `split("\\R")`, header line kept separately, null/blank prefixes filtered. Mirror this exactly; only extend the regex to also capture method + location. Do not regress to substring matching.
- **Story 3.1 pure-domain pattern (MIRROR):** `TenantDataSanitizer` (domain/service) — plain class, `private static final Pattern` constants, explicit null guard, no Spring. `TenantDataSanitizerTest` — `new TenantDataSanitizer()`, JUnit 5, static asserts. Replicate this shape for `FingerprintService`/`FingerprintServiceTest`.
- **Story 2.4 throwing-class (CONSUMER, do not edit here):** `TerminalOutputAdapter.throwingClass()` currently derives `@{ClassName}` from the **topmost** frame and self-documents that Epic 4 `FingerprintService` will refine it to the first own-code frame. That refinement is Story 4.4's job; this story just makes the data available.
- **Config already present:** `LogguardProperties.ownCodePackagePrefixes()` → `List<String>`, `@DefaultValue("be.vdab")`, prefix `logguard`. `application.yml:41–42` sets it. Pass the list into `FingerprintService` via constructor.
- **Toolchain:** native build via SDKMAN (Java 21.0.11 / Maven 3.9.16); Bash, not the blocked `mvnw.cmd`. Tests must stay hermetic (no external services).

### Git Intelligence
- Baseline HEAD `b5453e3` "Implement degradation detection, sticky banner, recovery (Story 2.6)". Epic 3 (3.1/3.2) is `done`; the LLM adapter and sanitizer are committed and stable — safe to read as reuse references.
- Established patterns to mirror: pure-domain service + record (3.1), `private static final Pattern` frame regex with FQCN group capture (3.2), hermetic JUnit 5 unit tests instantiated with `new` and a `List.of(...)` prefix argument.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Opus 4.8, 1M context)

### Debug Log References

- Native build via SDKMAN (Java 21.0.11 / Maven 3.9.16); `mvn -B -ntp test` (the `mvnw.cmd` wrapper is blocked on this host).
- `mvn -B -ntp test` → **BUILD SUCCESS, Tests run: 41, Failures: 0, Errors: 0, Skipped: 1** (the skipped one is the pre-existing `-Dopensearch.live`-gated `OpenSearchAdapterLiveTest`). +11 over the prior baseline (30 pass / 1 skipped) — all from `FingerprintServiceTest`. No regressions.

### Completion Notes List

- **All 11 ACs satisfied.** `FingerprintService.compute(ErrorLog)` (pure domain, no Spring) returns an `ErrorFingerprint` record with `throwingMethod` (line KEPT — first own-code frame, e.g. `LabelService.forwardingSourceFor:27`) and `stackTraceSequence` (all own-code frames, line numbers STRIPPED). Framework-frame fallback uses the topmost parseable frame when no `be.vdab.*` frame exists; distinct framework topmost frames → distinct fingerprints. Prefixes are constructor-injected (multi-prefix supported), never read from `LogguardProperties` inside the domain.
- **Line-number asymmetry (FR-6) is the keystone** — covered by two explicit tests: (a) a non-throwing own-code frame shifted by 85 lines → equal `stackTraceSequence` AND equal `hash()`; (b) the throwing frame's own line shifted (27→30) → equal `stackTraceSequence` but different `throwingMethod` and different `hash()`.
- **Hash (author decision, confirmed with user):** `ErrorFingerprint.hash()` = SHA-256 over `exceptionType\nthrowingMethod\nstackTraceSequence` (UTF-8), lowercase hex, first 8 chars. JDK-only (`MessageDigest` + `HexFormat`) — no new dependency. Deterministic; this is the lookup key for Stories 4.2/4.3 and the FR-18 output line (4.4).
- **Frame parsing reused from Story 3.2** (`LlmAdapter.ownCodeFrames`): FQCN parsed via regex (not substring), prefix test `fqcn.equals(p) || fqcn.startsWith(p + ".")`, `split("\\R")`, null/blank prefixes filtered. The regex was extended to also capture method + source location so the line number can be derived (`File.java:27` → 27; `Native Method`/`Unknown Source` → no `:line` suffix).
- **Never throws (AC #11):** null/blank `stackTrace` → `stackTraceSequence == ""` and `throwingMethod` falls back to the exception type (or `"unknown"` if that too is blank).
- **Scope held:** record + service + hermetic unit tests only. No `PollService`/persistence/suppression/output/escalation wiring, and `TerminalOutputAdapter` was left untouched (its throwing-class refinement is Story 4.4). No `pom.xml`/`application.yml` change (`logguard.own-code-package-prefixes: [be.vdab]` already present).

### File List

- `src/main/java/be/vdab/logguard/domain/model/ErrorFingerprint.java` (NEW) — domain record (3 components) + `hash()` (SHA-256 → first 8 hex). Zero Spring/JPA annotations.
- `src/main/java/be/vdab/logguard/domain/service/FingerprintService.java` (NEW) — pure-domain service; `compute(ErrorLog) → ErrorFingerprint`; frame regex (FQCN+method+location), own-code detection, framework fallback, line-number asymmetry.
- `src/test/java/be/vdab/logguard/domain/service/FingerprintServiceTest.java` (NEW) — 11 hermetic JUnit 5 tests covering normal path, both asymmetry directions, framework fallback (+ distinctness), multi-prefix, native frame, hash stability/format, null/blank/unknown fallbacks.

## Change Log

| Date | Change |
|---|---|
| 2026-06-29 | Story 4.1 drafted via create-story context engine (ErrorFingerprint record + FingerprintService; line-number asymmetry from allium spec; frame-parsing reuse from LlmAdapter 3.2; hash algorithm specified — SHA-256 truncated to 8 hex). Status → ready-for-dev. |
| 2026-06-29 | Story 4.1 implemented: `ErrorFingerprint` record (+ `hash()`) and pure-domain `FingerprintService` with own-code detection, framework-frame fallback, and the throwing_method/stack_trace_sequence line-number asymmetry. 11 hermetic tests added; full suite 41 pass / 1 skipped, no regressions. Status → review. |
