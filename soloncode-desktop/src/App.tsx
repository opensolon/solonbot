import { useState, useCallback, useRef, useEffect } from 'react';
import { ActivityBar, type ActivityType } from './components/layout/ActivityBar';
import { TitleBar } from './components/layout/TitleBar';
import { SidePanel } from './components/layout/SidePanel';
import { ExplorerPanel } from './components/sidebar/ExplorerPanel';
import { SearchPanel } from './components/sidebar/SearchPanel';
import { GitPanel, type GitStatus } from './components/sidebar/GitPanel';
import { ExtensionsPanel } from './components/sidebar/ExtensionsPanel';
import { SessionsPanel, type Session } from './components/sidebar/SessionsPanel';
import { SettingsPanel, type Settings } from './components/sidebar/SettingsPanel';
import { EditorPanel } from './components/editor/EditorPanel';
import { ChatView } from './components/ChatView';
import { Icon } from './components/common/Icon';
import { fileService, type FileInfo, type OpenFile } from './services/fileService';
import type { Conversation, Plugin } from './types';
import './App.css';

// 文件树节点接口
interface FileTreeNode {
  name: string;
  type: 'folder' | 'file';
  path: string;
  children?: FileTreeNode[];
}

// 将 FileInfo 转换为 FileTreeNode
function convertToFileTree(files: FileInfo[]): FileTreeNode[] {
  return files.map(f => ({
    name: f.name,
    type: f.isDir ? 'folder' as const : 'file' as const,
    path: f.path,
    children: f.children ? convertToFileTree(f.children) : undefined,
  }));
}

// 模拟 Git 状态
const mockGitStatus: GitStatus = {
  branch: 'main',
  ahead: 2,
  behind: 0,
  files: [
    { path: 'src/App.tsx', status: 'modified' as const, staged: false },
    { path: 'src/components/ChatView.tsx', status: 'modified' as const, staged: false },
    { path: 'src/components/NewFeature.tsx', status: 'added' as const, staged: true },
    { path: 'temp.ts', status: 'untracked' as const, staged: false },
  ]
};

// 模拟扩展
const mockExtensions = [
  { id: '1', name: 'Markdown 渲染器', description: '增强 Markdown 渲染', version: '1.0.0', installed: true, enabled: true, author: 'SolonCode' },
  { id: '2', name: '代码格式化', description: '自动格式化代码', version: '2.1.0', installed: true, enabled: true, author: 'SolonCode' },
  { id: '3', name: 'Python 支持', description: 'Python 语言支持', version: '1.5.0', installed: false, enabled: false, author: 'Community' },
  { id: '4', name: 'Java 支持', description: 'Java 语言支持', version: '1.2.0', installed: false, enabled: false, author: 'Community' },
];

// 模拟会话
const mockSessions: Session[] = [
  { id: 'solonclaw', title: 'SolonClaw', timestamp: '固定', messageCount: 0, isPermanent: true },
  { id: '1', title: '代码重构讨论', timestamp: '10:30', messageCount: 15 },
  { id: '2', title: 'Bug 修复', timestamp: '昨天', messageCount: 8 },
  { id: '3', title: '新功能实现', timestamp: '3天前', messageCount: 23 },
];

// 模拟设置
const defaultSettings: Settings = {
  apiUrl: 'https://open.bigmodel.cn/api/paas/v4/chat/completions',
  apiKey: '',
  model: 'glm-4.7',
  maxSteps: 30,
  theme: 'dark',
  fontSize: 14,
  language: 'zh-CN',
  tabSize: 2,
  autoSave: true,
  formatOnSave: true,
  shell: 'bash',
  terminalFontSize: 14,
};

// 面板位置类型
type PanelPosition = 'editor' | 'chat';

interface PanelState {
  editorVisible: boolean;
  chatVisible: boolean;
  editorWidth: number;
  chatWidth: number;
  panelOrder: PanelPosition[];
}

