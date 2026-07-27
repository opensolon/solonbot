import { FormEvent, useCallback, useEffect, useMemo, useState } from 'react';
import { Icon } from './common/Icon';
import { goalService, type GoalTask } from '../services/goalService';
import './GoalModeBar.css';

const ACTIVE_GOAL_STATUSES = new Set(['PURSUING', 'PAUSED', 'BLOCKED']);
const TERMINAL_GOAL_STATUSES = new Set(['ACHIEVED', 'BUDGET_LIMITED', 'ITERATION_LIMITED', 'FAILED']);

function goalStatus(task: GoalTask, streamActive = false) {
  const status = String(task.goal?.status || (task.enabled ? 'READY' : 'DISABLED')).toUpperCase();
  // goal_update 可能在当前模型流结束前先写入终态；运行中的任务仍应显示“执行中”。
  if ((task.running || streamActive) && TERMINAL_GOAL_STATUSES.has(status)) return 'PURSUING';
  return task.running && status === 'READY' ? 'PURSUING' : status;
}

function statusLabel(status: string) {
  const labels: Record<string, string> = {
    PURSUING: '执行中',
    PAUSED: '已暂停',
    ACHIEVED: '已完成',
    BLOCKED: '受阻',
    FAILED: '失败',
    BUDGET_LIMITED: 'Token 已耗尽',
    ITERATION_LIMITED: '轮次已耗尽',
    READY: '待执行',
    DISABLED: '已停用',
  };
  return labels[status] || status;
}

function isSavedSession(sessionId?: string) {
  return Boolean(sessionId && !sessionId.startsWith('temp-') && !sessionId.startsWith('pending-'));
}

function nonNegativeInteger(value: string, maximum: number) {
  return Math.min(maximum, Math.max(0, Math.floor(Number(value) || 0)));
}

interface GoalModeBarProps {
  selected: boolean;
  draftObjective?: string;
  streamActive?: boolean;
  backendPort?: number | null;
  sessionId?: string;
  maxTokens: number;
  maxIterations: number;
  onMaxTokensChange: (value: number) => void;
  onMaxIterationsChange: (value: number) => void;
  onCloseDraft?: () => void;
  onActiveGoalChange?: (active: boolean) => void;
}

