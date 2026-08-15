# Full Review — ChronoTask Workspace

*Consolidated report from Architecture, Design, Security, and Concurrency review subagents. Read-only — no source files were modified as part of this review.*

## Executive Summary

ChronoTask is a small, thoughtfully-documented scheduling library built on a hand-rolled thread pool (`ElasticThreadPool`, consumed as a Maven dependency). The thread-pool module itself is solid — its own concurrency invariants are well-documented and its dedicated test suite (confirmed passing) shows real prior hardening. The problems concentrate **one layer up, in the `chrono` package**: a High-severity functional defect (`PoolExecutor` never actually starts its pool) went undetected because the root project's test suite is **never executed by Maven at all** — a missing `<testSourceDirectory>`/JUnit wiring in the root `pom.xml` means `mvn test` reports "No tests to run" while silently skipping every test in `src-test/`. Independently, the concurrency and design reviews both surfaced a second class of bug: `FutureChronoTask` and `ChronoTask` don't propagate failure/success state or synchronize configuration correctly across threads, so callers can be handed futures that hang forever, and periodic-mode scheduling can race two overlapping executions of the same task. None of these are security vulnerabilities in the traditional sense (no injection, secrets, or network surface exists in this pure-Java library), but several design gaps (unbounded thread growth, no backpressure) are DoS foot-guns a consuming application could trigger.

## Findings by Severity

