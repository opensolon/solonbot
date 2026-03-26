import { useState, useEffect, useRef, useCallback } from 'react';
import type { Message, Conversation, Theme, Plugin, ContentItem, ContentType } from '../types';
import { saveMessage, getMessagesByConversation } from '../db';
import { ChatHeader } from './ChatHeader';
import { ChatMessages } from './ChatMessages';
import { ChatInput, type SendOptions } from './ChatInput';
import '../views/ChatPage.css';

interface ChatViewProps {
  currentConversation: Conversation;
  plugins: Plugin[];
}

export function ChatView({ currentConversation, plugins }: ChatViewProps) {
  const [currentTheme, setCurrentTheme] = useState<Theme>('dark');
  const [messages, setMessages] = useState<Message[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const chatMessagesRef = useRef<{ scrollToBottom: () => void } | null>(null);
  const API_BASE_URL = '/cli';

  function toggleTheme() {
    const newTheme = currentTheme === 'dark' ? 'light' : 'dark';
    setCurrentTheme(newTheme);
    document.documentElement.setAttribute('data-theme', newTheme);
    localStorage.setItem('soloncode-theme', newTheme);
  }

  function loadTheme() {
    const savedTheme = localStorage.getItem('soloncode-theme') as Theme | null;
    if (savedTheme) {
      setCurrentTheme(savedTheme);
    } else {
      const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
      setCurrentTheme(prefersDark ? 'dark' : 'light');
    }
    const themeToSet = savedTheme || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
    document.documentElement.setAttribute('data-theme', themeToSet);
  }

  const sendMessage = useCallback(async (messageText: string, options: SendOptions) => {
    // 构建包含上下文的消息
    let fullMessage = messageText;

    // 如果有上下文引用，添加到消息中
    if (options.contexts.length > 0) {
      const contextStr = options.contexts.map(c => `[${c.name}]`).join(' ');
      fullMessage = `${contextStr}\n\n${messageText}`;
    }

    const userMessage: Message = {
      id: Date.now(),
      role: 'user',
      timestamp: new Date().toLocaleTimeString(),
      contents: [{ type: 'text', text: fullMessage }]
    };

    setMessages(prev => [...prev, userMessage]);

    await saveMessage({
      conversationId: currentConversation.id,
      role: 'user',
      timestamp: userMessage.timestamp,
      contents: JSON.stringify(userMessage.contents)
    });

    setIsLoading(true);

    chatMessagesRef.current?.scrollToBottom();

    try {
      const sessionId = currentConversation.id.toString();
      const url = `${API_BASE_URL}?input=${encodeURIComponent(fullMessage)}&m=stream&model=${options.model}&agent=${options.agent}`;

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
      let currentType: ContentType = '';
      let assistantMsgId = Date.now() + Math.floor(Math.random() * 1000);

      if (reader) {
        while (true) {
          const { done, value } = await reader.read();
          if (done) break;

          const chunk = decoder.decode(value, { stream: true });
          const lines = chunk.split('\n');

          for (const line of lines) {
            if (line.trim()) {
              try {
                let jsonStr = line.trim();
                if (jsonStr.startsWith('data:')) {
                  jsonStr = jsonStr.substring(5).trim();
                }
                if (jsonStr === '[DONE]') {
                  setIsLoading(false);
                  chatMessagesRef.current?.scrollToBottom();
                  return;
                }

                const data = JSON.parse(jsonStr);
                const type = data.type as ContentType;
                let text = data.text || '';

                if (text === '') continue;

                if (currentType !== type) {
                  let isAddText = false;
                  const addText = "\n```\n\n";
                  if (currentType && (currentType === 'reason' || currentType === 'action')) {
                    isAddText = true;
                  }
                  currentType = type;

                  if (type === 'action') {
                    text = "```md\n> ⚡ " + (data?.toolName || '工具') + "\n" + JSON.stringify(data.args) + "\n" + text.substring(0, 5) + "...\n```\n\n";
                  } else if (type === 'reason') {
                    text = "```md\n> 🧠\n" + text;
                  } else {
                    text = "\n\n" + text;
                  }

                  if (isAddText) {
                    text = addText + text;
                  }
                }

                setMessages(prev => {
                  const lastMsg = prev[prev.length - 1];
                  if (!lastMsg || lastMsg.role !== 'assistant') {
                    const newMsg: Message = {
                      id: assistantMsgId,
                      role: 'assistant',
                      timestamp: new Date().toLocaleTimeString(),
                      contents: [{ type: 'text', text }]
                    };
                    return [...prev, newMsg];
                  } else {
                    const updated = [...prev];
                    const assistantMsg = updated[updated.length - 1];
                    const lastContent = assistantMsg.contents[assistantMsg.contents.length - 1];
                    if (lastContent) {
                      lastContent.text += text;
                    }
                    return updated;
                  }
                });

                chatMessagesRef.current?.scrollToBottom();
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
        contents: [{ type: 'error', text: `请求失败: ${error instanceof Error ? error.message : '未知错误'}` }]
      };
      setMessages(prev => [...prev, errorMessage]);

      await saveMessage({
        conversationId: currentConversation.id,
        role: 'error',
        timestamp: errorMessage.timestamp,
        contents: JSON.stringify(errorMessage.contents)
      });
    } finally {
      setIsLoading(false);
      chatMessagesRef.current?.scrollToBottom();
    }
  }, [currentConversation]);

  async function loadSolonClawMessages() {
    const storedMessages = await getMessagesByConversation('SolonClaw');

    if (storedMessages.length > 0) {
      setMessages(storedMessages.map(msg => ({
        ...msg,
        contents: JSON.parse(msg.contents)
      })));
    } else {
      setMessages([{
        id: 1,
        role: 'assistant',
        timestamp: new Date().toLocaleTimeString(),
        contents: [{
          type: 'text',
          text: '🦊 SolonClaw 已启动\n\n这是一个强大的代码分析和管理工具。我可以帮助你：\n\n• 分析项目结构和依赖关系\n• 检测代码质量问题\n• 生成代码文档\n• 执行代码重构建议\n• 管理项目配置\n\n请告诉我你需要什么帮助？'
        }]
      }]);
    }
  }

  async function loadConversationMessages(convId: string | number) {
    const storedMessages = await getMessagesByConversation(convId);

    if (storedMessages.length > 0) {
      setMessages(storedMessages.map(msg => ({
        ...msg,
        contents: JSON.parse(msg.contents)
      })));
    } else {
      setMessages([{
        id: 1,
        role: 'assistant',
        timestamp: new Date().toLocaleTimeString(),
        contents: [{
          type: 'text',
          text: '你好！我是 SolonCode 助手。有什么我可以帮助你的吗？'
        }]
      }]);
    }
  }

  useEffect(() => {
    loadTheme();
  }, []);

  useEffect(() => {
    if (currentConversation.id === 'SolonClaw' && currentConversation.isPermanent) {
      loadSolonClawMessages();
    } else {
      loadConversationMessages(currentConversation.id);
    }
  }, [currentConversation.id]);

  return (
    <main className="main-content">
      <ChatHeader
        title={currentConversation.title}
        status={currentConversation.status}
        theme={currentTheme}
        onToggleTheme={toggleTheme}
      />
      <ChatMessages
        ref={chatMessagesRef}
        messages={messages}
        isLoading={isLoading}
        theme={currentTheme}
      />
      <ChatInput onSend={sendMessage} />
    </main>
  );
}
