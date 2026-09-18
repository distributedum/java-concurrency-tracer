package pt.sd.trace;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Entry points invoked by the <em>bytecode</em> injected by the agent
 * ({@code pt.sd.trace.agent}). Students use {@code ReentrantLock},
 * {@code ReentrantReadWriteLock} and {@code Condition} directly and don't pass
 * names: the agent injects, at the creation site, a call that associates each
 * object (by identity) with the name of the variable or field that holds it.
 *
 * <p><b>Read/write locks.</b> {@code rw.readLock()} and {@code rw.writeLock()}
 * return two distinct objects, which naively would appear as two unrelated
 * locks — and the reader/writer exclusion would be invisible. So we register
 * each view as a <em>view of</em> a parent lock: both inherit the parent's name
 * (e.g. {@code rw}) and are distinguished by their <b>mode</b> ({@code READ} or
 * {@code WRITE}). This way the {@link Tracer} sees a single lock with two
 * ownership modes — which is exactly what it is.
 */
public final class Hooks {

    private Hooks() {}

    /** Object (lock or condition) -> readable name. */
    private static final Map<Object, String> NAME =
            Collections.synchronizedMap(new IdentityHashMap<>());
    /** Condition -> lock that created it (to link the await to the lock in the diagram). */
    private static final Map<Object, Object> OWNER =
            Collections.synchronizedMap(new IdentityHashMap<>());
    /** View (read/write lock) -> ReadWriteLock that produced it. */
    private static final Map<Object, Object> PARENT =
            Collections.synchronizedMap(new IdentityHashMap<>());
    /** View -> ownership mode (READ or WRITE). */
    private static final Map<Object, String> MODE =
            Collections.synchronizedMap(new IdentityHashMap<>());

    // ---- Registration (injected at creation sites) ---------------------------

    /** Associates a name with a lock or condition. The first name wins. */
    public static void nameThing(Object o, String name) {
        if (o != null && name != null) NAME.putIfAbsent(o, name);
    }

    /**
     * Registers that {@code cond} was created by {@code lock} and returns {@code cond}.
     * Injected right after {@code lock.newCondition()}.
     */
    public static Object linkOwner(Object lock, Object cond) {
        if (cond != null && lock != null) OWNER.putIfAbsent(cond, lock);
        return cond;
    }

    /**
     * Registers that {@code view} is the read/write view of {@code parent} and
     * returns {@code view}. Injected right after {@code rw.readLock()} /
     * {@code rw.writeLock()}.
     */
    public static Object linkView(Object parent, Object view, String mode) {
        if (view != null && parent != null) {
            PARENT.putIfAbsent(view, parent);
            MODE.putIfAbsent(view, mode);
        }
        return view;
    }

    // ---- Resolution -----------------------------------------------------------

    /** Name of a lock. A read/write view inherits the name of its parent ReadWriteLock. */
    private static String nameOf(Object o) {
        if (o == null) return "?";
        Object parent = PARENT.get(o);
        if (parent != null) return nameOf(parent);   // view -> parent's name
        String n = NAME.get(o);
        return (n != null) ? n
                : o.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(o));
    }

    /** Mode: READ/WRITE on the views of an RW lock, EXCLUSIVE on everything else. */
    private static String modeOf(Object lock) {
        String m = (lock == null) ? null : MODE.get(lock);
        return (m != null) ? m : Tracer.EXCLUSIVE;
    }

    private static Object ownerLock(Object cond) { return (cond == null) ? null : OWNER.get(cond); }

    // ---- Events (injected at call sites) -------------------------------------

    public static void onLockRequest(Object l)  { Tracer.get().lockRequest(nameOf(l),  modeOf(l)); }
    public static void onLockAcquired(Object l) { Tracer.get().lockAcquired(nameOf(l), modeOf(l)); }
    public static void onLockReleased(Object l) { Tracer.get().lockReleased(nameOf(l), modeOf(l)); }

    public static void onAwaitBegin(Object c) {
        Object l = ownerLock(c);
        Tracer.get().awaitBegin(nameOf(l), nameOf(c), modeOf(l));
    }
    public static void onAwaitWakeup(Object c) {
        Object l = ownerLock(c);
        Tracer.get().awaitWakeup(nameOf(l), nameOf(c), modeOf(l));
    }

    public static void onSignal(Object c)    { Tracer.get().signal(nameOf(c)); }
    public static void onSignalAll(Object c) { Tracer.get().signalAll(nameOf(c)); }

    // ---- Thread lifecycle (start/join/run) -------------------------------------
    //
    // The weaver matches start()V/join()V by NAME ALONE (it has no class
    // hierarchy info at transform time — a student's `class Worker extends
    // Thread` compiles `w.start()` to invokevirtual Worker.start, not
    // Thread.start). Every entry point below re-checks `instanceof Thread` and
    // no-ops otherwise, so an unrelated method that happens to be called
    // start()/join() on some other type costs one no-op call and nothing else.

    public static void onThreadStart(Object t) {
        if (t instanceof Thread th) Tracer.get().threadStart(th.threadId(), th.getName());
    }

    public static void onJoinBegin(Object t) {
        if (t instanceof Thread th) Tracer.get().joinBegin(th.threadId());
    }

    public static void onJoinEnd(Object t) {
        if (t instanceof Thread th) Tracer.get().threadJoin(th.threadId());
    }

    /** Injected at the entry of a woven run(). */
    public static void onRunBegin() { Tracer.get().threadBegin(); }

    /** Injected before every normal return of a woven run(). */
    public static void onRunEnd() { Tracer.threadDone(); }
}
