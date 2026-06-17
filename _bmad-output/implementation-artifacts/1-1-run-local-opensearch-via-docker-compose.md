---
baseline_commit: b7c60df21394bdfefbeae83fb57e7485b72e215e
---

# Story 1.1: Run Local OpenSearch via Docker Compose

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a developer,
I want a local OpenSearch instance running via a single `docker compose up` command,
so that I have a target for the companion app to write test errors into and for LogGuard to poll.

## Acceptance Criteria

1. The repo root contains a `docker-compose.yml`.
2. Running `docker compose up opensearch` starts OpenSearch and it becomes accessible at `http://localhost:9200`.
3. `curl http://localhost:9200` returns a JSON response confirming OpenSearch is running.
4. OpenSearch is configured as **single-node** with **security disabled** (no TLS or auth for local dev) — `curl` over plain `http://` (not `https://`) succeeds with no credentials.
5. The OpenSearch service has a healthcheck (`curl -s http://localhost:9200`) with a 10s interval, and the container reports `healthy`.

## Tasks / Subtasks

- [x] **Task 1: Create `docker-compose.yml` at repo root with the `opensearch` service** (AC: #1, #2)
  - [x] Create `docker-compose.yml` in the repository root (`C:\VDABapps\UserApps\IdeaProjects\logguard-ai\docker-compose.yml`).
  - [x] Define a service named exactly `opensearch` (the AC runs `docker compose up opensearch` by service name).
  - [x] Use image `opensearchproject/opensearch:2` (per architecture). Consider pinning to a specific minor (e.g. `:2.19.0`) for reproducibility — see Dev Notes "Image pinning".
  - [x] Map port `9200:9200` (REST API). Port `9600` (performance analyzer) is optional and not required by any AC — omit it.
- [x] **Task 2: Configure single-node + disabled security correctly** (AC: #4) — ⚠️ highest-risk task, read Dev Notes first
  - [x] Set `discovery.type=single-node` as an environment variable.
  - [x] Disable security with `DISABLE_SECURITY_PLUGIN=true` — **NOT** `plugins.security.disabled=true` (that env var silently stopped working in OpenSearch 2.12+; see Dev Notes "Security disable landmine").
  - [x] Do **not** set `OPENSEARCH_INITIAL_ADMIN_PASSWORD` — it is neither required nor used once security is disabled.
  - [x] Set `OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m` to cap heap on a dev machine (prevents the container from grabbing excessive RAM).
  - [x] Set `bootstrap.memory_lock=true` and the matching `ulimits: memlock: {soft: -1, hard: -1}` (standard OpenSearch single-node compose hygiene).
- [x] **Task 3: Add the healthcheck** (AC: #5)
  - [x] Add a `healthcheck` block: `test: ["CMD-SHELL", "curl -s http://localhost:9200 | grep -q opensearch"]`, `interval: 10s`, plus a sensible `timeout`, `retries`, and `start_period` (OpenSearch takes ~20–40s to boot — set `start_period: 30s` so early checks don't count as failures).
  - [x] Confirm `curl` is available inside the image (the official `opensearchproject/opensearch:2` image ships `curl` — the healthcheck relies on it).
- [x] **Task 4: Verify end-to-end** (AC: #2, #3, #4, #5)
  - [x] Run `docker compose up opensearch` (foreground) or `docker compose up -d opensearch` (detached).
  - [x] Wait for boot, then run `curl http://localhost:9200` from the host — confirm a JSON body with `"cluster_name"`, `"version"`, and the `"distribution" : "opensearch"` / tagline fields.
  - [x] Run `docker compose ps` and confirm the `opensearch` container STATUS shows `(healthy)`.
  - [x] Confirm the request works over plain `http://` with **no** `-k` flag and **no** credentials (proves security is truly disabled).
- [x] **Task 5: Repo hygiene** (AC: #1)
  - [x] If you add a named volume or bind mount for OpenSearch data, ensure any host data directory is gitignored. A bind mount is **not required** by the ACs — a named Docker volume (or no persistence at all) is fine for this story. Do not over-scope.

## Dev Notes

### Scope boundary — what this story is and is NOT
- This story delivers **only** the `opensearch` service in `docker-compose.yml`. 
- The `logguard-error-producer` companion-app service is **Story 1.2** — do NOT add it here. The architecture's full compose skeleton (architecture.md §Gap Analysis) shows both services; only the `opensearch` half belongs to this story. Creating the file with just `opensearch` now is correct; Story 1.2 appends the companion service.
- No Spring Boot / Maven scaffolding happens here — that is Story 2.1. This story is pure infrastructure.

### ⚠️ Security disable landmine (the #1 way this story fails)
The architecture document's Docker Compose skeleton (architecture.md, lines ~656–667) specifies:
```yaml
environment:
  - discovery.type=single-node
  - plugins.security.disabled=true   # ⚠️ THIS NO LONGER WORKS as a Docker env var in OpenSearch 2.12+
```
The `plugins.security.disabled=true` **environment variable** silently stopped taking effect in OpenSearch 2.12+ (opensearch-project/security issue #4062). The container will then boot with security **enabled**, which means:
- It serves over **HTTPS** (self-signed), so `curl http://localhost:9200` (AC #3, #4) fails with a connection/SSL error.
- In 2.12+ it also demands `OPENSEARCH_INITIAL_ADMIN_PASSWORD`, and without it the container exits during init.

**Correct approach for the official Docker image:** use the image's documented env toggle:
```yaml
environment:
  - discovery.type=single-node
  - DISABLE_SECURITY_PLUGIN=true
```
With `DISABLE_SECURITY_PLUGIN=true`, OpenSearch serves plain HTTP on 9200 with no auth (satisfies AC #3/#4) and `OPENSEARCH_INITIAL_ADMIN_PASSWORD` is not needed. **This is an intentional, verified correction to the architecture skeleton** — record it in your File List / Completion Notes so the architecture can be updated.

### Reference docker-compose.yml (starting point — adapt, don't blindly paste)
```yaml
services:
  opensearch:
    image: opensearchproject/opensearch:2
    environment:
      - discovery.type=single-node
      - DISABLE_SECURITY_PLUGIN=true
      - bootstrap.memory_lock=true
      - "OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m"
    ulimits:
      memlock:
        soft: -1
        hard: -1
    ports:
      - "9200:9200"
    healthcheck:
      test: ["CMD-SHELL", "curl -s http://localhost:9200 | grep -q opensearch"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s
```
Note: modern Docker Compose (v2) ignores the legacy top-level `version:` key — do not add it.

### Host prerequisite — `vm.max_map_count`
OpenSearch requires the host kernel setting `vm.max_map_count=262144` or it fails a bootstrap check on startup. On **Docker Desktop for Windows (WSL2 backend)** — METIS is on Windows 11 — this is usually already satisfied by the WSL2 VM, but if the container logs `max virtual memory areas vm.max_map_count [65530] is too low`, fix it inside the WSL2 distro (`wsl -d docker-desktop` → `sysctl -w vm.max_map_count=262144`) or via `.wslconfig`. Capture this in Completion Notes only if you actually hit it.

### Image pinning
Architecture pins the floating major tag `opensearchproject/opensearch:2`. Floating is acceptable per the architecture decision, but a floating tag can pull a newer minor on a later `docker pull` and shift behavior. If you want reproducible local runs, pin to the specific minor you tested (e.g. `opensearchproject/opensearch:2.19.0`) and note the chosen version in Completion Notes. Either choice is acceptable for this story.

### Verification expectations
- `curl http://localhost:9200` should return JSON similar to:
  ```json
  { "name": "...", "cluster_name": "docker-cluster", "version": { "distribution": "opensearch", "number": "2.x.x", ... }, "tagline": "The OpenSearch Project: https://opensearch.org/" }
  ```
  The healthcheck's `grep -q opensearch` matches the `"distribution": "opensearch"` / tagline text in that body.
- Inside the healthcheck, `localhost` correctly refers to the container itself (the OpenSearch process listens on 9200 in-container).

### Testing standards
- This is an infrastructure-only story — there is **no Java code and no unit/integration test** to write (test conventions in architecture.md §Test organisation apply from Story 2.1 onward).
- "Test" here = the manual verification in Task 4 (`curl` + `docker compose ps` healthy). Record the actual `curl` JSON output and the `(healthy)` status line in the Dev Agent Record → Completion Notes as evidence the ACs passed.

### Project Structure Notes
- New file: `docker-compose.yml` at repo root — aligns with architecture.md §Project Structure ("Docker Compose at repo root, single `docker-compose.yml`").
- The repo root IS the future `logguard-ai` Spring Boot app (architecture.md), but none of that structure is created in this story.
- No conflicts with existing structure: the repo currently has no `docker-compose.yml`.

### References
- [Source: epics.md#Story 1.1: Run Local OpenSearch via Docker Compose] — acceptance criteria
- [Source: epics.md#Epic 1: Local Development Environment & Companion App] — epic scope, "No LogGuard development in this epic"
- [Source: architecture.md#Infrastructure & Deployment] — Docker Compose at repo root, single-node, `plugins.security.disabled`, single `docker-compose.yml`
- [Source: architecture.md#Gap Analysis] — Docker Compose skeleton (opensearch + logguard-error-producer); healthcheck `curl -s http://localhost:9200 | grep -q opensearch`, interval 10s
- [Source: prd.md#9 Assumptions Index, ASSUMPTION-1] — local OpenSearch in Docker is the MVP target; production is the existing K8s-hosted cluster
- [Source: prd.md#4.1, ASSUMPTION-2] — MVP index name is configurable; production target pattern `logstash-app-openshift-application-springboot_error_*` (index creation is NOT part of this story)
- [Web: OpenSearch security disable for Docker — `DISABLE_SECURITY_PLUGIN=true`; `plugins.security.disabled` env broke in 2.12+ (opensearch-project/security#4062)]

### Latest Technical Information (verified 2026-06, past training cutoff)
- **OpenSearch 2.12+ behavior change:** the `plugins.security.disabled=true` *environment variable* no longer disables security in the Docker image (issue #4062). Use `DISABLE_SECURITY_PLUGIN=true`.
- **OpenSearch 2.12+ admin password:** when security is *enabled*, the image requires `OPENSEARCH_INITIAL_ADMIN_PASSWORD` (the old default `admin/admin` was removed). Not relevant here because we disable security — included so the dev doesn't accidentally enable security and get stuck.
- **Docker Compose v2:** the top-level `version:` field is obsolete and ignored; omit it.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Opus 4.8, 1M context)

### Debug Log References

- `wsl docker compose config` — validated compose syntax (Docker 29.5.2, Compose v5.1.4; Docker runs inside WSL on this machine, invoked as `wsl docker ...`).
- `wsl docker compose up -d opensearch` — pulled `opensearchproject/opensearch:2` (resolved to **2.19.5**) and started container `logguard-opensearch`.
- Health poll: container reported `healthy` ~28s after start.
- `wsl curl -s http://localhost:9200` — returned OpenSearch JSON over plain HTTP, no auth, `"distribution": "opensearch"`, version `2.19.5`.
- Windows-host reachability of `localhost:9200` confirmed working by the user (an initial `Invoke-RestMethod` from PowerShell hit a cold-start timeout immediately after the container went healthy; re-test by the user succeeded).

### Completion Notes List

- All 5 ACs verified live. OpenSearch single-node starts via `docker compose up opensearch`, serves plain HTTP on `:9200`, and the container reports `(healthy)` on a 10s-interval healthcheck.
- **Intentional correction to the architecture skeleton:** used `DISABLE_SECURITY_PLUGIN=true` instead of the architecture's `plugins.security.disabled=true` env var, which silently stopped disabling security in OpenSearch 2.12+ (opensearch-project/security#4062). With the corrected setting, OpenSearch serves plain HTTP with no auth — exactly what AC #3/#4 require. **Architecture doc (§Gap Analysis Docker Compose skeleton) should be updated to match.**
- Scope held to the `opensearch` service only; the `logguard-error-producer` companion service remains deferred to Story 1.2 (a comment in `docker-compose.yml` notes this).
- Added container hardening that is standard for single-node OpenSearch and harmless for local dev: `bootstrap.memory_lock=true` + `memlock` ulimits, and a 512m heap cap via `OPENSEARCH_JAVA_OPTS` so the container does not over-allocate RAM on a dev laptop.
- Task 5 (repo hygiene): no bind mount or named volume was added (container uses ephemeral storage), so there is no host data directory to gitignore for this story. The `.gitignore` for `data/` (H2) belongs to Story 2.1.
- `vm.max_map_count` bootstrap check passed on this WSL2 host — no manual `sysctl` adjustment was needed.
- The container is currently left **running** as the local dev stack. Stop it with `wsl docker compose down` (or `wsl docker compose stop opensearch`) when not needed.

### File List

- `docker-compose.yml` (NEW) — repo root; `opensearch` single-node service with disabled security and healthcheck.

## Change Log

| Date | Change |
|---|---|
| 2026-06-17 | Story 1.1 implemented: added `docker-compose.yml` with a local single-node OpenSearch service (security disabled via `DISABLE_SECURITY_PLUGIN=true`, 10s healthcheck). All 5 ACs verified live against OpenSearch 2.19.5. Status → review. |
