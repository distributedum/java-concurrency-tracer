# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A teaching tool for a Distributed Systems course (originally in Portuguese; now
fully translated to English). Students instrument
their own Java lock/condition-variable code and get a visual space-time (Lamport)
diagram of the execution: who blocked on what, who signaled whom, per-thread state
over time, and happens-before edges. No external dependencies — just the JDK to run
and a browser to view `viz/spacetime.html`.

There are two independent ways to instrument student code, both producing the same
`trace.json` format consumed by the visualizer. The **agent is the documented
default** (no code changes); the **wrappers are the fallback** for students stuck
on JDK 21–23:

1. **Java agent** (`-javaagent:sdtrace-agent.jar`) — bytecode weaving via
   `java.lang.classfile` (final in JDK 24+), so students don't touch their code at
   all. Requires JDK 24+.
2. **Wrappers** (`TracedLock`, `TracedCondition`, `TracedReadWriteLock`) — students
   swap `ReentrantLock`/`ReentrantReadWriteLock` for the traced equivalents, which
   implement the same `Lock`/`Condition`/`ReadWriteLock` interfaces. Works on any
   JDK (compiled with `--release 21`).

Both paths funnel into the same `pt.sd.trace.Tracer` singleton, which is the single
source of truth for event ordering, Lamport clocks, and causal edges.

## Build & run commands

```bash
# Agent path (recommended default, JDK 24+, transparent instrumentation)
./build-agent.sh demo/BoundedBufferRaw.java BoundedBufferRaw
# equivalent manual steps: compiles runtime with --release 21, agent classes with
# --release 24, packages both into sdtrace-agent.jar, then runs with -javaagent

# Wrapper path (fallback for JDK 21-23, compile with -g for readable names)
./build.sh demo/BoundedBufferDemo.java BoundedBufferDemo
# equivalent manual steps:
mkdir -p out
javac -g -encoding UTF-8 -d out src/pt/sd/trace/*.java demo/BoundedBufferDemo.java
java -cp out BoundedBufferDemo   # writes trace.json via shutdown hook
```

Both scripts also regenerate `viz/spacetime.html` by injecting the freshly produced
`trace.json` into `viz/template.html` (via a `python3` inline script, if available).

There is no test suite or linter in this repo; `viz/core.js` is written to be
`require`-able from Node for manual/ad-hoc checks (`module.exports` at the bottom)
but there's no test harness set up.

### Versioned generated artifacts

`sdtrace-agent.jar` and `viz/spacetime.html` are intentionally committed so students
can use them without compiling anything. Running either build script **modifies
both files** (recompiled jar, new embedded trace). To discard those local changes:
`git checkout -- sdtrace-agent.jar viz/spacetime.html`.

## Architecture

### Tracing core (`src/pt/sd/trace/`)

- **`Tracer.java`** — the singleton (`Tracer.get()`) that all instrumentation funnels
  through. `emit(...)` is the only place that creates events; it's fully
  `synchronized` to guarantee a single global total order (`seq`) and consistent
  shared state. For each event it computes:
  - a per-thread Lamport clock (`max(local, causes...) + 1`),
  - a physical timestamp (nanos since tracer init),
  - a **set** of causal edges (`causes`, plural — not just one `cause`), inferred by:
    1. **Lock handoff**: on `LOCK_ACQUIRED`, a shared-mode (reader) acquisition
       depends only on the last exclusive-mode release (writer publishing state);
       an exclusive-mode acquisition (plain lock or writer) depends on the *entire
       cohort* of releases that last emptied the lock, i.e. **all** readers that were
       holding it, not just the most recent one. This is required to keep Lamport's
       clock condition correct — using only the last recorded release can produce a
       clock that isn't a valid upper bound.
    2. **signal → await**: FIFO pairing per condition variable (`waitersByCond`
       deque, `pendingWakeup` map). This is an approximation — Java doesn't guarantee
       wakeup order or rule out spurious wakeups — documented as such.
    3. **Thread lifecycle**: `start()` → the started thread's first event
       (`pendingStartByTid`, keyed by the *child's* tid — known before `start()`
       since `Thread.threadId()` is assigned at construction) and the joined
       thread's last event → `join()`'s return (`lastEventByTid`, one entry per
       thread, updated on every `emit`). Unlike (2), both are **exact**
       happens-before edges, not an approximation.
  - Writes JSON manually (no external libs) via `toJson()`/`str()`.
- **`TracedLock.java`** / **`TracedCondition.java`** / **`TracedReadWriteLock.java`** —
  thin wrappers implementing the standard `java.util.concurrent.locks` interfaces,
  delegating to a real `ReentrantLock`/`ReentrantReadWriteLock` and calling into
  `Tracer` around `lock()`/`unlock()`/`await()`/`signal()`. `TracedReadWriteLock`'s
  read and write views share the same lock **name** but differ by **mode**
  (`READ`/`WRITE`) — this is what lets the tracer correctly model reader/writer
  exclusion (see cohort logic above).
