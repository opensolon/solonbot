import { useState, FormEvent, KeyboardEvent, useRef, useEffect, useCallback, useMemo, type ReactNode } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { Icon } from './common/Icon';
import type { ModelProvider } from '../services/settingsService';
import { PROVIDER_PRESETS } from '../services/settingsService';
import { fileService, isImageFile } from '../services/fileService';
import {
  extractSubagentHints,
  isValidSubagentName,
  type SubagentHint,
} from '../utils/subagents';
import './ChatInput.css';

/** 开始工作下拉面板 */
function StartWorkPanel({ onNewProject, onOpenFolder }: { onNewProject?: () => void; onOpenFolder?: () => void }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  return (
    <div className="start-work-panel" ref={ref}>
      <button type="button" className="start-work-trigger" onClick={() => setOpen(prev => !prev)}>
        <span className="start-work-trigger-left">
          <Icon name="folder" size={14} />
          <span>进入项目工作</span>
        </span>
        <Icon name="chevron-down" size={12} />
      </button>
      {open && (
        <div className="start-work-dropdown">
          <div className="start-work-dropdown-item" onClick={() => { setOpen(false); onNewProject?.(); }}>
            <Icon name="file" size={14} /> 新建项目
          </div>
          <div className="start-work-dropdown-item" onClick={() => { setOpen(false); onOpenFolder?.(); }}>
            <Icon name="folder" size={14} /> 打开项目
          </div>
        </div>
      )}
    </div>
  );
}

// 命令类型（从 Web 控制器 /web/chat/hints 加载）
interface CommandItem extends SubagentHint {
  name: string;
  description: string;
  type: string; // SYSTEM | CONFIG | AGENT
}

const DEFAULT_COMMANDS: CommandItem[] = [
  { name: 'loop', description: '查看或启动循环任务', type: 'SYSTEM' },
  { name: 'clear', description: '清空当前对话上下文', type: 'SYSTEM' },
  { name: 'compact', description: '压缩当前上下文', type: 'SYSTEM' },
  { name: 'models', description: '查看可用模型', type: 'CONFIG' },
  { name: 'help', description: '查看可用命令', type: 'SYSTEM' },
];

