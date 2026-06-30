---
baseline_commit: 192a6d73822b123a7a3dc7c4071dc4c32bb7b5d3
---

# Story 4.3: SuppressionFile Adapter with Hot-Reload, Ordering Guarantee, and Read-Only Authority

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a developer,
I want to add fingerprint hashes to a plain text file to permanently silence known-acceptable errors, with changes taking effect within the next poll cycle without restarting LogGuard,
so that suppression is zero-infrastructure (text editor + copy-paste) and entirely developer-controlled.

## Acceptance Criteria

1. **Given** `suppression.txt` contains entries in format `hash  # HumanLabel`, **when** a new poll cycle begins, **then** `SuppressionFilePort.loadHashes()` is called before `OpenSearchPort.findErrorsSince()` — reload completes before any error in the batch is processed (this ordering already holds in `PollService`; do not regress it).
2. `loadHashes()` returns the set of hash strings (everything before the ` #` comment on each line, trimmed).
3. Blank lines and lines starting with `#` are ignored.
4. If the same hash appears more than once, the first occurrence wins (a `Set` dedups inherently; behaviour must be that the hash is present exactly once).
5. **Given** `suppression.txt` does not exist, **when** `loadHashes()` is called, **then** an **empty set** is returned — an absent file is not an error condition (and no warning is printed).
6. **Given** `suppression.txt` exists but is unreadable or a read/parse error occurs, **when** `loadHashes()` is called, **then** the previously loaded set is kept in memory and returned unchanged, **and** the terminal prints `⚠️ Suppression file unreadable — using last known state`, **and** LogGuard continues polling normally (no exception propagates).
7. `SuppressionFilePort` in `domain/port/out/` declares only `Set<String> loadHashes()` — **no write method exists on this interface under any circumstance** (FR-16/NFR-6). Do not add one.
8. `SuppressionFileAdapter` in `infrastructure/filesystem/` has **no write, truncate, create, or modify path in any code branch, including exception handlers** — it only reads.
9. The suppression file path is configurable via `logguard.suppression-file-path` (already present in `LogguardProperties`; read it, do not re-declare).
10. A unit test `SuppressionFileAdapterTest` covers: normal parse (hashes extracted, comments stripped), blank/`#`-comment lines ignored, duplicate hash deduped, absent file → empty set (no warning), unreadable → last-known set returned + warning printed, and that the file is never modified.

## Tasks / Subtasks

