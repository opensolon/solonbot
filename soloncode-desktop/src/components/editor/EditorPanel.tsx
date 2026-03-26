import { useState, useEffect, useRef } from 'react';
import { Icon, getFileIconName } from '../common/Icon';
import './EditorPanel.css';

interface OpenFile {
  path: string;
  name: string;
  content: string;
  modified: boolean;
  language: string;
}

interface EditorPanelProps {
  files: OpenFile[];
  activeFilePath: string | null;
  onFileSelect: (path: string) => void;
  onFileClose: (path: string) => void;
  onContentChange: (path: string, content: string) => void;
  onFileSave: (path: string) => void;
}

export function EditorPanel({
  files,
  activeFilePath,
  onFileSelect,
  onFileClose,
  onContentChange,
  onFileSave
}: EditorPanelProps) {
  const [lineNumbers, setLineNumbers] = useState<number[]>([]);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const activeFile = files.find(f => f.path === activeFilePath);

  useEffect(() => {
    if (activeFile) {
      const lines = activeFile.content.split('\n');
      setLineNumbers(Array.from({ length: lines.length }, (_, i) => i + 1));
    }
  }, [activeFile?.content, activeFilePath]);

  function handleKeyDown(e: React.KeyboardEvent) {
    if ((e.ctrlKey || e.metaKey) && e.key === 's') {
      e.preventDefault();
      if (activeFilePath && activeFile?.modified) {
        onFileSave(activeFilePath);
      }
    }
  }

  function handleInput(e: React.FormEvent<HTMLTextAreaElement>) {
    const content = e.currentTarget.value;
    if (activeFilePath) {
      onContentChange(activeFilePath, content);
    }
  }

  if (files.length === 0) {
    return (
      <div className="editor-panel empty">
        <div className="empty-state">
          <Icon name="code" size={48} className="empty-icon" />
          <span className="empty-text">打开文件开始编辑</span>
          <span className="empty-hint">使用左侧资源管理器浏览文件</span>
        </div>
      </div>
    );
  }

  return (
    <div className="editor-panel">
      <div className="editor-tabs">
        {files.map(file => (
          <div
            key={file.path}
            className={`editor-tab${file.path === activeFilePath ? ' active' : ''}`}
            onClick={() => onFileSelect(file.path)}
          >
            <Icon name={getFileIconName(file.name)} size={14} className="tab-icon" />
            <span className="tab-name">{file.name}</span>
            {file.modified && <span className="tab-modified">●</span>}
            <button
              className="tab-close"
              onClick={(e) => {
                e.stopPropagation();
                onFileClose(file.path);
              }}
            >
              <Icon name="close" size={14} />
            </button>
          </div>
        ))}
      </div>

      {activeFile && (
        <div className="editor-content">
          <div className="line-numbers">
            {lineNumbers.map(num => (
              <div key={num} className="line-number">{num}</div>
            ))}
          </div>
          <textarea
            ref={textareaRef}
            className="code-area"
            value={activeFile.content}
            onChange={handleInput}
            onKeyDown={handleKeyDown}
            spellCheck={false}
          />
        </div>
      )}

      {activeFile && (
        <div className="editor-status">
          <span className="status-item">{activeFile.language}</span>
          <span className="status-item">UTF-8</span>
          <span className="status-item">行 {lineNumbers.length}</span>
        </div>
      )}
    </div>
  );
}