function App() {
  const [activeActivity, setActiveActivity] = useState<ActivityType>('sessions');
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [settings, setSettings] = useState(defaultSettings);

  // 工作区状态
  const [workspacePath, setWorkspacePath] = useState<string | null>(null);
  const [workspaceName, setWorkspaceName] = useState<string>('');
  const [workspaceFiles, setWorkspaceFiles] = useState<FileTreeNode[]>([]);

  // 面板状态 - 默认比例 1:2:5:2 (活动栏:侧边栏:编辑器:对话框)
  const [panelState, setPanelState] = useState<PanelState>({
    editorVisible: true,
    chatVisible: true,
    editorWidth: 0, // 将在 useEffect 中根据比例计算
    chatWidth: 0,   // 将在 useEffect 中根据比例计算
    panelOrder: ['editor', 'chat'],
  });

  // 计算默认面板宽度比例
  useEffect(() => {
    const updatePanelWidths = () => {
      if (!containerRef.current) return;

      const containerWidth = containerRef.current.clientWidth;
      const activityBarWidth = 48; // 活动栏宽度
      const sidebarWidth = sidebarCollapsed ? 48 : 260; // 侧边栏宽度
      const remainingWidth = containerWidth - activityBarWidth - sidebarWidth;

      // 比例 5:2 (编辑器:对话框)
      const totalParts = 7;
      const editorWidth = Math.floor(remainingWidth * (5 / totalParts));
      const chatWidth = remainingWidth - editorWidth;

      setPanelState(prev => ({
        ...prev,
        editorWidth: Math.max(300, editorWidth),
        chatWidth: Math.max(250, chatWidth),
      }));
    };

    updatePanelWidths();
    window.addEventListener('resize', updatePanelWidths);
    return () => window.removeEventListener('resize', updatePanelWidths);
  }, [sidebarCollapsed]);

  // 编辑器状态
  const [openFiles, setOpenFiles] = useState<Array<{
    path: string;
    name: string;
    content: string;
    modified: boolean;
    language: string;
  }>>([]);
  const [activeFilePath, setActiveFilePath] = useState<string | null>(null);

  // 会话状态
  const [sessions, setSessions] = useState<Session[]>(mockSessions);
  const [currentSessionId, setCurrentSessionId] = useState<string>('solonclaw');

  // 拖拽调整大小
  const [isResizing, setIsResizing] = useState<string | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  const currentConversation: Conversation = {
    id: currentSessionId,
    title: sessions.find(s => s.id === currentSessionId)?.title || '新会话',
    timestamp: new Date().toLocaleString(),
    status: 'active',
  };

  const plugins: Plugin[] = [
    { id: 'none', name: '插件暂不支持', icon: 'cube', description: '插件暂不支持', enabled: true, version: '1.0.0' }
  ];

  // 切换面板可见性
  const togglePanel = useCallback((panel: 'editor' | 'chat') => {
    setPanelState(prev => ({
      ...prev,
      [`${panel}Visible`]: !prev[`${panel}Visible`],
    }));
  }, []);

  // 交换面板位置
  const swapPanels = useCallback(() => {
    setPanelState(prev => ({
      ...prev,
      panelOrder: [...prev.panelOrder].reverse(),
    }));
  }, []);

  // 开始拖拽调整大小
  const startResize = useCallback((panel: string, e: React.MouseEvent) => {
    e.preventDefault();
    setIsResizing(panel);
  }, []);

  // 处理拖拽
  useEffect(() => {
    if (!isResizing) return;

    const handleMouseMove = (e: MouseEvent) => {
      if (!containerRef.current) return;

      const containerRect = containerRef.current.getBoundingClientRect();
      const sidebarWidth = sidebarCollapsed ? 48 : 308;
      const relativeX = e.clientX - containerRect.left - sidebarWidth;

      if (isResizing === 'editor') {
        const newEditorWidth = Math.max(300, Math.min(800, relativeX));
        setPanelState(prev => ({ ...prev, editorWidth: newEditorWidth }));
      } else if (isResizing === 'chat') {
        const totalWidth = containerRect.width - sidebarWidth;
        const newChatWidth = Math.max(300, Math.min(600, totalWidth - relativeX));
        setPanelState(prev => ({ ...prev, chatWidth: newChatWidth }));
      }
    };

    const handleMouseUp = () => {
      setIsResizing(null);
    };

    document.addEventListener('mousemove', handleMouseMove);
    document.addEventListener('mouseup', handleMouseUp);

    return () => {
      document.removeEventListener('mousemove', handleMouseMove);
      document.removeEventListener('mouseup', handleMouseUp);
    };
  }, [isResizing, sidebarCollapsed]);

  // 文件操作
  const handleFileSelect = useCallback((path: string) => {
    const existingFile = openFiles.find(f => f.path === path);
    if (existingFile) {
      setActiveFilePath(path);
      return;
    }

    const fileName = path.split('/').pop() || '';
    const ext = fileName.split('.').pop() || '';
    const langMap: Record<string, string> = {
      'ts': 'TypeScript',
      'tsx': 'TypeScript React',
      'js': 'JavaScript',
      'jsx': 'JavaScript React',
      'json': 'JSON',
      'css': 'CSS',
      'html': 'HTML',
      'md': 'Markdown',
    };

    setOpenFiles(prev => [...prev, {
      path,
      name: fileName,
      content: `// ${fileName}\n// 文件内容...\n`,
      modified: false,
      language: langMap[ext] || 'Plain Text',
    }]);
    setActiveFilePath(path);
  }, [openFiles]);

  const handleFileClose = useCallback((path: string) => {
    setOpenFiles(prev => {
      const newFiles = prev.filter(f => f.path !== path);
      if (activeFilePath === path && newFiles.length > 0) {
        setActiveFilePath(newFiles[newFiles.length - 1].path);
      } else if (newFiles.length === 0) {
        setActiveFilePath(null);
      }
      return newFiles;
    });
  }, [activeFilePath]);

  const handleContentChange = useCallback((path: string, content: string) => {
    setOpenFiles(prev => prev.map(f =>
      f.path === path ? { ...f, content, modified: true } : f
    ));
  }, []);

  const handleFileSave = useCallback(async (path: string) => {
    const file = openFiles.find(f => f.path === path);
    if (file && workspacePath) {
      try {
        await fileService.writeFile(path, file.content);
        setOpenFiles(prev => prev.map(f =>
          f.path === path ? { ...f, modified: false } : f
        ));
      } catch (err) {
        console.error('保存文件失败:', err);
      }
    } else {
      setOpenFiles(prev => prev.map(f =>
        f.path === path ? { ...f, modified: false } : f
      ));
    }
  }, [openFiles, workspacePath]);

  // 打开文件对话框
  const handleOpenFile = useCallback(async () => {
    try {
      const selectedPath = await fileService.openFileDialog({
        multiple: false,
        filters: [
          { name: '所有文件', extensions: ['*'] },
          { name: 'TypeScript', extensions: ['ts', 'tsx'] },
          { name: 'JavaScript', extensions: ['js', 'jsx'] },
          { name: '文本文件', extensions: ['txt', 'md', 'json'] },
        ],
      });
      if (selectedPath && typeof selectedPath === 'string') {
        const file = await fileService.openFile(selectedPath);
        setOpenFiles(prev => {
          if (prev.some(f => f.path === selectedPath)) {
            return prev;
          }
          return [...prev, file];
        });
        setActiveFilePath(selectedPath);
      }
    } catch (err) {
      console.error('打开文件失败:', err);
    }
  }, []);

  // 打开文件夹对话框
  const handleOpenFolder = useCallback(async () => {
    try {
      const selectedPath = await fileService.openFolderDialog();
      if (selectedPath) {
        const info = await fileService.getWorkspaceInfo(selectedPath);
        const files = await fileService.listDirectoryTree(selectedPath, 5);
        setWorkspacePath(selectedPath);
        setWorkspaceName(info.name);
        setWorkspaceFiles(convertToFileTree(files));
        setActiveActivity('explorer');
      }
    } catch (err) {
      console.error('打开文件夹失败:', err);
    }
  }, []);

  // 新建文件
  const handleNewFile = useCallback(() => {
    const newFile: OpenFile = {
      path: `untitled-${Date.now()}.ts`,
      name: '未命名',
      content: '',
      modified: true,
      language: 'TypeScript',
    };
    setOpenFiles(prev => [...prev, newFile]);
    setActiveFilePath(newFile.path);
  }, []);

  // 保存当前文件
  const handleSaveCurrentFile = useCallback(() => {
    if (activeFilePath) {
      handleFileSave(activeFilePath);
    }
  }, [activeFilePath, handleFileSave]);

  // 会话操作
  const handleNewSession = useCallback(() => {
    const newSession: Session = {
      id: Date.now().toString(),
      title: '新会话',
      timestamp: '刚刚',
      messageCount: 0,
    };
    setSessions(prev => [newSession, ...prev]);
    setCurrentSessionId(newSession.id);
  }, []);

  const handleDeleteSession = useCallback((id: string) => {
    if (id === 'solonclaw') return;
    setSessions(prev => prev.filter(s => s.id !== id));
    if (currentSessionId === id) {
      setCurrentSessionId('solonclaw');
    }
  }, [currentSessionId]);

  // 渲染侧边栏内容
  const renderSidebarContent = () => {
    if (sidebarCollapsed) return null;

    switch (activeActivity) {
      case 'explorer':
        return (
          <ExplorerPanel
            files={workspaceFiles.length > 0 ? workspaceFiles : []}
            workspaceName={workspaceName || '未打开文件夹'}
            onFileSelect={handleFileSelect}
            onOpenFolder={handleOpenFolder}
            onRefresh={async () => {
              if (workspacePath) {
                const files = await fileService.listDirectoryTree(workspacePath, 5);
                setWorkspaceFiles(convertToFileTree(files));
              }
            }}
          />
        );
      case 'search':
        return (
          <SearchPanel
            onSearch={async (query) => { console.log('搜索:', query); return []; }}
            onResultClick={(result) => console.log('点击结果:', result)}
          />
        );
      case 'git':
        return (
          <GitPanel
            status={mockGitStatus}
            onCommit={async (msg) => console.log('提交:', msg)}
            onStage={async (path) => console.log('暂存:', path)}
            onUnstage={async (path) => console.log('取消暂存:', path)}
            onPush={async () => console.log('推送')}
            onPull={async () => console.log('拉取')}
            onFileClick={handleFileSelect}
          />
        );
      case 'extensions':
        return (
          <ExtensionsPanel
            extensions={mockExtensions}
            onInstall={async (id) => console.log('安装:', id)}
            onUninstall={async (id) => console.log('卸载:', id)}
            onToggle={(id) => console.log('切换:', id)}
          />
        );
      case 'sessions':
        return (
          <SessionsPanel
            sessions={sessions}
            currentSessionId={currentSessionId}
            onSelectSession={setCurrentSessionId}
            onNewSession={handleNewSession}
            onDeleteSession={handleDeleteSession}
          />
        );
      case 'settings':
        return (
          <SettingsPanel
            settings={settings}
            onSettingsChange={setSettings}
          />
        );
      default:
        return null;
    }
  };

  // 渲染面板
  const renderPanel = (panel: PanelPosition) => {
    if (panel === 'editor') {
      if (!panelState.editorVisible) return null;
      return (
        <div key="editor" className="panel-wrapper editor-wrapper" style={{ width: panelState.editorWidth }}>
          <EditorPanel
            files={openFiles}
            activeFilePath={activeFilePath}
            onFileSelect={setActiveFilePath}
            onFileClose={handleFileClose}
            onContentChange={handleContentChange}
            onFileSave={handleFileSave}
          />
          <div
            className="resize-handle vertical"
            onMouseDown={(e) => startResize('editor', e)}
          />
        </div>
      );
    }

    if (panel === 'chat') {
      if (!panelState.chatVisible) return null;
      return (
        <div key="chat" className="panel-wrapper chat-wrapper" style={{ width: panelState.chatWidth, flex: '1 1 auto' }}>
          <ChatView
            currentConversation={currentConversation}
            plugins={plugins}
          />
        </div>
      );
    }

    return null;
  };

  return (
    <div className="app-container" ref={containerRef}>
      {/* 顶部标题栏/菜单栏 */}
      <TitleBar
        workspacePath={workspacePath || undefined}
        workspaceName={workspaceName}
        onNewFile={handleNewFile}
        onOpenFile={handleOpenFile}
        onOpenFolder={handleOpenFolder}
        onSave={handleSaveCurrentFile}
        onSaveAll={() => openFiles.forEach(f => handleFileSave(f.path))}
        editorVisible={panelState.editorVisible}
        chatVisible={panelState.chatVisible}
        onToggleEditor={() => togglePanel('editor')}
        onToggleChat={() => togglePanel('chat')}
        onSwapPanels={swapPanels}
      />

      {/* 主内容区 */}
      <div className="main-area">
        {/* 左侧活动栏 */}
        <ActivityBar
          activeActivity={activeActivity}
          onActivityChange={(activity) => {
            if (activeActivity === activity) {
              setSidebarCollapsed(!sidebarCollapsed);
            } else {
              setSidebarCollapsed(false);
              setActiveActivity(activity);
            }
          }}
        />

        {/* 侧边栏面板 */}
        <div className={`sidebar-container${sidebarCollapsed ? ' collapsed' : ''}`}>
          {!sidebarCollapsed && (
            <SidePanel title="" width={260} minWidth={200} maxWidth={400}>
              {renderSidebarContent()}
            </SidePanel>
          )}
        </div>

        {/* 动态面板区域 */}
        <div className="panels-container">
          {panelState.panelOrder.map(panel => renderPanel(panel))}
        </div>
      </div>

      {/* 底部状态栏 */}
      <div className="status-bar">
        <div className="status-left">
          <span className="status-item">
            <Icon name="bot" size={12} />
            {settings.model}
          </span>
          <span className="status-item">
            <Icon name="git" size={12} />
            main
          </span>
        </div>
        <div className="status-right">
          <span className="status-item">UTF-8</span>
          <span className="status-item">TypeScript</span>
        </div>
      </div>
    </div>
  );
}

export default App;
