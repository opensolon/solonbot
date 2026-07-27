export interface ProviderModelInfo {
  id: string;
  ownedBy?: string;
  contextLength?: number;
}

export interface MergeableProvider {
  id: string;
  type: string;
  apiUrl: string;
  model: string;
  contextLength?: number;
  availableModels?: ProviderModelInfo[];
}

function normalizeBaseUrl(value?: string) {
  return (value || '').trim().replace(/\/+$/, '');
}

function mergeModels(
  current: ProviderModelInfo[] | undefined,
  fetched: readonly ProviderModelInfo[],
  selectedModel?: string,
) {
  const models = new Map<string, ProviderModelInfo>();
  for (const model of current || []) {
    if (model.id) models.set(model.id, model);
  }
  for (const model of fetched) {
    if (model.id) models.set(model.id, { ...models.get(model.id), ...model });
  }
  if (selectedModel && !models.has(selectedModel)) {
    models.set(selectedModel, { id: selectedModel });
  }
  return Array.from(models.values());
}

function valuesEqual(left: unknown, right: unknown) {
  if (Object.is(left, right)) return true;
  if (left == null || right == null || typeof left !== 'object' || typeof right !== 'object') {
    return false;
  }
  return JSON.stringify(left) === JSON.stringify(right);
}

/**
 * 三方合并设置弹窗中的供应商编辑：
 * - edited 相对 base 改过的字段视为用户修改；
 * - 未改字段采用 latest，保留异步模型发现等后台更新；
 * - 用户删除与新增的供应商均按 edited 为准，同时保留后台并发新增项。
 */
export function mergeEditedProvidersWithLatest<T extends { id: string }>(
  baseProviders: readonly T[],
  editedProviders: readonly T[],
  latestProviders: readonly T[],
): T[] {
  const baseById = new Map(baseProviders.map(provider => [provider.id, provider]));
  const latestById = new Map(latestProviders.map(provider => [provider.id, provider]));
  const editedIds = new Set(editedProviders.map(provider => provider.id));
  const merged: T[] = [];

  for (const edited of editedProviders) {
    const base = baseById.get(edited.id);
    const latest = latestById.get(edited.id);
    if (!base || !latest) {
      merged.push(edited);
      continue;
    }

    const provider = { ...latest } as T;
    const keys = new Set<keyof T>([
      ...(Object.keys(base) as Array<keyof T>),
      ...(Object.keys(edited) as Array<keyof T>),
    ]);
    for (const key of keys) {
      if (!valuesEqual(edited[key], base[key])) {
        provider[key] = edited[key];
      }
    }
    merged.push(provider);
  }

  for (const latest of latestProviders) {
    if (!baseById.has(latest.id) && !editedIds.has(latest.id)) {
      merged.push(latest);
    }
  }
  return merged;
}

export function mergeFetchedModelsIntoLatest<T extends MergeableProvider>(
  latestProviders: readonly T[],
  target: { apiUrl: string; type: string },
  fetchedModels: readonly ProviderModelInfo[],
): { providers: T[]; providerId: string; changed: boolean } {
  const targetUrl = normalizeBaseUrl(target.apiUrl);
  const index = latestProviders.findIndex(provider => (
    normalizeBaseUrl(provider.apiUrl) === targetUrl && provider.type === target.type
  ));
  if (index < 0) {
    return { providers: latestProviders.slice(), providerId: '', changed: false };
  }

  const current = latestProviders[index];
  const availableModels = mergeModels(current.availableModels, fetchedModels, current.model);
  const selected = availableModels.find(model => model.id === current.model);
  const updated = {
    ...current,
    availableModels: availableModels.length > 0 ? availableModels : undefined,
    contextLength: selected?.contextLength || current.contextLength || 128000,
  } as T;
  const changed = JSON.stringify(current) !== JSON.stringify(updated);
  if (!changed) {
    return { providers: latestProviders.slice(), providerId: current.id, changed: false };
  }

  const providers = [...latestProviders];
  providers[index] = updated;
  return { providers, providerId: current.id, changed: true };
}
