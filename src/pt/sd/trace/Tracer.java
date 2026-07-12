package pt.sd.trace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracer centralizado para instrumentar execuções concorrentes com locks e
 * variáveis de condição.
 *
 * Regista eventos numa ordem total (via um contador global) e atribui a cada
 * evento:
 *   - um relógio lógico de Lamport, por thread;
 *   - um instante físico (nanos relativos ao arranque), para posicionar
 *     visualmente e ver a duração real dos bloqueios;
 *   - uma eventual aresta causal (happens-before) entre threads, usada para
 *     desenhar as setas do diagrama e para o merge do relógio de Lamport.
 *
 * Duas famílias de arestas causais são inferidas:
 *   1. HANDOFF de lock: quando uma thread adquire um lock, liga-se ao evento
 *      de libertação (RELEASE ou AWAIT_BEGIN) mais recente desse lock, se tiver
 *      sido feito por outra thread.
 *   2. SIGNAL -> WAKEUP: um signal()/signalAll() sobre uma condição liga-se ao
 *      regresso do await() da(s) thread(s) em espera (emparelhamento FIFO).
 *
 * NOTA PEDAGÓGICA: o emparelhamento signal->await é uma aproximação (FIFO, e não
 * modela wakeups espúrios), mas corresponde ao modelo mental que os alunos usam
 * e é suficiente para visualizar a causalidade. Está documentado como tal.
 *
 * O registo é totalmente serializado (bloco synchronized) para garantir uma
 * ordem global consistente e acesso seguro aos mapas de estado.
 */
public final class Tracer {

    /** Um evento registado. Campos públicos para facilitar a serialização. */
    public static final class Event {
        public final long   seq;      // ordem total de registo
        public final long   lamport;  // relógio lógico (por thread)
        public final long   tNanos;   // instante físico relativo ao arranque
        public final String thread;   // nome da thread
        public final long   tid;      // id da thread
        public final String kind;     // tipo de evento
        public final String lock;     // nome do lock (ou null)
        public final String cond;     // nome da condição (ou null)
        public final String detail;   // texto livre (ou null)
        public final String mode;     // EXCLUSIVE | READ | WRITE (ou null)
        public final long[] causes;   // seqs dos eventos causadores (pode ter varios!)
        public final Long   cause;    // primeira causa (compatibilidade; null se nenhuma)

        Event(long seq, long lamport, long tNanos, String thread, long tid,
              String kind, String lock, String cond, String detail, String mode, long[] causes) {
            this.seq = seq; this.lamport = lamport; this.tNanos = tNanos;
            this.thread = thread; this.tid = tid; this.kind = kind;
            this.lock = lock; this.cond = cond; this.detail = detail;
            this.mode = mode; this.causes = causes;
            this.cause = (causes.length == 0) ? null : causes[0];
        }
    }

    /** Modos de posse de um lock. */
    public static final String EXCLUSIVE = "EXCLUSIVE"; // ReentrantLock (ou write lock visto como exclusivo)
    public static final String READ      = "READ";      // ReadWriteLock em modo partilhado
    public static final String WRITE     = "WRITE";     // ReadWriteLock em modo exclusivo

    private static boolean shared(String mode) { return READ.equals(mode); }

    private static final Tracer INSTANCE = new Tracer();
    public static Tracer get() { return INSTANCE; }

    private final long t0 = System.nanoTime();
    private long nextSeq = 0L;

    private final List<Event> events = new ArrayList<>();
    private final Map<Long, Long> lamportByTid = new HashMap<>();
    // Último evento de libertação por um detentor EXCLUSIVO (ou escritor) desse lock.
    // Usado pelos LEITORES: a sua única dependência é a saída do último escritor.
    private final Map<String, Event> lastExclusiveReleaseByLock = new HashMap<>();

    // Detentores actuais de cada lock: tid -> profundidade (reentrância).
    // Com um read lock pode haver VÁRIOS em simultâneo — é esse o ponto.
    private final Map<String, Map<Long, Integer>> holdersByLock = new HashMap<>();
    // Libertações acumuladas desde que o lock deixou de estar vazio.
    private final Map<String, List<Event>> pendingReleasesByLock = new HashMap<>();
    // A COORTE: o grupo de libertações que esvaziou o lock da última vez.
    // Um detentor exclusivo (escritor) só entra quando TODOS saíram, por isso
    // depende de TODAS estas libertações — e não apenas da última registada.
    private final Map<String, List<Event>> lastCohortByLock = new HashMap<>();
    // Fila FIFO de threads em espera, por condição (evento AWAIT_BEGIN).
    private final Map<String, Deque<Event>> waitersByCond = new HashMap<>();
    // Sinal pendente que vai acordar uma thread: tid -> evento SIGNAL/SIGNAL_ALL.
    private final Map<Long, Event> pendingWakeup = new HashMap<>();

