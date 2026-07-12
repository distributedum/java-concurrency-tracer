import pt.sd.trace.Tracer;
import pt.sd.trace.TracedCondition;
import pt.sd.trace.TracedLock;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Produtor/consumidor com buffer limitado, usando o Lock e as Condition
 * instrumentados. O objectivo é gerar um trace rico: com bloqueios à espera do
 * lock, esperas em variável de condição, e notificações entre threads.
 *
 * A ÚNICA diferença face a um programa "normal" de SD é usar TracedLock em vez
 * de ReentrantLock. Tudo o resto é idêntico ao que os alunos escreveriam.
 */
public class BoundedBufferDemo {

    static final int CAPACITY = 1;

    // Estado partilhado protegido pelo lock.
    static final TracedLock lock = new TracedLock("bufferLock");
    static final TracedCondition naoCheio = lock.newCondition("naoCheio");
    static final TracedCondition naoVazio = lock.newCondition("naoVazio");
    static final Queue<Integer> buffer = new ArrayDeque<>();

    static void produzir(int valor) throws InterruptedException {
        lock.lock();
        try {
            while (buffer.size() == CAPACITY) {
                naoCheio.await();               // buffer cheio: espera
            }
            buffer.add(valor);
            Tracer.note("produziu " + valor + " (buffer=" + buffer.size() + ")");
            naoVazio.signal();                  // acorda um consumidor
        } finally {
            lock.unlock();
        }
    }

    static Integer consumir() throws InterruptedException {
        lock.lock();
        try {
            while (buffer.isEmpty()) {
                naoVazio.await();               // buffer vazio: espera
            }
            int v = buffer.poll();
            Tracer.note("consumiu " + v + " (buffer=" + buffer.size() + ")");
            naoCheio.signal();                  // acorda um produtor
            return v;
        } finally {
            lock.unlock();
        }
    }

    static Thread produtor(String nome, int base, int quantos) {
        return new Thread(() -> {
            try {
                for (int i = 0; i < quantos; i++) {
                    produzir(base + i);
                    Thread.sleep(20);           // trabalho fora da secção crítica
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                Tracer.threadDone();
            }
        }, nome);
    }

    static Thread consumidor(String nome, int quantos) {
        return new Thread(() -> {
            try {
                for (int i = 0; i < quantos; i++) {
                    consumir();
                    Thread.sleep(60);           // consome mais devagar -> buffer enche
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                Tracer.threadDone();
            }
        }, nome);
    }

    public static void main(String[] args) throws Exception {
        Thread p1 = produtor("P1", 100, 3);
        Thread p2 = produtor("P2", 200, 3);
        Thread c1 = consumidor("C1", 3);
        Thread c2 = consumidor("C2", 3);

        p1.start();
        Thread.sleep(10);
        c1.start();
        Thread.sleep(5);
        p2.start();
        c2.start();

        p1.join(); p2.join(); c1.join(); c2.join();

        Tracer.get().dump(java.nio.file.Path.of("trace.json"));
        System.out.println("Trace escrito para trace.json.");
    }
}
