export function isTodoToolName(toolName?: string) {
  const normalized = (toolName || '').toLowerCase().replace(/[_\s-]/g, '');
  const leafName = normalized.split('/').pop() || normalized;
  return leafName === 'todoread' || leafName === 'todowrite';
}
