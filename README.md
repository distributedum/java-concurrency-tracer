# Diagrama espaço-temporal de execuções concorrentes (Java)

Ferramenta pedagógica para **Sistemas Distribuídos**: os alunos instrumentam o
seu próprio código com locks e variáveis de condição e obtêm uma **representação
visual** da execução — quem bloqueia à espera de um lock, quem espera numa
condição, quem notificou quem, o estado de cada *thread* em cada instante, e as
relações de causalidade (*happens-before*) anotadas com **relógios de Lamport**.

Não há dependências externas: só o JDK para correr o programa e um browser para
abrir o diagrama. Funciona offline.

> **Para entregar aos alunos: ver [`docs/GUIA-ALUNOS.md`](docs/GUIA-ALUNOS.md)** — instruções passo a
> passo para IntelliJ e linha de comandos, nas duas vias, incluindo como limitar a
> instrumentação às classes deles (`include=`) e uma tabela de resolução de problemas.

---

## O que os alunos fazem (fluxo de trabalho)

1. **Usar as versões instrumentadas** do lock e da condição, em vez das normais.
   A única mudança no código deles é trocar `ReentrantLock` por `TracedLock`:

   ```java
   import pt.sd.trace.*;

   TracedLock lock       = new TracedLock("bufferLock");      // em vez de new ReentrantLock()
   TracedCondition cheio = lock.newCondition("naoCheio");     // condição com nome legível
   TracedCondition vazio = lock.newCondition("naoVazio");
   ```

   `TracedLock` implementa `java.util.concurrent.locks.Lock` e `TracedCondition`
   implementa `Condition`, por isso o resto do código fica **exactamente igual**
   (`lock.lock()`, `cond.await()`, `cond.signal()`, `lock.unlock()`, ...).

2. **(Opcional)** marcar eventos aplicacionais no sítio que quiserem:

   ```java
   Tracer.note("produziu " + v + " (buffer=" + buffer.size() + ")");
   ```

3. **Correr o programa.** No fim é escrito automaticamente um ficheiro
   `trace.json` na pasta de trabalho (via *shutdown hook*). Também podem forçar
   com `Tracer.get().dump(java.nio.file.Path.of("trace.json"))`.

4. **Abrir `spacetime.html`** no browser (basta fazer duplo-clique). Já vem com um
   exemplo carregado. Carregam no botão **“Abrir trace.json…”** e escolhem o
   ficheiro gerado pelo programa deles. O diagrama actualiza-se.

---

## Instrumentação transparente (agente — sem trocar `ReentrantLock`)

Há uma segunda via em que os alunos **não mudam nada** no código: usam
`java.util.concurrent.locks.ReentrantLock`/`Condition` como sempre, e um
**agente Java** injecta a instrumentação em tempo de carregamento. Basta uma
flag na linha de comandos:

```bash
javac -g -d out demo/BoundedBufferRaw.java          # o -g ajuda a nomear (ver abaixo)
java -javaagent:sdtrace-agent.jar -cp out BoundedBufferRaw
# gera o mesmo trace.json; abrir viz/spacetime.html como habitualmente
```

Ou com o atalho: `./build-agent.sh demo/BoundedBufferRaw.java BoundedBufferRaw`.

O agente reescreve o *bytecode* das classes dos alunos para:

1. **nomear** cada lock/condição no seu local de criação (`new ReentrantLock()`,
   `lock.newCondition()`), e
2. **envolver** as chamadas `lock()`, `unlock()`, `await()`, `signal()` e
   `signalAll()` com o registo dos eventos.

### De onde vêm os nomes

Como não há uma string passada à mão, o nome é inferido da variável ou campo que
guarda o objecto, por esta ordem:

- **campo** (ex.: `private final Lock lock = ...`) → nome do campo (**sempre
  fiável**, está no *bytecode*);
- **variável local** (ex.: `Lock meuLock = ...`) → nome da variável, **desde que
  se compile com `-g`** (a tabela de variáveis locais só é emitida assim; os IDEs
  e o Gradle/Maven já o fazem por omissão);
- **fallback** → `Tipo@Classe:linha` (ex.: `ReentrantLock@BoundedBufferRaw:27`),
  usando o número de linha, que o `javac` mantém por omissão.

