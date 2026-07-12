import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import pt.sd.trace.Tracer;

/**
 * Readers/writers — AGENT PATH. Uses ReentrantReadWriteLock directly: no
 * custom type, no name passed by hand.
 *
 *   javac -g -d out demo/ReadersWritersRaw.java
 *   java -javaagent:sdtrace-agent.jar -cp out ReadersWritersRaw
 *
 * A "fair" lock (fair=true) on purpose: a waiting writer blocks new readers
 * from cutting in line, which avoids writer starvation and keeps the diagram
 * readable and reproducible.
 */
public class ReadersWritersRaw {

    private final ReadWriteLock rw = new ReentrantReadWriteLock(true);
    private int value = 0;

    void write(int v) {
        rw.writeLock().lock();                 // EXCLUSIVE ownership
        try {
            value = v;
            Tracer.note("wrote " + v);
            sleepMs(40);
        } finally {
            rw.writeLock().unlock();
        }
    }

    int read() {
        rw.readLock().lock();                  // SHARED ownership
        try {
            Tracer.note("read " + value);
            sleepMs(50);
            return value;
        } finally {
            rw.readLock().unlock();
        }
    }

    static void sleepMs(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    public static void main(String[] args) throws Exception {
        ReadersWritersRaw d = new ReadersWritersRaw();

        Thread w1 = new Thread(() -> { d.write(42); Tracer.threadDone(); }, "W1");
        Thread r1 = new Thread(() -> { d.read(); Tracer.threadDone(); }, "R1");
        Thread r2 = new Thread(() -> { d.read(); Tracer.threadDone(); }, "R2");
        Thread r3 = new Thread(() -> { d.read(); Tracer.threadDone(); }, "R3");
        Thread w2 = new Thread(() -> { d.write(99); Tracer.threadDone(); }, "W2");

        w1.start();                 // grabs the write lock
        Thread.sleep(10);
        r1.start(); r2.start(); r3.start();   // block; then all enter together
        Thread.sleep(20);
        w2.start();                 // waits for the three readers to leave

        w1.join(); r1.join(); r2.join(); r3.join(); w2.join();

        Tracer.get().dump(java.nio.file.Path.of("trace.json"));
        System.out.println("Trace written to trace.json.");
    }
}
