# LogGuard AI — Demo Guide

How to demo LogGuard AI end-to-end: a daemon that polls OpenSearch for new application
errors, **fingerprints and deduplicates** them, uses a local LLM (Ollama) to produce a
root-cause analysis for each *unique* error, escalates ones that recur at volume, and lets
you permanently silence known-acceptable errors with a plain text file — all in the terminal.

## What the demo shows

| # | Feature | FRs | Epic |
|---|---|---|---|
| 1 | First-run lookback prompt | FR-2 | 2 |
| 2 | Polling + per-service grouping (most-affected first) | FR-27/28/29 | 2 |
| 3 | LLM root-cause analysis (root cause / likely location / suggested action) | FR-20/23 | 3 |
| 4 | Graceful LLM degradation — `analysis unavailable (reason)`, never dropped | FR-24 | 3 |
| 5 | **Fingerprint line** — every block ends with a copy-pasteable `Fingerprint: … [hash]` | FR-18/30 | 4 |
| 6 | **Deduplication** — the same error N× is analysed **once**, counted silently | FR-8/9/10 | 4 |
| 7 | **Escalation re-notifications** — a recurring error re-surfaces at 10×/100×/1000×, reusing the cached analysis (no fresh LLM call) | FR-11 | 4 |
| 8 | **Won't-fix suppression** — paste a hash into `suppression.txt`; the error is acknowledged with `⚑ Known / Won't Fix` and never re-analysed (hot-reloaded each cycle) | FR-14/16/17 | 4 |
| 9 | **Unsuppression** — delete the hash and the error is analysed again | FR-19 | 4 |
| 10 | **Won't-fix volume override** — even a suppressed error nudges you once at 1000× | FR-12 | 4 |
| 11 | OpenSearch-outage degradation banner + recovery with backlog replay | FR-33–36 | 2 |
| 12 | (bonus) Tenant-data redaction — KBO/email/LDAP stripped before the LLM | FR-21 | 3 |

## The moving parts

| Component | Where it runs | Port | Started by |
|---|---|---|---|
| OpenSearch | Docker (WSL) | 9200 | `docker compose` |
| logguard-error-producer | Docker (WSL) | 8080 | `docker compose` |
| Ollama (`gemma3:4b`) | host | 11434 | `ollama serve` |
| **LogGuard AI** (the app being demoed) | host (JVM) | — | `mvnw spring-boot:run` |
| `suppression.txt` | host, project root | — | you, with a text editor |

> Docker runs inside WSL on this machine — prefix docker commands with `wsl`
> (e.g. `wsl docker compose up -d`). Plain `docker` is not on the PATH.

## Prerequisites (one-time)

- **Java 21** and the Maven wrapper (`mvnw.cmd`, included).
- **Docker** available via WSL.
- **Ollama** installed with the model pulled:
  ```powershell
  ollama pull gemma3:4b
  ```

## The error producer — error types and parameters

The companion ships realistic `be.vdab.*` errors to OpenSearch on demand. One endpoint,
`POST /trigger-error`, with four parameters:

| Param | Default | Meaning |
|---|---|---|
| `type` | `npe` | Which error to throw — **each is a different fingerprint** (see table below) |
| `service` | `orgbeheer-service` | Simulated service name (drives per-service grouping) |
| `source` | `WG-CRM` | Free-text input for the scenario (unknown forwarding source / vacancy id / KBO) |
| `count` | `1` | How many copies to ship in one call — **drives the dedup, escalation and volume demos** |

| `type` | Exception | Thrown from | Example fingerprint label |
|---|---|---|---|
| `npe` | `NullPointerException` | `LabelService.forwardingSourceFor` | `NullPointerException@LabelService:27` |
| `state` | `IllegalStateException` | `VacatureService.publish` | `IllegalStateException@VacatureService:18` |
| `validation` | `IllegalArgumentException` | `KboValidationService.validate` | `IllegalArgumentException@KboValidationService:18` |
| `quota` | `ArithmeticException` | `MatchingQuotaService.remainingQuota` | `ArithmeticException@MatchingQuotaService:18` |

(Exact line numbers depend on the build; the **hash** in the `Fingerprint:` line is the real key.)

---

## Demo steps

### 1. Start the infrastructure (OpenSearch + error producer)

The producer image is built from source, so pass `--build` to pick up the demo error types:

```powershell
wsl docker compose up -d --build
```

Wait until OpenSearch is healthy (~30s). Verify:

```powershell
curl.exe http://localhost:9200          # should return an OpenSearch JSON banner
```

### 2. Make sure Ollama is running

```powershell
ollama list                          # confirm gemma3:4b is present
# if the server isn't already running:
ollama serve
```

### 3. Create an (empty) suppression file

LogGuard reads `suppression.txt` from the project root at the start of every poll cycle.
Create it empty now so you can paste a hash into it live later:

```powershell
New-Item -ItemType File suppression.txt   # only if it doesn't exist yet
```

### 4. Start LogGuard AI with demo-friendly settings

The production defaults (5-minute poll, 24-hour dedup window, escalation at 10/100/1000) are
too slow to show live. Shorten them so every feature is visible in seconds.

Build the jar and run it directly from a **real PowerShell terminal** — this is the reliable
way to get the interactive first-run prompt (running via `mvnw spring-boot:run` forks a child
JVM, so `System.console()` is null and the prompt is skipped):

```powershell
# Build (JDK 21 required; the mvnw.cmd wrapper may be blocked by Group Policy — use the jar)
.\mvnw.cmd -DskipTests package

