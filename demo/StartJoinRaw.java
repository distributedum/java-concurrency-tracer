import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import pt.sd.trace.Tracer;

/**
 * Demonstrates the thread-lifecycle edges (start -> begin, end -> join):
 * main spawns several workers that contend on a plain lock, sits visibly
 * blocked in join() while they run, and only afterwards prints a total.
 * Thanks to the exact happens-before edges on start()/join(), the closing
 * note's Lamport clock lands strictly after every worker's last event —
 * unlike a note printed with no ordering guarantee at all.
 *
 * Workers are real {@code Thread} subclasses (not lambdas passed to
 * {@code new Thread(...)}), so their {@code run()} gets woven by the agent
 * and THREAD_END is emitted automatically — no {@link Tracer#threadDone()}
 * call needed. (Lambda targets are hidden classes and aren't retransformed
 * by the agent, so this shape is also what makes automatic THREAD_END show
 * up at all.)
 *
 *   javac -g -d out demo/StartJoinRaw.java
 *   java -javaagent:sdtrace-agent.jar -cp out StartJoinRaw
 */
public class StartJoinRaw {

    static final int WORKERS = 4;
    static final int INCREMENTS = 50;

    static final Lock lock = new ReentrantLock();
    static int total = 0;

    static class Worker extends Thread {
        Worker(String name) { super(name); }

        @Override
        public void run() {
            for (int i = 0; i < INCREMENTS; i++) {
                lock.lock();
                try {
                    total++;
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Worker[] workers = new Worker[WORKERS];
        for (int i = 0; i < WORKERS; i++) workers[i] = new Worker("W" + (i + 1));

        for (Worker w : workers) w.start();
        for (Worker w : workers) w.join();

        // Printed only after every worker is done — its Lamport clock should
        // be strictly greater than every worker's last event.
        Tracer.note("total = " + total);

        Tracer.get().dump(java.nio.file.Path.of("trace.json"));
        System.out.println("Trace written to trace.json. total=" + total);
    }
}
