// Lógica de processamento do trace — partilhada entre o teste (node) e o HTML.
// Não depende do DOM.

// Estados de uma thread ao longo do tempo.
const STATE = {
  RUNNING: "RUNNING",     // a executar, sem o lock
  HOLDING: "HOLDING",     // posse EXCLUSIVA do lock (secção crítica)
  HOLDING_SHARED: "HOLDING_SHARED", // posse PARTILHADA (read lock de um RW): vários ao mesmo tempo
  BLOCKED: "BLOCKED",     // à espera de adquirir o lock
  WAITING: "WAITING",     // à espera numa variável de condição
  TERMINATED: "TERMINATED"
};

// Ordena por seq (ordem total de registo).
function bySeq(a, b) { return a.seq - b.seq; }

// Lista de threads pela ordem de primeira aparição.
function threadOrder(events) {
  const seen = [];
  const set = new Set();
  for (const e of events.slice().sort(bySeq)) {
    if (!set.has(e.thread)) { set.add(e.thread); seen.push(e.thread); }
  }
  return seen;
}

// Devolve, por thread, a lista de segmentos de estado:
//   { state, a, b }  onde a/b são valores no eixo escolhido (t físico ou lamport).
// axisKey = "t" (nanos) ou "lamport".
function stateSegments(events, axisKey) {
  const byThread = new Map();
  for (const e of events) {
    if (!byThread.has(e.thread)) byThread.set(e.thread, []);
    byThread.get(e.thread).push(e);
  }
  const result = new Map();
  for (const [thread, evs] of byThread) {
    evs.sort(bySeq);
    const segs = [];
    let state = STATE.RUNNING;
    let mark = evs[0][axisKey];
    const push = (until) => {
      if (until > mark) segs.push({ state, a: mark, b: until });
      mark = until;
    };
    for (const e of evs) {
      const at = e[axisKey];
      switch (e.kind) {
        case "LOCK_REQUEST":  push(at); state = STATE.BLOCKED; break;
        case "LOCK_ACQUIRED": push(at); state = (e.mode === "READ") ? STATE.HOLDING_SHARED : STATE.HOLDING; break;
        case "AWAIT_BEGIN":   push(at); state = STATE.WAITING; break;
        case "AWAIT_WAKEUP":  push(at); state = STATE.HOLDING; break;
        case "LOCK_RELEASED": push(at); state = STATE.RUNNING; break;
        case "THREAD_END":    push(at); state = STATE.TERMINATED; break;
        default: /* NOTE, SIGNAL, SIGNAL_ALL: não muda o estado */ break;
      }
    }
    result.set(thread, { segments: segs, last: evs[evs.length - 1] });
  }
  return result;
}

// Amplitude do eixo escolhido.
function axisRange(events, axisKey) {
  let lo = Infinity, hi = -Infinity;
  for (const e of events) { lo = Math.min(lo, e[axisKey]); hi = Math.max(hi, e[axisKey]); }
  return [lo, hi];
}

// Índice seq -> evento, para resolver causas.
function indexBySeq(events) {
  const m = new Map();
  for (const e of events) m.set(e.seq, e);
  return m;
}

if (typeof module !== "undefined") {
  module.exports = { STATE, threadOrder, stateSegments, axisRange, indexBySeq, bySeq };
}
