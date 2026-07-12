package pt.sd.trace.agent;

import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Reescreve o bytecode das classes dos alunos para instrumentar, de forma
 * transparente, o uso de {@code java.util.concurrent.locks.Lock} e
 * {@code Condition} — sem que troquem {@code ReentrantLock} por
 * {@code TracedLock} nem passem nomes à mão.
 *
 * Usa a Class-File API padrão ({@code java.lang.classfile}, final no JDK 24),
 * pelo que não há dependências externas.
 *
 * Duas coisas são injectadas:
 *   1. No local de CRIAÇÃO ({@code new ReentrantLock()} e
 *      {@code lock.newCondition()}), uma chamada que associa o objecto ao nome
 *      da variável/campo que o guarda. Cadeia de nomeação:
 *      campo -> variável local (precisa de {@code -g}) -> {@code Tipo@Classe:linha}.
 *   2. Nos locais de CHAMADA ({@code lock()}, {@code unlock()}, {@code await()},
 *      {@code signal()}, {@code signalAll()}), chamadas aos {@code Hooks} que
 *      registam os eventos, passando o próprio objecto para resolução do nome.
 */
final class LockWeaver {

    private static final String LOCKS_PKG = "java/util/concurrent/locks/";
    private static final String RLOCK     = "java/util/concurrent/locks/ReentrantLock";
    private static final String RRWLOCK   = "java/util/concurrent/locks/ReentrantReadWriteLock";

    private static final ClassDesc HOOKS = ClassDesc.of("pt.sd.trace.Hooks");
    private static final ClassDesc CD_Object = ClassDesc.of("java.lang.Object");
    private static final ClassDesc CD_String = ClassDesc.of("java.lang.String");
    private static final MethodTypeDesc MTD_Obj_v     = MethodTypeDesc.of(ClassDesc.ofDescriptor("V"), CD_Object);
    private static final MethodTypeDesc MTD_ObjStr_v  = MethodTypeDesc.of(ClassDesc.ofDescriptor("V"), CD_Object, CD_String);
    private static final MethodTypeDesc MTD_ObjObj_Obj= MethodTypeDesc.of(CD_Object, CD_Object, CD_Object);
    private static final MethodTypeDesc MTD_ObjObjStr_Obj = MethodTypeDesc.of(CD_Object, CD_Object, CD_Object, CD_String);

    private LockWeaver() {}

