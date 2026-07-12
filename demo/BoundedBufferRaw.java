import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import pt.sd.trace.Tracer;

/**
 * Producer/consumer with a bounded buffer, written with the standard lock API
 * — NO TracedLock, NO names passed by hand. The only "tracing" thing here is
 * the optional {@link Tracer#note} call (which students could even omit).
 *
 * All the lock/await/signal instrumentation is injected by the agent at class
 * load time. Run with:
 *
 *   javac -g -d out demo/BoundedBufferRaw.java
 *   java -javaagent:sdtrace-agent.jar -cp out BoundedBufferRaw
 */
public class BoundedBufferRaw {

    static final int CAPACITY = 1;

    private final Queue<Integer> buf = new ArrayDeque<>();
    private final Lock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    void produce(int value) throws InterruptedException {
        lock.lock();
        try {
            while (buf.size() == CAPACITY) notFull.await();
            buf.add(value);
            Tracer.note("produced " + value);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    int consume() throws InterruptedException {
        lock.lock();
        try {
            while (buf.isEmpty()) notEmpty.await();
            int v = buf.poll();
            Tracer.note("consumed " + v);
            notFull.signal();
            return v;
        } finally {
            lock.unlock();
        }
    }

    public static void main(String[] args) throws Exception {
        BoundedBufferRaw b = new BoundedBufferRaw();

        Runnable producer = () -> {
            int base = Thread.currentThread().getName().equals("P1") ? 10 : 20;
            for (int i = 0; i < 3; i++) {
                try { b.produce(base + i); Thread.sleep(20); }
                catch (InterruptedException e) { return; }
            }
            Tracer.threadDone();
        };
        Runnable consumer = () -> {
            for (int i = 0; i < 3; i++) {
                try { b.consume(); Thread.sleep(60); }
                catch (InterruptedException e) { return; }
            }
            Tracer.threadDone();
        };

        Thread p1 = new Thread(producer, "P1");
        Thread p2 = new Thread(producer, "P2");
        Thread c1 = new Thread(consumer, "C1");
        Thread c2 = new Thread(consumer, "C2");

        c1.start(); c2.start(); p1.start(); p2.start();
        p1.join(); p2.join(); c1.join(); c2.join();

        Tracer.get().dump(java.nio.file.Path.of("trace.json"));
        System.out.println("Trace written to trace.json.");
    }
}
