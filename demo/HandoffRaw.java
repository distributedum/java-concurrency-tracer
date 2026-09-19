import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import pt.sd.trace.Tracer;

/**
 * The smallest possible causal chain: one thread waits on a condition, another
 * sets the flag and signals it, and main joins both. Written with the standard
 * lock API — no {@code TracedLock}, no names passed by hand; the agent infers
 * {@code lock} and {@code ready} from the field names.
 *
 * {@code Signaler} sleeps briefly before signalling so {@code Waiter} is
 * provably parked inside {@code await()} first — this is what makes the
 * resulting diagram reproducible instead of scheduler-dependent.
 *
 * Both roles are real {@code Thread} subclasses (not lambdas), so their
 * {@code run()} gets woven by the agent and {@code THREAD_END} is emitted
 * automatically for each one.
 *
 *   javac -g -d out demo/HandoffRaw.java
 *   java -javaagent:sdtrace-agent.jar -cp out HandoffRaw
 */
public class HandoffRaw {

    static final Lock lock = new ReentrantLock();
    static final Condition ready = lock.newCondition();
    static boolean go = false;

    static class Waiter extends Thread {
        Waiter() { super("Waiter"); }

        @Override
        public void run() {
            lock.lock();
            try {
                while (!go) ready.await();
                Tracer.note("go!");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }
    }

    static class Signaler extends Thread {
        Signaler() { super("Signaler"); }

        @Override
        public void run() {
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            lock.lock();
            try {
                go = true;
                Tracer.note("ready");
                ready.signal();
            } finally {
                lock.unlock();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Waiter waiter = new Waiter();
        Signaler signaler = new Signaler();

        waiter.start();
        signaler.start();

        waiter.join();
        signaler.join();

        Tracer.get().dump(java.nio.file.Path.of("trace.json"));
        System.out.println("Trace written to trace.json.");
    }
}
