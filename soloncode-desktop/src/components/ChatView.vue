<script setup lang="ts">
import {ref, onMounted, watch} from 'vue';
import axios from 'axios';
import {type Message, type Conversation, type Theme, type Plugin, type ContentItem, type ContentType} from '../types';
import {db, saveMessage, getMessagesByConversation} from '../db';
import ChatHeader from './ChatHeader.vue';
import ChatMessages from './ChatMessages.vue';
import ChatInput from './ChatInput.vue';

interface Props {
  currentConversation: Conversation;
  plugins: Plugin[];
}

interface Emits {
  (e: 'toggle-theme'): void;
}

const props = defineProps<Props>();
const emit = defineEmits<Emits>();

const currentTheme = ref<Theme>('dark');
const messages = ref<Message[]>([]);
const isLoading = ref(false);
const chatMessagesRef = ref<InstanceType<typeof ChatMessages>>();
const API_BASE_URL = '/cli';

function toggleTheme() {
  currentTheme.value = currentTheme.value === 'dark' ? 'light' : 'dark';
  document.documentElement.setAttribute('data-theme', currentTheme.value);
  localStorage.setItem('soloncode-theme', currentTheme.value);
  emit('toggle-theme');
}

function loadTheme() {
  const savedTheme = localStorage.getItem('soloncode-theme') as Theme | null;
  if (savedTheme) {
    currentTheme.value = savedTheme;
  } else {
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    currentTheme.value = prefersDark ? 'dark' : 'light';
  }
  document.documentElement.setAttribute('data-theme', currentTheme.value);
}

async function sendMessage(messageText: string) {
  const userMessage: Message = {
    id: Date.now(),
    role: 'user',
    timestamp: new Date().toLocaleTimeString(),
    contents: [{type: 'text', text: messageText}]
  };

  messages.value.push(userMessage);

  // 保存用户消息到数据库
  await saveMessage({
    conversationId: props.currentConversation.id,
    role: 'user',
    timestamp: userMessage.timestamp,
    contents: JSON.stringify(userMessage.contents)
  });

  isLoading.value = true;

  await chatMessagesRef.value?.scrollToBottom();

  try {
    const sessionId = props.currentConversation.id.toString();
    const url = `${API_BASE_URL}?input=${encodeURIComponent(messageText)}&m=stream`;

    const response = await fetch(url, {
      method: 'GET',
      headers: {
        'X-Session-Id': sessionId
      }
    });

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    const reader = response.body?.getReader();
    const decoder = new TextDecoder();
    const currentType = ref<ContentType>('');
    if (reader) {
      while (true) {
        const {done, value} = await reader.read();
        if (done) return;
        const chunk = decoder.decode(value, {stream: true});
        const lines = chunk.split('\n');
        for (const line of lines) {
          if (line.trim()) {
            try {
              let jsonStr = line.trim();
              if (jsonStr.startsWith('data:')) {
                jsonStr = jsonStr.substring(5).trim();
              }
              if (jsonStr === '[DONE]') {
                // 保存助手消息到数据库
                const assistantMsg = messages.value[messages.value.length - 1];
                if (assistantMsg && assistantMsg.role === 'assistant') {
                  await saveMessage({
                    conversationId: props.currentConversation.id,
                    role: 'assistant',
                    timestamp: assistantMsg.timestamp,
                    contents: JSON.stringify(assistantMsg.contents)
                  });
                }
                isLoading.value = false;
                await chatMessagesRef.value?.scrollToBottom();
                return;
              }
              const data = JSON.parse(jsonStr);
              const type = data.type as ContentType;
              let text = data.text || '';
              if (text === '') {
                continue;
              }
              if (currentType.value != type) {
                let isAddText = false;
                const addText = "\n```\n\n";
                if (currentType.value && (currentType.value == 'reason' || currentType.value == 'action')) {
                  isAddText = true;
                }
                currentType.value = type;
                if (type === 'action') {
                  text = "```md\n> ⚡ " + data?.toolName || '工具' + "\n" + JSON.stringify(data.args) + "\n" + text.substring(0, 5) + "...\n```\n\n";
                } else if (type === 'reason') {
                  text = "```md\n> 🧠\n" + text;
                } else {
                  text = "\n\n" + text;
                }
                if (isAddText) {
                  text = addText + text;
                }
              }
              console.log(text)
              if (text || data.toolName || data.args) {
                // 获取或创建助手消息
                const lastMsg = messages.value[messages.value.length - 1];
                if (!lastMsg || lastMsg.role !== 'assistant') {
                  const newMsg: Message = {
                    id: Date.now() + Math.floor(Math.random() * 1000),
                    role: 'assistant',
                    timestamp: new Date().toLocaleTimeString(),
                    contents: []
                  };
                  messages.value.push(newMsg);
                }
                const assistantMsg = messages.value[messages.value.length - 1];
                const lastContent = assistantMsg.contents[assistantMsg.contents.length - 1];
                if (lastContent) {
                  lastContent.text += text;
                } else {
                  assistantMsg.contents.push({type: 'text', text});
                }
                await chatMessagesRef.value?.scrollToBottom();
              }
            } catch (e) {
              console.warn('Failed to parse chunk:', line, e);
            }
          }
        }
      }
    }
  } catch (error) {
    console.error('Failed to send message:', error);
    const errorMessage: Message = {
      id: Date.now() + 1,
      role: 'error',
      timestamp: new Date().toLocaleTimeString(),
      contents: [{type: 'error', text: `请求失败: ${error instanceof Error ? error.message : '未知错误'}`}]
    };
    messages.value.push(errorMessage);

    // 保存错误消息到数据库
    await saveMessage({
      conversationId: props.currentConversation.id,
      role: 'error',
      timestamp: errorMessage.timestamp,
      contents: JSON.stringify(errorMessage.contents)
    });
  } finally {
    isLoading.value = false;
    await chatMessagesRef.value?.scrollToBottom();
  }
}

