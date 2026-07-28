import { useCallback, useEffect, useMemo, useState } from 'react';
import { Icon } from '../common/Icon';
import { memoryService, type MemoryEntry } from '../../services/memoryService';
import './MemoryPanel.css';

const EMPTY_ENTRY: MemoryEntry = { key: '', content: '', importance: 5 };

export function MemoryPanel({ backendPort }: { backendPort?: number | null }) {
  const [entries, setEntries] = useState<MemoryEntry[]>([]);
  const [filter, setFilter] = useState('');
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [draft, setDraft] = useState<MemoryEntry>(EMPTY_ENTRY);
  const [isNew, setIsNew] = useState(false);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ text: string; error?: boolean } | null>(null);

  const refresh = useCallback(async () => {
    setBusy(true);
    try {
      setEntries(await memoryService.list(backendPort));
      setMessage(null);
    } catch {
      setMessage({ text: '长期记忆加载失败，请检查后端服务', error: true });
    } finally {
      setBusy(false);
    }
  }, [backendPort]);

  useEffect(() => { void refresh(); }, [refresh]);

  const visibleEntries = useMemo(() => {
    const query = filter.trim().toLocaleLowerCase();
    if (!query) return entries;
    return entries.filter(entry => entry.key.toLocaleLowerCase().includes(query)
      || entry.content.toLocaleLowerCase().includes(query));
  }, [entries, filter]);

  async function selectEntry(entry: MemoryEntry) {
    setSelectedKey(entry.key);
    setIsNew(false);
    setDraft(entry);
    try {
      setDraft(await memoryService.get(backendPort, entry.key));
    } catch {
      setMessage({ text: '记忆详情加载失败', error: true });
    }
  }

  async function saveDraft() {
    setBusy(true);
    try {
      await memoryService.save(backendPort, draft);
      setMessage({ text: '记忆已保存' });
      setSelectedKey(draft.key.trim());
      setIsNew(false);
      await refresh();
    } catch (error) {
      setMessage({ text: error instanceof Error ? error.message : '保存失败', error: true });
      setBusy(false);
    }
  }

  async function removeSelected() {
    if (!selectedKey || !window.confirm(`确定删除记忆“${selectedKey}”吗？`)) return;
    setBusy(true);
    try {
      await memoryService.remove(backendPort, selectedKey);
      setSelectedKey(null);
      setDraft(EMPTY_ENTRY);
      setMessage({ text: '记忆已删除' });
      await refresh();
    } catch {
      setMessage({ text: '删除失败，原数据已保留', error: true });
      setBusy(false);
    }
  }

  return (
    <div className="memory-panel">
      <div className="panel-header">
        <span className="panel-title">长期记忆</span>
        <div className="panel-actions">
          <button className="panel-action" title="刷新" onClick={() => void refresh()} disabled={busy}><Icon name="refresh" size={14} /></button>
          <button className="panel-action" title="新建记忆" onClick={() => { setIsNew(true); setSelectedKey(null); setDraft(EMPTY_ENTRY); }}><Icon name="add" size={14} /></button>
        </div>
      </div>
      <div className="memory-search"><Icon name="search" size={13} /><input value={filter} onChange={event => setFilter(event.target.value)} placeholder="搜索记忆" /></div>
      {message && <div className={`memory-message${message.error ? ' error' : ''}`}>{message.text}</div>}
      <div className="memory-list">
        {busy && entries.length === 0 ? <div className="memory-empty">加载中...</div> : visibleEntries.length === 0 ? <div className="memory-empty">暂无记忆</div> : visibleEntries.map(entry => (
          <button key={entry.key} className={`memory-item${selectedKey === entry.key ? ' active' : ''}`} onClick={() => void selectEntry(entry)}>
            <span className="memory-item-title"><span className="memory-star">★{entry.importance}</span>{entry.key}</span>
            <span className="memory-item-preview">{entry.content}</span>
            {entry.time && <span className="memory-item-time">{entry.time}</span>}
          </button>
        ))}
      </div>
      {(isNew || selectedKey) && (
        <div className="memory-editor">
          <label>Key<input value={draft.key} readOnly={!isNew} maxLength={128} onChange={event => setDraft(current => ({ ...current, key: event.target.value }))} /></label>
          <label>重要度<input type="number" min={1} max={10} value={draft.importance} onChange={event => setDraft(current => ({ ...current, importance: Math.max(1, Math.min(10, Number(event.target.value) || 1)) }))} /></label>
          <label>内容<textarea value={draft.content} onChange={event => setDraft(current => ({ ...current, content: event.target.value }))} placeholder="需要长期保留的信息" /></label>
          <div className="memory-editor-actions">
            {!isNew && <button className="memory-danger" onClick={() => void removeSelected()} disabled={busy}>删除</button>}
            <button className="memory-primary" onClick={() => void saveDraft()} disabled={busy}>保存</button>
          </div>
        </div>
      )}
    </div>
  );
}
