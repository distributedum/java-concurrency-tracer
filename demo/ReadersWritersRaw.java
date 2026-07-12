import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import pt.sd.trace.Tracer;

/**
 * Leitores/escritores — VIA AGENTE. Usa ReentrantReadWriteLock directamente:
 * nenhum tipo nosso, nenhum nome passado a mao.
 *
 *   javac -g -d out demo/ReadersWritersRaw.java
 *   java -javaagent:sdtrace-agent.jar -cp out ReadersWritersRaw
 *
 * Lock "justo" (fair=true) de proposito: um escritor a espera impede que novos
 * leitores passem a frente, o que evita a fome do escritor e torna o diagrama
 * legivel e reproduzivel.
 */
public class ReadersWritersRaw {

    private final ReadWriteLock rw = new ReentrantReadWriteLock(true);
    private int valor = 0;

    void escrever(int v) {
        rw.writeLock().lock();                 // posse EXCLUSIVA
        try {
            valor = v;
            Tracer.note("escreveu " + v);
            dorme(40);
        } finally {
            rw.writeLock().unlock();
        }
    }

    int ler() {
        rw.readLock().lock();                  // posse PARTILHADA
        try {
            Tracer.note("leu " + valor);
            dorme(50);
            return valor;
        } finally {
            rw.readLock().unlock();
        }
    }

    static void dorme(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    public static void main(String[] args) throws Exception {
        ReadersWritersRaw d = new ReadersWritersRaw();

        Thread w1 = new Thread(() -> { d.escrever(42); Tracer.threadDone(); }, "W1");
        Thread r1 = new Thread(() -> { d.ler(); Tracer.threadDone(); }, "R1");
        Thread r2 = new Thread(() -> { d.ler(); Tracer.threadDone(); }, "R2");
        Thread r3 = new Thread(() -> { d.ler(); Tracer.threadDone(); }, "R3");
        Thread w2 = new Thread(() -> { d.escrever(99); Tracer.threadDone(); }, "W2");

        w1.start();                 // agarra o lock em escrita
        Thread.sleep(10);
        r1.start(); r2.start(); r3.start();   // ficam bloqueados; depois entram TODOS juntos
        Thread.sleep(20);
        w2.start();                 // espera que os tres leitores saiam

        w1.join(); r1.join(); r2.join(); r3.join(); w2.join();

        Tracer.get().dump(java.nio.file.Path.of("trace.json"));
        System.out.println("Trace escrito para trace.json.");
    }
}
