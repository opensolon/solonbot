import { useState, useEffect, useRef, useCallback } from 'react';
import { fileService } from '../services/fileService';
import { settingsService } from '../services/settingsService';
import { backendService } from '../services/backendService';
import { setBackendPort as setChatBackendPort } from '../components/ChatView';
import type { BackendStatus } from '../components/layout/StatusBar';

type HeartbeatWorkerMessage = {
  type?: string;
  ok?: boolean;
  port?: number;
};

const HEARTBEAT_INTERVAL_MS = 30000;
const HEARTBEAT_TIMEOUT_MS = 2500;

export function useBackend() {
  const backendPortRef = useRef<number>(4808);
  const [backendPort, setBackendPortState] = useState<number | null>(null);
  const [backendStatus, setBackendStatus] = useState<BackendStatus>('connecting');
  const startPromiseRef = useRef<Promise<void> | null>(null);
  const startPortRef = useRef<number | null>(null);
  const startWorkspaceRef = useRef<string>('');
  const lastWorkspaceRef = useRef<string>('');
  const lastConnectedAtRef = useRef<number>(0);
  const failedProbeCountRef = useRef<number>(0);
  const heartbeatWorkerRef = useRef<Worker | null>(null);

  const configureHeartbeat = useCallback((port: number) => {
    backendPortRef.current = port;
    heartbeatWorkerRef.current?.postMessage({
      type: 'configure',
      port,
      intervalMs: HEARTBEAT_INTERVAL_MS,
      timeoutMs: HEARTBEAT_TIMEOUT_MS,
    });
  }, []);

  const markConnected = useCallback((port: number) => {
    lastConnectedAtRef.current = Date.now();
    failedProbeCountRef.current = 0;
    backendPortRef.current = port;
    setBackendPortState(port);
    setBackendStatus('connected');
    setChatBackendPort(port);
  }, []);

  const markDisconnected = useCallback(() => {
    setBackendPortState(null);
    setBackendStatus('disconnected');
    setChatBackendPort(null);
  }, []);

  const markProbeFailed = useCallback(() => {
    failedProbeCountRef.current += 1;
    const lastConnectedAt = lastConnectedAtRef.current;
    const hasRecentSuccess = lastConnectedAt > 0 && Date.now() - lastConnectedAt < 90_000;

    if (failedProbeCountRef.current >= 3 && !hasRecentSuccess) {
      setBackendStatus(prev => prev === 'connecting' ? 'connecting' : 'disconnected');
    }
  }, []);

  useEffect(() => {
    let disposed = false;
    const worker = new Worker(new URL('../workers/backendHeartbeatWorker.ts', import.meta.url), { type: 'module' });
    heartbeatWorkerRef.current = worker;

    worker.onmessage = (event: MessageEvent<HeartbeatWorkerMessage>) => {
      if (disposed) return;
      const message = event.data;
      if (message?.type !== 'backend-probe-result') return;

      const port = typeof message.port === 'number' && message.port > 0
        ? message.port
        : backendPortRef.current;
      if (message.ok) {
        markConnected(port);
      } else {
        markProbeFailed();
      }
    };

    configureHeartbeat(backendPortRef.current);
    return () => {
      disposed = true;
      worker.postMessage({ type: 'stop' });
      worker.terminate();
      if (heartbeatWorkerRef.current === worker) {
        heartbeatWorkerRef.current = null;
      }
    };
  }, [configureHeartbeat, markConnected, markProbeFailed]);

  const startBackend = useCallback(async (cliPort: number, onSettingsUpdate?: (updater: (prev: any) => any) => void, workspacePath?: string | null) => {
    const workspace = workspacePath?.trim() || '';
    if (startPromiseRef.current && startPortRef.current === cliPort && startWorkspaceRef.current === workspace) {
      await fileService.writeLog(`Backend start already in flight on port ${cliPort} for workspace ${workspace || 'user-home'}, reusing pending request`);
      return startPromiseRef.current;
    }
    if (startPromiseRef.current) {
      await fileService.writeLog(`Waiting for the pending backend start before switching to workspace ${workspace || 'user-home'}`);
      await startPromiseRef.current;
    }

    setBackendStatus('connecting');
    configureHeartbeat(cliPort);
    fileService.writeLog(`Starting backend flow on port ${cliPort}, workspace=${workspace || 'user-home'}`);

    const startPromise = (async () => {
      try {
        const port = await backendService.start(workspace, cliPort);
        if (port) {
          lastWorkspaceRef.current = workspace;
          markConnected(port);

          const cliConfig = await fileService.readGlobalChatModel();
          if (cliConfig && cliConfig.apiUrl && onSettingsUpdate) {
            onSettingsUpdate(prev => {
              const seeded = settingsService.ensureConfiguredProvider(prev.providers, cliConfig);
              const baseSettings = seeded.changed
                ? {
                  ...prev,
                  providers: seeded.providers,
                  activeProviderId: prev.activeProviderId || seeded.providerId,
                }
                : prev;

              if (seeded.changed) {
                void settingsService.save(baseSettings);
              }
              return baseSettings;
            });

            const availableModels = await settingsService.fetchModelsFromBackend(
              port,
              cliConfig.apiUrl,
              cliConfig.apiKey,
              cliConfig.provider,
              cliConfig.model,
            );
            if (availableModels) {
              onSettingsUpdate(prev => {
                const merged = settingsService.mergeFetchedModelsIntoConfiguredProvider(prev.providers, cliConfig, availableModels);
                if (!merged.changed) return prev;
                const updated = {
                  ...prev,
                  providers: merged.providers,
                  activeProviderId: prev.activeProviderId || merged.providerId,
                };
                void settingsService.save(updated);
                return updated;
              });
            }
          }
        } else {
          markDisconnected();
        }
      } catch {
        markDisconnected();
      } finally {
        if (startPromiseRef.current === startPromise) {
          startPromiseRef.current = null;
          startPortRef.current = null;
          startWorkspaceRef.current = '';
        }
      }
    })();

    startPromiseRef.current = startPromise;
    startPortRef.current = cliPort;
    startWorkspaceRef.current = workspace;

    return startPromise;
  }, [configureHeartbeat, markConnected, markDisconnected]);

  useEffect(() => { setChatBackendPort(backendPort); }, [backendPort]);

  const updateWorkspaceForChat = useCallback((_path: string | null) => {
    if (backendPortRef.current) { setChatBackendPort(backendPortRef.current); }
  }, []);

  const reconnectBackend = useCallback(async (onSettingsUpdate?: (updater: (prev: any) => any) => void) => {
    const port = backendPortRef.current;
    await startBackend(port, onSettingsUpdate, lastWorkspaceRef.current);
  }, [startBackend]);

  return { backendPort, backendPortRef, backendStatus, startBackend, reconnectBackend, updateWorkspaceForChat };
}