- **`Hooks.java`** — static entry points called by agent-woven bytecode (mirrors the
  wrapper API but for code that wasn't touched by hand). Thread-lifecycle entry
  points (`onThreadStart`, `onJoinBegin`, `onJoinEnd`) each guard on
  `instanceof Thread` and no-op otherwise, since `LockWeaver` matches
  `start()`/`join()` call sites by method name alone (see below) — this guard is
  what makes that safe. `onRunBegin`/`onRunEnd` have no receiver to guard on;
  safety there comes from `LockWeaver` only weaving `run()` on classes that are
  themselves shaped like a `Thread`/`Runnable` (see below).

### Agent (`src/pt/sd/trace/agent/`)

- **`Agent.java`** — `premain`, registers a `ClassFileTransformer`. Skips
  `java/`, `jdk/`, `sun/`, `javax/`, and its own `pt/sd/trace/` package. Supports
  `include=`/`exclude=`/`verbose` options (package-prefix filters, comma-separated,
  dots or slashes). Cheaply pre-filters classes by scanning the constant pool for
  `java/util/concurrent/locks/`, `java/lang/Thread`, `java/lang/Runnable`, or the
  `start`/`join` UTF-8 entries before attempting to weave — a false positive here
  just costs one weave attempt that changes nothing.
- **`LockWeaver.java`** — the actual bytecode rewriting using the JDK 24+
  `java.lang.classfile` API. Infers human-readable lock/condition names from field
  names (always reliable) or local variable names (only if compiled with `-g`);
  falls back to `Type@Class:line` otherwise. Wraps `lock()`, `unlock()`, `await()`,
  `signal()`, `signalAll()` calls on `java.util.concurrent.locks.*`. Deliberately
  does **not** cover `tryLock`, timed `await`, or `synchronized`/`wait`/`notify`.
  Also wraps no-arg `start()`/`join()` — matched **by method name alone** (no
  class-hierarchy info at transform time: `class Worker extends Thread` compiles
  `w.start()` to `invokevirtual Worker.start`, not `Thread.start`), with `Hooks`
  re-checking `instanceof Thread` at runtime to reject false matches. Separately,
  `run()` on a class that directly extends `Thread` or implements `Runnable` gets
  entry/exit calls woven in (`onRunBegin`/`onRunEnd`) — this is what makes
  `THREAD_END` automatic and no longer requires a manual `Tracer.threadDone()`
  call, for classes shaped that way.

Because `sdtrace-agent.jar`'s runtime classes are compiled with `--release 21` and
its agent classes with `--release 24`, the single jar works both as a library
(JDK 21+) and as a `-javaagent` (JDK 24+).

### Visualizer (`viz/`)

- **`core.js`** — pure trace-processing logic, no DOM dependency, `module.exports`ed
  for reuse/testing under Node. Computes per-thread state segments (`RUNNING`,
  `HOLDING`, `HOLDING_SHARED`, `BLOCKED`, `WAITING`, `JOINING`, `TERMINATED`) from
  the event stream, keyed on either the physical (`t`) or Lamport (`lamport`) axis.
  **Duplicated** (by design, so the viewer has no build step) as an inline copy in
  `template.html` — the two must be kept in sync by hand.
- **`template.html`** — the viewer shell with a `/*__TRACE__*/ ... /*__END__*/`
  marker where build scripts inject a trace.
- **`spacetime.html`** — `template.html` with a trace already embedded, committed so
  it opens standalone with a working example; also the file both build scripts
  overwrite with the newly generated trace.

### Event kinds

`LOCK_REQUEST`, `LOCK_ACQUIRED`, `LOCK_RELEASED`, `AWAIT_BEGIN`, `AWAIT_WAKEUP`,
`SIGNAL`, `SIGNAL_ALL`, `NOTE` (student-triggered via `Tracer.note(...)`),
`THREAD_START` (parent, at `start()`), `THREAD_BEGIN` (child, at `run()` entry —
exact cause: the matching `THREAD_START`), `JOIN_BEGIN` (parent, at `join()`),
`THREAD_JOIN` (parent, at `join()` return — exact cause: the joined thread's last
event), `THREAD_END` (now emitted automatically at `run()` return for a woven
class; `Tracer.threadDone()` stays available and is idempotent against a
duplicate automatic emission). Mode field is `EXCLUSIVE` | `READ` | `WRITE` |
`null`.

## Key invariants to preserve when touching tracer logic

- `Tracer.emit` must stay `synchronized` — the total order and all the per-lock/
  per-condition bookkeeping maps depend on serialized access.
- Causal edges are a **set** (`causes[]`), not a single value; the Lamport clock
  must take `max` over all of them. The `cause` singular field exists only for
  backward-compat JSON consumers and should keep mirroring `causes[0]`.
- Reader/writer cohort tracking (`lastCohortByLock`, `pendingReleasesByLock`,
  `holdersByLock`) is what makes multi-reader diagrams correct — don't collapse it
  back to "depend on the single most recent release."
