import assert from 'node:assert/strict';
import test from 'node:test';
import { resolveProjectName } from '../src/utils/projectContext.ts';

const projects = [
  { id: 'C:\\work\\fund', name: '基金系统' },
  { id: 'C:\\work\\python-server', name: 'python-server' },
];

test('resolves the project name from the conversation workspace', () => {
  assert.equal(resolveProjectName(projects, 'C:\\work\\fund'), '基金系统');
});

test('does not fall back to an unrelated active project', () => {
  assert.equal(resolveProjectName(projects, 'C:\\work\\missing'), undefined);
  assert.equal(resolveProjectName(projects, null), undefined);
});
