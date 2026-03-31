import { useState } from 'react';
import { Icon } from '../common/Icon';
import './GitPanel.css';

interface GitFile {
  path: string;
  status: 'modified' | 'added' | 'deleted' | 'untracked';
  staged: boolean;
}

export interface GitStatus {
  branch: string;
  ahead: number;
  behind: number;
  files: GitFile[];
}

interface GitPanelProps {
  status: GitStatus;
  onCommit: (message: string) => Promise<void>;
  onStage: (path: string) => Promise<void>;
  onUnstage: (path: string) => Promise<void>;
  onPush: () => Promise<void>;
  onPull: () => Promise<void>;
  onFileClick: (path: string) => void;
}

export function GitPanel({
  status,
  onCommit,
  onStage,
  onUnstage,
  onPush,
  onPull,
  onFileClick
}: GitPanelProps) {
  const [commitMessage, setCommitMessage] = useState('');
  const [isCommitting, setIsCommitting] = useState(false);

  const stagedFiles = status.files.filter(f => f.staged);
  const changedFiles = status.files.filter(f => !f.staged && f.status !== 'untracked');
  const untrackedFiles = status.files.filter(f => f.status === 'untracked');

  async function handleCommit() {
    if (!commitMessage.trim() || stagedFiles.length === 0) return;
    setIsCommitting(true);
    try {
      await onCommit(commitMessage);
      setCommitMessage('');
    } finally {
      setIsCommitting(false);
    }
  }

  return (
    <div className="git-panel">
      <div className="panel-header">
        <span className="panel-title">源代码管理</span>
        <div className="panel-actions">
          <button className="panel-action" title="推送" onClick={onPush}>
            <Icon name="push" size={16} />
          </button>
          <button className="panel-action" title="拉取" onClick={onPull}>
            <Icon name="pull" size={16} />
          </button>
        </div>
      </div>

      <div className="branch-info">
        <Icon name="git" size={14} />
        <span className="branch-name">{status.branch}</span>
        {status.ahead > 0 && <span className="branch-ahead">↑{status.ahead}</span>}
        {status.behind > 0 && <span className="branch-behind">↓{status.behind}</span>}
      </div>

      <div className="commit-area">
        <textarea
          className="commit-input"
          placeholder="提交信息..."
          value={commitMessage}
          onChange={(e) => setCommitMessage(e.target.value)}
          rows={3}
        />
        <button
          className="commit-button"
          onClick={handleCommit}
          disabled={isCommitting || !commitMessage.trim() || stagedFiles.length === 0}
        >
          {isCommitting ? '提交中...' : `提交 (${stagedFiles.length})`}
        </button>
      </div>

      <div className="git-files">
        {stagedFiles.length > 0 && (
          <div className="file-group">
            <div className="group-header">
              <span>已暂存的更改</span>
              <span className="group-count">{stagedFiles.length}</span>
            </div>
            {stagedFiles.map(file => (
              <div key={file.path} className="git-file-item staged">
                <Icon name={file.status} size={14} className={`status-icon ${file.status}`} />
                <span className="file-path" onClick={() => onFileClick(file.path)}>
                  {file.path}
                </span>
                <button className="unstage-btn" onClick={() => onUnstage(file.path)} title="取消暂存">
                  <Icon name="remove" size={14} />
                </button>
              </div>
            ))}
          </div>
        )}

        {changedFiles.length > 0 && (
          <div className="file-group">
            <div className="group-header">
              <span>已更改</span>
              <span className="group-count">{changedFiles.length}</span>
            </div>
            {changedFiles.map(file => (
              <div key={file.path} className="git-file-item">
                <Icon name={file.status} size={14} className={`status-icon ${file.status}`} />
                <span className="file-path" onClick={() => onFileClick(file.path)}>
                  {file.path}
                </span>
                <button className="stage-btn" onClick={() => onStage(file.path)} title="暂存">
                  <Icon name="add" size={14} />
                </button>
              </div>
            ))}
          </div>
        )}

        {untrackedFiles.length > 0 && (
          <div className="file-group">
            <div className="group-header">
              <span>未跟踪</span>
              <span className="group-count">{untrackedFiles.length}</span>
            </div>
            {untrackedFiles.map(file => (
              <div key={file.path} className="git-file-item untracked">
                <Icon name="untracked" size={14} className="status-icon untracked" />
                <span className="file-path" onClick={() => onFileClick(file.path)}>
                  {file.path}
                </span>
                <button className="stage-btn" onClick={() => onStage(file.path)} title="暂存">
                  <Icon name="add" size={14} />
                </button>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
