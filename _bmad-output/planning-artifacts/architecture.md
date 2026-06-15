---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8]
lastStep: 8
status: 'complete'
completedAt: '2026-06-15'
inputDocuments:
  - _bmad-output/planning-artifacts/prds/prd-logguard-ai-2026-06-15/prd.md
  - _bmad-output/planning-artifacts/prds/prd-logguard-ai-2026-06-15/addendum.md
  - specs/logguard-ai.allium
  - _bmad-output/brainstorming/brainstorming-session-2026-06-08-1400.md
workflowType: 'architecture'
project_name: 'logguard-ai'
user_name: 'METIS'
date: '2026-06-15'
---

# Architecture Decision Document

_This document builds collaboratively through step-by-step discovery. Sections are appended as we work through each architectural decision together._

---

## Project Context Analysis

### Requirements Overview

**Functional Requirements:**
36 FRs across 7 features describe a single-direction, schedule-driven data pipeline: OpenSearch → Fingerprint gate → LLM → Terminal. There is no inbound HTTP API, no UI, no multi-tenancy. The application is a daemon with one scheduled entry point and one MVP output channel. Correctness requirements are precise: ordering constraints (checkpoint-advance invariant, suppression-reload-before-poll) are as important as functional behaviour.

Feature groupings and their architectural weight:
- **Periodic Error Polling** (FR-1–5): scheduled entry point, first-run interactive prompt, checkpoint persistence
- **Fingerprint Deduplication** (FR-6–10): domain computation + H2 persistence, three-state gate
- **Escalation Notifications** (FR-11–13): threshold tracking against persisted OccurrenceCount
- **Won't-Fix Suppression** (FR-14–19): read-only file system access, hot-reload per cycle
- **LLM Root Cause Analysis** (FR-20–25): outbound HTTP to local LLM, payload construction, output parsing, analysis caching
- **Terminal Output** (FR-26–32): buffered grouped output, formatted blocks, status indicators
- **Degradation Detection** (FR-33–36): health state as first-class domain concept, sticky banner, checkpoint stasis

**Non-Functional Requirements:**
- **NFR-1 (No silent drops):** every failure path must surface to terminal output — comprehensive exception handling at all adapter boundaries
- **NFR-2 (Self-observability):** `DegradedState` is a first-class domain concept, not a logging side-effect — the poll loop must check and print health state on every cycle
- **NFR-3 (Configuration-first):** all operational parameters externalized via `application.yml` / environment variables — `@ConfigurationProperties` binding
- **NFR-4 (LLM throughput budget):** deduplication gate must precede all LLM calls — enforced by domain pipeline ordering
- **NFR-5 (Checkpoint-advance invariant):** PollCheckpoint write must be transactional and occur only after full batch delivery — `@Transactional` boundary on the batch processing method
- **NFR-6 (Read-only suppression):** SuppressionFile adapter has no write path — enforced at the port boundary

**Scale & Complexity:**
- Primary domain: backend daemon / CLI (no UI, no auth, no multi-tenancy)
- Complexity level: Low-Medium
- External integrations: 3 MVP (OpenSearch read, local LLM HTTP, suppression file read) + 1 test fixture (companion Spring Boot app)
- Concurrency model: single-threaded sequential poll cycle sufficient for MVP; Java 21 virtual threads ease blocking LLM HTTP calls

### Technical Constraints & Dependencies

| Constraint | Value | Architectural impact |
|---|---|---|
| Java version | 21 | Virtual threads available for blocking LLM calls; records/sealed classes usable in domain model |
| Spring Boot | 4.x (Spring Framework 7, Jakarta EE) | `jakarta.*` namespace; `@Scheduled`, `@ConfigurationProperties`, Spring Data JPA, Spring Shell for interactive prompt |
| Embedded DB | H2 | No external DB dependency; schema managed via Flyway or JPA auto-DDL; two persistent entities (PollCheckpoint, DeduplicationRecord) |
| Module structure | Ports and adapters (hexagonal) | Domain core isolated from all infrastructure; each external system behind a port interface |
| OpenSearch | Local Docker container (MVP) | OpenSearch Java client or RestClient; connection config externalized |
| LLM | Local (Ollama or similar) HTTP endpoint | REST adapter; timeout handling required; `LLMAnalysis.unavailability_reason` on failure |
| SuppressionFile | Developer-owned flat file | Read-only file system adapter; reload every cycle; no write path |
| Companion app | Spring Boot + Logback + logstash-logback-encoder | Separate application; appender config must produce `_source.structured.*` / `_source.kubernetes.*` field layout |

### Cross-Cutting Concerns Identified

