package pt.sd.trace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Centralized tracer for instrumenting concurrent executions with locks and
 * condition variables.
 *
 * Records events in a total order (via a global counter) and assigns each
 * event:
 *   - a per-thread logical Lamport clock;
 *   - a physical instant (nanos relative to startup), to position it visually
 *     and see the actual duration of blocking;
 *   - an optional causal edge (happens-before) between threads, used to draw
 *     the diagram's arrows and to merge the Lamport clock.
 *
 * Three families of causal edges are inferred:
 *   1. Lock HANDOFF: when a thread acquires a lock, it links to the most
 *      recent release event (RELEASE or AWAIT_BEGIN) of that lock, if it was
 *      done by another thread.
 *   2. SIGNAL -> WAKEUP: a signal()/signalAll() on a condition links to the
 *      return of the await() of the waiting thread(s) (FIFO pairing).
 *   3. Thread lifecycle: START -> BEGIN (a thread's first event links back to
 *      its creator's start() call) and END -> JOIN (a join() that returns
 *      links to the joined thread's last event). Unlike (2), these are exact
 *      happens-before edges, not an approximation.
 *
 * TEACHING NOTE: the signal->await pairing is an approximation (FIFO, and it
 * doesn't model spurious wakeups), but it matches the mental model students
 * use and is enough to visualize causality. It's documented as such.
 *
 * Recording is fully serialized (a synchronized block) to guarantee a
 * consistent global order and safe access to the state maps.
 */
public final class Tracer {

    /** A recorded event. Public fields to make serialization easier. */
    public static final class Event {
        public final long   seq;      // total recording order
        public final long   lamport;  // logical clock (per thread)
        public final long   tNanos;   // physical instant relative to startup
        public final String thread;   // thread name
        public final long   tid;      // thread id
        public final String kind;     // event type
        public final String lock;     // lock name (or null)
        public final String cond;     // condition name (or null)
        public final String detail;   // free text (or null)
        public final String mode;     // EXCLUSIVE | READ | WRITE (or null)
        public final long[] causes;   // seqs of the causing events (can be several!)
        public final Long   cause;    // first cause (compatibility; null if none)

        Event(long seq, long lamport, long tNanos, String thread, long tid,
              String kind, String lock, String cond, String detail, String mode, long[] causes) {
            this.seq = seq; this.lamport = lamport; this.tNanos = tNanos;
            this.thread = thread; this.tid = tid; this.kind = kind;
            this.lock = lock; this.cond = cond; this.detail = detail;
            this.mode = mode; this.causes = causes;
            this.cause = (causes.length == 0) ? null : causes[0];
        }
    }

    /** Lock ownership modes. */
    public static final String EXCLUSIVE = "EXCLUSIVE"; // ReentrantLock (or a write lock seen as exclusive)
    public static final String READ      = "READ";      // ReadWriteLock in shared mode
    public static final String WRITE     = "WRITE";     // ReadWriteLock in exclusive mode

    private static boolean shared(String mode) { return READ.equals(mode); }

    private static final Tracer INSTANCE = new Tracer();
    public static Tracer get() { return INSTANCE; }

    private final long t0 = System.nanoTime();
    private long nextSeq = 0L;

    private final List<Event> events = new ArrayList<>();
    private final Map<Long, Long> lamportByTid = new HashMap<>();
    // Last release event by an EXCLUSIVE holder (or writer) of that lock.
    // Used by READERS: their only dependency is the exit of the last writer.
    private final Map<String, Event> lastExclusiveReleaseByLock = new HashMap<>();

    // Current holders of each lock: tid -> depth (reentrancy).
    // With a read lock there can be SEVERAL at once — that's the whole point.
    private final Map<String, Map<Long, Integer>> holdersByLock = new HashMap<>();
    // Releases accumulated since the lock last became empty.
    private final Map<String, List<Event>> pendingReleasesByLock = new HashMap<>();
    // The COHORT: the group of releases that last emptied the lock.
    // An exclusive holder (writer) only enters once EVERYONE has left, so it
    // depends on ALL of these releases — not just the last one recorded.
    private final Map<String, List<Event>> lastCohortByLock = new HashMap<>();
    // FIFO queue of waiting threads, per condition (AWAIT_BEGIN event).
    private final Map<String, Deque<Event>> waitersByCond = new HashMap<>();
    // Pending signal that will wake up a thread: tid -> SIGNAL/SIGNAL_ALL event.
    private final Map<Long, Event> pendingWakeup = new HashMap<>();
    // Pending start() call, keyed by the CHILD's tid (known before start(), since
    // Thread.threadId() is assigned at construction). Consumed by the child's
    // first event (THREAD_BEGIN), which links back to it.
    private final Map<Long, Event> pendingStartByTid = new HashMap<>();
    // Last event recorded by each thread, so a join() that returns can link to
    // the joined thread's final event. One entry per thread, not per event.
    private final Map<Long, Event> lastEventByTid = new HashMap<>();

    private Tracer() {
        // Automatically dumps to trace.json at the end, for convenience.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { dump(Path.of("trace.json")); } catch (IOException ignored) {}
        }));
    }

    // ---- Internal API used by TracedLock / TracedCondition ------------------

    void lockRequest(String lock)   { lockRequest(lock, EXCLUSIVE); }
    void lockAcquired(String lock)  { lockAcquired(lock, EXCLUSIVE); }
    void lockReleased(String lock)  { lockReleased(lock, EXCLUSIVE); }
    void awaitBegin(String lock, String cond)  { awaitBegin(lock, cond, EXCLUSIVE); }
    void awaitWakeup(String lock, String cond) { awaitWakeup(lock, cond, EXCLUSIVE); }

    void lockRequest(String lock, String mode)   { emit("LOCK_REQUEST",  lock, null, null, mode); }
    void lockAcquired(String lock, String mode)  { emit("LOCK_ACQUIRED", lock, null, null, mode); }
    void lockReleased(String lock, String mode)  { emit("LOCK_RELEASED", lock, null, null, mode); }
    void awaitBegin(String lock, String cond, String mode)  { emit("AWAIT_BEGIN",  lock, cond, null, mode); }
    void awaitWakeup(String lock, String cond, String mode) { emit("AWAIT_WAKEUP", lock, cond, null, mode); }

    void signal(String cond)     { emit("SIGNAL",     null, cond, null, null); }
    void signalAll(String cond)  { emit("SIGNAL_ALL", null, cond, null, null); }

    // Thread lifecycle: injected by the agent around start()/join() and run().
    // The "targetTid" slot doubles as: the CHILD being started (THREAD_START),
    // or the thread being joined (JOIN_BEGIN / THREAD_JOIN).
    void threadStart(long childTid, String childName) { emit("THREAD_START", null, null, childName, null, childTid); }
    void threadBegin()                                { emit("THREAD_BEGIN", null, null, null, null, -1L); }
    void joinBegin(long targetTid)                    { emit("JOIN_BEGIN",   null, null, null, null, targetTid); }
    void threadJoin(long targetTid)                   { emit("THREAD_JOIN",  null, null, null, null, targetTid); }

    // ---- Public API for students ---------------------------------------------

    /** Marks an arbitrary application-level event (e.g. "produced 7"). */
    public static void note(String detail) { INSTANCE.emit("NOTE", null, null, detail, null); }

    /** Marks the end of a thread (optional; improves the diagram). Idempotent:
     *  a no-op if this thread's last recorded event is already THREAD_END (the
     *  agent path already emits this automatically at run() return). */
    public static void threadDone() {
        synchronized (INSTANCE) {
            long tid = Thread.currentThread().threadId();
            Event last = INSTANCE.lastEventByTid.get(tid);
            if (last != null && "THREAD_END".equals(last.kind)) return;
        }
        INSTANCE.emit("THREAD_END", null, null, null, null);
    }

    /** Clears the state (useful for running several scenarios in the same process). */
    public static synchronized void reset() {
        INSTANCE.events.clear();
        INSTANCE.lamportByTid.clear();
        INSTANCE.lastExclusiveReleaseByLock.clear();
        INSTANCE.holdersByLock.clear();
        INSTANCE.pendingReleasesByLock.clear();
        INSTANCE.lastCohortByLock.clear();
        INSTANCE.waitersByCond.clear();
        INSTANCE.pendingWakeup.clear();
        INSTANCE.pendingStartByTid.clear();
        INSTANCE.lastEventByTid.clear();
        INSTANCE.nextSeq = 0L;
    }

    // ---- Core -----------------------------------------------------------------

    private Event emit(String kind, String lock, String cond, String detail, String mode) {
        return emit(kind, lock, cond, detail, mode, -1L);
    }

    private synchronized Event emit(String kind, String lock, String cond, String detail, String mode, long targetTid) {
        Thread th = Thread.currentThread();
        long tid = th.threadId();
        String tname = th.getName();
        long tNanos = System.nanoTime() - t0;
        long seq = nextSeq++;

        // Determine the causal edges (there can be SEVERAL).
        List<Event> causes = new ArrayList<>();
        if ("LOCK_ACQUIRED".equals(kind)) {
            if (shared(mode)) {
                // READER: not blocked by other readers. Its only dependency is
                // the exit of the last WRITER (which "publishes" the value it will read).
                // Linking it to another reader would be inventing causality.
                Event rel = lastExclusiveReleaseByLock.get(lock);
                if (rel != null && rel.tid != tid) causes.add(rel);
            } else {
                // EXCLUSIVE (plain lock or WRITER): only enters once EVERYONE has left.
                // It therefore depends on ALL the releases of the cohort that emptied the
                // lock — with 3 readers, that's 3 edges, not one. Keeping just the last
                // one recorded would drop real edges and could violate the Lamport
                // condition (if a reader that left earlier had a higher clock).
                List<Event> cohort = lastCohortByLock.get(lock);
                if (cohort != null) {
                    for (Event r : cohort) if (r.tid != tid) causes.add(r);
                }
            }
        } else if ("AWAIT_WAKEUP".equals(kind)) {
            Event sig = pendingWakeup.remove(tid);
            if (sig != null) causes.add(sig);
        } else if ("THREAD_BEGIN".equals(kind)) {
            // Exact edge: this thread's first breath happens-after its creator's
            // start() call. Not an approximation, unlike signal->await.
            Event start = pendingStartByTid.remove(tid);
            if (start != null) causes.add(start);
        } else if ("THREAD_JOIN".equals(kind)) {
            // Exact edge: join() only returns after the target thread is done.
            Event last = lastEventByTid.get(targetTid);
            if (last != null && last.tid != tid) causes.add(last);
        }

        // Lamport clock: max over ALL causes.
        long local = lamportByTid.getOrDefault(tid, 0L);
        long base = local;
        for (Event c : causes) base = Math.max(base, c.lamport);
        long lamport = base + 1;
        lamportByTid.put(tid, lamport);

        long[] causeSeqs = new long[causes.size()];
        for (int i = 0; i < causeSeqs.length; i++) causeSeqs[i] = causes.get(i).seq;

        Event e = new Event(seq, lamport, tNanos, tname, tid, kind, lock, cond,
                            detail, mode, causeSeqs);

        // Downstream effects on shared state.
        switch (kind) {
            case "LOCK_ACQUIRED", "AWAIT_WAKEUP" -> {
                // Becomes a holder of the lock (the await returns by reacquiring it).
                holdersByLock.computeIfAbsent(lock, k -> new HashMap<>())
                             .merge(tid, 1, Integer::sum);
            }
            case "LOCK_RELEASED", "AWAIT_BEGIN" -> {
                if (!shared(mode)) lastExclusiveReleaseByLock.put(lock, e);

                Map<Long, Integer> holders = holdersByLock.computeIfAbsent(lock, k -> new HashMap<>());
                if ("AWAIT_BEGIN".equals(kind)) {
                    holders.remove(tid);              // the await fully releases the lock
                } else {
                    Integer d = holders.get(tid);     // unlock: goes down one reentrancy level
                    if (d == null || d <= 1) holders.remove(tid); else holders.put(tid, d - 1);
                }

                List<Event> pend = pendingReleasesByLock.computeIfAbsent(lock, k -> new ArrayList<>());
                pend.add(e);
                if (holders.isEmpty()) {
                    // The lock became EMPTY: this cohort is the one the next
                    // exclusive holder had to wait for in full.
                    lastCohortByLock.put(lock, pend);
                    pendingReleasesByLock.put(lock, new ArrayList<>());
                }
            }
            case "THREAD_START" -> pendingStartByTid.put(targetTid, e);
        }
        if ("AWAIT_BEGIN".equals(kind)) {
            waitersByCond.computeIfAbsent(cond, k -> new ArrayDeque<>()).addLast(e);
        } else if ("SIGNAL".equals(kind)) {
            Deque<Event> q = waitersByCond.get(cond);
            if (q != null) {
                Event w = q.pollFirst();
                if (w != null) pendingWakeup.put(w.tid, e);
            }
        } else if ("SIGNAL_ALL".equals(kind)) {
            Deque<Event> q = waitersByCond.get(cond);
            if (q != null) {
                Event w;
                while ((w = q.pollFirst()) != null) pendingWakeup.put(w.tid, e);
            }
        }

        events.add(e);
        lastEventByTid.put(tid, e);
        return e;
    }

    // ---- JSON serialization (no dependencies) --------------------------------

    /** Writes the trace as JSON to the given path. */
    public synchronized void dump(Path path) throws IOException {
        Files.writeString(path, toJson());
    }

    public synchronized String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"events\": [\n");
        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            sb.append("    {")
              .append("\"seq\":").append(e.seq)
              .append(",\"lamport\":").append(e.lamport)
              .append(",\"t\":").append(e.tNanos)
              .append(",\"thread\":").append(str(e.thread))
              .append(",\"tid\":").append(e.tid)
              .append(",\"kind\":").append(str(e.kind))
              .append(",\"lock\":").append(str(e.lock))
              .append(",\"cond\":").append(str(e.cond))
              .append(",\"detail\":").append(str(e.detail))
              .append(",\"mode\":").append(str(e.mode))
              .append(",\"cause\":").append(e.cause == null ? "null" : e.cause)
              .append(",\"causes\":").append(seqs(e.causes))
              .append("}");
            if (i < events.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    private static String seqs(long[] a) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) { if (i > 0) b.append(","); b.append(a[i]); }
        return b.append("]").toString();
    }

    private static String str(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default   -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append("\"").toString();
    }
}
