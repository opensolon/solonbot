import { useCallback, useEffect, useMemo, useState } from 'react';
import { invoke } from '@tauri-apps/api/core';
import { Icon } from '../common/Icon';
import type { AgentConfig } from '../../services/settingsService';
import { fileService } from '../../services/fileService';
import { getManagedResourceNameError } from '../../services/managedResourceService';
import { agentConfigFilePath } from '../../utils/agentPath';
import './AgentDetail.css';

const AGENT_TOOL_OPTIONS = [
  { id: 'read', label: '读取文件', description: '查看文件内容' },
  { id: 'write', label: '写入文件', description: '创建或覆盖文件' },
  { id: 'edit', label: '编辑文件', description: '修改已有文件' },
  { id: 'bash', label: 'Bash', description: '执行终端命令' },
  { id: 'codesearch', label: '代码搜索', description: '搜索工作区代码' },
  { id: 'websearch', label: '网络搜索', description: '检索公开网页' },
  { id: 'webfetch', label: '网页访问', description: '读取指定网页' },
  { id: 'lsp', label: '代码分析', description: '使用语言服务' },
  { id: 'mcp', label: 'MCP', description: '调用已配置的 MCP 工具' },
  { id: 'restapi', label: 'Web API', description: '调用已配置的 API 工具' },
] as const;

type AgentToolId = typeof AGENT_TOOL_OPTIONS[number]['id'];

interface AgentDraft {
  name: string;
  description: string;
  prompt: string;
  tools: AgentToolId[];
}

interface AgentDetailProps {
  agent: AgentConfig;
  onSaved: (agent: AgentConfig) => void;
  onClose: () => void;
}

