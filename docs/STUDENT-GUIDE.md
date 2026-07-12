# Space-time diagram of concurrent executions — Usage guide

This tool lets you instrument Java programs that use *locks* and condition
variables, and get a **visual representation** of the execution: the state of each
*thread* over time, blocking on lock acquisition, waits on conditions, notifications
between *threads*, and causality relations (*happens-before*), annotated with
**Lamport clocks**.

There are two instrumentation paths. **One** must be chosen.

| | **Path A — Wrappers** | **Path B — Agent** |
|---|---|---|
| Code changes | replace `ReentrantLock` with `TracedLock` | **none** |
| Minimum JDK version | **21** | **24** |
| Lock names | defined explicitly | inferred automatically |
| Mechanism | library | `-javaagent` option |

On JDK 21, use Path A. On JDK 24 or higher, either path is valid; Path B has the
advantage of not requiring any code changes.

---

## 1. Package contents

```
sdtrace-agent.jar      Single file to add to the project.
                       Serves simultaneously as a library (Path A) and an agent (Path B).
viz/spacetime.html     Visualizer. Opens in the browser and works offline.
src/pt/sd/trace/       Tool source code.
demo/                  Four complete examples (two per path).
```

Before starting, confirm the installed JDK version:

```bash
java -version
```

---

## 2. Path A — Wrappers (JDK 21 or higher)

### 2.1. IntelliJ setup

**Add the library.** Copy `sdtrace-agent.jar` into the project (e.g. into a `lib/`
folder). Then right-click the file → **Add as Library…**

Alternatively: *File → Project Structure → Libraries → **+** → Java*, and select
the JAR.

### 2.2. Code changes

Only the creation of the *lock* and conditions changes:

```java
import pt.sd.trace.*;

public class Buffer {
    // Before:  private final Lock lock = new ReentrantLock();
    private final TracedLock lock = new TracedLock("bufferLock");

    // Before:  private final Condition notEmpty = lock.newCondition();
    private final TracedCondition notEmpty = lock.newCondition("notEmpty");
    private final TracedCondition notFull = lock.newCondition("notFull");

    void produce(int v) throws InterruptedException {
        lock.lock();                       // the rest of the code stays unchanged
        try {
            while (full()) notFull.await();
            // ...
            Tracer.note("produced " + v);  // optional: application mark on the diagram
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }
}
```

`TracedLock` implements `java.util.concurrent.locks.Lock` and `TracedCondition`
implements `Condition`, so the rest of the code stays unchanged.

For read/write *locks*:

```java
TracedReadWriteLock rw = new TracedReadWriteLock("data");
rw.readLock().lock();      // SHARED ownership (several readers at once)
rw.writeLock().lock();     // EXCLUSIVE ownership
```

### 2.3. Running

In IntelliJ, run normally (▶). No additional configuration is needed. At the end
of the run, the `trace.json` file is generated in the working directory.

### 2.4. Command line

```bash
javac -cp sdtrace-agent.jar -d out src/Buffer.java src/Main.java
java  -cp sdtrace-agent.jar:out Main          # Linux and macOS
java  -cp "sdtrace-agent.jar;out" Main        # Windows
```

On Windows, the *classpath* separator is `;`, not `:`.

---

## 3. Path B — Agent (JDK 24 or higher)

The code is written with the standard Java API. Instrumentation is injected by the
agent at class-loading time.

```java
import java.util.concurrent.locks.*;

public class Buffer {
    private final Lock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    // rest of the code unchanged
}
```

### 3.1. IntelliJ setup

**Step 1 — set the project SDK.**
*File → Project Structure → Project → SDK*: select a JDK **24 or higher**.
If none is available, use *Add SDK → Download JDK…*

**Step 2 — add the library.**
Right-click `sdtrace-agent.jar` → **Add as Library…** (needed to use
`Tracer.note(...)` and to reference the agent).

**Step 3 — activate the agent.**

1. *Run → Edit Configurations…*
2. Select the configuration containing the `main` method.
3. **Modify options → Add VM options**.
4. In the **VM options** field, enter:

```
-javaagent:lib/sdtrace-agent.jar=include=pt.ua.sd.assignment1
```

The value of `include=` should match the package of your assignment's classes
(see section 4).

If the path contains spaces, wrap the option in quotes:

```
"-javaagent:C:\My Projects\lib\sdtrace-agent.jar=include=pt.ua.sd.assignment1"
```

**Step 4 — run.** The console should show:

```
[sdtrace] agent active | include=pt/ua/sd/assignment1
```

The absence of this line means the agent wasn't loaded; in that case, review
Step 3.

### 3.2. Command line

```bash
javac -g -cp sdtrace-agent.jar -d out src/*.java
java -javaagent:sdtrace-agent.jar=include=pt.ua.sd.assignment1 -cp sdtrace-agent.jar:out Main
```

On Windows:

```
java -javaagent:sdtrace-agent.jar=include=pt.ua.sd.assignment1 -cp "sdtrace-agent.jar;out" Main
```

The `-g` option only matters when *locks* are held in **local variables** (see
section 6). IntelliJ compiles with `-g` by default.

---

## 4. Restricting instrumentation to your assignment's classes

The agent ignores all JDK classes by default (`java.*`, `jdk.*`, `sun.*`,
`javax.*`). However, external libraries present on the *classpath* that use
*locks* internally (caches, connection pools, testing frameworks) would be
instrumented and introduce noise into the diagram.

The **`include=`** option restricts instrumentation to the given classes. The
comparison is done by **prefix** of the qualified name.

### Examples

Classes in one package:

```
-javaagent:sdtrace-agent.jar=include=pt.ua.sd.assignment1
```

