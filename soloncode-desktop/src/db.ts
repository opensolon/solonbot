import Dexie, { Table } from 'dexie';

export interface DbMessage {
  id?: number;
  conversationId: string | number;
  role: string;
  timestamp: string;
  contents: string;
}

export interface DbConversation {
  id?: string | number;
  title: string;
  timestamp: string;
  status: string;
  isPermanent?: boolean;
  icon?: string;
}

class SolonCodeDatabase extends Dexie {
  messages!: Table<DbMessage>;
  conversations!: Table<DbConversation>;

  constructor() {
    super('SolonCodeDB');
    this.version(1).stores({
      messages: '++id, conversationId, timestamp',
      conversations: '++id, title, timestamp, status'
    });
  }
}

export const db = new SolonCodeDatabase();

export async function saveMessage(message: Omit<DbMessage, 'id'>): Promise<number> {
  return await db.messages.add(message);
}

export async function getMessagesByConversation(conversationId: string | number): Promise<DbMessage[]> {
  return await db.messages
    .where('conversationId')
    .equals(conversationId)
    .toArray();
}

export async function saveConversation(conversation: DbConversation): Promise<string | number> {
  // 使用字符串 ID 作为主键查找
  if (typeof conversation.id === 'string') {
    const existing = await db.conversations.toArray();
    const existingById = existing.find(c => c.id === conversation.id);
    if (existingById) {
      await db.conversations.delete(existingById.id as number);
      const newId = await db.conversations.add(conversation);
      return conversation.id;
    }
    const newId = await db.conversations.add(conversation);
    return conversation.id;
  }

  const existing = await db.conversations.where('id').equals(conversation.id as number).first();
  if (existing) {
    await db.conversations.update(conversation.id as number, conversation);
    return conversation.id;
  }
  return await db.conversations.add(conversation);
}

export async function getAllConversations(): Promise<DbConversation[]> {
  return await db.conversations.toArray();
}

export async function deleteConversation(id: string | number): Promise<void> {
  await db.messages.where('conversationId').equals(id).delete();
  if (typeof id === 'number') {
    await db.conversations.where('id').equals(id).delete();
  } else {
    const all = await db.conversations.toArray();
    const toDelete = all.filter(c => c.id === id);
    await Promise.all(toDelete.map(c => db.conversations.delete(c.id as number)));
  }
}

export async function updateConversation(id: string | number, updates: Partial<DbConversation>): Promise<void> {
  if (typeof id === 'number') {
    await db.conversations.where('id').equals(id).modify(updates);
  } else {
    const all = await db.conversations.toArray();
    const toUpdate = all.filter(c => c.id === id);
    await Promise.all(toUpdate.map(c => db.conversations.update(c.id as number, updates)));
  }
}
