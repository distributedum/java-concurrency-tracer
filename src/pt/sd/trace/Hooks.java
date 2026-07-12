package pt.sd.trace;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Pontos de entrada invocados pelo <em>bytecode</em> injectado pelo agente
 * ({@code pt.sd.trace.agent}). Os alunos usam {@code ReentrantLock},
 * {@code ReentrantReadWriteLock} e {@code Condition} directamente e não passam
 * nomes: o agente injecta, no local de criação, uma chamada que associa cada
 * objecto (por identidade) ao nome da variável ou campo que o guarda.
 *
 * <p><b>Read/write locks.</b> {@code rw.readLock()} e {@code rw.writeLock()}
 * devolvem dois objectos distintos, que ingenuamente apareceriam como dois locks
 * sem relação nenhuma — e a exclusão leitor/escritor ficaria invisível. Por isso
 * registamos cada vista como <em>vista de</em> um lock-pai: ambas herdam o nome
 * do pai (ex.: {@code rw}) e distinguem-se pelo <b>modo</b> ({@code READ} ou
 * {@code WRITE}). Assim o {@link Tracer} vê um único lock com dois modos de
 * posse — que é exactamente o que ele é.
 */
public final class Hooks {

    private Hooks() {}

    /** Objecto (lock ou condição) -> nome legível. */
    private static final Map<Object, String> NAME =
            Collections.synchronizedMap(new IdentityHashMap<>());
    /** Condição -> lock que a criou (para ligar o await ao lock no diagrama). */
    private static final Map<Object, Object> OWNER =
            Collections.synchronizedMap(new IdentityHashMap<>());
    /** Vista (read/write lock) -> ReadWriteLock que a produziu. */
    private static final Map<Object, Object> PARENT =
            Collections.synchronizedMap(new IdentityHashMap<>());
    /** Vista -> modo de posse (READ ou WRITE). */
    private static final Map<Object, String> MODE =
            Collections.synchronizedMap(new IdentityHashMap<>());

    // ---- Registo (injectado nos locais de criação) --------------------------

    /** Associa um nome a um lock ou condição. O primeiro nome ganha. */
    public static void nameThing(Object o, String name) {
        if (o != null && name != null) NAME.putIfAbsent(o, name);
    }

    /**
     * Regista que {@code cond} foi criada por {@code lock} e devolve {@code cond}.
     * Injectado logo após {@code lock.newCondition()}.
     */
    public static Object linkOwner(Object lock, Object cond) {
        if (cond != null && lock != null) OWNER.putIfAbsent(cond, lock);
        return cond;
    }

    /**
     * Regista que {@code view} e a vista de leitura/escrita de {@code parent} e
     * devolve {@code view}. Injectado logo apos {@code rw.readLock()} /
     * {@code rw.writeLock()}.
     */
    public static Object linkView(Object parent, Object view, String mode) {
        if (view != null && parent != null) {
            PARENT.putIfAbsent(view, parent);
            MODE.putIfAbsent(view, mode);
        }
        return view;
    }

    // ---- Resolucao ----------------------------------------------------------

    /** Nome de um lock. Uma vista read/write herda o nome do ReadWriteLock pai. */
    private static String nameOf(Object o) {
        if (o == null) return "?";
        Object parent = PARENT.get(o);
        if (parent != null) return nameOf(parent);   // vista -> nome do pai
        String n = NAME.get(o);
        return (n != null) ? n
                : o.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(o));
    }

    /** Modo: READ/WRITE nas vistas de um RW lock, EXCLUSIVE nos restantes. */
    private static String modeOf(Object lock) {
        String m = (lock == null) ? null : MODE.get(lock);
        return (m != null) ? m : Tracer.EXCLUSIVE;
    }

    private static Object ownerLock(Object cond) { return (cond == null) ? null : OWNER.get(cond); }

    // ---- Eventos (injectados nos locais de chamada) -------------------------

    public static void onLockRequest(Object l)  { Tracer.get().lockRequest(nameOf(l),  modeOf(l)); }
    public static void onLockAcquired(Object l) { Tracer.get().lockAcquired(nameOf(l), modeOf(l)); }
    public static void onLockReleased(Object l) { Tracer.get().lockReleased(nameOf(l), modeOf(l)); }

    public static void onAwaitBegin(Object c) {
        Object l = ownerLock(c);
        Tracer.get().awaitBegin(nameOf(l), nameOf(c), modeOf(l));
    }
    public static void onAwaitWakeup(Object c) {
        Object l = ownerLock(c);
        Tracer.get().awaitWakeup(nameOf(l), nameOf(c), modeOf(l));
    }

    public static void onSignal(Object c)    { Tracer.get().signal(nameOf(c)); }
    public static void onSignalAll(Object c) { Tracer.get().signalAll(nameOf(c)); }
}