export function GoalModeBar({
  selected,
  draftObjective = '',
  streamActive = false,
  backendPort,
  sessionId,
  maxTokens,
  maxIterations,
  onMaxTokensChange,
  onMaxIterationsChange,
  onCloseDraft,
  onActiveGoalChange,
}: GoalModeBarProps) {
  const [tasks, setTasks] = useState<GoalTask[]>([]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [showSettings, setShowSettings] = useState(false);
  const [settingsObjective, setSettingsObjective] = useState('');
  const [settingsTokens, setSettingsTokens] = useState(0);
  const [settingsIterations, setSettingsIterations] = useState(0);
  const validSession = isSavedSession(sessionId);

  const refresh = useCallback(async () => {
    if (!validSession || !sessionId) {
      setTasks([]);
      return;
    }
    try {
      setTasks(await goalService.list(backendPort, sessionId));
      setMessage('');
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Goal 状态加载失败');
    }
  }, [backendPort, sessionId, validSession]);

  useEffect(() => {
    void refresh();
    const timer = window.setInterval(() => void refresh(), 5000);
    const handleGoalUpdate = (event: Event) => {
      const updatedSessionId = (event as CustomEvent<{ sessionId?: string }>).detail?.sessionId;
      if (!updatedSessionId || updatedSessionId === sessionId) void refresh();
    };
    window.addEventListener('soloncode-goal-updated', handleGoalUpdate);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener('soloncode-goal-updated', handleGoalUpdate);
    };
  }, [refresh, sessionId]);

  const activeGoal = useMemo(
    () => tasks.find(task => ACTIVE_GOAL_STATUSES.has(goalStatus(task))),
    [tasks],
  );
  const latestGoal = activeGoal || tasks[tasks.length - 1];
  const displayedGoal = draftObjective ? undefined : latestGoal;
  const latestStatus = displayedGoal ? goalStatus(displayedGoal, streamActive) : '';
  const operationStatus = displayedGoal ? goalStatus(displayedGoal) : '';
  const canEditGoal = Boolean(displayedGoal && ACTIVE_GOAL_STATUSES.has(operationStatus));

  useEffect(() => {
    onActiveGoalChange?.(Boolean(activeGoal));
  }, [activeGoal, onActiveGoalChange]);

  useEffect(() => {
    setShowSettings(false);
    setMessage('');
  }, [displayedGoal?.id, sessionId]);

  function openSettings() {
    if (showSettings) {
      setShowSettings(false);
      return;
    }
    if (displayedGoal) {
      setSettingsObjective(displayedGoal.goal?.condition || displayedGoal.prompt);
      setSettingsTokens(displayedGoal.goal?.maxTokens || displayedGoal.maxTokens || 0);
      setSettingsIterations(displayedGoal.goal?.maxIterations || 0);
    } else {
      setSettingsTokens(maxTokens);
      setSettingsIterations(maxIterations);
    }
    setMessage('');
    setShowSettings(true);
  }

  async function operate(action: 'pause' | 'resume' | 'remove') {
    const target = displayedGoal;
    if (!target || !sessionId || busy) return;
    if (action === 'remove' && ACTIVE_GOAL_STATUSES.has(operationStatus)
      && !window.confirm('关闭将停止并移除当前 Goal，但不会删除对话记录。是否继续？')) return;
    setBusy(true);
    setMessage('');
    try {
      await goalService[action](backendPort, sessionId, target.id);
      setShowSettings(false);
      await refresh();
      window.dispatchEvent(new CustomEvent('soloncode-goal-updated', { detail: { sessionId } }));
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Goal 操作失败');
    } finally {
      setBusy(false);
    }
  }

  async function saveSettings(event: FormEvent) {
    event.preventDefault();
    const tokens = nonNegativeInteger(String(settingsTokens), 1_000_000_000);
    const iterations = nonNegativeInteger(String(settingsIterations), 10_000);
    if (!displayedGoal) {
      onMaxTokensChange(tokens);
      onMaxIterationsChange(iterations);
      setShowSettings(false);
      return;
    }
    const objective = settingsObjective.trim();
    if (!sessionId || !objective || busy || !canEditGoal) return;
    setBusy(true);
    setMessage('');
    try {
      await goalService.update(backendPort, sessionId, displayedGoal.id, {
        prompt: objective,
        maxTokens: tokens,
        maxIterations: iterations,
      });
      onMaxTokensChange(tokens);
      onMaxIterationsChange(iterations);
      setShowSettings(false);
      await refresh();
      window.dispatchEvent(new CustomEvent('soloncode-goal-updated', { detail: { sessionId } }));
    } catch (error) {
      setMessage(error instanceof Error ? error.message : 'Goal 设置保存失败');
    } finally {
      setBusy(false);
    }
  }

  if (!selected && !latestGoal) return null;

  const iteration = displayedGoal?.goal?.iteration ?? displayedGoal?.currentIteration ?? 0;
  const displayedMaxIterations = displayedGoal?.goal?.maxIterations || 0;

  return (
    <div className="goal-mode-bar">
      <div className="goal-mode-main">
        <span className="goal-mode-title"><Icon name="goal" size={14} /> Goal</span>
        {displayedGoal ? (
          <>
            <span className="goal-mode-objective" title={displayedGoal.goal?.condition || displayedGoal.prompt}>
              {displayedGoal.goal?.condition || displayedGoal.prompt}
            </span>
            <span className={`goal-mode-status status-${latestStatus.toLowerCase()}`}>{statusLabel(latestStatus)}</span>
            <span className="goal-mode-progress">
              第 {iteration}{displayedMaxIterations ? `/${displayedMaxIterations}` : ''} 轮
              {(displayedGoal.goal?.maxTokens || displayedGoal.maxTokens)
                ? ` · ${displayedGoal.goal?.consumedTokens || 0}/${displayedGoal.goal?.maxTokens || displayedGoal.maxTokens} tk`
                : ''}
            </span>
            {activeGoal && (latestStatus === 'PURSUING' ? (
              <button type="button" disabled={busy} onClick={() => void operate('pause')}>暂停</button>
            ) : (
              <button type="button" disabled={busy} onClick={() => void operate('resume')}>继续</button>
            ))}
            {canEditGoal && (
              <button type="button" className="goal-icon-button" disabled={busy} onClick={openSettings} title="修改目标和限制" aria-label="修改 Goal 设置">
                <Icon name="settings" size={14} />
              </button>
            )}
            <button type="button" className="goal-icon-button danger" disabled={busy} onClick={() => void operate('remove')} title="关闭 Goal" aria-label="关闭 Goal">
              <Icon name="close" size={14} />
            </button>
          </>
        ) : (
          <>
            <span className="goal-mode-objective goal-mode-draft" title={draftObjective}>
              {draftObjective || (validSession ? '输入 /goal 后描述目标' : '创建会话后输入 /goal 描述目标')}
            </span>
            <span className="goal-mode-progress">
              {maxTokens > 0 ? `${maxTokens} tk` : 'Token 不限'} · {maxIterations > 0 ? `${maxIterations} 轮` : '轮次不限'}
            </span>
            <button type="button" className="goal-icon-button" onClick={openSettings} title="设置 Token 和轮次限制" aria-label="设置 Goal 限制">
              <Icon name="settings" size={14} />
            </button>
            <button type="button" className="goal-icon-button danger" onClick={onCloseDraft} title="关闭 Goal 模式" aria-label="关闭 Goal 模式">
              <Icon name="close" size={14} />
            </button>
          </>
        )}
      </div>
      {showSettings && (
        <form className="goal-mode-settings" onSubmit={saveSettings}>
          {displayedGoal && (
            <label className="goal-objective-field">
              <span>目标提示词</span>
              <input
                value={settingsObjective}
                maxLength={20_000}
                disabled={busy}
                onChange={event => setSettingsObjective(event.target.value)}
                aria-label="Goal 目标提示词"
              />
            </label>
          )}
          <label className="goal-budget-field">
            <span>Token</span>
            <input
              type="number"
              min={0}
              max={1_000_000_000}
              value={settingsTokens}
              disabled={busy}
              onChange={event => setSettingsTokens(nonNegativeInteger(event.target.value, 1_000_000_000))}
              aria-label="Goal Token 上限，0 表示不限"
            />
          </label>
          <label className="goal-budget-field">
            <span>轮次</span>
            <input
              type="number"
              min={0}
              max={10_000}
              value={settingsIterations}
              disabled={busy}
              onChange={event => setSettingsIterations(nonNegativeInteger(event.target.value, 10_000))}
              aria-label="Goal 最大轮次，0 表示不限"
            />
          </label>
          <span className="goal-budget-unlimited">0 = 不限</span>
          <button type="submit" className="goal-settings-save" disabled={busy || Boolean(displayedGoal && !settingsObjective.trim())}>保存</button>
          <button type="button" className="goal-settings-cancel" disabled={busy} onClick={() => setShowSettings(false)}>取消</button>
        </form>
      )}
      {message && <div className="goal-mode-message error">{message}</div>}
    </div>
  );
}
