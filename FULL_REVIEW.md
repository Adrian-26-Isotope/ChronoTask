# Full Review — TimedTask Workspace

*Consolidated report from Architecture, Design, Security, and Concurrency review subagents. Read-only — no source files were modified as part of this review.*

## Executive Summary

TimedTask is a small, thoughtfully-documented scheduling library built on a hand-rolled thread pool (`CustomThreadPool`, consumed as a git submodule). The thread-pool module itself is solid — its own concurrency invariants are well-documented and its dedicated test suite (confirmed passing) shows real prior hardening. The problems concentrate **one layer up, in the `timer` package**: a High-severity functional defect (`TimedTaskPoolExecutor` never actually starts its pool) went undetected because the root project's test suite is **never executed by Maven at all** — a missing `<testSourceDirectory>`/JUnit wiring in the root `pom.xml` means `mvn test` reports "No tests to run" while silently skipping every test in `src-test/`. Independently, the concurrency and design reviews both surfaced a second class of bug: `FutureTimedTask` and `TimedTask` don't propagate failure/success state or synchronize configuration correctly across threads, so callers can be handed futures that hang forever, and periodic-mode scheduling can race two overlapping executions of the same task. None of these are security vulnerabilities in the traditional sense (no injection, secrets, or network surface exists in this pure-Java library), but several design gaps (unbounded thread growth, no backpressure) are DoS foot-guns a consuming application could trigger.

## Findings by Severity

