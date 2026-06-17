---
baseline_commit: 1a3ecce45d54bfcfbbe56dcd155fc8b9ac89c3bb
---

# Story 2.3: OpenSearch Error Polling Adapter

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a developer,
I want LogGuard to retrieve ERROR-level log entries from OpenSearch for all documents newer than the last checkpoint,
so that every error that occurred since the last successful poll is available for processing.

## Acceptance Criteria

1. **Given** the companion app has written test errors to local OpenSearch (Epic 1 complete) **and** `PollCheckpoint.last_successful_poll_at` is set to 1 hour ago, **when** `OpenSearchAdapter.findErrorsSince(Instant from)` is called, **then** all test errors written after that timestamp are returned as `ErrorLog` records.
2. Each `ErrorLog` maps the fields correctly: `exception_type`, `error_message`, `stack_trace`, `service_name`, `app_name`, `team`, `environment`, `occurred_at`, `vdab_authorization` (from the nested `_source` paths — see Dev Notes field-mapping table).
3. If OpenSearch returns zero documents, an **empty list** is returned — **no exception thrown**.
4. `OpenSearchPort` interface in `domain/port/out/` declares `List<ErrorLog> findErrorsSince(Instant from)`.
5. The `ErrorLog` domain record in `domain/model/` has **no Spring or JPA annotations**.
6. The OpenSearch base URL and index pattern are read from `LogguardProperties.opensearch` — **not hardcoded**.
7. The `opensearch-java` client is used (**not** Spring Data OpenSearch).
8. A unit test `OpenSearchAdapterTest` in `infrastructure/opensearch/` tests field mapping using a stubbed OpenSearch response.

## Tasks / Subtasks

