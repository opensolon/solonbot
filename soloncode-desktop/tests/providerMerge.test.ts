import assert from 'node:assert/strict';
import test from 'node:test';
import { mergeEditedProvidersWithLatest, mergeFetchedModelsIntoLatest } from '../src/utils/providerMerge.ts';

const latestProviders = [
  {
    id: 'target',
    type: 'openai',
    name: '用户刚修改的名称',
    apiUrl: 'https://api.example.com/v1',
    apiKey: 'new-key',
    model: 'model-a',
    enabled: true,
    contextLength: 32000,
  },
  {
    id: 'added-while-fetching',
    type: 'anthropic',
    name: '异步期间新增的供应商',
    apiUrl: 'https://another.example.com',
    apiKey: 'another-key',
    model: 'model-b',
    enabled: true,
  },
];

test('merges fetched models into the latest provider without replacing concurrent changes', () => {
  const result = mergeFetchedModelsIntoLatest(
    latestProviders,
    { apiUrl: 'https://api.example.com/v1/', type: 'openai' },
    [{ id: 'model-a', contextLength: 128000 }, { id: 'model-c' }],
  );

  assert.equal(result.changed, true);
  assert.equal(result.providers.length, 2);
  assert.equal(result.providers[0].name, '用户刚修改的名称');
  assert.equal(result.providers[0].apiKey, 'new-key');
  assert.equal(result.providers[0].contextLength, 128000);
  assert.deepEqual(result.providers[1], latestProviders[1]);
});

test('discards a stale fetch result after the target provider configuration changed', () => {
  const result = mergeFetchedModelsIntoLatest(
    latestProviders.map(provider => provider.id === 'target'
      ? { ...provider, apiUrl: 'https://new.example.com/v1' }
      : provider),
    { apiUrl: 'https://api.example.com/v1', type: 'openai' },
    [{ id: 'stale-model' }],
  );

  assert.equal(result.changed, false);
  assert.equal(result.providerId, '');
  assert.equal(result.providers.some(provider => provider.availableModels?.some(model => model.id === 'stale-model')), false);
});

test('three-way merges settings edits with asynchronous provider updates', () => {
  const base = [{
    id: 'target',
    type: 'openai',
    name: '原名称',
    apiUrl: 'https://api.example.com/v1',
    apiKey: 'old-key',
    model: 'model-a',
    availableModels: [{ id: 'model-a' }],
  }];
  const edited = [{ ...base[0], name: '用户修改的名称' }];
  const latest = [
    {
      ...base[0],
      availableModels: [{ id: 'model-a', contextLength: 128000 }, { id: 'model-c' }],
    },
    {
      id: 'discovered',
      type: 'anthropic',
      name: '后台新增',
      apiUrl: 'https://another.example.com',
      apiKey: '',
      model: 'model-b',
    },
  ];

  const merged = mergeEditedProvidersWithLatest(base, edited, latest);

  assert.equal(merged.length, 2);
  assert.equal(merged[0].name, '用户修改的名称');
  assert.deepEqual(merged[0].availableModels, latest[0].availableModels);
  assert.equal(merged[1].id, 'discovered');
});

test('does not resurrect a provider removed by the user while async data arrives', () => {
  const base = [latestProviders[0]];
  const latest = [{ ...latestProviders[0], availableModels: [{ id: 'model-c' }] }];

  assert.deepEqual(mergeEditedProvidersWithLatest(base, [], latest), []);
});
