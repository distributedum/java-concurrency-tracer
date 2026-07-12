package pt.sd.trace.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.security.ProtectionDomain;

/**
 * Agente Java que instrumenta, de forma transparente, o uso de locks e
 * variáveis de condição no código dos alunos. Basta arrancar a JVM com:
 *
 * <pre>{@code
 *   java -javaagent:sdtrace-agent.jar -cp out MinhaClasse
 * }</pre>
 *
 * Opcionalmente, restringir as classes a instrumentar por prefixo:
 *
 * <pre>{@code
 *   java -javaagent:sdtrace-agent.jar=include=com.aluno,BoundedBufferRaw -cp out MinhaClasse
 * }</pre>
 *
 * No fim da execução, o {@link pt.sd.trace.Tracer} escreve {@code trace.json}
 * (via shutdown hook), pronto a abrir no visualizador.
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
                    return null; // JDK e o próprio runtime de tracing
                }
                // include= : se estiver definido, SÓ estas classes são instrumentadas.
                if (includes != null) {
                    boolean ok = false;
                    for (String p : includes) if (name.startsWith(p)) { ok = true; break; }
                    if (!ok) return null;
                }
                // exclude= : estas ficam sempre de fora (tem prioridade sobre include).
                if (excludes != null) {
                    for (String p : excludes) if (name.startsWith(p)) return null;
                }
                // Pré-filtro barato: só reescreve classes que referenciam locks.
                if (!referencesLocks(buffer)) return null;
                try {
                    byte[] out = LockWeaver.weave(name, buffer);
                    if (verbose) System.err.println("[sdtrace] instrumentada: " + name.replace('/', '.'));
                    return out;
                } catch (Throwable t) {
                    System.err.println("[sdtrace] falha ao instrumentar " + name + ": " + t);
                    return null; // deixa a classe original intacta em caso de erro
                }
            }
        });

        StringBuilder banner = new StringBuilder("[sdtrace] agente activo");
        banner.append(" | include=").append(includes == null ? "(todas as classes da aplicacao)" : String.join(",", includes));
        if (excludes != null) banner.append(" | exclude=").append(String.join(",", excludes));
        if (verbose) banner.append(" | verbose");
        System.err.println(banner);
    }

    /** args no formato: chave=valor;chave=valor;flag  (ex.: include=pt.ua.sd;verbose) */
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

    /** "pt.ua.sd,com.aluno" -> {"pt/ua/sd", "com/aluno"} (aceita pontos ou barras). */
    private static String[] prefixes(String list) {
        if (list == null || list.isBlank()) return null;
        String[] parts = list.split(",");
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim().replace('.', '/');
        return parts;
    }

    /** Procura a string UTF-8 do pacote de locks na constant pool (varredura simples). */
    private static boolean referencesLocks(byte[] classBytes) {
        String s = new String(classBytes, StandardCharsets.ISO_8859_1);
        return s.contains("java/util/concurrent/locks/");
    }

}
