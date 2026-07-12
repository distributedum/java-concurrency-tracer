package pt.sd.trace;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An instrumented {@link Lock}. Students use it exactly like a ReentrantLock —
 * but each relevant operation is recorded in the {@link Tracer}.
 *
 *   Lock l = new TracedLock("bufferLock");
 *   Condition notEmpty = l.newCondition("notEmpty");
 */
public final class TracedLock implements Lock {

    private final ReentrantLock inner;
    private final String name;
    private final Tracer tracer = Tracer.get();

    public TracedLock(String name) { this(name, false); }

    public TracedLock(String name, boolean fair) {
        this.name = name;
        this.inner = new ReentrantLock(fair);
    }

    public String name() { return name; }

    @Override
    public void lock() {
        tracer.lockRequest(name);   // requested the lock (may block next)
        inner.lock();
        tracer.lockAcquired(name);  // got the lock
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        tracer.lockRequest(name);
        inner.lockInterruptibly();
        tracer.lockAcquired(name);
    }

    @Override
    public boolean tryLock() {
        boolean ok = inner.tryLock();
        if (ok) tracer.lockAcquired(name);
        return ok;
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        tracer.lockRequest(name);
        boolean ok = inner.tryLock(time, unit);
        if (ok) tracer.lockAcquired(name);
        return ok;
    }

    @Override
    public void unlock() {
        tracer.lockReleased(name);  // recorded BEFORE actually releasing
        inner.unlock();
    }

    @Override
    public Condition newCondition() {
        return newCondition("cond@" + name);
    }

    /** Creates an instrumented condition with a readable name. */
    public TracedCondition newCondition(String condName) {
        return new TracedCondition(inner.newCondition(), name, condName);
    }
}
