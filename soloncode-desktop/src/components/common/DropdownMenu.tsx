/**
 * 下拉菜单组件
 * @author bai
 */
import { useState, useRef, useEffect, useCallback } from 'react';
import './DropdownMenu.css';

export interface MenuItem {
  id: string;
  label: string;
  shortcut?: string;
  disabled?: boolean;
  divider?: boolean;
  children?: MenuItem[];
}

interface DropdownMenuProps {
  trigger: React.ReactNode;
  items: MenuItem[];
  onItemClick?: (itemId: string) => void;
  align?: 'left' | 'right';
}

export function DropdownMenu({ trigger, items, onItemClick, align = 'left' }: DropdownMenuProps) {
  const [isOpen, setIsOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLDivElement>(null);

  // 点击外部关闭菜单
  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (
        menuRef.current &&
        triggerRef.current &&
        !menuRef.current.contains(event.target as Node) &&
        !triggerRef.current.contains(event.target as Node)
      ) {
        setIsOpen(false);
      }
    };

    if (isOpen) {
      document.addEventListener('mousedown', handleClickOutside);
    }

    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, [isOpen]);

  // 键盘事件处理
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setIsOpen(false);
      }
    };

    if (isOpen) {
      document.addEventListener('keydown', handleKeyDown);
    }

    return () => {
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [isOpen]);

  const handleItemClick = useCallback((item: MenuItem) => {
    if (item.disabled) return;
    if (item.children && item.children.length > 0) return;

    setIsOpen(false);
    onItemClick?.(item.id);
  }, [onItemClick]);

  const renderMenuItem = (item: MenuItem, depth: number = 0) => {
    if (item.divider) {
      return <div key={item.id} className="dropdown-divider" />;
    }

    const hasChildren = item.children && item.children.length > 0;

    return (
      <div
        key={item.id}
        className={`dropdown-item${item.disabled ? ' disabled' : ''}${hasChildren ? ' has-children' : ''}`}
        onClick={() => handleItemClick(item)}
      >
        <span className="dropdown-item-label">{item.label}</span>
        {item.shortcut && <span className="dropdown-item-shortcut">{item.shortcut}</span>}
        {hasChildren && <span className="dropdown-item-arrow">▶</span>}
        {hasChildren && (
          <div className="dropdown-submenu">
            {item.children!.map(child => renderMenuItem(child, depth + 1))}
          </div>
        )}
      </div>
    );
  };

  return (
    <div className="dropdown-container">
      <div
        ref={triggerRef}
        className="dropdown-trigger"
        onClick={() => setIsOpen(!isOpen)}
      >
        {trigger}
      </div>
      {isOpen && (
        <div
          ref={menuRef}
          className={`dropdown-menu ${align === 'right' ? 'align-right' : ''}`}
        >
          {items.map(item => renderMenuItem(item))}
        </div>
      )}
    </div>
  );
}

export default DropdownMenu;
