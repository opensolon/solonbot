/**
 * 文件服务 - 封装 Tauri 文件操作 API
 * @author bai
 */
import { invoke } from '@tauri-apps/api/core';
import { open, save } from '@tauri-apps/plugin-dialog';
import { readFile, writeFile, readDir } from '@tauri-apps/plugin-fs';

// 文件信息接口
export interface FileInfo {
  name: string;
  path: string;
  isDir: boolean;
  children?: FileInfo[];
}

// 工作区信息接口
export interface WorkspaceInfo {
  path: string;
  name: string;
}

// 打开的文件接口
export interface OpenFile {
  path: string;
  name: string;
  content: string;
  modified: boolean;
  language: string;
}

/**
 * 文件服务类
 */
export const fileService = {
  /**
   * 打开文件对话框
   */
  async openFileDialog(options?: {
    multiple?: boolean;
    filters?: Array<{ name: string; extensions: string[] }>;
  }): Promise<string | string[] | null> {
    return await open({
      multiple: options?.multiple,
      filters: options?.filters,
      directory: false,
    });
  },

  /**
   * 打开文件夹对话框
   */
  async openFolderDialog(): Promise<string | null> {
    const result = await open({
      directory: true,
      multiple: false,
    });
    return result as string | null;
  },

  /**
   * 保存文件对话框
   */
  async saveFileDialog(options?: {
    defaultPath?: string;
    filters?: Array<{ name: string; extensions: string[] }>;
  }): Promise<string | null> {
    return await save({
      defaultPath: options?.defaultPath,
      filters: options?.filters,
    });
  },

  /**
   * 读取文件内容
   */
  async readFile(path: string): Promise<string> {
    try {
      // 优先使用插件 API
      const bytes = await readFile(path);
      return new TextDecoder().decode(bytes);
    } catch {
      // 回退到 invoke
      return await invoke<string>('read_file', { path });
    }
  },

  /**
   * 写入文件内容
   */
  async writeFile(path: string, content: string): Promise<void> {
    try {
      // 优先使用插件 API
      const bytes = new TextEncoder().encode(content);
      await writeFile(path, bytes);
    } catch {
      // 回退到 invoke
      await invoke<void>('write_file', { path, content });
    }
  },

  /**
   * 列出目录内容
   */
  async listDirectory(path: string): Promise<FileInfo[]> {
    return await invoke<FileInfo[]>('list_directory', { path });
  },

  /**
   * 递归列出目录树
   */
  async listDirectoryTree(path: string, maxDepth: number = 5): Promise<FileInfo[]> {
    return await invoke<FileInfo[]>('list_directory_tree', { path, maxDepth });
  },

  /**
   * 创建新文件
   */
  async createFile(path: string): Promise<void> {
    await invoke<void>('create_file', { path });
  },

  /**
   * 创建新目录
   */
  async createDirectory(path: string): Promise<void> {
    await invoke<void>('create_directory', { path });
  },

  /**
   * 删除文件
   */
  async deleteFile(path: string): Promise<void> {
    await invoke<void>('delete_file', { path });
  },

  /**
   * 删除目录
   */
  async deleteDirectory(path: string): Promise<void> {
    await invoke<void>('delete_directory', { path });
  },

  /**
   * 重命名文件或目录
   */
  async renameItem(oldPath: string, newPath: string): Promise<void> {
    await invoke<void>('rename_item', { oldPath, newPath });
  },

  /**
   * 检查路径是否存在
   */
  async pathExists(path: string): Promise<boolean> {
    return await invoke<boolean>('path_exists', { path });
  },

  /**
   * 获取工作区信息
   */
  async getWorkspaceInfo(path: string): Promise<WorkspaceInfo> {
    return await invoke<WorkspaceInfo>('get_workspace_info', { path });
  },

  /**
   * 获取文件语言类型
   */
  getLanguageFromPath(path: string): string {
    const ext = path.split('.').pop()?.toLowerCase() || '';
    const langMap: Record<string, string> = {
      'ts': 'TypeScript',
      'tsx': 'TypeScript React',
      'js': 'JavaScript',
      'jsx': 'JavaScript React',
      'json': 'JSON',
      'css': 'CSS',
      'scss': 'SCSS',
      'less': 'Less',
      'html': 'HTML',
      'md': 'Markdown',
      'py': 'Python',
      'java': 'Java',
      'rs': 'Rust',
      'go': 'Go',
      'vue': 'Vue',
      'xml': 'XML',
      'yaml': 'YAML',
      'yml': 'YAML',
      'toml': 'TOML',
      'sh': 'Shell',
      'bash': 'Bash',
    };
    return langMap[ext] || 'Plain Text';
  },

  /**
   * 打开文件并返回 OpenFile 对象
   */
  async openFile(path: string): Promise<OpenFile> {
    const content = await this.readFile(path);
    const name = path.split(/[/\\]/).pop() || '';
    const language = this.getLanguageFromPath(path);

    return {
      path,
      name,
      content,
      modified: false,
      language,
    };
  },
};

export default fileService;
