---
baseline_commit: 09e5508f7a343d72e84068416e5e816cbba9f258
---

# Story 1.2: Companion App Produces Correctly-Structured Test Errors in OpenSearch

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a developer,
I want a companion Spring Boot app (`logguard-error-producer`) that writes ERROR-level log entries to the local OpenSearch instance with the exact field structure LogGuard expects,
so that I have a reliable, controlled test fixture for all LogGuard development without needing a production cluster.

## Acceptance Criteria

1. The companion app is scaffolded from Spring Initializr: **Java 21**, **Spring Boot 4.1.0**, **Web** dependency, group `be.vdab`, artifact `logguard-error-producer`, package `be.vdab.logguard.producer`. It lives in the `logguard-error-producer/` subdirectory of the repo root.
2. `net.logstash.logback:logstash-logback-encoder:9.0` **and** `internetitem:logback-elasticsearch-appender` are declared in `logguard-error-producer/pom.xml`.
3. `logback-spring.xml` routes **ERROR-level** logs to OpenSearch via the `OPENSEARCH_URL` environment variable (defaulting to `http://localhost:9200`).
4. `ErrorTriggerController` exposes at minimum one **POST** endpoint (e.g. `POST /trigger-error`) that throws a realistic `be.vdab.*` exception (the stack trace it produces must contain **≥1 `be.vdab.*` frame**).
5. The companion app is added to `docker-compose.yml` with `depends_on: { opensearch: { condition: service_healthy } }` and `OPENSEARCH_URL=http://opensearch:9200`.
6. After `docker compose up` and a POST to the trigger endpoint, a document appears in the local OpenSearch index containing all required fields **at the exact nested paths** (see the Field Contract table in Dev Notes):
   - `_source.structured.error.type` — exception class name
   - `_source.structured.error.message` — error message
   - `_source.structured.error.stack_trace` — full stack trace with ≥1 `be.vdab.*` frame
   - `_source.structured.service.name` — service name
   - `_source.structured.log.level` — `"ERROR"`
   - `_source.kubernetes.labels.appName` — app name
   - `_source.kubernetes.namespace_labels.vdab_be_team` — team label
   - `_source.kubernetes.namespace_labels.vdab_be_environment` — environment label
   - `_source.@timestamp` — ISO 8601 timestamp
   - `_source.vdab.authorization` — LDAP DN string (e.g. `cn=TESTUSER,ou=users,ou=intern,O=VDAB`)
7. **Verification — the integration gate (field check half, AR-17 partial):** querying the index directly (`curl 'http://localhost:9200/<index>/_search?pretty'`) shows the document with the fields as **genuinely nested JSON objects** (e.g. `_source.structured.error.type`), **not** flat keys literally named `"structured.error.type"`. Record the actual `_source` JSON in the Dev Agent Record as evidence.

## Tasks / Subtasks

