/**
 * 资源管理器面板
 * @author bai
 */
import { useState } from 'react';
import { Icon, getFileIconName } from '../common/Icon';
import './ExplorerPanel.css';

interface FileNode {
  name: string;
  type: 'file' | 'folder';
  children?: FileNode[];
  path: string;
  gitStatus?: 'modified' | 'added' | 'deleted' | 'untracked';
}

interface ExplorerPanelProps {
  files: FileNode[];
  workspaceName?: string;
  onFileSelect: (path: string) => void;
  onOpenFolder?: () => void;
  onRefresh?: () => void;
  onNewFile?: () => void;
  onNewFolder?: () => void;
}

export function ExplorerPanel({
  files,
  workspaceName,
  onFileSelect,
  onOpenFolder,
  onRefresh,
  onNewFile,
  onNewFolder
}: ExplorerPanelProps) {
  const [expandedFolders, setExpandedFolders] = useState<Set<string>>(new Set());

  const toggleFolder = (path: string) => {
    setExpandedFolders(prev => {
      const next = new Set(prev);
      if (next.has(path)) {
        next.delete(path);
      } else {
        next.add(path);
      }
      return next;
    });
  };

  function renderFileNode(node: FileNode, depth: number = 0) {
    const indent = depth * 16;
    const isExpanded = expandedFolders.has(node.path);

    return (
      <div key={node.path}>
        <div
          className={`file-node ${node.type}${isExpanded ? ' expanded' : ''}`}
          style={{ paddingLeft: `${indent + 8}px` }}
          onClick={() => {
            if (node.type === 'folder') {
              toggleFolder(node.path);
            } else {
              onFileSelect(node.path);
            }
          }}
        >
          {node.type === 'folder' ? (
            <>
              <Icon name={isExpanded ? 'folder-open' : 'folder'} size={16} className="file-icon" />
              <Icon name={isExpanded ? 'chevron-down' : 'chevron-right'} size={12} className="chevron-icon" />
            </>
          ) : (
            <>
              <Icon name={getFileIconName(node.name)} size={16} className="file-icon" />
              <span className="chevron-placeholder" />
            </>
          )}
          <span className="file-name">{node.name}</span>
          {node.gitStatus && (
            <span className={`git-status ${node.gitStatus}`}>
              <Icon name={node.gitStatus} size={12} />
            </span>
          )}
        </div>
        {node.type === 'folder' && isExpanded && node.children?.map(child => renderFileNode(child, depth + 1))}
      </div>
    );
  }

  const hasWorkspace = files.length > 0 || workspaceName;

  return (
    <div className="explorer-panel">
      <div className="panel-header">
        <span className="panel-title">资源管理器</span>
        {hasWorkspace && (
          <div className="panel-actions">
            <button className="panel-action" title="新建文件" onClick={onNewFile}>
              <Icon name="add" size={16} />
            </button>
            <button className="panel-action" title="新建文件夹" onClick={onNewFolder}>
              <Icon name="folder-add" size={16} />
            </button>
            <button className="panel-action" title="刷新" onClick={onRefresh}>
              <Icon name="refresh" size={16} />
            </button>
          </div>
        )}
      </div>

      {hasWorkspace ? (
        <>
          <div className="workspace-header">
            <Icon name="folder-root" size={16} />
            <span className="workspace-name">{workspaceName || '工作区'}</span>
          </div>
          <div className="panel-content">
            {files.map(file => renderFileNode(file))}
          </div>
        </>
      ) : (
        <div className="empty-workspace">
          <div className="empty-icon">
            <Icon name="folder" size={48} />
          </div>
          <p className="empty-text">尚未打开文件夹</p>
          <button className="open-folder-btn" onClick={onOpenFolder}>
            打开文件夹
          </button>
        </div>
      )}
    </div>
  );
}