# Run with JDK 21 on the PATH for this session, then launch the jar
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\temurin-21.0.11"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
java -jar target\logguard-ai-0.0.1-SNAPSHOT.jar --logguard.poll-interval=20s --logguard.deduplication-window=60s --logguard.escalation-thresholds=5,1000
```

> If your default `java` is older than 21 you'll get `UnsupportedClassVersionError` (class file
> version 65.0) — the `JAVA_HOME`/`PATH` lines above point at JDK 21 to avoid that.

- **`poll-interval=20s`** — a new cycle every 20s.
- **`deduplication-window=60s`** — a fingerprint's window expires after 60s, so the
  won't-fix / unsuppression steps below take effect within the demo instead of after 24h.
- **`escalation-thresholds=5,1000`** — escalate at **5×** (visible quickly) and keep **1000**
  for the won't-fix volume override (which is fixed to the 1000× threshold by design).

On **first run** you'll see:

```
First run detected. Process last [N] hours of history? (default: 24h)
```

Type `0` and press Enter (start "now", so only errors you trigger during the demo are picked
up — keeps the output clean). Blank/invalid input defaults to 24h.

> ⚠️ The lookback prompt only appears when `System.console()` is available — i.e. launched with
> `java -jar` from a **real interactive terminal**. From a forked `mvnw spring-boot:run` process or a
> non-interactive IDE "Run" console it is skipped and 24h is used automatically.

---

### Feature 2–5: polling, grouping, LLM analysis, the Fingerprint line

Fire a few **distinct** errors across **different services**:

```powershell
curl.exe -X POST "http://localhost:8080/trigger-error?type=npe&service=orgbeheer-service"
curl.exe -X POST "http://localhost:8080/trigger-error?type=state&service=orgbeheer-service&source=VAC-42"
curl.exe -X POST "http://localhost:8080/trigger-error?type=quota&service=vacature-service"
```

Within one poll interval, LogGuard prints grouped, most-affected-service-first blocks. Note the
**`Fingerprint:` line** at the end of every block — that hash is what you paste into `suppression.txt`:

```
Analyzing 2 new errors in orgbeheer-service...
── orgbeheer-service (2 errors) ────────────────
[1/2] java.lang.NullPointerException@LabelService
  Root cause:       ...
  Likely location:  LabelService.forwardingSourceFor:27
  Suggested action: ...
  Fingerprint: NullPointerException@LabelService:27  [a3f9c2b1]
[2/2] java.lang.IllegalStateException@VacatureService
  Root cause:       ...
  Likely location:  VacatureService.publish:18
  Suggested action: ...
  Fingerprint: IllegalStateException@VacatureService:18  [7c1d4e88]
