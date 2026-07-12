package pt.sd.trace;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Um {@link ReadWriteLock} instrumentado — o equivalente do {@link TracedLock}
 * para o problema dos leitores/escritores.
 *
 * <pre>{@code
 *   TracedReadWriteLock rw = new TracedReadWriteLock("dados");
 *   rw.readLock().lock();    // posse PARTILHADA  (varios leitores ao mesmo tempo)
 *   rw.writeLock().lock();   // posse EXCLUSIVA   (exclui toda a gente)
 * }</pre>
 *
 * As duas vistas partilham o <b>mesmo nome</b> ({@code dados}) e distinguem-se
 * pelo <b>modo</b> ({@code READ} / {@code WRITE}). E isso que permite ao
 * {@link Tracer} perceber que sao o mesmo lock e desenhar a exclusao entre
 * leitores e escritores — se fossem dois nomes diferentes, nao haveria uma unica
 * seta a explicar por que motivo o escritor ficou bloqueado.
 *
 * <p>Nota: so o <em>write lock</em> suporta variaveis de condicao. Chamar
 * {@code readLock().newCondition()} lanca {@link UnsupportedOperationException}
 * — e assim no Java e mantemos o mesmo comportamento.
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

    /** Uma das duas vistas (leitura ou escrita) do mesmo lock. */
    public static final class View implements Lock {
        private final Lock inner;
        private final String lockName;
        private final String mode;      // Tracer.READ ou Tracer.WRITE
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
            tracer.lockReleased(lockName, mode);   // regista ANTES de libertar
            inner.unlock();
        }

        /** So valido no write lock (o read lock lanca UnsupportedOperationException). */
        @Override
        public Condition newCondition() { return newCondition("cond@" + lockName); }

        /** Condicao instrumentada com nome legivel. So valida no write lock. */
        public TracedCondition newCondition(String condName) {
            return new TracedCondition(inner.newCondition(), lockName, condName, mode);
        }
    }
}