No padrão monitor típico da cadeira, o lock e as condições são **campos** do
objecto partilhado, por isso saem com os nomes bonitos (`lock`, `naoVazio`,
`naoCheio`) **sem qualquer flag**.

### Requisitos e âmbito

- Requer **JDK 24+** (o agente usa a *Class-File API* padrão, `java.lang.classfile`,
  final no JDK 24 — **zero dependências externas**). Para quem tiver de ficar no
  **JDK 21 (LTS)**, a via dos *wrappers* acima continua a funcionar.
- Instrumenta as chamadas sobre `java.util.concurrent.locks.*`. Ficam **de fora**
  (documentado, para não induzir em erro): `tryLock`, os `await` com *timeout*
  (`awaitNanos`, `await(t,u)`) e o `synchronized`/`wait`/`notify` (que não passa
  por estas classes). O par `await()`/`awaitUninterruptibly()` sem argumentos é
  coberto.
- Restringir o alvo por prefixo de pacote:
  `-javaagent:sdtrace-agent.jar=include=com.aluno,BoundedBufferRaw`.

### Qual das duas vias usar

- **Wrappers (`TracedLock`)** — funciona em qualquer JDK, dá nomes explícitos e é
  totalmente transparente na leitura do código. Boa para começar.
- **Agente** — não obriga a tocar no código dos alunos (útil para instrumentar
  trabalhos já entregues), mas exige JDK 24+ e alguma disciplina de nomeação
  (campos, ou `-g`). Boa como demonstração de *load-time weaving*.

Como nota didática, a via do agente ilustra bem que os **símbolos de *debug*** são
uma escolha de compilação e que o *bytecode* não carrega os nomes das variáveis
locais por omissão — o mesmo motivo por que uma *stack trace* às vezes mostra
`arg0` em vez do nome real.

---

## Como ler o diagrama

- Cada **coluna** é uma *thread*; o **tempo corre de cima para baixo**.
- A linha fina vertical é a *thread* a executar (sem o lock).
- As **bandas coloridas** são os estados notáveis:
  - **âmbar** — bloqueada à espera de adquirir o lock (entre `lock()` e a posse efectiva);
  - **turquesa (sólida)** — posse **exclusiva**: dentro da secção crítica (`ReentrantLock`, ou o
    *write lock* de um `ReadWriteLock`);
  - **turquesa (hachurada, tracejada)** — posse **partilhada**: dentro do *read lock* de um
    `ReadWriteLock`. Várias *threads* podem ter esta banda **ao mesmo tempo** — é essa
    sobreposição que mostra que os leitores não se excluem entre si;
  - **violeta** — à espera numa variável de condição (dentro de `await()`).
- Os **marcadores** são os eventos (pedir/adquirir/libertar lock, `await` entra/regressa,
  `signal`, marcas). A legenda em cima identifica cada glifo.
- As **setas** são relações de causalidade entre *threads*:
  - **tracejado cinzento** — *handoff* de lock: a libertação por uma *thread*
    “passa” o lock à aquisição por outra;
  - **rosa** — `signal()`/`signalAll()` → regresso do `await()` que acordou.
- Cada evento tem o seu **relógio de Lamport** (L…). Ao passar o rato por cima de
  um evento, o diagrama realça todo o seu **passado causal** e a *tooltip* mostra
  como o relógio foi actualizado (`max(local, causa) + 1`).

Botões úteis:
- **Eixo: Tempo físico / Relógio de Lamport.** No tempo físico vê-se a duração
  real dos bloqueios e os intervalos ociosos; no relógio de Lamport vê-se a ordem
  causal de forma compacta. Alternar entre os dois é um bom exercício.
- Ligar/desligar cada tipo de seta, os relógios e as marcas; e o zoom vertical.

---

## Locks de leitura/escrita (`ReadWriteLock`)