Several packages (comma-separated, no spaces):

```
-javaagent:sdtrace-agent.jar=include=pt.ua.sd.buffer,pt.ua.sd.philosophers
```

A package and all its sub-packages — a result of the prefix comparison:
`include=pt.ua.sd` covers `pt.ua.sd.buffer`, `pt.ua.sd.readers`, etc.

Classes without a `package` declaration (default package) — give the class names:

```
-javaagent:sdtrace-agent.jar=include=Buffer,Main
```

Instrument everything except one specific library:

```
-javaagent:sdtrace-agent.jar=exclude=com.acme.cache
```

List the classes actually instrumented (useful when no events are being
recorded) — add `verbose`, separating options with `;`:

```bash
java "-javaagent:sdtrace-agent.jar=include=pt.ua.sd;verbose" -cp out Main
```

Result:

```
[sdtrace] agent active | include=pt/ua/sd | verbose
[sdtrace] instrumented: pt.ua.sd.assignment1.Buffer
```

**Important note.** The `;` character is interpreted as a command separator by
*shells* (bash, zsh and PowerShell). When using `verbose` on the command line,
the `-javaagent` option must be **wrapped in quotes**, as in the example above.
In IntelliJ's *VM options* field, quotes aren't necessary.

### Effect of the restriction

Considering a library `com.libx.Cache` that internally uses a `ReentrantLock`:

| Configuration | *Locks* recorded |
|---|---|
| `-javaagent:sdtrace-agent.jar` | `myLock` and `lk` (the library's — noise) |
| `-javaagent:sdtrace-agent.jar=include=pt.ua.sd.t1` | only `myLock` |
| `-javaagent:sdtrace-agent.jar=exclude=com.libx` | only `myLock` |

---

## 5. Viewing the diagram

1. Run the program. The **`trace.json`** file is generated in the working
   directory. In IntelliJ, that's the project root; the exact value is found in
   *Run → Edit Configurations… → Working directory*.
2. Open **`viz/spacetime.html`** in the browser (works offline).
3. Select **"Open trace.json…"** and pick the generated file.

Reading the diagram:

- Each **column** corresponds to a *thread*; **time flows top to bottom**.
- **Bands**: amber — blocked waiting for the *lock*; solid turquoise — critical
  section (exclusive ownership); hatched turquoise — read (shared ownership,
  several *threads* can show it at the same time); violet — waiting on a
  condition variable.
- **Arrows**: dashed gray — *lock* handoff between *threads*; pink —
  `signal`/`signalAll` followed by the return from `await`.
- **Clicking** an event pins the details panel, with the Lamport clock
  calculation and its causal past. Clicking outside the event, or pressing
  `Esc`, closes the panel.
- The **Axis: Physical time / Lamport clock** selector switches between the
  actual duration of blocking and the causal order.
- **Zoom** on the time axis: `Ctrl` (or `⌘`) with the mouse wheel, touchpad
  pinch, or the `+` and `−` keys; the `0` key resets to the initial value. Zoom
  keeps the point under the cursor fixed, which lets you magnify a contention
  spot without losing sight of it. When there's no cursor over the diagram
  (keyboard use, or the slider), the anchor becomes the top of the visible area.

---

## 6. Inference of *lock* names (Path B)

In Path A, names are defined explicitly. In Path B, the agent infers them in the
following order of precedence:

1. **Field** — `private final Lock lock = new ReentrantLock();` produces the
   name `lock`. Always works, with no extra compilation options. This is the
   usual case, where the *lock* is a field of the shared object.
2. **Local variable** — `Lock myLock = new ReentrantLock();` produces the name
   `myLock`, provided compilation includes `-g` (IntelliJ does this by default;
   on the command line it must be given explicitly).
3. **Final fallback** — `ReentrantLock@Buffer:27` (type, class, and line
   number). No event is left unidentified.

In a `ReadWriteLock`, the read and write views share the field's name and are
distinguished by their **mode**, which lets the reader/writer exclusion be
represented.

---

## 7. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `UnsupportedClassVersionError` when starting with `-javaagent` | JDK earlier than version 24 | Use Path A or install a JDK 24+ |
| The `[sdtrace] agent active` message isn't shown | The `-javaagent` option wasn't passed to the JVM | IntelliJ: *Modify options → Add VM options* (don't confuse with *Program arguments*) |
| Diagram with no lock events | The `include=` value doesn't match your classes' package | Run with `;verbose` (in quotes) and check the instrumented classes |
| Locks not belonging to your assignment are recorded | Classpath libraries use *locks* | Set `include=` to your assignment's package |
| *Locks* show up as `ReentrantLock@Class:12` | *Locks* in local variables, compiled without `-g` | Compile with `javac -g`, or declare the *lock* as a field |
| The `trace.json` file isn't found | It was generated in a different folder | Check the *Working directory* of the run configuration |
| `UnsupportedOperationException` on `readLock().newCondition()` | Only the *write lock* supports conditions | Use `writeLock().newCondition()` |
| The command is interrupted when using `verbose` | `;` is a command separator in the *shell* | Wrap the `-javaagent` option in quotes |

---

## 8. Instrumentation scope

Not instrumented:

- `synchronized`, `wait()` and `notify()`, since they don't use the *lock*
  classes from `java.util.concurrent.locks`. Assignments that need diagram
  representation should use explicit *locks*.
- `tryLock()` and the timed *await* variants (`awaitNanos`, `await(t, u)`):
  they're recorded in Path A, but not in Path B.

Recording events introduces additional synchronization and a slight observation
effect. The physical time axis is suitable for analyzing blocking, but not for
performance measurements.
