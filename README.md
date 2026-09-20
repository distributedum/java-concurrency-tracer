# ThreadLens - A Java Tool for Visualizing Multi-Threaded Executions

A Java agent that lets you see causality in concurrent programs. Point it at any
program that uses locks and condition variables and you'll get a space-time diagram
of what actually happened: who blocked on which lock, who waited on which
condition, who signalled whom, and which events really happened before which, 
annotated with Lamport clocks. Your own code stays exactly as it is.

![Space-time diagram of a single wait/signal handoff, on the Lamport-clock axis](assets/screenshot.png "=350px")

## Setup

### (Optional) Build the jar

```bash
./build.sh
```

Produces `sdtrace-agent.jar` to be included in your project's library. Alternatively, you can use the `sdtrace-agent.jar` already in the repository.

### IntelliJ IDEA

1. **Project SDK**: *File → Project Structure → Project → SDK* → a JDK **24 or
   higher** (*Add SDK → Download JDK…* if none is installed).
2. **Add the library**: right-click `sdtrace-agent.jar` → **Add as Library…**
   (needed for `Tracer.note(...)`).
3. **Activate the agent**: *Run → Edit Configurations…* → select your run
   configuration → **Modify options → Add VM options**, then set:

   ```
   -javaagent:sdtrace-agent.jar=include=pt.uminho.sd
   ```

   All your project files you want to analyse must be under the package you specify in the `include=` parameter.
   
4. Run it, ant verify the console prints `[sdtrace] agent active | include=pt/uminho/sd`. If it doesn't, the `-javaagent` option wasn't picked up. Re-do the steps above.

### javac and java

```bash
javac -g -cp sdtrace-agent.jar -d out src/*.java
java -javaagent:sdtrace-agent.jar=include=your.package -cp sdtrace-agent.jar:out Main
```

> Note: On Windows, use `;` instead of `:` as the classpath separator. `include=` takes
comma-separated package prefixes and keeps other classpath libraries' internal
locks out of the diagram; `-g` only matters for locks held in local variables
(see Known limitations).

## Usage

### Instrumenting a class

Write the class as usual. No additional code is needed to make the agent work.
If there are locks, conditions of other mechanisms of interest, the agent will automatically capture them.

This is an example class file:

```java
private final Lock lock = new ReentrantLock();
private final Condition notFull = lock.newCondition();
private final Condition notEmpty = lock.newCondition();

void produce(int value) throws InterruptedException {
    lock.lock();
    try {
        while (buf.size() == CAPACITY) notFull.await();
        buf.add(value);
        notEmpty.signal();
    } finally {
        lock.unlock();
    }
}
```

The agent weaves `lock()`/`unlock()`/`await()`/`signal()`/`signalAll()` calls at
class-loading time. 

Lock and condition names come from the variable's name that holds them
(`lock`, `notFull`, `notEmpty` above). This helps you to correlate the diagram visualization with your own code.

### Marking your own events

If you want to add your custom notes (such as logs) to the diagram, use the `Tracer.note()` method.
The diagram will show events at the point in time they were recorded.

```java
Tracer.note("produced " + value);
```

### Viewing the diagram

Running the program, the agent produces a `trace.json` file in the working directory.

Open the hosted [visualizer](https://distributedum.github.io/java-concurrency-tracer/), and use **Open trace.json…**.

Toggle **Physical time / Lamport clock** to switch between actual wall-clock
duration and causal order;

### Running the bundled demo

```bash
./run-demo.sh                                          # demo/BoundedBufferRaw
./run-demo.sh demo/HandoffRaw.java HandoffRaw           # the run pictured above
./run-demo.sh demo/ReadersWritersRaw.java ReadersWritersRaw
```

## Known limitations
- Only `java.util.concurrent.locks.*` is instrumented — `synchronized`,
  `wait()`/`notify()`, `tryLock()`, and the timed `await` variants are not.
- `signal()` → `await()` pairing is **FIFO and approximate**: Java doesn't
  guarantee wakeup order, and spurious wakeups aren't modeled.
- Thread lifecycle tracing covers no-arg `start()`/`join()` only; `join(long)`
  isn't woven, and a thread that dies from an uncaught exception leaves its
  lane open-ended.
- Lock/condition names come from **fields** reliably; **local variables** need
  `-g` (most IDEs and build tools already compile with it) or they fall back to
  `Type@Class:line`.
- Recording is serialized through a single monitor, which introduces a small
  observation effect. This is suitable for seeing blocking patterns, not for
  micro-benchmarks.
- Reentrant acquisition of the same lock shows up as a new request/acquire
  pair, not as nesting.