| Severity | Category | Location | Finding |
|---|---|---|---|
| **High** | Architecture | [TimedTaskPoolExecutor.java](src/adrian/os/java/timer/TimedTaskPoolExecutor.java) | Constructors call `CustomThreadPool.builder()....build()` instead of `.start()` — the pool never leaves `NOT_RUNNING`, so every submitted task is silently rejected. |
| **High** | Architecture | [pom.xml](pom.xml) (root) | No `<testSourceDirectory>`/JUnit/Surefire config — `mvn test` reports "No tests to run"; all of `src-test/` is never compiled or executed. This is why the above defect shipped undetected. |
| **High** | Design + Concurrency | [FutureTimedTask.java](src/adrian/os/java/timer/FutureTimedTask.java#L61-L69) `start()` | The boolean result of `TimedTask.start()` is discarded. If the underlying start fails (e.g. pool shutdown races with `start()`), the caller gets a `CompletableFuture` that never completes — `future.get()` hangs forever. |
| **High** | Design + Concurrency | [TimedTask.java](src/adrian/os/java/timer/TimedTask.java) setters vs. `start()`/`Timer` | Config fields (`initialDelay`, `periodicDelay`, `repetitiveDelay`, `name`) are written without `synchronized`/`volatile` while read from `start()` and the timer thread — a TOCTOU + cross-thread visibility race if configuration and `start()` happen on different threads. |
| **High** | Concurrency | [TimedTask.java](src/adrian/os/java/timer/TimedTask.java) `Timer.loopTimer()` | In periodic mode, the next firing is scheduled *before* the current execution completes (only submitted, not awaited) — a slow task can cause two invocations to run concurrently, racing on `FutureTimedTask.nextResult`/`lastResult`. |
| Medium | Architecture | [pom.xml](pom.xml), [.gitmodules](.gitmodules), [.gitignore](.gitignore) | No real Maven reactor between root and the `CustomJavaThreadPool` submodule; local-only build helpers (`.mvn/settings.xml`, `mvn-jdk25.bat`) are gitignored — a fresh clone has no documented, reproducible build path. |
| Medium | Architecture + Design | [TimedTaskBuilder.java](src/adrian/os/java/timer/TimedTaskBuilder.java) / [FutureTimedTaskBuilder.java](src/adrian/os/java/timer/FutureTimedTaskBuilder.java) | ~90% duplicated fields, validation, and mutual-exclusion logic between the two builders — any invariant change must be applied twice. |
| Medium | Architecture | `TimedTask.Timer` in [TimedTask.java](src/adrian/os/java/timer/TimedTask.java) | Scheduling uses `LocalDateTime.now()` (wall-clock) rather than a monotonic clock — vulnerable to NTP/DST/manual clock adjustments causing execution bursts or stalls. |
| Medium | Architecture | [CustomThreadPool.java](external/CustomJavaThreadPool/src/adrian/os/java/threadpool/CustomThreadPool.java) | Duplicates most of `ThreadPoolExecutor`'s elastic-sizing capability via bespoke concurrency machinery, with no documented rationale for why the JDK implementation wasn't sufficient. |
| Medium | Design | `TimedTask`/builders | "Periodic vs. repetitive vs. one-shot" modeled as two nullable, mutually-exclusive `Duration` fields (primitive obsession) instead of a sealed `Schedule` type — the invariant is enforced ad hoc in 3 places. |
| Medium | Design | `TimedTask.Timer` calculation methods | Next-execution-time policy logic is buried in a private inner class also handling thread lifecycle — can't be unit-tested without a real timer thread. |
| Medium | Design | [CustomThreadPool.java](external/CustomJavaThreadPool/src/adrian/os/java/threadpool/CustomThreadPool.java) `workerDemand` | Counter correctness depends on a 5-call-site, comment-only "event A/C/D/E/G" protocol with no compiler/test enforcement. |
| Medium | Design | `CustomThreadPool.workerDemand` field | Declared `protected` on a non-`final` class — any subclass can directly corrupt the fragile counter invariant above. |
| Medium | Design | [TimedTaskThreadExecutor.java](src/adrian/os/java/timer/TimedTaskThreadExecutor.java) `threadFactory` | Not `volatile`; reconfiguring after scheduling starts has no visibility guarantee to executing threads. |
| Medium | Design | [FutureTimedTask.java](src/adrian/os/java/timer/FutureTimedTask.java) `stop()` | Leaves any pending `CompletableFuture` permanently uncompleted with no documentation of this hang-forever behavior. |
| Medium | Security | [CustomThreadPool.java](external/CustomJavaThreadPool/src/adrian/os/java/threadpool/CustomThreadPool.java) default `maxThreads` | Defaults to `Integer.MAX_VALUE` with an unbounded queue — unbounded thread growth is a DoS foot-gun if task volume is influenced by untrusted input. |
| Medium | Concurrency | [CustomThreadPool.java](external/CustomJavaThreadPool/src/adrian/os/java/threadpool/CustomThreadPool.java) `idleTime` handling | `Duration.ZERO` idle time (settable, and the coerced value for negative durations) causes core workers to busy-spin (non-blocking `poll(0, NANOSECONDS)`). |
| Low–Medium | Concurrency | [CustomThreadPool.java](external/CustomJavaThreadPool/src/adrian/os/java/threadpool/CustomThreadPool.java) `shutdown()` | Doesn't interrupt idle workers, so `awaitTermination()` latency is bounded by the full `idleTime` (default 60s) even with an empty queue. |
| Low | Architecture + Design | Both builders' `setName()` | `new String(name)` "defensive copy" is a no-op cargo-cult pattern (`String` is immutable) — provides no benefit, and the javadoc's justification is technically incorrect. |
| Low | Architecture | Builder constructors | Non-standard `@warning` Javadoc tag won't render correctly in generated docs. |
| Low | Architecture | `TimedTask.State` enum | `@SuppressWarnings("javadoc")` used instead of documenting the enum, inconsistent with the rest of the codebase. |
| Low | Design | Both executor subclasses | Duplicate `run(Runnable)`/`run(Runnable, String)` pairs could collapse into one nullable-name overload. |
| Low | Design | [TimedTaskPoolExecutor.java](src/adrian/os/java/timer/TimedTaskPoolExecutor.java) | Re-exposes most of `AbstractExecutorService`'s lifecycle surface as manual forwarding methods. |
| Low | Design | `TimedTask` setters | Four near-identical guard blocks could be a single helper (would also help fix the synchronization finding above). |
| Low | Design | `TimedTask.State.SHUTDOWN` vs. `ThreadPoolState.SHUTDOWN` | Same enum constant name, different lifecycle semantics (transient/restartable vs. terminal) in two tightly-coupled modules — confusing to readers. |
| Low | Security + Concurrency | [TimedTaskThreadExecutor.java](src/adrian/os/java/timer/TimedTaskThreadExecutor.java) | Spawns a new thread per execution with no cap/backpressure — combinable with the periodic-overlap race for unbounded thread pile-up. |
| Low | Security | `Duration` setters in both builders | No `Objects.requireNonNull` before `.isNegative()` — `null` throws an unguarded NPE instead of a clear `IllegalArgumentException`. |
| Low | Security | `TimedTask.Timer.executeTask()`, `Worker.handleTaskError()` | `getUncaughtExceptionHandler()` dereferenced without a null check — reachable only via a misbehaving custom `Thread` subclass. |
| Low | Concurrency | `TimedTask.start()` | Can block the caller indefinitely (holding the instance monitor) if the injected executor doesn't execute promptly — largely theoretical given `CustomThreadPool`'s unbounded queue. |
| Informational | Security | Thread naming (`TimedTaskPoolExecutor`, `TimedTask`) | Task/timer names are used verbatim as thread names — a latent info-exposure concern only if names ever derive from untrusted input and get logged. |
| Informational | Concurrency | `CustomThreadPool.stopWorker()` | Redundant self-interrupt on the exiting thread — no correctness impact, flagged for maintenance clarity only. |

## Findings by Category

### Architecture Review (full report)

#### Overview

TimedTask is a small, well-documented Java library providing task-centric (as opposed to service-centric) scheduled execution, built as two independently versioned Maven artifacts: the `timer` package at the repository root ([src/adrian/os/java/timer](src/adrian/os/java/timer)) and a `threadpool` package pulled in as a **git submodule** ([external/CustomJavaThreadPool](external/CustomJavaThreadPool), see [.gitmodules](.gitmodules)). The design follows classic Strategy/Builder/Template-Method patterns: `TimedTask` owns lifecycle and scheduling state, `AbstractTimedTaskExecutor` abstracts *how* work is executed (dedicated virtual threads vs. a shared pool), and `TimedTaskBuilder`/`FutureTimedTaskBuilder` provide fluent, validated construction. The custom thread pool module is a from-scratch `AbstractExecutorService` implementation with an elastic worker count, a dedicated `WorkerAdjuster` coordinator, and a small state-machine enum (`ThreadPoolState`) driving per-state polling behavior.

Despite the small size, the codebase shows unusually thorough internal documentation (invariants spelled out in Javadoc, e.g. the `workerDemand` accounting in [CustomThreadPool.java](external/CustomJavaThreadPool/src/adrian/os/java/threadpool/CustomThreadPool.java)). However, verification during this review surfaced a critical, concrete defect and a build-configuration gap that together mean a large fraction of the project's own test suite has likely never executed against the real build.

#### Strengths

- **Clear separation of concerns**: task definition (`TimedTask`), configuration (`TimedTaskBuilder`), and execution strategy (`AbstractTimedTaskExecutor` + its two implementations) are cleanly separated — a textbook Strategy pattern.
- **Good encapsulation**: mutator methods on `TimedTask` are `protected`/package-private; builders have protected constructors only reachable via factory methods.
- **One-directional dependency**: `timer` depends on `threadpool`, never the reverse — no circular coupling.
- **Thoughtful concurrency documentation** in the thread pool module: the `workerDemand` invariant enumerates every mutating event (A–G); `ThreadPoolState` is decoupled from live objects via an immutable `WorkerPollContext` record.
- **Parameterized cross-executor tests**: run the same behavioral assertions against both executor implementations.
- **Deliberate memory-safety design**: `Duration`/`String` defensive copying in builders, explicit warnings against strong references in closures.

#### Findings

1. **High** — `TimedTaskPoolExecutor` never starts its owned thread pool. Constructors call `.build()` not `.start()`, so the pool stays `NOT_RUNNING` and every submission is rejected and silently swallowed by `TimedTask.start()`.
2. **High** — Root module's test suite is disconnected from the Maven build. `mvn test` reports "No tests to run"; no `<testSourceDirectory>`, JUnit dependency, or Surefire config in root `pom.xml`. This directly explains why Finding #1 went undetected.
3. **Medium** — No real Maven module relationship between root and the thread-pool submodule; local-only build helpers are gitignored, leaving no documented reproducible build path for a fresh clone.
4. **Medium** — Significant duplication between `TimedTaskBuilder` and `FutureTimedTaskBuilder`.
5. **Medium** — Wall-clock-based scheduling (`LocalDateTime.now()`) is vulnerable to system clock changes; a monotonic-clock (`System.nanoTime()`) approach would be more robust.
6. **Medium** — Custom thread pool duplicates most of `ThreadPoolExecutor`'s capability with no documented rationale, adding concurrency-risk surface.
7. **Low** — Non-standard `@warning` Javadoc tag won't render correctly.
8. **Low** — `new String(name)` "defensive copy" doesn't achieve its stated purpose (`String` is immutable).
9. **Low** — `@SuppressWarnings("javadoc")` on `TimedTask.State` instead of documenting it.

#### Recommendations

1. Fix `TimedTaskPoolExecutor` to call `.start()`, add a regression test.
2. Wire the root `pom.xml` for tests (this is what would have caught #1).
3. Formalize the module relationship (Maven reactor) or document the build order.
4. Extract a shared builder base to remove duplication.
5. Consider monotonic-clock scheduling.
6. Document (or reconsider) the custom thread pool's rationale vs. `ThreadPoolExecutor`.
7. Replace `@warning` with a standard doc block; drop the pointless `String` copy.
8. Add Javadoc to `TimedTask.State`.

---

### Design Review (full report)

#### Scope

Class/module-level review of the timer core and thread pool packages.

#### Strengths

- **Strategy/Template pattern**: `AbstractTimedTaskExecutor` is a small, intention-revealing template; `TimedTaskPoolExecutor`/`TimedTaskThreadExecutor` are clean, focused subclasses.
- **State pattern in `ThreadPoolState`**: polling behavior per state is expressed as enum constants, decoupled via an immutable record snapshot.
- **`WorkerAdjuster`** is well-scoped and single-responsibility.
- **`DurationCopier`** is a narrowly-scoped, well-justified utility — the standard the rest of the codebase should be held to.
- **Composition over inheritance**: `FutureTimedTask` wraps rather than subclasses `TimedTask`.
- **`getCompletedTasksCount()`** correctly combines an accumulator with live worker snapshots under one lock, avoiding a classic race.

#### Findings

**High**

1. `FutureTimedTask.start()` silently discards the underlying start result — can hand out a future that never completes.
2. Configuration setters on `TimedTask` are not synchronized against `start()`, risking a visibility/data race across threads.

**Medium**

3. Significant duplication between `TimedTaskBuilder` and `FutureTimedTaskBuilder`.
4. Nullable, mutually-exclusive `Duration` fields instead of an explicit schedule type (primitive obsession).
5. Scheduling-calculation logic buried in a private inner class, hurting testability.
6. `workerDemand` correctness depends on a distributed, comment-only protocol across 5 call sites.
7. `workerDemand` is `protected`, exposing a fragile invariant to subclasses.
8. `TimedTaskThreadExecutor.threadFactory` is not `volatile`.
9. `stop()` leaves a pending `CompletableFuture` permanently uncompleted, undocumented.

**Low**

10. Misguided/incorrect defensive copy of `String` (mirrors Architecture #8).
11. Duplicate `run(Runnable)`/`run(Runnable, String)` implementations across both executor subclasses.
12. `TimedTaskPoolExecutor` re-exposes most of `AbstractExecutorService`'s lifecycle API as forwarding methods.
13. Repeated `if (!isRunning()) {...}` guard pattern across four setters.
14. Same enum constant name (`SHUTDOWN`) with different lifecycle semantics in `TimedTask.State` vs. `ThreadPoolState`.

#### Recommendations (prioritized)

1. Fix `FutureTimedTask.start()` to propagate failure.
2. Synchronize `TimedTask`'s configuration setters; bundle with guard-clause dedup.
3. Extract a shared base for the two builders; consider a sealed `Schedule` type.
4. Pull scheduling-calculation logic into an independently testable policy component.
5. Encapsulate `workerDemand`'s protocol behind named methods; make it `private`.
6. Make `TimedTaskThreadExecutor.threadFactory` volatile.
7. Decide/document `FutureTimedTask.stop()`'s contract for pending futures.
8. Drop the pointless `new String(name)` copy.
9. Lower priority: consolidate `run(...)` overloads, reconsider the forwarding surface, rename `TimedTask.State.SHUTDOWN`.

---

### Security Review (full report)

#### Scope

Core timer package, custom thread pool, Maven build files, and test suites. `target/` excluded.

No secrets, hardcoded credentials, network/HTTP calls, SQL, deserialization, or reflection abuse were found in production code. No external runtime dependencies exist (only JUnit at test scope), so there's no transitive-dependency CVE surface beyond Maven plugin versions.

#### Findings

1. **Medium** — Unbounded thread-pool growth by default (`maxThreads = Integer.MAX_VALUE`, unbounded `LinkedBlockingQueue`) — a resource-exhaustion/DoS foot-gun if task volume is attacker-influenced. *(CWE-400 / OWASP A04:2021)*
2. **Low–Medium** — `TimedTaskThreadExecutor` creates one new thread per execution with no throttling; combined with short intervals or a platform-thread factory, unbounded thread creation is possible. *(CWE-400)*
3. **Low** — Missing null-validation on `Duration` setters — `null` throws an unguarded NPE instead of a clear `IllegalArgumentException`. *(CWE-476)*
4. **Low** — Uncaught-exception handler dereferenced without null-safety in `Timer.executeTask()`/`Worker.handleTaskError()` — reachable only via a misbehaving custom `Thread` subclass. *(CWE-476)*
5. **Informational** — Default worker/pool thread names use task names verbatim — minor info-exposure risk only if names ever derive from untrusted input and are logged. *(A09:2021)*

#### Summary

The classic high-severity OWASP categories (Injection, Broken Auth, Cryptographic Failures, SSRF, Broken Access Control) are largely not applicable to this pure-Java scheduling/concurrency library. The realistic residual risk is resource exhaustion from unbounded thread growth — a design-level DoS foot-gun, not an active vulnerability, that matters only if a consuming application lets untrusted input control scheduling volume without its own bounds.

**Top priorities**: bound `TimedTaskPoolExecutor`'s default pool / use a bounded queue with rejection policy; add null-guards to `Duration` setters; document the DoS risk of unbounded thread creation in `TimedTaskThreadExecutor`.

---

### Concurrency Review (full report)

#### Scope

Timer layer, hand-rolled thread pool, and their test suites. `target/` excluded, no files modified.

The `CustomThreadPool` implementation is unusually well-documented about its own concurrency invariants, which materially helped this review — most findings below are in areas *not* covered by that self-documentation, or one layer up in the timer package.

#### Findings

1. **High** — Overlapping periodic executions share task state without synchronization: `loopTimer()` schedules the next firing *before* the current execution completes (only submitted, not awaited). For `FutureTimedTask`, this races on `nextResult`/`lastResult`, potentially mismatching futures to results. Repetitive mode is *not* affected (inherently serialized).
2. **High** — `FutureTimedTask.start()` ignores `TimedTask.start()`'s return value; if the underlying start fails (e.g. racing with pool shutdown), a caller gets a future that never completes.
3. **Medium** — Task configuration fields (`initialDelay`, `periodicDelay`, `repetitiveDelay`, `name`) are neither `volatile` nor synchronized — TOCTOU and cross-thread visibility risk against `start()`/`Timer`.
4. **Medium** — Zero idle time (`Duration.ZERO`, settable and the coerced value for negative durations) causes core workers to busy-spin instead of blocking.
5. **Low–Medium** — Graceful `shutdown()` doesn't wake idle workers, so `awaitTermination()` latency is bounded by the full `idleTime` (default 60s) even with an empty queue — confirmed by the pool's own `testShutdown()` test.
6. **Low** — `TimedTask.start()` can block the caller indefinitely on a congested/unresponsive executor (largely theoretical given `CustomThreadPool`'s unbounded queue).
7. **Low (informational)** — Redundant self-interrupt in `stopWorker()` — no correctness impact, just confusing for maintainers.
8. **Low** — `TimedTaskThreadExecutor` has no backpressure/thread-count bound; combined with Finding #1, a slow periodic task can accumulate unbounded threads over time.

#### Summary

**Overall risk: Moderate.** The hand-rolled `CustomThreadPool` itself is solid — state transitions are consistently guarded, `workerDemand` accounting is carefully documented and event-driven, and its own regression tests show real prior hardening against subtle races. The higher-severity issues are in the **timer layer built on top of the pool**, not the pool itself.

**Top priorities**: fix overlapping periodic executions (#1); propagate `TimedTask.start()`'s return value through `FutureTimedTask.start()` (#2); make `TimedTask`'s delay/name fields volatile (#3); reject or special-case `Duration.ZERO` idle time (#4).

## Top Priorities

1. **Fix `TimedTaskPoolExecutor` to call `.start()`, and wire the root `pom.xml` for tests.** These two Architecture findings are causally linked: the pool-executor bug is a complete functional break in a primary public API, and it shipped because the test suite that would have caught it (`src-test/`) is never compiled or run by `mvn test`. Fix the build wiring first (or alongside), then add a regression test asserting successful execution immediately after construction.
2. **Propagate `TimedTask.start()`'s success/failure through `FutureTimedTask.start()`.** Raised independently by both Design and Concurrency reviews — currently a caller can be handed a `CompletableFuture` that hangs forever whenever the underlying start silently fails (e.g. racing with pool shutdown).
3. **Fix overlapping periodic executions.** The most subtle correctness bug found: a slow task on a short periodic interval can run concurrently with itself, corrupting which result pairs with which logical execution in `FutureTimedTask`.
4. **Synchronize/`volatile`-ize `TimedTask`'s configuration fields.** Also flagged independently by two reviews — a genuine cross-thread visibility gap in an otherwise carefully synchronized class.
5. **Bound thread-pool growth by default** (`maxThreads`, bounded queue + rejection policy) and **document the build/module relationship** for the submodule. Neither is an active exploit, but both are foot-guns for anyone deploying or building on top of this library today.
