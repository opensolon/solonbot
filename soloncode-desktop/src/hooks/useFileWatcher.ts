/**
 * 文件监听 Hook - 监听工作区文件变化并自动刷新
 * @author bai
 */
import { useEffect, useRef } from 'react';
import { fileService } from '../services/fileService';

interface UseFileWatcherOptions {
  /** 工作区路径 */
  workspacePath: string | null;
  /** 文件变化回调 */
  onChange?: (paths: string[]) => void;
  /** 是否启用 */
  enabled?: boolean;
}

/**
 * 监听工作区文件变化
 * - Tauri 环境使用原生文件监听
 * - 浏览器环境使用轮询
 */
export function useFileWatcher({
  workspacePath,
  onChange,
  enabled = true,
}: UseFileWatcherOptions) {
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  useEffect(() => {
    if (!workspacePath || !enabled) return;

    let disposed = false;
    let unwatch: (() => void) | undefined;
    void fileService.watchPath(
      workspacePath,
      (event) => {
        const paths = event.paths || [];
        if (paths.length > 0) onChangeRef.current?.(paths);
      },
      { recursive: true },
    ).then(stopWatching => {
      if (disposed) {
        stopWatching();
        return;
      }
      unwatch = stopWatching;
    }).catch(err => {
      if (!disposed) console.error('[useFileWatcher] 监听失败:', err);
    });

    return () => {
      disposed = true;
      unwatch?.();
    };
  }, [workspacePath, enabled]);
}
