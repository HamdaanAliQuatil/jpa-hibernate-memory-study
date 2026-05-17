# JPA/Hibernate Memory Study

This is a small standalone Hibernate harness for three related questions:

1. After `detach()` or `clear()`, do dirty-check snapshots or entity values remain reachable long enough to be recovered?
2. Across `detach()`, `clear()`, `close()`, `commit()`, and `rollback()`, how much of an initialized object graph remains reachable before GC?
3. Can timing differences reveal persistence-context membership?

The current state of the research is:

- `detach()` / `clear()` are confirmed to create an ORM-state transition, not a memory-erasure event.
- There is a measurable pre-GC recovery window where detached data may still be present and recoverable from process memory.
- In the phase 2 graph runs, initialized associations materially expanded what remained recoverable before GC.
- In the same graph runs, lifecycle boundary mattered less than whether the association had actually been initialized.
- Persistence-context membership is observable via timing inside the same JVM/process boundary.
- Neither finding should be described as a standalone remote exploit. Both depend heavily on attacker position and available access.

## What it does

- Boots Hibernate ORM 6.4 with an in-memory H2 database.
- Persists:
  - a single `SecretNote` entity with a large `byte[]` payload
  - a `GraphRoot` with either one child or three children
- Runs a baseline single-entity retention experiment using `detach()` and `clear()`.
- Runs a phase 2 graph-retention matrix across:
  - `detach(entity)`
  - `clear()`
  - `session.close()`
  - `transaction.commit()`
  - `transaction.rollback()`
- Compares graph scenarios:
  - lazy collection not initialized
  - single child initialized
  - multi-child graph initialized
- Captures a reachability timeline:
  - immediately after `detach()` / `clear()`
  - after the session closes
  - after allocation pressure without explicit GC
  - after forced GC
- Can emit both live-only heap dumps and `live=false` dumps for dead-object forensics.
- Measures repeated `find()` latency in three scenarios:
  - entity already managed in the first-level cache
  - entity detached before each lookup
  - session cleared before each lookup

## Run

```powershell
& 'C:\Gradle\gradle-7.6\bin\gradle.bat' run
```

Optional live-only heap dumps:

```powershell
& 'C:\Gradle\gradle-7.6\bin\gradle.bat' run --args="--heap-dumps"
```

Optional live and non-live heap dumps:

```powershell
& 'C:\Gradle\gradle-7.6\bin\gradle.bat' run --args="--heap-dumps --non-live-heap-dumps"
```

Skip the allocation-pressure window probe:

```powershell
& 'C:\Gradle\gradle-7.6\bin\gradle.bat' run --args="--skip-window-probe"
```

## Output

- Console report
- Generated markdown report in `build/reports/jpa-memory-study/findings.md`
- Optional `.hprof` dumps in the same report directory
- Research notes:
  - [FINDINGS.md](./FINDINGS.md)
  - [THREAT_MODEL.md](./THREAT_MODEL.md)

## Interpretation limits

- `detach()` and `clear()` do not trigger garbage collection on their own.
- Detached objects remain fully usable to any code that still has a reference to them.
- This harness answers reachability and retention questions inside the managed heap. It does not prove anything about freed-page byte remnants after GC.
- The graph results show what happened in this setup with Hibernate 6.4 and H2. They should be read as controlled measurements, not universal JVM rules.
- `live=false` heap dumps are a suitable next step for checking whether dead objects remain visible in heap artifacts.
- HPROF is still a managed-heap artifact, not a raw RAM acquisition tool. It is useful for object reachability and retained content, but not for proving byte remnants in freed pages.

## Repository hygiene

- This repository is intended to commit source and written findings.
- Generated artifacts such as `build/`, `.gradle/`, and `.hprof` dumps should stay local and are ignored by git.
- The root-level notes (`FINDINGS.md` and `THREAT_MODEL.md`) summarize the experimental results and threat-model implications.
