const TOOL_NAME = /^[A-Za-z0-9_.:/-]{1,128}$/;
const SHELL_TOOLS = /(^|[/.:_-])(bash|shell|terminal|exec|powershell|cmd)([/.:_-]|$)/i;

export function isValidPermissionToolName(toolName: string): boolean {
  return TOOL_NAME.test(toolName.trim());
}

export function canRememberToolPermission(toolName: string): boolean {
  const normalized = toolName.trim();
  return TOOL_NAME.test(normalized) && !SHELL_TOOLS.test(normalized);
}

export function redactCommandPreview(command?: string): string | undefined {
  if (!command) return undefined;
  return command
    .slice(0, 500)
    .replace(/(api[_-]?key|token|password|secret)\s*[=:]\s*([^\s;&|]+)/gi, '$1=<redacted>')
    .replace(/(--?(?:api[_-]?key|token|password|secret))\s+([^\s;&|]+)/gi, '$1 <redacted>')
    .replace(/bearer\s+[a-z0-9._~-]+/gi, 'Bearer <redacted>');
}
