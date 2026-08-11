import { useState, useEffect, useCallback, useRef } from 'react';
import { gitService, type GitStatus, type DiffLine } from '../services/gitService';

const emptyGitStatus: GitStatus = {
  branch: '',
  ahead: 0,
  behind: 0,
  files: [],
};

const EMPTY_DIFF_LINES: DiffLine[] = [];
const AUTO_DIFF_DELAY_MS = 180;
const AUTO_DIFF_MAX_CONTENT_LENGTH = 1024 * 1024;

export function useGit(
  activeProjectPath: string | null,
  activeFilePath: string | null,
  gitPanelVisible: boolean,
  activeFileContentLength: number = 0,
  activeFileIsImage: boolean = false,
) {
  const [gitStatus, setGitStatus] = useState<GitStatus>(emptyGitStatus);
  const [diffState, setDiffState] = useState<{ path: string | null; lines: DiffLine[] }>({
    path: null,
    lines: EMPTY_DIFF_LINES,
  });
  const prevFilePathRef = useRef<string | null>(null);
  const prevStatusHashRef = useRef<string>('');
  const diffRequestRef = useRef(0);

  // Never expose the previous model's decorations while a new file is being
  // selected. Applying a large stale decoration set is noticeably expensive.
  const diffLines = diffState.path === activeFilePath
    && !activeFileIsImage
    && activeFileContentLength <= AUTO_DIFF_MAX_CONTENT_LENGTH
    ? diffState.lines
    : EMPTY_DIFF_LINES;

  const refreshGitStatus = useCallback(async () => {
    if (activeProjectPath) {
      const status = await gitService.status(activeProjectPath);
      setGitStatus(status);
    } else {
      setGitStatus(emptyGitStatus);
    }
  }, [activeProjectPath]);

  // 只在 Git 面板可见时轮询
  useEffect(() => {
    if (!gitPanelVisible) return;
    refreshGitStatus();
    const timer = setInterval(refreshGitStatus, 5000);
    return () => clearInterval(timer);
  }, [refreshGitStatus, gitPanelVisible]);

  // 获取当前活跃文件的 git diff — 仅在文件切换或相关文件状态变化时刷新
  useEffect(() => {
    const requestId = ++diffRequestRef.current;
    if (!activeProjectPath || !activeFilePath) {
      prevFilePathRef.current = null;
      return;
    }

    // Images and very large files should not run an automatic line diff while
    // switching tabs. The Git panel can still request their full diff explicitly.
    if (activeFileIsImage || activeFileContentLength > AUTO_DIFF_MAX_CONTENT_LENGTH) {
      prevFilePathRef.current = activeFilePath;
      prevStatusHashRef.current = '';
      return;
    }

    const projectPath = activeProjectPath.replace(/\\/g, '/').replace(/\/$/, '');
    const filePath = activeFilePath.replace(/\\/g, '/');
    if (!filePath.startsWith(`${projectPath}/`)) return;
    const relPath = filePath.slice(projectPath.length + 1);
    const statusHash = gitStatus.files
      .filter(f => f.path === relPath)
      .map(f => `${f.path}:${f.status}:${f.staged}`)
      .join(',');

    if (activeFilePath === prevFilePathRef.current && statusHash === prevStatusHashRef.current) {
      return;
    }

    prevFilePathRef.current = activeFilePath;
    prevStatusHashRef.current = statusHash;

    const timer = window.setTimeout(() => {
      void gitService.diffFile(activeProjectPath, relPath)
        .then(lines => {
          if (diffRequestRef.current === requestId) {
            setDiffState({ path: activeFilePath, lines });
          }
        })
        .catch(() => {
          if (diffRequestRef.current === requestId) {
            setDiffState({ path: activeFilePath, lines: EMPTY_DIFF_LINES });
          }
        });
    }, AUTO_DIFF_DELAY_MS);

    return () => window.clearTimeout(timer);
  }, [activeProjectPath, activeFilePath, activeFileContentLength, activeFileIsImage, gitStatus]);

  return {
    gitStatus,
    diffLines,
    refreshGitStatus,
    setGitStatus,
  };
}
