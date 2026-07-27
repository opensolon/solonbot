import { useState, useCallback, useEffect, useMemo, useRef } from 'react';
import {
  getAllConversations, saveConversation, deleteConversation,
  updateConversation, saveLastSessionId, loadLastSessionId,
  migrateConversationsToProjects, reassignMessages,
  getMessageCount, getMessageCountsByConversation,
  UNLINKED_PROJECT,
} from '../db';
import type { Conversation } from '../types';
import { isUnlinkedEmptySession } from '../utils/sessionProject';

export interface Session {
  id: string;
  title: string;
  timestamp: string;
  messageCount: number;
  isPermanent?: boolean;
  workspacePath?: string;
}

export function useSessions(
  activeProjectPath: string | null,
  options?: {
    onSessionIdResolved?: (oldId: string, newId: string) => void;
  },
) {
  const [sessions, setSessions] = useState<Session[]>([]);
  const [currentSessionId, setCurrentSessionId] = useState<string>();
  const [pendingSession, setPendingSession] = useState<Session | null>(null);
  const resolvingSessionsRef = useRef<Map<string, Promise<string>>>(new Map());

  // 初始化加载会话
  useEffect(() => {
    let cancelled = false;
    (async () => {
      await migrateConversationsToProjects();
      const [convs, messageCounts] = await Promise.all([
        getAllConversations(),
        getMessageCountsByConversation(),
      ]);
      const loaded: Session[] = convs.map(c => {
        const id = c.id!.toString();
        return {
          id,
          title: c.title,
          timestamp: c.timestamp,
          messageCount: messageCounts.get(id) || 0,
          isPermanent: c.isPermanent,
          workspacePath: c.workspacePath || UNLINKED_PROJECT,
        };
      });
      if (!cancelled) setSessions(loaded);
    })();
    return () => { cancelled = true; };
  }, []);

  // 恢复项目最后会话
  const restoreLastSession = useCallback(async (projectPath: string) => {
    const lastSessionId = await loadLastSessionId(projectPath);
    if (lastSessionId) {
      setCurrentSessionId(lastSessionId);
    }
  }, []);

  // 保存最后会话
  useEffect(() => {
    if (activeProjectPath && currentSessionId && !currentSessionId.startsWith('temp-')) {
      saveLastSessionId(activeProjectPath, currentSessionId);
    }
  }, [activeProjectPath, currentSessionId]);

  const handleNewSession = useCallback((projectId?: string, _title?: string): string => {
    const currentSession = sessions.find(s => s.id === currentSessionId);
    if (currentSessionId && !currentSessionId.startsWith('temp-') && !currentSession) {
      return '';
    }

    const tempId = `temp-${Date.now()}`;
    // 只有调用方显式传入项目时才关联；恢复的活跃项目不能作为隐式默认值。
    const workspacePath = projectId && projectId !== UNLINKED_PROJECT
      ? projectId
      : UNLINKED_PROJECT;
    const title = _title || '新会话';
    const timestamp = new Date().toISOString();
    setPendingSession({ id: tempId, title, timestamp, messageCount: 0, workspacePath });
    setCurrentSessionId(tempId);
    return tempId;
  }, [currentSessionId, sessions]);

  const handleDeleteSession = useCallback((id: string) => {
    const remaining = sessions.filter(s => s.id !== id);
    setSessions(remaining);
    if (id.startsWith('temp-')) {
      setPendingSession(current => current?.id === id ? null : current);
    } else {
      deleteConversation(id);
    }
    if (currentSessionId === id) {
      setCurrentSessionId(remaining.length > 0 ? remaining[0].id : undefined);
    }
  }, [currentSessionId, sessions]);

  const handleUpdateSessionTitle = useCallback(async (sessionId: string, title: string): Promise<string> => {
    if (!sessionId.startsWith('temp-')) {
      const exists = sessions.find(session => session.id === sessionId);
      if (exists?.title === '新会话') {
        await updateConversation(sessionId, { title });
        setSessions(prev => prev.map(session =>
          session.id === sessionId && session.title === '新会话'
            ? { ...session, title }
            : session
        ));
      }
      return sessionId;
    }

    const resolving = resolvingSessionsRef.current.get(sessionId);
    if (resolving) return resolving;

    const pending = pendingSession?.id === sessionId ? pendingSession : null;
    const sessionWsPath = pending?.workspacePath || UNLINKED_PROJECT;
    const resolvePromise = (async () => {
      try {
        const dbId = await saveConversation({
          title,
          timestamp: pending?.timestamp || new Date().toISOString(),
          status: 'active',
          workspacePath: sessionWsPath,
        });
        const realId = dbId.toString();
        await reassignMessages(sessionId, realId);
        const messageCount = await getMessageCount(realId);
        const persistedSession: Session = {
          id: realId,
          title,
          timestamp: new Date().toISOString(),
          messageCount,
          workspacePath: sessionWsPath,
        };

        setSessions(prev => [
          persistedSession,
          ...prev.filter(session => session.id !== sessionId && session.id !== realId),
        ]);
        setPendingSession(current => current?.id === sessionId ? null : current);
        options?.onSessionIdResolved?.(sessionId, realId);

        // 让发送方先把临时 ID 切换为真实 ID，再触发会话视图更新。
        setTimeout(() => {
          setCurrentSessionId(current => current === sessionId ? realId : current);
        }, 0);
        return realId;
      } catch (err) {
        console.error('[Sessions] 保存新会话失败:', err);
        return sessionId;
      } finally {
        resolvingSessionsRef.current.delete(sessionId);
      }
    })();

    resolvingSessionsRef.current.set(sessionId, resolvePromise);
    return resolvePromise;
  }, [activeProjectPath, pendingSession, sessions, options]);

  const incrementSessionMessageCount = useCallback((sessionId: string, count = 1) => {
    setSessions(prev => prev.map(session =>
      session.id === sessionId
        ? { ...session, messageCount: session.messageCount + count, timestamp: new Date().toISOString() }
        : session
    ));
  }, []);

  const linkCurrentEmptySessionToProject = useCallback(async (projectPath: string): Promise<boolean> => {
    if (!projectPath || projectPath === UNLINKED_PROJECT) return false;

    if (!currentSessionId) {
      return Boolean(handleNewSession(projectPath));
    }

    if (currentSessionId.startsWith('temp-')) {
      const pending = pendingSession?.id === currentSessionId ? pendingSession : null;
      if (!pending || !isUnlinkedEmptySession(pending)) return false;
      setPendingSession(current => current?.id === currentSessionId
        ? { ...current, workspacePath: projectPath }
        : current
      );
      return true;
    }

    const session = sessions.find(item => item.id === currentSessionId);
    if (!session || !isUnlinkedEmptySession(session)) return false;

    await updateConversation(currentSessionId, { workspacePath: projectPath });
    setSessions(current => current.map(item => item.id === currentSessionId
      ? { ...item, workspacePath: projectPath }
      : item
    ));
    return true;
  }, [currentSessionId, handleNewSession, pendingSession, sessions]);

  const remapProjectPath = useCallback((oldPath: string, newPath: string) => {
    setSessions(prev => prev.map(session =>
      session.workspacePath === oldPath
        ? { ...session, workspacePath: newPath }
        : session
    ));
    setPendingSession(current => current?.workspacePath === oldPath
      ? { ...current, workspacePath: newPath }
      : current
    );
  }, []);

  const currentConversation: Conversation = useMemo(() => {
    const session = sessions.find(s => s.id === currentSessionId);
    const currentPendingSession = pendingSession?.id === currentSessionId ? pendingSession : undefined;
    return {
      id: currentSessionId,
      title: session?.title || currentPendingSession?.title || '新会话',
      timestamp: new Date().toLocaleString(),
      status: 'active',
      workspacePath: session?.workspacePath || currentPendingSession?.workspacePath,
    };
  }, [currentSessionId, pendingSession, sessions]);

  return {
    sessions,
    setSessions,
    currentSessionId,
    setCurrentSessionId,
    currentConversation,
    handleNewSession,
    handleDeleteSession,
    handleUpdateSessionTitle,
    incrementSessionMessageCount,
    linkCurrentEmptySessionToProject,
    remapProjectPath,
    restoreLastSession,
  };
}