    private Tracer() {
        // Descarrega automaticamente para trace.json no fim, por conveniência.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { dump(Path.of("trace.json")); } catch (IOException ignored) {}
        }));
    }

    // ---- API interna usada por TracedLock / TracedCondition -----------------

    void lockRequest(String lock)   { lockRequest(lock, EXCLUSIVE); }
    void lockAcquired(String lock)  { lockAcquired(lock, EXCLUSIVE); }
    void lockReleased(String lock)  { lockReleased(lock, EXCLUSIVE); }
    void awaitBegin(String lock, String cond)  { awaitBegin(lock, cond, EXCLUSIVE); }
    void awaitWakeup(String lock, String cond) { awaitWakeup(lock, cond, EXCLUSIVE); }

    void lockRequest(String lock, String mode)   { emit("LOCK_REQUEST",  lock, null, null, mode); }
    void lockAcquired(String lock, String mode)  { emit("LOCK_ACQUIRED", lock, null, null, mode); }
    void lockReleased(String lock, String mode)  { emit("LOCK_RELEASED", lock, null, null, mode); }
    void awaitBegin(String lock, String cond, String mode)  { emit("AWAIT_BEGIN",  lock, cond, null, mode); }
    void awaitWakeup(String lock, String cond, String mode) { emit("AWAIT_WAKEUP", lock, cond, null, mode); }

    void signal(String cond)     { emit("SIGNAL",     null, cond, null, null); }
    void signalAll(String cond)  { emit("SIGNAL_ALL", null, cond, null, null); }

    // ---- API pública para os alunos ----------------------------------------

    /** Marca um evento aplicacional arbitrário (ex.: "produziu 7"). */
    public static void note(String detail) { INSTANCE.emit("NOTE", null, null, detail, null); }

    /** Marca o fim de uma thread (opcional; melhora o diagrama). */
    public static void threadDone() { INSTANCE.emit("THREAD_END", null, null, null, null); }

    /** Limpa o estado (útil para correr vários cenários no mesmo processo). */
    public static synchronized void reset() {
        INSTANCE.events.clear();
        INSTANCE.lamportByTid.clear();
        INSTANCE.lastExclusiveReleaseByLock.clear();
        INSTANCE.holdersByLock.clear();
        INSTANCE.pendingReleasesByLock.clear();
        INSTANCE.lastCohortByLock.clear();
        INSTANCE.waitersByCond.clear();
        INSTANCE.pendingWakeup.clear();
        INSTANCE.nextSeq = 0L;
    }

    // ---- Núcleo -------------------------------------------------------------

    private synchronized Event emit(String kind, String lock, String cond, String detail, String mode) {
        Thread th = Thread.currentThread();
        long tid = th.threadId();
        String tname = th.getName();
        long tNanos = System.nanoTime() - t0;
        long seq = nextSeq++;

        // Determinar as arestas causais (podem ser VÁRIAS).
        List<Event> causes = new ArrayList<>();
        if ("LOCK_ACQUIRED".equals(kind)) {
            if (shared(mode)) {
                // LEITOR: não é bloqueado por outros leitores. A sua única dependência é
                // a saída do último ESCRITOR (que "publica" o valor que ele vai ler).
                // Ligá-lo a outro leitor seria inventar causalidade.
                Event rel = lastExclusiveReleaseByLock.get(lock);
                if (rel != null && rel.tid != tid) causes.add(rel);
            } else {
                // EXCLUSIVO (lock normal ou ESCRITOR): só entra quando TODOS saíram.
                // Depende, portanto, de TODAS as libertações da coorte que esvaziou o
                // lock — com 3 leitores, são 3 arestas, não uma. Ficar só pela última
                // registada perderia arestas reais e podia violar a condição de Lamport
                // (se um leitor que saiu antes tivesse relógio mais alto).
                List<Event> cohort = lastCohortByLock.get(lock);
                if (cohort != null) {
                    for (Event r : cohort) if (r.tid != tid) causes.add(r);
                }
            }
        } else if ("AWAIT_WAKEUP".equals(kind)) {
            Event sig = pendingWakeup.remove(tid);
            if (sig != null) causes.add(sig);
        }

        // Relógio de Lamport: max sobre TODAS as causas.
        long local = lamportByTid.getOrDefault(tid, 0L);
        long base = local;
        for (Event c : causes) base = Math.max(base, c.lamport);
        long lamport = base + 1;
        lamportByTid.put(tid, lamport);

        long[] causeSeqs = new long[causes.size()];
        for (int i = 0; i < causeSeqs.length; i++) causeSeqs[i] = causes.get(i).seq;

        Event e = new Event(seq, lamport, tNanos, tname, tid, kind, lock, cond,
                            detail, mode, causeSeqs);

        // Efeitos posteriores no estado partilhado.
        switch (kind) {
            case "LOCK_ACQUIRED", "AWAIT_WAKEUP" -> {
                // Passa a deter o lock (o await regressa readquirindo-o).
                holdersByLock.computeIfAbsent(lock, k -> new HashMap<>())
                             .merge(tid, 1, Integer::sum);
            }
            case "LOCK_RELEASED", "AWAIT_BEGIN" -> {
                if (!shared(mode)) lastExclusiveReleaseByLock.put(lock, e);

                Map<Long, Integer> holders = holdersByLock.computeIfAbsent(lock, k -> new HashMap<>());
                if ("AWAIT_BEGIN".equals(kind)) {
                    holders.remove(tid);              // o await liberta o lock por completo
                } else {
                    Integer d = holders.get(tid);     // unlock: desce um nível de reentrância
                    if (d == null || d <= 1) holders.remove(tid); else holders.put(tid, d - 1);
                }

                List<Event> pend = pendingReleasesByLock.computeIfAbsent(lock, k -> new ArrayList<>());
                pend.add(e);
                if (holders.isEmpty()) {
                    // O lock ficou VAZIO: esta coorte é a que o próximo detentor
                    // exclusivo teve de esperar por inteiro.
                    lastCohortByLock.put(lock, pend);
                    pendingReleasesByLock.put(lock, new ArrayList<>());
                }
            }
        }
        if ("AWAIT_BEGIN".equals(kind)) {
            waitersByCond.computeIfAbsent(cond, k -> new ArrayDeque<>()).addLast(e);
        } else if ("SIGNAL".equals(kind)) {
            Deque<Event> q = waitersByCond.get(cond);
            if (q != null) {
                Event w = q.pollFirst();
                if (w != null) pendingWakeup.put(w.tid, e);
            }
        } else if ("SIGNAL_ALL".equals(kind)) {
            Deque<Event> q = waitersByCond.get(cond);
            if (q != null) {
                Event w;
                while ((w = q.pollFirst()) != null) pendingWakeup.put(w.tid, e);
            }
        }

        events.add(e);
        return e;
    }

    // ---- Serialização JSON (sem dependências) -------------------------------

    /** Escreve o trace em JSON para o caminho dado. */
    public synchronized void dump(Path path) throws IOException {
        Files.writeString(path, toJson());
    }

    public synchronized String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"events\": [\n");
        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            sb.append("    {")
              .append("\"seq\":").append(e.seq)
              .append(",\"lamport\":").append(e.lamport)
              .append(",\"t\":").append(e.tNanos)
              .append(",\"thread\":").append(str(e.thread))
              .append(",\"tid\":").append(e.tid)
              .append(",\"kind\":").append(str(e.kind))
              .append(",\"lock\":").append(str(e.lock))
              .append(",\"cond\":").append(str(e.cond))
              .append(",\"detail\":").append(str(e.detail))
              .append(",\"mode\":").append(str(e.mode))
              .append(",\"cause\":").append(e.cause == null ? "null" : e.cause)
              .append(",\"causes\":").append(seqs(e.causes))
              .append("}");
            if (i < events.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n}\n");
        return sb.toString();
    }

    private static String seqs(long[] a) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) { if (i > 0) b.append(","); b.append(a[i]); }
        return b.append("]").toString();
    }

    private static String str(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default   -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append("\"").toString();
    }
}
