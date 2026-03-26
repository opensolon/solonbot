import { useState } from 'react';
import { Icon } from '../common/Icon';
import './SettingsPanel.css';

export interface Settings {
  // AI 配置
  apiUrl: string;
  apiKey: string;
  model: string;
  maxSteps: number;

  // 外观配置
  theme: 'dark' | 'light';
  fontSize: number;
  language: string;

  // 编辑器配置
  tabSize: number;
  autoSave: boolean;
  formatOnSave: boolean;

  // 终端配置
  shell: string;
  terminalFontSize: number;
}

interface SettingsPanelProps {
  settings: Settings;
  onSettingsChange: (settings: Settings) => void;
}

export function SettingsPanel({ settings, onSettingsChange }: SettingsPanelProps) {
  const [searchQuery, setSearchQuery] = useState('');

  function updateSetting<K extends keyof Settings>(key: K, value: Settings[K]) {
    onSettingsChange({ ...settings, [key]: value });
  }

  return (
    <div className="settings-panel">
      <div className="panel-header">
        <span className="panel-title">设置</span>
      </div>

      <div className="search-container">
        <div className="search-wrapper">
          <Icon name="search" size={14} className="search-prefix" />
          <input
            type="text"
            className="search-input"
            placeholder="搜索设置..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
          />
        </div>
      </div>

      <div className="settings-list">
        <div className="settings-section">
          <div className="section-header">
            <Icon name="bot" size={16} />
            <span>AI 配置</span>
          </div>

          <div className="setting-item">
            <label className="setting-label">API 地址</label>
            <input
              type="text"
              className="setting-input"
              value={settings.apiUrl}
              onChange={(e) => updateSetting('apiUrl', e.target.value)}
              placeholder="https://api.example.com/v1/chat/completions"
            />
          </div>

          <div className="setting-item">
            <label className="setting-label">API Key</label>
            <input
              type="password"
              className="setting-input"
              value={settings.apiKey}
              onChange={(e) => updateSetting('apiKey', e.target.value)}
              placeholder="sk-..."
            />
          </div>

          <div className="setting-item">
            <label className="setting-label">模型</label>
            <select
              className="setting-select"
              value={settings.model}
              onChange={(e) => updateSetting('model', e.target.value)}
            >
              <option value="glm-4.7">GLM-4.7</option>
              <option value="gpt-4">GPT-4</option>
              <option value="gpt-4o">GPT-4o</option>
              <option value="claude-3-opus">Claude 3 Opus</option>
              <option value="claude-3-sonnet">Claude 3 Sonnet</option>
              <option value="deepseek-chat">DeepSeek Chat</option>
            </select>
          </div>

          <div className="setting-item">
            <label className="setting-label">最大步数</label>
            <input
              type="number"
              className="setting-input number"
              value={settings.maxSteps}
              onChange={(e) => updateSetting('maxSteps', parseInt(e.target.value) || 30)}
              min={1}
              max={100}
            />
          </div>
        </div>

        <div className="settings-section">
          <div className="section-header">
            <Icon name="theme" size={16} />
            <span>外观</span>
          </div>

          <div className="setting-item">
            <label className="setting-label">主题</label>
            <select
              className="setting-select"
              value={settings.theme}
              onChange={(e) => updateSetting('theme', e.target.value as 'dark' | 'light')}
            >
              <option value="dark">暗色</option>
              <option value="light">亮色</option>
            </select>
          </div>

          <div className="setting-item">
            <label className="setting-label">字体大小</label>
            <input
              type="number"
              className="setting-input number"
              value={settings.fontSize}
              onChange={(e) => updateSetting('fontSize', parseInt(e.target.value) || 14)}
              min={10}
              max={24}
            />
          </div>

          <div className="setting-item">
            <label className="setting-label">语言</label>
            <select
              className="setting-select"
              value={settings.language}
              onChange={(e) => updateSetting('language', e.target.value)}
            >
              <option value="zh-CN">中文</option>
              <option value="en-US">English</option>
            </select>
          </div>
        </div>

        <div className="settings-section">
          <div className="section-header">
            <Icon name="code" size={16} />
            <span>编辑器</span>
          </div>

          <div className="setting-item">
            <label className="setting-label">Tab 大小</label>
            <input
              type="number"
              className="setting-input number"
              value={settings.tabSize}
              onChange={(e) => updateSetting('tabSize', parseInt(e.target.value) || 2)}
              min={1}
              max={8}
            />
          </div>

          <div className="setting-item checkbox">
            <label className="checkbox-label">
              <input
                type="checkbox"
                checked={settings.autoSave}
                onChange={(e) => updateSetting('autoSave', e.target.checked)}
              />
              <span>自动保存</span>
            </label>
          </div>

          <div className="setting-item checkbox">
            <label className="checkbox-label">
              <input
                type="checkbox"
                checked={settings.formatOnSave}
                onChange={(e) => updateSetting('formatOnSave', e.target.checked)}
              />
              <span>保存时格式化</span>
            </label>
          </div>
        </div>

        <div className="settings-section">
          <div className="section-header">
            <Icon name="terminal" size={16} />
            <span>终端</span>
          </div>

          <div className="setting-item">
            <label className="setting-label">Shell</label>
            <select
              className="setting-select"
              value={settings.shell}
              onChange={(e) => updateSetting('shell', e.target.value)}
            >
              <option value="bash">Bash</option>
              <option value="zsh">Zsh</option>
              <option value="powershell">PowerShell</option>
              <option value="cmd">CMD</option>
            </select>
          </div>

          <div className="setting-item">
            <label className="setting-label">终端字体大小</label>
            <input
              type="number"
              className="setting-input number"
              value={settings.terminalFontSize}
              onChange={(e) => updateSetting('terminalFontSize', parseInt(e.target.value) || 14)}
              min={10}
              max={24}
            />
          </div>
        </div>
      </div>
    </div>
  );
}
