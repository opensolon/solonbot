use serde::{Deserialize, Serialize};
use std::fs;
use std::path::Path;

#[derive(Debug, Serialize, Deserialize)]
pub struct FileInfo {
    name: String,
    path: String,
    is_dir: bool,
    children: Option<Vec<FileInfo>>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct WorkspaceInfo {
    path: String,
    name: String,
}

/// 读取文件内容
#[tauri::command]
fn read_file(path: &str) -> Result<String, String> {
    fs::read_to_string(path).map_err(|e| format!("读取文件失败: {}", e))
}

/// 写入文件内容
#[tauri::command]
fn write_file(path: &str, content: &str) -> Result<(), String> {
    fs::write(path, content).map_err(|e| format!("写入文件失败: {}", e))
}

/// 列出目录内容
#[tauri::command]
fn list_directory(path: &str) -> Result<Vec<FileInfo>, String> {
    let entries = fs::read_dir(path).map_err(|e| format!("读取目录失败: {}", e))?;

    let mut files = Vec::new();
    for entry in entries {
        if let Ok(entry) = entry {
            let path_buf = entry.path();
            let is_dir = path_buf.is_dir();
            let name = path_buf
                .file_name()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_default();

            files.push(FileInfo {
                name,
                path: path_buf.to_string_lossy().to_string(),
                is_dir,
                children: None,
            });
        }
    }

    // 排序：文件夹优先，然后按名称排序
    files.sort_by(|a, b| {
        if a.is_dir == b.is_dir {
            a.name.to_lowercase().cmp(&b.name.to_lowercase())
        } else if a.is_dir {
            std::cmp::Ordering::Less
        } else {
            std::cmp::Ordering::Greater
        }
    });

    Ok(files)
}

/// 递归列出目录树
#[tauri::command]
fn list_directory_tree(path: &str, max_depth: usize) -> Result<Vec<FileInfo>, String> {
    fn build_tree(path: &Path, current_depth: usize, max_depth: usize) -> Option<Vec<FileInfo>> {
        if current_depth > max_depth {
            return None;
        }

        let entries = match fs::read_dir(path) {
            Ok(e) => e,
            Err(_) => return None,
        };

        let mut files = Vec::new();
        for entry in entries.flatten() {
            let path_buf = entry.path();
            let is_dir = path_buf.is_dir();
            let name = path_buf
                .file_name()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_default();

            // 跳过隐藏文件和常见忽略目录
            if name.starts_with('.') || name == "node_modules" || name == "target" {
                continue;
            }

            let children = if is_dir && current_depth < max_depth {
                build_tree(&path_buf, current_depth + 1, max_depth)
            } else {
                None
            };

            files.push(FileInfo {
                name,
                path: path_buf.to_string_lossy().to_string(),
                is_dir,
                children,
            });
        }

        // 排序：文件夹优先
        files.sort_by(|a, b| {
            if a.is_dir == b.is_dir {
                a.name.to_lowercase().cmp(&b.name.to_lowercase())
            } else if a.is_dir {
                std::cmp::Ordering::Less
            } else {
                std::cmp::Ordering::Greater
            }
        });

        Some(files)
    }

    let path = Path::new(path);
    build_tree(path, 0, max_depth).ok_or_else(|| "无法读取目录".to_string())
}

/// 创建新文件
#[tauri::command]
fn create_file(path: &str) -> Result<(), String> {
    // 确保父目录存在
    if let Some(parent) = Path::new(path).parent() {
        if !parent.exists() {
            fs::create_dir_all(parent).map_err(|e| format!("创建目录失败: {}", e))?;
        }
    }
    fs::write(path, "").map_err(|e| format!("创建文件失败: {}", e))
}

/// 创建新目录
#[tauri::command]
fn create_directory(path: &str) -> Result<(), String> {
    fs::create_dir_all(path).map_err(|e| format!("创建目录失败: {}", e))
}

/// 删除文件
#[tauri::command]
fn delete_file(path: &str) -> Result<(), String> {
    fs::remove_file(path).map_err(|e| format!("删除文件失败: {}", e))
}

/// 删除目录
#[tauri::command]
fn delete_directory(path: &str) -> Result<(), String> {
    fs::remove_dir_all(path).map_err(|e| format!("删除目录失败: {}", e))
}

/// 重命名文件或目录
#[tauri::command]
fn rename_item(old_path: &str, new_path: &str) -> Result<(), String> {
    fs::rename(old_path, new_path).map_err(|e| format!("重命名失败: {}", e))
}

/// 检查路径是否存在
#[tauri::command]
fn path_exists(path: &str) -> bool {
    Path::new(path).exists()
}

/// 获取工作区信息
#[tauri::command]
fn get_workspace_info(path: &str) -> Result<WorkspaceInfo, String> {
    let path_buf = Path::new(path);
    if !path_buf.exists() {
        return Err("路径不存在".to_string());
    }

    let name = path_buf
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "工作区".to_string());

    Ok(WorkspaceInfo {
        path: path_buf.to_string_lossy().to_string(),
        name,
    })
}

/// 初始化工作区配置
#[tauri::command]
fn init_workspace_config(workspace_path: &str) -> Result<String, String> {
    let workspace = Path::new(workspace_path);
    if !workspace.exists() {
        return Err("工作区路径不存在".to_string());
    }

    // 创建 .soloncode 目录
    let soloncode_dir = workspace.join(".soloncode");
    if !soloncode_dir.exists() {
        fs::create_dir_all(&soloncode_dir)
            .map_err(|e| format!("创建 .soloncode 目录失败: {}", e))?;
    }

    // 创建 settings.json 文件（如果不存在）
    let settings_file = soloncode_dir.join("settings.json");
    if !settings_file.exists() {
        let default_settings = r#"{
  "version": "1.0.0",
  "project": {
    "name": "",
    "description": ""
  },
  "ai": {
    "model": "glm-4.7",
    "maxSteps": 30
  },
  "editor": {
    "fontSize": 14,
    "tabSize": 2,
    "autoSave": true
  }
}"#;
        fs::write(&settings_file, default_settings)
            .map_err(|e| format!("创建 settings.json 失败: {}", e))?;
    }

    Ok(settings_file.to_string_lossy().to_string())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_fs::init())
        .invoke_handler(tauri::generate_handler![
            read_file,
            write_file,
            list_directory,
            list_directory_tree,
            create_file,
            create_directory,
            delete_file,
            delete_directory,
            rename_item,
            path_exists,
            get_workspace_info,
            init_workspace_config
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
