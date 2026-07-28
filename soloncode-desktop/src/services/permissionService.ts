import { db, type DbPermissionAudit, type PermissionAuditAction } from '../db';
import { canRememberToolPermission, isValidPermissionToolName, redactCommandPreview } from '../utils/permissionPolicy';

function normalizeTool(toolName: string): string {
  const value = toolName.trim();
  if (!isValidPermissionToolName(value)) throw new Error('工具名称无效');
  return value;
}

function policyId(workspacePath: string, toolName: string): string {
  return `${workspacePath.replace(/\\/g, '/').toLowerCase()}\n${toolName.toLowerCase()}`;
}

export const permissionService = {
  canRemember(toolName: string): boolean {
    return canRememberToolPermission(toolName);
  },

  async isAlwaysAllowed(workspacePath: string, toolName: string): Promise<boolean> {
    if (!workspacePath || !canRememberToolPermission(toolName)) return false;
    return Boolean(await db.permissionPolicies.get(policyId(workspacePath, toolName)));
  },

  async allowAlways(workspacePath: string, toolName: string): Promise<void> {
    const normalized = normalizeTool(toolName);
    if (!workspacePath || !canRememberToolPermission(normalized)) throw new Error('命令执行类工具不能永久放行');
    await db.permissionPolicies.put({
      id: policyId(workspacePath, normalized),
      workspacePath,
      toolName: normalized,
      createdAt: new Date().toISOString(),
    });
  },

  async audit(input: {
    sessionId: string;
    workspacePath: string;
    toolName: string;
    action: PermissionAuditAction;
    command?: string;
  }): Promise<void> {
    const row: DbPermissionAudit = {
      sessionId: input.sessionId,
      workspacePath: input.workspacePath,
      toolName: normalizeTool(input.toolName),
      action: input.action,
      commandPreview: redactCommandPreview(input.command),
      createdAt: new Date().toISOString(),
    };
    await db.permissionAudits.add(row);
    const count = await db.permissionAudits.count();
    if (count > 500) {
      const ids = (await db.permissionAudits.orderBy('id').limit(count - 500).primaryKeys()) as number[];
      await db.permissionAudits.bulkDelete(ids);
    }
  },

  listAudits(limit = 50): Promise<DbPermissionAudit[]> {
    return db.permissionAudits.orderBy('id').reverse().limit(limit).toArray();
  },

  listPolicies(workspacePath?: string) {
    return workspacePath
      ? db.permissionPolicies.where('workspacePath').equals(workspacePath).toArray()
      : db.permissionPolicies.toArray();
  },

  removePolicy(id: string): Promise<void> {
    return db.permissionPolicies.delete(id);
  },
};
