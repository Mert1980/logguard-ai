# LogGuard AI — Demo Guide

How to demo LogGuard AI end-to-end: a daemon that polls OpenSearch for new
application errors, groups them by service, and uses a local LLM (Ollama) to
produce a root-cause analysis for each one in the terminal.

## What the demo shows

1. **First-run lookback prompt** — on a fresh start the app asks how far back to scan.
2. **Polling + grouping** — errors are fetched since the last checkpoint and grouped
   by service, most-affected first (FR-27/28).
3. **LLM root-cause analysis** — each error is analysed by a local Ollama model and
   printed with *root cause / likely location / suggested action* (FR-20/23).
4. **Graceful degradation** — if the LLM is down, the error still surfaces with
   `analysis unavailable (reason)` instead of being dropped (FR-24).

## The moving parts

| Component | Where it runs | Port | Started by |
|---|---|---|---|
| OpenSearch | Docker (WSL) | 9200 | `docker compose` |
| logguard-error-producer | Docker (WSL) | 8080 | `docker compose` |
| Ollama (`gemma3:4b`) | host | 11434 | `ollama serve` |
| **LogGuard AI** (the app being demoed) | host (JVM) | — | `mvnw spring-boot:run` |

> Docker runs inside WSL on this machine — prefix docker commands with `wsl`
> (e.g. `wsl docker compose up -d`). Plain `docker` is not on the PATH.

## Prerequisites (one-time)

- **Java 21** and the Maven wrapper (`mvnw.cmd`, included).
- **Docker** available via WSL.
- **Ollama** installed with the model pulled:
  ```powershell
  ollama pull gemma3:4b
  ```

---

## Demo steps

### 1. Start the infrastructure (OpenSearch + error producer)

```powershell
wsl docker compose up -d
```

Wait until OpenSearch is healthy (~30s). Verify:

```powershell
curl http://localhost:9200          # should return an OpenSearch JSON banner
```

### 2. Make sure Ollama is running

```powershell
ollama list                          # confirm gemma3:4b is present
# if the server isn't already running:
ollama serve
```

### 3. Start LogGuard AI

For a live demo, shorten the 5-minute poll interval so cycles are visible:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--logguard.poll-interval=30s"
```

On **first run** you'll see the prompt:

```
First run detected. Process last [N] hours of history? (default: 24h)
```

Type `0` and press Enter (start "now", so only errors you trigger during the demo
are picked up — keeps the output clean). Blank/invalid input defaults to 24h.

> ⚠️ The lookback prompt only appears when launched in a **real interactive
> terminal**. If you run it from an IDE "Run" console without console support, the
> prompt is skipped and 24h is used automatically.

### 4. Trigger some errors

The companion producer ships realistic `be.vdab.*` errors to OpenSearch on demand.
Fire a few, across **different services**, to show the per-service grouping:

```powershell
curl -X POST "http://localhost:8080/trigger-error?service=orgbeheer-service&source=WG-CRM"
curl -X POST "http://localhost:8080/trigger-error?service=orgbeheer-service&source=WG-XYZ"
curl -X POST "http://localhost:8080/trigger-error?service=vacature-service&source=WG-CRM"
```

(`service` and `source` are optional — defaults are `orgbeheer-service` / `WG-CRM`.)

### 5. Watch the analysis

Within one poll interval (30s above), LogGuard prints something like:

```
Analyzing 2 new errors in orgbeheer-service...
── orgbeheer-service (2 errors) ────────────────
[1/2] java.lang.IllegalStateException
  Root cause:       ...
  Likely location:  LabelService.forwardingSourceFor(...)
  Suggested action: ...
[2/2] ...
```

The first LLM call can take a while on `gemma3:4b` (timeout is 120s) — that's
expected; let it run.

### 6. (Optional) Show graceful degradation

Stop Ollama (`Ctrl+C` on `ollama serve`) and trigger another error. The next cycle
prints the error with:

```
  Root cause:       analysis unavailable (...)
```

proving errors are never silently dropped (FR-24).

---

## Re-running the demo — important

The poll **checkpoint is persisted to file-based H2 in `./data/`** so it survives
restarts. This is why a second run can look like "nothing happens": the checkpoint
has already advanced past every error, so there's nothing new to analyse, and the
first-run lookback prompt does **not** reappear.

To re-run cleanly you have two options:

- **Just trigger new errors** — the running (or restarted) daemon will pick up
  anything newer than its checkpoint. Simplest for a repeat demo.
- **Full reset** (fresh "first run", including the lookback prompt): stop the app
  and delete the checkpoint store:
  ```powershell
  Remove-Item -Recurse -Force .\data
  ```
  Then start again from step 3.

## Troubleshooting

- **No output after triggering errors** — confirm the error actually shipped: the
  curl response should say `error produced and shipped to OpenSearch`. Then check
  the index has documents:
  ```powershell
  curl "http://localhost:9200/logstash-app-openshift-application-springboot_error_*/_count"
  ```
- **App exits / can't see the prompt** — launch from a real terminal (PowerShell),
  not a non-interactive IDE console, so `System.console()` is available.
- **`analysis unavailable`** when you expected analysis — Ollama isn't reachable on
  `localhost:11434` or `gemma3:4b` isn't pulled (`ollama list`).
- **Port already in use (8080/9200)** — a previous `docker compose` stack is still
  up: `wsl docker compose down` then start again.

## Tear down

```powershell
wsl docker compose down
```