- [x] **Task 1: `ErrorLog` domain record** (AC: #2, #5)
  - [x] Create `be.vdab.logguard.domain.model.ErrorLog` as a Java `record` with: `String exceptionType`, `String errorMessage`, `String stackTrace`, `String serviceName`, `String appName`, `String team`, `String environment`, `Instant occurredAt`, `String vdabAuthorization`. **Zero Spring/JPA annotations** (pure domain).
- [x] **Task 2: `OpenSearchPort` outbound port** (AC: #4)
  - [x] Create `be.vdab.logguard.domain.port.out.OpenSearchPort` with `List<ErrorLog> findErrorsSince(Instant from)`. No annotations. [Source: architecture.md lines 529–530.]
- [x] **Task 3: `OpenSearchClient` bean configuration** (AC: #6, #7) — read Dev Notes "Client setup + Jackson 2/3 landmine"
  - [x] Create `OpenSearchClientConfig` (`@Configuration`) in `infrastructure/opensearch/` that builds a singleton `OpenSearchClient` from `LogguardProperties.opensearch.baseUrl`.
  - [x] Parse the base URL into an Apache HC5 `HttpHost` (scheme/host/port) and build the transport with `ApacheHttpClient5TransportBuilder.builder(host).setMapper(new JacksonJsonpMapper()).build()`, then `new OpenSearchClient(transport)`. Plain `http`, no auth/TLS (local security disabled). `httpclient5` is transitive via `opensearch-java` — no extra dependency.
  - [x] Do **not** use Spring Data OpenSearch (AC #7). Do **not** wire Boot's Jackson 3 `ObjectMapper` into `JacksonJsonpMapper` (it is Jackson 2 — see landmine).
- [x] **Task 4: `OpenSearchErrorDocument` deserialization DTO** (AC: #2) — Jackson 2 only
  - [x] Create `OpenSearchErrorDocument` in `infrastructure/opensearch/` mirroring the nested `_source` shape, using **Jackson 2** annotations (`com.fasterxml.jackson.annotation.*`) — NOT `tools.jackson.*`. Annotate the type `@JsonIgnoreProperties(ignoreUnknown = true)` (the real documents carry many extra fields).
  - [x] Mirror: `@timestamp` (String, via `@JsonProperty("@timestamp")`), `structured.error.{type,message,stack_trace}`, `structured.service.name`, `kubernetes.labels.appName`, `kubernetes.namespace_labels.{vdab_be_team,vdab_be_environment}`, `vdab.authorization`. Use nested static records/classes for `structured`, `kubernetes`, `vdab`. Hold `@timestamp` as a `String`; the adapter parses it to `Instant` (avoids needing a Jackson date module on the JsonpMapper).
- [x] **Task 5: `OpenSearchAdapter` implementing the port** (AC: #1, #2, #3, #6, #7)
  - [x] Create `OpenSearchAdapter` (`@Component`) in `infrastructure/opensearch/` implementing `OpenSearchPort`, constructor-injected with the `OpenSearchClient` and `LogguardProperties`.
  - [x] Build a search against `LogguardProperties.opensearch.indexPattern` with a **range query on `@timestamp` greater than `from`** (ISO-8601). Request a generous size (e.g. 1000) or note pagination as out of scope for MVP (companion volumes are tiny); document the cap if you set one.
  - [x] Deserialize hits via `client.search(request, OpenSearchErrorDocument.class)` and map each `_source` to an `ErrorLog` (package-private `toErrorLog(doc)`), parsing `@timestamp` → `Instant`.
  - [x] **Zero docs → empty list, no throw** (AC #3). Wildcard index patterns return empty hits when no index matches (`allow_no_indices`), so a missing index is not an error. Genuine transport/query failures should propagate (a `RuntimeException`) so the poll loop's degradation logic (Story 2.6) can detect outages — do NOT swallow connection errors into an empty list (that would hide an outage, violating NFR-2). See Dev Notes.
- [x] **Task 6: Unit test `OpenSearchAdapterTest`** (AC: #8)
  - [x] In `src/test/java/be/vdab/logguard/infrastructure/opensearch/`, test field mapping with a **stubbed/canned OpenSearch `_source`**: deserialize a canned nested JSON document (copy the real shape from Story 1.2) into `OpenSearchErrorDocument` with a Jackson 2 `ObjectMapper`, then assert `toErrorLog(...)` produces an `ErrorLog` with every field correct and `occurredAt` parsed from `@timestamp`. (This is the "stubbed response" — no network.) Keep it a pure/`Test` (no Spring context).
  - [x] Optionally also cover: missing/extra fields tolerated (`@JsonIgnoreProperties`), and `@timestamp` parsing.
- [x] **Task 7: Live integration-gate verification (AR-17 completion)** (AC: #1, #2, #3)
  - [x] Ensure the stack is up (`docker compose up -d`) and the companion has written errors (`curl -XPOST http://localhost:8080/trigger-error`). Story 1.2 left documents in `logstash-app-openshift-application-springboot_error_*`.
  - [x] Exercise `findErrorsSince(Instant.now().minus(1h))` against the **running** local OpenSearch (a small `@SpringBootTest`-style harness, a throwaway `main`, or a temporary `ApplicationRunner`/test pointed at `http://localhost:9200`) and confirm it returns `ErrorLog` records with the `be.vdab.*` fields populated — **this completes the integration gate (AR-17): the companion's documents are read by LogGuard with no field-mapping changes.** Record the mapped `ErrorLog` (exception_type, service_name, occurred_at, a stack-trace snippet) as evidence.
  - [x] Confirm `findErrorsSince` with a `from` in the far future (or against an empty window) returns an empty list without throwing (AC #3).
  - [x] Run the full test suite — no regressions (Stories 2.1/2.2 tests still green).

## Dev Notes

### ⚠️ Client setup + Jackson 2/3 landmine (read before Tasks 3–4)
`opensearch-java:3.9.0` (declared in Story 2.1) brings **its own Jackson 2** (`com.fasterxml.jackson.core:jackson-databind`) and **Apache HttpClient 5** transitively. Spring Boot 4 ships **Jackson 3** (`tools.jackson.*`). Both are on the classpath and must not be mixed:
- The OpenSearch client's `JacksonJsonpMapper` is **Jackson 2** — construct it with `new JacksonJsonpMapper()` (it creates its own Jackson 2 `ObjectMapper`). Do NOT pass Boot's `tools.jackson` `ObjectMapper` into it.
- `OpenSearchErrorDocument` must use **`com.fasterxml.jackson.annotation.*`** annotations (`@JsonProperty`, `@JsonIgnoreProperties`). If you import `tools.jackson.*` by mistake, binding silently breaks. (This mirrors Story 1.2, where Boot-4-Jackson-3 vs `com.fasterxml` first surfaced.)
- Client construction (verified pattern for opensearch-java 3.x):
  ```java
  HttpHost host = new HttpHost(uri.getScheme(), uri.getHost(), uri.getPort()); // org.apache.hc.core5.http.HttpHost
  OpenSearchTransport transport = ApacheHttpClient5TransportBuilder
          .builder(host)
          .setMapper(new JacksonJsonpMapper())
          .build();
  OpenSearchClient client = new OpenSearchClient(transport);
  ```
  No credentials/TLS — local OpenSearch serves plain HTTP on `:9200` (security disabled, Story 1.1). `httpclient5` needs no explicit dependency (transitive).
- [Source: opensearch-java 3.9.0 POM (jackson-databind + httpclient5 transitive); OpenSearch Java client USER_GUIDE — `ApacheHttpClient5TransportBuilder` + `JacksonJsonpMapper`.]

### Field-mapping table (authoritative — verified live in Story 1.2)
`ErrorLog` ← nested `_source` paths (the companion writes exactly these; confirmed live in Story 1.2):
| `ErrorLog` field | `_source` path |
|---|---|
| `exceptionType` | `structured.error.type` |
| `errorMessage` | `structured.error.message` |
| `stackTrace` | `structured.error.stack_trace` |
| `serviceName` | `structured.service.name` |
| `appName` | `kubernetes.labels.appName` |
| `team` | `kubernetes.namespace_labels.vdab_be_team` |
| `environment` | `kubernetes.namespace_labels.vdab_be_environment` |
| `occurredAt` | `@timestamp` (ISO-8601 → `Instant`) |
| `vdabAuthorization` | `vdab.authorization` |

Example real document (from Story 1.2 live verification) to use as the canned test `_source`:
```json
{ "@timestamp": "2026-06-17T15:53:23.800Z",
  "structured": { "error": { "type": "java.lang.NullPointerException",
      "message": "Cannot invoke \"String.toUpperCase()\" because \"resolved\" is null",
      "stack_trace": "java.lang.NullPointerException: ...\n\tat be.vdab.logguard.producer.service.LabelService.forwardingSourceFor(LabelService.java:27)\n\t..." },
    "service": { "name": "orgbeheer-service" }, "log": { "level": "ERROR" } },
  "kubernetes": { "labels": { "appName": "logguard-error-producer" },
    "namespace_labels": { "vdab_be_team": "backend-team", "vdab_be_environment": "local" } },
  "vdab": { "authorization": "cn=TESTUSER,ou=users,ou=intern,O=VDAB" } }
```
[Source: prd.md §4.1 ErrorLog field contract; specs/logguard-ai.allium entity ErrorLog; Story 1.2 live `_source`.]

### Time-window query
- Query the index pattern `LogguardProperties.opensearch.indexPattern` (default `logstash-app-openshift-application-springboot_error_*`) with a **range** query: `@timestamp` `gt` `from` (FR-4). No additional `log.level` filter — the index pattern already isolates errors (brainstorming decision; confirmed in Story 1.2). [Source: epics.md FR-4; architecture.md.]
- `occurred_at > last_successful_poll_at` is the contract (FR-4). Use `gt` (strictly greater) to avoid re-fetching the boundary doc — dedup (Epic 4) would absorb a duplicate anyway, but `gt` matches the FR.
- MVP volumes are tiny (companion fixture). A single search with `size` ~1000 is fine; if you cap results, `log()`/document the cap (no silent truncation). Scroll/search-after pagination is out of scope for MVP.

### Error handling nuance (do NOT swallow outages)
AC #3 says zero documents → empty list, no throw. That refers to a **successful** query returning no hits (and wildcard-no-index, which OpenSearch treats as empty). It does **not** mean catching connection failures and returning empty — that would make an OpenSearch outage look like "no errors", violating NFR-2 (self-observability). Let genuine transport/IO/query exceptions propagate from `findErrorsSince`; the poll loop's degradation detection (Story 2.6) counts consecutive failures and shows the sticky banner. This is the one adapter where the "convert exceptions to safe return values" rule is deliberately NOT applied to connection errors. [Source: architecture.md NFR-2; FR-33–36; Story 2.6.]

### Scope boundary
- This story delivers the **read adapter only**: `ErrorLog`, `OpenSearchPort`, the client config, the adapter, and field-mapping verification. It does **not** build `PollService`, the scheduler, terminal output, fingerprinting, or dedup — those are Stories 2.4/2.5/2.6 and Epic 4.
- No `@Scheduled` and no `PollUseCase` wiring here. `findErrorsSince` is exercised directly (test/harness) for verification.
- Completing this story = **AR-17 integration gate done** (companion → OpenSearch → LogGuard read path proven end-to-end).

### Testing standards
- `OpenSearchAdapterTest` (suffix `Test`, no Spring context) — the AC's "stubbed OpenSearch response" = a canned nested `_source` JSON decoded with a Jackson 2 `ObjectMapper` into `OpenSearchErrorDocument`, then `toErrorLog(...)` asserted field-by-field. This directly validates the nested-path binding (the integration-gate concern) without a network or a live cluster.
- The **live** integration-gate run (Task 7) is the authoritative end-to-end check (companion docs actually read + mapped). Record evidence.
- Do not add `@SpringBootTest` to the mapping unit test — keep it pure. If you write a live test that hits `localhost:9200`, gate it so it does not run/fail in environments without the stack up (e.g. a manual harness or a disabled/`@Disabled`-by-default test), to avoid breaking `mvn test` on machines without Docker. [Source: architecture.md test conventions lines 321–329; previous stories used in-memory/isolated tests so `mvn test` stays hermetic.]

### Project Structure Notes
- New: `domain/model/ErrorLog.java`; `domain/port/out/OpenSearchPort.java`; `infrastructure/opensearch/{OpenSearchClientConfig,OpenSearchAdapter,OpenSearchErrorDocument}.java`; `infrastructure/opensearch/OpenSearchAdapterTest.java` (test).
- The `domain/model`, `domain/port/out`, `infrastructure/opensearch` packages already exist (Story 2.1 `package-info.java`). Add classes alongside the package-info; keep it.
- No changes to `pom.xml` (opensearch-java 3.9.0 already declared in 2.1) or `application.yml` (opensearch config already present). If you find opensearch-java needs a companion artifact at runtime, flag it — but the 3.9.0 POM bundles transport + Jackson 2.

### References
- [Source: epics.md#Story 2.3: OpenSearch error polling adapter] — acceptance criteria
- [Source: epics.md#FR-4] — time-window query (`occurred_at > last_successful_poll_at`, no extra level filter)
- [Source: epics.md#AR-17] — integration gate: companion docs read by LogGuard without field-mapping changes
- [Source: architecture.md#Port Interface Contracts] — `OpenSearchPort.findErrorsSince(Instant)` (lines 529–530)
- [Source: architecture.md#Starter Template Evaluation] — `opensearch-java` chosen over Spring Data OpenSearch (lines 104–106)
- [Source: architecture.md#Naming Patterns / Enforcement] — adapter naming, domain records, no `@Value`, adapter error-handling pattern
- [Source: prd.md#4.1 + specs/logguard-ai.allium#entity ErrorLog] — ErrorLog field contract / source mapping
- [Web: opensearch-java 3.9.0 POM — jackson-databind (Jackson 2) + httpclient5 transitive]
- [Web: OpenSearch Java client USER_GUIDE / docs — `ApacheHttpClient5TransportBuilder` + `JacksonJsonpMapper`, `OpenSearchClient`]

### Previous Story Intelligence (Stories 2.2, 2.1, 1.2, 1.1)
- **Toolchain:** native build via SDKMAN (Java 21.0.11 / Maven 3.9.16); non-interactive shells `source "$HOME/.sdkman/bin/sdkman-init.sh"` first. Bare `docker` (not `wsl docker`).
- **Boot 4 / Jackson 3:** Boot ships Jackson 3 (`tools.jackson.*`); modular test starters (use JUnit assertions, don't assume AssertJ). This is exactly why opensearch-java's Jackson 2 must be kept separate.
- **`LogguardProperties.opensearch`** already has `baseUrl` (`http://localhost:9200`) and `indexPattern` (`logstash-app-openshift-application-springboot_error_*`) with defaults (Story 2.1). Read from it (constructor-inject `LogguardProperties`), never `@Value`/hardcode.
- **Live infra is up:** Story 1.1 OpenSearch on `:9200` (single-node, security disabled); Story 1.2 companion writes errors to `logstash-app-openshift-application-springboot_error_<date>` — 3 docs were present, services `orgbeheer-service`/`vacatures-service`/etc. Use `docker compose up -d` + `curl -XPOST localhost:8080/trigger-error` to (re)seed.
- **Keep `mvn test` hermetic:** Stories 2.1/2.2 tests use in-memory H2 / no external deps. A live OpenSearch test must not break `mvn test` when Docker isn't running — make the mapping test pure (canned JSON) and any localhost:9200 test manual/`@Disabled`-by-default.
- **Hexagonal discipline (enforced in reviews):** domain records have zero Spring/JPA; adapters are `@Component` in `infrastructure/`; explicit constructor injection.

### Git Intelligence
- HEAD `1a3ecce` "Implement Story 2.2…" is the baseline. Stories 1.1–2.2 committed/in review. `opensearch-java:3.9.0` was added to `pom.xml` in the 2.1 commit (`1301955`) — already resolvable.
- Established patterns to mirror: nested DTO/record mapping (companion's `OpenSearchErrorAppender` built the same `_source` shape — read it for the exact field names), `@Component` adapters implementing a `domain/port/out` interface, package-private pure helpers for unit-testability.

### Latest Technical Information (verified 2026-06-17, past training cutoff)
- **`opensearch-java:3.9.0`** — transport via `ApacheHttpClient5TransportBuilder` (Apache HttpClient 5, transitive); JSON via `JacksonJsonpMapper` (**Jackson 2 / `com.fasterxml`**). A `Jackson3JsonpMapper` is only a feature request upstream (issue #1810) — not available; stay on Jackson 2 for the client.
- **Jackson coexistence:** Boot 4 (Jackson 3, `tools.jackson`) + opensearch-java (Jackson 2, `com.fasterxml`) on one classpath is fine as long as each side uses its own. Annotate the OpenSearch DTO with `com.fasterxml.jackson.*` only.
- **OpenSearch 2.19.x** local (from Story 1.1); wildcard index search with no matching index returns empty hits (no error) by default.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Opus 4.8, 1M context)

### Debug Log References

- Native build via SDKMAN (Java 21.0.11 / Maven 3.9.16).
- `mvn -B -ntp clean test` → **BUILD SUCCESS, Tests run: 13, Failures 0, Skipped 1** (the live test self-skips via `assumeTrue` when `-Dopensearch.live` is unset — `mvn test` stays hermetic). The `opensearch-java:3.9.0` typed API compiled first try: `client.search(s -> s.index(pattern).size(...).allowNoIndices(true).query(q -> q.range(r -> r.field("@timestamp").gt(JsonData.of(from.toString())))), OpenSearchErrorDocument.class)`, `ApacheHttpClient5TransportBuilder`, `JacksonJsonpMapper`, `OpenSearchClient`.
- Mapping unit tests (canned `_source` → Jackson 2 `ObjectMapper` → `OpenSearchErrorDocument` → `toErrorLog`) pass, incl. unknown/missing-field tolerance.
- **Live AR-17 gate:** `mvn -B -ntp test -Dtest=OpenSearchAdapterLiveTest -Dopensearch.live=true` → **Tests run: 1, Failures 0, Skipped 0** against the running local OpenSearch (a fresh error POSTed to the companion beforehand). `findErrorsSince(now−1h)` returned mapped `ErrorLog`s (non-empty; exception type/service/occurredAt populated; ≥1 stack trace with a `be.vdab.*` frame); a far-future window returned empty with no throw (AC #3).

### Completion Notes List

- **All 8 ACs satisfied.** `OpenSearchAdapter.findErrorsSince(Instant)` queries the configured index pattern with a `@timestamp > from` range query and maps the nested `_source` to `ErrorLog` (AC #1/#2); zero/empty window → empty list, no throw (AC #3); `OpenSearchPort` declares the method (AC #4); `ErrorLog` is a pure domain record (AC #5); base URL + index pattern come from `LogguardProperties.opensearch` (AC #6); the official `opensearch-java` client is used, not Spring Data (AC #7); `OpenSearchAdapterTest` covers field mapping via a stubbed/canned response (AC #8).
- **AR-17 integration gate COMPLETE** — the companion app's documents (Story 1.2) are read by LogGuard end-to-end with no field-mapping changes, verified live.
- **Jackson 2/3 separation held (the 2.1-predicted landmine):** `opensearch-java` brings Jackson 2 (`com.fasterxml`) + httpclient5 transitively; Boot 4 uses Jackson 3 (`tools.jackson`). The DTO and the client's `JacksonJsonpMapper` use Jackson 2 only; Boot's Jackson 3 is untouched. No version pinning or exclusions were needed — both coexist.
- **Outage handling (deliberate):** transport/IO failures propagate as `OpenSearchQueryException` (not swallowed into an empty list) so Story 2.6's degradation detection can see them (NFR-2). AC #3's "empty list, no throw" applies only to a successful empty query / wildcard-no-index.
- **Hermetic tests preserved:** the mapping test is pure (canned JSON, no network); the live test is `assumeTrue(-Dopensearch.live)`-gated so `mvn test` passes with no Docker. Run the gate with `mvn test -Dtest=OpenSearchAdapterLiveTest -Dopensearch.live=true` (stack up + a triggered error).
- **MVP cap:** `size=1000`, no pagination (documented constant `MAX_RESULTS`); fine for companion volumes, revisit for production scale.
- No `pom.xml`/`application.yml` changes — `opensearch-java:3.9.0` and the `logguard.opensearch.*` config were already in place from Story 2.1.

### File List

- `src/main/java/be/vdab/logguard/domain/model/ErrorLog.java` (NEW) — domain record, no annotations.
- `src/main/java/be/vdab/logguard/domain/port/out/OpenSearchPort.java` (NEW) — outbound port.
- `src/main/java/be/vdab/logguard/infrastructure/opensearch/OpenSearchErrorDocument.java` (NEW) — Jackson 2 deserialization DTO mirroring the nested `_source`.
- `src/main/java/be/vdab/logguard/infrastructure/opensearch/OpenSearchClientConfig.java` (NEW) — `OpenSearchClient` bean (ApacheHttpClient5 transport + JacksonJsonpMapper).
- `src/main/java/be/vdab/logguard/infrastructure/opensearch/OpenSearchAdapter.java` (NEW) — implements `OpenSearchPort`; range query + `_source`→`ErrorLog` mapping.
- `src/main/java/be/vdab/logguard/infrastructure/opensearch/OpenSearchQueryException.java` (NEW) — propagated query/transport failure.
- `src/test/java/be/vdab/logguard/infrastructure/opensearch/OpenSearchAdapterTest.java` (NEW) — field-mapping unit test (canned `_source`, hermetic).
- `src/test/java/be/vdab/logguard/infrastructure/opensearch/OpenSearchAdapterLiveTest.java` (NEW) — `-Dopensearch.live`-gated AR-17 integration-gate check.

## Change Log

| Date | Change |
|---|---|
| 2026-06-17 | Story 2.3 drafted via create-story context engine (opensearch-java 3.9.0 client + Jackson 2/3 interplay researched; completes AR-17 integration gate). Status → ready-for-dev. |
| 2026-06-17 | Story 2.3 implemented: `ErrorLog` + `OpenSearchPort` + `OpenSearchAdapter` (opensearch-java 3.9.0, ApacheHttpClient5 + Jackson 2 JacksonJsonpMapper) reading the companion's nested `_source`. 13 tests green (live gate self-skips); AR-17 integration gate verified live. Status → review. |
