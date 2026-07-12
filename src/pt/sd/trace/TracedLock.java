package pt.sd.trace;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Um {@link Lock} instrumentado. Os alunos usam-no exactamente como um
 * ReentrantLock — mas cada operação relevante é registada no {@link Tracer}.
 *
 *   Lock l = new TracedLock("bufferLock");
 *   Condition naoVazio = l.newCondition("naoVazio");
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
        tracer.lockRequest(name);   // pediu o lock (pode bloquear a seguir)
        inner.lock();
        tracer.lockAcquired(name);  // obteve o lock
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
        tracer.lockReleased(name);  // regista ANTES de libertar de facto
        inner.unlock();
    }

    @Override
    public Condition newCondition() {
        return newCondition("cond@" + name);
    }

    /** Cria uma condição instrumentada com um nome legível. */
    public TracedCondition newCondition(String condName) {
        return new TracedCondition(inner.newCondition(), name, condName);
    }
}
