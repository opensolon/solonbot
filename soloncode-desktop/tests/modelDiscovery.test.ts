import assert from 'node:assert/strict';
import test from 'node:test';
import { requestDesktopModels } from '../src/services/modelDiscoveryService.ts';

test('uses JSON POST for model discovery when supported', async () => {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  const fetcher = (async (url: string | URL | Request, init?: RequestInit) => {
    calls.push({ url: String(url), init });
    return new Response('{}', { status: 200 });
  }) as typeof fetch;

  const response = await requestDesktopModels({
    backendPort: 4808,
    apiUrl: 'https://api.example.com/v1',
    apiKey: 'secret',
    provider: 'openai',
    model: 'demo',
  }, fetcher);

  assert.equal(response.status, 200);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].init?.method, 'POST');
  assert.equal(calls[0].url.includes('secret'), false);
});

test('falls back to the legacy GET contract only after a 405', async () => {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  const fetcher = (async (url: string | URL | Request, init?: RequestInit) => {
    calls.push({ url: String(url), init });
    return calls.length === 1
      ? new Response(null, { status: 405 })
      : new Response('{"code":200,"data":[]}', { status: 200 });
  }) as typeof fetch;

  const response = await requestDesktopModels({
    backendPort: 4808,
    apiUrl: 'https://api.example.com/v1',
    apiKey: 'legacy-secret',
    provider: 'openai',
  }, fetcher);

  assert.equal(response.status, 200);
  assert.equal(calls.length, 2);
  assert.equal(calls[0].init?.method, 'POST');
  assert.equal(calls[1].init?.method, 'GET');
  assert.match(calls[1].url, /apiKey=legacy-secret/);
});
