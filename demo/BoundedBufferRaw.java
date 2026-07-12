import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import pt.sd.trace.Tracer;

/**
 * Produtor/consumidor com buffer limitado, escrito com a API padrão de locks
 * — SEM TracedLock, SEM nomes passados à mão. A única coisa "de tracing" é o
 * {@link Tracer#note} opcional (que os alunos podem até omitir).
 *
 * Toda a instrumentação de lock/await/signal é injectada pelo agente em tempo
 * de carregamento. Correr com:
 *
 *   javac -g -d out demo/BoundedBufferRaw.java
 *   java -javaagent:sdtrace-agent.jar -cp out BoundedBufferRaw
 */
public class BoundedBufferRaw {

    static final int CAPACITY = 1;

    private final Queue<Integer> buf = new ArrayDeque<>();
    private final Lock lock = new ReentrantLock();
    private final Condition naoCheio = lock.newCondition();
    private final Condition naoVazio = lock.newCondition();

    void produzir(int valor) throws InterruptedException {
        lock.lock();
        try {
            while (buf.size() == CAPACITY) naoCheio.await();
            buf.add(valor);
            Tracer.note("produziu " + valor);
            naoVazio.signal();
        } finally {
            lock.unlock();
        }
    }

    int consumir() throws InterruptedException {
        lock.lock();
        try {
            while (buf.isEmpty()) naoVazio.await();
            int v = buf.poll();
            Tracer.note("consumiu " + v);
            naoCheio.signal();
            return v;
        } finally {
            lock.unlock();
        }
    }

    public static void main(String[] args) throws Exception {
        BoundedBufferRaw b = new BoundedBufferRaw();

        Runnable produtor = () -> {
            int base = Thread.currentThread().getName().equals("P1") ? 10 : 20;
            for (int i = 0; i < 3; i++) {
                try { b.produzir(base + i); Thread.sleep(20); }
                catch (InterruptedException e) { return; }
            }
            Tracer.threadDone();
        };
        Runnable consumidor = () -> {
            for (int i = 0; i < 3; i++) {
                try { b.consumir(); Thread.sleep(60); }
                catch (InterruptedException e) { return; }
            }
            Tracer.threadDone();
        };

        Thread p1 = new Thread(produtor, "P1");
        Thread p2 = new Thread(produtor, "P2");
        Thread c1 = new Thread(consumidor, "C1");
        Thread c2 = new Thread(consumidor, "C2");

        c1.start(); c2.start(); p1.start(); p2.start();
        p1.join(); p2.join(); c1.join(); c2.join();

        Tracer.get().dump(java.nio.file.Path.of("trace.json"));
        System.out.println("Trace escrito para trace.json.");
    }
}
