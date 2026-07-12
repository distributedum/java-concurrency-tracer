package pt.sd.trace.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.security.ProtectionDomain;

/**
 * Java agent that transparently instruments the use of locks and condition
 * variables in student code. Just start the JVM with:
 *
 * <pre>{@code
 *   java -javaagent:sdtrace-agent.jar -cp out MyClass
 * }</pre>
 *
 * Optionally, restrict the classes to instrument by prefix:
 *
 * <pre>{@code
 *   java -javaagent:sdtrace-agent.jar=include=com.student,BoundedBufferRaw -cp out MyClass
 * }</pre>
 *
 * At the end of the run, the {@link pt.sd.trace.Tracer} writes {@code trace.json}
 * (via a shutdown hook), ready to open in the visualizer.
 */
public final class Agent {

    private Agent() {}

    public static void premain(String args, Instrumentation inst) {
        final Map<String, String> opts = parseArgs(args);
        final String[] includes = prefixes(opts.get("include"));
        final String[] excludes = prefixes(opts.get("exclude"));
        final boolean verbose   = opts.containsKey("verbose");

        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> beingRedefined,
                                    ProtectionDomain pd, byte[] buffer) {
                if (name == null) return null;
                if (name.startsWith("java/") || name.startsWith("jdk/")
                        || name.startsWith("sun/") || name.startsWith("javax/")
                        || name.startsWith("pt/sd/trace/")) {
                    return null; // the JDK and the tracing runtime itself
                }
                // include= : if set, ONLY these classes are instrumented.
                if (includes != null) {
                    boolean ok = false;
                    for (String p : includes) if (name.startsWith(p)) { ok = true; break; }
                    if (!ok) return null;
                }
                // exclude= : these are always left out (takes priority over include).
                if (excludes != null) {
                    for (String p : excludes) if (name.startsWith(p)) return null;
                }
                // Cheap pre-filter: only rewrites classes that reference locks.
                if (!referencesLocks(buffer)) return null;
                try {
                    byte[] out = LockWeaver.weave(name, buffer);
                    if (verbose) System.err.println("[sdtrace] instrumented: " + name.replace('/', '.'));
                    return out;
                } catch (Throwable t) {
                    System.err.println("[sdtrace] failed to instrument " + name + ": " + t);
                    return null; // leaves the original class intact on error
                }
            }
        });

        StringBuilder banner = new StringBuilder("[sdtrace] agent active");
        banner.append(" | include=").append(includes == null ? "(all application classes)" : String.join(",", includes));
        if (excludes != null) banner.append(" | exclude=").append(String.join(",", excludes));
        if (verbose) banner.append(" | verbose");
        System.err.println(banner);
    }

    /** args in the format: key=value;key=value;flag  (e.g. include=pt.ua.sd;verbose) */
    private static Map<String, String> parseArgs(String args) {
        Map<String, String> m = new LinkedHashMap<>();
        if (args == null || args.isBlank()) return m;
        for (String part : args.split(";")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            if (eq < 0) m.put(part, "");                                  // flag (ex.: verbose)
            else m.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        return m;
    }

    /** "pt.ua.sd,com.student" -> {"pt/ua/sd", "com/student"} (accepts dots or slashes). */
    private static String[] prefixes(String list) {
        if (list == null || list.isBlank()) return null;
        String[] parts = list.split(",");
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim().replace('.', '/');
        return parts;
    }

    /** Looks for the locks package's UTF-8 string in the constant pool (simple scan). */
    private static boolean referencesLocks(byte[] classBytes) {
        String s = new String(classBytes, StandardCharsets.ISO_8859_1);
        return s.contains("java/util/concurrent/locks/");
    }

}
