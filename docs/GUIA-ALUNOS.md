# Diagrama espaço-temporal de execuções concorrentes — Guia de utilização

Esta ferramenta permite instrumentar programas Java que usem *locks* e variáveis de
condição, e obter uma **representação visual** da execução: os estados de cada *thread*
ao longo do tempo, os bloqueios na aquisição de *locks*, as esperas em condições, as
notificações entre *threads* e as relações de causalidade (*happens-before*), anotadas
com **relógios de Lamport**.

Existem duas vias de instrumentação. Deve ser escolhida **uma**.

| | **Via A — Wrappers** | **Via B — Agente** |
|---|---|---|
| Alterações ao código | substituir `ReentrantLock` por `TracedLock` | **nenhuma** |
| Versão mínima do JDK | **21** | **24** |
| Nomes dos *locks* | definidos explicitamente | inferidos automaticamente |
| Mecanismo | biblioteca | opção `-javaagent` |

Em JDK 21 deve usar-se a Via A. Em JDK 24 ou superior, qualquer uma das vias é válida;
a Via B tem a vantagem de não exigir alterações ao código.

---

## 1. Conteúdo do pacote

```
sdtrace-agent.jar      Ficheiro único a adicionar ao projecto.
                       Serve simultaneamente como biblioteca (Via A) e agente (Via B).
viz/spacetime.html     Visualizador. Abre-se no browser e funciona offline.
src/pt/sd/trace/       Código-fonte da ferramenta.
demo/                  Quatro exemplos completos (dois por via).
```

Antes de começar, confirmar a versão do JDK instalada:

```bash
java -version
```

---

## 2. Via A — Wrappers (JDK 21 ou superior)

### 2.1. Configuração no IntelliJ

**Adicionar a biblioteca.** Copiar `sdtrace-agent.jar` para o projecto (por exemplo, para
uma pasta `lib/`). Em seguida, clique-direito sobre o ficheiro → **Add as Library…**

Em alternativa: *File → Project Structure → Libraries → **+** → Java*, e seleccionar o JAR.

### 2.2. Alterações ao código

Apenas a criação do *lock* e das condições é alterada:

```java
import pt.sd.trace.*;

public class Buffer {
    // Antes:  private final Lock lock = new ReentrantLock();
    private final TracedLock lock = new TracedLock("bufferLock");

    // Antes:  private final Condition naoVazio = lock.newCondition();
    private final TracedCondition naoVazio = lock.newCondition("naoVazio");
    private final TracedCondition naoCheio = lock.newCondition("naoCheio");

    void produzir(int v) throws InterruptedException {
        lock.lock();                       // o restante código mantém-se inalterado
        try {
            while (cheio()) naoCheio.await();
            // ...
            Tracer.note("produziu " + v);  // opcional: marca aplicacional no diagrama
            naoVazio.signal();
        } finally {
            lock.unlock();
        }
    }
}
```

`TracedLock` implementa `java.util.concurrent.locks.Lock` e `TracedCondition` implementa
`Condition`, pelo que o restante código permanece inalterado.

Para *locks* de leitura/escrita:

```java
TracedReadWriteLock rw = new TracedReadWriteLock("dados");
rw.readLock().lock();      // posse partilhada (vários leitores em simultâneo)
rw.writeLock().lock();     // posse exclusiva
```

### 2.3. Execução

No IntelliJ, executar normalmente (▶). Não é necessária qualquer configuração adicional.
No final da execução é gerado o ficheiro `trace.json` na pasta de trabalho.

### 2.4. Linha de comandos

```bash
javac -cp sdtrace-agent.jar -d out src/Buffer.java src/Main.java
java  -cp sdtrace-agent.jar:out Main          # Linux e macOS
java  -cp "sdtrace-agent.jar;out" Main        # Windows
```

Em Windows, o separador do *classpath* é `;` e não `:`.

---

## 3. Via B — Agente (JDK 24 ou superior)

O código é escrito com a API padrão do Java. A instrumentação é injectada pelo agente no
momento do carregamento das classes.

```java
import java.util.concurrent.locks.*;

public class Buffer {
    private final Lock lock = new ReentrantLock();
    private final Condition naoVazio = lock.newCondition();
    private final Condition naoCheio = lock.newCondition();
    // restante código sem alterações
}
```

### 3.1. Configuração no IntelliJ

**Passo 1 — definir o SDK do projecto.**
*File → Project Structure → Project → SDK*: seleccionar um JDK **24 ou superior**.
Caso não esteja disponível, usar *Add SDK → Download JDK…*

