import pt.sd.trace.Tracer;
import pt.sd.trace.TracedCondition;
import pt.sd.trace.TracedLock;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Producer/consumer with a bounded buffer, using the instrumented Lock and
 * Condition. The goal is to generate a rich trace: blocking while waiting for
 * the lock, waiting on a condition variable, and notifications between threads.
 *
 * The ONLY difference from a "normal" distributed-systems program is using
 * TracedLock instead of ReentrantLock. Everything else is identical to what
 * students would write.
 */
public class BoundedBufferDemo {

    static final int CAPACITY = 1;

    // Shared state protected by the lock.
    static final TracedLock lock = new TracedLock("bufferLock");
    static final TracedCondition notFull = lock.newCondition("notFull");
    static final TracedCondition notEmpty = lock.newCondition("notEmpty");
    static final Queue<Integer> buffer = new ArrayDeque<>();

    static void produce(int value) throws InterruptedException {
        lock.lock();
        try {
            while (buffer.size() == CAPACITY) {
                notFull.await();                // buffer full: wait
            }
            buffer.add(value);
            Tracer.note("produced " + value + " (buffer=" + buffer.size() + ")");
            notEmpty.signal();                  // wake a consumer
        } finally {
            lock.unlock();
        }
    }

    static Integer consume() throws InterruptedException {
        lock.lock();
        try {
            while (buffer.isEmpty()) {
                notEmpty.await();               // buffer empty: wait
            }
            int v = buffer.poll();
            Tracer.note("consumed " + v + " (buffer=" + buffer.size() + ")");
            notFull.signal();                   // wake a producer
            return v;
        } finally {
            lock.unlock();
        }
    }

    static Thread producer(String name, int base, int count) {
        return new Thread(() -> {
            try {
                for (int i = 0; i < count; i++) {
                    produce(base + i);
                    Thread.sleep(20);           // work outside the critical section
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                Tracer.threadDone();
            }
        }, name);
    }

    static Thread consumer(String name, int count) {
        return new Thread(() -> {
            try {
                for (int i = 0; i < count; i++) {
                    consume();
                    Thread.sleep(60);           // consumes slower -> buffer fills up
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                Tracer.threadDone();
            }
        }, name);
    }

    public static void main(String[] args) throws Exception {
        Thread p1 = producer("P1", 100, 3);
        Thread p2 = producer("P2", 200, 3);
        Thread c1 = consumer("C1", 3);
        Thread c2 = consumer("C2", 3);

        p1.start();
        Thread.sleep(10);
        c1.start();
        Thread.sleep(5);
        p2.start();
        c2.start();

        p1.join(); p2.join(); c1.join(); c2.join();

        Tracer.get().dump(java.nio.file.Path.of("trace.json"));
        System.out.println("Trace written to trace.json.");
    }
}
