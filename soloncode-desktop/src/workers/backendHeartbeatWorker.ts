/// <reference lib="webworker" />

export {};

type HeartbeatCommand =
  | { type: 'configure'; port: number; intervalMs?: number; timeoutMs?: number }
  | { type: 'probe'; port?: number; requestId?: string }
  | { type: 'stop' };

type HeartbeatResult = {
  type: 'backend-probe-result';
  ok: boolean;
  port: number;
  requestId?: string;
  error?: string;
};

let activePort = 4808;
let intervalMs = 30000;
let timeoutMs = 2500;
let timerId: number | undefined;
let inFlight = false;

function postProbeResult(result: HeartbeatResult) {
  self.postMessage(result);
}

function stopTimer() {
  if (timerId !== undefined) {
    self.clearInterval(timerId);
    timerId = undefined;
  }
}

function startTimer() {
  stopTimer();
  timerId = self.setInterval(() => {
    void probeBackend(activePort);
  }, intervalMs);
}

async function probeBackend(port: number, requestId?: string) {
  if (inFlight) return;
  inFlight = true;

  const controller = new AbortController();
  const timeout = self.setTimeout(() => controller.abort(), timeoutMs);

  try {
    const response = await fetch(`http://localhost:${port}/desktop/version`, {
      cache: 'no-store',
      signal: controller.signal,
    });

    let ok = response.ok;
    if (ok) {
      try {
        const payload = await response.json();
        ok = payload?.code === 200 && Boolean(payload?.data?.version);
      } catch {
        ok = false;
      }
    }

    postProbeResult({ type: 'backend-probe-result', ok, port, requestId });
  } catch (error) {
    postProbeResult({
      type: 'backend-probe-result',
      ok: false,
      port,
      requestId,
      error: error instanceof Error ? error.message : String(error),
    });
  } finally {
    self.clearTimeout(timeout);
    inFlight = false;
  }
}

self.onmessage = (event: MessageEvent<HeartbeatCommand>) => {
  const command = event.data;
  if (!command || typeof command.type !== 'string') return;

  if (command.type === 'stop') {
    stopTimer();
    return;
  }

  if (command.type === 'configure') {
    activePort = command.port || 4808;
    intervalMs = command.intervalMs || intervalMs;
    timeoutMs = command.timeoutMs || timeoutMs;
    startTimer();
    void probeBackend(activePort);
    return;
  }

  if (command.type === 'probe') {
    void probeBackend(command.port || activePort, command.requestId);
  }
};