1. **Transactional integrity** — PollCheckpoint advance is a transactional write that must happen after — not before or during — batch delivery. Spans persistence adapter and domain pipeline.
2. **Error visibility** — No silent drops at any stage. Every adapter boundary must catch, wrap, and surface exceptions to the terminal output port rather than swallowing them.
3. **Health state management** — DegradedState (`degradation_started_at`, `consecutive_poll_failures`) must be checked and printed on every poll cycle, not just at state transitions.
4. **Configuration binding** — All tunable parameters bound to a single `@ConfigurationProperties` class; no scattered `@Value` annotations.
5. **Data governance boundary** — `vdab_authorization` must be excluded from the LLM port payload for any cloud LLM adapter. Enforced at the LLM port interface (post-MVP adapter swap point).

---

## Starter Template Evaluation

### Primary Technology Domain

Java backend daemon — no web UI, no frontend scaffold. Both applications scaffolded from Spring Initializr (`start.spring.io`), living in the same repository.

### Applications Scaffolded

#### 1. `logguard-ai` — Main Daemon

**Initializr settings:** Maven · Java 21 · Spring Boot 4.1.0 · `be.vdab` / `logguard-ai` / `be.vdab.logguard`

**Initializr command:**
```bash
curl "https://start.spring.io/starter.zip" \
  -d type=maven-project \
  -d language=java \
  -d bootVersion=4.1.0 \
  -d baseDir=logguard-ai \
  -d groupId=be.vdab \
  -d artifactId=logguard-ai \
  -d name=logguard-ai \
  -d packageName=be.vdab.logguard \
  -d javaVersion=21 \
  -d dependencies=data-jpa,h2,spring-shell,flyway,actuator \
  -o logguard-ai.zip
```

**Dependencies (add manually to `pom.xml`):**
- `org.opensearch.client:opensearch-java` — official OpenSearch Java client (Spring Data OpenSearch has no confirmed Spring Boot 4 compatibility)
- `RestClient` — built into `spring-web`; used for LLM HTTP calls (no WebFlux required)

#### 2. `logguard-error-producer` — Companion Test Fixture

**Initializr settings:** Maven · Java 21 · Spring Boot 4.1.0 · `be.vdab` / `logguard-error-producer` / `be.vdab.logguard.producer`

**Initializr command:**
```bash
curl "https://start.spring.io/starter.zip" \
  -d type=maven-project \
  -d language=java \
  -d bootVersion=4.1.0 \
  -d baseDir=logguard-error-producer \
  -d groupId=be.vdab \
  -d artifactId=logguard-error-producer \
  -d name=logguard-error-producer \
  -d packageName=be.vdab.logguard.producer \
  -d javaVersion=21 \
  -d dependencies=web \
  -o logguard-error-producer.zip
```

**Dependencies (add manually):**
- `net.logstash.logback:logstash-logback-encoder:9.0` — structured JSON logging matching `_source.structured.*` layout
- `internetitem:logback-elasticsearch-appender` — ships Logback events to local OpenSearch via HTTP (`localhost:9200`)

### Hexagonal Package Structure for `logguard-ai`

Ports and adapters implemented via **package-level separation within a single Maven module**. Maven sub-modules would add build overhead with no benefit at this scale for a single-service daemon.

```
be.vdab.logguard
├── domain/
│   ├── model/          # ErrorLog, ErrorFingerprint, DeduplicationRecord,
│   │                   # PollCheckpoint, LLMAnalysis (records / value objects)
│   ├── port/
│   │   ├── in/         # PollUseCase (inbound / driving port)
│   │   └── out/        # OpenSearchPort, LlmPort,
│   │                   # DeduplicationRecordRepository, PollCheckpointRepository,
│   │                   # SuppressionFilePort, TerminalOutputPort
│   └── service/        # PollService (domain logic — zero Spring annotations)
├── infrastructure/
│   ├── opensearch/     # OpenSearchAdapter implements OpenSearchPort
│   ├── llm/            # LlmAdapter implements LlmPort
│   ├── persistence/    # JPA entities + Spring Data repos
│   │                   # implements DeduplicationRecordRepository, PollCheckpointRepository
│   ├── filesystem/     # SuppressionFileAdapter implements SuppressionFilePort
│   ├── terminal/       # TerminalOutputAdapter implements TerminalOutputPort
│   └── config/         # @ConfigurationProperties, @Scheduled scheduler bean
└── LogguardAiApplication.java
```

**Key invariant:** `domain/` has zero Spring or infrastructure imports. All Spring annotations live in `infrastructure/`. The domain compiles and unit-tests without a Spring context.

