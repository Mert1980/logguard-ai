---
baseline_commit: 4ccf2991fe9b01a0d499ab4e9001f77511de8bc2
---

# Story 4.2: DeduplicationRecord Persistence, Three-State Gate, and LLM Analysis Caching

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a developer,
I want each unique error fingerprint to trigger LLM analysis only once per deduplication window with the result cached for reuse,
so that the local LLM is called at most once per unique bug per day regardless of how many times that bug occurs.

## Acceptance Criteria

1. **Given** an `ErrorLog` whose fingerprint has **no active** `DeduplicationRecord` (new error), **when** `PollService` processes it, **then** a new `DeduplicationRecord` is created with `occurrenceCount = 1`, `wontFix = false`, `expiresAt = now + deduplicationWindow`, and `lastNotifiedThreshold = null`.
2. The new error proceeds to `LlmPort.analyse()` and the resulting `LLMAnalysis` is stored in `DeduplicationRecord.storedAnalysis`; the error is delivered to terminal output.
3. **Given** an `ErrorLog` whose fingerprint has an **active** `DeduplicationRecord` with `wontFix = false` (cooling), **when** `PollService` processes it, **then** `occurrenceCount` is incremented, **no** LLM call is made, and **no** terminal output is produced (duplicate suppressed).
4. **Given** an `ErrorLog` whose fingerprint's `DeduplicationRecord` has **expired** (older than `deduplicationWindow`), **when** `PollService` processes it, **then** the expired record is treated as **absent** and a new `DeduplicationRecord` is created (error treated as new).
5. `V2__create_deduplication_record.sql` creates the `deduplication_record` table with explicit columns: `id`, `fingerprint_hash`, `exception_type`, `throwing_method`, `stack_trace_sequence`, `first_seen_at`, `expires_at`, `occurrence_count`, `last_notified_threshold`, `wont_fix`, `stored_analysis`.
6. `deduplicationWindow` is configurable via `logguard.deduplication-window` in `application.yml` (default 24h) — already present in `LogguardProperties`; read it, do not re-declare.
7. The domain `DeduplicationRecord` record in `domain/model/` has **zero JPA/Spring annotations**.
8. `DeduplicationRecordRepositoryAdapter` in `infrastructure/persistence/` implements the `DeduplicationRecordRepository` port.

### Additional contract (correctness — required for the story to work end-to-end)