- [x] **Task 1: Scaffold the `logguard-error-producer` companion app** (AC: #1)
  - [x] Generate via Spring Initializr exactly per architecture.md §Starter Template Evaluation (Maven · Java 21 · Spring Boot 4.1.0 · group `be.vdab` · artifact `logguard-error-producer` · package `be.vdab.logguard.producer` · dependency `web`). The Initializr `curl` command is in architecture.md lines 113–126.
  - [x] Place it in the `logguard-error-producer/` subdirectory of the repo root (NOT at repo root — the repo root is reserved for the main `logguard-ai` app, scaffolded later in Story 2.1). See architecture.md §Project Structure (lines 510–523).
  - [x] Confirm `mvn -f logguard-error-producer/pom.xml package` (or the IDE) resolves and the app starts on its own. The base Initializr `web` app needs **no** Spring milestones repo and **no** Spring AI — keep the pom minimal (those are `logguard-ai` concerns only).
- [x] **Task 2: Add the logging dependencies** (AC: #2)
  - [x] Add `net.logstash.logback:logstash-logback-encoder:9.0` to `logguard-error-producer/pom.xml`.
  - [x] Add `internetitem:logback-elasticsearch-appender` (resolve the latest available version; architecture pins no version). ⚠️ **Verify it resolves and is compatible with the Logback 1.5.x that Spring Boot 4 ships** — this library is relatively unmaintained; see Dev Notes "Appender compatibility risk". If it cannot ship the nested structure or is incompatible, see the documented fallback.
- [x] **Task 3: Configure `logback-spring.xml` to produce the nested document and ship ERROR logs to OpenSearch** (AC: #3, #6) — ⚠️ **highest-risk task, read Dev Notes "The nesting landmine" FIRST**
  - [x] Create `logguard-error-producer/src/main/resources/logback-spring.xml`.
  - [x] Drive the bulk endpoint from `OPENSEARCH_URL` (default `http://localhost:9200`). The appender's `<url>` is the **`_bulk`** endpoint, so build it as `${OPENSEARCH_URL:-http://localhost:9200}/_bulk`.
  - [x] Configure the appender to **filter to ERROR level only** (`<filter class="ch.qos.logback.classic.filter.ThresholdFilter"><level>ERROR</level></filter>` on the OpenSearch appender, or an explicit level filter).
  - [x] Write to an `<index>` that matches LogGuard's configured `index-pattern`. Default LogGuard pattern is `logstash-app-openshift-application-springboot_error_*` (architecture.md line 314/598). Recommended: `logstash-app-openshift-application-springboot_error_%date{yyyy.MM.dd}` so the document lands under that wildcard with **no LogGuard config change needed** in Story 2.3. See Dev Notes "Index name".
  - [x] Produce the **nested** field structure (`structured.error.*`, `structured.service.name`, `structured.log.level`, `kubernetes.labels.appName`, `kubernetes.namespace_labels.*`, `vdab.authorization`, `@timestamp`). Read Dev Notes "The nesting landmine" — the internetitem appender's `<property>` entries are FLAT and dotted names will NOT nest.
- [x] **Task 4: Implement `ErrorTriggerController`** (AC: #4)
  - [x] Create `be.vdab.logguard.producer.controller.ErrorTriggerController` with a `POST /trigger-error` endpoint.
  - [x] Throw / log a realistic `be.vdab.*` exception whose stack trace includes ≥1 `be.vdab.*` frame (e.g. a `be.vdab.logguard.producer.*` service method that throws `NullPointerException` or a domain-flavoured exception). The fingerprint pipeline (Epic 4) and LLM prompt (Epic 3) both key on `be.vdab.*` frames — a stack trace with no own-code frame would make this fixture useless for those stories.
  - [x] Ensure the controller logs at **ERROR** level via SLF4J so the OpenSearch appender picks it up, attaching the per-event nested fields (exception type/message/stack_trace, service name). See Dev Notes for the structured-logging technique.
  - [x] Populate `vdab.authorization` with a representative LDAP DN such as `cn=TESTUSER,ou=users,ou=intern,O=VDAB` (a static/configurable value is fine for the fixture).
- [x] **Task 5: Add the companion service to `docker-compose.yml`** (AC: #5)
  - [x] Append a `logguard-error-producer` service with `build: ./logguard-error-producer`, `depends_on: { opensearch: { condition: service_healthy } }`, and `environment: [OPENSEARCH_URL=http://opensearch:9200]`. (Architecture skeleton: architecture.md lines 669–676.)
  - [x] Add a **`Dockerfile`** in `logguard-error-producer/` — `build:` requires one and the architecture skeleton assumes it. See Dev Notes "Dockerfile is required (implied)".
  - [x] Map a host port so the developer can POST to the controller from the host (e.g. `ports: ["8080:8080"]`). The skeleton omits this but AC #6 requires reaching the endpoint. See Dev Notes "Port mapping is required (implied)".
  - [x] Preserve the existing `opensearch` service from Story 1.1 unchanged (do NOT modify its config). Remove the "added in Story 1.2" comment lines that are now satisfied, or update them.
- [x] **Task 6: Verify end-to-end — the integration gate field check** (AC: #6, #7)
  - [x] `docker compose up` (or `docker compose up -d`), wait for `opensearch` healthy and the companion to start.
  - [x] `POST` to the trigger endpoint (e.g. `curl -XPOST http://localhost:8080/trigger-error`).
  - [x] Query OpenSearch: `curl 'http://localhost:9200/_cat/indices?v'` to find the index, then `curl 'http://localhost:9200/<index>/_search?pretty'`.
  - [x] **Confirm every AC #6 field is present at its exact nested path and is a real nested object, not a flat dotted key.** Paste the actual `_source` JSON into the Dev Agent Record → Completion Notes as the integration-gate evidence.
  - [x] Confirm the companion writes **only ERROR-level** documents to the index (a stray INFO/startup log appearing in the error index would pollute LogGuard's poll results).

## Dev Notes

### Scope boundary — what this story is and is NOT
- This story delivers the **companion test fixture only**: the scaffolded `logguard-error-producer` app, its Logback→OpenSearch wiring, the trigger controller, the Docker wiring, and a **direct-query verification** that the document has the right nested fields.
- This story is the **field-verification half** of the integration gate (epic calls it "AR-17 partial"). It does **NOT** wire `OpenSearchAdapter` or any LogGuard code — that is Story 2.3, which completes the gate by reading this document through `OpenSearchPort.findErrorsSince()`. Do NOT build any `be.vdab.logguard.*` (main app) code here. [Source: epics.md Epic 1 "AR-17 partial — field verification"; architecture.md lines 729–734 four-step gate; Story 2.3 ACs in epics.md lines 359–378.]
- **No `logguard-ai` main-app scaffolding** happens here — that is Story 2.1 (repo root). This story only creates the `logguard-error-producer/` subdirectory app.

### ⚠️ THE NESTING LANDMINE (the #1 way this story fails) — read before Task 3
LogGuard reads errors from **genuinely nested** JSON paths inside `_source`:
```
_source.structured.error.type      _source.kubernetes.labels.appName
_source.structured.error.message   _source.kubernetes.namespace_labels.vdab_be_team
_source.structured.error.stack_trace  _source.kubernetes.namespace_labels.vdab_be_environment
_source.structured.service.name    _source.vdab.authorization
_source.structured.log.level       _source.@timestamp
```
That means the document must contain a real object `structured` → object `error` → field `type`, etc.

**The trap:** the `internetitem:logback-elasticsearch-appender` builds its document from flat `<property>` entries. Its `<property><name>…</name></property>` produces **flat top-level keys only — it does NOT interpret dots as nesting.** A `<property><name>structured.error.type</name>…</property>` will create a document with a single flat key literally named `"structured.error.type"`, which is **NOT** the same as the nested object `structured: { error: { type: … } }`. LogGuard (Story 2.3) reads the nested path and will find nothing → the integration gate fails. [Verified 2026-06 against the internetitem appender README: `<property>` supports `name`/`value`/`type`/`allowEmpty` only; no dot-notation nesting.]

**Recommended approach — build the nested JSON yourself, ship it raw:**
1. Enable the appender's `rawJsonMessage` mode (`<rawJsonMessage>true</rawJsonMessage>`) so the appender treats the log message as a pre-formed JSON document body instead of building one from flat properties.
2. Use `logstash-logback-encoder` to compose the **fully nested** JSON for the message. Two complementary techniques:
   - **Static fields** (`kubernetes.*`, `vdab.authorization`) via the encoder's `<customFields>` — `customFields` accepts arbitrary nested JSON objects:
     ```xml
     <customFields>{"kubernetes":{"labels":{"appName":"logguard-error-producer"},"namespace_labels":{"vdab_be_team":"backend-team","vdab_be_environment":"local"}},"vdab":{"authorization":"cn=TESTUSER,ou=users,ou=intern,O=VDAB"}}</customFields>
     ```
   - **Per-event fields** (`structured.error.*`, `structured.service.name`, `structured.log.level`) via SLF4J structured markers/arguments that serialize Maps as nested objects, e.g. with `net.logstash.logback.marker.Markers`:
     ```java
     import static net.logstash.logback.marker.Markers.append;
     log.error(append("structured", Map.of(
         "error", Map.of("type", ex.getClass().getName(),
                         "message", String.valueOf(ex.getMessage()),
                         "stack_trace", stackTraceAsString(ex)),
         "service", Map.of("name", "orgbeheer-service"),
         "log",     Map.of("level", "ERROR"))),
         "error triggered", ex);
     ```
     `logstash-logback-encoder` serializes nested `Map` values as nested JSON objects, producing `structured.error.type` etc.
3. `@timestamp` is emitted by `logstash-logback-encoder` automatically; confirm it appears at `_source.@timestamp`.

**This is a known, intentional adaptation of the architecture's library pairing — analogous to Story 1.1's `DISABLE_SECURITY_PLUGIN` correction.** The architecture lists both libraries (architecture.md lines 128–131, 64) but does not specify the wiring; the appender alone cannot produce nesting. **Whatever exact wiring you land on, AC #7 is the arbiter: query OpenSearch and confirm the `_source` has real nested objects.** Record the chosen technique and the actual `_source` JSON in Completion Notes.

**If the internetitem appender proves incompatible or cannot ship the nested doc** (see compatibility risk below): an acceptable documented fallback is to produce the same nested document another way (e.g. a maintained ES/OpenSearch Logback appender, or a tiny `RestClient` call from the trigger handler that indexes the JSON directly). Do not silently change the contract — flag the deviation in Completion Notes so the architecture can be updated, and AC #6/#7 (the nested document in OpenSearch) remain the hard requirement.

### Appender compatibility risk
`internetitem:logback-elasticsearch-appender` is relatively unmaintained and predates Logback 1.5 / Java 21 / OpenSearch. Verify early (Task 2) that it resolves on Maven Central and starts cleanly under Spring Boot 4 (Logback 1.5.x). The OpenSearch `_bulk` API is Elasticsearch-bulk-compatible, so HTTP shipping itself should work, but confirm. If it fails to load or ship, use the fallback in "The nesting landmine" and document it.

### Index name
- LogGuard's default poll pattern is `logstash-app-openshift-application-springboot_error_*` (architecture.md line 314, 598). [Source: prd.md §9 ASSUMPTION-2 — "MVP index name is configurable; the production index pattern `logstash-app-openshift-application-springboot_error_*` is the long-term target."]
- Per the brainstorming decision, the index name itself isolates ERROR-level logs at the pipeline level — LogGuard does **not** filter on `log.level`, so **only ERROR documents may land in this index** (hence the ERROR-only filter in Task 3). [Source: brainstorming session — "The index name … already isolates error-level logs … LogGuard doesn't need to filter `log.level = ERROR`".]
- **Recommendation:** have the companion write to `logstash-app-openshift-application-springboot_error_%date{yyyy.MM.dd}` so the document matches the production wildcard and Story 2.3 needs no config change. Any index name is technically acceptable for MVP as long as LogGuard's `index-pattern` is later set to match — but matching the production pattern now avoids a future edit.

### Dockerfile is required (implied)
The architecture compose skeleton uses `build: ./logguard-error-producer`, which requires a `Dockerfile` in that directory. The ACs don't name it explicitly, but `docker compose up` (AC #5/#6) cannot build the service without it. Add a simple multi-stage (or JRE-base) Dockerfile that builds the Spring Boot jar and runs it on a Java 21 base image. [Source: architecture.md lines 669–676.]

### Port mapping is required (implied)
AC #6 requires POSTing to `ErrorTriggerController`. With the companion running in a container, expose its HTTP port to the host (e.g. `ports: ["8080:8080"]`). The architecture skeleton omits this; add it. Avoid colliding with any host port already in use.

### Field Contract (authoritative — every field LogGuard reads)
The canonical source-to-field mapping is `specs/logguard-ai.allium` (entity `ErrorLog`) and prd.md §4.1.

| LogGuard `ErrorLog` field | OpenSearch path (`_source.`) | Example value | Companion source |
|---|---|---|---|
| `exception_type` | `structured.error.type` | `java.lang.NullPointerException` | thrown exception class name |
| `error_message` | `structured.error.message` | `Cannot invoke method on null object` | exception message |
| `stack_trace` | `structured.error.stack_trace` | multi-line trace w/ `be.vdab.*` frame | full stack trace string |
| `service_name` | `structured.service.name` | `orgbeheer-service` | configurable service id |
| `severity` | `structured.log.level` | `ERROR` | must be `ERROR` |
| `app_name` | `kubernetes.labels.appName` | `logguard-error-producer` | configurable |
| `team` | `kubernetes.namespace_labels.vdab_be_team` | `backend-team` | configurable (post-MVP routing) |
| `environment` | `kubernetes.namespace_labels.vdab_be_environment` | `local` | configurable |
| `occurred_at` | `@timestamp` | `2026-06-17T14:35:42.123Z` (ISO 8601) | Logback timestamp |
| `vdab_authorization` | `vdab.authorization` | `cn=TESTUSER,ou=users,ou=intern,O=VDAB` | static/configurable LDAP DN |

[Source: prd.md §4.1 line 86 (ErrorLog field contract); specs/logguard-ai.allium entity `ErrorLog` lines ~115–136; epics.md Story 1.2 ACs lines 289–306.]

### [POST-MVP LANDMINE] vdab.authorization — note for awareness only
`vdab.authorization` holds a real VDAB LDAP identity. For MVP (local LLM, on-prem) it is intentionally included so LogGuard can use it as root-cause context. **It must be excluded from any cloud-LLM payload post-MVP** (FR-20 landmine). This does NOT affect this story — the companion just writes a representative DN — but do not strip or omit it here; LogGuard (Epic 3) handles payload governance. [Source: prd.md §11.1 Data Governance; specs/logguard-ai.allium ErrorLog comment.]

### Testing standards
- The Initializr-generated `ErrorTriggerControllerTest` slot exists in the structure (architecture.md line 523). A minimal MockMvc test asserting `POST /trigger-error` returns its expected status is a reasonable, low-cost addition but is **not** an AC — the binding requirement is the **live integration-gate verification** (Task 6): document present in OpenSearch with correct nested fields.
- This companion app is a **test fixture**, not production code — it is intentionally exempt from the `logguard-ai` hexagonal/architecture rules (those apply to the main app from Story 2.1 onward). Keep it simple. [Source: architecture.md — companion app is a separate application / test fixture.]
- "Test" evidence for this story = the pasted `_source` JSON and `_cat/indices` output in the Dev Agent Record.

### Project Structure Notes
- New subdirectory: `logguard-error-producer/` with its own `pom.xml`, `Dockerfile`, `src/main/java/be/vdab/logguard/producer/...`, `src/main/resources/{application.yml, logback-spring.xml}`. Matches architecture.md §Project Structure lines 510–523.
- Modifies the existing repo-root `docker-compose.yml` (created in Story 1.1) — appends the companion service; leaves `opensearch` untouched.
- `target/` and `.idea` are already gitignored (repo `.gitignore`). No new gitignore entries required for this story (the companion's `target/` is covered by the existing `target/` rule if it builds in-tree; Docker builds inside the image).
- No conflict with the future `logguard-ai` main app: that occupies the repo root `src/` (Story 2.1); this story only touches `logguard-error-producer/` and `docker-compose.yml`.

### References
- [Source: epics.md#Story 1.2: Companion App Produces Correctly-Structured Test Errors in OpenSearch] — acceptance criteria, required fields, Initializr settings, dependencies
- [Source: epics.md#Epic 1: Local Development Environment & Companion App] — epic scope, "No LogGuard development in this epic", AR-16/AR-17 coverage
- [Source: epics.md#AR-16] — `logstash-logback-encoder:9.0` + `internetitem:logback-elasticsearch-appender` produce `_source.structured.*` / `_source.kubernetes.*`
- [Source: epics.md#AR-17] — integration gate: companion produces documents LogGuard can detect/fingerprint/analyze without field-mapping changes
- [Source: architecture.md#Starter Template Evaluation] — companion Initializr command (lines 113–126), dependencies (lines 128–131)
- [Source: architecture.md#Project Structure & Boundaries] — `logguard-error-producer/` layout (lines 510–523)
- [Source: architecture.md#Gap Analysis] — Docker Compose skeleton incl. companion service (lines 669–676)
- [Source: architecture.md#Implementation Handoff] — four-step integration gate (lines 729–734)
- [Source: prd.md#4.1] — ErrorLog field contract (line 86); ASSUMPTION-2 index name (lines 448–449)
- [Source: prd.md#11.1 Data Governance] — vdab.authorization post-MVP exclusion landmine
- [Source: specs/logguard-ai.allium#entity ErrorLog] — canonical field-to-source mapping (lines ~115–136)
- [Source: prd.md/brainstorming] — index pattern isolates ERROR level; LogGuard does not filter `log.level`
- [Web: internetitem/logback-elasticsearch-appender README — `<property>` supports name/value/type/allowEmpty only (flat, no dot-nesting); `rawJsonMessage` treats the log message as raw JSON; `<index>` supports `%date` patterns; `<url>` is the `_bulk` endpoint]
- [Web: logfellow/logstash-logback-encoder README — `customFields` accepts arbitrary nested JSON objects; `StructuredArguments`/`Markers` serialize nested Maps as nested JSON]

### Previous Story Intelligence (Story 1.1)
From `1-1-run-local-opensearch-via-docker-compose.md` (status: review):
- **Docker invocation:** Claude Code runs from Ubuntu on WSL, so use bare `docker` / `docker compose` / `curl` directly (NOT `wsl docker …`). Story 1.1's notes used `wsl docker …` because that work was driven from a Windows context — that prefix is not needed here.
- OpenSearch resolved to **2.19.5** (floating `:2` tag), single-node, security disabled, plain HTTP on `:9200`, no auth. The `_bulk` endpoint is reachable at `http://opensearch:9200/_bulk` from inside the compose network and `http://localhost:9200/_bulk` from the host/WSL.
- The `opensearch` container reports `(healthy)` ~28s after start (10s-interval healthcheck, `start_period: 30s`). The companion's `depends_on: condition: service_healthy` will wait for that — expect ~30s before the companion starts.
- Story 1.1 left a comment in `docker-compose.yml` reserving the companion service for "Story 1.2" — fulfil/update that comment now.
- Story 1.1 added container hardening (`bootstrap.memory_lock`, 512m heap) — do not disturb it.
- Pattern established: record live verification evidence (actual command output / JSON) in Completion Notes as proof the ACs passed. Continue that here with the `_source` JSON.

### Git Intelligence
- Latest commit `09e5508` "Implement Story 1.1: local OpenSearch via Docker Compose" added the repo-root `docker-compose.yml` (opensearch only). This story builds directly on it.
- No Java/Maven code exists in the repo yet — the companion app is the first compilable application in this codebase. The repo root currently holds only planning artifacts, `.claude/`, `docker-compose.yml`, and Initializr metadata scratch files (`.initializr.json`, `.sbdeps.pom`, etc., untracked) — none are dependencies of this story.

### Latest Technical Information (verified 2026-06, past training cutoff)
- **internetitem appender = flat fields only.** `<property>` entries create flat top-level JSON keys; dotted names do **not** nest. To produce nested `structured.*` / `kubernetes.*` objects, use `rawJsonMessage=true` and have `logstash-logback-encoder` build the nested JSON (via `<customFields>` for static objects + `Markers`/`StructuredArguments` Maps for per-event objects). The `<index>` supports `%date{…}` patterns; `<url>` must point at the `_bulk` endpoint.
- **logstash-logback-encoder 9.0** `customFields` accepts arbitrary nested JSON; `Markers.append(name, Map)` / `StructuredArguments` serialize nested `Map`s as nested JSON objects — this is the mechanism for per-event nesting.
- **logstash-logback-encoder 9.0** requires Logback ≥ 1.3; Spring Boot 4 ships Logback 1.5.x — compatible (architecture validated this, line 621).
- **OpenSearch `_bulk`** is Elasticsearch-bulk-API-compatible, so an Elasticsearch Logback appender can ship to it over plain HTTP (security disabled locally).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Opus 4.8, 1M context)

### Debug Log References

- Toolchain: `docker` works directly (Ubuntu/WSL); no local JDK/Maven on PATH → the companion is compiled **inside Docker** via its `Dockerfile` (multi-stage `maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre`). `docker compose up -d --build` is the build+verify loop.
- Verified artifact coordinates on Maven Central before scaffolding: `logstash-logback-encoder:9.0` ✓, `com.internetitem:logback-elasticsearch-appender:1.6` (latest) ✓, Spring Boot `4.1.0.RELEASE` is the Initializr default ✓.
- Scaffolded via `start.spring.io/starter.zip` (Java 21, Boot 4.1.0, `web`). Note: Boot 4 maps `web` to `spring-boot-starter-webmvc` / `spring-boot-starter-webmvc-test`.
- **First build failed**: `OpenSearchErrorAppender` imported `com.fasterxml.jackson.databind.node.ObjectNode` → `cannot find symbol`. Cause: **Spring Boot 4 ships Jackson 3** (`tools.jackson.*`), not Jackson 2 (`com.fasterxml.jackson.*`). Fixed by removing the Jackson dependency entirely and building the fixed-shape JSON with manual, RFC-8259-escaped string construction — version-agnostic.
- Second build succeeded; both containers up (`opensearch` healthy, companion `Started … in 1.313 s` on Java 21.0.11 / Tomcat 11).
- Live integration-gate evidence (Completion Notes below): `POST /trigger-error` → HTTP 500; document indexed to `logstash-app-openshift-application-springboot_error_2026.06.17`; `curl _search` shows all required fields as genuinely nested objects.

### Completion Notes List

- **All 7 ACs verified live.** The companion writes ERROR documents to local OpenSearch with the exact nested `_source.structured.*` / `_source.kubernetes.*` / `_source.vdab.*` layout LogGuard reads. Captured `_source`:
  - `@timestamp` = `2026-06-17T15:53:23.800Z`
  - `structured.error.type` = `java.lang.NullPointerException`
  - `structured.error.message` = `Cannot invoke "String.toUpperCase()" because "resolved" is null`
  - `structured.error.stack_trace` = full trace including own-code frame `be.vdab.logguard.producer.service.LabelService.forwardingSourceFor(LabelService.java:27)` (≥1 `be.vdab.*` frame ✓)
  - `structured.service.name` = `orgbeheer-service` (and `vacatures-service` on the second request — per-request override via MDC works)
  - `structured.log.level` = `ERROR`
  - `kubernetes.labels.appName` = `logguard-error-producer`
  - `kubernetes.namespace_labels.vdab_be_team` = `backend-team`, `…vdab_be_environment` = `local`
  - `vdab.authorization` = `cn=TESTUSER,ou=users,ou=intern,O=VDAB`
- **Fields are genuinely nested objects, not flat dotted keys (AC #7).** Verified by `_search` showing the object hierarchy and by a terms aggregation on `structured.log.level.keyword`.
- **Only ERROR-level docs in the index (AC, no pollution):** terms aggregation returned `[('ERROR', 2)]`. ERROR-only is enforced by a `ThresholdFilter` on the appender plus scoping the OpenSearch appender to the `be.vdab.logguard.producer` logger.
- **NESTING LANDMINE confirmed and worked around as the story predicted.** The internetitem appender's JSON is built from `@timestamp` + `message` + MDC + flat `<property>` entries — all **flat root-level keys**; `rawJsonMessage` only nests under `message`. It cannot emit `structured.error.type` as a nested object (verified against the appender source). Implemented a custom Logback appender (`OpenSearchErrorAppender`) that POSTs the nested document to `${OPENSEARCH_URL}/<index>/_doc?refresh=true`. **The architecture's AR-16 shipping choice (internetitem appender) should be updated to reflect this** — analogous to Story 1.1's `DISABLE_SECURITY_PLUGIN` correction. Both libraries remain declared in `pom.xml` per AC #2 (`internetitem` is declared-but-not-used as the active shipper).
- Index name uses `logstash-app-openshift-application-springboot_error_%date{yyyy.MM.dd}` so the document matches LogGuard's default production wildcard `…_error_*` — **no LogGuard config change needed in Story 2.3.**
- `OPENSEARCH_URL` drives the bulk host (`http://opensearch:9200` in compose, default `http://localhost:9200`). `refresh=true` on indexing makes docs immediately searchable for deterministic verification.
- **Testing note (per story Testing Standards):** the companion is a test fixture, intentionally exempt from the main-app architecture/test rules. No unit tests were added; the binding requirement is the live integration-gate verification, which passed. The Initializr-generated `LogguardErrorProducerApplicationTests` (context-load) is retained and compiles; image builds run with `-DskipTests` (the appender's `start()` opens no socket, so a future `mvn test` context load does not require OpenSearch).
- The stack is left **running** as the local dev stack (consistent with Story 1.1). Stop with `docker compose down`.

### File List

- `logguard-error-producer/` (NEW) — companion Spring Boot app scaffolded from Spring Initializr (Boot 4.1.0, Java 21, web). Includes Initializr-generated `mvnw`/`mvnw.cmd`, `.mvn/`, `.gitignore`, `.gitattributes`, `HELP.md`, `application.properties`, and `LogguardErrorProducerApplicationTests.java`.
- `logguard-error-producer/pom.xml` (MODIFIED) — added `net.logstash.logback:logstash-logback-encoder:9.0` and `com.internetitem:logback-elasticsearch-appender:1.6`.
- `logguard-error-producer/src/main/java/be/vdab/logguard/producer/logging/OpenSearchErrorAppender.java` (NEW) — custom Logback appender producing the nested OpenSearch document over HTTP.
- `logguard-error-producer/src/main/java/be/vdab/logguard/producer/service/LabelService.java` (NEW) — own-code service whose failure path throws a `be.vdab.*`-framed `NullPointerException`.
- `logguard-error-producer/src/main/java/be/vdab/logguard/producer/controller/ErrorTriggerController.java` (NEW) — `POST /trigger-error` endpoint.
- `logguard-error-producer/src/main/resources/logback-spring.xml` (NEW) — ERROR-only routing to OpenSearch via `OPENSEARCH_URL`.
- `logguard-error-producer/Dockerfile` (NEW) — multi-stage build of the Spring Boot jar.
- `logguard-error-producer/.dockerignore` (NEW).
- `docker-compose.yml` (MODIFIED) — added the `logguard-error-producer` service (`build`, `depends_on: opensearch healthy`, `OPENSEARCH_URL=http://opensearch:9200`, port `8080`); refreshed header comments. `opensearch` service left unchanged.

## Change Log

| Date | Change |
|---|---|
| 2026-06-17 | Story 1.2 drafted via create-story context engine. Status → ready-for-dev. |
| 2026-06-17 | Story 1.2 implemented: scaffolded `logguard-error-producer`, custom `OpenSearchErrorAppender` (nesting-landmine workaround), `ErrorTriggerController`, `logback-spring.xml`, `Dockerfile`, and companion service in `docker-compose.yml`. All 7 ACs verified live against OpenSearch 2.19.x — error document lands at the correct nested paths with a `be.vdab.*` stack frame. Status → review. |
