/** 网页版国际象棋 AI：浏览器内运行 Stockfish WASM（lite 单线程版），无需服务器。 */
let worker: Worker | null = null;
let chain: Promise<unknown> = Promise.resolve();

const SKILL = [2, 8, 16];
const MOVE_TIME_MS = [150, 500, 1500];

function ensureWorker(): Worker {
  if (worker) return worker;
  const instance = new Worker('stockfish-19-lite-single.js');
  instance.onerror = () => {
    try { instance.terminate(); } catch { /* ignore */ }
    if (worker === instance) worker = null;
  };
  worker = instance;
  return instance;
}

function send(instance: Worker, command: string): void {
  instance.postMessage(command);
}

function waitFor(instance: Worker, predicate: (line: string) => boolean, timeoutMs: number): Promise<string> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      instance.removeEventListener('message', onMessage);
      reject(new Error('stockfish 响应超时'));
    }, timeoutMs);
    const onMessage = (event: MessageEvent) => {
      const line = typeof event.data === 'string' ? event.data : String(event.data);
      if (predicate(line)) {
        clearTimeout(timer);
        instance.removeEventListener('message', onMessage);
        resolve(line);
      }
    };
    instance.addEventListener('message', onMessage);
  });
}

async function requestBestMove(fen: string, difficulty: number): Promise<string> {
  const instance = ensureWorker();
  send(instance, 'setoption name Ponder value false');
  send(instance, `setoption name Skill Level value ${SKILL[Math.min(2, Math.max(0, difficulty))] ?? 8}`);
  send(instance, `position fen ${fen}`);
  const bestmove = waitFor(instance, (line) => line.startsWith('bestmove'), 20_000);
  send(instance, `go movetime ${MOVE_TIME_MS[Math.min(2, Math.max(0, difficulty))] ?? 500}`);
  const line = await bestmove;
  const uci = line.split(/\s+/)[1] ?? '';
  if (!/^[a-h][1-8][a-h][1-8][qrbn]?$/.test(uci)) throw new Error('stockfish 未给出合法着法');
  return uci;
}

export function chessBestMove(fen: string, difficulty: number): Promise<string> {
  const next = chain.then(() => requestBestMove(fen, difficulty), () => requestBestMove(fen, difficulty));
  chain = next.catch(() => undefined);
  return next;
}
