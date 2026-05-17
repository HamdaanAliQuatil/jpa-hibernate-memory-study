# Findings

Date: 2026-05-17

## Controlled answers to the two research questions

### 1. After `clear()` or `detach()`, do entity values or dirty-check snapshots remain in heap long enough to be recovered?

Yes, in an important qualified sense.

- `detach()` and `clear()` do **not** trigger garbage collection.
- They remove Hibernate's management references from the persistence context.
- In the enhanced harness, the entity, Hibernate `loadedState` snapshot, original note value, and original large `byte[]` payload were all still reachable:
  - immediately after `detach()` / `clear()`
  - after the session closed
  - even after roughly `96 MB` of allocation pressure with no explicit GC
- After forced GC, the entity, `loadedState`, and original large `byte[]` payload were no longer strongly reachable in this setup.

That means there is a real **pre-GC recovery window**. Detached is not the same thing as erased.

What remains unresolved:

- Whether dead objects still appear in the raw heap after they are no longer live.
- Whether the surviving `String` marker after forced GC is due to H2/JDBC retention, string deduplication, or some other non-Hibernate path.

The new `live=false` heap dumps are intended for that next forensic step.

Security meaning:

- This is relevant when an attacker can inspect memory or collect process diagnostics.
- It is not a remote exploit by itself.
- It matters because teams sometimes over-interpret `clear()` / `detach()` as if they also clean memory. They do not.
- In a compromised pod or same-JVM-code-execution scenario, this widens the collection window for sensitive entity data.

### 2. Can cache/timing behavior reveal persistence-context membership?

Yes.

Observed timings from the enhanced run:

| Scenario | avg us | p50 us | p95 us | prepared statements | entity loads |
| --- | ---: | ---: | ---: | ---: | ---: |
| managed-hit | 20.46 | 15.60 | 25.40 | 1 | 1 |
| detach-then-find | 308.83 | 251.80 | 598.50 | 251 | 251 |
| clear-then-find | 278.87 | 240.50 | 455.40 | 250 | 250 |

Interpretation:

- A managed entity lookup is an order of magnitude faster than a detached/cleared lookup in this harness.
- The statement counts show why: the managed-hit path stays in the first-level cache, while the others fall through to SQL.
- So persistence-context membership is a concrete timing oracle, at least for code running in the same JVM and able to measure local latencies.

Security meaning:

- This leaks application state and access patterns, not the entity values themselves.
- The most realistic attacker model is an in-process adversary that can execute code and measure timing with low noise.
- The finding is useful for profiling business flows, detecting session reuse, and chaining with other information leaks.
- It is usually not a strong remote attack on its own.

## Security meaning

### Recovery window

If an adversary already has enough capability to inspect heap state inside the process, `detach()` and `clear()` are not a cleanup primitive. They only change ORM semantics. The object may remain present until GC actually runs.

### Timing oracle

If an adversary can run code in-process, timing can reveal:

- whether a specific entity is already loaded
- whether some code path recently touched that entity
- whether a shared session/persistence context is being reused
- whether a lazy graph is already initialized

That does not automatically become a remote exploit over normal network latency, but it can matter for:

- malicious plugins or extensions
- same-process multi-tenant code
- untrusted scripting inside the server
- attack chaining with other information leaks

## New artifacts produced

The enhanced run can generate:

- live-only heap dumps
- `live=false` heap dumps
- a report with reachability states at:
  - after operation
  - after session close
  - after allocation pressure
  - after forced GC

## Next forensic step

Inspect the `*-all.hprof` files in MAT and search for the exact markers:

- `originalNoteMarker`
- `originalPayloadMarker`
- `replacementNoteMarker`
- `replacementPayloadMarker`

That is the next step needed to move from "still live vs not live" to "still visible anywhere in the raw heap dump".

For a more complete threat-model discussion, see [THREAT_MODEL.md](./THREAT_MODEL.md).