### Architectural Decisions Provided by Starter

| Decision | Value |
|---|---|
| Language & runtime | Java 21; virtual threads enabled (`spring.threads.virtual.enabled=true`) for blocking LLM calls |
| Persistence | JPA + H2 embedded; Flyway for explicit schema management (no auto-DDL) |
| Interactive prompt | Spring Shell 4.0.3 — handles first-run lookback prompt (FR-2) without custom stdin reader |
| HTTP client | Spring `RestClient` (built-in) — LLM calls and OpenSearch REST; no WebFlux dependency |
| Configuration | `@ConfigurationProperties` — single binding class for all operational parameters |
| Build | Maven; both apps share parent repo; independent deployable JARs |

**Note:** Running both Initializr commands, wiring the parent repository, and verifying the Logback appender field mapping against the expected `_source.structured.*` layout is the first implementation story.

---

## Core Architectural Decisions

### Decision Priority Analysis

**Critical (block implementation):**
- H2 file-based persistence — PollCheckpoint must survive restarts
- Spring AI milestone repository — required before any dependency resolution
- `@Transactional` boundary on batch processing — checkpoint-advance invariant (NFR-5)

**Important (shape architecture):**
- Ollama + Spring AI 2.0.0-M4 — LLM adapter implementation pattern
- `fixedDelay` scheduling — no-overlap poll cycle contract
- `StructuredOutputConverter` → `LLMAnalysis` record — FR-23 parsing strategy

**Deferred (post-MVP):**
- Google Chat webhook adapter
- Gemini API adapter (blocked by `vdab_authorization` data governance requirement)
- GitLab service registry adapter

### Data Architecture

| Decision | Value | Rationale |
|---|---|---|
| H2 mode | File-based: `jdbc:h2:file:./data/logguard` | `PollCheckpoint` must survive restarts; in-memory loses cursor on JVM exit, breaking catch-up behaviour |
| Schema management | Flyway migrations in `src/main/resources/db/migration/` | Explicit DDL control; no auto-DDL surprises; migration history tracked |
| Persistent entities | `poll_checkpoint` (1 row ever), `deduplication_record` (1 row per active fingerprint) | Minimal schema; dedup records expire after `deduplication_window` (24h default) |
| Transaction boundary | `@Transactional` on `PollService.processBatch()` | Checkpoint advance and batch delivery are atomic — crash before commit re-fetches the same batch |
| `./data/` directory | Gitignored | Local runtime state, not source-controlled |

### Authentication & Security

No authentication or authorization required — single-operator developer tool. Data governance enforced structurally:
- `vdab_authorization` excluded from `LlmPort` outbound interface contract for any cloud LLM adapter (post-MVP)
- `SuppressionFilePort` has no write method — read-only contract enforced at the port interface level

### LLM Integration

| Decision | Value | Rationale |
|---|---|---|
| LLM runtime | Ollama | Most common local LLM runtime; easy model swap (`llama3`, `codellama`, etc.) |
| Spring AI version | `2.0.0-M4` (via `spring-ai-bom`) | Only Spring AI line compatible with Spring Boot 4.x |
| Starter dependency | `spring-ai-starter-model-ollama:2.0.0-M4` | Provides `OllamaChatModel`, timeout config, and retry |
| Response mapping | `StructuredOutputConverter` → `LLMAnalysis` Java record | FR-23 three-field contract bound directly; missing label = malformed → FR-24 fallback |
| Maven repository | Spring milestones: `https://repo.spring.io/milestone` | Add to `pom.xml` `<repositories>` — required before any `mvn install` |

**Cascading implication:** `LLMAnalysis` must be a Java record for `StructuredOutputConverter` binding. All domain model value objects use Java 21 records consistently.

### Poll Scheduling

| Decision | Value | Rationale |
|---|---|---|
| Scheduling mode | `@Scheduled(fixedDelayString = "${logguard.poll-interval:5m}")` | N minutes *after* previous poll completes — no overlap regardless of batch size |
| Initial delay | `initialDelay = 0` | First-run check starts immediately on application startup |
| Enable annotation | `@EnableScheduling` on `LogguardAiApplication` | Single activation point |
| Virtual threads | `spring.threads.virtual.enabled=true` | Java 21 Loom; Ollama HTTP calls are blocking — virtual threads prevent thread starvation during LLM analysis |

### Infrastructure & Deployment

