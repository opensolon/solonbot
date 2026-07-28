/** 将 Agent 目录转换成可读取的 AGENT.md 路径，并兼容 Windows canonicalize 的扩展路径。 */
export function agentConfigFilePath(agentPath: string): string {
  let normalized = agentPath.trim().replace(/[\\/]+$/, '');
  const upper = normalized.toUpperCase();

  if (upper.startsWith('\\\\?\\UNC\\')) {
    normalized = `\\\\${normalized.slice(8)}`;
  } else if (normalized.startsWith('\\\\?\\')) {
    normalized = normalized.slice(4);
  }

  if (/[\\/]AGENT\.md$/i.test(normalized)) return normalized;
  const separator = normalized.includes('\\') ? '\\' : '/';
  return `${normalized}${separator}AGENT.md`;
}