**Passo 2 — adicionar a biblioteca.**
Clique-direito sobre `sdtrace-agent.jar` → **Add as Library…** (necessário para usar
`Tracer.note(...)` e para referenciar o agente).

**Passo 3 — activar o agente.**

1. *Run → Edit Configurations…*
2. Seleccionar a configuração que contém o método `main`.
3. **Modify options → Add VM options**.
4. No campo **VM options**, introduzir:

```
-javaagent:lib/sdtrace-agent.jar=include=pt.ua.sd.trabalho1
```

O valor de `include=` deve corresponder ao pacote das classes do trabalho
(ver secção 4).

Se o caminho contiver espaços, delimitar a opção com aspas:

```
"-javaagent:C:\Os Meus Projectos\lib\sdtrace-agent.jar=include=pt.ua.sd.trabalho1"
```

**Passo 4 — executar.** A consola deve apresentar:

```
[sdtrace] agente activo | include=pt/ua/sd/trabalho1
```

A ausência desta linha indica que o agente não foi carregado; nesse caso, rever o Passo 3.

### 3.2. Linha de comandos

```bash
javac -g -cp sdtrace-agent.jar -d out src/*.java
java -javaagent:sdtrace-agent.jar=include=pt.ua.sd.trabalho1 -cp sdtrace-agent.jar:out Main
```

Em Windows:

```
java -javaagent:sdtrace-agent.jar=include=pt.ua.sd.trabalho1 -cp "sdtrace-agent.jar;out" Main
```

A opção `-g` é relevante apenas quando os *locks* são guardados em **variáveis locais**
(ver secção 6). O IntelliJ compila com `-g` por omissão.

---

## 4. Restringir a instrumentação às classes do trabalho

O agente ignora por omissão todas as classes do JDK (`java.*`, `jdk.*`, `sun.*`,
`javax.*`). No entanto, bibliotecas externas presentes no *classpath* que utilizem *locks*
internamente (caches, *pools* de ligações, frameworks de teste) seriam instrumentadas e
introduziriam ruído no diagrama.

A opção **`include=`** restringe a instrumentação às classes indicadas. A comparação é
feita por **prefixo** do nome qualificado.

### Exemplos

Classes num pacote:

```
-javaagent:sdtrace-agent.jar=include=pt.ua.sd.trabalho1
```

Vários pacotes (separados por vírgulas, sem espaços):

```
-javaagent:sdtrace-agent.jar=include=pt.ua.sd.buffer,pt.ua.sd.filosofos
```

Um pacote e todos os seus sub-pacotes — resulta da comparação por prefixo:
`include=pt.ua.sd` abrange `pt.ua.sd.buffer`, `pt.ua.sd.leitores`, etc.

Classes sem declaração de `package` (pacote por omissão) — indicar o nome das classes:

```
-javaagent:sdtrace-agent.jar=include=Buffer,Main
```

Instrumentar tudo excepto uma biblioteca específica:

```
-javaagent:sdtrace-agent.jar=exclude=com.acme.cache
```

Listar as classes efectivamente instrumentadas (útil quando não são registados eventos) —
acrescentar `verbose`, separando as opções por `;`:

```bash
java "-javaagent:sdtrace-agent.jar=include=pt.ua.sd;verbose" -cp out Main
```

Resultado:

```
[sdtrace] agente activo | include=pt/ua/sd | verbose
[sdtrace] instrumentada: pt.ua.sd.trabalho1.Buffer
```

**Nota importante.** O carácter `;` é interpretado como separador de comandos pelas *shells*
(bash, zsh e PowerShell). Ao usar `verbose` na linha de comandos, a opção `-javaagent`
deve ser **delimitada por aspas**, como no exemplo acima. No campo *VM options* do IntelliJ
as aspas não são necessárias.

### Efeito da restrição

Considerando uma biblioteca `com.libx.Cache` que utiliza internamente um `ReentrantLock`:

| Configuração | *Locks* registados |
|---|---|
| `-javaagent:sdtrace-agent.jar` | `meuLock` e `lk` (o da biblioteca — ruído) |
| `-javaagent:sdtrace-agent.jar=include=pt.ua.sd.t1` | apenas `meuLock` |
| `-javaagent:sdtrace-agent.jar=exclude=com.libx` | apenas `meuLock` |

---

## 5. Visualização do diagrama

1. Executar o programa. É gerado o ficheiro **`trace.json`** na pasta de trabalho.
   No IntelliJ, corresponde à raiz do projecto; o valor exacto encontra-se em
   *Run → Edit Configurations… → Working directory*.
