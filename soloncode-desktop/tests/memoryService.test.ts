import test from 'node:test';
import assert from 'node:assert/strict';
import { validateMemoryEntry } from '../src/services/memoryService.ts';

test('accepts a valid long-term memory entry', () => {
  assert.equal(validateMemoryEntry({ key: 'project-build', content: 'Use Maven 3.8', importance: 8 }), null);
});

test('rejects empty and out-of-range memory values', () => {
  assert.match(validateMemoryEntry({ key: '', content: 'x', importance: 5 }) || '', /Key/);
  assert.match(validateMemoryEntry({ key: 'x', content: '', importance: 5 }) || '', /内容/);
  assert.match(validateMemoryEntry({ key: 'x', content: 'y', importance: 11 }) || '', /1-10/);
});