```

The first LLM call can take a while on `gemma3:4b` (timeout 120s) — that's expected.

> 📋 **Copy a hash now** (e.g. the `quota` one) — you'll suppress it in a later step.

---

### Feature 6: deduplication — N errors, one analysis

Fire the **same** error many times in one call:

```powershell
curl.exe -X POST "http://localhost:8080/trigger-error?type=npe&service=orgbeheer-service&count=5"
```

Next cycle, LogGuard analyses it **once** — there is exactly **one** analysis block (and one LLM
call) for all 5 occurrences. The other 4 are counted silently. *Five errors in, one analysis out:
that's the LLM-budget guarantee (NFR-4).*

---

### Feature 7: escalation re-notification

A known (already-analysed) error that keeps recurring re-surfaces at the configured thresholds,
**reusing the stored analysis — no new LLM call**. With `escalation-thresholds=5,1000`:

1. Establish + analyse the error once (so its analysis is cached). Use a fresh fingerprint:
   ```powershell
   curl.exe -X POST "http://localhost:8080/trigger-error?type=validation&service=match-service&source=0999.999.999"
   ```
   Wait one cycle for its analysis block (note the `Fingerprint:` hash).
2. Now flood it to cross 5× **within the 60s window**:
   ```powershell
   curl.exe -X POST "http://localhost:8080/trigger-error?type=validation&service=match-service&source=0999.999.999&count=4"
   ```
   Next cycle prints the escalation, reusing the cached root cause:
   ```
   ⚠️ Known error IllegalArgumentException@KboValidationService:18 now seen 5× since 2026-07-01T...
      Root cause: <the analysis from step 1, reused — no fresh LLM call>
   ```

> Do steps 1 and 2 within the 60s dedup window so the count accumulates on the same record. If the
> window lapses, the fingerprint is treated as new and analysed again instead of escalating.

---

### Feature 8 + 9: won't-fix suppression and unsuppression

Silence a known-acceptable error with nothing but a text editor.

1. Pick the **hash** of an analysed error from its `Fingerprint:` line — say the `quota`
   error's hash from earlier. Add it to `suppression.txt` (format: `hash  # free-text comment`):
   ```powershell
   Add-Content suppression.txt "7c1d4e88  # ArithmeticException in quota calc — known, backlog ticket JIRA-1234"
   ```
   LogGuard reloads the file at the start of the **next** cycle (hot-reload, FR-14) — no restart.
2. Let the error's 60s dedup window lapse (so its next occurrence is treated as new), then trigger it again:
   ```powershell
   curl.exe -X POST "http://localhost:8080/trigger-error?type=quota&service=vacature-service"
   ```
   Instead of an analysis block, LogGuard now prints just the acknowledgement — **no LLM call**:
   ```
   ⚑ Known / Won't Fix: ArithmeticException@MatchingQuotaService:18  7c1d4e88
   ```
   This is deliberate: `⚑` says "I see this; you told me not to care" — distinct from silence.
3. **Unsuppress** — delete that line from `suppression.txt` (and let the window lapse again):
   ```powershell
   Set-Content suppression.txt ""    # or remove just that line in an editor
   curl.exe -X POST "http://localhost:8080/trigger-error?type=quota&service=vacature-service"
   ```
   The error is analysed normally again (FR-19) — the file is the single source of truth, and
   LogGuard **never writes to it** (FR-16).

---

### Feature 10 (optional): won't-fix volume override

Even a suppressed error gets **one** nudge if it reaches truly unusual volume (1000×) — a read-only
prompt to reconsider, without un-suppressing it. To show it live, suppress a fresh fingerprint and
fire 1000 copies in one call (this ships 1000 documents, so give it a moment):

```powershell
# (with the error's hash already in suppression.txt)
curl.exe -X POST "http://localhost:8080/trigger-error?type=state&service=orgbeheer-service&source=VAC-77&count=1000"
```

Next cycle (the first occurrence prints `⚑`, the 1000th crosses the override):

