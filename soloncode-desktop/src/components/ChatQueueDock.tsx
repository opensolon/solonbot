import type { QueuedChatMessage } from '../services/chatQueueService';
import { Icon } from './common/Icon';
import './ChatQueueDock.css';

export function ChatQueueDock({ items, running, onRemove, onClear, onContinue }: {
  items: QueuedChatMessage[];
  running: boolean;
  onRemove: (id: string) => void;
  onClear: () => void;
  onContinue: () => void;
}) {
  if (items.length === 0) return null;
  return (
    <div className="chat-queue-dock">
      <div className="chat-queue-header">
        <span>{running ? '运行中追加' : '待发送'} · {items.length}</span>
        <span className="chat-queue-actions">
          {!running && <button onClick={onContinue} disabled={Boolean(items[0]?.hadFiles)} title={items[0]?.hadFiles ? '附件未持久化，请移除后重新发送' : '发送下一条'}><Icon name="push" size={12} />继续</button>}
          <button onClick={onClear}>清空</button>
        </span>
      </div>
      <div className="chat-queue-list">
        {items.map((item, index) => (
          <div className="chat-queue-item" key={item.id}>
            <span className="chat-queue-index">{index + 1}</span>
            <span className="chat-queue-text" title={item.displayText}>{item.displayText}</span>
            {item.hadFiles
              ? <span className="chat-queue-restored">附件需重选</span>
              : item.restored && <span className="chat-queue-restored">已恢复</span>}
            <button onClick={() => onRemove(item.id)} title="移除"><Icon name="close" size={11} /></button>
          </div>
        ))}
      </div>
    </div>
  );
}