| ID | Severity | Category | Location | Finding |
|---|---|---|---|---|
| **F1** | **High** | Architecture | [PoolExecutor.java](src/org/adrian/chrono/PoolExecutor.java) | ✅ **FIXED** — Constructors call `ElasticThreadPool.builder()....build()` instead of `.start()` — the pool never leaves `NOT_RUNNING`, so every submitted task is silently rejected. Constructors now call `.start()`. |
| **F2** | **High** | Architecture | [pom.xml](pom.xml) (root) | ✅ **FIXED** — No `<testSourceDirectory>`/JUnit/Surefire config — `mvn test` reports "No tests to run"; all of `src-test/` is never compiled or executed. This is why the above defect shipped undetected. Added `<testSourceDirectory>` and JUnit Jupiter/Platform Suite test dependencies. |
| **F3** | **High** | Design + Concurrency | [FutureChronoTask.java](src/org/adrian/chrono/FutureChronoTask.java#L61-L69) `start()` | ✅ **FIXED** — The boolean result of `ChronoTask.start()` is discarded. If the underlying start fails (e.g. pool shutdown races with `start()`), the caller gets a `CompletableFuture` that never completes — `future.get()` hangs forever. `start()` now returns `null` instead of a hanging future when the underlying start fails. |
| **F4** | **High** | Design + Concurrency | [ChronoTask.java](src/org/adrian/chrono/ChronoTask.java) setters vs. `start()`/`Timer` | ✅ **FIXED** — Config fields (`initialDelay`, `periodicDelay`, `repetitiveDelay`, `name`) are written without `synchronized`/`volatile` while read from `start()` and the timer thread — a TOCTOU + cross-thread visibility race if configuration and `start()` happen on different threads. Fields are now `volatile` and the setters are `synchronized`. |
| **F5** | **High** | Concurrency | [ChronoTask.java](src/org/adrian/chrono/ChronoTask.java) `Timer.loopTimer()` | ✅ **FIXED** — In periodic mode, the next firing is scheduled *before* the current execution completes (only submitted, not awaited), so a slow task can still run concurrently with subsequent invocations — this overlap itself is intentional/by design. What was fixed: `FutureChronoTask` now claims and completes the exposed future right after an execution's outcome is known (completion order) instead of at dispatch time (start order), so the future handed out by `start()`/`getNextResult()` is always resolved by whichever overlapping execution finishes first. |
| **F7** | Medium | Architecture + Design | [ChronoTaskBuilder.java](src/org/adrian/chrono/ChronoTaskBuilder.java) / [FutureChronoTaskBuilder.java](src/org/adrian/chrono/FutureChronoTaskBuilder.java) | ✅ **FIXED** — ~90% duplicated fields, validation, and mutual-exclusion logic between the two builders — any invariant change must be applied twice. |
| **F8** | Medium | Architecture | `ChronoTask.Timer` in [ChronoTask.java](src/org/adrian/chrono/ChronoTask.java) | ✅ **FIXED** — Scheduling used `LocalDateTime.now()` (wall-clock) rather than a monotonic clock — vulnerable to NTP/manual clock adjustments causing execution bursts or stalls. All call sites now use a `MonotonicClock` backed by `System.nanoTime()`, which is immune to wall-clock changes. |
| **F10** | Medium | Design | `ChronoTask`/builders | ✅ **FIXED** — "Periodic vs. repetitive vs. one-shot" modeled as two nullable, mutually-exclusive `Duration` fields (primitive obsession) instead of a sealed `Schedule` type — the invariant is enforced ad hoc in 3 places. Replaced with a sealed `Schedule` type (`Schedule.Periodic`, `Schedule.Repetitive`, `Schedule.OneShot`); mutual exclusion is now guaranteed by construction (one field, one type). `setPeriodicDelay`/`setRepetitiveDelay` retained as `@Deprecated` adapters. |
| **F11** | Medium | Design | `ChronoTask.Timer` calculation methods | ✅ **FIXED** — Next-execution-time policy logic is buried in a private inner class also handling thread lifecycle — can't be unit-tested without a real timer thread. The policy is now expressed as polymorphic methods (`afterDispatch`/`afterCompletion`) on the `Schedule` type, directly unit-tested in `ScheduleTest` without a timer thread. |
| **F14** | Medium | Design | [ThreadExecutor.java](src/org/adrian/chrono/ThreadExecutor.java) `threadFactory` | ✅ **FIXED** — `threadFactory` was a plain field read from timer/worker threads but written from the configuring thread — no JMM visibility guarantee, so a reconfiguration after scheduling starts may never be seen by executing threads. Field is now `volatile` (sufficient: single unconditional write needs visibility, not atomicity against a check-then-act). `setThreadFactory` now also rejects `null` with `Objects.requireNonNull` (F27 precedent) and documents the visibility contract; the injected factory is treated as an opaque, thread-safe collaborator. Regression tests added: a null-argument assertion, and a functional swap test confirming a swapped factory is used by subsequent `run(...)` invocations. |
| **F15** | Medium | Design | [FutureChronoTask.java](src/org/adrian/chrono/FutureChronoTask.java) `stop()` | ✅ **FIXED** — Leaves any pending `CompletableFuture` permanently uncompleted with no documentation of this hang-forever behavior. `stop()` now cancels the pending future; the consumer's claim-and-complete is guarded by a `synchronized` check-then-swap that skips if the future is already done (cancelled), preventing orphan futures from `getAndSet`. `start()` installs a fresh future when the current one is already done. |
| **F19** | Low | Architecture + Design | [AbstractTaskBuilder.java](chrono-task-core/src/org/adrian/chrono/AbstractTaskBuilder.java) `setName()` | ✅ **FIXED** — `new String(name)` "defensive copy" was a no-op cargo-cult pattern (`String` is immutable) — provided no benefit, and the javadoc's justification was technically incorrect. `setName` now stores the reference directly via `Objects.requireNonNull(name, "name")` (safe because `String` is immutable), and the Javadoc rationale is corrected. The single implementation lives in the shared `AbstractTaskBuilder` (per the F7 dedup), so both concrete builders inherit the fix. |
| **F20** | Low | Architecture | [ChronoTaskBuilder.java](chrono-task-core/src/org/adrian/chrono/ChronoTaskBuilder.java) / [FutureChronoTaskBuilder.java](chrono-task-core/src/org/adrian/chrono/FutureChronoTaskBuilder.java) constructors | ✅ **FIXED** — Non-standard `@warning` Javadoc tag won't render correctly in generated docs. Replaced with a standard one-sentence constructor summary followed by a `<p>` paragraph containing the warning prose, consistent with the `<p>` convention used elsewhere in the codebase. |
| **F23** | Low | Design | [PoolExecutor.java](src/org/adrian/chrono/PoolExecutor.java) | ⚪ **ACCEPTED** — Re-exposes most of `AbstractExecutorService`'s lifecycle surface as manual forwarding methods. Accepted: the forwarding methods are intentional public library API (consumers may rely on them); removing them would be a breaking change. The hierarchy and pool encapsulation are preserved to keep the public surface stable and non-leaky; the residual cost is a few trivial one-liners mirroring the effectively-frozen `ExecutorService` signatures. |
| **F24** | Low | Design | `ChronoTask` setters | ✅ **FIXED** — Four near-identical guard blocks (`setName`, `setInitialDelay`, `setSchedule`, `setMaxConcurrentExecutions`) each repeated the `if (!isRunning()) { …; return true; } return false;` pattern. Extracted a single `applyIfStopped(Runnable)` helper that centralizes both the running-guard and the `synchronized` monitor; each setter is now a one-liner delegating to it. Lock semantics are unchanged (helper is `synchronized` on `this`); `setSchedule`'s `Objects.requireNonNull` still runs inside the guard, preserving the running-check-before-null-check ordering. |
| **F26** | Low | Security + Concurrency | [ThreadExecutor.java](src/org/adrian/chrono/ThreadExecutor.java) | 🟡 **MITIGATED** — Spawns a new thread per execution with no cap/backpressure — combinable with the periodic-overlap race for unbounded thread pile-up. Opt-in `ChronoTask.setMaxConcurrentExecutions(n)` (via `ChronoTaskBuilder`/`FutureChronoTaskBuilder`) now bounds concurrent overlap using a `Semaphore`-based throttle in `Timer.loopTimer()`/`executeTask()`; default remains unbounded (`Integer.MAX_VALUE`) to preserve existing behavior. |
| **F27** | Low | Security | `Duration` setters in both builders | ✅ **FIXED** — No `Objects.requireNonNull` before `.isNegative()` — `null` throws an unguarded NPE instead of a clear `IllegalArgumentException`. `setInitialDelay` now has an explicit `Objects.requireNonNull`; `setPeriodicDelay`/`setRepetitiveDelay` validation moved into the `Schedule.Periodic`/`Schedule.Repetitive` record compact constructors, which reject `null` with `NullPointerException` and negative values with `IllegalArgumentException`. |
| **F28** | Low | Security | `ChronoTask.Timer.executeTask()` | ✅ **FIXED** — `getUncaughtExceptionHandler()` was dereferenced without a null check — reachable only via a misbehaving custom `Thread` subclass. The handler is now stored in a local variable and only invoked when non-null; a `null` handler (e.g. from a custom `Thread` subclass) is silently skipped instead of throwing an NPE. |
| **F29** | Low | Concurrency | `ChronoTask.start()` | ✅ **FIXED** — `start()` held the instance monitor for its entire body, including the `executor.run()` dispatch — a blocking executor (e.g. `CallerRunsPolicy`, bounded queue, or custom `AbstractExecutor` subclass) would hold the lock indefinitely, blocking `stop()` and all setters. The `executor.run()` call is now outside the `synchronized` block; state is set to `RUNNING` and the next-execution time is computed inside the monitor, then the monitor is released before dispatching to the executor. A `RejectedExecutionException` re-acquires the monitor to roll back to `STOPPED`. Field reads outside the monitor are safe (`name`/`initialDelay`/`state` are `volatile`). |
| **F30** | Informational | Security | Thread naming (`PoolExecutor`, `ChronoTask`) | ✅ **FIXED** — Task/timer names were used verbatim as thread names — a latent info-exposure concern if names ever derive from untrusted input and get logged. A new `ThreadNames.sanitize(String)` utility enforces an allow-list (`^[a-zA-Z0-9_\-#:.\[\]{1,128}$`) and rejects names that violate the pattern with `IllegalArgumentException`. `AbstractTaskBuilder.setName()` and `PoolExecutor(String)` now validate all task/pool names before they reach `Thread.setName()`. |
| **F31** | Low | Design + Concurrency | [FutureChronoTask.java](src/org/adrian/chrono/FutureChronoTask.java) / [ChronoTask.java](src/org/adrian/chrono/ChronoTask.java) `Timer.setNextRepetetiveExecutionTime()` | ✅ **FIXED** — Residual of F15. When a one-shot or repetitive task self-terminates internally (via `ChronoTask.stop()` called from `Timer`'s runnable `finally` when `afterCompletion` returns `null`), `FutureChronoTask.stop()` was never called, leaving the orphan `CompletableFuture` hanging forever. Fixed with a termination callback: `ChronoTask` now fires an `onTermination` callback on every transition to `STOPPED` (covering both self-termination via `runTimer`'s `finally` and the rejected-start path) **outside** the `ChronoTask` monitor; `FutureChronoTask` registers a **`synchronized`** callback that cancels the orphan future, guarded by `isRunning()` so it never cancels a future belonging to a new `start()`. Firing outside the CT monitor avoids inverting the established FCT→CT lock order, eliminating the race where `start()` could return a cancelled future and the block risk of holding the CT monitor during the callback. New regression tests: `testOneShotSelfTerminationCancelsOrphanFuture` and `testRestartAfterSelfTerminationReturnsUsableFuture`. |

## Findings by Category

### Architecture Review (full report)

#### Overview

ChronoTask is a small, well-documented Java library providing task-centric (as opposed to service-centric) scheduled execution, built as two independently versioned Maven artifacts: the `chrono` package at the repository root ([src/org/adrian/chrono](src/org/adrian/chrono)) and a `threadpool` package dependency (`elastic-thread-pool`). The design follows classic Strategy/Builder/Template-Method patterns: `ChronoTask` owns lifecycle and scheduling state, `AbstractExecutor` abstracts *how* work is executed (dedicated virtual threads vs. a shared pool), and `ChronoTaskBuilder`/`FutureChronoTaskBuilder` provide fluent, validated construction. The custom thread pool module is a from-scratch `AbstractExecutorService` implementation with an elastic worker count, a dedicated `WorkerAdjuster` coordinator, and a small state-machine enum (`ThreadPoolState`) driving per-state polling behavior.

Despite the small size, the codebase shows unusually thorough internal documentation (invariants spelled out in Javadoc, e.g. the `workerDemand` accounting in `ElasticThreadPool.java`). However, verification during this review surfaced a critical, concrete defect and a build-configuration gap that together mean a large fraction of the project's own test suite has likely never executed against the real build.

#### Strengths

- **Clear separation of concerns**: task definition (`ChronoTask`), configuration (`ChronoTaskBuilder`), and execution strategy (`AbstractExecutor` + its two implementations) are cleanly separated — a textbook Strategy pattern.
- **Good encapsulation**: mutator methods on `ChronoTask` are `protected`/package-private; builders have protected constructors only reachable via factory methods.
- **One-directional dependency**: `chrono` depends on `threadpool`, never the reverse — no circular coupling.
- **Thoughtful concurrency documentation** in the thread pool module: the `workerDemand` invariant enumerates every mutating event (A–G); `ThreadPoolState` is decoupled from live objects via an immutable `WorkerPollContext` record.
- **Parameterized cross-executor tests**: run the same behavioral assertions against both executor implementations.
- **Deliberate memory-safety design**: `Duration`/`String` defensive copying in builders, explicit warnings against strong references in closures.

#### Findings

1. **F1 — High — ✅ FIXED** — `PoolExecutor` never starts its owned thread pool. Constructors call `.build()` not `.start()`, so the pool stays `NOT_RUNNING` and every submission is rejected and silently swallowed by `ChronoTask.start()`. Constructors now call `.start()`.
2. **F2 — High — ✅ FIXED** — Root module's test suite is disconnected from the Maven build. `mvn test` reports "No tests to run"; no `<testSourceDirectory>`, JUnit dependency, or Surefire config in root `pom.xml`. This directly explains why F1 went undetected. `testSourceDirectory` and JUnit test dependencies added to root `pom.xml`.
3. **F7 — Medium** — Significant duplication between `ChronoTaskBuilder` and `FutureChronoTaskBuilder`.
4. **F8 — Medium — ✅ FIXED** — Wall-clock-based scheduling (`LocalDateTime.now()`) was vulnerable to system clock changes; now uses a `MonotonicClock` backed by `System.nanoTime()`.
5. **F20 — Low — ✅ FIXED** — Non-standard `@warning` Javadoc tag won't render correctly; replaced with standard Javadoc prose in both builder constructors.
6. **F19 — Low — ✅ FIXED** — `new String(name)` "defensive copy" didn't achieve its stated purpose (`String` is immutable). `setName` now stores the reference directly via `Objects.requireNonNull` and the Javadoc rationale is corrected (consolidated in `AbstractTaskBuilder`).

#### Recommendations

1. Fix `PoolExecutor` to call `.start()`, add a regression test. (F1)
2. Wire the root `pom.xml` for tests (this is what would have caught F1). (F2)
3. Extract a shared builder base to remove duplication. (F7)
4. ~~Consider monotonic-clock scheduling.~~ ✅ Fixed — `MonotonicClock` (F8)
5. ~~Replace `@warning` with a standard doc block; drop the pointless `String` copy.~~ ✅ Fixed — `@warning` converted to standard Javadoc prose (F20); `String` copy dropped (F19)

---

### Design Review (full report)

#### Scope

Class/module-level review of the chrono core and thread pool packages.

#### Strengths

- **Strategy/Template pattern**: `AbstractExecutor` is a small, intention-revealing template; `PoolExecutor`/`ThreadExecutor` are clean, focused subclasses.
- **State pattern in `ThreadPoolState`**: polling behavior per state is expressed as enum constants, decoupled via an immutable record snapshot.
- **`WorkerAdjuster`** is well-scoped and single-responsibility.
- **`DurationCopier`** is a narrowly-scoped, well-justified utility — the standard the rest of the codebase should be held to.
- **Composition over inheritance**: `FutureChronoTask` wraps rather than subclasses `ChronoTask`.
- **`getCompletedTasksCount()`** correctly combines an accumulator with live worker snapshots under one lock, avoiding a classic race.

#### Findings

**High**

1. **F3 — ✅ FIXED** — `FutureChronoTask.start()` silently discards the underlying start result — can hand out a future that never completes. Now returns `null` instead when the start fails.
2. **F4 — ✅ FIXED** — Configuration setters on `ChronoTask` are not synchronized against `start()`, risking a visibility/data race across threads. Fields are now `volatile` and setters `synchronized`.

**Medium**

3. **F7** — Significant duplication between `ChronoTaskBuilder` and `FutureChronoTaskBuilder`.
4. **F10 — ✅ FIXED** — Nullable, mutually-exclusive `Duration` fields replaced with a sealed `Schedule` type; mutual exclusion is now by construction.
5. **F11 — ✅ FIXED** — Scheduling-calculation logic pulled into the `Schedule` type's polymorphic `afterDispatch`/`afterCompletion` methods, directly unit-testable.
6. **F14 — ✅ FIXED** — `ThreadExecutor.threadFactory` is now `volatile`; `setThreadFactory` rejects `null` and documents the visibility contract.
7. **F15 — ✅ FIXED** — `stop()` now cancels the pending `CompletableFuture`; the consumer skips claiming an already-done future to prevent orphans.

**Low**

10. **F19 — ✅ FIXED** — Misguided/incorrect defensive copy of `String` (mirrors F19 from the Architecture review); `setName` now stores the reference directly and the Javadoc is corrected.
12. **F23 — ⚪ ACCEPTED** — `PoolExecutor` re-exposes most of `AbstractExecutorService`'s lifecycle API as forwarding methods. Accepted: the forwarding methods are intentional public library API; the hierarchy and pool encapsulation are preserved to keep the public surface stable and non-leaky. |
13. **F24 — ✅ FIXED** — Repeated `if (!isRunning()) {...}` guard pattern across four setters; now a single `applyIfStopped(Runnable)` helper.

#### Recommendations (prioritized)

1. Fix `FutureChronoTask.start()` to propagate failure. (F3)
2. ~~Synchronize `ChronoTask`'s configuration setters; bundle with guard-clause dedup.~~ ✅ Fixed — setters `synchronized` via `applyIfStopped` helper (F4, F24)
3. ~~Extract a shared base for the two builders; consider a sealed `Schedule` type.~~ ✅ Fixed — `AbstractTaskBuilder` shared base + sealed `Schedule` (F7, F10)
4. ~~Pull scheduling-calculation logic into an independently testable policy component.~~ ✅ Fixed — `Schedule.afterDispatch`/`afterCompletion` (F11)
5. ~~Make `ThreadExecutor.threadFactory` volatile.~~ ✅ Fixed — `volatile` + `Objects.requireNonNull` + visibility Javadoc (F14)
6. ~~Decide/document `FutureChronoTask.stop()`'s contract for pending futures.~~ ✅ Fixed — `stop()` cancels pending future; consumer guards against orphans (F15)
7. ~~Drop the pointless `new String(name)` copy.~~ ✅ Fixed — `setName` stores the reference directly via `Objects.requireNonNull` (F19)
8. ~~Lower priority: reconsider the forwarding surface, rename `ChronoTask.State.SHUTDOWN`.~~ ⚪ Accepted (F23).

---

### Security Review (full report)

#### Scope

Core chrono package, custom thread pool, Maven build files, and test suites. `target/` excluded.

No secrets, hardcoded credentials, network/HTTP calls, SQL, deserialization, or reflection abuse were found in production code. No external runtime dependencies exist (only JUnit at test scope), so there's no transitive-dependency CVE surface beyond Maven plugin versions.

#### Findings

1. **F26 — Low–Medium — 🟡 MITIGATED** — `ThreadExecutor` creates one new thread per execution with no throttling; combined with short intervals or a platform-thread factory, unbounded thread creation is possible. *(CWE-400)* Opt-in `setMaxConcurrentExecutions(n)` now bounds concurrent overlap; default remains unbounded.
2. **F27 — Low — ✅ FIXED** — Missing null-validation on `Duration` setters — `null` throws an unguarded NPE instead of a clear `IllegalArgumentException`. *(CWE-476)* `setInitialDelay` now has `Objects.requireNonNull`; `setPeriodicDelay`/`setRepetitiveDelay` validation moved into the `Schedule` record constructors.
3. **F28 — Low — ✅ FIXED** — Uncaught-exception handler dereferenced without null-safety in `Timer.executeTask()` — reachable only via a misbehaving custom `Thread` subclass. *(CWE-476)* The handler is now stored in a local variable and only invoked when non-null; a `null` handler is silently skipped instead of throwing an NPE.
4. **F30 — Informational — ✅ FIXED** — Default worker/pool thread names use task names verbatim — minor info-exposure risk only if names ever derive from untrusted input and are logged. *(A09:2021)* A new `ThreadNames.sanitize(String)` utility enforces an allow-list (`^[a-zA-Z0-9_\-#:.\[\]{1,128}$`), rejecting names that violate the pattern with `IllegalArgumentException`; `AbstractTaskBuilder.setName()` and `PoolExecutor(String)` now validate all names before they reach `Thread.setName()`.

#### Summary

The classic high-severity OWASP categories (Injection, Broken Auth, Cryptographic Failures, SSRF, Broken Access Control) are largely not applicable to this pure-Java scheduling/concurrency library. The realistic residual risk in this repository is resource exhaustion via unbounded thread creation paths in chrono-side execution strategy choices.

**Top priorities**: ~~add null-guards to `Duration` setters~~ *(fixed — F27)*; document and constrain unbounded thread creation risk in `ThreadExecutor`.

---

### Concurrency Review (full report)

#### Scope

Chrono layer, hand-rolled thread pool, and their test suites. `target/` excluded, no files modified.

The `ElasticThreadPool` implementation is unusually well-documented about its own concurrency invariants, which materially helped this review — most findings below are in areas *not* covered by that self-documentation, or one layer up in the chrono package.

#### Findings

1. **F5 — High — ✅ FIXED** — Overlapping periodic executions share task state: `loopTimer()` schedules the next firing *before* the current execution completes (only submitted, not awaited) — this overlap is intentional and remains by design; it is *not* itself a defect. The defect was the resulting `FutureChronoTask` `nextResult`/`lastResult` race, which mismatched futures to results depending on start order. Fixed by relocating the atomic future-claim to completion time, so the exposed future is always resolved by whichever overlapping execution finishes first. Repetitive mode was never affected (inherently serialized).
2. **F3 — High — ✅ FIXED** — `FutureChronoTask.start()` ignores `ChronoTask.start()`'s return value; if the underlying start fails (e.g. racing with pool shutdown), a caller gets a future that never completes. Now returns `null` in that case.
3. **F4 — Medium — ✅ FIXED** — Task configuration fields (`initialDelay`, `periodicDelay`, `repetitiveDelay`, `name`) are neither `volatile` nor synchronized — TOCTOU and cross-thread visibility risk against `start()`/`Timer`. Fields are now `volatile` and setters `synchronized`.
4. **F29 — Low — ✅ FIXED** — `ChronoTask.start()` can no longer block the caller indefinitely on a congested/unresponsive executor. The `executor.run()` dispatch is now outside the `synchronized` block; state mutation and next-execution-time computation remain inside the monitor, and a `RejectedExecutionException` re-acquires it to roll back.
5. **F26 — Low — 🟡 MITIGATED** — `ThreadExecutor` has no backpressure/thread-count bound; combined with F5's intentional periodic overlap, a slow periodic task can accumulate unbounded threads over time. Opt-in `setMaxConcurrentExecutions(n)` now bounds concurrent overlap (default remains unbounded). Note: this is independent of the F5 `FutureChronoTask` result-correctness fix — overlap itself is still permitted (and unbounded by default), only now safe with respect to `nextResult`/`lastResult`.

#### Summary

**Overall risk: Moderate.** The higher-severity issues remain concentrated in the **chrono layer**, particularly start/future correctness and configuration visibility under concurrency.

**Top priorities**: ~~fix overlapping periodic executions (F5)~~ *(fixed)*; ~~propagate `ChronoTask.start()`'s return value through `FutureChronoTask.start()` (F3)~~ *(fixed)*; ~~make `ChronoTask`'s delay/name fields volatile (F4)~~ *(fixed)*; clarify/mitigate potential caller blocking in `ChronoTask.start()` (F29).

## Top Priorities

1. ✅ **FIXED** — **(F1, F2) Fix `PoolExecutor` to call `.start()`, and wire the root `pom.xml` for tests.** These two Architecture findings are causally linked: the pool-executor bug is a complete functional break in a primary public API, and it shipped because the test suite that would have caught it (`src-test/`) is never compiled or run by `mvn test`. Fix the build wiring first (or alongside), then add a regression test asserting successful execution immediately after construction.
2. ✅ **FIXED** — **(F3) Propagate `ChronoTask.start()`'s success/failure through `FutureChronoTask.start()`.** Raised independently by both Design and Concurrency reviews — currently a caller can be handed a `CompletableFuture` that hangs forever whenever the underlying start silently fails (e.g. racing with pool shutdown).
3. ✅ **FIXED** — **(F5) Fix the `FutureChronoTask` result-correctness race under overlapping periodic executions.** The most subtle correctness bug found: a slow task on a short periodic interval can run concurrently with itself; the future claim is now performed at completion time (instead of dispatch time), so the exposed future is always resolved by whichever overlapping execution finishes first. Note: overlap itself in `loopTimer()` remains intentional by design and was not removed — only the resulting result-correctness race was closed.
4. ✅ **FIXED** — **(F4) Synchronize/`volatile`-ize `ChronoTask`'s configuration fields.** Also flagged independently by two reviews — a genuine cross-thread visibility gap in an otherwise carefully synchronized class.