function normalizeCommands(list: CommandItem[]) {
  const map = new Map<string, CommandItem>();
  const backendCommands = list.filter(command => String(command.type || '').toLowerCase() === 'command');
  for (const command of [...DEFAULT_COMMANDS, ...backendCommands]) {
    const name = String(command.name || '').replace(/^\//, '').trim();
    if (!name) continue;
    map.set(name, {
      name,
      description: command.description || '',
      type: command.type || 'SYSTEM',
    });
  }
  return Array.from(map.values());
}

// 对话中可通过 @ 选择的真实 Agent。
export interface ChatAgentOption {
  name: string;
  description?: string;
  enabled: boolean;
}

export interface ChatSkillOption {
  name: string;
  description?: string;
  enabled: boolean;
}

interface AgentMenuOption {
  id: string;
  name: string;
  description?: string;
}

/** Shared visual menu for both the @ mention picker and the input-bar agent picker. */
function AgentPickerMenu({
  agents,
  selectedAgent,
  activeIndex,
  includeDefault = false,
  emptyText,
  footer,
  onSelect,
}: {
  agents: readonly AgentMenuOption[];
  selectedAgent?: string;
  activeIndex?: number;
  includeDefault?: boolean;
  emptyText: string;
  footer?: ReactNode;
  onSelect: (agentName: string) => void;
}) {
  return (
    <>
      <div className="agent-picker-header">选择智能体</div>
      <div className="agent-picker-list">
        {includeDefault && (
          <button
            type="button"
            className={`agent-picker-item${selectedAgent ? '' : ' active'}`}
            onClick={() => onSelect('')}
          >
            <Icon name="bot" size={15} />
            <span className="agent-picker-item-content">
              <span className="agent-picker-item-name">默认 Agent</span>
              <span className="agent-picker-item-description">使用主代理处理任务</span>
            </span>
            {!selectedAgent && <span className="agent-picker-check">✓</span>}
          </button>
        )}
        {agents.length === 0 ? (
          <div className="agent-picker-empty">{emptyText}</div>
        ) : agents.map((agent, index) => {
          const isSelected = selectedAgent === agent.name;
          const isActive = isSelected || activeIndex === index;
          return (
            <button
              key={agent.id}
              type="button"
              className={`agent-picker-item${isActive ? ' active' : ''}`}
              onClick={() => onSelect(agent.name)}
            >
              <Icon name="bot" size={15} />
              <span className="agent-picker-item-content">
                <span className="agent-picker-item-name">{agent.name}</span>
                <span className="agent-picker-item-description">{agent.description || 'Subagent'}</span>
              </span>
              {includeDefault && isSelected && <span className="agent-picker-check">✓</span>}
            </button>
          );
        })}
      </div>
      {footer && <div className="agent-picker-footer">{footer}</div>}
    </>
  );
}

function containsAgentMention(value: string, agentName: string) {
  const token = `@${agentName}`;
  let index = value.indexOf(token);
  while (index >= 0) {
    const before = index === 0 ? '' : value[index - 1];
    const afterIndex = index + token.length;
    const after = afterIndex >= value.length ? '' : value[afterIndex];
    if ((!before || /\s/.test(before)) && (!after || /\s/.test(after))) return true;
    index = value.indexOf(token, index + token.length);
  }
  return false;
}

// 上下文引用项
interface ContextRef {
  id: string;
  type: 'file' | 'folder' | 'code' | 'symbol';
  name: string;
  path?: string;
}

interface ChatInputProps {
  onSend: (message: string, options: SendOptions) => void;
  isLoading?: boolean;
  onStop?: () => void;
  availableFiles?: ContextRef[];
  agents?: ChatAgentOption[];
  skills?: ChatSkillOption[];
  agentRefreshKey?: number;
  providers?: ModelProvider[];
  activeProviderId?: string;
  onModelChange?: (providerId: string) => void;
  activeFileName?: string;
  backendPort?: number | null;
  showStartWork?: boolean;
  onNewProject?: () => void;
  onOpenFolder?: () => void;
  workspacePath?: string;
  baseContextTokens?: number;
  contextTokenLimit?: number;
}

export interface SendOptions {
  model: string;       // providerId，用于前端状态管理
  modelName: string;   // 实际模型名，用于CLI后端识别
  agent: string;
  contexts: ContextRef[];
  attachments: Attachment[];
  reasoningEffort: ReasoningEffort;
}

export interface Attachment {
  id: string;
  name: string;
  type: 'image' | 'text';
  content: string; // image: base64 data url; text: file content
  path?: string;
}

/** 获取模型显示名称 */
function getModelDisplayName(p: ModelProvider): string {
  const preset = PROVIDER_PRESETS[p.type as keyof typeof PROVIDER_PRESETS];
  const modelLabel = preset?.models.find(m => m.value === p.model)?.label || p.model;
  return modelLabel || p.model;
}

export type ReasoningEffort = 'low' | 'medium' | 'high' | 'max';

const REASONING_OPTIONS: Array<{ key: ReasoningEffort; label: string; desc: string }> = [
  { key: 'low', label: '低', desc: '快速响应，基础推理' },
  { key: 'medium', label: '中', desc: '平衡思考（默认）' },
  { key: 'high', label: '高', desc: '深度推理，适合复杂任务' },
  { key: 'max', label: '极高', desc: '最大推理深度' },
];

function estimateTokens(text: string) {
  return Math.ceil(text.length / 4);
}

export function ChatInput({ onSend, isLoading, onStop, availableFiles = [], agents = [], skills = [], agentRefreshKey = 0, providers = [], activeProviderId, onModelChange, activeFileName, backendPort, showStartWork, onNewProject, onOpenFolder, workspacePath, baseContextTokens = 0, contextTokenLimit = 128000 }: ChatInputProps) {
  // 从每个 provider 的 availableModels 展开为独立的可选模型
  const allModels = useMemo(() => {
    const result: ModelProvider[] = [];
    for (const p of providers) {
      if (!p.enabled) continue;
      if (p.availableModels && p.availableModels.length > 0) {
        for (const m of p.availableModels) {
          result.push({
            id: `${p.id}__${m.id}`,
            type: p.type,
            name: p.name,
            apiUrl: p.apiUrl,
            apiKey: p.apiKey,
            model: m.id,
            enabled: true,
            contextLength: m.contextLength || p.contextLength,
          });
        }
      } else if (p.model) {
        result.push(p);
      }
    }
    return result;
  }, [providers]);

  const [backendSubagents, setBackendSubagents] = useState<ChatAgentOption[] | null>(null);

  const availableAgents = useMemo(() => {
    const uniqueAgents = new Map<string, ChatAgentOption & { id: string; icon: string }>();
    for (const agent of backendSubagents ?? agents) {
      const name = agent.name.trim();
      if (!agent.enabled || !isValidSubagentName(name) || uniqueAgents.has(name)) continue;
      uniqueAgents.set(name, {
        ...agent,
        id: name,
        name,
        icon: 'bot',
      });
    }
    return Array.from(uniqueAgents.values());
  }, [agents, backendSubagents]);

  const [userInput, setUserInput] = useState('');
  const [selectedModel, setSelectedModel] = useState('');
  const [reasoningEffort, setReasoningEffort] = useState<ReasoningEffort>(() => {
    const saved = localStorage.getItem('soloncode-reasoning-effort') as ReasoningEffort | null;
    return saved && REASONING_OPTIONS.some(item => item.key === saved) ? saved : 'medium';
  });
  const [selectedAgent, setSelectedAgent] = useState('');
  const [contexts, setContexts] = useState<ContextRef[]>([]);
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const [workspaceFiles, setWorkspaceFiles] = useState<ContextRef[]>([]);
  const workspaceFilesLoadedRef = useRef(false);

  // 模型选择器弹出状态
  const [showModelPicker, setShowModelPicker] = useState(false);
  const modelPickerRef = useRef<HTMLDivElement>(null);
  const modelPickerPanelRef = useRef<HTMLDivElement>(null);

  const [showReasoningPicker, setShowReasoningPicker] = useState(false);
  const reasoningPickerRef = useRef<HTMLDivElement>(null);
  const reasoningPickerPanelRef = useRef<HTMLDivElement>(null);

  // 语音输入状态
  const [voiceRecording, setVoiceRecording] = useState(false);
  const recognitionRef = useRef<any>(null);
  const voiceRecordingRef = useRef(false);
  const voiceBaseTextRef = useRef('');
  const voiceFinalRef = useRef('');
  const voiceRafRef = useRef(false);

  // 以应用当前模型为唯一来源，避免旧的本地缓存和状态栏显示不一致。
  useEffect(() => {
    if (activeProviderId && allModels.some(m => m.id === activeProviderId)) {
      setSelectedModel(activeProviderId);
      return;
    }

    const separatorIndex = activeProviderId?.indexOf('__') ?? -1;
    const providerId = separatorIndex >= 0
      ? activeProviderId?.slice(0, separatorIndex)
      : activeProviderId;
    const provider = providers.find(item => item.id === providerId);
    const matchingProviderModel = provider
      ? allModels.find(item => item.id.startsWith(`${provider.id}__`) && item.model === provider.model)
      : undefined;
    const firstProviderModel = providerId
      ? allModels.find(item => item.id === providerId || item.id.startsWith(`${providerId}__`))
      : undefined;

    setSelectedModel(matchingProviderModel?.id || firstProviderModel?.id || allModels[0]?.id || '');
  }, [activeProviderId, allModels, providers]);

  useEffect(() => {
    if (!showModelPicker) return;
    function handleClickOutside(event: MouseEvent) {
      const target = event.target as Node;
      if (!modelPickerRef.current?.contains(target) && !modelPickerPanelRef.current?.contains(target)) {
        setShowModelPicker(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [showModelPicker]);

  useEffect(() => {
    if (!showReasoningPicker) return;
    const handler = (e: MouseEvent) => {
      const target = e.target as Node;
      if (!reasoningPickerRef.current?.contains(target) && !reasoningPickerPanelRef.current?.contains(target)) {
        setShowReasoningPicker(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [showReasoningPicker]);

  // 语音输入初始化
  useEffect(() => {
    const SpeechRecognition = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (!SpeechRecognition) return;
    const recognition = new SpeechRecognition();
    recognition.lang = 'zh-CN';
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.maxAlternatives = 1;

    recognition.onresult = (event: any) => {
      let interimTranscript = '';
      let finalTranscript = '';
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const transcript = event.results[i][0].transcript;
        if (event.results[i].isFinal) {
          finalTranscript += transcript;
        } else {
          interimTranscript += transcript;
        }
      }
      if (finalTranscript) {
        voiceFinalRef.current += finalTranscript;
      }
      if (!voiceRafRef.current) {
        voiceRafRef.current = true;
        requestAnimationFrame(() => {
          voiceRafRef.current = false;
          setUserInput(voiceBaseTextRef.current + voiceFinalRef.current + interimTranscript);
        });
      }
    };

    recognition.onerror = () => {
      voiceRecordingRef.current = false;
      setVoiceRecording(false);
    };

    recognition.onend = () => {
      if (voiceRecordingRef.current) {
        try { recognition.start(); } catch {}
      } else {
        voiceRecordingRef.current = false;
        setVoiceRecording(false);
      }
    };

    recognitionRef.current = recognition;
  }, []);

  // 语音按钮事件
  const voiceBtnHandlers = useMemo(() => ({
    onStart: () => {
      if (!recognitionRef.current) return;
      voiceBaseTextRef.current = userInput;
      voiceFinalRef.current = '';
      voiceRecordingRef.current = true;
      setVoiceRecording(true);
      try { recognitionRef.current.start(); } catch {}
    },
    onStop: () => {
      voiceRecordingRef.current = false;
      if (recognitionRef.current) {
        try { recognitionRef.current.stop(); } catch {}
      }
      setVoiceRecording(false);
      voiceBaseTextRef.current = userInput;
      voiceFinalRef.current = '';
    },
  }), [userInput]);

  // 自动完成状态
  const [showAutocomplete, setShowAutocomplete] = useState(false);
  const [autocompleteType, setAutocompleteType] = useState<'context' | 'agent' | 'command' | 'skill' | null>(null);
  const [autocompleteQuery, setAutocompleteQuery] = useState('');
  const [autocompletePosition, setAutocompletePosition] = useState({ start: 0, end: 0 });
  const [selectedIndex, setSelectedIndex] = useState(0);

  // 命令与 Subagent 列表（与 Web 端共用 /web/chat/hints）
  const [commands, setCommands] = useState<CommandItem[]>(DEFAULT_COMMANDS);
  const hintsLoadedRef = useRef(false);
  const hintsLoadingRef = useRef(false);

  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const autocompleteRef = useRef<HTMLDivElement>(null);

  // 粘贴处理：支持图片和文件
  function processClipboardItems(items: DataTransferItemList | FileList | null) {
    if (!items) return;
    for (const item of Array.from(items)) {
      const file = item instanceof File ? item : (item as DataTransferItem).getAsFile?.();
      const fileType = file?.type || '';
      if (fileType.startsWith('image/') && file) {
        const reader = new FileReader();
        reader.onload = () => {
          setAttachments(prev => [...prev, {
            id: `img-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
            name: file.name || 'pasted-image.png',
            type: 'image' as const,
            content: reader.result as string,
          }]);
        };
        reader.readAsDataURL(file);
        return true;
      }
      if (file && !fileType.startsWith('image/')) {
        const reader = new FileReader();
        reader.onload = () => {
          setAttachments(prev => [...prev, {
            id: `file-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
            name: file.name,
            type: 'text' as const,
            content: reader.result as string,
          }]);
        };
        reader.readAsText(file);
        return true;
      }
    }
    return false;
  }

  function handlePaste(e: React.ClipboardEvent) {
    const items = e.clipboardData?.items;
    if (!items) return;
    for (const item of Array.from(items)) {
      if (item.type.startsWith('image/')) {
        e.preventDefault();
        processClipboardItems(items);
        return;
      }
    }
    const files = e.clipboardData?.files;
    if (files && files.length > 0 && files[0].type.startsWith('image/')) {
      e.preventDefault();
      processClipboardItems(files);
    }
  }

  function removeAttachment(id: string) {
    setAttachments(prev => prev.filter(a => a.id !== id));
  }

  // 从后端加载命令和已注册的 Subagent，避免直接展示尚未注册的本地 Agent 文件。
  const loadHints = useCallback(async () => {
    if (hintsLoadedRef.current || hintsLoadingRef.current) return;
    hintsLoadingRef.current = true;
    const port = backendPort || 4808;
    try {
      const resp = await fetch(`http://localhost:${port}/web/chat/hints`);
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
      const json = await resp.json();
      const list: CommandItem[] = json.data || json;
      const hints = Array.isArray(list) ? list : [];
      setCommands(normalizeCommands(hints));
      setBackendSubagents(extractSubagentHints(hints));
      hintsLoadedRef.current = true;
    } catch {
      setCommands(DEFAULT_COMMANDS);
      setBackendSubagents(null);
    } finally {
      hintsLoadingRef.current = false;
    }
  }, [backendPort]);

  useEffect(() => {
    hintsLoadedRef.current = false;
    hintsLoadingRef.current = false;
    setBackendSubagents(null);
    setCommands(DEFAULT_COMMANDS);
    const timer = window.setTimeout(() => void loadHints(), 600);
    return () => window.clearTimeout(timer);
  }, [backendPort, loadHints]);

  // 保存 Agent 后先显示刚保存的本地配置，再读取后端重新注册后的结果。
  // 后端由文件监听刷新 Agent，延迟重试一次可覆盖文件系统通知稍晚到达的情况。
  useEffect(() => {
    if (agentRefreshKey === 0) return;
    setBackendSubagents(null);
    setCommands(DEFAULT_COMMANDS);

    const refreshHints = () => {
      if (hintsLoadingRef.current) return;
      hintsLoadedRef.current = false;
      void loadHints();
    };
    const immediateTimer = window.setTimeout(refreshHints, 250);
    const settledTimer = window.setTimeout(refreshHints, 1200);
    return () => {
      window.clearTimeout(immediateTimer);
      window.clearTimeout(settledTimer);
    };
  }, [agentRefreshKey, loadHints]);

  // 加载工作区文件列表（懒加载，首次输入 # 时触发）
  const loadWorkspaceFiles = useCallback(async () => {
    if (workspaceFilesLoadedRef.current || !workspacePath) return;
    try {
      const tree = await fileService.listDirectoryTree(workspacePath, 4);
      const flatten = (items: typeof tree, basePath = ''): ContextRef[] => {
        const result: ContextRef[] = [];
        for (const item of items) {
          if (!item.isDir) {
            result.push({
              id: item.path,
              type: 'file',
              name: item.name,
              path: item.path,
            });
          }
          if (item.children) {
            result.push(...flatten(item.children, item.path));
          }
        }
        return result;
      };
      const files = flatten(tree);
      setWorkspaceFiles(files);
      workspaceFilesLoadedRef.current = true;
    } catch { /* ignore */ }
  }, [workspacePath]);

  // 工作区变化时重置缓存
  useEffect(() => {
    workspaceFilesLoadedRef.current = false;
    setWorkspaceFiles([]);
  }, [workspacePath]);

  // 获取过滤后的自动完成选项
  const getFilteredOptions = useCallback(() => {
    if (autocompleteType === 'command') {
      return commands.filter(c =>
        c.name.toLowerCase().includes(autocompleteQuery.toLowerCase()) ||
        c.description.toLowerCase().includes(autocompleteQuery.toLowerCase())
      );
    }
    if (autocompleteType === 'agent') {
      const query = autocompleteQuery.toLocaleLowerCase();
      return availableAgents.filter(agent =>
        agent.name.toLocaleLowerCase().includes(query)
        || (agent.description || '').toLocaleLowerCase().includes(query)
      );
    }
    if (autocompleteType === 'skill') {
      const query = autocompleteQuery.toLocaleLowerCase();
      return skills.filter(skill => skill.enabled && (
        skill.name.toLocaleLowerCase().includes(query)
        || (skill.description || '').toLocaleLowerCase().includes(query)
      )).map(skill => ({ id: skill.name, name: skill.name, description: skill.description, type: 'skill' }));
    }
    if (autocompleteType === 'context') {
      // 首次触发时加载文件列表
      loadWorkspaceFiles();
      const query = autocompleteQuery.toLowerCase();
      if (!query) return workspaceFiles.slice(0, 50);
      return workspaceFiles.filter(f => f.name.toLowerCase().includes(query)).slice(0, 50);
    }
    return [];
  }, [autocompleteType, autocompleteQuery, availableFiles, availableAgents, commands, skills, workspaceFiles]);

  // 处理输入变化
  function handleInput(event: React.ChangeEvent<HTMLTextAreaElement>) {
    const value = event.target.value;
    const cursorPos = event.target.selectionStart;

    // 找到光标前的最后一个触发字符
    const textBeforeCursor = value.substring(0, cursorPos);
    const lastSlashIndex = textBeforeCursor.lastIndexOf('/');
    const lastAtIndex = textBeforeCursor.lastIndexOf('@');
    const lastHashIndex = textBeforeCursor.lastIndexOf('#');
    const lastDollarIndex = textBeforeCursor.lastIndexOf('$');

    // 检查 / 后面是否有空格（有则不算命令触发）
    let triggerType: 'agent' | 'context' | 'command' | 'skill' | null = null;
    let triggerIndex = -1;

    if (lastSlashIndex !== -1) {
      const afterSlash = textBeforeCursor.substring(lastSlashIndex + 1);
      const beforeSlash = textBeforeCursor.substring(0, lastSlashIndex);
      const isCommandPosition = beforeSlash.length === 0 || /\s$/.test(beforeSlash);
      if (isCommandPosition && !afterSlash.includes(' ') && lastSlashIndex >= lastAtIndex && lastSlashIndex >= lastHashIndex && lastSlashIndex >= lastDollarIndex) {
        triggerType = 'command';
        triggerIndex = lastSlashIndex;
        // 异步加载命令（首次）
        loadHints();
      }
    }

    if (!triggerType && lastDollarIndex !== -1) {
      const afterDollar = textBeforeCursor.substring(lastDollarIndex + 1);
      const beforeDollar = textBeforeCursor.substring(0, lastDollarIndex);
      const isSkillPosition = lastDollarIndex === 0 || /\s$/.test(beforeDollar);
      if (isSkillPosition && !/\s/.test(afterDollar) && lastDollarIndex > lastAtIndex && lastDollarIndex > lastHashIndex) {
        triggerType = 'skill';
        triggerIndex = lastDollarIndex;
      }
    }

    if (!triggerType) {
      const afterAt = lastAtIndex === -1 ? '' : textBeforeCursor.substring(lastAtIndex + 1);
      const beforeAt = lastAtIndex <= 0 ? '' : textBeforeCursor.substring(0, lastAtIndex);
      const isAgentPosition = lastAtIndex === 0 || /\s$/.test(beforeAt);
      if (lastAtIndex > lastHashIndex && lastAtIndex !== -1 && isAgentPosition && !/\s/.test(afterAt)) {
        triggerType = 'agent';
        triggerIndex = lastAtIndex;
        loadHints();
      } else if (lastHashIndex !== -1) {
        triggerType = 'context';
        triggerIndex = lastHashIndex;
      }
    }

    if (triggerType && triggerIndex !== -1) {
      setShowModelPicker(false);
      setShowReasoningPicker(false);
      setAutocompleteType(triggerType);
      setAutocompleteQuery(value.substring(triggerIndex + 1, cursorPos));
      setAutocompletePosition({ start: triggerIndex, end: cursorPos });
      setShowAutocomplete(true);
      setSelectedIndex(0);
    } else {
      setShowAutocomplete(false);
      setAutocompleteType(null);
    }

    if (selectedAgent && !containsAgentMention(value, selectedAgent)) {
      setSelectedAgent('');
    }
    setUserInput(value);
  }

  // 选择自动完成项
  function selectAutocompleteItem(item: { id: string; name: string }) {
    const beforeTrigger = userInput.substring(0, autocompletePosition.start);
    const afterCursor = userInput.substring(autocompletePosition.end);

    const trigger = autocompleteType === 'agent' ? '@' : autocompleteType === 'command' ? '/' : autocompleteType === 'skill' ? '$' : '#';

    if (autocompleteType === 'command') {
      // 命令选择后直接填入并触发发送
      const newValue = beforeTrigger + `/${item.name}` + afterCursor;
      setUserInput(newValue);
      setShowAutocomplete(false);
      setAutocompleteType(null);
      setTimeout(() => {
        if (textareaRef.current) {
          const newPos = beforeTrigger.length + 1 + item.name.length;
          textareaRef.current.focus();
          textareaRef.current.setSelectionRange(newPos, newPos);
        }
      }, 0);
      return;
    }

    const newValue = beforeTrigger + `${trigger}${item.name} ` + afterCursor;

    setUserInput(newValue);
    setShowAutocomplete(false);
    setAutocompleteType(null);

    if (autocompleteType === 'context') {
      // 读取文件内容作为附件
      if (item.path) {
        fileService.readFile(item.path).then(content => {
          setAttachments(prev => {
            if (prev.find(a => a.path === item.path)) return prev;
            return [...prev, {
              id: item.id,
              name: item.name,
              type: 'text' as const,
              content,
              path: item.path,
            }];
          });
        }).catch(() => {
          // 读取失败则降级为上下文引用
          setContexts(prev => {
            if (prev.find(c => c.id === item.id)) return prev;
            return [...prev, { id: item.id, type: 'file' as const, name: item.name, path: item.path }];
          });
        });
      }
    }

    if (autocompleteType === 'agent') {
      const agent = availableAgents.find(a => a.id === item.id || a.name === item.name);
      if (agent) {
        setSelectedAgent(agent.name);
      }
    }

    setTimeout(() => {
      if (textareaRef.current) {
        const newPos = beforeTrigger.length + trigger.length + item.name.length + 1;
        textareaRef.current.focus();
        textareaRef.current.setSelectionRange(newPos, newPos);
      }
    }, 0);
  }

  // 键盘导航
  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (showAutocomplete) {
      const options = getFilteredOptions();
      if (options.length > 0) {
        if (event.key === 'ArrowDown') {
          event.preventDefault();
          setSelectedIndex(prev => (prev + 1) % options.length);
          return;
        }
        if (event.key === 'ArrowUp') {
          event.preventDefault();
          setSelectedIndex(prev => (prev - 1 + options.length) % options.length);
          return;
        }
        if (event.key === 'Tab' || (event.key === 'Enter' && !event.shiftKey)) {
          event.preventDefault();
          const selected = options[selectedIndex];
          if (selected) {
            selectAutocompleteItem(selected);
          }
          return;
        }
        if (event.key === 'Escape') {
          setShowAutocomplete(false);
          return;
        }
      }
    }

    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      sendMessage();
    }
  }

  const canSend = Boolean(userInput.trim())
    && (!selectedAgent || userInput.trim() !== `@${selectedAgent}`);

  function sendMessage() {
    if (!canSend) return;
    const provider = allModels.find(p => p.id === selectedModel);
    onSend(userInput, {
      model: selectedModel,
      modelName: provider?.model || selectedModel,
      agent: selectedAgent,
      contexts: [...contexts],
      attachments: [...attachments],
      reasoningEffort,
    });
    setUserInput('');
    setContexts([]);
    setAttachments([]);
    setSelectedAgent('');
    setShowAutocomplete(false);
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    sendMessage();
  }

  // 移除上下文引用
  function removeContext(id: string) {
    setContexts(prev => prev.filter(c => c.id !== id));
    const context = contexts.find(c => c.id === id);
    if (context) {
      setUserInput(prev => prev.replace(new RegExp(`#${context.name}\\s*`, 'g'), ''));
    }
  }

  // 点击外部关闭自动完成
  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (autocompleteRef.current && !autocompleteRef.current.contains(event.target as Node)) {
        setShowAutocomplete(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  const filteredOptions = getFilteredOptions();
  const autocompleteAgentOptions = autocompleteType === 'agent'
    ? filteredOptions as AgentMenuOption[]
    : [];

  // 当前选中的 provider
  const currentProvider = allModels.find(p => p.id === selectedModel);
  const currentReasoning = REASONING_OPTIONS.find(item => item.key === reasoningEffort) || REASONING_OPTIONS[0];
  const effectiveContextTokenLimit = currentProvider?.contextLength || contextTokenLimit || 128000;
  const contextTokens = useMemo(() => {
    const attachmentTokens = attachments.reduce((sum, item) => {
      if (item.type === 'image') return sum + 1100;
      return sum + estimateTokens(item.content);
    }, 0);
    return baseContextTokens + estimateTokens(userInput) + attachmentTokens;
  }, [attachments, baseContextTokens, userInput]);
  const contextPercent = Math.min(100, Math.round((contextTokens / effectiveContextTokenLimit) * 100));

  return (
    <div className="chat-input-wrapper">
      {/* 附件预览 */}
      {attachments.length > 0 && (
        <div className="attachment-preview">
          {attachments.map(att => (
            <div key={att.id} className="attachment-item">
              {att.type === 'image' ? (
                <img src={att.content} alt={att.name} className="attachment-thumbnail" />
              ) : (
                <div className="attachment-file-icon">
                  <Icon name="file" size={16} />
                </div>
              )}
              <button className="attachment-remove" onClick={() => removeAttachment(att.id)}>
                <Icon name="close" size={10} />
              </button>
              <span className="attachment-name">{att.name}</span>
            </div>
          ))}
        </div>
      )}
      {/* 上下文标签 */}
      {(activeFileName || contexts.length > 0) && (
        <div className="context-tags">
          {activeFileName && (
            <span className="context-tag input-active-file">
              <Icon name="file" size={12} />
              <span>{activeFileName}</span>
            </span>
          )}
          {contexts.map(context => (
            <span key={context.id} className="context-tag">
              <Icon name="file" size={12} />
              <span>{context.name}</span>
              <button
                className="context-tag-remove"
                onClick={() => removeContext(context.id)}
              >
                <Icon name="close" size={10} />
              </button>
            </span>
          ))}
        </div>
      )}

      {/* 输入区域 */}
      <div className="input-area">
        <form onSubmit={handleSubmit} className="input-container">
          {/* 工具栏 */}
          <div className="input-toolbar">
          </div>

          {/* 输入行 */}
          <div className="input-row">
            <textarea
              ref={textareaRef}
              value={userInput}
              onChange={handleInput}
              onPaste={handlePaste}
              className="message-input"
              placeholder={currentProvider ? `${getModelDisplayName(currentProvider)}` : '输入消息...'}
              rows={1}
              onKeyDown={handleKeyDown}
            />
          </div>

          {/* 底部操作栏 */}
          <div className="input-bottom-bar">
            {/* 附件 */}
            <button
              type="button"
              className="toolbar-btn"
              title="添加附件"
              onClick={async () => {
                const result = await fileService.openFileDialog({ multiple: true });
                if (!result) return;
                const paths = Array.isArray(result) ? result : [result];
                for (const filePath of paths) {
                  const name = filePath.split(/[/\\]/).pop() || filePath;
                  const isImage = isImageFile(filePath);
                  try {
                    if (isImage) {
                      const base64 = await invoke<string>('read_file_binary', { path: filePath });
                      const ext = filePath.split('.').pop()?.toLowerCase() || 'png';
                      const mime = `image/${ext === 'jpg' ? 'jpeg' : ext}`;
                      setAttachments(prev => {
                        if (prev.find(a => a.path === filePath)) return prev;
                        return [...prev, { id: `img-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`, name, type: 'image' as const, content: `data:${mime};base64,${base64}`, path: filePath }];
                      });
                    } else {
                      const content = await fileService.readFile(filePath);
                      setAttachments(prev => {
                        if (prev.find(a => a.path === filePath)) return prev;
                        return [...prev, { id: `file-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`, name, type: 'text' as const, content, path: filePath }];
                      });
                    }
                  } catch { /* ignore */ }
                }
              }}
            >
              <Icon name="attach" size={14} />
            </button>

            {/* 模型 */}
            <div className="model-picker-wrapper" ref={modelPickerRef}>
              <button
                type="button"
                className={`model-picker-btn${showModelPicker ? ' active' : ''}`}
                onClick={() => {
                  setShowModelPicker(open => !open);
                  setShowReasoningPicker(false);
                  setShowAutocomplete(false);
                }}
                title={`选择模型：${currentProvider ? getModelDisplayName(currentProvider) : '未选择'}`}
              >
                <Icon name="model" size={14} className="model-picker-icon" />
                <span className="model-picker-name">
                  {currentProvider ? getModelDisplayName(currentProvider) : '选择模型'}
                </span>
                <span className={`model-picker-arrow${showModelPicker ? ' open' : ''}`}>▾</span>
              </button>
            </div>

            {/* 推理程度 */}
            <div className="model-picker-wrapper" ref={reasoningPickerRef}>
              <button
                type="button"
                className={`model-picker-btn reasoning-picker-btn${showReasoningPicker ? ' active' : ''}`}
                onClick={() => {
                  setShowReasoningPicker(open => !open);
                  setShowModelPicker(false);
                  setShowAutocomplete(false);
                }}
                title={`推理强度：${currentReasoning.label}`}
              >
                <span className="model-picker-name">{currentReasoning.label}</span>
                <span className={`model-picker-arrow${showReasoningPicker ? ' open' : ''}`}>▾</span>
              </button>
            </div>

            <span
              className="context-meter spacer"
              title="上下文用量"
              aria-label="上下文用量"
              style={{ '--context-percent': `${contextPercent}%` } as React.CSSProperties}
            >
              <span className="context-meter-ring" />
            </span>
            {(typeof window !== 'undefined' && ((window as any).SpeechRecognition || (window as any).webkitSpeechRecognition)) && (
              <button
                type="button"
                className={`toolbar-btn${voiceRecording ? ' recording' : ''}`}
                title={voiceRecording ? '松开结束' : '按住说话'}
                onMouseDown={(e) => { e.preventDefault(); voiceBtnHandlers.onStart(); }}
                onMouseUp={(e) => { e.preventDefault(); voiceBtnHandlers.onStop(); }}
                onMouseLeave={() => { if (voiceRecording) voiceBtnHandlers.onStop(); }}
                onTouchStart={(e) => { e.preventDefault(); voiceBtnHandlers.onStart(); }}
                onTouchEnd={(e) => { e.preventDefault(); voiceBtnHandlers.onStop(); }}
                onTouchCancel={() => { if (voiceRecording) voiceBtnHandlers.onStop(); }}
              >
                <Icon name="mic" size={14} />
              </button>
            )}
            <button
              type={isLoading && onStop ? 'button' : 'submit'}
              className={`send-stop-button${isLoading ? ' stopping' : ''}`}
              disabled={!isLoading && !canSend}
              onClick={isLoading && onStop ? onStop : undefined}
              title={isLoading && onStop ? '停止生成' : '发送'}
            >
              <Icon name={isLoading && onStop ? 'close' : 'push'} size={isLoading && onStop ? 13 : 17} />
            </button>
          </div>
        </form>

        {showModelPicker && (
          <div className="input-picker-dropdown model-picker-dropdown" ref={modelPickerPanelRef}>
            <div className="input-picker-header">选择模型</div>
            <div className="input-picker-list">
              {allModels.length === 0 ? (
                <div className="model-picker-empty">暂无可用模型</div>
              ) : (
                allModels.map(p => {
                  const label = getModelDisplayName(p);
                  const isActive = p.id === selectedModel;
                  return (
                    <button
                      key={p.id}
                      type="button"
                      className={`model-picker-item${isActive ? ' active' : ''}`}
                      onClick={() => {
                        setSelectedModel(p.id);
                        onModelChange?.(p.id);
                        setShowModelPicker(false);
                      }}
                    >
                      <span className="model-picker-item-name">{label}</span>
                      <span className="model-picker-item-source">{p.name}</span>
                      {isActive && <span className="model-picker-check">✓</span>}
                    </button>
                  );
                })
              )}
            </div>
          </div>
        )}

        {showReasoningPicker && (
          <div className="input-picker-dropdown model-picker-dropdown" ref={reasoningPickerPanelRef}>
            <div className="input-picker-header">选择推理强度</div>
            <div className="input-picker-list">
              {REASONING_OPTIONS.map(item => (
                <button
                  key={item.key}
                  type="button"
                  className={`model-picker-item${reasoningEffort === item.key ? ' active' : ''}`}
                  onClick={() => {
                    setReasoningEffort(item.key);
                    localStorage.setItem('soloncode-reasoning-effort', item.key);
                    setShowReasoningPicker(false);
                  }}
                >
                  <span className="model-picker-item-name">{item.label}</span>
                  <span className="model-picker-item-source">{item.desc}</span>
                  {reasoningEffort === item.key && <span className="model-picker-check">✓</span>}
                </button>
              ))}
            </div>
          </div>
        )}

        {/* 自动完成下拉框 */}
        {showAutocomplete && autocompleteType === 'agent' && (
          <div className="agent-picker-dropdown autocomplete-agent-picker" ref={autocompleteRef}>
            <AgentPickerMenu
              agents={autocompleteAgentOptions}
              activeIndex={selectedIndex}
              emptyText={!hintsLoadedRef.current ? '加载中...' : '没有匹配项'}
              onSelect={(agentName) => selectAutocompleteItem({ id: agentName, name: agentName })}
              footer={<><span>↑↓ 选择</span><span>Tab 确认</span><span>Esc 关闭</span></>}
            />
          </div>
        )}
        {showAutocomplete && autocompleteType !== 'agent' && (
          <div className="autocomplete-dropdown" ref={autocompleteRef}>
            <div className="autocomplete-header">
              {autocompleteType === 'command' ? '命令' : autocompleteType === 'skill' ? '选择 Skill' : '引用文件'}
            </div>
            <div className="autocomplete-list">
              {filteredOptions.length === 0 ? (
                <div className="autocomplete-empty">
                  {(autocompleteType === 'command' || autocompleteType === 'agent') && !hintsLoadedRef.current ? '加载中...' : '没有匹配项'}
                </div>
              ) : filteredOptions.map((option, index) => (
                <div
                  key={option.id || (option as any).name}
                  className={`autocomplete-item${index === selectedIndex ? ' selected' : ''}`}
                  onClick={() => selectAutocompleteItem(option)}
                >
                  <span className="item-icon">
                    <Icon name={
                      autocompleteType === 'command'
                        ? 'terminal'
                        : autocompleteType === 'skill'
                          ? 'skills'
                        : autocompleteType === 'agent'
                          ? (option as any).icon || 'bot'
                          : (option as any).type === 'folder' ? 'folder' : 'file'
                    } size={12} />
                  </span>
                  <div className="item-info">
                    <span className="item-name">
                      {autocompleteType === 'command' ? `/${option.name}` : autocompleteType === 'skill' ? `$${option.name}` : option.name}
                    </span>
                    {(option as any).description && (
                      <span className="item-desc">{(option as any).description}</span>
                    )}
                    {(option as any).path && (
                      <span className="item-path">{(option as any).path}</span>
                    )}
                  </div>
                </div>
              ))}
            </div>
            <div className="autocomplete-footer">
              <span>↑↓ 选择</span>
              <span>Tab 确认</span>
              <span>Esc 关闭</span>
            </div>
          </div>
        )}
      </div>
      {showStartWork && (
        <StartWorkPanel onNewProject={onNewProject} onOpenFolder={onOpenFolder} />
      )}
    </div>
  );
}