Suportado nas **duas** vias, com o mesmo modelo. O ponto essencial é que
`readLock()` e `writeLock()` devolvem **dois objectos diferentes** — se fossem
tratados como dois locks independentes, a exclusão entre leitores e escritores
ficaria completamente invisível (nem uma seta a explicar por que motivo o
escritor esteve bloqueado). Por isso as duas vistas são registadas como
**vistas do mesmo lock**: partilham o **nome** e distinguem-se pelo **modo**
(`READ` = partilhado, `WRITE` = exclusivo).

**Via wrappers:**

```java
TracedReadWriteLock rw = new TracedReadWriteLock("dados");
rw.readLock().lock();      // posse partilhada
rw.writeLock().lock();     // posse exclusiva
```

**Via agente:** nada a fazer — `new ReentrantReadWriteLock()` é instrumentado tal
como está, e o nome sai do campo (ex.: `rw`).

O exemplo `ReadersWritersDemo` / `ReadersWritersRaw` (o mesmo cenário nas duas
vias) põe 3 leitores e 2 escritores sobre o mesmo lock. No diagrama vê-se:

- os **três leitores sobrepostos** na mesma faixa temporal (bandas hachuradas em
  simultâneo) — não se excluem entre si;
- o escritor **excluído** enquanto eles lá estão, e a seta a ligá-lo à saída do
  **último** leitor;
- as setas do `unlock()` de escrita para **cada** leitor que entrou a seguir.

Os exemplos usam um lock **justo** (`fair = true`) de propósito: com um lock
injusto, novos leitores podem passar à frente de um escritor em espera e provocar
**fome do escritor** — o que também é interessante de mostrar aos alunos, bastando
trocar o `true` por `false` e comparar os dois diagramas.

> Nota: só o *write lock* suporta variáveis de condição. `readLock().newCondition()`
> lança `UnsupportedOperationException` — é assim no Java, e mantemos o mesmo
> comportamento.

---

## Modelo de eventos e de causalidade

O `Tracer` regista todos os eventos numa **ordem total** (contador global,
registo serializado) e atribui a cada um:

- um **relógio de Lamport por *thread*** (incremento local; nos eventos de
  “recepção” faz `max(local, remetente) + 1`);
- um **instante físico** (nanos desde o arranque), para posicionar visualmente;
- eventualmente uma **aresta causal** para outra *thread*.

Duas famílias de arestas são inferidas automaticamente:

1. **Handoff de lock** — quando uma *thread* adquire o lock, liga-se ao evento de
   libertação mais recente desse lock (seja um `unlock()` ou a libertação
   implícita feita por um `await()`), se tiver sido de outra *thread*.
   Com um **`ReadWriteLock`** a regra desdobra-se, porque a posse pode ser partilhada:
   - um **escritor** (posse exclusiva) só entra quando **todos** saíram, por isso depende
     de **todas** as libertações da *coorte* que esvaziou o lock. Com 3 leitores lá dentro,
     o escritor tem **3 arestas a entrar**, não uma;
   - um **leitor** não é bloqueado por outros leitores, por isso liga-se **apenas à saída
     do último escritor**. Nunca se desenham setas leitor→leitor: seriam causalidade
     inventada. A aresta escritor→leitor é a *publicação* do valor escrito, e um mesmo
     `unlock()` de escrita pode gerar **várias** setas — uma por leitor que entrou a seguir.

   Por isso um evento tem um **conjunto** de causas (`causes`), e não uma só. O relógio de
   Lamport faz `max` sobre **todas**: `L = max(local, c1, c2, …) + 1`. Isto não é cosmético
   — ficar só pela última libertação registada **viola a condição de Lamport** sempre que um
   leitor que saiu mais cedo tiver relógio mais alto do que o último a sair (é fácil de
   construir: basta um leitor que fez mais trabalho antes de entrar). O trace continua a
   trazer também o campo `cause` (a primeira causa) para compatibilidade.
2. **`signal` → `await`** — cada `signal()` acorda o primeiro em espera (fila
   FIFO por condição); `signalAll()` acorda todos. Liga-se ao regresso do
   `await()` correspondente.

### Limitações honestas (vale a pena discutir com os alunos)

- O emparelhamento `signal`→`await` é **FIFO e aproximado**: o Java não garante
  qual a *thread* acordada nem modela *spurious wakeups*. Com o padrão correcto
  `while (condição) cond.await();` a lógica do programa continua certa; a seta é
  uma leitura plausível da causalidade, não uma garantia da JVM.
