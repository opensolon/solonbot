export interface MemoryEntry {
  key: string;
  content: string;
  importance: number;
  time?: string;
}

type BackendResult<T> = { code?: number; data?: T; message?: string; description?: string };

const MAX_KEY_LENGTH = 128;
const MAX_CONTENT_LENGTH = 100_000;

function baseUrl(port?: number | null) {
  return `http://localhost:${port || 4808}`;
}

async function readResult<T>(response: Response): Promise<T> {
  if (!response.ok) throw new Error('后端请求失败');
  const result = await response.json() as BackendResult<T>;
  if (result.code !== undefined && result.code !== 200) {
    throw new Error(result.message || result.description || '操作失败');
  }
  return result.data as T;
}

export function validateMemoryEntry(entry: Pick<MemoryEntry, 'key' | 'content' | 'importance'>) {
  const key = entry.key.trim();
  const content = entry.content.trim();
  if (!key) return 'Key 不能为空';
  if (key.length > MAX_KEY_LENGTH) return `Key 不能超过 ${MAX_KEY_LENGTH} 个字符`;
  if (!content) return '内容不能为空';
  if (content.length > MAX_CONTENT_LENGTH) return '内容过长';
  if (!Number.isInteger(entry.importance) || entry.importance < 1 || entry.importance > 10) return '重要度需为 1-10';
  return null;
}

export const memoryService = {
  async list(port?: number | null): Promise<MemoryEntry[]> {
    const response = await fetch(`${baseUrl(port)}/web/chat/memory/list`, { cache: 'no-store' });
    const data = await readResult<MemoryEntry[]>(response);
    return (Array.isArray(data) ? data : []).sort((left, right) =>
      (right.importance || 0) - (left.importance || 0)
      || String(right.time || '').localeCompare(String(left.time || ''))
    );
  },

  async get(port: number | null | undefined, key: string): Promise<MemoryEntry> {
    const response = await fetch(`${baseUrl(port)}/web/chat/memory/get?key=${encodeURIComponent(key)}`, { cache: 'no-store' });
    return readResult<MemoryEntry>(response);
  },

  async save(port: number | null | undefined, entry: Pick<MemoryEntry, 'key' | 'content' | 'importance'>): Promise<void> {
    const validationError = validateMemoryEntry(entry);
    if (validationError) throw new Error(validationError);
    const body = new URLSearchParams({
      key: entry.key.trim(),
      content: entry.content.trim(),
      importance: String(entry.importance),
    });
    const response = await fetch(`${baseUrl(port)}/web/chat/memory/save`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body,
    });
    await readResult<unknown>(response);
  },

  async remove(port: number | null | undefined, key: string): Promise<void> {
    const normalizedKey = key.trim();
    if (!normalizedKey || normalizedKey.length > MAX_KEY_LENGTH) throw new Error('无效的 Key');
    const body = new URLSearchParams({ key: normalizedKey });
    const response = await fetch(`${baseUrl(port)}/web/chat/memory/remove`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
      body,
    });
    await readResult<unknown>(response);
  },
};