function parseScalar(value: string) {
  const trimmed = value.trim();
  if (trimmed.startsWith('"')) {
    try {
      return String(JSON.parse(trimmed));
    } catch {
      // Fallback for hand-written YAML that is not JSON-compatible.
    }
  }
  return trimmed.replace(/^['"]|['"]$/g, '').trim();
}

function parseTools(value: string): AgentToolId[] {
  const supported = new Set<string>(AGENT_TOOL_OPTIONS.map(tool => tool.id));
  const selected = new Set<string>();
  for (const raw of value.replace(/^\[|\]$/g, '').split(',')) {
    const tool = parseScalar(raw).toLowerCase();
    if (supported.has(tool)) selected.add(tool);
  }
  return AGENT_TOOL_OPTIONS.filter(tool => selected.has(tool.id)).map(tool => tool.id);
}

function parseAgentContent(content: string, agent: AgentConfig): AgentDraft {
  const lines = content.replace(/\r\n/g, '\n').split('\n');
  let name = agent.name;
  let description = agent.description;
  let tools: AgentToolId[] = [];
  let bodyStart = 0;

  if (lines[0]?.trim() === '---') {
    const end = lines.findIndex((line, index) => index > 0 && line.trim() === '---');
    if (end > 0) {
      for (let index = 1; index < end; index += 1) {
        const match = lines[index].match(/^([A-Za-z][\w-]*):\s*(.*)$/);
        if (!match) continue;
        const [, key, value] = match;
        if (key === 'name') name = parseScalar(value) || name;
        if (key === 'description') description = parseScalar(value);
        if (key === 'tools' || key === 'allowed-tools') tools = parseTools(value);
      }
      bodyStart = end + 1;
    }
  }

  return {
    name,
    description,
    prompt: lines.slice(bodyStart).join('\n').trim() || `# ${name}\n\n在此编写你的 Agent 指令...`,
    tools,
  };
}

function draftChanged(initial: AgentDraft, draft: AgentDraft) {
  return initial.name !== draft.name
    || initial.description !== draft.description
    || initial.prompt !== draft.prompt
    || initial.tools.join(',') !== draft.tools.join(',');
}

export function AgentDetail({ agent, onSaved, onClose }: AgentDetailProps) {
  const [initialDraft, setInitialDraft] = useState<AgentDraft | null>(null);
  const [draft, setDraft] = useState<AgentDraft | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError('');
    fileService.readFile(agentConfigFilePath(agent.path))
      .then(content => {
        if (cancelled) return;
        const parsed = parseAgentContent(content, agent);
        setInitialDraft(parsed);
        setDraft(parsed);
      })
      .catch(error => {
        console.error('[AgentDetail] 读取 Agent 配置失败:', error);
        if (!cancelled) setError('无法读取 Agent 配置，请稍后重试');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => { cancelled = true; };
  }, [agent]);

  const validationError = useMemo(() => {
    if (!draft) return '';
    const nameError = getManagedResourceNameError(draft.name);
    if (nameError) return nameError;
    if (!draft.description.trim()) return 'Agent 简介不能为空';
    if (Array.from(draft.description.trim()).length > 240) return 'Agent 简介不能超过 240 个字符';
    if (!draft.prompt.trim()) return '提示词不能为空';
    if (draft.prompt.length > 20_000) return '提示词不能超过 20,000 个字符';
    return '';
  }, [draft]);

  const dirty = Boolean(initialDraft && draft && draftChanged(initialDraft, draft));

  const toggleTool = useCallback((toolId: AgentToolId) => {
    setDraft(current => {
      if (!current) return current;
      const selected = new Set(current.tools);
      if (selected.has(toolId)) selected.delete(toolId);
      else selected.add(toolId);
      return {
        ...current,
        tools: AGENT_TOOL_OPTIONS.filter(tool => selected.has(tool.id)).map(tool => tool.id),
      };
    });
  }, []);

  const handleSave = useCallback(async () => {
    if (!draft || !dirty || validationError || saving) return;
    setSaving(true);
    setError('');
    try {
      const saved = await invoke<Pick<AgentConfig, 'name' | 'description' | 'path' | 'enabled'>>('update_agent_config', {
        agentPath: agent.path,
        agentScope: agent.scope || 'system',
        projectPath: agent.scope === 'project' ? agent.projectPath : undefined,
        name: draft.name.trim(),
        description: draft.description.trim(),
        prompt: draft.prompt.trim(),
        tools: draft.tools,
      });
      const updated = { ...agent, ...saved };
      const nextDraft = { ...draft, name: saved.name, description: saved.description };
      setInitialDraft(nextDraft);
      setDraft(nextDraft);
      onSaved(updated);
    } catch (error) {
      console.error('[AgentDetail] 保存 Agent 配置失败:', error);
      setError('保存失败，请检查配置后重试');
    } finally {
      setSaving(false);
    }
  }, [agent, dirty, draft, onSaved, saving, validationError]);

  return (
    <div className="agent-detail">
      <div className="agent-detail-header">
        <div className="agent-detail-heading"><Icon name="agents" size={16} /><span>Agent 设置</span></div>
        <button type="button" className="agent-detail-close" title="关闭设置" onClick={onClose}><Icon name="close" size={14} /></button>
      </div>

      {loading || !draft ? (
        <div className="agent-detail-loading">{error || '加载 Agent 配置...'}</div>
      ) : (
        <>
          <div className="agent-detail-scroll">
            <div className="agent-detail-hero">
              <div className="agent-detail-name-field">
                <label htmlFor="agent-detail-name">Agent 名称</label>
                <input
                  id="agent-detail-name"
                  value={draft.name}
                  maxLength={64}
                  disabled={saving}
                  onChange={event => setDraft(current => current ? { ...current, name: event.target.value } : current)}
                />
                <p>保存名称时将同步更新 Agent 目录和 AGENT.md。</p>
              </div>
              <span className={`agent-detail-status${agent.enabled ? '' : ' disabled'}`}>{agent.enabled ? '已启用' : '已停用'}</span>
            </div>

            <section className="agent-detail-section">
              <h3>简介</h3>
              <textarea
                className="agent-detail-description"
                value={draft.description}
                maxLength={240}
                rows={2}
                disabled={saving}
                placeholder="简要说明 Agent 的职责和适用场景"
                onChange={event => setDraft(current => current ? { ...current, description: event.target.value } : current)}
              />
            </section>

            <section className="agent-detail-section">
              <h3>提示词</h3>
              <textarea
                className="agent-detail-prompt"
                value={draft.prompt}
                maxLength={20_000}
                rows={10}
                disabled={saving}
                placeholder="定义 Agent 的角色、行为准则和工作流程"
                onChange={event => setDraft(current => current ? { ...current, prompt: event.target.value } : current)}
              />
            </section>

            <section className="agent-detail-section">
              <div className="agent-tools-heading">
                <div><h3>可用工具</h3><p>只授予此 Agent 完成任务所需的工具权限。</p></div>
                <span>{draft.tools.length} 个已选择</span>
              </div>
              <div className="agent-tools-grid">
                {AGENT_TOOL_OPTIONS.map(tool => {
                  const selected = draft.tools.includes(tool.id);
                  return (
                    <button
                      key={tool.id}
                      type="button"
                      role="checkbox"
                      aria-checked={selected}
                      disabled={saving}
                      className={`agent-tool-option${selected ? ' selected' : ''}`}
                      onClick={() => toggleTool(tool.id)}
                    >
                      <span className="agent-tool-option-check"><Icon name={selected ? 'check' : 'add'} size={12} /></span>
                      <span><strong>{tool.label}</strong><small>{tool.description}</small></span>
                    </button>
                  );
                })}
              </div>
            </section>
          </div>

          <div className="agent-detail-actions">
            {(error || (dirty && validationError)) && <span className="agent-detail-save-error">{error || validationError}</span>}
            <button type="button" className="agent-detail-save" disabled={!dirty || Boolean(validationError) || saving} onClick={() => { void handleSave(); }}>
              <Icon name={saving ? 'loading' : 'save'} size={13} />{saving ? '保存中' : '保存更改'}
            </button>
          </div>
        </>
      )}
    </div>
  );
}