    /** Reescreve os bytes da classe {@code internalName}. */
    static byte[] weave(String internalName, byte[] original) {
        String simpleClass = internalName.substring(internalName.lastIndexOf('/') + 1);
        ClassFile cf = ClassFile.of();
        ClassModel cm = cf.parse(original);

        return cf.transformClass(cm, (classBuilder, classElement) -> {
            if (classElement instanceof MethodModel mm) {
                classBuilder.transformMethod(mm, (methodBuilder, methodElement) -> {
                    if (methodElement instanceof CodeModel code) {
                        Map<Integer, Deque<String>> lvt = readLocalNames(code);
                        int[] line = { -1 };
                        Pending[] pending = { null };
                        methodBuilder.transformCode(code, (xb, e) -> {
                            // 1) Fecho de um "pending" (criação à espera do consumidor).
                            if (pending[0] != null && e instanceof java.lang.classfile.Instruction) {
                                Pending p = pending[0];
                                if (e instanceof StoreInstruction si
                                        && si.typeKind() == java.lang.classfile.TypeKind.REFERENCE) {
                                    String nm = pollLocal(lvt, si.slot(), p.fallback);
                                    emitName(xb, nm);
                                    pending[0] = null;
                                    xb.with(e);
                                    return;
                                }
                                if (e instanceof FieldInstruction fi && isPutField(fi)) {
                                    emitName(xb, fi.name().stringValue());
                                    pending[0] = null;
                                    xb.with(e);
                                    return;
                                }
                                // Consumidor não é um store reconhecido: nomeia com fallback.
                                emitName(xb, p.fallback);
                                pending[0] = null;
                                // continua para tratar 'e' normalmente
                            }

                            // 2) Actualiza linha corrente (para o fallback).
                            if (e instanceof LineNumber ln) { line[0] = ln.line(); xb.with(e); return; }

                            // 3) Invocações relevantes.
                            if (e instanceof InvokeInstruction inv) {
                                String owner = inv.owner().asInternalName();
                                String nm    = inv.name().stringValue();
                                String desc  = inv.typeSymbol().descriptorString();

                                // Construtor de lock -> abre pending para nomear no consumidor.
                                if (inv.opcode() == java.lang.classfile.Opcode.INVOKESPECIAL
                                        && nm.equals("<init>")
                                        && (owner.equals(RLOCK) || owner.equals(RRWLOCK))) {
                                    xb.with(e);
                                    pending[0] = new Pending(simple(owner) + "@" + simpleClass + ":" + line[0]);
                                    return;
                                }

                                if (owner.startsWith(LOCKS_PKG)) {
                                    // Vistas de um ReadWriteLock: readLock()/writeLock().
                                    // Sem isto, as duas vistas apareceriam como dois locks
                                    // independentes e a exclusao leitor/escritor ficaria invisivel.
                                    if ((nm.equals("readLock") || nm.equals("writeLock")) && desc.startsWith("()")) {
                                        boolean isRead = nm.equals("readLock");
                                        xb.dup();                       // [rw, rw]
                                        xb.with(e);                     // [rw, view]
                                        xb.loadConstant(isRead ? "READ" : "WRITE");
                                        xb.invokestatic(HOOKS, "linkView", MTD_ObjObjStr_Obj);
                                        xb.checkcast(inv.typeSymbol().returnType());
                                        return;
                                    }
                                    switch (nm + desc) {
                                        case "lock()V", "lockInterruptibly()V" -> {
                                            xb.dup(); xb.dup();
                                            xb.invokestatic(HOOKS, "onLockRequest", MTD_Obj_v);
                                            xb.with(e);
                                            xb.invokestatic(HOOKS, "onLockAcquired", MTD_Obj_v);
                                            return;
                                        }
                                        case "unlock()V" -> {
                                            xb.dup();
                                            xb.invokestatic(HOOKS, "onLockReleased", MTD_Obj_v);
                                            xb.with(e);
                                            return;
                                        }
                                        case "await()V", "awaitUninterruptibly()V" -> {
                                            xb.dup(); xb.dup();
                                            xb.invokestatic(HOOKS, "onAwaitBegin", MTD_Obj_v);
                                            xb.with(e);
                                            xb.invokestatic(HOOKS, "onAwaitWakeup", MTD_Obj_v);
                                            return;
                                        }
                                        case "signal()V" -> {
                                            xb.dup();
                                            xb.invokestatic(HOOKS, "onSignal", MTD_Obj_v);
                                            xb.with(e);
                                            return;
                                        }
                                        case "signalAll()V" -> {
                                            xb.dup();
                                            xb.invokestatic(HOOKS, "onSignalAll", MTD_Obj_v);
                                            xb.with(e);
                                            return;
                                        }
                                        case "newCondition()Ljava/util/concurrent/locks/Condition;" -> {
                                            xb.dup();                 // duplica o lock (receptor)
                                            xb.with(e);               // -> [lock, cond]
                                            xb.invokestatic(HOOKS, "linkOwner", MTD_ObjObj_Obj); // -> [Object]
                                            xb.checkcast(inv.typeSymbol().returnType());        // -> [cond]
                                            pending[0] = new Pending("Condition@" + simpleClass + ":" + line[0]);
                                            return;
                                        }
                                        default -> { xb.with(e); return; }
                                    }
                                }
                            }

                            xb.with(e);
                        });
                    } else {
                        methodBuilder.with(methodElement);
                    }
                });
            } else {
                classBuilder.with(classElement);
            }
        });
    }

    private static void emitName(java.lang.classfile.CodeBuilder xb, String name) {
        xb.dup();
        xb.loadConstant(name);
        xb.invokestatic(HOOKS, "nameThing", MTD_ObjStr_v);
    }

    private static boolean isPutField(FieldInstruction fi) {
        return fi.opcode() == java.lang.classfile.Opcode.PUTFIELD
            || fi.opcode() == java.lang.classfile.Opcode.PUTSTATIC;
    }

    private static String pollLocal(Map<Integer, Deque<String>> lvt, int slot, String fallback) {
        Deque<String> dq = lvt.get(slot);
        if (dq != null && !dq.isEmpty()) return dq.pollFirst();
        return fallback;
    }

    private static Map<Integer, Deque<String>> readLocalNames(CodeModel code) {
        Map<Integer, Deque<String>> m = new HashMap<>();
        code.findAttribute(Attributes.localVariableTable()).ifPresent(a -> {
            a.localVariables().forEach(lv ->
                m.computeIfAbsent(lv.slot(), k -> new ArrayDeque<>()).addLast(lv.name().stringValue()));
        });
        return m;
    }

    private static String simple(String internal) {
        return internal.substring(internal.lastIndexOf('/') + 1);
    }

    private record Pending(String fallback) {}
}
