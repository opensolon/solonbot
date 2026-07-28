import assert from 'node:assert/strict';
import test from 'node:test';
import { canRememberToolPermission, redactCommandPreview } from '../src/utils/permissionPolicy.ts';

test('never remembers shell-like tools', () => {
  assert.equal(canRememberToolPermission('bash'), false);
  assert.equal(canRememberToolPermission('agent/powershell'), false);
  assert.equal(canRememberToolPermission('file_edit'), true);
});

test('redacts common secrets from audit previews', () => {
  const preview = redactCommandPreview('curl -H "Authorization: Bearer abc.def" --token top-secret api_key=123');
  assert.ok(preview);
  assert.equal(preview!.includes('abc.def'), false);
  assert.equal(preview!.includes('top-secret'), false);
  assert.equal(preview!.includes('api_key=123'), false);
});
