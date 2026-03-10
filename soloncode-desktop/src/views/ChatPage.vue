<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { type Conversation, type Plugin } from '../types';
import { saveConversation, getAllConversations } from '../db';
import Sidebar from '../components/Sidebar.vue';
import ChatView from '../components/ChatView.vue';

const plugins = ref<Plugin[]>([
  {
    id: 'solonclaw',
    name: 'SolonClaw',
    icon: '🦊',
    description: '代码分析和项目管理工具',
    enabled: true,
    version: '1.0.0'
  },
  {
    id: 'copilot',
    name: 'Code Copilot',
    icon: '🤖',
    description: 'AI 代码助手',
    enabled: true,
    version: '2.1.0'
  },
  {
    id: 'formatter',
    name: 'Code Formatter',
    icon: '✨',
    description: '代码格式化工具',
    enabled: false,
    version: '1.5.0'
  },
  {
    id: 'linter',
    name: 'Code Linter',
    icon: '🔍',
    description: '代码质量检查',
    enabled: true,
    version: '3.0.0'
  }
]);

const currentConversation = ref<Conversation>({
  id: 1,
  title: '新建对话',
  timestamp: new Date().toLocaleString(),
  status: 'active'
});

const conversations = ref<Conversation[]>([
  {
    id: 'SolonClaw',
    title: 'SolonClaw',
    timestamp: new Date().toLocaleString(),
    status: 'active',
    isPermanent: true,
    icon: '🦊'
  }
]);

// 初始化 SolonClaw 会话（如果不存在）
async function initSolonClawConversation() {
  const stored = await getAllConversations();
  const solonClawExists = stored.some(c => c.id === 'SolonClaw');
  if (!solonClawExists) {
    const solonClawConv: Conversation = {
      id: 'SolonClaw',
      title: 'SolonClaw',
      timestamp: new Date().toLocaleString(),
      status: 'active',
      isPermanent: true,
      icon: '🦊'
    };
    await saveConversation(solonClawConv);
    conversations.value.unshift(solonClawConv);
  }
}

async function newConversation() {
  const newConv: Conversation = {
    id: Date.now(),
    title: '新建对话',
    timestamp: new Date().toLocaleString(),
    status: 'active'
  };
  conversations.value.unshift(newConv);
  currentConversation.value = newConv;
  await saveConversation(newConv);
}

function selectConversation(conv: Conversation) {
  currentConversation.value = conv;
}

function togglePlugin(pluginId: string) {
  const plugin = plugins.value.find(p => p.id === pluginId);
  if (plugin) {
    plugin.enabled = !plugin.enabled;
  }
}

onMounted(async () => {
  // 初始化 SolonClaw 会话
  await initSolonClawConversation();

  // 从数据库加载会话列表
  const storedConversations = await getAllConversations();
  if (storedConversations.length > 0) {
    conversations.value = storedConversations as Conversation[];
  }
  selectConversation(conversations.value[0]);
});
</script>

<template>
  <div class="app-container">
    <Sidebar
      :conversations="conversations"
      :current-conversation="currentConversation"
      :plugins="plugins"
      @new-conversation="newConversation"
      @select-conversation="selectConversation"
      @toggle-plugin="togglePlugin"
    />
    <ChatView
      :current-conversation="currentConversation"
      :plugins="plugins"
    />
  </div>
</template>

<style>
/* 全局样式保持不变，在 App.vue 中定义 */
</style>