- O registo é serializado num monitor único — introduz uma pequena
  sincronização e um ligeiro **efeito de observação** no *timing*. O eixo de tempo
  físico é, por isso, aproximado (excelente para ver bloqueios, não para
  micro-*benchmark*).
- Uma reentrância no mesmo lock aparece como novo par pedir/adquirir.

---

## Compilar e correr o exemplo

```bash
# a partir da raiz do projecto
mkdir -p out
javac -g -encoding UTF-8 -d out src/pt/sd/trace/*.java demo/BoundedBufferDemo.java
java -cp out BoundedBufferDemo      # gera trace.json
# depois: abrir viz/spacetime.html e carregar o trace.json
```

Ou usar o atalho:

```bash
./build.sh demo/BoundedBufferDemo.java BoundedBufferDemo
```

Para a **via transparente** (agente, JDK 24+), ver a secção
*“Instrumentação transparente”* acima, ou simplesmente:

```bash
./build-agent.sh demo/BoundedBufferRaw.java BoundedBufferRaw
```

---

## Estrutura

```
src/pt/sd/trace/
  Tracer.java              núcleo: eventos, relógios de Lamport, causalidade, JSON
  TracedLock.java          Lock instrumentado (via wrappers)
  TracedCondition.java     Condition instrumentada
  TracedReadWriteLock.java ReadWriteLock instrumentado (vistas de leitura/escrita)
  Hooks.java               pontos de entrada chamados pelo bytecode injectado (via agente)
  agent/
    Agent.java             premain: regista o transformador (-javaagent)
    LockWeaver.java        reescrita de bytecode com a Class-File API (JDK 24+)
demo/
  BoundedBufferDemo.java   produtor/consumidor — via wrappers
  BoundedBufferRaw.java    o mesmo — via agente
  ReadersWritersDemo.java  leitores/escritores — via wrappers
  ReadersWritersRaw.java   o mesmo — via agente
viz/
  spacetime.html           visualizador autónomo (com um exemplo já embutido)
  template.html            o mesmo, com marcador para injectar outro trace
  core.js                  lógica de processamento, isolada e testável em node
docs/
  GUIA-ALUNOS.md           guia de utilização, para entregar aos alunos
build.sh                   via wrappers: compila, corre e injecta o trace (JDK 21+)
build-agent.sh             via agente: compila, empacota e corre com -javaagent (JDK 24+)
sdtrace-agent.jar          artefacto pronto a usar (biblioteca E agente no mesmo ficheiro)
```

### Artefactos versionados

Dois ficheiros gerados estão intencionalmente sob controlo de versões, para que os alunos
os possam usar sem compilar nada:

- `sdtrace-agent.jar` — o *runtime* é compilado com `--release 21` e o agente com
  `--release 24`, pelo que **o mesmo ficheiro serve as duas vias**: como biblioteca em
  JDK 21+, e como agente em JDK 24+.
- `viz/spacetime.html` — visualizador com um exemplo embutido, para abrir sem passos prévios.

Em consequência, executar `build.sh` ou `build-agent.sh` **modifica estes dois ficheiros**
(o JAR é recompilado e o novo *trace* é injectado no visualizador). Para descartar essas
alterações locais: `git checkout -- sdtrace-agent.jar viz/spacetime.html`.

---

## Ideias de extensão para as aulas

- **`synchronized` / `wait` / `notify`**: fornecer um `Monitor` que encapsule um
  objecto com `enter()/exit()/await()/notifyOne()` instrumentados, para os alunos
  compararem os dois estilos no mesmo diagrama.
- **Deadlock**: com dois locks nomeados e aquisição em ordens opostas, o diagrama
  mostra duas *threads* permanentemente em âmbar (bloqueadas) — visualização
  imediata do impasse.
- **Exercício**: dar aos alunos um trace e pedir-lhes que reconstruam a ordem de
  Lamport e identifiquem eventos concorrentes (sem relação causal) — o botão do
  eixo de Lamport serve de correcção.
- **Exportar** o SVG para os diapositivos (o diagrama é SVG puro no DOM).