| Decision | Value | Rationale |
|---|---|---|
| Docker Compose | Single `docker-compose.yml` at repo root | One `docker compose up` starts everything; simplest for single-developer MVP |
| Compose services | `opensearch` + `logguard-error-producer` | LogGuard AI runs on host; OpenSearch and companion app containerized |
| OpenSearch config | `discovery.type=single-node`, `plugins.security.disabled=true` | No TLS/auth needed for local dev |
| Logging | Logback (Spring Boot default) | No custom configuration for LogGuard AI; companion app uses `logstash-logback-encoder:9.0` |

### Decision Impact Analysis

**Implementation sequence:**
1. Add Spring milestone repo + Spring AI BOM to `pom.xml`
2. Configure file-based H2 + Flyway baseline migration (`V1__init.sql`)
3. Wire `@Scheduled fixedDelay` + first-run Spring Shell prompt
4. Implement `LlmAdapter` using `OllamaChatModel` + `StructuredOutputConverter`
5. Implement `OpenSearchAdapter` using `opensearch-java` client
6. Wire `docker-compose.yml`; verify companion app Logback field mapping end-to-end

**Cross-component dependencies:**
- `StructuredOutputConverter` requires `LLMAnalysis` to be a Java record → domain model convention
- File-based H2 path must be configurable via `@ConfigurationProperties` — never hardcoded
- Spring AI milestone repo must be committed before first `mvn install` — day-one concern

---

## Implementation Patterns & Consistency Rules

### Potential Conflict Points Identified

9 areas where an implementing agent could diverge and produce incompatible code: hexagonal layer naming, JPA entity vs domain record distinction, database naming, config property naming, test organisation, `LLMAnalysis` record field binding, terminal output routing, adapter error handling, and `@Transactional` placement.

### Naming Patterns

**Hexagonal layer naming:**

| Element | Convention | Example |
|---|---|---|
| Domain value object | Java `record` | `ErrorFingerprint`, `LLMAnalysis` |
| Domain entity (JPA-persisted) | `class` with `@Entity` in `infrastructure/persistence/` | `DeduplicationRecordEntity`, `PollCheckpointEntity` |
| Outbound port (driven) | `{Name}Port` interface | `OpenSearchPort`, `LlmPort`, `SuppressionFilePort` |
| Inbound port (driving) | `{Name}UseCase` interface | `PollUseCase` |
| Adapter implementing port | `{Name}Adapter` class | `OpenSearchAdapter`, `LlmAdapter` |
| Domain service | `{Name}Service` | `PollService`, `FingerprintService` |
| Spring config properties class | `{Name}Properties` | `LogguardProperties` |

**Critical distinction:** Domain records (`ErrorFingerprint`, `DeduplicationRecord`) live in `domain/model/` with zero JPA annotations. JPA-mapped classes (`DeduplicationRecordEntity`) live in `infrastructure/persistence/` and translate to/from domain records. Never add `@Entity` to a domain record.

**Database naming — explicit over implicit:**

All JPA mappings use explicit `@Column(name = "...")` and `@Table(name = "...")`. Never rely on Hibernate's auto-naming strategy.

| Convention | Value | Example |
|---|---|---|
| Table names | `snake_case`, singular | `poll_checkpoint`, `deduplication_record` |
| Column names | `snake_case` | `last_successful_poll_at`, `occurrence_count`, `wont_fix` |
| Primary key | `id` (Long, `@GeneratedValue`) | — |
| Boolean columns | `BOOLEAN NOT NULL DEFAULT FALSE` | `wont_fix` |
| Timestamp columns | `TIMESTAMP WITH TIME ZONE` | `first_seen_at`, `expires_at` |

Flyway migration file naming: `V{n}__{snake_case_description}.sql` (two underscores).
- `V1__create_poll_checkpoint.sql`
- `V2__create_deduplication_record.sql`

**Configuration property naming:**

All properties under `logguard.*`, kebab-case keys, bound via `LogguardProperties` (`@ConfigurationProperties(prefix = "logguard")`). `@Value("${...}")` is prohibited.

```yaml
logguard:
  poll-interval: 5m
  deduplication-window: 24h
  escalation-thresholds: [10, 100, 1000]
  max-consecutive-poll-failures: 3
  own-code-package-prefixes:
    - be.vdab
  suppression-file-path: ./suppression.txt
  ollama:
    base-url: http://localhost:11434
    model: llama3
    timeout: 30s
  opensearch:
    base-url: http://localhost:9200
    index-pattern: logstash-app-openshift-application-springboot_error_*
  h2:
    data-dir: ./data
```

### Structure Patterns

**Test organisation:**

