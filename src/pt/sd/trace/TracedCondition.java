package pt.sd.trace;

import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

/**
 * An instrumented {@link Condition}. await() records entering the wait (which
 * releases the lock) and waking up (which reacquires the lock); signal()/signalAll()
 * record the notification.
 */
public final class TracedCondition implements Condition {

    private final Condition inner;
    private final String lockName;
    private final String condName;
    private final Tracer tracer = Tracer.get();

    private final String mode;   // EXCLUSIVE (plain lock) or WRITE (write lock of an RW lock)

    TracedCondition(Condition inner, String lockName, String condName) {
        this(inner, lockName, condName, Tracer.EXCLUSIVE);
    }

    TracedCondition(Condition inner, String lockName, String condName, String mode) {
        this.inner = inner;
        this.lockName = lockName;
        this.condName = condName;
        this.mode = mode;
    }

    public String name() { return condName; }

    @Override
    public void await() throws InterruptedException {
        tracer.awaitBegin(lockName, condName, mode);   // releases the lock and sleeps
        try {
            inner.await();
        } finally {
            tracer.awaitWakeup(lockName, condName, mode); // woke up and reacquired the lock
        }
    }

    @Override
    public void awaitUninterruptibly() {
        tracer.awaitBegin(lockName, condName, mode);
        try {
            inner.awaitUninterruptibly();
        } finally {
            tracer.awaitWakeup(lockName, condName, mode);
        }
    }

    @Override
    public long awaitNanos(long nanosTimeout) throws InterruptedException {
        tracer.awaitBegin(lockName, condName, mode);
        try {
            return inner.awaitNanos(nanosTimeout);
        } finally {
            tracer.awaitWakeup(lockName, condName, mode);
        }
    }

    @Override
    public boolean await(long time, TimeUnit unit) throws InterruptedException {
        tracer.awaitBegin(lockName, condName, mode);
        try {
            return inner.await(time, unit);
        } finally {
            tracer.awaitWakeup(lockName, condName, mode);
        }
    }

    @Override
    public boolean awaitUntil(Date deadline) throws InterruptedException {
        tracer.awaitBegin(lockName, condName, mode);
        try {
            return inner.awaitUntil(deadline);
        } finally {
            tracer.awaitWakeup(lockName, condName, mode);
        }
    }

    @Override
    public void signal() {
        tracer.signal(condName);
        inner.signal();
    }

    @Override
    public void signalAll() {
        tracer.signalAll(condName);
        inner.signalAll();
    }
}
