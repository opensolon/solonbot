export interface SubagentHint {
  name: string;
  description?: string;
  type?: string;
}

export function isValidSubagentName(name: string) {
  const trimmed = name.trim();
  const length = Array.from(trimmed).length;
  return length > 0 && length <= 64 && /^[\p{L}\p{N}_-]+$/u.test(trimmed);
}

export function extractSubagentHints(hints: SubagentHint[]) {
  const subagents = new Map<string, { name: string; description: string; enabled: true }>();

  for (const hint of hints) {
    if (String(hint.type || '').toLowerCase() !== 'subagent') continue;
    const name = String(hint.name || '').trim();
    if (!isValidSubagentName(name) || subagents.has(name)) continue;
    subagents.set(name, {
      name,
      description: String(hint.description || ''),
      enabled: true,
    });
  }

  return Array.from(subagents.values());
}