| Test type | Location | Suffix | Spring context |
|---|---|---|---|
| Domain unit tests | `src/test/java/be/vdab/logguard/domain/` | `Test` | None — pure Java |
| Adapter unit tests | `src/test/java/be/vdab/logguard/infrastructure/` | `Test` | None — mock ports |
| Integration tests | `src/test/java/be/vdab/logguard/` | `IT` | `@SpringBootTest` |

If a domain test requires `@SpringBootTest`, the domain layer has a dependency leak.

**Prompt template location:** `src/main/resources/prompts/llm-analysis.st` — loaded via `ClassPathResource`. Never hardcoded in Java.

### Format Patterns

**`LLMAnalysis` record — field names must match `StructuredOutputConverter` binding:**

```java
public record LLMAnalysis(
    boolean llmAvailable,
    String rootCause,           // maps from "Root cause:"
    String likelyLocation,      // maps from "Likely location:"
    String suggestedAction,     // maps from "Suggested action:"
    String unavailabilityReason
) {}
```

A malformed response (any content field null after conversion) triggers FR-24 fallback: return `LLMAnalysis` with `llmAvailable=false` and `unavailabilityReason` describing the failure.

**Terminal output status indicators** — constants in `TerminalOutputAdapter`, never inline literals in domain or service code:

```java
static final String DEGRADED   = "🔴";
static final String RECOVERED  = "🟢";
static final String WONT_FIX   = "⚑";
static final String ESCALATION = "⚠️";
```

### Process Patterns

**Error handling at adapter boundaries — highest priority consistency rule:**

Every outbound adapter catches all infrastructure exceptions at its own boundary and converts to domain-safe return values. Infrastructure exceptions must never propagate into `domain/` or `application/`.

```java
// LlmAdapter — correct pattern
@Override
public LLMAnalysis analyse(ErrorLog error) {
    try {
        return chatClient.prompt()
            .user(buildPrompt(error))
            .call()
            .entity(LLMAnalysis.class);
    } catch (Exception e) {
        return new LLMAnalysis(false, null, null, null, e.getMessage());
    }
}
```

**`@Transactional` placement — one rule:**

`@Transactional` on `PollService.processBatch()` only. Not on repository methods. Not on adapters. Not on the scheduler. The transaction wraps: fetch from OpenSearch → process all errors → advance checkpoint.

**Terminal output routing — prohibited patterns:**

`System.out.println` and `System.err.println` are prohibited in `domain/` and `infrastructure/` except inside `TerminalOutputAdapter`. All user-facing output goes through `TerminalOutputPort`.

**SLF4J log levels:**

| Event | Level |
|---|---|
| Poll cycle start/end | `DEBUG` |
| New error detected | `DEBUG` |
| LLM call start/result | `DEBUG` |
| Adapter failure | `WARN` |
| Unrecoverable error | `ERROR` |
| Startup/shutdown | `INFO` |

User-facing output (banners, analysis blocks, escalation notices) goes to `TerminalOutputPort`, not the logger.

### Enforcement Guidelines

**All implementing agents MUST:**
- Keep `domain/` free of Spring annotations (`@Component`, `@Service`, `@Autowired`, `@Transactional`, `@Entity`)
- Use explicit `@Column` and `@Table` — no implicit JPA naming
- Bind all config via `LogguardProperties` — no `@Value`
- Route all terminal output through `TerminalOutputPort`
- Catch all infrastructure exceptions at the adapter boundary — never in the domain service
- Suffix test classes with `Test` (unit) or `IT` (Spring integration)

**Anti-patterns to reject in code review:**
- `@Entity` on a domain record
- `System.out.println` outside `TerminalOutputAdapter`
- `@Value("${logguard...}")` instead of `LogguardProperties`
- `@Transactional` on a repository method or adapter
- Infrastructure exception type in a domain method signature

---

## Project Structure & Boundaries

### Complete Project Directory Structure

The repository root IS the main `logguard-ai` Spring Boot application. The companion app lives as a subdirectory.