2. Abrir **`viz/spacetime.html`** no browser (funciona offline).
3. Seleccionar **“Abrir trace.json…”** e indicar o ficheiro gerado.

Leitura do diagrama:

- Cada **coluna** corresponde a uma *thread*; o **tempo decorre de cima para baixo**.
- **Bandas**: âmbar — bloqueada à espera do *lock*; turquesa sólida — secção crítica
  (posse exclusiva); turquesa hachurada — leitura (posse partilhada, podendo várias
  *threads* apresentá-la em simultâneo); violeta — em espera numa variável de condição.
- **Setas**: cinzento tracejado — passagem do *lock* entre *threads*; rosa —
  `signal`/`signalAll` seguido do regresso do `await`.
- **Clicar** num evento fixa o painel de detalhes, com o cálculo do relógio de Lamport e o
  respectivo passado causal. Clicar fora do evento, ou premir `Esc`, fecha o painel.
- O selector **Eixo: Tempo físico / Relógio de Lamport** alterna entre a duração real dos
  bloqueios e a ordem causal.
- **Zoom** sobre o eixo do tempo: `Ctrl` (ou `⌘`) com a roda do rato, pinça no *touchpad*,
  ou as teclas `+` e `−`; a tecla `0` repõe o valor inicial. O zoom mantém fixo o ponto que
  estiver sob o cursor, o que permite ampliar uma zona de contenção sem a perder de vista.
  Quando não existe cursor sobre o diagrama (utilização do teclado ou do cursor deslizante),
  a referência passa a ser o topo da área visível.

---

## 6. Inferência dos nomes dos *locks* (Via B)

Na Via A os nomes são definidos explicitamente. Na Via B, o agente infere-os pela seguinte
ordem de precedência:

1. **Campo** — `private final Lock lock = new ReentrantLock();` produz o nome `lock`.
   Funciona sempre, sem opções de compilação adicionais. Corresponde ao caso habitual, em
   que o *lock* é um campo do objecto partilhado.
2. **Variável local** — `Lock meuLock = new ReentrantLock();` produz o nome `meuLock`,
   desde que a compilação inclua `-g` (o IntelliJ fá-lo por omissão; na linha de comandos é
   necessário indicá-lo explicitamente).
3. **Alternativa final** — `ReentrantLock@Buffer:27` (tipo, classe e número de linha).
   Nenhum evento fica sem identificação.

Num `ReadWriteLock`, as vistas de leitura e de escrita partilham o nome do campo e
distinguem-se pelo **modo** de posse, o que permite representar a exclusão entre leitores e
escritores.

---

## 7. Resolução de problemas

| Sintoma | Causa provável | Resolução |
|---|---|---|
| `UnsupportedClassVersionError` ao iniciar com `-javaagent` | JDK anterior à versão 24 | Usar a Via A ou instalar um JDK 24+ |
| A mensagem `[sdtrace] agente activo` não é apresentada | A opção `-javaagent` não foi passada à JVM | IntelliJ: *Modify options → Add VM options* (não confundir com *Program arguments*) |
| Diagrama sem eventos de *lock* | O valor de `include=` não corresponde ao pacote das classes | Executar com `;verbose` (entre aspas) e verificar as classes instrumentadas |
| São registados *locks* não pertencentes ao trabalho | Bibliotecas do *classpath* utilizam *locks* | Definir `include=` com o pacote do trabalho |
| Os *locks* surgem como `ReentrantLock@Classe:12` | *Locks* em variáveis locais, compilados sem `-g` | Compilar com `javac -g`, ou declarar o *lock* como campo |
| O ficheiro `trace.json` não é encontrado | Foi gerado noutra pasta | Verificar o *Working directory* da configuração de execução |
| `UnsupportedOperationException` em `readLock().newCondition()` | Apenas o *write lock* suporta condições | Utilizar `writeLock().newCondition()` |
| O comando é interrompido ao usar `verbose` | `;` é separador de comandos na *shell* | Delimitar a opção `-javaagent` com aspas |

---

## 8. Âmbito da instrumentação

Não são instrumentados:

- `synchronized`, `wait()` e `notify()`, por não utilizarem as classes de *lock* de
  `java.util.concurrent.locks`. Os trabalhos que exijam representação no diagrama devem
  recorrer a *locks* explícitos.
- `tryLock()` e as variantes de `await` com *timeout* (`awaitNanos`, `await(t, u)`): são
  registados na Via A, mas não na Via B.

O registo dos eventos introduz uma sincronização adicional e um ligeiro efeito de
observação. O eixo de tempo físico é adequado para analisar bloqueios, mas não para medições
de desempenho.