- [ ] **Task 1: Add the suppression-unreadable terminal output method** (AC: #6) — read Dev Notes "Routing the warning"
  - [ ] Add `void printSuppressionUnreadable();` to `TerminalOutputPort` (`domain/port/out/`).
  - [ ] Implement it in `TerminalOutputAdapter` using the existing `ESCALATION` (`⚠️`) status constant — never an inline glyph literal (Story 2.4 AC). Print exactly: `⚠️ Suppression file unreadable — using last known state` (mirror the blank-line-then-line style of `printDegraded`/`printRecovery`, `System.out.flush()` after).
- [ ] **Task 2: Implement real parsing + hot-reload + last-known-state in `SuppressionFileAdapter`** (AC: #1–#9) — read Dev Notes "Parsing" and "Absent vs unreadable"
  - [ ] Replace the Story 2.5 stub. Keep `@Component implements SuppressionFilePort`. Constructor-inject `LogguardProperties` (for `suppressionFilePath()`) and `TerminalOutputPort`. Resolve the path to a `java.nio.file.Path` once at construction; provide a package-private constructor `(String suppressionFilePath, TerminalOutputPort terminal)` for the unit test (the `@Autowired` public constructor delegates to it).
  - [ ] Hold `private Set<String> lastKnown = Set.of();` as in-memory state (updated on every successful load; reload is sequential with the single-threaded poll — FR-14 — so no synchronisation needed; note this).
  - [ ] `loadHashes()`: if `Files.exists(path)` is false → set `lastKnown = Set.of()` and return it (absent = empty, no warning, AC #5). Else read all lines (UTF-8); parse (Task 3); on success store into `lastKnown` and return it. On any `IOException` (or other read failure) → call `terminalOutput.printSuppressionUnreadable()` and return the unchanged `lastKnown` (AC #6). **Never throws** (honours the port contract and keeps `PollService`'s unguarded call safe).
  - [ ] Use only read APIs (`Files.exists`, `Files.readAllLines`/`Files.lines`). No `Files.write`, `createFile`, `delete`, `newOutputStream`, channel writes, etc. — anywhere, including the catch block (AC #8, FR-16).
- [ ] **Task 3: Line parsing helper** (AC: #2, #3, #4)
  - [ ] For each line: take the substring before the first `#` (or the whole line if no `#`), `strip()` it; if the result is non-empty, add it to a `LinkedHashSet`. This naturally: ignores blank lines and whitespace-only lines, ignores full `#`-comment lines (and leading-whitespace `#` lines), strips the `  # HumanLabel` comment from a hash line, and dedups (first occurrence wins / present once). Return the set.
- [ ] **Task 4: Unit test `SuppressionFileAdapterTest`** (AC: #10) — pure JUnit 5 + `@TempDir`, no Spring; mirror `TenantDataSanitizerTest`/`PollServiceTest` fake style
  - [ ] Use a JUnit `@TempDir Path tmp`. Construct the adapter via the package-private `(String path, TerminalOutputPort terminal)` ctor with a small `RecordingTerminal` fake that counts `printSuppressionUnreadable()` calls.
  - [ ] **Normal parse:** write a file with hash+comment lines, a bare hash line, a blank line, a `#`-comment line, leading/trailing spaces, and a duplicate hash → assert the returned set is exactly the expected hashes (comments stripped, blanks/comments ignored, dup present once), and `0` warnings.
  - [ ] **Absent file:** path to a non-existent file → empty set, `0` warnings.
  - [ ] **Unreadable → last known + warning:** load a valid file (populates last-known), then make the path unreadable by replacing the file with a **directory** at the same path (delete file, `Files.createDirectory`) — `readAllLines` on a directory throws `IOException` cross-platform → assert the returned set equals the previously loaded set AND `1` warning printed.
  - [ ] **Read-only:** capture the file's bytes before and after a `loadHashes()` call and assert they are unchanged (supports FR-16). Optionally assert the adapter exposes no write method (compile-time: the port has none).

## Dev Notes

### Scope boundary (read first)
- **IN:** make `SuppressionFileAdapter` actually read/parse the file with hot-reload + absent/unreadable handling + read-only guarantee, and add the one `TerminalOutputPort` method needed for the FR-15 warning.
- **NOT IN:** *consuming* the returned hash set. `PollService` already calls `loadHashes()` first each cycle (FR-14 ordering) but currently **discards** the result — that stays as-is in this story. The won't-fix-from-birth decision (create a record with `wontFix=true` when a fingerprint hash is suppressed), the `⚑ Known / Won't Fix` label, unsuppression, and the per-error `Fingerprint:` output line are **Story 4.4**. Do **not** wire the set into the dedup gate here.
- This story **resolves** the deferred-work item *"Suppression reload result discarded + unguarded"*: because the adapter now handles its own read failures internally and **never throws**, `PollService`'s existing unguarded `loadHashes()` call cannot abort a cycle. No `PollService` change is needed (and none should be made).

### Routing the warning (design decision)
FR-15's `⚠️ Suppression file unreadable — using last known state` is user-facing terminal output, so per AR-10 it must go through `TerminalOutputPort` (only `TerminalOutputAdapter` may touch `System.out`). The architecture's port sketch lists no suppression-specific method, so add `printSuppressionUnreadable()`. The adapter can't signal "unreadable" through its return type (the port is fixed to `Set<String> loadHashes()` — AC #7), so the **adapter** owns the warning: it depends on `TerminalOutputPort` (an adapter depending on another outbound port is fine in hexagonal) and prints when a read fails. Reuse the existing `ESCALATION` (`⚠️`) constant in `TerminalOutputAdapter` — Story 2.4 forbids inline status-glyph literals.
[Source: specs/logguard-ai.allium#ReloadSuppressionFile (`Print: "⚠️ Suppression file unreadable — using last known state"`); architecture.md#AR-10; epics.md#FR-15.]

### Parsing (authoritative)
Format per entry: `hash  # HumanLabel` — the hash is the lookup key; everything after `#` is a human comment that does not affect matching (FR-14). Algorithm per line:
```java
int hashIdx = line.indexOf('#');
String token = (hashIdx >= 0 ? line.substring(0, hashIdx) : line).strip();
if (!token.isEmpty()) hashes.add(token);   // LinkedHashSet: order-preserving, dedups (first wins)
```
This single rule satisfies AC #2/#3/#4 at once: blank/whitespace-only lines → empty token → skipped; full `#`-comment lines (incl. leading-whitespace `   # …`) → empty token → skipped; `a3f9c2b1  # NPE@LabelV2Config:21` → `a3f9c2b1`; a bare `a3f9c2b1` line → `a3f9c2b1`; duplicates collapse in the `Set`. Do **not** validate the hash shape — a non-matching token simply never matches a fingerprint (lenient is correct; over-validation risks rejecting a future hash format).
[Source: epics.md#FR-14, #Story 4.3 AC; specs/logguard-ai.allium lines 197–198, 229–237.]

### Absent vs unreadable (distinct branches — do not conflate)
- **Absent** (`!Files.exists(path)`): return an **empty set**, set `lastKnown = Set.of()`, **no warning**. Removing the file is a deliberate "nothing suppressed" state, not an error (AC #5). So `Files.readAllLines` is only called when the file exists.
- **Unreadable** (file exists but `readAllLines` throws `IOException` — permissions, the path is a directory, mid-read deletion/TOCTOU, decode error): keep `lastKnown` **unchanged**, print the warning, return `lastKnown` (AC #6). Catch `IOException` (and defensively any other `RuntimeException` from reading) — but the catch block must itself only read/return, never write.
This split is why the "unreadable" test forces an `IOException` by pointing the path at a **directory** (portable) rather than relying on filesystem permissions.

### Read-only authority (FR-16 / NFR-6) — structural
- The port has no write method (AC #7) — structural enforcement at the interface. Keep it that way.
- The adapter must use only read APIs. Audit every branch incl. the `catch`: no `Files.write/createFile/delete/move`, no `OutputStream`/`Writer`/`FileChannel` write, no `RandomAccessFile`. The unit test asserts the file bytes are unchanged after a load.
[Source: epics.md#FR-16, #NFR-6; architecture.md "SuppressionFilePort has no write method — structurally enforced".]

### Wiring / dependencies
`SuppressionFileAdapter` is a `@Component`; Spring injects `LogguardProperties` + `TerminalOutputPort` (the `TerminalOutputAdapter` bean). No cycle: `SuppressionFileAdapter → TerminalOutputPort` (a sink with no deps); `PollService → SuppressionFilePort + TerminalOutputPort`. No `DomainServiceConfig` change. The path is read once at construction (the file *contents* are re-read each cycle = hot-reload; the configured path doesn't change at runtime).

### Testing standards
- `SuppressionFileAdapterTest` is pure (no Spring context): `@TempDir`, a hand-written `RecordingTerminal implements TerminalOutputPort` counting `printSuppressionUnreadable()`, construct via the package-private `(String, TerminalOutputPort)` ctor. Static `org.junit.jupiter.api.Assertions.*`. Keep it hermetic.
- Also add the new `printSuppressionUnreadable()` no-op to the existing `RecordingTerminal` fake inside `PollServiceTest` (it implements `TerminalOutputPort`) so that file still compiles.
- Build/run via Bash with JDK 21 (`source "$HOME/.sdkman/bin/sdkman-init.sh"`; `mvn -B -ntp test`; the `mvnw.cmd` wrapper is blocked). Baseline before this story: 52 pass / 1 skipped.

### Project Structure Notes
- UPDATE: `infrastructure/filesystem/SuppressionFileAdapter.java` (real impl), `domain/port/out/TerminalOutputPort.java` (+1 method), `infrastructure/terminal/TerminalOutputAdapter.java` (+1 impl), `src/test/java/.../service/PollServiceTest.java` (RecordingTerminal +1 no-op method).
- NEW: `src/test/java/be/vdab/logguard/infrastructure/filesystem/SuppressionFileAdapterTest.java`.
- No `pom.xml`/`application.yml`/migration change. `SuppressionFilePort.java` is unchanged (already correct — read-only, documented).

### References
- [Source: epics.md#Story 4.3] — acceptance criteria
- [Source: epics.md#FR-14, #FR-15, #FR-16; #NFR-6] — ordering, absent/unreadable handling, read-only authority
- [Source: specs/logguard-ai.allium#ReloadSuppressionFile (rule + @guidance)] — parse format, absent/unreadable behaviour, the exact warning string, the never-write invariant
- [Source: architecture.md#Port Interface Contracts] — `SuppressionFilePort: Set<String> loadHashes() // never throws`; `TerminalOutputPort` method list
- [Source: architecture.md#AR-10] — all terminal output via `TerminalOutputPort`; `System.out` only in `TerminalOutputAdapter`
- [Source: src/main/java/.../filesystem/SuppressionFileAdapter.java] — the Story 2.5 stub being replaced
- [Source: src/main/java/.../terminal/TerminalOutputAdapter.java] — status constants + print style to mirror

### Previous Story Intelligence (Stories 4.2, 4.1, 2.6, 2.5)
- **Story 2.5 stub:** `SuppressionFileAdapter.loadHashes()` returns `Set.of()`; `PollService.poll()` calls it first each cycle for the FR-14 ordering and discards the result — that call site stays unchanged. The stub's own Javadoc already says the real behaviour "never gains a write path (FR-16/NFR-6)".
- **`TerminalOutputAdapter` (Story 2.4/2.6):** status constants `DEGRADED/RECOVERED/WONT_FIX/ESCALATION` (= `⚠️`) are defined and `ESCALATION` is currently unused — use it for the suppression warning. Print style: leading `System.out.println()` blank line, then the message, then `System.out.flush()`.
- **Test fakes:** `PollServiceTest.RecordingTerminal` implements `TerminalOutputPort` and must gain the new method (a no-op counter is fine).
- **Config:** `LogguardProperties.suppressionFilePath()` → `@DefaultValue("./suppression.txt")`; `application.yml` sets `logguard.suppression-file-path: ./suppression.txt`. Read it; never `@Value`.
- **Toolchain:** SDKMAN Java 21.0.11 / Maven 3.9.16; Bash; `mvn -B -ntp test`. Tests stay hermetic.

### Git Intelligence
- Baseline HEAD `192a6d7` "Implement deduplication gate with LLM-analysis caching (Story 4.2)". The dedup gate, `FingerprintService`, and persistence are committed and stable.
- Mirror committed patterns: `@Component` adapter implementing a `domain/port/out` interface with explicit constructor injection (`OpenSearchAdapter`, `PollCheckpointRepositoryAdapter`); pure `@TempDir` unit tests; status-constant usage in `TerminalOutputAdapter`.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8 (Claude Code, dev-story execution)

### Debug Log References

- `mvn -B -ntp test` initially failed with "release version 21 not supported" under the SDKMAN-default JDK; resolved by exporting `JAVA_HOME=$HOME/.jdks/temurin-21.0.11` before the build. Suite then green: 56 pass / 1 skipped (baseline was 52/1; +4 = the new `SuppressionFileAdapterTest`).

### Completion Notes List

- **Task 1 (AC #6):** Added `printSuppressionUnreadable()` to `TerminalOutputPort`; implemented in `TerminalOutputAdapter` reusing the existing `ESCALATION` (`⚠️`) status constant — no inline glyph literal (Story 2.4 AC). Prints exactly `⚠️ Suppression file unreadable — using last known state`, mirroring the blank-line-then-line + `System.out.flush()` style of `printDegraded`/`printRecovery`.
- **Tasks 2/3 (AC #1–#9):** Replaced the Story 2.5 stub in `SuppressionFileAdapter` with real read/parse + hot-reload. `@Autowired` ctor delegates to a package-private `(String, TerminalOutputPort)` ctor for the test. In-memory `lastKnown` (no synchronisation — sequential with the single-threaded poll, FR-14). Absent file → `lastKnown = Set.of()`, no warning (AC #5); successful read → parse + store + return; `IOException`/`RuntimeException` → warn + return unchanged `lastKnown`, never throws (AC #6). Parse rule = substring before first `#`, `strip()`, non-empty into a `LinkedHashSet` — satisfies AC #2/#3/#4 in one pass. Read-only: only `Files.exists`/`Files.readAllLines`; no write API in any branch incl. the catch (AC #8, FR-16).
- **Scope honoured:** the returned set is still discarded by `PollService` (consuming it = Story 4.4). No `PollService` change. Because the adapter never throws, the deferred-work item "Suppression reload result discarded + unguarded" is resolved structurally.
- **Task 4 (AC #10):** New hermetic `SuppressionFileAdapterTest` (`@TempDir`, no Spring) with 4 cases: normal parse (comments stripped, blanks/comments ignored, dup deduped), absent → empty + 0 warnings, unreadable (file replaced by a directory → `IOException` cross-platform) → last-known kept + 1 warning, and bytes-unchanged read-only assertion. Added the `printSuppressionUnreadable()` no-op to `PollServiceTest.RecordingTerminal` so it still compiles.

### File List

- `src/main/java/be/vdab/logguard/domain/port/out/TerminalOutputPort.java` (MODIFIED — +`printSuppressionUnreadable()`)
- `src/main/java/be/vdab/logguard/infrastructure/terminal/TerminalOutputAdapter.java` (MODIFIED — implemented the warning)
- `src/main/java/be/vdab/logguard/infrastructure/filesystem/SuppressionFileAdapter.java` (MODIFIED — real impl replacing the stub)
- `src/test/java/be/vdab/logguard/infrastructure/filesystem/SuppressionFileAdapterTest.java` (NEW)
- `src/test/java/be/vdab/logguard/domain/service/PollServiceTest.java` (MODIFIED — RecordingTerminal no-op)

## Change Log

| Date | Change |
|---|---|
| 2026-06-29 | Story 4.3 drafted via create-story context engine (real SuppressionFile parse + hot-reload + absent/unreadable handling + read-only authority; adds TerminalOutputPort.printSuppressionUnreadable for the FR-15 warning). Status → ready-for-dev. |
| 2026-06-30 | Implemented all tasks (AC #1–#10). Suite green: 56 pass / 1 skipped. Status → review. |