async function loadSolonClawMessages() {
  // 优先从数据库加载历史消息
  const storedMessages = await getMessagesByConversation('SolonClaw');

  if (storedMessages.length > 0) {
    messages.value = storedMessages.map(msg => ({
      ...msg,
      contents: JSON.parse(msg.contents)
    }));
  } else {
    messages.value = [
      {
        id: 1,
        role: 'assistant',
        timestamp: new Date().toLocaleTimeString(),
        contents: [{
          type: 'text',
          text: '🦊 SolonClaw 已启动\n\n这是一个强大的代码分析和管理工具。我可以帮助你：\n\n• 分析项目结构和依赖关系\n• 检测代码质量问题\n• 生成代码文档\n• 执行代码重构建议\n• 管理项目配置\n\n请告诉我你需要什么帮助？'
        }]
      }
    ];
  }
}

async function loadConversationMessages(convId: string | number) {
  const storedMessages = await getMessagesByConversation(convId);

  if (storedMessages.length > 0) {
    messages.value = storedMessages.map(msg => ({
      ...msg,
      contents: JSON.parse(msg.contents)
    }));
  } else {
    messages.value = [
      {
        id: 1,
        role: 'assistant',
        timestamp: new Date().toLocaleTimeString(),
        contents: [{
          type: 'text',
          text: '你好！我是 SolonCode 助手。有什么我可以帮助你的吗？'
        }]
      }
    ];
  }
}

watch(() => props.currentConversation.id, (newId) => {
  if (newId === 'SolonClaw' && props.currentConversation.isPermanent) {
    loadSolonClawMessages();
  } else {
    loadConversationMessages(newId);
  }
}, {immediate: true});

onMounted(() => {
  loadTheme();
});
</script>

<template>
  <main class="main-content">
    <ChatHeader
        :title="props.currentConversation.title"
        :status="props.currentConversation.status"
        :theme="currentTheme"
        @toggle-theme="toggleTheme"
    />
    <ChatMessages
        ref="chatMessagesRef"
        :messages="messages"
        :is-loading="isLoading"
        :theme="currentTheme"
    />
    <ChatInput @send="sendMessage"/>
  </main>
</template>

<style scoped>
.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
</style>
