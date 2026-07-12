// Trace processing logic — shared between the test (node) and the HTML.
// Does not depend on the DOM.

// States of a thread over time.
const STATE = {
  RUNNING: "RUNNING",     // running, without the lock
  HOLDING: "HOLDING",     // EXCLUSIVE ownership of the lock (critical section)
  HOLDING_SHARED: "HOLDING_SHARED", // SHARED ownership (read lock of an RW lock): several at once
  BLOCKED: "BLOCKED",     // waiting to acquire the lock
  WAITING: "WAITING",     // waiting on a condition variable
  TERMINATED: "TERMINATED"
};

// Sorts by seq (total recording order).
function bySeq(a, b) { return a.seq - b.seq; }

// List of threads in order of first appearance.
function threadOrder(events) {
  const seen = [];
  const set = new Set();
  for (const e of events.slice().sort(bySeq)) {
    if (!set.has(e.thread)) { set.add(e.thread); seen.push(e.thread); }
  }
  return seen;
}

// Returns, per thread, the list of state segments:
//   { state, a, b }  where a/b are values on the chosen axis (physical t or lamport).
// axisKey = "t" (nanos) or "lamport".
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
        default: /* NOTE, SIGNAL, SIGNAL_ALL: doesn't change the state */ break;
      }
    }
    result.set(thread, { segments: segs, last: evs[evs.length - 1] });
  }
  return result;
}

// Range of the chosen axis.
function axisRange(events, axisKey) {
  let lo = Infinity, hi = -Infinity;
  for (const e of events) { lo = Math.min(lo, e[axisKey]); hi = Math.max(hi, e[axisKey]); }
  return [lo, hi];
}

// Index seq -> event, to resolve causes.
function indexBySeq(events) {
  const m = new Map();
  for (const e of events) m.set(e.seq, e);
  return m;
}

if (typeof module !== "undefined") {
  module.exports = { STATE, threadOrder, stateSegments, axisRange, indexBySeq, bySeq };
}
