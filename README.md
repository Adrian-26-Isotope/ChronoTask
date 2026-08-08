# ChronoTask

A lightweight, flexible task scheduler for Java with fine-grained control over task execution and lifecycle management.

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Key Differences from Java's ScheduledExecutorService](#key-differences-from-javas-scheduledexecutorservice)
- [Architecture](#architecture)
- [Usage Guide](#usage-guide)
  - [Creating a ChronoTask](#creating-a-chronotask)
  - [Execution Modes](#execution-modes)
  - [Executor Types](#executor-types)
  - [Controlling Task Lifecycle](#controlling-task-lifecycle)
  - [Future-Based Tasks (FutureChronoTask)](#future-based-tasks-futurechronotask)
- [Advanced Usage](#advanced-usage)
  - [Custom Thread Factories](#custom-thread-factories)
  - [Working with Thread Pools](#working-with-thread-pools)
  - [Memory Considerations](#memory-considerations)
- [Best Practices](#best-practices)
- [Requirements](#requirements)
- [Dependencies](#dependencies)
- [License](#license)
- [Author](#author)

## Overview

ChronoTask is a lightweight task scheduler for Java that provides individual task instances with their own lifecycle management. Each `ChronoTask` can be independently started, stopped, and restarted, giving fine-grained control over task execution.

The library supports three execution modes: one-time with optional initial delay, periodic (fixed-rate), and repetitive (fixed-delay).

## Features

- **Individual Task Lifecycle Management**: Each `ChronoTask` instance can be independently started, stopped, and restarted, providing fine-grained control over individual task execution.

- **Three Execution Modes**: Supports one-time execution with optional initial delay, periodic (fixed-rate) execution, and repetitive (fixed-delay) execution to cover different scheduling scenarios.

- **Flexible Executor Options**: Provide your own `AbstractExecutor` implementation for full control how tasks shall be executed, or choose between 2 built-in executors:
  -  `ThreadExecutor` for individual thread execution (using virtual threads by default) or
  -  `PoolExecutor` for efficient thread pool-based execution.

- **Fluent Builder API**: Intuitive `ChronoTaskBuilder` provides a fluent interface for configuring tasks with method chaining for clean and readable task creation.

- **Custom Thread Factory Support**: Configure custom `ThreadFactory` implementations to control thread creation behavior, naming conventions, and thread properties.

- **Built-in Thread Pool Integration**: `PoolExecutor` integrates with `ElasticThreadPool` for efficient resource management with configurable behavior.

- **Future-Based Results**: `FutureChronoTask<T>` wraps a `ChronoTask` and exposes each execution result via `CompletableFuture<T>`, enabling reactive result handling, chaining, and `getLastResult()` for the most recent value.

- **Exception Propagation**: Uncaught exceptions thrown inside a task are forwarded to the thread's `UncaughtExceptionHandler` rather than being silently swallowed.

- **Task State Introspection**: Query task state with `isRunning()` to monitor execution status and coordinate between multiple tasks.

- **Named Tasks**: Assign meaningful names to tasks and their execution threads for easier debugging, logging, and monitoring.

- **Memory-Safe Design**: Architecture encourages weak references and proper cleanup to prevent memory leaks from long-running tasks.

- **Graceful Shutdown**: Tasks can be stopped gracefully, allowing in-flight executions to complete before termination.

## Key Differences from Java's ScheduledExecutorService

### Design Philosophy

Java's `ScheduledExecutorService` is a **service-centric** approach: you create a service (executor) and submit multiple tasks to it. The service manages all tasks collectively, and tasks are represented by `ScheduledFuture` handles that provide limited control.

`ChronoTask` follows a **task-centric** approach: each task is an independent, first-class object with its own lifecycle. You can create, start, stop, and restart individual tasks without affecting others. The executor is just a configurable execution strategy.

**Analogy**: `ScheduledExecutorService` is like a job scheduler service where you submit job descriptions. `ChronoTask` is like having individual alarm clocks - each one can be independently configured, started, stopped, and reset.

### Specific Differences

#### 1. Task Lifecycle Control

**ScheduledExecutorService**:
- Tasks are submitted to the service and return a `ScheduledFuture`
- To stop a task, you must cancel its `ScheduledFuture`, which cannot be restarted
- Stopping requires keeping track of `ScheduledFuture` references
- Once cancelled, you must resubmit the task to schedule it again
- No straightforward way to temporarily pause and resume a task

```java
// ScheduledExecutorService example
ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
ScheduledFuture<?> future = executor.scheduleAtFixedRate(task, 0, 1, TimeUnit.SECONDS);

// To stop
future.cancel(false);

// To restart - must resubmit entirely
future = executor.scheduleAtFixedRate(task, 0, 1, TimeUnit.SECONDS);
```

**ChronoTask**:
- Each task is a self-contained object with `start()` and `stop()` methods
- Tasks can be stopped and restarted multiple times
- No need to track separate handles - the task object itself provides control
- Clean, object-oriented lifecycle management

```java
// ChronoTask example
ChronoTask task = executor.createTask(t -> doWork())
    .setPeriodicDelay(Duration.ofSeconds(1))
    .build();

task.start();  // Start execution
task.stop();   // Stop execution
task.start();  // Restart - same configuration
```

#### 2. Execution Timing Semantics

**ScheduledExecutorService**:
- `scheduleAtFixedRate()`: Attempts to maintain fixed rate, but if execution takes longer than the period, subsequent executions may run back-to-back without delay
- `scheduleWithFixedDelay()`: Guarantees delay between completion of one execution and start of next
- Distinction between rate and delay is method-based
- No built-in support for one-time execution with initial delay followed by different scheduling

**ChronoTask**:
- **Periodic mode** (via `setPeriodicDelay()`): Similar to `scheduleAtFixedRate()` - schedules next execution at fixed intervals from the start time, **regardless of task execution duration**
- **Repetitive mode** (via `setRepetitiveDelay()`): Similar to `scheduleWithFixedDelay()` - waits for task completion before scheduling next execution with the specified delay
- **One-time mode**: When neither periodic nor repetitive delay is set, task executes once after initial delay
- All modes support optional `setInitialDelay()` for consistent delayed start behavior
- Mode is configuration-based rather than method-based

```java
// Periodic: Fixed-rate execution
ChronoTask periodic = executor.createTask(t -> doWork())
    .setInitialDelay(Duration.ofSeconds(5))
    .setPeriodicDelay(Duration.ofSeconds(10))
    .build();

// Repetitive: Fixed-delay execution
ChronoTask repetitive = executor.createTask(t -> doWork())
    .setInitialDelay(Duration.ofSeconds(5))
    .setRepetitiveDelay(Duration.ofSeconds(10))
    .build();

// One-time: Single execution after delay
ChronoTask oneTime = executor.createTask(t -> doWork())
    .setInitialDelay(Duration.ofSeconds(5))
    .build();
```

In periodic mode, if a task's execution takes longer than `periodicDelay`, the next
firing is scheduled anyway - the previous execution and the new one can run
concurrently. This overlap is **allowed by default** (unbounded). Use
`setMaxConcurrentExecutions(n)` to cap how many executions of the same task may run
at once; the timer thread blocks (without busy-waiting) until a slot frees up once
the cap is reached. Setting `n = 1` reproduces fully-serialized behavior, equivalent
to no overlap at all:

```java
// Allow at most 2 overlapping executions of a slow periodic task
TimedTask bounded = executor.createTask(t -> doSlowWork())
    .setPeriodicDelay(Duration.ofSeconds(1))
    .setMaxConcurrentExecutions(2)
    .build();
```

#### 3. Execution Model

**ScheduledExecutorService**:
- Centralized thread pool manages all scheduled tasks
- Tasks share a common thread pool with fixed or cached sizing
- Limited control over thread creation per task
- Thread pool configuration applies to all tasks uniformly
- Tasks are executed on pool threads, thread naming controlled by pool's ThreadFactory

**ChronoTask**:
- Flexible executor abstraction via `AbstractExecutor`.
- Built-in **`ThreadExecutor`**: Each timer gets its own dedicated thread (virtual threads by default), with configurable `ThreadFactory`.
- Built-in **`PoolExecutor`**: Tasks share a thread pool (uses `ElasticThreadPool` internally). Similar to the `ScheduledExecutorService`.
- Each task can theoretically use a different executor.
- Named tasks automatically propagate names to their execution threads for better debugging.

```java
// Option 1: Individual threads per task (virtual threads by default)
ThreadExecutor threadExec = new ThreadExecutor();
ChronoTask task1 = threadExec.createTask(t -> doWork())
    .setName("DatabaseSync")
    .setPeriodicDelay(Duration.ofMinutes(5))
    .build();

// Option 2: Shared thread pool
PoolExecutor poolExec = new PoolExecutor("MyTaskPool");
ChronoTask task2 = poolExec.createTask(t -> doWork())
    .setName("CacheCleanup")
    .setPeriodicDelay(Duration.ofMinutes(10))
    .build();

// Option 3: Custom executor implementation
AbstractExecutor customExec = new MyCustomExecutor();
```

#### 4. Resource Management

**ScheduledExecutorService**:
- Executor service owns and manages thread resources
- Must call `shutdown()` or `shutdownNow()` on the executor service to release resources
- Cancelling individual tasks doesn't release thread pool resources
- Thread pool continues to consume resources until explicitly shut down
- Memory leaks possible if strong references kept in task closures

**ChronoTask**:
- Executor is separate from individual task lifecycle
- Stopping a task releases its specific execution thread (in `ThreadExecutor` mode)
- Thread pool executors can be shut down independently: `poolExecutor.shutdown()`
- Architecture explicitly encourages weak references to prevent memory leaks
- Builder pattern creates defensive copies of `Duration` objects to prevent external reference retention
- Timer thread and task execution threads are separate, allowing fine-grained resource control

```java
// Warning in JavaDoc: Avoid strong references to external objects
// Use WeakReference for long-lived external objects
WeakReference<MyService> serviceRef = new WeakReference<>(myService);
// ChronoTask resource management
ChronoTask task = executor.createTask(t -> {
    MyService service = serviceRef.get();
    if (service != null) {
        service.doWork();
    }
}).build();

task.start();
// ... later
task.stop();  // Releases timer thread resources immediately

// For pool executors, shutdown the pool when done
poolExecutor.shutdown();
```

#### 5. Task Control and Introspection

**ScheduledExecutorService**:
- Limited state introspection and self control.

**ChronoTask**:
- **Public API**: `isRunning()` method provides clear boolean state - `true` when timer is currently scheduled, `false` when stopped
- **Self-Reference**: Tasks receive reference to themselves (`Consumer<ChronoTask>`), enabling self-introspection and self-control
- **Self-Stopping**: Tasks can stop themselves based on internal logic or conditions
- **Named Tasks**: Optional naming propagates to execution threads for debugging:
  - Timer thread: `[TaskName]Timer`
  - Task execution threads: `[TaskName]Task#1`, `[TaskName]Task#2`, etc.
- **Simple State Model**: Three states - `RUNNING`, `SHUTDOWN` (transient), and `STOPPED` - easy to understand and use

```java
// ChronoTask - rich introspection and self-control
ChronoTask task = executor.createTask(t -> {
    // Task can introspect its own state
    if (t.isRunning()) {
        System.out.println("Task is actively scheduled");
    }

    // Perform work
    processData();

    // Task can stop itself based on conditions
    if (shouldStopCondition()) {
        System.out.println("Stopping task from within");
        t.stop();
    }
})
.setName("DataProcessor")
.setPeriodicDelay(Duration.ofSeconds(30))
.build();

// Start the task
task.start();

// External monitoring - simple and clear
if (task.isRunning()) {
    System.out.println("Task is active and scheduled");
} else {
    System.out.println("Task is stopped");
}

// Later - stop from outside
task.stop();

// Restart if needed
if (!task.isRunning()) {
    task.start();
}
```

**Key Advantages of ChronoTask**:

1. **Bidirectional Control**: Tasks can be controlled both externally (via `start()`/`stop()`) and internally (task can call `stop()` on itself)

2. **Simple State Querying**: Single `isRunning()` method covers all needs - no confusion between "done", "cancelled", or "running"

3. **Self-Aware Tasks**: The `Consumer<ChronoTask>` pattern enables tasks to make decisions based on their own state

4. **Debugging Support**: Named tasks with automatic thread naming make it easy to identify tasks in thread dumps and logs

### Summary Table

| Aspect | ScheduledExecutorService | ChronoTask |
|--------|-------------------------|-----------|
| **Design Pattern** | Service-centric (submit tasks to service) | Task-centric (independent task objects) |
| **Lifecycle** | Via `ScheduledFuture` handles | Direct `start()` and `stop()` methods |
| **Restart** | Must resubmit task | Built-in `stop()` and `start()` again |
| **Execution Strategy** | Fixed thread pool | Pluggable executors |
| **Thread Management** | Shared pool for all tasks | Flexible depending on execution strategy |
| **Timing Modes** | Method-based (`scheduleAtFixedRate` vs `scheduleWithFixedDelay`) | Configuration-based (`setPeriodicDelay` vs `setRepetitiveDelay`) |
| **State Inspection** | `isDone()`, `isCancelled()` | `isRunning()` |
| **Future / Result API** | `ScheduledFuture` (control only) | `FutureChronoTask<T>` — `CompletableFuture<T>` per execution |
| **Task Self-Reference** | No | Yes |
| **Resource Cleanup** | Service-level shutdown | Task-level + optional thread pool shutdown |
| **Memory Management** | Manual management required | Defensive copies, weak reference encouragement |
| **Thread Naming** | Pool-level ThreadFactory | Pool-level ThreadFactory / Per-task naming with automatic propagation |

### When to Use Each

**Use ScheduledExecutorService when**:
- You need a simple, standard solution for task scheduling
- Tasks are fire-and-forget with no need for individual lifecycle control
- You prefer working with `Future`-based APIs
- You're integrating with existing executor-based frameworks

**Use ChronoTask when**:
- You need fine-grained control over individual task lifecycles
- Tasks need to be dynamically started, stopped, and restarted
- You want tasks to introspect or control themselves
- You need flexibility in choosing between dedicated threads or thread pools
- You want better debugging support with named tasks and threads
- You need per-execution results via `CompletableFuture<T>` (use `FutureChronoTask<T>`)

## Architecture

### Core Components

The ChronoTask library is built around a clean, modular architecture that separates concerns between task definition, execution strategy, and lifecycle management. The design follows object-oriented principles with well-defined responsibilities for each component.

#### 1. ChronoTask

The `ChronoTask` class is the central component representing an individual scheduled task. It encapsulates:

- **Task Logic**: Stores the user-defined task as a `Consumer<ChronoTask>`, allowing tasks to receive a reference to themselves for introspection and self-control.

- **Lifecycle State**: Maintains an internal state machine with three states:
  - `RUNNING`: Task is actively scheduled and executing
  - `SHUTDOWN`: Transitional state — `stop()` has been called; timer thread is winding down
  - `STOPPED`: Task is fully stopped

- **Timing Configuration**: Holds three optional `Duration` fields:
  - `initialDelay`: Delay before the first execution
  - `periodicDelay`: Fixed-rate interval between execution starts (scheduled at fixed intervals)
  - `repetitiveDelay`: Fixed-delay interval after execution completion
  - `maxConcurrentExecutions`: Bounds concurrent overlap in periodic mode (default unbounded); see `setMaxConcurrentExecutions()`

- **Internal Timer**: Contains a nested `Timer` class that manages the scheduling logic on a dedicated timer thread.

- **Execution Control**: Provides thread-safe `start()` and `stop()` methods for lifecycle management, plus `isRunning()` for state introspection.

**Key Design Decisions**:
- Defensive copying of `Duration` objects to prevent external reference retention
- Separation of timer thread (scheduling) from task execution thread(s)
- Self-reference pattern enables tasks to introspect and control themselves
- Synchronization mechanisms prevent race conditions during state transitions

#### 2. ChronoTaskBuilder

The `ChronoTaskBuilder` class implements the Builder pattern for fluent, type-safe task configuration. It:

- **Enforces Required Parameters**: Mandates `Consumer<ChronoTask>` task and `AbstractExecutor` at construction
- **Provides Fluent API**: Method chaining for optional parameters (`setInitialDelay()`, `setPeriodicDelay()`, `setRepetitiveDelay()`, `setName()`, `setMaxConcurrentExecutions()`)
- **Validates Configuration**: Ensures mutually exclusive execution modes (periodic vs. repetitive)
- **Prevents Memory Leaks**: Creates defensive copies of all `Duration` and `String` parameters to decouple from external references
- **Builds Immutable Tasks**: Constructs fully configured `ChronoTask` instances via `build()`

**Key Design Decisions**:
- Fluent API improves readability and reduces configuration errors
- Defensive copying prevents unintended object retention
- Validation logic centralized in builder rather than scattered across `ChronoTask`
- Builder pattern separates task configuration from task execution concerns

#### 3. AbstractExecutor

The `AbstractExecutor` is an abstract base class that defines the execution strategy pattern.

- **Factory for Builders**: Provides two factory methods:
  - `createTask(Consumer<ChronoTask>)` → returns a `ChronoTaskBuilder`
  - `createFutureTask(Function<FutureChronoTask<T>, T>)` → returns a `FutureChronoTaskBuilder<T>`
- **Execution Abstraction**: Declares two abstract methods for execution:
  - `run(Runnable task)`: Execute task without naming
  - `run(Runnable task, String name)`: Execute task with thread naming support
- **Strategy Pattern**: Allows different execution implementations without changing `ChronoTask` code

**Key Design Decisions**:
- Abstract class (not interface) allows adding common functionality in future without breaking implementations
- Two `run()` variants support both anonymous and named task execution
- Factory method pattern centralizes builder creation logic

#### 4. ThreadExecutor

`ThreadExecutor` is a concrete executor that creates individual threads for each task.

- **Per-Task Threading**: Each `run()` call creates a new thread via the configured `ThreadFactory`
- **Virtual Thread Default**: Uses `Thread.ofVirtual().factory()` by default for lightweight thread creation
- **Configurable ThreadFactory**: Allows custom thread factories via `setThreadFactory(ThreadFactory)`
- **Thread Naming Support**: Implements named execution by setting thread names before starting

**Key Design Decisions**:
- Virtual threads by default minimize resource overhead for many concurrent tasks
- ThreadFactory pattern allows full control over thread creation (daemon status, priorities, etc.)
- Immediate thread start ensures consistent behavior
- Suitable for tasks that need isolation or have their own lifecycle requirements

#### 5. PoolExecutor

`PoolExecutor` is a concrete executor that uses a shared thread pool for task execution.

- **Thread Pool Integration**: Uses external `ElasticThreadPool`
- **Configurable Construction**: Offers three constructors:
  - Default: Minimum 0 threads, 60-second idle time
  - Named: Same as default with pool name
  - Custom: Accepts any `AbstractExecutorService` implementation
- **Task Submission**: Submits tasks to the pool rather than creating new threads
- **Lifecycle Management**: Provides `shutdown()` method to gracefully terminate the pool

**Key Design Decisions**:
- Default configuration (0 minimum threads) allows pool to scale down when idle
- Integration with `ElasticThreadPool` provides advanced features like dynamic sizing
- Accepting `AbstractExecutorService` allows integration with any Java executor
- Task names are propagated to pool execution threads
- Suitable for many tasks sharing limited thread resources

#### 6. FutureChronoTask\<T\>

`FutureChronoTask<T>` wraps a `ChronoTask` and exposes each execution result as a `CompletableFuture<T>`.

- **Result per Execution**: Each execution runs the task first, then atomically claims whichever future is currently exposed and completes it with the result or exception, installing a fresh future for the following execution. Overlapping executions are published in completion order, not start order.
- **Start Returns Future**: `start()` returns the `CompletableFuture<T>` for the next result to become available.
- **Next Result Access**: `getNextResult()` returns the future that will be completed by whichever execution's result becomes available next (completion order), which may be an execution that was already in-flight when the method was called.
- **Last Result Access**: `getLastResult()` returns an `Optional<T>` of the most recent successful result.
- **Full Lifecycle**: `start()`, `stop()`, `isRunning()` delegate to the underlying `ChronoTask`.
- **Exception Propagation**: If the task throws, the future is completed exceptionally; the `lastResult` is not updated.

**Key Design Decisions**:
- `AtomicReference<CompletableFuture<T>>` ensures thread-safe future swapping with no races
- The future is claimed and completed right after an execution's outcome is known, so whichever execution finishes first wins the race for the currently-exposed future
- `start()` is `synchronized` and returns the currently-exposed future before starting the underlying task
- Instances are created via `executor.createFutureTask(Function<FutureChronoTask<T>, T>)` and `FutureChronoTaskBuilder<T>.build()`

#### 7. FutureChronoTaskBuilder\<T\>

The `FutureChronoTaskBuilder<T>` class mirrors `ChronoTaskBuilder` for `FutureChronoTask<T>`.

- **Same fluent API**: `setInitialDelay()`, `setPeriodicDelay()`, `setRepetitiveDelay()`, `setName()`, `setMaxConcurrentExecutions()`
- **Builds `FutureChronoTask<T>`**: Constructs fully configured instances via `build()`
- **Protected constructor**: Instantiated exclusively through `AbstractExecutor.createFutureTask(Function)`

### Component Interaction

The following diagram illustrates how components interact during typical task lifecycle operations:

```
┌─────────────────────────────────────────────────────────────────────┐
│                         User/Application                            │
└───────────────────────────────┬─────────────────────────────────────┘
                                │
                                │ 1. Choose Executor Strategy
                                ▼
                ┌───────────────────────────────┐
                │  AbstractExecutor             │
                │  (Strategy Pattern)           │
                └───────────────┬───────────────┘
                                │
                ┌───────────────┴───────────────┐
                │                               │
                ▼                               ▼
    ┌───────────────────────┐       ┌───────────────────────┐
    │ ChronoTaskThread-     │       │ ChronoTaskPool-       │
    │ Executor              │       │ Executor              │
    │                       │       │                       │
    │ • Virtual threads     │       │ • Thread pool         │
    │ • Custom ThreadFactory│       │ • ElasticThreadPool   │
    └───────────┬───────────┘       └───────────┬───────────┘
                │                               │
                │ 2. createTask(task)           │
                └───────────────┬───────────────┘
                                │
                                ▼
                      ┌────────────────────────┐
                      │  ChronoTaskBuilder     │
                      │                        │
                      │  3. Configure task:    │
                      │  • setName()           │
                      │  • setInitialDelay()   │
                      │  • setPeriodicDelay()  │
                      │  • setRepetitiveDelay()│
                      └──────────┬─────────────┘
                                 │
                                 │ 4. build()
                                 ▼
                        ┌────────────────┐
                        │   ChronoTask   │
                        │                │
                        │  State:        │
                        │  • NOT_RUNNING │
                        └────────┬───────┘
                                 │
                                 │ 5. start()
                                 ▼
                        ┌────────────────┐
                        │   ChronoTask   │
                        │                │
                        │  State:        │
                        │  • RUNNING     │
                        └────────┬───────┘
                                 │
                                 ├──────────────────────────────────┐
                                 │                                  │
                    6. Spawn Timer Thread               7. Execute Task (per schedule)
                                 │                                  │
                                 ▼                                  ▼
                    ┌─────────────────────┐          ┌──────────────────────────┐
                    │  Timer Execution    │          │  Task Execution          │
                    │  (via Executor)     │          │  (via Executor)          │
                    │                     │          │                          │
                    │  • Calculates next  │          │  • Runs user task        │
                    │    execution time   │          │  • Task receives self-   │
                    │  • Sleeps/waits     │          │    reference             │
                    │  • Triggers task    │          │  • Can call stop() on    │
                    │    execution        │          │    itself                │
                    │  • Handles periodic/│          │                          │
                    │    repetitive modes │          │                          │
                    └─────────────────────┘          └──────────────────────────┘
                                 │
                                 │ 8. stop() (external or self-triggered)
                                 ▼
                        ┌────────────────┐
                        │   ChronoTask   │
                        │                │
                        │  State:        │
                        │  • NOT_RUNNING │
                        └────────────────┘
                                 │
                                 │ 9. Can restart()
                                 └────────┐
                                          │
                                          └──────────────────────────────┐
                                                                         │
                                                                         ▼
                                                                  Back to step 5
```

#### Interaction Flow Details

**1. Executor Selection**
- User creates an executor instance (`ThreadExecutor` or `PoolExecutor`)
- Optionally configures executor (e.g., custom `ThreadFactory` or thread pool settings)

**2. Task Creation**
- User calls `executor.createTask(task)` passing a `Consumer<ChronoTask>`
- Executor returns a `ChronoTaskBuilder` pre-configured with the executor reference

**3. Task Configuration**
- User chains builder methods to configure task parameters
- Builder validates and stores configuration (with defensive copying)
- Periodic and repetitive delays are mutually exclusive

**4. Task Building**
- User calls `build()` to construct the `ChronoTask`
- Builder creates `ChronoTask` instance and applies all configuration
- `ChronoTask` starts in `NOT_RUNNING` state

**5. Task Activation**
- User calls `task.start()`
- `ChronoTask` transitions to `RUNNING` state
- Initial execution time is calculated (now + initialDelay)

**6. Timer Thread Creation**
- `ChronoTask` delegates to executor to spawn timer thread
- Timer thread is named `[TaskName]Timer` if task has a name
- Timer thread enters scheduling loop

**7. Task Execution Loop**
- **Timer thread** continuously:
  - Checks if current time >= next execution time
  - For **periodic mode**: Calculates next execution time immediately (fixed-rate)
  - Delegates actual task execution to executor
  - For **repetitive mode**: Waits for task completion to calculate next execution time (fixed-delay)
  - For **one-time mode**: Stops after single execution

- **Executor** creates execution thread(s):
  - Thread named `[TaskName]Task#N` where N is execution count
  - Executes user's `Consumer<ChronoTask>`, passing task reference
  - Task can call `stop()` on itself if needed

**8. Task Deactivation**
- User calls `task.stop()` (or task calls `stop()` on itself)
- Task transitions to `NOT_RUNNING` state
- Timer thread detects state change and terminates gracefully
- Execution threads complete their current run and terminate

**9. Task Reactivation**
- Task can be restarted with `task.start()`
- Returns to step 5 with same configuration
- Execution count continues incrementing

#### Thread Model

The architecture employs a **dual-thread model** for each running task:

1. **Timer Thread** (1 per task)
   - Dedicated to scheduling logic
   - Runs continuously while task is in `RUNNING` state
   - Sleeps/waits between executions
   - Minimal resource consumption

2. **Execution Thread(s)** (variable per task)
   - Created on-demand for each task execution
   - For `ThreadExecutor`: New thread per execution (virtual threads by default)
   - For `PoolExecutor`: Drawn from shared thread pool
   - Executes user task code
   - Terminates after task completion

This separation ensures:
- Timer precision is not affected by long-running task executions
- Tasks can run concurrently with their own scheduling
- Clean resource cleanup when tasks are stopped

#### Synchronization and Thread Safety

The architecture employs multiple synchronization mechanisms:

1. **State Transitions**: `start()` and `stop()` are `synchronized` to prevent race conditions
2. **Execution Lock**: Internal `executionLock` coordinates between timer and execution threads
3. **Volatile State**: Task state is `volatile` for visibility across threads
4. **Atomic Next Execution**: Next execution time updates are synchronized with notification
5. **Interrupt Handling**: Proper interrupt handling for graceful shutdown

#### Memory Management Strategy

The architecture is designed to prevent memory leaks through:

1. **Defensive Copying**: All `Duration` and `String` parameters are copied to prevent external reference chains
2. **Weak Reference Encouragement**: JavaDoc explicitly warns against strong references in task closures
3. **Thread Lifecycle**: Threads are created with task-scoped lifetimes and terminate properly
4. **No Thread Caching**: Timer threads are created per-task and released on stop
5. **Optional Pooling**: Pool executor allows resource sharing when appropriate

This design ensures that stopping a task releases all associated resources, preventing long-term memory retention.

## Usage Guide

### Creating a ChronoTask

ChronoTask uses a fluent builder pattern for task creation. The process involves three steps:

1. **Choose an executor** - Decide between individual threads, a thread pool or a custom executor
2. **Configure the task** - Set timing parameters and optional name
3. **Build and start** - Create the task instance and start execution

#### Using ThreadExecutor

`ThreadExecutor` creates a dedicated thread for each task execution. By default, it uses Java's virtual threads, making it efficient even for many concurrent tasks.

**Basic Example:**

```java
// Create the executor (uses virtual threads by default)
ThreadExecutor executor = new ThreadExecutor();

// Create and configure a task
ChronoTask task = executor.createTask(t -> {
    System.out.println("Task executed!");
})
.setName("MyTask")
.setPeriodicDelay(Duration.ofSeconds(5))
.build();

// Start the task
task.start();
```

**With Custom ThreadFactory:**

```java
// Create executor with custom thread factory
ThreadExecutor executor = new ThreadExecutor();
executor.setThreadFactory(r -> {
    Thread thread = new Thread(r);
    thread.setDaemon(true);
    thread.setPriority(Thread.MAX_PRIORITY);
    return thread;
});

// Create task
ChronoTask task = executor.createTask(t -> {
    // High-priority daemon thread execution
    performCriticalWork();
})
.build();
```

**When to use:**
- Tasks need isolation from each other
- Different tasks need different thread configurations
- Resource consumption is not a concern (virtual threads are lightweight)
- You want automatic thread cleanup when tasks stop

#### Using PoolExecutor

`PoolExecutor` uses a shared thread pool to execute tasks, making it more efficient when you have many tasks competing for limited resources.

**Basic Example:**

```java
// Create pool executor with default settings
// (0 minimum threads, 60-second idle time)
PoolExecutor executor = new PoolExecutor("MyTaskPool");

// Create multiple tasks sharing the pool
ChronoTask task1 = executor.createTask(t -> {
    processData();
})
.setName("DataProcessor")
.setPeriodicDelay(Duration.ofMinutes(1))
.build();

ChronoTask task2 = executor.createTask(t -> {
    cleanupCache();
})
.setName("CacheCleanup")
.setPeriodicDelay(Duration.ofMinutes(5))
.build();

// Start both tasks
task1.start();
task2.start();

// Later: shutdown the pool when all tasks are done
task1.stop();
task2.stop();
executor.shutdown();
```

**With Custom Thread Pool:**

```java
// Create custom thread pool with specific configuration
ElasticThreadPool customPool = ElasticThreadPool.builder()
    .setMinThreads(2)
    .setMaxThreads(10)
    .setIdleTime(Duration.ofMinutes(2))
    .setName("CustomTaskPool")
    .build();

// Create executor with custom pool
PoolExecutor executor = new PoolExecutor(customPool);

// Use the executor
ChronoTask task = executor.createTask(t -> {
    performWork();
})
.build();
```

**When to use:**
- You have many tasks and want to limit total thread count
- Tasks are short-lived and can share thread resources
- You need centralized thread pool management
- Memory efficiency is a priority

**Important Notes:**
- Remember to call `shutdown()` on the pool executor when done
- The pool scales down automatically when threads are idle

### Execution Modes

ChronoTask supports three distinct execution modes, configured through the builder API. The mode is determined by which delay methods you call on the builder.

#### One-Time Execution with Initial Delay

A one-time task executes exactly once after an optional initial delay, then automatically stops.

**Configuration:**
- Set only `setInitialDelay()` (or set neither delay)
- Do not set `setPeriodicDelay()` or `setRepetitiveDelay()`

**Example:**

```java
// Execute once immediately
ChronoTask immediate = executor.createTask(t -> {
    System.out.println("Executed immediately");
})
.build();

immediate.start();
// Executes once, then task automatically stops

// Execute once after 5 seconds
ChronoTask delayed = executor.createTask(t -> {
    System.out.println("Executed after 5 seconds");
})
.setInitialDelay(Duration.ofSeconds(5))
.build();

delayed.start();
// Waits 5 seconds, executes once, then stops
```

**Behavior:**
- Task transitions to `NOT_RUNNING` state after execution
- Can be restarted with `start()` to execute again
- Timer thread terminates after execution

#### Periodic Execution (Fixed-Rate)

Periodic execution schedules tasks at **fixed intervals from the start time**, similar to `ScheduledExecutorService.scheduleAtFixedRate()`. The next execution is scheduled immediately when the current execution starts, regardless of how long the execution takes.

**Configuration:**
- Call `setPeriodicDelay(Duration)` on the builder
- Optionally add `setInitialDelay()` for delayed start

**Example:**

```java
// Execute every 10 seconds, starting immediately
ChronoTask periodic = executor.createTask(t -> {
    performPeriodicCheck();
})
.setPeriodicDelay(Duration.ofSeconds(10))
.build();

periodic.start();

// Execute every 1 minute with 30-second initial delay
ChronoTask delayedPeriodic = executor.createTask(t -> {
    syncData();
})
.setInitialDelay(Duration.ofSeconds(30))
.setPeriodicDelay(Duration.ofMinutes(1))
.build();

delayedPeriodic.start();
```

**Timing Behavior:**

```
Time:    0s    10s   20s   30s   40s   50s   60s
         |-----|-----|-----|-----|-----|-----|
Execute: X     X     X     X     X     X     X
         ↑     ↑     ↑     ↑     ↑     ↑     ↑
         └─────┴─────┴─────┴─────┴─────┴─────┘
         Fixed 10-second intervals


Time:    0s    10s   20s   30s
         |-----|-----|-----|
Execute: X===  |     |     |
               X========== |
                     X==   |
                           X===

         triggers at fixed intervals
```

**Important Characteristics:**
- **Fixed schedule**: Next execution scheduled based on start time, not completion time
- **No drift**: Long-term scheduling stays accurate (no cumulative timing errors)
- **Overlap possible**: If execution takes longer than the period, executions may overlap (multiple tasks running simultaneously)

**When to use:**
- You need predictable, fixed-rate execution
- Timing accuracy is important
- Task duration is generally shorter than the period
- Occasional overlapping executions are acceptable

#### Repetitive Execution (Fixed-Delay)

Repetitive execution schedules the next execution **after the previous execution completes**, similar to `ScheduledExecutorService.scheduleWithFixedDelay()`. This guarantees a specific delay between task completions and starts.

**Configuration:**
- Call `setRepetitiveDelay(Duration)` on the builder
- Optionally add `setInitialDelay()` for delayed start

**Example:**

```java
// Execute with 5-second delay after each completion
ChronoTask repetitive = executor.createTask(t -> {
    processQueue(); // May take variable time
})
.setRepetitiveDelay(Duration.ofSeconds(5))
.build();

repetitive.start();

// Execute with 10-second initial delay, then 30-second delays between completions
ChronoTask delayedRepetitive = executor.createTask(t -> {
    performMaintenance();
})
.setInitialDelay(Duration.ofSeconds(10))
.setRepetitiveDelay(Duration.ofSeconds(30))
.build();

delayedRepetitive.start();
```

**Timing Behavior:**

```
Time:    0s  3s    8s 10s   15s 18s   23s
         |---|-----|--|-----|---|-----|-|-----
Execute: X===|     X==|     X===|     X=|
         └─┬─┘     └┬─┘     └─┬─┘     └┬┘
         3s│   5s   │2s 5s  3s│   5s   │1s  5s
         exe delay exe delay exe delay exe delay

Next execution = completion time + 5s delay
```

**Important Characteristics:**
- **Completion-based**: Next execution scheduled only after current execution finishes
- **No overlap**: Tasks never overlap; each execution completes before the next starts
- **Variable intervals**: Total time between starts = execution time + delay
- **Self-throttling**: Automatically adjusts to task execution time

**When to use:**
- Tasks have variable execution times
- You must prevent overlapping executions
- You need guaranteed rest period between tasks
- Task execution time may occasionally exceed desired period

#### Choosing Between Periodic and Repetitive

| Aspect | Periodic (Fixed-Rate) | Repetitive (Fixed-Delay) |
|--------|----------------------|-------------------------|
| **Next execution** | Scheduled from start time | Scheduled from completion time |
| **Interval basis** | Fixed intervals | Completion + delay |
| **Can overlap** | Yes | No |
| **Drift** | No cumulative drift | Potential drift over time |
| **Variable execution** | May cause overlaps | Automatically accommodates |
| **Best for** | Regular schedules | Variable-duration tasks |

### Executor Types

ChronoTask provides two built-in executor implementations, each optimized for different use cases. You can also create custom executors by extending `AbstractExecutor`.

#### ThreadExecutor

`ThreadExecutor` creates **individual threads** for each task execution, providing complete isolation between tasks.

**Key Features:**

- **Virtual Threads by Default**: Uses `Thread.ofVirtual().factory()`, making it efficient even with many concurrent tasks
- **Custom ThreadFactory Support**: Configure thread properties (daemon status, priority, naming, etc.)
- **Automatic Thread Naming**: Named tasks automatically name their threads as `[TaskName]Timer` and `[TaskName]Task#N`
- **Immediate Cleanup**: Stopping a task immediately releases its threads
- **Thread Isolation**: Each task execution gets its own thread, preventing interference

**Configuration:**

```java
// Default configuration (virtual threads)
ThreadExecutor executor = new ThreadExecutor();

// Custom thread factory for platform threads
ThreadFactory customFactory = r -> {
    Thread thread = new Thread(r);
    thread.setDaemon(true);
    thread.setPriority(Thread.NORM_PRIORITY);
    return thread;
};
executor.setThreadFactory(customFactory);

// Custom factory with thread naming
ThreadFactory namedFactory = r -> {
    Thread thread = Thread.ofVirtual().factory().newThread(r);
    thread.setUncaughtExceptionHandler((t, e) -> {
        System.err.println("Exception in " + t.getName() + ": " + e.getMessage());
    });
    return thread;
};
executor.setThreadFactory(namedFactory);
```

**Resource Model:**

Each running task creates:
- **1 timer thread**: Dedicated to scheduling logic
- **N execution threads**: One per execution (created on-demand)

**Advantages:**
- Simple and predictable resource model
- Complete task isolation
- Easy debugging with individual threads
- No contention between tasks
- Automatic cleanup on task stop

**Considerations:**
- More threads than pool-based approach (mitigated by virtual threads)
- Each task manages its own threads independently
- Better for long-running or resource-intensive tasks

**Best Used For:**
- Tasks requiring isolation
- Long-running task executions
- Tasks with different thread requirements
- When thread count is not a concern (virtual threads)
- Development and debugging (clearer thread dumps)

#### PoolExecutor

`PoolExecutor` uses a **shared thread pool** to execute tasks, optimizing resource usage when many tasks compete for limited threads.

**Key Features:**

- **Shared Resource Pool**: Multiple tasks share a common thread pool
- **Dynamic Scaling**: Pool grows and shrinks based on demand
- **Configurable Sizing**: Control minimum threads, maximum threads, and idle time
- **ElasticThreadPool Integration**: Uses the `ElasticThreadPool` implementation
- **Graceful Shutdown**: `shutdown()` method for clean termination

**Configuration:**

```java
// Default: 0 minimum threads, 60-second idle time
PoolExecutor executor = new PoolExecutor();

// Named pool (useful for monitoring)
PoolExecutor namedExecutor = new PoolExecutor("MyAppTasks");

// Custom pool configuration
ElasticThreadPool customPool = ElasticThreadPool.builder()
    .setMinThreads(4)           // Always keep 4 threads alive
    .setMaxThreads(20)          // Scale up to 20 threads
    .setIdleTime(Duration.ofMinutes(5))  // Kill idle threads after 5 minutes
    .setName("TaskExecutionPool")
    .build();

PoolExecutor poolExecutor = new PoolExecutor(customPool);

// Using any AbstractExecutorService
ExecutorService javaExecutor = Executors.newFixedThreadPool(10);
PoolExecutor javaPoolExecutor = new PoolExecutor(javaExecutor);
```

**Resource Model:**

- **Timer threads**: Each task draws one timer thread from the pool while in `RUNNING` state
- **Execution threads**: Drawn from shared pool, reused across all tasks
- **Pool sizing**: Dynamically adjusted based on configuration and demand

**Thread Pool Lifecycle:**

```java
PoolExecutor executor = new PoolExecutor("MyPool");

// Create and start multiple tasks
ChronoTask task1 = executor.createTask(t -> work1()).build();
ChronoTask task2 = executor.createTask(t -> work2()).build();
task1.start();
task2.start();

// Stop individual tasks (timer threads released, pool threads returned to pool)
task1.stop();
task2.stop();

// Shutdown pool when completely done
executor.shutdown();  // Initiates graceful shutdown
```

**Advantages:**
- Efficient resource usage with many tasks
- Bounded thread creation
- Centralized thread management
- Reuses threads across task executions
- Better for systems with thread count limits

**Considerations:**
- Shared pool means tasks can affect each other's performance ⚠️
- Must remember to shutdown pool when done

**Best Used For:**
- Many concurrent tasks
- Short-lived task executions
- Memory-constrained environments
- Systems with thread count limits
- Production deployments with resource management

#### Comparison Matrix

| Feature | ThreadExecutor | PoolExecutor |
|---------|------------------------|----------------------|
| **Thread Model** | Individual threads per execution | Shared thread pool |
| **Default Thread Type** | Virtual threads | Depends on pool configuration |
| **Resource Efficiency** | Lower (more threads) | Higher (shared resources) |
| **Task Isolation** | Complete | Partial (shared pool) |
| **Thread Naming** | Automatic | Automatic (propagated from task name) |
| **Cleanup** | Automatic on task stop | Requires pool shutdown |
| **Scalability** | Excellent (virtual threads) | Good (bounded by pool) |
| **Configuration** | ThreadFactory | Pool parameters (including ThreadFactory) |
| **Best For** | Isolation, debugging | Resource efficiency |
| **Setup Complexity** | Simple | Moderate |

#### Custom Executors

You can create custom executors by extending `AbstractExecutor`:

```java
public class MyCustomExecutor extends AbstractExecutor {

    @Override
    void run(Runnable task) {
        // Your custom execution logic
        // Example: Log, measure, route to specialized threads, etc.
        System.out.println("Executing task");
        new Thread(task).start();
    }

    @Override
    void run(Runnable task, String name) {
        // Your custom execution logic with naming support
        Thread thread = new Thread(task);
        thread.setName(name);
        thread.start();
    }
}

// Use your custom executor
MyCustomExecutor executor = new MyCustomExecutor();
ChronoTask task = executor.createTask(t -> doWork()).build();
```

### Controlling Task Lifecycle

ChronoTask provides simple, intuitive methods for controlling task execution. Each task is an independent object with its own lifecycle that can be managed without affecting other tasks.

#### Starting Tasks

Use the `start()` method to begin task execution. This transitions the task from `NOT_RUNNING` to `RUNNING` state and spawns the timer thread.

**Start Behavior:**

- **Returns `true`**: Task successfully started
- **Returns `false`**: Task was already `RUNNING` (calling `start()` on a running task has no effect)
- **Returns `false` on rejected submission**: If the configured executor rejects the task submission (e.g. a `PoolExecutor` whose underlying pool has been shut down, throwing `RejectedExecutionException`), `start()` catches the rejection, rolls the task back to `STOPPED` (instead of leaving it stuck), and returns `false`. The task remains safely restartable once the executor is available again.
- **Blocks if shutting down**: If `stop()` was called but the timer thread has not fully terminated yet (`SHUTDOWN` state), `start()` waits until the task reaches `STOPPED`, then proceeds normally and returns `true`
- **Immediate effect**: Timer thread created and scheduling begins immediately
- **Initial delay**: If configured, first execution waits for initial delay period

**Example with Initial Delay:**

```java
ChronoTask task = executor.createTask(t -> {
    System.out.println("Executed at: " + LocalDateTime.now());
})
.setInitialDelay(Duration.ofSeconds(5))
.setPeriodicDelay(Duration.ofSeconds(10))
.build();

System.out.println("Starting at: " + LocalDateTime.now());
task.start();
// Timer starts immediately, but first execution waits 5 seconds
// Output:
// Starting at: 2025-11-06T10:00:00
// Executed at: 2025-11-06T10:00:05  (first execution after 5s)
// Executed at: 2025-11-06T10:00:15  (subsequent executions every 10s)
// Executed at: 2025-11-06T10:00:25
```

**Thread Safety:**

The `start()` method is `synchronized`, making it safe to call from multiple threads.

#### Stopping Tasks

Use the `stop()` method to halt task execution and terminate the timer thread gracefully.

**Stop Behavior:**

- **Transitions to `SHUTDOWN`**: `stop()` moves the task to the `SHUTDOWN` state and immediately interrupts the sleeping timer thread so it wakes up and exits without waiting for the full sleep duration
- **Completes to `STOPPED`**: Once the timer thread exits it transitions the task to `STOPPED`
- **No return value**: `stop()` is a `void` method (always succeeds)
- **Idempotent**: Calling `stop()` on a `STOPPED` task is safe (no effect)
- **Current execution**: Any currently executing task instance completes normally
- **Immediate scheduling halt**: No new executions are scheduled after `stop()`

**Timing of Stop:**

```java
ChronoTask task = executor.createTask(t -> {
    System.out.println("Start execution: " + LocalDateTime.now());
    Thread.sleep(3000);  // Simulates long-running work
    System.out.println("End execution: " + LocalDateTime.now());
})
.setPeriodicDelay(Duration.ofSeconds(5))
.build();

task.start();
Thread.sleep(1000);  // Let task start executing
task.stop();         // Stop while task is executing

// Output:
// Start execution: 10:00:00
// End execution: 10:00:03  <- Execution completes despite stop()
// No further executions occur
```

**Thread Safety:**

The `stop()` method is `synchronized` and thread-safe.

#### Restarting Tasks

Tasks can be restarted after stopping, using the same configuration. Simply call `start()` again.

**Restart Behavior:**

- **Same configuration**: All timing parameters (initial delay, periodic/repetitive delay) are preserved
- **Timing restarts**: Next execution time is recalculated from the restart moment (initial delay applies again)
- **Execution count continues**: The execution counter is not reset and continues incrementing across restarts
- **Initial delay reapplied**: If configured, initial delay applies again on restart
- **Unlimited restarts**: Tasks can be stopped and restarted indefinitely

#### Checking Task State

Use the `isRunning()` method to check if a task is actively running.

**State Check Behavior:**

- **Returns `true`**: Task is in `RUNNING` state (timer thread active)
- **Returns `false`**: Task is in `SHUTDOWN` or `STOPPED` state (timer thread winding down or terminated)
- **Thread-safe**: Safe to call from any thread
- **Real-time**: Reflects current state accurately

**Coordination Between Tasks:**

```java
ChronoTask primaryTask = executor.createTask(t -> {
    System.out.println("Primary task running");
})
.setPeriodicDelay(Duration.ofSeconds(5))
.build();

ChronoTask secondaryTask = executor.createTask(t -> {
    // Secondary task only runs when primary is active
    if (primaryTask.isRunning()) {
        System.out.println("Secondary task running");
    } else {
        System.out.println("Primary stopped, stopping secondary");
        t.stop();  // Stop self
    }
})
.setPeriodicDelay(Duration.ofSeconds(2))
.build();

primaryTask.start();
secondaryTask.start();

Thread.sleep(10000);
primaryTask.stop();  // Secondary will detect and stop itself
```

#### Self-Stopping Tasks

Tasks receive a reference to themselves (`Consumer<ChronoTask>`), enabling self-introspection and self-control.

**Task Stops Itself After Condition:**

```java
AtomicInteger counter = new AtomicInteger(0);

ChronoTask selfStoppingTask = executor.createTask(t -> {
    int count = counter.incrementAndGet();
    System.out.println("Execution #" + count);

    // Stop after 5 executions
    if (count >= 5) {
        System.out.println("Reached limit, stopping");
        t.stop();  // Task stops itself
    }
})
.setPeriodicDelay(Duration.ofSeconds(1))
.build();

selfStoppingTask.start();
// Task will run 5 times and then stop automatically
```

**Task Stops on Error Condition:**

```java
ChronoTask monitoringTask = executor.createTask(t -> {
    if (!checkSystemHealth()) {
        System.err.println("System unhealthy, stopping monitoring");
        t.stop();  // Stop self on error
        return;
    }

    System.out.println("System healthy");
})
.setPeriodicDelay(Duration.ofSeconds(10))
.build();

monitoringTask.start();
```

#### Lifecycle Summary

```
┌─────────────────┐
│  Task Created   │
│   (STOPPED)     │
└────────┬────────┘
         │
         │ start() → returns true
         ▼
┌─────────────────┐
│  Task Running   │
│   (RUNNING)     │◄──────────────┐
└────────┬────────┘               │
         │                        │
         │ stop()                 │ start() → blocks until STOPPED,
         ▼                        │           then returns true
┌─────────────────┐               │
│  Shutting Down  │               │
│   (SHUTDOWN)    │               │
└────────┬────────┘               │
         │ timer thread exits     │
         ▼                        │
┌─────────────────┐               │
│  Task Stopped   │───────────────┘
│   (STOPPED)     │
└─────────────────┘

Note: start() on RUNNING task returns false (no state change)
      start() rejected by executor returns false and rolls back RUNNING → STOPPED
      stop() on STOPPED task has no effect (idempotent)
```

**Key Lifecycle Points:**

1. **Creation**: Task starts in `STOPPED` state
2. **Start**: Transitions to `RUNNING`, starts timer thread; blocks if currently in `SHUTDOWN`
3. **Running**: Task executes according to configuration
4. **Stop**: Transitions to `SHUTDOWN`, interrupts sleeping timer thread; timer thread exits and transitions task to `STOPPED`
5. **Restart**: Can repeat start-stop cycle indefinitely
6. **Self-stop**: Task can stop itself from within execution
7. **One-time**: Automatically stops after single execution (if no periodic/repetitive delay)

### Future-Based Tasks (FutureChronoTask)

`FutureChronoTask<T>` is created via `createFutureTask(Function<FutureChronoTask<T>, T>)` on any executor. Use it when you need to receive the result of each execution as a `CompletableFuture<T>`.

#### One-Shot with Result

```java
ThreadExecutor executor = new ThreadExecutor();

FutureChronoTask<String> task = executor.createFutureTask(futureTask -> {
    return fetchDataFromRemote();
})
.setName("RemoteFetch")
.setInitialDelay(Duration.ofSeconds(1))
.build();

CompletableFuture<String> firstResult = task.start();
String data = firstResult.get(5, TimeUnit.SECONDS);
System.out.println("Got: " + data);
```

#### Recurring with Result Chaining

```java
FutureChronoTask<Integer> task = executor.createFutureTask(t -> {
    return computeMetric();
})
.setName("MetricCollector")
.setPeriodicDelay(Duration.ofSeconds(10))
.build();

CompletableFuture<Integer> next = task.start();

// Chain across multiple executions
next.thenAccept(value -> {
    System.out.println("First: " + value);
    // Get the future for the next execution
    task.getNextResult().thenAccept(v2 -> System.out.println("Second: " + v2));
});
```

#### Reading the Last Successful Result

```java
// After at least one execution, getLastResult() returns the most recent value
task.getLastResult().ifPresent(v -> System.out.println("Last: " + v));
```

#### Exception Handling

If the task throws, the future completes exceptionally — `getLastResult()` is not updated.

```java
CompletableFuture<String> result = task.start();
result.exceptionally(ex -> {
    System.err.println("Task failed: " + ex.getMessage());
    return null;
});
```

## Advanced Usage

### Custom Thread Factories

`ThreadExecutor` allows you to customize thread creation behavior through the `ThreadFactory` interface. This provides fine-grained control over thread properties and behavior.

```java
ThreadFactory namedThreadFactory = new ThreadFactory() {
    private final AtomicInteger threadNumber = new AtomicInteger(1);

    @Override
    public Thread newThread(Runnable r) {
        Thread thread = Thread.ofVirtual().factory().newThread(r);
        thread.setName("MyApp-Worker-" + threadNumber.getAndIncrement());
        return thread;
    }
};

executor.setThreadFactory(namedThreadFactory);
// Threads will be named: MyApp-Worker-1, MyApp-Worker-2, etc.
```

### Working with Thread Pools

`PoolExecutor` provides flexible thread pool configuration through `ElasticThreadPool` or any `AbstractExecutorService` implementation.

#### Understanding Thread Pool Configuration

Thread pools in ChronoTask (via `ElasticThreadPool`) are configured with three main parameters:

- **Minimum Threads**: Core threads that stay alive even when idle
- **Maximum Threads**: Upper bound on total threads (determined by `ElasticThreadPool`)
- **Idle Time**: How long threads wait for work before terminating

#### Default Pool Configuration

The default `PoolExecutor` constructor creates a pool optimized for dynamic scaling:

```java
PoolExecutor executor = new PoolExecutor();
// Equivalent to:
// - Minimum threads: 0 (no threads kept alive when idle)
// - Idle time: 60 seconds (threads terminate after 60s of inactivity)
```

**Behavior:**
- Pool starts with zero threads
- Threads created on demand when tasks execute
- Idle threads terminate after 60 seconds
- Pool scales down to zero when all threads are idle
- Memory efficient for intermittent task execution

#### Custom Pool Configurations

**Always-Ready Pool (Minimum Threads):**

```java
ElasticThreadPool pool = ElasticThreadPool.builder()
    .setMinThreads(4)  // Always keep 4 threads alive
    .setIdleTime(Duration.ofMinutes(10))
    .setName("AlwaysReadyPool")
    .build();

PoolExecutor executor = new PoolExecutor(pool);
```

#### Named Thread Pools

Naming pools helps with monitoring, debugging, and thread dump analysis:

```java
// Simple named pool
PoolExecutor executor = new PoolExecutor("BackgroundTasks");

// Custom named pool
ElasticThreadPool pool = ElasticThreadPool.builder()
    .setName("DataSyncPool")
    .setMinThreads(2)
    .setIdleTime(Duration.ofMinutes(5))
    .build();

PoolExecutor executor = new PoolExecutor(pool);
```

Threads in the pool will have names based on the pool name, making them easy to identify in monitoring tools.

#### Using Standard Java Executors

`PoolExecutor` accepts any `AbstractExecutorService`:

```java
// Fixed thread pool
ExecutorService fixedPool = Executors.newFixedThreadPool(8);
PoolExecutor executor = new PoolExecutor(fixedPool);

// Cached thread pool
ExecutorService cachedPool = Executors.newCachedThreadPool();
PoolExecutor cachedExecutor = new PoolExecutor(cachedPool);

// Scheduled executor (for integration)
ScheduledExecutorService scheduledPool = Executors.newScheduledThreadPool(4);
PoolExecutor scheduledExecutor = new PoolExecutor(scheduledPool);

// Work-stealing pool (Java 8+)
ExecutorService workStealingPool = Executors.newWorkStealingPool();
PoolExecutor stealingExecutor = new PoolExecutor(workStealingPool);
```

#### Pool Lifecycle Management

Proper pool shutdown is critical to prevent resource leaks:

**Graceful Shutdown**

```java
PoolExecutor executor = new PoolExecutor("MyPool");

try {
    // Create and run tasks
    ChronoTask task1 = executor.createTask(t -> work1()).build();
    ChronoTask task2 = executor.createTask(t -> work2()).build();

    task1.start();
    task2.start();

    // ... application logic ...

} finally {
    // Stop all tasks
    task1.stop(); // IMPORTANT
    task2.stop(); // IMPORTANT

    // Shutdown the pool
    executor.shutdown();

    // Optionally wait for termination
    boolean terminated = executor.awaitTermination(Duration.ofSeconds(30));
    if (!terminated) {
        System.err.println("Pool did not terminate within timeout");
    }
}
```

**Forceful Shutdown**

```java
PoolExecutor executor = new PoolExecutor("MyPool");

try {
    // Create and run tasks
    ChronoTask task1 = executor.createTask(t -> work1()).build();
    ChronoTask task2 = executor.createTask(t -> work2()).build();

    task1.start();
    task2.start();

    // ... application logic ...

} finally {
    // task1.stop(); // CAN BE SKIPPED
    // task2.stop(); // CAN BE SKIPPED

    // Shutdown the pool forcefully
    executor.shutdownNow();
}
```

### Memory Considerations

ChronoTask is designed with memory efficiency in mind, but long-running tasks require careful attention to prevent memory leaks.

#### Understanding Memory Retention

Memory leaks in ChronoTask typically occur through:

1. **Strong references in task closures**: Tasks capture external objects
2. **Long-lived task instances**: Tasks that run indefinitely
3. **Accumulated state**: Data that grows over time within tasks
4. **External object retention**: Tasks holding references to large objects

#### The Strong Reference Problem

**Problem Example:**

```java
public class ServiceManager {
    private LargeDataService dataService = new LargeDataService(); // Large object

    public void startMonitoring() {
        ChronoTask monitor = executor.createTask(t -> {
            // This closure captures 'this', which includes dataService
            dataService.checkHealth();
        })
        .setPeriodicDelay(Duration.ofSeconds(10))
        .build();

        monitor.start();

        // Even if we set dataService = null later, the task still holds a reference!
        // Memory leak: dataService cannot be garbage collected while task runs
    }
}
```

**Why This Happens:**

The task lambda captures the enclosing `ServiceManager` instance (`this`), creating a strong reference chain:
```
ChronoTask → Task Lambda → ServiceManager → dataService
```

As long as the `ChronoTask` is running, `this` `ServiceManager` instance remains in memory, even if no longer needed elsewhere.

#### Solution 1: Weak References

Use `WeakReference` to allow garbage collection:

```java
public class ServiceManager {
    private LargeDataService dataService = new LargeDataService();

    public void startMonitoring() {
        // Create weak reference to allow GC
        WeakReference<LargeDataService> serviceRef = new WeakReference<>(dataService);

        ChronoTask monitor = executor.createTask(t -> {
            LargeDataService service = serviceRef.get();
            if (service != null) {
                service.checkHealth();
            } else {
                // Service was garbage collected
                System.out.println("Service no longer available, stopping monitor");
                t.stop();  // Stop task when service is gone
            }
        })
        .setPeriodicDelay(Duration.ofSeconds(10))
        .build();

        monitor.start();

        // Now dataService can be GC'd when no longer needed
        // Task will detect this and stop itself
    }
}
```

**Benefits:**
- Allows large objects to be garbage collected
- Task can detect when referenced object is gone
- Prevents memory leaks in long-running tasks

#### Solution 2: Static Methods or Separate Classes

Avoid capturing the enclosing instance by using static methods:

```java
public class ServiceManager {
    private LargeDataService dataService = new LargeDataService();

    public void startMonitoring() {
        // Pass only what's needed, not the entire 'this'
        String serviceId = dataService.getId();

        ChronoTask monitor = executor.createTask(t -> {
            // This doesn't capture ServiceManager instance
            MonitoringUtils.checkHealth(serviceId);
        })
        .setPeriodicDelay(Duration.ofSeconds(10))
        .build();

        monitor.start();
    }
}

class MonitoringUtils {
    static void checkHealth(String serviceId) {
        // Lookup service by ID, or work with minimal data
        // No strong reference to ServiceManager
    }
}
```

#### Memory-Safe Patterns

**Pattern 1: Minimal Capture**

```java
// BAD: Captures entire object
ChronoTask task = executor.createTask(t -> {
    this.processAllData();  // Captures 'this'
}).build();

// GOOD: Capture only what's needed
String data = this.data;
ChronoTask task = executor.createTask(t -> {
    process(data);  // Captures only 'data' string
}).build();
```

**Pattern 2: Weak Reference with Cleanup**

```java
WeakReference<HeavyResource> resourceRef = new WeakReference<>(heavyResource);
AtomicBoolean cleanedUp = new AtomicBoolean(false);

ChronoTask task = executor.createTask(t -> {
    HeavyResource resource = resourceRef.get();

    if (resource == null && !cleanedUp.getAndSet(true)) {
        System.out.println("Resource GC'd, performing cleanup");
        performCleanup();
        t.stop();
    } else if (resource != null) {
        resource.doWork();
    }
}).setPeriodicDelay(Duration.ofSeconds(10)).build();
```

## Best Practices

### 1. Avoid Strong References in Tasks

**Problem**: Task lambdas can inadvertently capture large objects, preventing garbage collection and causing memory leaks.

#### Why This Matters

When you create a task lambda inside a class, the lambda implicitly captures `this`, creating a strong reference to the enclosing instance. If the task runs for a long time, this prevents the entire object (and everything it references) from being garbage collected.

#### Best Practice: Use Weak References

```java
// ✅ GOOD: Use WeakReference for large objects
public class DataService {
    private byte[] largeDataBuffer = new byte[100_000_000];

    public void startMonitoring() {
        // Create weak reference to allow GC
        WeakReference<DataService> serviceRef = new WeakReference<>(this);

        ChronoTask task = executor.createTask(t -> {
            DataService service = serviceRef.get();
            if (service != null) {
                service.checkStatus();
            } else {
                // Service was GC'd, stop the task
                System.out.println("Service no longer available");
                t.stop();
            }
        }).setPeriodicDelay(Duration.ofMinutes(1)).build();

        task.start();
        // largeDataBuffer can now be GC'd when service instance is no longer needed
    }
}
```

#### Best Practice: Extract Minimal Data

```java
// ✅ GOOD: Capture only what's needed
public class DataService {
    private byte[] largeDataBuffer = new byte[100_000_000];
    private String serviceId;

    public void startMonitoring() {
        // Extract only the ID, not the entire object
        String id = this.serviceId;

        ChronoTask task = executor.createTask(t -> {
            // Only 'id' is captured, not 'this'
            checkStatusById(id);
        }).setPeriodicDelay(Duration.ofMinutes(1)).build();

        task.start();
    }

    private static void checkStatusById(String id) {
        // Static method doesn't require instance
    }
}
```

#### Checklist

- [ ] Review task lambdas for implicit `this` captures
- [ ] Use `WeakReference` for large objects in long-running tasks
- [ ] Extract only necessary data before creating task
- [ ] Consider static methods to avoid instance capture
- [ ] Add task timeout/self-stop logic for finite lifetimes

### 2. Proper Resource Cleanup

**Problem**: Failing to stop tasks and shutdown executors causes resource leaks and prevents clean application shutdown.

#### Why This Matters

Running tasks hold threads and other resources. Without proper cleanup:
- JVM may not exit (non-daemon threads keep it alive)
- Memory leaks from accumulated task state
- Thread pool resources remain allocated
- Application shutdown hangs or times out

#### Best Practice: Stop Tasks Explicitly

```java
// ✅ GOOD: Store reference and stop when done
public class DataProcessor {
    private ChronoTask processingTask;

    public void start() {
        processingTask = executor.createTask(t -> processData())
            .setPeriodicDelay(Duration.ofMinutes(5))
            .build();

        processingTask.start();
    }

    public void stop() {
        if (processingTask != null) {
            processingTask.stop();
        }
    }
}
```

#### Best Practice: Use Try-Finally or Try-With-Resources Pattern

```java
// ✅ GOOD: Ensure cleanup with try-finally
public void runProcessing() {
    ChronoTask task = executor.createTask(t -> process()).build();

    try {
        task.start();
        // Application logic
        doWork();
    } finally {
        // Guaranteed cleanup
        task.stop();
    }
}
```

#### Checklist

- [ ] Every started task has a clear stop point
- [ ] Pool executors are shutdown when application terminates
- [ ] Use try-finally for guaranteed cleanup
- [ ] Implement graceful shutdown with timeouts
- [ ] Consider shutdown hooks for non-daemon threads
- [ ] Test shutdown scenarios (normal exit, interruption, errors)

### 3. Choosing Between Periodic and Repetitive

**Problem**: Using the wrong execution mode can cause overlapping executions, timing drift, or inefficient resource usage.

#### Understanding the Difference

**Periodic (Fixed-Rate)**:
- Schedules based on **start time** of executions
- Maintains consistent rate over time
- Executions may overlap if duration > period
- No drift accumulation

**Repetitive (Fixed-Delay)**:
- Schedules based on **completion time** of executions
- Guarantees delay between executions
- No overlapping executions
- May drift over long periods

#### When to Use Periodic (Fixed-Rate)

```java
// ✅ GOOD: Periodic for fast, predictable tasks
ChronoTask healthCheck = executor.createTask(t -> {
    // Fast check: 10-50ms
    boolean healthy = checkServiceHealth();
    logHealth(healthy);
})
.setPeriodicDelay(Duration.ofSeconds(30))
.build();

// Result: Checks run exactly every 30 seconds
// 00:00, 00:30, 01:00, 01:30, ...
```

**Use periodic when**:
- Task execution is fast (< 10% of period)
- Consistent timing is important
- You need predictable scheduling (e.g., "every 5 minutes")
- Task should run at specific intervals regardless of duration
- Occasional overlaps are acceptable

#### When to Use Repetitive (Fixed-Delay)

```java
// ✅ GOOD: Repetitive for variable-duration tasks
ChronoTask batchProcessor = executor.createTask(t -> {
    // Variable duration: 100ms to 10 seconds
    processBatchFromQueue();  // Duration depends on batch size
})
.setRepetitiveDelay(Duration.ofSeconds(5))
.build();

// Result: 5-second rest between batches, no overlaps
```

**Use repetitive when**:
- Task execution time is variable or unpredictable
- Tasks must not overlap
- You need guaranteed rest period between executions
- Task duration may occasionally exceed desired period
- System needs time to recover/cool down between runs

#### Comparison Example

```java
// PERIODIC: May overlap if processing is slow
ChronoTask periodicTask = executor.createTask(t -> {
    Thread.sleep(7000);  // Task takes 7 seconds
})
.setPeriodicDelay(Duration.ofSeconds(5))  // But period is 5 seconds
.build();
// Result: Multiple tasks run simultaneously!
// Time:  0s   5s   7s   10s  12s  15s  17s
// Exec:  [----X----]
//             [----X----]
//                  [----X----]
//                       [----X----]
// (overlapping executions)

// REPETITIVE: Never overlaps
ChronoTask repetitiveTask = executor.createTask(t -> {
    Thread.sleep(7000);  // Task takes 7 seconds
})
.setRepetitiveDelay(Duration.ofSeconds(5))  // 5 seconds after completion
.build();
// Result: Tasks never overlap
// Time:  0s   7s   12s  19s  24s
// Exec:  [----X----]    [----X----]
//                 ^5s^        ^5s^
// (guaranteed 5-second gap)
```

#### Decision Tree

```
Is task duration predictable and fast?
├─ YES: Use PERIODIC
│   └─ Fast, consistent scheduling
│
└─ NO: Can task duration exceed period?
    ├─ YES: Use REPETITIVE (prevent overlaps)
    │   └─ Safe, no resource contention
    │
    └─ NO: Either works, prefer PERIODIC
        └─ More predictable timing
```

#### Checklist

- [ ] Measured typical and maximum task execution time
- [ ] Chosen mode appropriate for task characteristics
- [ ] Considered impact of potential overlaps (periodic)
- [ ] Documented why mode was chosen
- [ ] Tested with realistic workloads

### 4. Thread Pool vs Individual Threads

**Problem**: Choosing the wrong executor type can lead to resource exhaustion or unnecessary overhead.

#### Understanding the Tradeoff

**ThreadExecutor** (Individual Threads):
- ✅ Complete task isolation
- ✅ Simple resource model
- ✅ Easy debugging (clear thread names)
- ❌ More threads (mitigated by virtual threads)

**PoolExecutor** (Shared Pool):
- ✅ Bounded resource usage
- ✅ Thread reuse efficiency
- ✅ Centralized management
- ❌ Tasks can affect each others performance
- ❌ More complex configuration

#### When to Use ThreadExecutor

```java
// ✅ GOOD: Thread executor for isolated, long-running tasks
ThreadExecutor executor = new ThreadExecutor();

ChronoTask task1 = executor.createTask(t -> {
    // This task needs isolation
    performCriticalOperation();
}).setName("CriticalTask").build();

ChronoTask task2 = executor.createTask(t -> {
    // This task has different thread requirements
    performBackgroundWork();
}).setName("BackgroundTask").build();
```

**Use thread executor when**:
- You have relatively few tasks (< 100)
- Tasks need complete isolation
- Tasks have different thread requirements (priority, daemon status)
- Debugging is important (named threads help)
- Using virtual threads (default) – lightweight and efficient
- Each task needs its own dedicated resources

#### When to Use PoolExecutor

```java
// ✅ GOOD: Pool executor for many similar tasks
ElasticThreadPool pool = ElasticThreadPool.builder()
    .setMinThreads(4)
    .setMaxThreads(20)
    .setName("TaskPool")
    .build();

PoolExecutor executor = new PoolExecutor(pool);

// Create many tasks sharing the pool
for (int i = 0; i < 100; i++) {
    ChronoTask task = executor.createTask(t -> {
        processItem(i);
    }).build();
    task.start();
}
```

**Use pool executor when**:
- You have many tasks (> 20)
- Resource limits are important
- Tasks are similar in nature
- Memory efficiency is a priority
- You need centralized thread management
- Platform threads are required (not virtual)

#### Configuration Guidelines

**For Thread Executor:**
```java
ThreadExecutor executor = new ThreadExecutor();

// Default (virtual threads) is usually best
// Only customize if you need specific thread properties:
if (needsPlatformThreads) {
    ThreadFactory factory = r -> {
        Thread thread = new Thread(r);
        thread.setDaemon(true);
        thread.setPriority(Thread.HIGH_PRIORITY);
        return thread;
    };
    executor.setThreadFactory(factory);
}
```

**For Pool Executor:**
```java
// Configure based on workload characteristics
ElasticThreadPool pool = ElasticThreadPool.builder()
    .setMinThreads(cpuCores)              // For CPU-bound tasks
    .setMaxThreads(cpuCores * 2)          // Reasonable upper bound
    .setIdleTime(Duration.ofMinutes(5))   // Keep threads longer for steady load
    .setName("WorkerPool")
    .build();

// Or for I/O-bound tasks:
ElasticThreadPool ioPool = ElasticThreadPool.builder()
    .setMinThreads(0)                     // Scale down when idle
    .setMaxThreads(100)                   // Can have many waiting for I/O
    .setIdleTime(Duration.ofSeconds(60))  // Quick scale-down
    .setName("IOPool")
    .build();
```

#### Decision Matrix

| Scenario | Executor Type | Rationale |
|----------|---------------|-----------|
| < 20 tasks | Thread Executor | Overhead is minimal, simplicity wins |
| > 100 tasks | Pool Executor | Resource efficiency matters |
| CPU-bound tasks | Pool Executor | Limit to CPU core count |
| I/O-bound tasks | Thread Executor (virtual) | Lightweight, no contention |
| Mixed workload | Both | Separate critical from background |
| Development | Thread Executor | Easier debugging |
| Production | Pool Executor | Predictable resource usage |

#### Checklist

- [ ] Counted expected number of concurrent tasks
- [ ] Identified task characteristics (CPU/I/O-bound)
- [ ] Chosen executor type based on workload
- [ ] Configured pool size appropriately (if using pool)
- [ ] Documented executor choice reasoning
- [ ] Load tested with realistic task counts

### 5. Naming Tasks

**Problem**: Unnamed tasks make debugging, monitoring, and troubleshooting difficult.

#### Best Practice: Always Name Production Tasks

```java
// ✅ GOOD: Named task
ChronoTask task = executor.createTask(t -> {
    processData();
})
.setName("DataProcessor")
.build();
// Thread names: "[DataProcessor]Timer", "[DataProcessor]Task#1"
// (immediately clear what each thread does!)
```

## Requirements

- Java 25+
- JUnit 5

## Dependencies

- [ElasticThreadPool](https://github.com/Adrian-26-Isotope/ElasticThreadPool) (maven dependency)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Author

Adrian-26-Isotope
