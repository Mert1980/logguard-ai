---
baseline_commit: 09e5508f7a343d72e84068416e5e816cbba9f258
---

# Story 2.1: Scaffold logguard-ai and Configure Project Infrastructure

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a developer,
I want the `logguard-ai` application scaffolded with all infrastructure configured and the application starting cleanly,
so that the project is ready for feature development with a consistent hexagonal structure, working persistence layer, and all required dependencies in place.

## Acceptance Criteria

1. Running `mvn spring-boot:run` (native host toolchain via SDKMAN — see Dev Notes "Build/run") in the repo root starts the application **without errors**.
2. H2 **file-based** persistence initializes at `./data/logguard` (`jdbc:h2:file:./data/logguard`).
3. Flyway runs and reports **"Successfully applied 0 migrations"** (the migration folder exists but is empty; `V1`/`V2` are added in Stories 2.2 and 4.x).
4. `LogguardProperties` binds correctly from `application.yml` with **all** config fields present and defaulted (see the field list in Dev Notes).
5. The full hexagonal package skeleton exists: `domain/model/`, `domain/port/in/`, `domain/port/out/`, `domain/service/`, `infrastructure/opensearch/`, `infrastructure/llm/`, `infrastructure/persistence/`, `infrastructure/filesystem/`, `infrastructure/terminal/`, `infrastructure/config/`.
6. Virtual threads are enabled (`spring.threads.virtual.enabled: true` in `application.yml`).
7. **No Spring milestones repository is declared** (intentional update to AR-2). Spring AI `2.0.0` is GA on Maven Central, so the milestone repo from the original AC is dropped per METIS decision — the build resolves entirely from Maven Central.
8. The Spring AI BOM is declared in `<dependencyManagement>`. **Use `spring-ai-bom:2.0.0` (GA, on Maven Central)** rather than the architecture's original `2.0.0-M4` — see Dev Notes.
9. The `opensearch-java` client dependency is present in `pom.xml` (recommended version `3.9.0` — latest stable; architecture leaves it unpinned).

## Tasks / Subtasks