9. `DeduplicationRecordRepository` (new port in `domain/port/out/`) declares exactly: `Optional<DeduplicationRecord> findActiveByFingerprint(ErrorFingerprint fingerprint)`, `void save(DeduplicationRecord record)`, `void deleteExpired(Instant before)`. `findActiveByFingerprint` returns a record **only if** `expires_at > now` (expired rows are invisible — realises AC #4 at the adapter boundary). `save` is an **upsert keyed by `fingerprint_hash`** (one row per fingerprint): update the existing row if present, else insert.
10. **NFR-4 (the point of the story):** within a single poll cycle, a fingerprint that occurs N times triggers **exactly one** `LlmPort.analyse()` call and **exactly one** per-error terminal block; the other N−1 occurrences only increment `occurrenceCount`. This holds for duplicates **within one batch** and **across cycles** (the cached `storedAnalysis` is reused — no fresh call).
11. The Story 2.6 degradation/recovery behaviour is **preserved unchanged**: outage → failure count + checkpoint stasis + sticky banner; recovery → recovery line + full backlog replay; healthy cycle → checkpoint advances and state clears. The dedup gate only changes how the **non-empty success path** turns errors into LLM calls + output.

## Tasks / Subtasks

- [x] **Task 1: `DeduplicationRecord` domain record** (AC: #1, #4, #7, #10) — pure domain, mirrors `PollCheckpoint`/`ErrorFingerprint`
  - [x] Create `be.vdab.logguard.domain.model.DeduplicationRecord` as a Java `record` with components: `ErrorFingerprint fingerprint`, `Instant firstSeenAt`, `Instant expiresAt`, `int occurrenceCount`, `Integer lastNotifiedThreshold` (nullable), `boolean wontFix`, `LLMAnalysis storedAnalysis` (nullable). **Zero JPA/Spring annotations.** Do NOT add a persistence `id` — identity is the fingerprint hash; the adapter resolves the row (keeps the domain clean, like `PollCheckpoint`).
  - [x] Add factory + transition helpers (immutable, return new instances): `static DeduplicationRecord createNew(ErrorFingerprint fp, Instant now, Duration window, boolean wontFix)` → `occurrenceCount=1`, `lastNotifiedThreshold=null`, `storedAnalysis=null`, `expiresAt=now.plus(window)`; `DeduplicationRecord incrementOccurrence()` → `occurrenceCount+1` (all else unchanged); `DeduplicationRecord withStoredAnalysis(LLMAnalysis analysis)`.
- [x] **Task 2: `V2__create_deduplication_record.sql` Flyway migration** (AC: #5) — read Dev Notes "Schema + Hibernate validate"
  - [x] Add `src/main/resources/db/migration/V2__create_deduplication_record.sql` with the exact columns of AC #5. `fingerprint_hash VARCHAR(64) NOT NULL UNIQUE`; `exception_type`/`throwing_method` `VARCHAR(512)`; `stack_trace_sequence` and `stored_analysis` `CLOB`; `first_seen_at`/`expires_at` `TIMESTAMP WITH TIME ZONE NOT NULL`; `occurrence_count INTEGER NOT NULL DEFAULT 1`; `last_notified_threshold INTEGER` (nullable); `wont_fix BOOLEAN NOT NULL DEFAULT FALSE`. Follow `V1__create_poll_checkpoint.sql` exactly for style (`BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY`).
  - [x] Add an index on `fingerprint_hash` (the lookup column). The UNIQUE constraint already creates one on most engines; an explicit `CREATE INDEX` is fine/redundant — keep it simple, the UNIQUE is sufficient.
- [x] **Task 3: `DeduplicationRecordEntity` JPA mapping** (AC: #5, #7-boundary) — mirror `PollCheckpointEntity` precisely
  - [x] Create `DeduplicationRecordEntity` in `infrastructure/persistence/` — `@Entity @Table(name = "deduplication_record")`, mutable class with protected no-arg ctor + getters/setters (NOT a record; JPA entities are classes here). Explicit `@Column(name="...")` on every field (AR-7).
  - [x] `@Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name="id") Long id`. `fingerprint_hash` → `@Column(name="fingerprint_hash", nullable=false, unique=true, length=64)`. `exception_type`/`throwing_method` → `length=512`. `stack_trace_sequence` and `stored_analysis` → `@Lob` (CLOB). `first_seen_at`/`expires_at` → `@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE) @Column(..., nullable=false)` exactly as `PollCheckpointEntity` does for `Instant`. `occurrence_count nullable=false`; `last_notified_threshold` nullable (`Integer`); `wont_fix nullable=false`.
  - [x] `stored_analysis` column holds the serialized `LLMAnalysis`: declare the field as `private LLMAnalysis storedAnalysis;` annotated `@Convert(converter = LlmAnalysisJsonConverter.class)` + `@Lob @Column(name="stored_analysis")` (see Task 4).
- [x] **Task 4: `LlmAnalysisJsonConverter` (stored_analysis serialization)** (AC: #2) — read Dev Notes "stored_analysis serialization"
  - [x] Create `LlmAnalysisJsonConverter implements AttributeConverter<LLMAnalysis, String>` in `infrastructure/persistence/`, annotated `@Converter`. JSON via a self-contained, `private static final` **Jackson 3** mapper: `tools.jackson.databind.json.JsonMapper.builder().build()` (Boot 4's primary Jackson — the one on the *compile* classpath; records bind natively). `convertToDatabaseColumn(null) → null`; `convertToEntityAttribute(null/blank) → null`. Jackson 3's `JacksonException` is unchecked, so no checked-exception handling is needed. Do **not** use Jackson 2 (`com.fasterxml.jackson.databind`) — its `jackson-databind` is `runtime`-scoped only and will not compile in `src/main`.
- [x] **Task 5: `DeduplicationRecordRepository` port + `DeduplicationRecordJpaRepository`** (AC: #8, #9)
  - [x] Create `be.vdab.logguard.domain.port.out.DeduplicationRecordRepository` with the three methods of AC #9. No annotations.
  - [x] Create `DeduplicationRecordJpaRepository extends JpaRepository<DeduplicationRecordEntity, Long>` in `infrastructure/persistence/` with `Optional<DeduplicationRecordEntity> findByFingerprintHash(String fingerprintHash)` and `long deleteByExpiresAtBefore(Instant ts)`.
- [x] **Task 6: `DeduplicationRecordRepositoryAdapter`** (AC: #8, #9) — mirror `PollCheckpointRepositoryAdapter`’s find-or-create upsert
  - [x] `@Component` implementing the port. `findActiveByFingerprint(fp)` → `jpa.findByFingerprintHash(fp.hash()).filter(e -> e.getExpiresAt().isAfter(Instant.now())).map(this::toDomain)`. `save(record)` → `findByFingerprintHash(record.fingerprint().hash())` → existing entity or `new`; copy ALL fields from the domain record (including `fingerprint_hash = fp.hash()`, the three fingerprint columns, counts, threshold, wontFix, storedAnalysis); `jpa.save(entity)`. `deleteExpired(before)` → `jpa.deleteByExpiresAtBefore(before)`.
  - [x] `toDomain(entity)` rebuilds `new ErrorFingerprint(exceptionType, throwingMethod, stackTraceSequence)` and the `DeduplicationRecord`. (The fingerprint columns are denormalised copies; `fingerprint_hash` stays the lookup key.)
- [x] **Task 7: Wire `FingerprintService` as a bean + extend `PollService` construction** (AC: #1, #11) — read Dev Notes "Wiring"
  - [x] In `DomainServiceConfig`: add `@Bean FingerprintService fingerprintService(LogguardProperties p)` → `new FingerprintService(p.ownCodePackagePrefixes())` (Story 4.1 left it unwired by design — it is needed now). Extend the `pollService(...)` bean to also inject `FingerprintService` + `DeduplicationRecordRepository` and pass `p.deduplicationWindow()`.
  - [x] Extend the `PollService` constructor to accept `FingerprintService fingerprintService`, `DeduplicationRecordRepository dedupRepository`, and `Duration deduplicationWindow` (in addition to the existing params). Keep it Spring-free.
- [x] **Task 8: Three-state dedup gate in `PollService` (two-phase)** (AC: #1, #2, #3, #4, #10, #11) — read Dev Notes "Two-phase gate algorithm" — this is the heart of the story
  - [x] Replace the current "group all errors → printServiceGroup (analyse + print every error)" block with the two-phase algorithm: **Phase 1 (gate, batch order):** for each error compute the fingerprint; `findActiveByFingerprint` → if present, `save(record.incrementOccurrence())` (silent, no LLM, no output); if absent (NEW), `createNew(fp, now, window, wontFix=false)`, `save`, and register `(error, fingerprint)` for phase 2 (only the first occurrence of each fingerprint reaches this branch). **Phase 2 (analyse + output):** group the registered NEW errors by service, order services by NEW-count descending; for each service `printProgress(service, newCount)` then for each new error `analyse`, re-fetch via `findActiveByFingerprint(fp)` and `save(current.withStoredAnalysis(analysis))`, then `printAnalysis(...)`.
  - [x] Preserve everything else in `poll()` verbatim: empty-checkpoint skip, suppression `loadHashes()` ordering, `pollStart`/`findErrorsSince`, `handlePollFailure` on exception, the recovery-line print when `degradationStartedAt != null`, the refresh-lag checkpoint advance + clamp, and the `save(new PollCheckpoint(advanced, null, 0))` clear. If there are zero NEW errors (all duplicates), print nothing (no progress, no header) — consistent with FR-26.
  - [x] **Suppression is NOT integrated yet:** always create with `wontFix=false` in this story. The won't-fix-from-birth branch, the WontFix label, and the per-error Fingerprint output line are **Story 4.4**. Escalation-threshold checks are **Story 4.5** (persist `lastNotifiedThreshold=null`; do not check thresholds here). Keep the gate shaped so 4.4/4.5 slot in cleanly.
- [x] **Task 9: Update `PollServiceTest` (regression) + new gate unit tests** (AC: #1, #3, #4, #10, #11) — read Dev Notes "PollServiceTest regression"
  - [x] Update the existing 7 tests to the new `PollService` constructor: pass a real `new FingerprintService(List.of("be.vdab"))`, a new `FakeDeduplicationRepository`, and `Duration.ofHours(24)`. Make `FakeLlm` and `RecordingTerminal` **count** calls (FakeLlm already returns `unavailable`; add an `analyseCalls` counter).
  - [x] **Fix `recoveryPrintsBacklogAndClearsState`:** its two backlog errors are currently identical (same fingerprint → would now collapse to ONE analysis). Give them **distinct stack traces** (distinct own-code throwing frames → distinct fingerprints) so both are NEW and `analysisCalls == 2` still holds. (Today `errorIn(...)` passes the bare exception-type string as the stack trace — degenerate, all-equal fingerprints. Add a stack-trace param or a second helper.)
  - [x] Add gate tests with a `FakeDeduplicationRepository` (a `Map<String, DeduplicationRecord>` keyed by `fingerprint.hash()`; `findActiveByFingerprint` returns `Optional.ofNullable(map.get(fp.hash()))`; `save` upserts): **NEW** → record created (count=1, wontFix=false, expiresAt≈now+24h, storedAnalysis set), 1 LLM call, 1 `printAnalysis`. **COOLING** (pre-seed an active record) → count incremented, 0 LLM, 0 `printAnalysis`. **Duplicate within one batch** (3 identical errors) → 1 LLM call, 1 `printAnalysis`, stored count = 3 (NFR-4). **Two distinct fingerprints, one a dup** → 2 LLM calls. **All-duplicates cycle** → 0 progress, 0 analysis, checkpoint still advances.
- [x] **Task 10: `DeduplicationRecordRepositoryAdapterTest` (persistence integration)** (AC: #4, #5, #8, #9) — mirror `PollCheckpointRepositoryAdapterTest` (`@SpringBootTest @Transactional`, `deleteAll()` first)
  - [x] Round-trip save→`findActiveByFingerprint` returns the record with all fields incl. a non-null `storedAnalysis` (verifies the JSON converter + `@Lob` columns + `TIMESTAMP WITH TIME ZONE` mapping validate against the V2 schema — `ddl-auto: validate` must pass at context startup).
  - [x] **Expiry (AC #4):** a record with `expiresAt` in the past is **not** returned by `findActiveByFingerprint` (treated as absent). **Upsert (AC #9):** two `save`s for the same fingerprint hash keep `count == 1` row and reflect the latest state. **deleteExpired:** removes only past-expiry rows.

## Dev Notes

### Scope boundary (read first) — what is and is NOT in this story
- **IN:** `DeduplicationRecord` (domain) + `DeduplicationRecordEntity`/JPA repo/adapter + V2 migration + JSON converter for `storedAnalysis`; the `DeduplicationRecordRepository` port; wiring `FingerprintService` as a bean; the **two-phase NEW vs ACTIVE gate** in `PollService` with LLM-analysis caching (NFR-4).
- **NOT IN (later stories) — do not implement, but shape the gate so they slot in:**
  - **Story 4.3** — real suppression-file parsing/hot-reload + "unreadable → last known state" warning. Here `suppressionFilePort.loadHashes()` is still called for ordering (FR-14) and its result is **not** consulted by the gate yet.
  - **Story 4.4** — won't-fix-from-birth (create with `wontFix=true` when the hash is suppressed), the `⚑ Known / Won't Fix` label, the WON'T-FIX re-encounter label, **and the per-error `Fingerprint:` output line (FR-18)**. So this story does **not** change `TerminalOutputPort.printAnalysis` and does **not** add the Fingerprint line. Always `wontFix=false`.
  - **Story 4.5** — escalation threshold re-notifications + won't-fix 1,000× override. So this story does **not** read `escalationThresholds` and does **not** check/advance `lastNotifiedThreshold` (persist it as `null`).
- The deferred-work item *"Duplicate analysis on crash/failure after partial print"* is **resolved** by this story: dedup record writes + checkpoint advance share the one `@Transactional` poll cycle, so a mid-cycle failure rolls back both and the re-fetched batch is re-deduplicated cleanly. The other deferred item *"`throwingClass` can show a framework/JDK frame"* stays open until 4.4.

### Two-phase gate algorithm (the heart — implement exactly)
Why two phases: FR-27's progress line (`Analyzing N new errors in [service]…`) must print the count of errors that will **actually** be analysed/displayed, and a service whose errors are all duplicates must produce **no** output (AC #3 + FR-26). The NEW-vs-ACTIVE decision needs only a repository lookup (no LLM), so resolve all gate decisions first, then analyse.

```
poll():
  ... (unchanged: empty-checkpoint skip; suppressionFilePort.loadHashes();
       pollStart = now; try findErrorsSince catch -> handlePollFailure,return;
       if degradationStartedAt != null -> terminalOutput.printRecovery(now))

  // ---- Phase 1: gate (batch order) ----
  Instant now = Instant.now();
  // preserve first-seen order of NEW fingerprints, dedup within the batch:
  LinkedHashMap<String, ErrorLog> newByHash = new LinkedHashMap<>();   // hash -> first ErrorLog
  Map<String, ErrorFingerprint> fpByHash = new HashMap<>();
  for (ErrorLog error : errors) {
      ErrorFingerprint fp = fingerprintService.compute(error);
      Optional<DeduplicationRecord> active = dedupRepository.findActiveByFingerprint(fp);
      if (active.isPresent()) {
          dedupRepository.save(active.get().incrementOccurrence());     // COOLING/active: silent
      } else if (!newByHash.containsKey(fp.hash())) {                   // NEW (first in batch)
          dedupRepository.save(DeduplicationRecord.createNew(fp, now, deduplicationWindow, false));
          newByHash.put(fp.hash(), error);
          fpByHash.put(fp.hash(), fp);
      } else {                                                          // NEW seen earlier THIS batch
          dedupRepository.findActiveByFingerprint(fp)
              .ifPresent(r -> dedupRepository.save(r.incrementOccurrence()));
      }
  }

  // ---- Phase 2: analyse + output (only NEW) ----
  if (!newByHash.isEmpty()) {
      Map<String,List<ErrorLog>> byService = newByHash.values().stream()
          .collect(groupingBy(serviceOrUnknown, LinkedHashMap::new, toList()));
      byService.entrySet().stream()
          .sorted(comparingInt((Map.Entry<String,List<ErrorLog>> e)->e.getValue().size()).reversed())
          .forEach(e -> {
              terminalOutput.printProgress(e.getKey(), e.getValue().size());
              int i = 1, n = e.getValue().size();
              for (ErrorLog err : e.getValue()) {
                  ErrorFingerprint fp = fingerprintService.compute(err);
                  LLMAnalysis analysis = llmPort.analyse(err);
                  dedupRepository.findActiveByFingerprint(fp)
                      .ifPresent(r -> dedupRepository.save(r.withStoredAnalysis(analysis)));
                  terminalOutput.printAnalysis(i++, n, err, analysis);
              }
          });
  }
  ... (unchanged: advanced = pollStart.minus(refreshWindow); clamp; save(new PollCheckpoint(advanced,null,0)))
```
Critical details:
- **Save the NEW record in Phase 1 BEFORE Phase 2 analysis** — so a fingerprint appearing twice in the same batch is found active on its 2nd occurrence and increments instead of double-analysing (NFR-4 within a batch). Hibernate auto-flushes before the `findByFingerprintHash` query, so the just-saved row is visible inside the same transaction.
- **Phase 2 re-fetches before caching the analysis** (`findActiveByFingerprint(fp)` then `withStoredAnalysis`) so it writes onto the *current* `occurrenceCount` (which Phase 1 may have incremented past 1) instead of clobbering it back to 1. Do not carry the Phase-1 record object into Phase 2.
- `serviceOrUnknown` = `error.serviceName() != null ? error.serviceName() : "unknown"` (keep the existing helper logic). Order services by **NEW** count descending (FR-29 over deduplicated counts).
- All-duplicates cycle → `newByHash` empty → no progress/header/blocks (FR-26), checkpoint still advances (a successful cycle).

### `PollServiceTest` regression (MUST handle — do not skip)
The existing suite constructs `PollService` with the **7-arg** constructor and uses fakes. Two required changes:
1. **Constructor:** every `new PollService(...)` must pass the 3 new args. Use a **real** `new FingerprintService(List.of("be.vdab"))` (it is pure), a `FakeDeduplicationRepository`, and `Duration.ofHours(24)`.
2. **`recoveryPrintsBacklogAndClearsState` will break as written.** It seeds two backlog errors via `errorIn("orgbeheer-service")`, whose `stackTrace` is the bare string `"java.lang.NullPointerException"` (no frames) → both fingerprints are identical → with dedup they collapse to **one** analysis, failing `assertEquals(2, terminal.analysisCalls)`. Fix by giving the two errors **distinct own-code stack traces** (so distinct fingerprints, both NEW, both analysed). Add an overload, e.g. `errorIn(service, "\tat be.vdab.app.A.foo(A.java:1)")` and `errorIn(service, "\tat be.vdab.app.B.bar(B.java:2)")`, and keep the `analysisCalls == 2` assertion. The other 6 tests pass unchanged once the constructor is updated (empty/zero-error cycles and failure cycles don't reach the gate, or reach it with no NEW errors).

`FakeDeduplicationRepository` shape (mirror the other in-file fakes — no mocking framework):
```java
private static final class FakeDeduplicationRepository implements DeduplicationRecordRepository {
    final Map<String, DeduplicationRecord> byHash = new HashMap<>();
    public Optional<DeduplicationRecord> findActiveByFingerprint(ErrorFingerprint fp) {
        return Optional.ofNullable(byHash.get(fp.hash()));   // expiry filtering is the adapter's job
    }
    public void save(DeduplicationRecord r) { byHash.put(r.fingerprint().hash(), r); }
    public void deleteExpired(Instant before) { byHash.values().removeIf(r -> !r.expiresAt().isAfter(before)); }
}
```

### Schema + Hibernate `validate` (the silent-failure trap)
`spring.jpa.hibernate.ddl-auto: validate` (architecture) means the entity mapping must match the Flyway DDL **at context startup**, or every `@SpringBootTest` fails to boot. Mirror `PollCheckpointEntity` exactly:
- `Instant` columns → `@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)` + DDL `TIMESTAMP WITH TIME ZONE`. (Without the `@JdbcTypeCode`, Hibernate expects `TIMESTAMP` and validate fails.)
- CLOB columns (`stack_trace_sequence`, `stored_analysis`) → `@Lob`. `VARCHAR` columns → matching `length` on `@Column`.
- `BIGINT GENERATED BY DEFAULT AS IDENTITY` ↔ `@GeneratedValue(strategy = IDENTITY)`.
- H2 is **file-based** in the app (`jdbc:h2:file:./data/logguard`) but the existing test slice uses in-memory H2 with Flyway (see the test log: `jdbc:h2:mem:logguard-test`). Just follow `PollCheckpointRepositoryAdapterTest` — it already boots Flyway+H2 correctly.

### stored_analysis serialization — use Boot's Jackson 3 (verified against the dependency tree)
The allium spec models `stored_analysis: LLMAnalysis?` and the architecture only says "use `@Convert` + a custom `AttributeConverter` or a Hibernate JSON type". Decision: a JPA `AttributeConverter<LLMAnalysis,String>` (`LlmAnalysisJsonConverter`) serializing to a JSON string in a CLOB column, using **Jackson 3** — Boot 4's primary mapper.

**Why Jackson 3, not Jackson 2 (classpath-verified, `mvn dependency:tree`):**
- `tools.jackson.core:jackson-databind:3.1.4` — **compile** scope (via `spring-boot-starter-jackson`). Available to `src/main`.
- `com.fasterxml.jackson.core:jackson-databind:2.21.4` — **runtime** scope only (transitive via `opensearch-java`). NOT on the main compile classpath, so a `src/main` converter importing `com.fasterxml.jackson.databind.ObjectMapper` would fail to compile. (The only Jackson-2 `ObjectMapper` import in the repo is in *test* code, where runtime deps are visible.)

Implementation: `private static final JsonMapper MAPPER = tools.jackson.databind.json.JsonMapper.builder().build();`. `LLMAnalysis` is a record → binds natively. Jackson 3's `JacksonException` is **unchecked**, so `writeValueAsString`/`readValue` need no try/catch to compile. Null/blank ⇄ null both directions.

### Persistence conventions (architecture — enforced in review)
- Domain `DeduplicationRecord` = Java record in `domain/model/`, **zero** JPA annotations. JPA `DeduplicationRecordEntity` = mutable class in `infrastructure/persistence/`. Adapter translates between them (mirror `PollCheckpointRepositoryAdapter`).
- Explicit `@Table(name=...)` / `@Column(name=...)` everywhere (AR-7); table name `snake_case` singular (`deduplication_record`); columns `snake_case`. Flyway only — no auto-DDL.
- `findActiveByFingerprint` filters expiry in the adapter (`expiresAt.isAfter(Instant.now())`) — the domain gate just treats `Optional.empty()` as NEW. This is why AC #4 (expired→new) is verified in the **adapter** test, not the `PollService` test.

### Authoritative contracts (from spec/architecture)
- **Domain `DeduplicationRecord`** [allium 154–162]: `fingerprint: ErrorFingerprint`, `first_seen_at`, `expires_at`, `occurrence_count` (total incl. suppressed dups), `last_notified_threshold: Integer?`, `won't_fix: Boolean`, `stored_analysis: LLMAnalysis?`.
- **Three-state gate** [allium 292–355]: NEW = no record with `expires_at > now` → create (`count=1`, `expires_at=now+window`, `wont_fix=is_suppressed`, `stored_analysis=null`); COOLING = active & `won't_fix=false` → `count++`; WON'T-FIX = active & `won't_fix=true` → `count++` silent. (This story: `is_suppressed` is always treated false → only NEW + active-increment paths are live; the suppressed/won't-fix branches arrive in 4.4.)
- **Repository port** [architecture 535–538]: `Optional<DeduplicationRecord> findActiveByFingerprint(ErrorFingerprint)`, `void save(DeduplicationRecord)`, `void deleteExpired(Instant before)`.
- **Caching** [allium 419–421]: after analysis, `record.stored_analysis = analysis`; reused at escalation (4.5) with no fresh call.
- **`LLMAnalysis`** [domain/model/LLMAnalysis.java]: `record(boolean llmAvailable, String rootCause, String likelyLocation, String suggestedAction, String unavailabilityReason)` with `available(...)`/`unavailable(reason)` factories.
- **Window** [LogguardProperties]: `@DefaultValue("24h") Duration deduplicationWindow` (key `logguard.deduplication-window`) — already bound; just read it.

### File-mapping (entity ↔ columns)
| Domain `DeduplicationRecord` | Entity column | DDL type |
|---|---|---|
| `fingerprint.hash()` | `fingerprint_hash` | `VARCHAR(64) NOT NULL UNIQUE` (lookup key) |
| `fingerprint.exceptionType()` | `exception_type` | `VARCHAR(512)` |
| `fingerprint.throwingMethod()` | `throwing_method` | `VARCHAR(512)` |
| `fingerprint.stackTraceSequence()` | `stack_trace_sequence` | `CLOB` (`@Lob`) |
| `firstSeenAt` | `first_seen_at` | `TIMESTAMP WITH TIME ZONE NOT NULL` |
| `expiresAt` | `expires_at` | `TIMESTAMP WITH TIME ZONE NOT NULL` |
| `occurrenceCount` | `occurrence_count` | `INTEGER NOT NULL DEFAULT 1` |
| `lastNotifiedThreshold` | `last_notified_threshold` | `INTEGER` (nullable) |
| `wontFix` | `wont_fix` | `BOOLEAN NOT NULL DEFAULT FALSE` |
| `storedAnalysis` | `stored_analysis` | `CLOB` (`@Lob`, JSON via converter, nullable) |

### Wiring
`DomainServiceConfig.pollService(...)` currently injects `OpenSearchPort, PollCheckpointRepository, TerminalOutputPort, LlmPort, SuppressionFilePort, LogguardProperties`. Add `FingerprintService` (new `@Bean` in the same config — `new FingerprintService(p.ownCodePackagePrefixes())`) and `DeduplicationRecordRepository` (the `@Component` adapter, auto-injected), and pass `p.deduplicationWindow()` into the new `PollService` constructor. `TransactionalPollUseCase` is unchanged (still wraps `PollService.poll()` in the one `@Transactional`). The dedup writes therefore commit/rollback atomically with the checkpoint advance.

### Testing standards
- Domain/gate tests: pure JUnit 5, hand-written fakes, no Spring (mirror `PollServiceTest`/`TenantDataSanitizerTest`). Static `org.junit.jupiter.api.Assertions.*`.
- Adapter test: `@SpringBootTest @Transactional`, `@Autowired` the port + JPA repo, `deleteAll()` first (mirror `PollCheckpointRepositoryAdapterTest`). This is the test that proves the V2 schema + entity mapping validate and that expiry filtering works.
- Build/run via Bash with JDK 21 (`source "$HOME/.sdkman/bin/sdkman-init.sh"`; native `mvn -B -ntp test`; the `mvnw.cmd` wrapper is blocked on this host). Keep `mvn test` hermetic — no Docker/Ollama/OpenSearch (the H2 slice is in-memory).

### Project Structure Notes
- NEW: `domain/model/DeduplicationRecord.java`; `domain/port/out/DeduplicationRecordRepository.java`; `infrastructure/persistence/{DeduplicationRecordEntity,DeduplicationRecordJpaRepository,DeduplicationRecordRepositoryAdapter,LlmAnalysisJsonConverter}.java`; `src/main/resources/db/migration/V2__create_deduplication_record.sql`; `src/test/java/.../persistence/DeduplicationRecordRepositoryAdapterTest.java`.
- UPDATE: `domain/service/PollService.java` (constructor + two-phase gate); `infrastructure/config/DomainServiceConfig.java` (FingerprintService bean + new pollService params); `src/test/java/.../service/PollServiceTest.java` (constructor + the recovery-test fingerprint fix + new gate tests).
- No `application.yml` change (`logguard.deduplication-window: 24h` already defaulted via `LogguardProperties`). No `pom.xml` change (JPA, H2, Flyway, Jackson 2 all already on the classpath).

### References
- [Source: epics.md#Story 4.2] — acceptance criteria
- [Source: epics.md#FR-8, #FR-9, #FR-10, #FR-25; #NFR-4] — three-state gate, dedup window, occurrence tracking, analysis caching, LLM throughput bound
- [Source: specs/logguard-ai.allium lines 154–162] — DeduplicationRecord entity contract
- [Source: specs/logguard-ai.allium lines 292–355] — ProcessNewError / ProcessCoolingError / ProcessWontFixError rules
- [Source: specs/logguard-ai.allium lines 419–421] — stored_analysis caching (AnalyseError)
- [Source: architecture.md lines 535–538] — DeduplicationRecordRepository port contract
- [Source: architecture.md lines 277–293] — domain-record vs JPA-entity split; explicit @Table/@Column; snake_case; Flyway; validate
- [Source: architecture.md lines 137–156, 445, 468–473] — package placement for model/port/persistence
- [Source: src/main/java/.../persistence/PollCheckpoint{Entity,RepositoryAdapter,JpaRepository}.java; V1 migration] — the persistence pattern to mirror
- [Source: src/main/java/.../service/PollService.java] — the poll cycle being modified
- [Source: src/test/java/.../service/PollServiceTest.java; .../persistence/PollCheckpointRepositoryAdapterTest.java] — test harness patterns

### Previous Story Intelligence (Stories 4.1, 3.2, 2.6, 2.x)
- **Story 4.1 (REUSE):** `FingerprintService(List<String> prefixes).compute(ErrorLog) → ErrorFingerprint`; `ErrorFingerprint.hash()` = SHA-256 → first 8 lowercase hex chars — this is the `fingerprint_hash` lookup key. The service was deliberately left **unwired**; this story adds the bean.
- **Story 2.6 (PRESERVE):** `PollService.poll()` handles degradation/recovery; `handlePollFailure` + recovery line + checkpoint stasis/clear. The gate must not disturb any of it (AC #11). Existing 7 `PollServiceTest` cases guard this — keep them green.
- **Story 2.3/2.1 (PERSISTENCE):** `PollCheckpointEntity` shows the exact `@JdbcTypeCode(TIMESTAMP_WITH_TIMEZONE)` + explicit `@Column` pattern and the find-or-create upsert in `PollCheckpointRepositoryAdapter`. Note `opensearch-java`'s Jackson 2 (`com.fasterxml`) is **runtime**-scoped — the converter must use Boot's compile-scoped **Jackson 3** (`tools.jackson`), not Jackson 2.
- **Toolchain:** SDKMAN Java 21.0.11 / Maven 3.9.16; Bash; `mvn -B -ntp test`. Baseline before this story: 41 pass / 1 skipped.

### Git Intelligence
- Baseline HEAD `4ccf299` "Implement ErrorFingerprint computation and FingerprintService (Story 4.1)". `domain/model/ErrorFingerprint.java` + `domain/service/FingerprintService.java` are committed and stable — safe to depend on.
- Mirror committed patterns: `V1__…sql` (migration style), `PollCheckpointEntity`/`…Adapter`/`…JpaRepository` (persistence triad), `PollCheckpointRepositoryAdapterTest` (`@SpringBootTest @Transactional` slice).

### Latest Technical Information
- **Spring Boot 4.1 / Hibernate ORM 7.4** (from the test log) with `ddl-auto: validate` + Flyway. `@JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)` is the verified way to align `Instant` ↔ `TIMESTAMP WITH TIME ZONE` (already used in `PollCheckpointEntity`). Spring Data derived `deleteBy…` needs a surrounding transaction (the `@SpringBootTest @Transactional` slice provides it; in production `deleteExpired` would be called from a transactional context — not wired to a scheduler in this story).
- **Jackson coexistence (verified):** Boot 4 ships Jackson 3 (`tools.jackson.databind:3.1.4`, **compile**); `opensearch-java` drags in Jackson 2 (`com.fasterxml...databind:2.21.4`, **runtime** only). The converter must use **Jackson 3** (`tools.jackson.databind.json.JsonMapper`) — Jackson 2 databind isn't compilable from `src/main`. Jackson 3 exceptions are unchecked (`JacksonException`).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Opus 4.8, 1M context)

### Debug Log References

- Native build via SDKMAN (Java 21.0.11 / Maven 3.9.16); `mvn -B -ntp test`.
- Classpath check (`mvn dependency:tree`) settled the serialization choice: `tools.jackson...databind:3.1.4` is **compile**-scoped (Boot's primary), while `com.fasterxml...databind:2.21.4` is **runtime**-only — so the converter uses Jackson 3 (`tools.jackson.databind.json.JsonMapper`); Jackson 2 databind would not compile in `src/main`.
- First run: 50 tests, 1 failure + 1 error — both in `DeduplicationRecordRepositoryAdapterTest` because two cases seeded records with a fixed `NOW=2026-06-15` whose `expiresAt` was already past the real system clock (2026-06-29), so `findActiveByFingerprint` (filters on `Instant.now()`) correctly treated them as expired. Fixed the tests to seed relative to `Instant.now()`; the adapter was correct.
- Final: `mvn -B -ntp test` → **BUILD SUCCESS, Tests run: 50, Failures: 0, Errors: 0, Skipped: 1** (+9 over 4.1's 41; the skip is the pre-existing `-Dopensearch.live` gate). Story 2.6 regression suite still green.

### Completion Notes List

- **All ACs satisfied.** Three-state gate in `PollService` via a two-phase algorithm: **Phase 1** fingerprints every error and updates its `DeduplicationRecord` — active (cooling) → `incrementOccurrence()` silently; absent → `createNew(count=1, wontFix=false, expiresAt=now+window)` and register for analysis. **Phase 2** groups the NEW errors by service (NEW-count desc), prints the progress line, calls the LLM once each, re-fetches and caches `storedAnalysis`, and prints the block.
- **NFR-4 holds within a batch and across cycles:** a fingerprint occurring N times in one batch → 1 LLM call + 1 block + count=N (the NEW record is saved in Phase 1 before Phase 2, so later same-batch occurrences are found active and increment). Across cycles the cached `storedAnalysis` means no fresh call. Verified by `duplicatesWithinOneBatchAnalysedOnce`, `twoDistinctFingerprintsOneDuplicateAnalysedTwice`, `coolingDuplicateIncrementsSilentlyWithoutLlmOrOutput`.
- **Expiry (FR-9) lives in the adapter:** `findActiveByFingerprint` returns a row only when `expiresAt > Instant.now()`, so an expired fingerprint is treated as new — verified by `expiredRecordIsNotReturnedAsActive`. `save` upserts keyed by `fingerprint_hash` (one row per fingerprint), so an expired→new transition updates in place (no UNIQUE violation).
- **stored_analysis** persists as JSON in a CLOB via `LlmAnalysisJsonConverter` (Jackson 3, self-contained `JsonMapper`); round-trips with all fields intact (`savesThenFindsActiveRoundTripIncludingStoredAnalysis`). The `@SpringBootTest` adapter test booting at all proves the V2 schema + entity mapping validate under `ddl-auto: validate`.
- **Story 2.6 preserved:** degradation/recovery/checkpoint logic untouched; the gate only restructures the non-empty success path. `recoveryPrintsBannerReplaysBacklogAndClearsState` updated to use two distinct-fingerprint backlog errors so the "full backlog replayed" intent (2 analyses) survives dedup.
- **FingerprintService wired:** new `@Bean` in `DomainServiceConfig` (`new FingerprintService(props.ownCodePackagePrefixes())`), injected into `PollService` alongside the dedup repository and `deduplicationWindow`. Dedup writes share the existing single `@Transactional` poll cycle, so a mid-cycle failure rolls back records + checkpoint together (resolves the deferred "duplicate analysis on crash" item).
- **Scope held:** suppression result not consulted (records always `wontFix=false`) — Story 4.3/4.4; no Fingerprint output line / WontFix label — Story 4.4; no escalation / threshold checks (`lastNotifiedThreshold` persisted as `null`) — Story 4.5. `TerminalOutputPort` unchanged. No `pom.xml`/`application.yml` change.

### File List

- `src/main/java/be/vdab/logguard/domain/model/DeduplicationRecord.java` (NEW) — pure domain record + `createNew`/`incrementOccurrence`/`withStoredAnalysis`.
- `src/main/java/be/vdab/logguard/domain/port/out/DeduplicationRecordRepository.java` (NEW) — outbound port (findActiveByFingerprint/save/deleteExpired).
- `src/main/resources/db/migration/V2__create_deduplication_record.sql` (NEW) — table with the AC #5 columns.
- `src/main/java/be/vdab/logguard/infrastructure/persistence/DeduplicationRecordEntity.java` (NEW) — JPA entity (explicit @Column, TIMESTAMP WITH TIME ZONE, @Lob CLOBs, JSON-converted stored_analysis).
- `src/main/java/be/vdab/logguard/infrastructure/persistence/DeduplicationRecordJpaRepository.java` (NEW) — Spring Data repo (findByFingerprintHash, deleteByExpiresAtBefore).
- `src/main/java/be/vdab/logguard/infrastructure/persistence/DeduplicationRecordRepositoryAdapter.java` (NEW) — implements the port; upsert + expiry filter + entity↔domain translation.
- `src/main/java/be/vdab/logguard/infrastructure/persistence/LlmAnalysisJsonConverter.java` (NEW) — `AttributeConverter<LLMAnalysis,String>` (Jackson 3 JSON).
- `src/main/java/be/vdab/logguard/domain/service/PollService.java` (UPDATE) — constructor + two-phase dedup gate (`gateAndCollectNew` / `analyseServiceGroup`).
- `src/main/java/be/vdab/logguard/infrastructure/config/DomainServiceConfig.java` (UPDATE) — FingerprintService @Bean + extended pollService wiring.
- `src/test/java/be/vdab/logguard/domain/service/PollServiceTest.java` (UPDATE) — new constructor, counting fakes, FakeDeduplicationRepository, recovery-test fingerprint fix, 5 new gate tests.
- `src/test/java/be/vdab/logguard/infrastructure/persistence/DeduplicationRecordRepositoryAdapterTest.java` (NEW) — `@SpringBootTest @Transactional` round-trip + expiry + upsert + deleteExpired.

## Change Log

| Date | Change |
|---|---|
| 2026-06-29 | Story 4.2 drafted via create-story context engine (DeduplicationRecord + entity/adapter/JSON converter + V2 migration; two-phase NEW/ACTIVE gate in PollService with LLM caching; FingerprintService wiring; PollServiceTest regression flagged). Status → ready-for-dev. |
| 2026-06-29 | Serialization decision corrected to Boot's Jackson 3 after `dependency:tree` showed Jackson 2 databind is runtime-scoped only (not compilable from `src/main`). |
| 2026-06-29 | Story 4.2 implemented: DeduplicationRecord + persistence triad + V2 migration + JSON converter; two-phase three-state dedup gate in PollService with LLM-analysis caching (NFR-4); FingerprintService wired. 9 new tests; full suite 50 pass / 1 skipped, Story 2.6 regression green. Status → review. |
| 2026-06-29 | Code review (Sonnet 4.6, 3 adversarial lenses): 4 findings addressed in code (failed-analysis not cached; Phase-2 cache-miss logs; converter read hardened; across-cycle NFR-4 + failed-analysis tests). 6 items deferred (see deferred-work.md). Suite 52 pass / 1 skipped. |

## Senior Developer Review (AI)

**Reviewer:** Sonnet 4.6 (three parallel adversarial lenses: Blind Hunter, Edge Case Hunter, Acceptance Auditor), triaged by Opus 4.8.
**Date:** 2026-06-29
**Outcome:** Approve (with fixes applied). ACs #1–#9, #11 fully met; AC #10 (NFR-4) was code-correct but its across-cycle half was untested — now tested. All applied fixes are committed in the working tree; suite 52 pass / 1 skipped.

### Action Items

- [x] **[High] Do not cache a failed `LLMAnalysis`** (`PollService.analyseServiceGroup`) — a transient Ollama outage cached `llmAvailable=false`, poisoning the dedup window so escalation (4.5) would replay the failure. Now guarded by `if (analysis.llmAvailable())`; failed analysis still displayed (FR-24), `storedAnalysis` left null. Covered by `failedLlmAnalysisIsNotCached`. *(Blind F6 + Edge F2)*
- [x] **[Med] Phase-2 cache-miss is no longer silent** (`PollService.analyseServiceGroup`) — the `ifPresent` no-op now logs a WARN if the record created in Phase 1 is unexpectedly absent when caching its analysis. *(Blind F1 + Edge F4)*
- [x] **[Med] Harden the JSON converter read** (`LlmAnalysisJsonConverter.convertToEntityAttribute`) — corrupt/legacy `stored_analysis` JSON previously threw an unchecked `JacksonException` through the JPA layer, aborting every cycle touching that fingerprint. Now caught → WARN → returns null (treated as no cached analysis). *(Blind F8 + Edge F11)*
- [x] **[Med] Test across-cycle NFR-4** (`PollServiceTest.sameFingerprintAcrossCyclesAnalysedOnlyOnce`) — the story's "no fresh LLM call across cycles" claim now has a test (two `poll()` calls, same fingerprint → 1 LLM call, count 2). *(Acceptance GAP 1)*

### Deferred (logged in deferred-work.md, not blocking)

- [ ] **[Low] 32-bit (8-hex) fingerprint hash collision** — approved Story 4.1 design; negligible at LogGuard's active-table scale.
- [ ] **[Low] Concurrent-cycle UNIQUE race** — mitigated by single-threaded `@Scheduled` fixedDelay; revisit if a multi-threaded scheduler is introduced.
- [ ] **[Low] `deleteExpired` transaction + scheduler wiring** — housekeeping out of 4.2 scope; make transactional when a cleanup job is added.
- [ ] **[Low] `occurrence_count` int→long** — wraps at ~2.1e9; implausible volume, free insurance for 4.5.
- [ ] **[Low] `expires_at == now` boundary ghost row** — closed-open interval is intentional; harmless.
- [ ] **[Low] null `service.name` vs literal `"unknown"` conflation** — cosmetic terminal grouping only.