```
⚠️ Won't-fix error IllegalStateException@VacatureService:18 now seen 1000× since 2026-07-01T... — volume is unusually high
```

The `wont_fix` decision is **not** cleared — it's a nudge, not an override of your call (FR-12).

---

### Feature 11 (optional): OpenSearch-outage degradation + recovery

Show that a silent terminal is never ambiguous between "no errors" and "LogGuard is blind":

```powershell
wsl docker compose stop opensearch
```

After `max-consecutive-poll-failures` (default 3) cycles, a sticky banner reprints every cycle:

```
🔴 LOGGUARD DEGRADED — OpenSearch unreachable since 2026-07-01T... (3 failures)
```

Bring it back:

```powershell
wsl docker compose start opensearch
```

The next successful poll prints recovery and **replays the missed backlog** (escalation thresholds
fire in sequence as the counts catch up, FR-13):

```
🟢 LOGGUARD RECOVERED — polling resumed at 2026-07-01T..., catching up from checkpoint
```

---

### Feature 4 (optional): graceful LLM degradation

Stop Ollama (`Ctrl+C` on `ollama serve`) and trigger a **new** error. The next cycle still surfaces it:

```
  Root cause:       analysis unavailable (...)
```

proving errors are never silently dropped (FR-24). The Fingerprint line is still printed (FR-18), so
you can suppress even an error the LLM couldn't analyse.

---

### Feature 12 (bonus): tenant-data redaction

Trigger a `validation` error whose message embeds a Belgian KBO number:

```powershell
curl.exe -X POST "http://localhost:8080/trigger-error?type=validation&source=0123.456.789"
```

The KBO (and any email / LDAP DN) is replaced with `[KBO]` / `[EMAIL]` / `[LDAP-DN]` in the payload
sent to the LLM (FR-21) — the analysis works without leaking tenant data to the model.

---

## Re-running the demo — important

The poll **checkpoint is persisted to file-based H2 in `./data/`** (and so are the deduplication
records). A second run can look like "nothing happens": the checkpoint has advanced past every error
and the dedup records still suppress repeats, so there's nothing new to analyse, and the first-run
lookback prompt does **not** reappear.

To re-run cleanly:

- **Just trigger new errors** — the daemon picks up anything newer than its checkpoint. Simplest for
  a repeat demo. Use a new `source=` to get a fresh fingerprint when you want a clean analysis.
- **Full reset** (fresh "first run" + empty dedup state): stop the app and delete the H2 store:
  ```powershell
  Remove-Item -Recurse -Force .\data
  ```
  Then start again from step 4. (Leave or clear `suppression.txt` as you like — it's never written by LogGuard.)

## Troubleshooting

- **New error types return `Unknown type`** — the producer image wasn't rebuilt. Re-run
  `wsl docker compose up -d --build`.
- **No output after triggering errors** — confirm the error shipped: the curl response should say
  `shipped N× <ExceptionClass> to OpenSearch`. Then check the index has documents:
  ```powershell
  curl.exe "http://localhost:9200/logstash-app-openshift-application-springboot_error_*/_count"
  ```
- **Won't-fix `⚑` didn't appear** — the fingerprint still had an **active** dedup record when you
  re-triggered. Suppression takes effect on the next *new* occurrence; wait out the
  `deduplication-window` (60s in the demo config) before re-triggering, and confirm the hash in
  `suppression.txt` exactly matches the `Fingerprint:` line (everything after `#` is just a comment).
- **Escalation didn't fire** — the occurrences must land on the **same active record**: keep them
  within the dedup window, and remember the cooling escalation needs the error analysed once first so
  there's a cached root cause to reuse.
- **App exits / can't see the prompt** — launch from a real terminal (PowerShell), not a
  non-interactive IDE console, so `System.console()` is available.
- **`analysis unavailable`** when you expected analysis — Ollama isn't reachable on `localhost:11434`
  or `gemma3:4b` isn't pulled (`ollama list`).
- **Port already in use (8080/9200)** — a previous stack is still up: `wsl docker compose down`.

## Tear down

```powershell
wsl docker compose down
```
