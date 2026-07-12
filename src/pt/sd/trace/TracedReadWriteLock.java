package pt.sd.trace;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * An instrumented {@link ReadWriteLock} — the {@link TracedLock} equivalent
 * for the readers/writers problem.
 *
 * <pre>{@code
 *   TracedReadWriteLock rw = new TracedReadWriteLock("data");
 *   rw.readLock().lock();    // SHARED ownership     (several readers at once)
 *   rw.writeLock().lock();   // EXCLUSIVE ownership   (excludes everyone else)
 * }</pre>
 *
 * The two views share the <b>same name</b> ({@code data}) and are distinguished
 * by their <b>mode</b> ({@code READ} / {@code WRITE}). That's what lets the
 * {@link Tracer} realize they're the same lock and draw the exclusion between
 * readers and writers — if they had two different names, there would be no
 * single arrow explaining why the writer was blocked.
 *
 * <p>Note: only the <em>write lock</em> supports condition variables. Calling
 * {@code readLock().newCondition()} throws {@link UnsupportedOperationException}
 * — that's how it is in Java, and we keep the same behavior.
 */
public final class TracedReadWriteLock implements ReadWriteLock {

    private final String name;
    private final ReentrantReadWriteLock inner;
    private final View read;
    private final View write;

    public TracedReadWriteLock(String name) { this(name, false); }

    public TracedReadWriteLock(String name, boolean fair) {
        this.name  = name;
        this.inner = new ReentrantReadWriteLock(fair);
        this.read  = new View(inner.readLock(),  name, Tracer.READ);
        this.write = new View(inner.writeLock(), name, Tracer.WRITE);
    }

    public String name() { return name; }

    @Override public View readLock()  { return read; }
    @Override public View writeLock() { return write; }

    /** One of the two views (read or write) of the same lock. */
    public static final class View implements Lock {
        private final Lock inner;
        private final String lockName;
        private final String mode;      // Tracer.READ or Tracer.WRITE
        private final Tracer tracer = Tracer.get();

        View(Lock inner, String lockName, String mode) {
            this.inner = inner; this.lockName = lockName; this.mode = mode;
        }

        public String mode() { return mode; }

        @Override
        public void lock() {
            tracer.lockRequest(lockName, mode);
            inner.lock();
            tracer.lockAcquired(lockName, mode);
        }

        @Override
        public void lockInterruptibly() throws InterruptedException {
            tracer.lockRequest(lockName, mode);
            inner.lockInterruptibly();
            tracer.lockAcquired(lockName, mode);
        }

        @Override
        public boolean tryLock() {
            boolean ok = inner.tryLock();
            if (ok) tracer.lockAcquired(lockName, mode);
            return ok;
        }

        @Override
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            tracer.lockRequest(lockName, mode);
            boolean ok = inner.tryLock(time, unit);
            if (ok) tracer.lockAcquired(lockName, mode);
            return ok;
        }

        @Override
        public void unlock() {
            tracer.lockReleased(lockName, mode);   // recorded BEFORE releasing
            inner.unlock();
        }

        /** Only valid on the write lock (the read lock throws UnsupportedOperationException). */
        @Override
        public Condition newCondition() { return newCondition("cond@" + lockName); }

        /** Instrumented condition with a readable name. Only valid on the write lock. */
        public TracedCondition newCondition(String condName) {
            return new TracedCondition(inner.newCondition(), lockName, condName, mode);
        }
    }
}