- [x] **Task 1: Scaffold `logguard-ai` from Spring Initializr INTO THE REPO ROOT** (AC: #1) — ⚠️ read Dev Notes "Repo-root scaffolding landmine" FIRST
  - [x] Generate the project with the architecture's Initializr settings: Maven · Java 21 · Spring Boot 4.1.0 · group `be.vdab` · artifact `logguard-ai` · name `logguard-ai` · package `be.vdab.logguard` · dependencies `data-jpa,h2,spring-shell,flyway,actuator`. (All six dependency ids are valid on start.spring.io for Boot 4.1.0; `4.1.0` resolves to `4.1.0.RELEASE`.) The exact `curl` command is in architecture.md lines 89–102.
  - [x] Generate to a **temp location**, then move `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/`, `src/`, `HELP.md`, `.gitattributes` into the repo root. The repo root **IS** the main app (architecture.md §Project Structure line 423). Do **NOT** create a `logguard-ai/` subfolder.
  - [x] **Do not overwrite** the existing repo-root `docker-compose.yml` (Story 1.1/1.2) or the `logguard-error-producer/` subdirectory (Story 1.2). Merge — never clobber — the existing root `.gitignore` (see Task 6).
  - [x] Confirm the generated `pom.xml` uses `spring-boot-starter-parent:4.1.0` as parent and does **not** declare `logguard-error-producer` as a Maven module (the two apps are independent — architecture.md line 169).
- [x] **Task 2: Add manual dependencies + repositories to `pom.xml`** (AC: #7, #8, #9)
  - [x] Add `<dependencyManagement>` importing the Spring AI BOM: `org.springframework.ai:spring-ai-bom:2.0.0` (GA). See Dev Notes "Spring AI is GA now" — do **not** use `2.0.0-M4`.
  - [x] Add `org.opensearch.client:opensearch-java:3.9.0` as a dependency.
  - [x] **Do NOT add the Spring milestones repository** (METIS decision: GA from Maven Central). Leave `pom.xml` with no extra `<repositories>`. Note the AR-2/AR-3 correction in Completion Notes for an architecture-doc update.
  - [x] Do **not** add the Ollama starter (`spring-ai-starter-model-ollama`) here — that is Story 3.2. The BOM in this story manages versions only; no `spring-ai-*` artifact is pulled yet.
  - [x] Confirm the build resolves (Docker Maven build, see Dev Notes). The BOM import + `opensearch-java` must download cleanly.
- [x] **Task 3: Create the hexagonal package skeleton** (AC: #5) — keep `domain/` Spring-free
  - [x] Create the package directories under `src/main/java/be/vdab/logguard/`: `domain/model/`, `domain/port/in/`, `domain/port/out/`, `domain/service/`, `infrastructure/opensearch/`, `infrastructure/llm/`, `infrastructure/persistence/`, `infrastructure/filesystem/`, `infrastructure/terminal/`, `infrastructure/config/`.
  - [x] Materialize each empty package with a `package-info.java` (git does not track empty dirs; `package-info.java` documents the layer and keeps the skeleton in version control). Briefly note each layer's responsibility and the "`domain/` has zero Spring annotations" invariant in the domain package-infos.
  - [x] Keep `LogguardAiApplication.java` at `be.vdab.logguard` (Initializr default). Add `@EnableScheduling` and `@ConfigurationPropertiesScan` (the scheduler bean itself arrives in Story 2.5; enabling now is harmless). [Source: architecture.md line 439, AR-12.]
- [x] **Task 4: Implement `LogguardProperties`** (AC: #4) — the only real "logic" in this story
  - [x] Create `be.vdab.logguard.infrastructure.config.LogguardProperties` as `@ConfigurationProperties(prefix = "logguard")` with **all** fields present and defaulted (full field list in Dev Notes "LogguardProperties contract"). Use a record or class with nested records for `ollama`, `opensearch`, `h2`.
  - [x] Bind via `@ConfigurationPropertiesScan` on the application class (preferred) or `@EnableConfigurationProperties(LogguardProperties.class)`. Do **NOT** use `@Value` anywhere (AR-11).
  - [x] Use kebab-case keys under `logguard.*` in `application.yml`; types: `Duration` for `poll-interval`/`deduplication-window`/`ollama.timeout`, `List<Integer>` for `escalation-thresholds`, `List<String>` for `own-code-package-prefixes`.
- [x] **Task 5: Author `application.yml`** (AC: #2, #3, #4, #6)
  - [x] Create `src/main/resources/application.yml` from the architecture skeleton (architecture.md lines 576–599): `spring.threads.virtual.enabled: true`, H2 file datasource `jdbc:h2:file:${logguard.h2.data-dir:./data}/logguard`, `spring.jpa.hibernate.ddl-auto: validate`, and the full `logguard.*` block.
  - [x] Set Flyway to manage schema (no auto-DDL): keep `ddl-auto: validate`. The `db/migration/` folder exists but is empty in this story → Flyway must log "Successfully applied 0 migrations".
  - [x] **Defer** `spring.ai.ollama.*` properties to Story 3.2 (the Ollama autoconfig/starter is not on the classpath yet; adding them now is harmless but unused — omit to avoid confusion, or include commented-out).
- [x] **Task 6: Repo hygiene — Flyway dir, `./data/` gitignore, `.gitignore` merge** (AC: #2, #3)
  - [x] Create the empty `src/main/resources/db/migration/` directory (add a `.gitkeep` so it is tracked; remove the placeholder when `V1` lands in Story 2.2).
  - [x] Add `data/` (or `/data/`) to the repo-root `.gitignore` — AR-5 requires the H2 runtime directory be gitignored. **Preserve the existing entries** (`target/`, `.vscode/`, `.idea`, `_bmad/config.user.toml`); append, don't replace.
  - [x] Reconcile the Initializr-generated `.gitignore` (standard Maven ignores) with the existing root one — keep both sets, no duplicates, no lost entries.
- [x] **Task 7: Verify the app starts cleanly end-to-end** (AC: #1, #2, #3, #4, #6)
  - [x] Build and boot the app (Docker Maven if no host JDK — see Dev Notes). Confirm log line `Started LogguardAiApplication in N seconds` with **no** stack traces.
  - [x] Confirm H2 created `./data/logguard.mv.db` on the host (file-based persistence, AC #2).
  - [x] Confirm the Flyway log shows the schema-history table creation and **"Successfully applied 0 migrations"** (AC #3).
  - [x] Add/keep a context-load test asserting `LogguardProperties` binds with expected defaults (AC #4). Run it via the Docker Maven path.
  - [x] Record the actual startup log (Started line, Flyway line) and the `./data/` listing in the Dev Agent Record as evidence.

## Dev Notes

### ⚠️ Repo-root scaffolding landmine (the #1 way this story goes wrong)
The repository root **is** the `logguard-ai` Spring Boot app (architecture.md line 423–426) — `pom.xml`, `mvnw`, `src/` all live at the root, **not** in a `logguard-ai/` subfolder. But the root is **already populated** by earlier stories:
- `docker-compose.yml` (Story 1.1/1.2) — must be preserved untouched.
- `logguard-error-producer/` (Story 1.2) — sibling app, must remain independent (NOT a Maven module of the root pom).
- `.gitignore` (existing: `target/`, `.vscode/`, `.idea`, `_bmad/config.user.toml`) — must be **merged**, not overwritten.
- `_bmad/`, `_bmad-output/`, `specs/`, `docs/`, `.claude/` — leave alone.

**Safe procedure:** generate the Initializr zip to a temp dir (or with `baseDir=logguard-ai`), then **move** `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/`, `src/`, `HELP.md`, `.gitattributes` into the repo root. Manually merge `.gitignore`. Verify `docker-compose.yml` and `logguard-error-producer/` are intact afterward. Initializr does **not** generate a `docker-compose.yml`, so the only real collision is `.gitignore`.

### Build/run — native host toolchain via SDKMAN (RESOLVED)
METIS installed a host toolchain via **SDKMAN**: **Java 21.0.11 (Temurin)** and **Maven 3.9.16**. This matches the architecture's "daemon runs on host" model — build and run natively with `mvn spring-boot:run` (or `./mvnw spring-boot:run`). No Docker build is needed for the main app.
- **SDKMAN gotcha for tooling/non-interactive shells:** SDKMAN is loaded from `~/.bashrc` (interactive shells only). A non-interactive shell (e.g. an automated tool shell) will NOT have `java`/`mvn` on PATH. Prepend `source "$HOME/.sdkman/bin/sdkman-init.sh"` (or export `JAVA_HOME=$HOME/.sdkman/candidates/java/current` and add `…/java/current/bin` + `…/maven/current/bin` to PATH) before running `mvn`/`java`. A normal interactive terminal works directly.
- AC #1 ("starts without errors") is verified by observing the `Started LogguardAiApplication` log line and a clean Flyway run.
- The H2 DB is written to `./data/logguard.mv.db` at the repo root when run natively.

### Spring AI is GA now — use 2.0.0, not M4 (intentional correction to AR-2/AR-3)
The architecture (written 2026-06-15) pinned `spring-ai-bom:2.0.0-M4` and mandated the Spring milestones repo (AR-2, AR-3) because only milestones existed then. **As of now (2026-06-17), Spring AI `2.0.0` is GA on Maven Central** (the full `2.0.0-M1…M8 → RC1/RC2 → 2.0.0` line shipped), and `spring-ai-starter-model-ollama:2.0.0` is on Central too. Implications:
- Use **`spring-ai-bom:2.0.0`** (GA) in `<dependencyManagement>`. It is stable and resolves from Maven Central with no milestone repo.
- **The Spring milestones repo is dropped** (METIS decision). GA resolves from Maven Central, so no `<repositories>` entry is added. This is a documented, beneficial correction to architecture **AR-2/AR-3** (record it in Completion Notes so the architecture doc can be updated) — the same kind of correction as Story 1.1 (`DISABLE_SECURITY_PLUGIN`) and Story 1.2 (Jackson 3 / custom appender).
- In **this** story the BOM only manages versions — no `spring-ai-*` dependency is added (the Ollama starter is Story 3.2), so the BOM version does not affect 2.1's build beyond resolving its own POM.

### LogguardProperties contract (all fields, with defaults from architecture.md lines 299–317 / 587–599)
Bind under prefix `logguard`:
| Property (kebab-case) | Type | Default |
|---|---|---|
| `poll-interval` | `Duration` | `5m` |
| `deduplication-window` | `Duration` | `24h` |
| `escalation-thresholds` | `List<Integer>` | `[10, 100, 1000]` |
| `max-consecutive-poll-failures` | `int` | `3` |
| `own-code-package-prefixes` | `List<String>` | `[be.vdab]` |
| `suppression-file-path` | `String` | `./suppression.txt` |
| `ollama.base-url` | `String` | `http://localhost:11434` |
| `ollama.model` | `String` | `llama3` |
| `ollama.timeout` | `Duration` | `30s` |
| `opensearch.base-url` | `String` | `http://localhost:9200` |
| `opensearch.index-pattern` | `String` | `logstash-app-openshift-application-springboot_error_*` |
| `h2.data-dir` | `String` | `./data` |

Note: the `opensearch.index-pattern` default already matches the index the companion app writes to (Story 1.2 verified live: `logstash-app-openshift-application-springboot_error_2026.06.17`), so Story 2.3's poll adapter will find those documents with **no config change**.

### Scope boundary — what this story is and is NOT
- This story is **infrastructure only**: scaffold, dependencies, package skeleton, `LogguardProperties`, `application.yml`, clean startup. **No business logic, no entities, no adapters, no ports, no scheduler body, no migrations.**
- `domain/model/` records (`ErrorLog`, `PollCheckpoint`, etc.), the port interfaces, `PollService`, adapters, and `V1`/`V2` migrations are **later stories** (2.2–2.6, 3.x, 4.x). Creating empty packages now is correct; populating them is not.
- Flyway has **zero** migrations in this story — that is the expected, asserted state (AC #3). Do not add a `V1` here.

### Forward risks to be aware of (do not solve here, just don't block them)
- **opensearch-java 3.x + Jackson 3:** Boot 4 ships Jackson 3 (`tools.jackson.*`); `opensearch-java` 3.9.0 brings its own Jackson 2 (`com.fasterxml.*`) databind. Both can coexist on the classpath. This matters when the client is actually wired in **Story 2.3** (field mapping), not now. Declaring the dependency in 2.1 must not break startup (it does not — it is unused until 2.3).
- **Spring Shell interactive mode:** with `spring-shell` on the classpath, the app may start an interactive shell/REPL on a TTY. For 2.1 this is not an error (the app still "starts"). The first-run prompt wiring (`FirstRunInitializer`, `ApplicationRunner`) is Story 2.2; shell behavior/headless config is handled there.
- **Flyway H2 driver module:** Flyway 10+ (managed by Boot 4) split out per-database modules. If Flyway fails to recognise H2 at startup, add `org.flywaydb:flyway-database-h2` (Boot-managed version) to `pom.xml`. Verify during Task 7; add only if needed.
- **`ddl-auto: validate` with zero entities:** there are no `@Entity` classes yet, so `validate` has nothing to check and passes. Do not switch to `none`/`create` — `validate` is the architecture's choice (AR-6, no auto-DDL).

### Testing standards
- This is a scaffold story; the natural test is a **context-load smoke test** (the Initializr-generated `LogguardAiApplicationTests` with `@SpringBootTest`) extended to assert `LogguardProperties` binds with the expected defaults. That single test exercises AC #2/#3/#4/#6 together (datasource up, Flyway ran, properties bound, context loads). [Source: architecture.md §Test organisation lines 321–329 — integration tests `@SpringBootTest` suffixed `IT`; the generated `…ApplicationTests` is the context-load smoke test.]
- Domain unit tests come later (no domain code yet). Do not add `@SpringBootTest` to anything in `domain/` — if a domain test ever needs a Spring context, that signals a dependency leak (architecture.md line 329).
- Run tests via the Docker Maven path (no host JDK). It is acceptable to verify primarily through live startup (Task 7) given the environment; record the evidence.

### Project Structure Notes
- After this story the repo root contains: `pom.xml`, `mvnw`/`mvnw.cmd`, `.mvn/`, `src/main/java/be/vdab/logguard/...`, `src/main/resources/{application.yml, db/migration/}`, `src/test/...`, alongside the pre-existing `docker-compose.yml`, `logguard-error-producer/`, `specs/`, `_bmad*/`, `docs/`.
- Target package tree to materialize (architecture.md lines 437–489): `domain/{model,port/in,port/out,service}`, `infrastructure/{opensearch,llm,persistence,filesystem,terminal,config}`.
- No conflict with `logguard-error-producer/` — it is a sibling directory, not under `src/`, so the root Maven build ignores it.

### References
- [Source: epics.md#Story 2.1: Scaffold logguard-ai and configure project infrastructure] — acceptance criteria
- [Source: epics.md#Epic 2: LogGuard App — First Working Version] — epic scope, AR coverage (AR-1, AR-2, AR-3, AR-5, AR-6, AR-7, AR-8–AR-12, AR-15)
- [Source: epics.md#AR-1] — both apps from Spring Initializr: Java 21, Spring Boot 4.1.0, Maven, group be.vdab
- [Source: epics.md#AR-2/AR-3] — Spring milestones repo + Spring AI BOM (superseded by GA — see Dev Notes)
- [Source: epics.md#AR-5/AR-6/AR-7] — H2 file persistence at ./data/logguard (gitignored); Flyway migrations, no auto-DDL; explicit @Column/@Table
- [Source: epics.md#AR-8/AR-11/AR-12] — hexagonal package structure; LogguardProperties (@ConfigurationProperties), no @Value; @Scheduled/virtual threads
- [Source: architecture.md#Starter Template Evaluation] — Initializr command (lines 89–102), hexagonal package tree (lines 132–158)
- [Source: architecture.md#Project Structure & Boundaries] — full directory layout (lines 421–524); repo root IS the main app (line 423)
- [Source: architecture.md#Implementation Patterns & Consistency Rules] — naming, config property naming (lines 295–317), test organisation (lines 321–329), enforcement guidelines (lines 400–416)
- [Source: architecture.md#Key Configuration (application.yml skeleton)] — lines 576–599
- [Web: Maven Central — `spring-ai-bom:2.0.0` GA available (M1–M8/RC1/RC2/GA line shipped); `spring-ai-starter-model-ollama:2.0.0` present]
- [Web: Maven Central — `org.opensearch.client:opensearch-java` latest `3.9.0`]
- [Web: start.spring.io — dependency ids data-jpa/h2/flyway/spring-shell/actuator valid for Boot 4.1.0; `4.1.0` → `4.1.0.RELEASE`]

### Previous Story Intelligence (Stories 1.2 and 1.1)
From `1-2-companion-app-…md` (status: review) and `1-1-…md`:
- **Docker invocation:** use bare `docker` / `docker compose` (Ubuntu/WSL) — NOT `wsl docker`.
- **Toolchain:** Story 1.2 had to compile inside Docker (no host JDK at the time). A host JDK 21 + Maven are now installed via SDKMAN — build the main app natively (see "Build/run — native host toolchain via SDKMAN").
- **Spring Boot 4 specifics learned in 1.2:** Boot 4 renamed the web starters to `spring-boot-starter-webmvc` / `-webmvc-test`; **Boot 4 ships Jackson 3** (`tools.jackson.*`), so any code touching JSON must not assume `com.fasterxml.jackson`. (Relevant to Story 2.3's OpenSearch mapping, not 2.1.)
- **Verify against live infrastructure and paste the evidence** into the Dev Agent Record (established pattern in 1.1/1.2).
- OpenSearch from Story 1.1 runs at `http://localhost:9200` (single-node, security disabled); the companion (1.2) writes errors to `logstash-app-openshift-application-springboot_error_*`. The default `LogguardProperties.opensearch.index-pattern` is set to match. The stack is currently up (`docker compose ps`).

### Git Intelligence
- Recent commits: `09e5508` (Story 1.1 docker-compose) is HEAD; Story 1.2's working changes (`logguard-error-producer/`, compose edit) are **uncommitted** in the tree. This story adds the root Maven project alongside them.
- No `pom.xml` exists at the repo root yet — scaffolding there is a clean add (only `.gitignore` needs merging).

### Latest Technical Information (verified 2026-06-17, past training cutoff)
- **Spring AI 2.0.0 is GA on Maven Central** — use `spring-ai-bom:2.0.0`; milestone repo no longer required. (`2.0.0-M4` also still exists on Central but is superseded.)
- **`spring-ai-starter-model-ollama:2.0.0`** is on Maven Central (for Story 3.2).
- **`opensearch-java` latest stable `3.9.0`** on Maven Central.
- **Spring Boot `4.1.0.RELEASE`** is the current Initializr default; dependency ids `data-jpa, h2, flyway, spring-shell, actuator` are all valid for it.
- **Flyway** is version-managed by Boot 4; H2 support may require the `flyway-database-h2` module (add only if the empty-migration run fails to recognise H2).

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Opus 4.8, 1M context)

### Debug Log References

- Build/run native via SDKMAN (Java 21.0.11 Temurin, Maven 3.9.16). Non-interactive shells need `source "$HOME/.sdkman/bin/sdkman-init.sh"` first.
- **Initializr rejected `spring-shell`** for Boot 4.1.0: `400 Bad Request — Dependency 'spring-shell' is not compatible with Spring Boot 4.1.0`. Scaffolded without it (deps `data-jpa,h2,flyway,actuator`); see Completion Notes.
- `mvn -B -ntp clean test` → BUILD SUCCESS; `Tests run: 2, Failures: 0, Errors: 0`. Confirmed: Spring AI BOM `2.0.0` + `opensearch-java 3.9.0` resolved from Maven Central with no milestone repo.
- `mvn -B -ntp spring-boot:run` → `Started LogguardAiApplication in 23.448 seconds`; no `ERROR` / `APPLICATION FAILED`; **no Tomcat/Netty line** (non-web daemon context). H2 file `data/logguard.mv.db` created on disk.
- Flyway log: `Successfully validated 0 migrations` → `Creating Schema History table` → `No migration necessary`. (Boot 4 ships Flyway 11, which phrases the empty run as "validated 0 migrations" + "No migration necessary" rather than the AC's literal "applied 0 migrations" — semantically identical: zero migrations ran. A benign `No migrations found` WARN appears because `db/migration` holds only `.gitkeep`.)

### Completion Notes List

- **All 9 ACs verified live.** App scaffolded at the repo root (Boot 4.1.0, Java 21), starts cleanly via `mvn spring-boot:run`, H2 file persistence at `./data/logguard`, Flyway runs with zero migrations, `LogguardProperties` binds all fields with defaults (asserted by `logguardPropertiesBindWithDefaults`), full hexagonal skeleton present, virtual threads enabled, Spring AI BOM `2.0.0` (GA) + `opensearch-java 3.9.0` declared, no milestone repo.
- **Repo-root scaffolding (landmine) handled cleanly:** generated to a temp dir, moved `pom.xml`/`mvnw`/`.mvn`/`src`/`HELP.md`/`.gitattributes` into the root, **merged** `.gitignore` (kept `target/`, `.vscode/`, `.idea`, `_bmad/config.user.toml`; added `data/`, the wrapper jar, `HELP.md`). `docker-compose.yml` and `logguard-error-producer/` left untouched and verified intact. The root pom does **not** declare the companion as a module.
- **AR-2/AR-3 correction (per METIS decision):** used `spring-ai-bom:2.0.0` (GA, Maven Central) and **dropped** the Spring milestones repo — the build resolves entirely from Central. **Architecture AR-2/AR-3 should be updated** to reflect Spring AI GA (the milestone repo is obsolete).
- **⚠️ Spring Shell deferred — incompatible with Boot 4.1.0.** The architecture's scaffold (AR-1/AR-15) lists `spring-shell` (pinned 4.0.3) for the first-run prompt, but Initializr rejects it for Boot 4.1.0 and Spring Shell's latest is 4.0.3 (built against Boot 4.0.x). `spring-shell` is **not** in this story's ACs, so it was omitted. **Story 2.2 (FirstRunInitializer / first-run lookback prompt) must resolve this** — either find a Boot-4.1-compatible Spring Shell or implement the prompt with plain `System.console()`/`BufferedReader` stdin (simpler, no dependency). Flag for architecture follow-up.
- **Spring Boot 4 specifics observed:** the `h2` Initializr dep now pulls `spring-boot-h2console` (dedicated Boot 4 starter) — present but inert since no web server is on the classpath. Test starters are modular (`spring-boot-starter-{actuator,data-jpa,flyway}-test`); JUnit assertions used in the test to avoid assuming AssertJ is on the modular test classpath. Hibernate ORM 7.4.1, H2 2.4.240.
- `ddl-auto: validate` with zero `@Entity` classes passes (nothing to validate) — left as-is per AR-6.
- The H2 file `data/logguard.mv.db` from verification remains on disk (gitignored). Story 2.2's `V1` migration will run against it.

### File List

- `pom.xml` (NEW) — repo-root `logguard-ai` app pom (Boot 4.1.0 parent); added Spring AI BOM `2.0.0` to `<dependencyManagement>` and `opensearch-java:3.9.0`; no milestone repo.
- `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, `HELP.md`, `.gitattributes` (NEW) — Initializr scaffold at repo root.
- `src/main/java/be/vdab/logguard/LogguardAiApplication.java` (NEW) — `@SpringBootApplication` + `@EnableScheduling` + `@ConfigurationPropertiesScan`.
- `src/main/java/be/vdab/logguard/infrastructure/config/LogguardProperties.java` (NEW) — `@ConfigurationProperties(prefix="logguard")` record with nested `Ollama`/`OpenSearch`/`H2` records, all defaulted.
- `src/main/java/be/vdab/logguard/{domain/model,domain/port/in,domain/port/out,domain/service,infrastructure/opensearch,infrastructure/llm,infrastructure/persistence,infrastructure/filesystem,infrastructure/terminal,infrastructure/config}/package-info.java` (NEW) — hexagonal package skeleton (10 files).
- `src/main/resources/application.yml` (NEW) — virtual threads, H2 file datasource, `ddl-auto: validate`, Flyway, full `logguard.*` config block. (Replaced the generated `application.properties`, which was deleted.)
- `src/main/resources/db/migration/.gitkeep` (NEW) — keeps the empty Flyway folder tracked.
- `src/test/java/be/vdab/logguard/LogguardAiApplicationTests.java` (MODIFIED) — added `logguardPropertiesBindWithDefaults` asserting all bound defaults.
- `.gitignore` (MODIFIED) — appended `data/`, `.mvn/wrapper/maven-wrapper.jar`, `HELP.md`; preserved existing entries.

## Change Log

| Date | Change |
|---|---|
| 2026-06-17 | Story 2.1 drafted via create-story context engine. Status → ready-for-dev. |
| 2026-06-17 | Updated for GA decision: use `spring-ai-bom:2.0.0`, drop the Spring milestones repo (AR-2/AR-3 correction). |
| 2026-06-17 | Story 2.1 implemented: scaffolded `logguard-ai` at repo root (Boot 4.1.0, Java 21), `LogguardProperties`, hexagonal skeleton, H2 file persistence + empty Flyway, virtual threads, Spring AI BOM 2.0.0 + opensearch-java 3.9.0. `spring-shell` omitted (incompatible with Boot 4.1.0 — deferred to Story 2.2). All 9 ACs verified live (`spring-boot:run` clean start; 2 tests green). Status → review. |
