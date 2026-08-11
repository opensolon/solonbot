import assert from 'node:assert/strict';
import test from 'node:test';
import { redactLogContent } from '../src/utils/logViewer.ts';

test('redacts credentials without hiding ordinary token usage', () => {
  const content = [
    'apiKey=super-secret',
    'Authorization: Bearer abc.def.ghi',
    'password="two word secret"',
    'key sk-abcdefghijklmnop',
    'Token: 26270',
  ].join('\n');

  const redacted = redactLogContent(content);
  assert.equal(redacted.includes('super-secret'), false);
  assert.equal(redacted.includes('abc.def.ghi'), false);
  assert.equal(redacted.includes('two word secret'), false);
  assert.equal(redacted.includes('sk-abcdefghijklmnop'), false);
  assert.equal(redacted.includes('Token: 26270'), true);
});
