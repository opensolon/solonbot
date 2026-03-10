<script setup lang="ts">
// 全局样式和主题管理在这里
</script>

<template>
  <router-view />
</template>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

:root {
  /* 暗色主题变量（默认） */
  --cb-vscode-editor-background: #1e1e1e;
  --cb-vscode-sideBar-background: #252526;
  --cb-vscode-input-background: #3c3c3c;
  --cb-vscode-foreground: #cccccc;
  --cb-vscode-button-background: #0e639c;
  --cb-vscode-button-foreground: #ffffff;
  --cb-vscode-button-hoverBackground: #1177bb;
  --cb-vscode-list-hoverBackground: #2a2d2e;
  --cb-vscode-list-activeSelectionBackground: #094771;
  --cb-vscode-widget-border: #414141;
  --cb-vscode-panel-border: #3c3c3c;
  --cb-text-primary: #d2d3e0;
  --cb-text-secondary: #858699;
  --cb-border-color: #4a5568;
  --cb-accent: #6b7cff;
  --cb-accent-light: #7b8dff;
  --cb-success: #4ade80;
}

/* 亮色主题变量 */
[data-theme="light"] {
  --cb-vscode-editor-background: #ffffff;
  --cb-vscode-sideBar-background: #f3f3f3;
  --cb-vscode-input-background: #f5f5f5;
  --cb-vscode-foreground: #333333;
  --cb-vscode-button-background: #007acc;
  --cb-vscode-button-foreground: #ffffff;
  --cb-vscode-button-hoverBackground: #0062a3;
  --cb-vscode-list-hoverBackground: #e8e8e8;
  --cb-vscode-list-activeSelectionBackground: #e1e1e1;
  --cb-vscode-widget-border: #d0d7de;
  --cb-vscode-panel-border: #e1e1e1;
  --cb-text-primary: #24292f;
  --cb-text-secondary: #656d76;
  --cb-border-color: #d0d7de;
  --cb-accent: #bbe3b3;
  --cb-accent-light: #0b57d0;
  --cb-success: #1a7f37;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Roboto", "Oxygen", "Ubuntu", "Cantarell", "Fira Sans", "Droid Sans", "Helvetica Neue", sans-serif;
  font-size: 14px;
  line-height: 1.5;
  color: var(--cb-vscode-foreground);
  background-color: var(--cb-vscode-editor-background);
  overflow: hidden;
  transition: background-color 0.3s, color 0.3s;
}

#app {
  width: 100vw;
  height: 100vh;
}

.app-container {
  display: flex;
  width: 100%;
  height: 100%;
}

::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

::-webkit-scrollbar-track {
  background: transparent;
}

::-webkit-scrollbar-thumb {
  background: var(--cb-vscode-widget-border);
  border-radius: 4px;
}

::-webkit-scrollbar-thumb:hover {
  background: var(--cb-border-color);
}
</style>
