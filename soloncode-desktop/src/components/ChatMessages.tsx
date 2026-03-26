import { useEffect, useRef, forwardRef, useImperativeHandle } from 'react';
import ReactMarkdown from 'react-markdown';
import { Prism as SyntaxHighlighter } from 'react-syntax-highlighter';
import { oneDark, oneLight } from 'react-syntax-highlighter/dist/esm/styles/prism';
import { Icon } from './common/Icon';
import type { Message, Theme } from '../types';
import './ChatMessages.css';

interface ChatMessagesProps {
  messages: Message[];
  isLoading: boolean;
  theme?: Theme;
}

export interface ChatMessagesRef {
  scrollToBottom: () => void;
}

export const ChatMessages = forwardRef<ChatMessagesRef, ChatMessagesProps>(
  ({ messages, isLoading, theme }, ref) => {
    const chatContainer = useRef<HTMLDivElement>(null);

    useImperativeHandle(ref, () => ({
      scrollToBottom
    }));

    function scrollToBottom() {
      if (chatContainer.current) {
        chatContainer.current.scrollTop = chatContainer.current.scrollHeight;
      }
    }

    useEffect(() => {
      scrollToBottom();
    }, [messages]);

    function getRoleLabel(role: string): string {
      const labels: Record<string, string> = {
        'user': '你',
        'assistant': '助手',
        'reason': '思考',
        'action': '执行',
        'error': '错误'
      };
      return labels[role] || role;
    }

    function getRoleIcon(role: string): 'user' | 'assistant' | 'bot' | 'warning' | 'error' {
      const icons: Record<string, 'user' | 'assistant' | 'bot' | 'warning' | 'error'> = {
        'user': 'user',
        'assistant': 'bot',
        'reason': 'assistant',
        'action': 'assistant',
        'error': 'error'
      };
      return icons[role] || 'bot';
    }

    return (
      <div className="chat-messages" ref={chatContainer}>
        {messages.length === 0 && !isLoading && (
          <div className="empty-messages">
            <Icon name="chat" size={48} className="empty-icon" />
            <span className="empty-text">开始对话</span>
          </div>
        )}

        {messages.map((message) => (
          <div
            key={message.id}
            className={`message ${message.role}`}
          >
            <div className="message-bubble">
              <div className="message-header">
                <Icon name={getRoleIcon(message.role)} size={12} />
                <span className="message-role">{getRoleLabel(message.role)}</span>
              </div>
              <div className="message-text">
                {message.contents.map((item, index) => (
                  <div key={index} className="content-item">
                    <ReactMarkdown
                      components={{
                        code({ node, inline, className, children, ...props }) {
                          const match = /language-(\w+)/.exec(className || '');
                          return !inline && match ? (
                            <SyntaxHighlighter
                              style={theme === 'dark' ? oneDark : oneLight}
                              language={match[1]}
                              PreTag="div"
                              {...props}
                            >
                              {String(children).replace(/\n$/, '')}
                            </SyntaxHighlighter>
                          ) : (
                            <code className={className} {...props}>
                              {children}
                            </code>
                          );
                        }
                      }}
                    >
                      {item.text}
                    </ReactMarkdown>
                  </div>
                ))}
              </div>
              <div className="message-time">{message.timestamp}</div>
            </div>
          </div>
        ))}

        {isLoading && (
          <div className="message assistant loading">
            <div className="message-bubble">
              <div className="message-header">
                <Icon name="bot" size={12} />
                <span className="message-role">助手</span>
              </div>
              <div className="loading-indicator">
                <span></span>
                <span></span>
                <span></span>
              </div>
            </div>
          </div>
        )}
      </div>
    );
  }
);
