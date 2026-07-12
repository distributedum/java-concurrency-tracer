package pt.sd.trace;

import java.util.Date;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

/**
 * Uma {@link Condition} instrumentada. await() regista a entrada em espera
 * (que liberta o lock) e o despertar (que readquire o lock); signal()/signalAll()
 * registam a notificação.
 */
public final class TracedCondition implements Condition {

    private final Condition inner;
    private final String lockName;
    private final String condName;
    private final Tracer tracer = Tracer.get();

    private final String mode;   // EXCLUSIVE (lock normal) ou WRITE (write lock de um RW)

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
        tracer.awaitBegin(lockName, condName, mode);   // liberta o lock e adormece
        try {
            inner.await();
        } finally {
            tracer.awaitWakeup(lockName, condName, mode); // acordou e readquiriu o lock
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
