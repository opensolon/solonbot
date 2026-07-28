export interface GoalTask {
  id: string;
  sessionId?: string;
  type: 'GOAL' | string;
  prompt: string;
  enabled: boolean;
  running?: boolean;
  currentIteration?: number;
  maxTokens?: number;
  maxDurationMs?: number;
  lastResult?: string;
  lastExecutedAt?: string;
  goal?: {
    condition?: string;
    status?: string;
    iteration?: number;
    consumedTokens?: number;
    maxTokens?: number;
    maxIterations?: number;
    startedAt?: string;
  };
}

type BackendResult<T> = { code?: number; data?: T; message?: string; description?: string };

function url(port?: number | null, path = '') { return `http://localhost:${port || 4808}${path}`; }

async function result<T>(response: Response): Promise<T> {
  const payload = await response.json().catch(() => ({})) as BackendResult<T>;
  if (!response.ok) throw new Error(payload.message || payload.description || 'Goal 请求失败');
  if (payload.code !== undefined && payload.code !== 200) throw new Error(payload.message || payload.description || 'Goal 操作失败');
  return payload.data as T;
}

async function post<T>(port: number | null | undefined, path: string, fields: Record<string, unknown>) {
  return result<T>(await fetch(url(port, path), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(fields),
  }));
}

export const goalService = {
  async list(port: number | null | undefined, sessionId: string): Promise<GoalTask[]> {
    const tasks = await result<GoalTask[]>(await fetch(url(port, `/desktop/chat/goals/list?sessionId=${encodeURIComponent(sessionId)}`), { cache: 'no-store' }));
    return (Array.isArray(tasks) ? tasks : []).filter(task => String(task.type).toUpperCase() === 'GOAL');
  },
  add(port: number | null | undefined, sessionId: string, input: {
    prompt: string;
    maxTokens?: number;
    maxIterations?: number;
    maxDurationMinutes?: number;
    modelName?: string;
    agent?: string;
    workspace?: string;
    reasoningEffort?: string;
  }) {
    return post<string>(port, '/desktop/chat/goals/add', {
      sessionId,
      prompt: input.prompt.trim(),
      maxTokens: input.maxTokens && input.maxTokens > 0 ? Math.floor(input.maxTokens) : undefined,
      maxIterations: input.maxIterations && input.maxIterations > 0 ? Math.floor(input.maxIterations) : undefined,
      maxDurationMinutes: input.maxDurationMinutes && input.maxDurationMinutes > 0 ? Math.floor(input.maxDurationMinutes) : undefined,
      modelName: input.modelName,
      agent: input.agent,
      workspace: input.workspace,
      reasoningEffort: input.reasoningEffort,
    });
  },
  update(port: number | null | undefined, sessionId: string, taskId: string, input: {
    prompt: string;
    maxTokens: number;
    maxIterations: number;
  }) {
    return post<void>(port, '/desktop/chat/goals/update', {
      sessionId,
      taskId,
      prompt: input.prompt.trim(),
      maxTokens: Math.max(0, Math.floor(input.maxTokens)),
      maxIterations: Math.max(0, Math.floor(input.maxIterations)),
    });
  },
  pause: (port: number | null | undefined, sessionId: string, taskId: string) => post<void>(port, '/desktop/chat/goals/pause', { sessionId, taskId }),
  resume: (port: number | null | undefined, sessionId: string, taskId: string) => post<void>(port, '/desktop/chat/goals/resume', { sessionId, taskId }),
  trigger: (port: number | null | undefined, sessionId: string, taskId: string) => post<void>(port, '/desktop/chat/goals/trigger', { sessionId, taskId }),
  remove: (port: number | null | undefined, sessionId: string, taskId: string) => post<void>(port, '/desktop/chat/goals/remove', { sessionId, taskId }),
};