```
logguard-ai/                                          (repo root = main app)
├── pom.xml
├── docker-compose.yml
├── .gitignore
├── README.md
├── suppression.txt.example                           (template for developers)
├── specs/
│   └── logguard-ai.allium
├── data/                                             (gitignored — H2 file DB at runtime)
│
├── src/
│   ├── main/
│   │   ├── java/be/vdab/logguard/
│   │   │   ├── LogguardAiApplication.java            (@SpringBootApplication @EnableScheduling)
│   │   │   │
│   │   │   ├── domain/
│   │   │   │   ├── model/
│   │   │   │   │   ├── ErrorLog.java                 (record)
│   │   │   │   │   ├── ErrorFingerprint.java         (record)
│   │   │   │   │   ├── DeduplicationRecord.java      (record — domain state, no @Entity)
│   │   │   │   │   ├── PollCheckpoint.java           (record — cursor + degradation state)
│   │   │   │   │   └── LLMAnalysis.java              (record — StructuredOutputConverter target)
│   │   │   │   ├── port/
│   │   │   │   │   ├── in/
│   │   │   │   │   │   └── PollUseCase.java          (interface — inbound driving port)
│   │   │   │   │   └── out/
│   │   │   │   │       ├── OpenSearchPort.java
│   │   │   │   │       ├── LlmPort.java              (always returns, never throws)
│   │   │   │   │       ├── DeduplicationRecordRepository.java
│   │   │   │   │       ├── PollCheckpointRepository.java
│   │   │   │   │       ├── SuppressionFilePort.java  (read-only — no write method)
│   │   │   │   │       └── TerminalOutputPort.java
│   │   │   │   └── service/
│   │   │   │       ├── PollService.java              (implements PollUseCase, @Transactional on processBatch)
│   │   │   │       └── FingerprintService.java       (pure domain — no Spring annotations)
│   │   │   │
│   │   │   └── infrastructure/
│   │   │       ├── opensearch/
│   │   │       │   └── OpenSearchAdapter.java        (implements OpenSearchPort)
│   │   │       ├── llm/
│   │   │       │   └── LlmAdapter.java               (implements LlmPort — Spring AI OllamaChatModel)
│   │   │       ├── persistence/
│   │   │       │   ├── DeduplicationRecordEntity.java
│   │   │       │   ├── PollCheckpointEntity.java
│   │   │       │   ├── DeduplicationRecordJpaRepository.java
│   │   │       │   ├── PollCheckpointJpaRepository.java
│   │   │       │   ├── DeduplicationRecordRepositoryAdapter.java
│   │   │       │   └── PollCheckpointRepositoryAdapter.java
│   │   │       ├── filesystem/
│   │   │       │   └── SuppressionFileAdapter.java
│   │   │       ├── terminal/
│   │   │       │   └── TerminalOutputAdapter.java    (only class permitted to use System.out)
│   │   │       └── config/
│   │   │           ├── LogguardProperties.java       (@ConfigurationProperties(prefix = "logguard"))
│   │   │           └── PollScheduler.java            (@Scheduled fixedDelay)
│   │   │
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-local.yml
│   │       ├── prompts/
│   │       │   └── llm-analysis.st                   (Spring AI prompt template)
│   │       └── db/migration/
│   │           ├── V1__create_poll_checkpoint.sql
│   │           └── V2__create_deduplication_record.sql
│   │
│   └── test/
│       └── java/be/vdab/logguard/
│           ├── domain/
│           │   ├── model/
│           │   │   └── ErrorFingerprintTest.java
│           │   └── service/
│           │       ├── PollServiceTest.java
│           │       └── FingerprintServiceTest.java
│           ├── infrastructure/
│           │   ├── opensearch/
│           │   │   └── OpenSearchAdapterTest.java
│           │   ├── llm/
│           │   │   └── LlmAdapterTest.java
│           │   ├── persistence/
│           │   │   └── DeduplicationRecordRepositoryAdapterTest.java
│           │   └── filesystem/
│           │       └── SuppressionFileAdapterTest.java
│           └── LogguardAiIT.java                     (@SpringBootTest — full pipeline smoke test)
│
└── logguard-error-producer/
    ├── pom.xml
    └── src/
        ├── main/
        │   ├── java/be/vdab/logguard/producer/
        │   │   ├── LogguardErrorProducerApplication.java
        │   │   └── controller/
        │   │       └── ErrorTriggerController.java
        │   └── resources/
        │       ├── application.yml
        │       └── logback-spring.xml
        └── test/
            └── java/be/vdab/logguard/producer/
                └── ErrorTriggerControllerTest.java
```

### Port Interface Contracts

```java
OpenSearchPort:
  List<ErrorLog> findErrorsSince(Instant from)

LlmPort:
  LLMAnalysis analyse(ErrorLog error)           // never throws — returns llmAvailable=false on failure

DeduplicationRecordRepository:
  Optional<DeduplicationRecord> findActiveByFingerprint(ErrorFingerprint fp)
  void save(DeduplicationRecord record)
  void deleteExpired(Instant before)

PollCheckpointRepository:
  Optional<PollCheckpoint> load()               // empty = first run
  void save(PollCheckpoint checkpoint)

SuppressionFilePort:
  Set<String> loadHashes()                      // empty set if file absent — never throws

TerminalOutputPort:
  void printProgress(String serviceName, int count)
  void printAnalysis(ErrorLog error, LLMAnalysis analysis, String fingerprint, String hash)
  void printEscalation(String humanLabel, int threshold, Instant firstSeen, LLMAnalysis stored)
  void printWontFixLabel(String humanLabel, String hash)
  void printDegradedBanner(Instant since, int failureCount)
  void printRecoveryLine(Instant now)
```

