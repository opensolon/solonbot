export type MessageType = 'user' | 'assistant' | 'reason' | 'action' | 'error';
export type ContentType = 'reason' | 'action' | 'text' | 'error';

export interface ContentItem {
  type: ContentType;
  text: string;
  toolName?: string;
  args?: any;
}

export interface Message {
  id: number;
  role: MessageType;
  timestamp: string;
  contents: ContentItem[];
}

export interface Conversation {
  id: string | number;
  title: string;
  timestamp: string;
  status: string;
  isPermanent?: boolean;
  icon?: string;
}

export interface Plugin {
  id: string;
  name: string;
  icon: string;
  description: string;
  enabled: boolean;
  version: string;
}

export type Theme = 'dark' | 'light';
