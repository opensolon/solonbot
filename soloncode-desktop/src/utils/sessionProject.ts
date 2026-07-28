export interface ProjectLinkableSession {
  messageCount: number;
  workspacePath?: string;
}

/** 只有没有消息、且尚未关联项目的会话，才能由项目管理入口补充关联。 */
export function isUnlinkedEmptySession(session: ProjectLinkableSession): boolean {
  const isUnlinked = !session.workspacePath || session.workspacePath === '__unlinked__';
  return session.messageCount === 0 && isUnlinked;
}
