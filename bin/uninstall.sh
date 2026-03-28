#!/bin/bash
# =============================================
#  Solon Code Uninstall Script (Linux / macOS)
# =============================================

echo ""
echo "============================================"
echo "   Solon Code Uninstaller"
echo "============================================"
echo ""

INSTALL_DIR="$(cd "$(dirname "$0")" && pwd)"

# 确认
read -p "Uninstall Solon Code from $INSTALL_DIR ? (Y/N): " -n 1 -r
echo ""
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Cancelled."
    exit 0
fi

# 检测操作系统
OS_TYPE="$(uname -s)"
echo "[Info] Detected OS: $OS_TYPE"

# ============================================
#  清理 shell 配置文件中的 PATH 配置
# ============================================
echo ""
echo "[1/3] Cleaning shell configuration files..."

clean_shell_config() {
    local config_file="$1"
    if [ -f "$config_file" ]; then
        # 备份
        cp "$config_file" "${config_file}.bak" 2>/dev/null
        
        # 移除 Solon Code 相关行
        if [[ "$OS_TYPE" == "Darwin" ]]; then
            # macOS sed
            sed -i '' '/# Solon Code/d' "$config_file" 2>/dev/null
            sed -i '' '/SOLONCODE_HOME/d' "$config_file" 2>/dev/null
            sed -i '' '/\.soloncode\/bin/d' "$config_file" 2>/dev/null
        else
            # Linux sed
            sed -i '/# Solon Code/d' "$config_file" 2>/dev/null
            sed -i '/SOLONCODE_HOME/d' "$config_file" 2>/dev/null
            sed -i '/\.soloncode\/bin/d' "$config_file" 2>/dev/null
        fi
        echo "      Cleaned: $config_file"
    fi
}

# 清理所有可能的配置文件
# zsh
clean_shell_config "$HOME/.zshrc"

# bash
clean_shell_config "$HOME/.bashrc"
clean_shell_config "$HOME/.bash_profile"
clean_shell_config "$HOME/.profile"

# Fish shell
FISH_CONFIG="$HOME/.config/fish/config.fish"
if [ -f "$FISH_CONFIG" ]; then
    cp "$FISH_CONFIG" "${FISH_CONFIG}.bak" 2>/dev/null
    if [[ "$OS_TYPE" == "Darwin" ]]; then
        sed -i '' '/# Solon Code/d' "$FISH_CONFIG" 2>/dev/null
        sed -i '' '/SOLONCODE_HOME/d' "$FISH_CONFIG" 2>/dev/null
        sed -i '' '/set -gx PATH.*soloncode/d' "$FISH_CONFIG" 2>/dev/null
    else
        sed -i '/# Solon Code/d' "$FISH_CONFIG" 2>/dev/null
        sed -i '/SOLONCODE_HOME/d' "$FISH_CONFIG" 2>/dev/null
        sed -i '/set -gx PATH.*soloncode/d' "$FISH_CONFIG" 2>/dev/null
    fi
    echo "      Cleaned: $FISH_CONFIG"
fi

# ============================================
#  删除符号链接
# ============================================
echo ""
echo "[2/3] Removing command symlinks..."

# 系统级链接
if [ -L "/usr/local/bin/soloncode" ] || [ -f "/usr/local/bin/soloncode" ]; then
    if [ "$(id -u)" -eq 0 ]; then
        rm -f /usr/local/bin/soloncode 2>/dev/null && echo "      Removed /usr/local/bin/soloncode"
    elif command -v sudo &> /dev/null; then
        sudo rm -f /usr/local/bin/soloncode 2>/dev/null && echo "      Removed /usr/local/bin/soloncode"
    fi
fi

# 用户级链接 (homebrew 或用户 bin)
if [ -L "$HOME/.local/bin/soloncode" ] || [ -f "$HOME/.local/bin/soloncode" ]; then
    rm -f "$HOME/.local/bin/soloncode" 2>/dev/null && echo "      Removed ~/.local/bin/soloncode"
fi

if [ -L "$HOME/bin/soloncode" ] || [ -f "$HOME/bin/soloncode" ]; then
    rm -f "$HOME/bin/soloncode" 2>/dev/null && echo "      Removed ~/bin/soloncode"
fi

# ============================================
#  清理启动脚本 (如果有安装在安装目录外)
# ============================================
echo ""
echo "[3/3] Cleaning up..."

# 检查是否有残留的启动脚本
for cmd_path in "/usr/local/bin/soloncode" "$HOME/.local/bin/soloncode" "$HOME/bin/soloncode"; do
    if [ -L "$cmd_path" ]; then
        rm -f "$cmd_path" 2>/dev/null
    fi
done

echo "      Cleanup complete"

# ============================================
#  完成
# ============================================
echo ""
echo "============================================"
echo "   Uninstall Complete!"
echo "============================================"
echo ""
echo "  Note: Config and session data preserved at:"
echo "        ~/.soloncode/"
echo "  To fully remove: rm -rf ~/.soloncode"
echo ""