### Data Flow

```
PollScheduler  (@Scheduled fixedDelay)
  └─ PollService.processBatch()  [@Transactional]
       ├─ SuppressionFilePort.loadHashes()                [reload BEFORE poll — FR-14]
       ├─ OpenSearchPort.findErrorsSince(checkpoint)
       └─ for each ErrorLog (batched by service_name):
            ├─ FingerprintService.compute(error) → ErrorFingerprint
            ├─ DeduplicationRecordRepository.findActiveByFingerprint()
            ├─ [NEW — hash in suppression] → save(wont_fix=true) → printWontFixLabel()
            ├─ [NEW — not suppressed]      → LlmPort.analyse() → save(record) → buffer output
            ├─ [COOLING]                   → save(count++) → check thresholds → printEscalation()
            └─ [WONT-FIX]                  → save(count++) → check 1000× → printEscalation()
       ├─ TerminalOutputPort.print*(buffered, ordered by error count desc)
       └─ PollCheckpointRepository.save(advanced checkpoint)   [LAST — NFR-5]
```

### Key Configuration (`application.yml` skeleton)

```yaml
spring:
  threads.virtual.enabled: true
  datasource:
    url: jdbc:h2:file:${logguard.h2.data-dir:./data}/logguard
    driver-class-name: org.h2.Driver
  jpa.hibernate.ddl-auto: validate
  ai.ollama:
    base-url: ${logguard.ollama.base-url:http://localhost:11434}
    chat.model: ${logguard.ollama.model:llama3}

logguard:
  poll-interval: 5m
  deduplication-window: 24h
  escalation-thresholds: [10, 100, 1000]
  max-consecutive-poll-failures: 3
  own-code-package-prefixes: [be.vdab]
  suppression-file-path: ./suppression.txt
  h2.data-dir: ./data
  ollama.timeout: 30s
  opensearch:
    base-url: http://localhost:9200
    index-pattern: logstash-app-openshift-application-springboot_error_*
```

### FR-to-File Mapping

| FR group | Primary files |
|---|---|
| FR-1–5 Polling | `PollService`, `PollScheduler`, `OpenSearchAdapter`, `PollCheckpointRepositoryAdapter` |
| FR-6–10 Deduplication | `FingerprintService`, `DeduplicationRecord`, `DeduplicationRecordRepositoryAdapter` |
| FR-11–13 Escalation | `PollService` (threshold logic), `TerminalOutputAdapter` |
| FR-14–19 WontFix | `SuppressionFileAdapter`, `PollService` (gate), `TerminalOutputAdapter` |
| FR-20–25 LLM | `LlmAdapter`, `LLMAnalysis`, `prompts/llm-analysis.st` |
| FR-26–32 Terminal output | `TerminalOutputAdapter`, `TerminalOutputPort` |
| FR-33–36 Degradation | `PollService`, `PollCheckpoint`, `TerminalOutputAdapter` |
| NFR-3 Config | `LogguardProperties`, `application.yml` |
| Companion app | `logguard-error-producer/`, `logback-spring.xml` |

---

## Architecture Validation Results

### Coherence Validation ✅

All technology versions coexist without conflict: Spring Boot 4.1.0 + Spring AI 2.0.0-M4 + Spring Shell 4.0.3 + H2 + Flyway + `opensearch-java`. Virtual threads are compatible with `@Scheduled fixedDelay`. `logstash-logback-encoder:9.0` requires Logback ≥ 1.3 — Spring Boot 4 ships 1.5.x. No version conflicts.

Naming conventions are internally consistent across all layers. The domain/infrastructure split is clean. Config property naming (`logguard.*` kebab-case) is uniform. Test conventions (`Test`/`IT`) align with JUnit 5 + Spring Boot norms.

### Requirements Coverage Validation ✅

All 36 FRs and 6 NFRs are architecturally supported. Highest-risk FRs verified:

