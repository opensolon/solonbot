import { invoke } from '@tauri-apps/api/core';

function providerCredentialId(providerId: string): string {
  const normalized = providerId.replace(/[^a-zA-Z0-9._:-]/g, '_').slice(0, 96);
  if (!normalized) throw new Error('模型供应商标识无效');
  return `provider:${normalized}`;
}

export const credentialService = {
  async getProviderApiKey(providerId: string): Promise<string | undefined> {
    try {
      const value = await invoke<string | null>('credential_get', { id: providerCredentialId(providerId) });
      return value || undefined;
    } catch {
      return undefined;
    }
  },

  async setProviderApiKey(providerId: string, value: string): Promise<boolean> {
    try {
      const id = providerCredentialId(providerId);
      if (value) await invoke('credential_set', { id, value });
      else await invoke('credential_delete', { id });
      return true;
    } catch {
      return false;
    }
  },
};