| FR | Risk | Architectural coverage |
|---|---|---|
| FR-3 Checkpoint-advance invariant | Data loss on crash | `@Transactional` on `PollService.processBatch()`; `save()` called last |
| FR-8 Won't-fix-from-birth | Missing code path | `PollService` checks `suppressionHashes` at record creation time |
| FR-14 Suppression reload ordering | Race condition | `loadHashes()` called before `findErrorsSince()` within same transaction |
| FR-16 Never write SuppressionFile | Silent override | `SuppressionFilePort` has no write method — structurally enforced |
| FR-23 Three-field output contract | Parsing failure | `LLMAnalysis` record + `StructuredOutputConverter`; null field = malformed → FR-24 |
| FR-24 LLM failure fallback | Silent drop | `LlmAdapter` try/catch returns `llmAvailable=false` — never propagates |

### Gap Analysis

**Important gap resolved — Spring Shell first-run prompt:**
Added `FirstRunInitializer.java` to `infrastructure/config/` as an `ApplicationRunner`. Checks `PollCheckpointRepository.load()` on startup — if empty, uses Spring Shell's `Terminal` to prompt the developer for the lookback window before the scheduler fires.

**Important gap resolved — `strip_tenant_data` not mapped:**
Added `TenantDataSanitizer.java` to `domain/service/` — pure Java, no Spring. Contains `sanitize(String input): String` with the KBO/email/LDAP-DN allow/deny list (FR-21). Called from `LlmAdapter.buildPrompt()`.

**Structure additions from gap resolution:**
```
domain/service/
  └── TenantDataSanitizer.java      (pure Java — FR-21 strip_tenant_data)

infrastructure/config/
  └── FirstRunInitializer.java      (ApplicationRunner — FR-2 first-run prompt)
```

**Docker Compose skeleton (minor gap resolved):**
```yaml
services:
  opensearch:
    image: opensearchproject/opensearch:2
    environment:
      - discovery.type=single-node
      - plugins.security.disabled=true
    ports:
      - "9200:9200"
    healthcheck:
      test: ["CMD-SHELL", "curl -s http://localhost:9200 | grep -q opensearch"]
      interval: 10s

  logguard-error-producer:
    build: ./logguard-error-producer
    depends_on:
      opensearch:
        condition: service_healthy
    environment:
      - OPENSEARCH_URL=http://opensearch:9200
```

### Architecture Completeness Checklist

**Requirements Analysis**
- [x] Project context thoroughly analyzed
- [x] Scale and complexity assessed
- [x] Technical constraints identified
- [x] Cross-cutting concerns mapped

**Architectural Decisions**
- [x] Critical decisions documented with versions
- [x] Technology stack fully specified
- [x] Integration patterns defined
- [x] Performance considerations addressed

**Implementation Patterns**
- [x] Naming conventions established
- [x] Structure patterns defined
- [x] Communication patterns specified
- [x] Process patterns documented

**Project Structure**
- [x] Complete directory structure defined
- [x] Component boundaries established
- [x] Integration points mapped
- [x] Requirements to structure mapping complete

### Architecture Readiness Assessment

**Overall Status: READY FOR IMPLEMENTATION**

**Confidence Level: High** — all 36 FRs and 6 NFRs supported, all 16 checklist items checked, no critical gaps.

**Key strengths:**
- Hexagonal structure enforces data governance at the port boundary — `vdab_authorization` cloud LLM landmine handled structurally
- `@Transactional` on `processBatch()` makes the checkpoint-advance invariant impossible to violate accidentally
- `LlmPort` contract (always returns, never throws) + `StructuredOutputConverter` on a Java record makes FR-23/FR-24 cleanly implementable
- `SuppressionFilePort` with no write method enforces NFR-6 structurally

**Post-MVP enhancement slots:**
- Google Chat: `TerminalOutputPort` can be augmented with a `GoogleChatOutputAdapter` without touching domain
- Gemini swap: `LlmAdapter` is the sole `LlmPort` implementation; swap requires only excluding `vdab_authorization` from payload
- TTL cleanup: `deleteExpired(Instant before)` already on repository port — add a `@Scheduled` job post-MVP

### Implementation Handoff

**AI Agent Guidelines:**
- Follow all architectural decisions exactly as documented
- Apply adapter error-handling pattern at every infrastructure boundary
- Enforce `domain/` Spring-free invariant — if a domain unit test needs `@SpringBootTest`, something is wrong
- Refer to `prd.md` for behavioral requirements; refer to this document for all architectural questions

**First implementation story — four-step integration gate:**
1. Run Initializr commands; add Spring milestone repo + Spring AI BOM to `pom.xml`
2. Add `docker-compose.yml`; start OpenSearch container
3. Configure `logback-spring.xml` in companion app; POST to `ErrorTriggerController`; verify document appears in OpenSearch with correct `_source.structured.*` fields
4. Wire `OpenSearchAdapter` to read that document — if fields resolve correctly, the integration gate passes and full pipeline build can begin
